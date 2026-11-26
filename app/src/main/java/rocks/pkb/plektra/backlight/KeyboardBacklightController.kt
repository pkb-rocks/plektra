package rocks.pkb.plektra.backlight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.ShizukuStatus

/** Whether Plektra may talk to the vendor service on this firmware build. */
internal enum class BacklightSupport {
    Supported,

    /** Unverified build, but a recovery record from another build of this device exists. */
    FirmwareChanged,
    Unsupported
}

/** How the vendor timeout shown in the UI was obtained. */
internal sealed class BacklightObservation {
    abstract val timeout: String?
    val followsDisplay: Boolean get() = timeout == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY

    /** Read during the operation that produced the snapshot, at [readAtMillis]. */
    data class Fresh(override val timeout: String, val readAtMillis: Long) : BacklightObservation()

    /** Last value read on this device and build; it may have changed since. */
    data class LastVerified(override val timeout: String, val verifiedAtMillis: Long) : BacklightObservation()

    object Unknown : BacklightObservation() {
        override val timeout: String? = null
    }
}

internal enum class BacklightOutcome {
    None,
    Enabled,
    Disabled,

    /** The timeout already was `-1` and Plektra holds no value to restore. */
    AlreadyFollowingWithoutOriginal,

    /** Turning it off needs a recorded original timeout, and none exists. */
    NoOriginalToRestore,

    /** The current timeout is not a plain value that could be restored later. */
    UnrestorableCurrentValue,
    ShizukuUnavailable,
    ServiceUnverified,
    UnsupportedFirmware,
    ReadFailed,

    /** The recovery record could not be saved, updated or removed, so nothing was changed. */
    RecordFailed,

    /** The write completed but the read-back shows another value. */
    WriteFailed,

    /**
     * A write was not confirmed and may still take effect. The recovery record is kept and no
     * further write is made until the value is read back or the phone has restarted.
     */
    Uncertain
}

internal data class BacklightSnapshot(
    val support: BacklightSupport,
    val shizuku: ShizukuStatus,
    val observation: BacklightObservation,
    val recovery: BacklightRecoveryRecord?,
    val outcome: BacklightOutcome = BacklightOutcome.None
) {
    val canChange: Boolean
        get() = support == BacklightSupport.Supported && shizuku == ShizukuStatus.Connected
}

/**
 * Switches the Unihertz keyboard backlight between the vendor timeout and `-1`, with which the
 * vendor light controller keeps the light on until the display turns off and lights it again when
 * the display turns on. Plektra runs nothing in the background for it.
 *
 * The original timeout is persisted before the first write and restored exactly on disable.
 * Every write is announced in the record before it is sent; only a completed reply or a read-back
 * of the written value confirms it. Only read values update the shown state.
 */
internal class KeyboardBacklightController(
    private val shell: BacklightShell,
    private val store: BacklightRecoveryStore,
    private val binding: () -> BacklightDeviceBinding,
    private val bootCount: () -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()

    /** Cheap, local check for whether the setting should be offered on this device. */
    fun isOffered(): Boolean {
        val currentBinding = binding()
        return UnihertzBacklightFirmware.isVerified(currentBinding.buildFingerprint) ||
            sameDeviceRecord(currentBinding) != null
    }

    suspend fun refresh(): BacklightSnapshot = operate { context ->
        context.snapshot(context.readAndReconcile().outcome)
    }

    suspend fun enable(): BacklightSnapshot = operate { context ->
        val read = context.readAndReconcile()
        val current = read.timeout ?: return@operate context.snapshot(read.outcome)
        context.blockingOutcome()?.let { return@operate context.snapshot(it) }
        val existing = context.record
        if (current == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY) {
            return@operate context.snapshot(
                if (existing != null) BacklightOutcome.Enabled else BacklightOutcome.AlreadyFollowingWithoutOriginal
            )
        }
        // A record left by an interrupted enable keeps its original; it is never overwritten.
        val base = existing ?: run {
            if (!UnihertzBacklightVendorCommand.isRestorableTimeout(current)) {
                return@operate context.snapshot(BacklightOutcome.UnrestorableCurrentValue)
            }
            BacklightRecoveryRecord(
                binding = context.binding,
                originalTimeout = current,
                phase = BacklightRecoveryRecord.Phase.Enabling,
                createdAtMillis = clock()
            )
        }
        context.write(base, UnihertzBacklightVendorCommand.WriteTimeout.followDisplay())
    }

    suspend fun disable(): BacklightSnapshot = operate { context ->
        val read = context.readAndReconcile()
        val current = read.timeout ?: return@operate context.snapshot(read.outcome)
        context.blockingOutcome()?.let { return@operate context.snapshot(it) }
        if (current != UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY) {
            return@operate context.snapshot(BacklightOutcome.Disabled)
        }
        val record = context.record
        val restore = record?.let { UnihertzBacklightVendorCommand.WriteTimeout.restore(it.originalTimeout) }
            ?: return@operate context.snapshot(BacklightOutcome.NoOriginalToRestore)
        context.write(record, restore)
    }

    private suspend fun operate(block: suspend (OperationContext) -> BacklightSnapshot): BacklightSnapshot =
        mutex.withLock {
            withContext(ioDispatcher) {
                val currentBinding = binding()
                block(OperationContext(currentBinding, trustedRecord(currentBinding)))
            }
        }

    /** Only a record from this device and this build may drive a write. */
    private fun trustedRecord(currentBinding: BacklightDeviceBinding): BacklightRecoveryRecord? =
        store.readRecord()?.takeIf { it.binding == currentBinding }

    private fun sameDeviceRecord(currentBinding: BacklightDeviceBinding): BacklightRecoveryRecord? =
        store.readRecord()?.takeIf { it.binding.deviceKey == currentBinding.deviceKey }

    private fun support(currentBinding: BacklightDeviceBinding) = when {
        UnihertzBacklightFirmware.isVerified(currentBinding.buildFingerprint) -> BacklightSupport.Supported
        sameDeviceRecord(currentBinding) != null -> BacklightSupport.FirmwareChanged
        else -> BacklightSupport.Unsupported
    }

    private data class ReadResult(val timeout: String?, val outcome: BacklightOutcome)

    private inner class OperationContext(
        val binding: BacklightDeviceBinding,
        var record: BacklightRecoveryRecord?
    ) {
        private var fresh: BacklightObservation.Fresh? = null

        /** Set when the record no longer matches the device state and could not be updated. */
        private var recordOutOfDate = false

        /** A write that could not be ruled out, or a record that could not be updated, blocks writes. */
        fun blockingOutcome(): BacklightOutcome? = when {
            record?.unconfirmedWrite != null -> BacklightOutcome.Uncertain
            recordOutOfDate -> BacklightOutcome.RecordFailed
            else -> null
        }

        /**
         * Reads the timeout after the guards pass and settles the recovery record against it.
         */
        fun readAndReconcile(): ReadResult {
            fresh = null
            if (support(binding) != BacklightSupport.Supported) {
                return ReadResult(null, BacklightOutcome.UnsupportedFirmware)
            }
            if (shell.status() != ShizukuStatus.Connected) return ReadResult(null, BacklightOutcome.ShizukuUnavailable)
            val services = shell.run(UnihertzBacklightVendorCommand.ListServices)
            if (!services.succeeded ||
                !UnihertzBacklightVendorCommand.serviceListShowsVerifiedInterface(services.output)
            ) {
                return ReadResult(null, BacklightOutcome.ServiceUnverified)
            }
            val result = shell.run(UnihertzBacklightVendorCommand.ReadTimeout)
            val timeout = result.takeIf { it.succeeded }?.let { UnihertzVendorParcel.parseString(it.output) }
                ?: return ReadResult(null, BacklightOutcome.ReadFailed)
            val now = clock()
            fresh = BacklightObservation.Fresh(timeout, now)
            store.writeVerified(BacklightVerifiedReading(binding, timeout, now))
            reconcile(timeout)
            return ReadResult(timeout, BacklightOutcome.None)
        }

        /**
         * While a write is unconfirmed, the record is only ever kept or narrowed: the entry is
         * cleared when its value is read back or the phone has restarted since. Without one, the
         * record is removed when the original value is in effect again, or when someone replaced
         * the verified `-1` with another value outside Plektra.
         */
        private fun reconcile(timeout: String) {
            var current = record ?: return
            val followsDisplay = timeout == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY
            current.unconfirmedWrite?.let { unconfirmed ->
                val landed = timeout == unconfirmed.value
                val rebooted = unconfirmed.bootCount != null && bootCount()?.let { it != unconfirmed.bootCount } == true
                if (!landed && !rebooted) return
                val settled = current.copy(
                    unconfirmedWrite = null,
                    phase = if (landed && followsDisplay) BacklightRecoveryRecord.Phase.Enabled else current.phase
                )
                if (!store.writeRecord(settled)) {
                    recordOutOfDate = true
                    return
                }
                current = settled
                record = settled
            }
            when {
                timeout == current.originalTimeout ||
                    (!followsDisplay && current.phase == BacklightRecoveryRecord.Phase.Enabled) -> {
                    if (store.clearRecord()) record = null else recordOutOfDate = true
                }
                followsDisplay && current.phase == BacklightRecoveryRecord.Phase.Enabling -> {
                    val enabled = current.copy(phase = BacklightRecoveryRecord.Phase.Enabled)
                    // If this fails, the Enabling record still restores the same original.
                    if (store.writeRecord(enabled)) record = enabled
                }
            }
        }

        /**
         * Announces [command] in the record, sends it and reads back. Only a completed `service
         * call` with a reply parcel proves the vendor call has returned; anything else keeps the
         * announcement so a late completion cannot strand the device without its original.
         */
        suspend fun write(
            base: BacklightRecoveryRecord,
            command: UnihertzBacklightVendorCommand.WriteTimeout
        ): BacklightSnapshot {
            val target = command.argv().last()
            val announced = base.copy(
                unconfirmedWrite = BacklightRecoveryRecord.UnconfirmedWrite(target, bootCount())
            )
            if (!store.writeRecord(announced)) return snapshot(BacklightOutcome.RecordFailed)
            record = announced
            return withContext(NonCancellable) {
                val result = shell.run(command)
                val returned = result.succeeded && UnihertzVendorParcel.hasReply(result.output)
                if (returned) {
                    val settled = announced.copy(unconfirmedWrite = null)
                    if (store.writeRecord(settled)) record = settled
                }
                val after = readAndReconcile()
                snapshot(
                    when {
                        after.timeout == target -> if (target == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY) {
                            BacklightOutcome.Enabled
                        } else {
                            BacklightOutcome.Disabled
                        }
                        record?.unconfirmedWrite != null || after.timeout == null -> BacklightOutcome.Uncertain
                        else -> BacklightOutcome.WriteFailed
                    }
                )
            }
        }

        fun snapshot(outcome: BacklightOutcome): BacklightSnapshot {
            val shizukuStatus = if (fresh != null) ShizukuStatus.Connected else shell.status()
            val observation = fresh
                ?: store.readVerified()
                    ?.takeIf { it.binding == binding }
                    ?.let { BacklightObservation.LastVerified(it.timeout, it.verifiedAtMillis) }
                ?: BacklightObservation.Unknown
            val support = support(binding)
            // After a firmware change the earlier record is shown, but never used for a write.
            val shown = record ?: sameDeviceRecord(binding).takeIf { support == BacklightSupport.FirmwareChanged }
            return BacklightSnapshot(support, shizukuStatus, observation, shown, outcome)
        }
    }
}
