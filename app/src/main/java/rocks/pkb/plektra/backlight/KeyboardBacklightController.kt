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

    /** Unverified build, but a recovery record from a verified build of this device exists. */
    FirmwareChanged,
    Unsupported
}

/** How the vendor timeout shown in the UI was obtained. */
internal sealed class BacklightObservation {
    abstract val timeout: String?
    val followsDisplay: Boolean get() = timeout == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY

    data class Fresh(override val timeout: String) : BacklightObservation()

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

    /** The recovery record could not be made durable, so nothing was changed. */
    RecordFailed,

    /** The write was rejected; the read-back shows the previous value. */
    WriteFailed,

    /** The value after the write could not be read; the recovery record is kept. */
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
 * Every change is followed by a read-back, and only read values update the shown state.
 */
internal class KeyboardBacklightController(
    private val shell: BacklightShell,
    private val store: BacklightRecoveryStore,
    private val binding: () -> BacklightDeviceBinding,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()

    /** Cheap, local check for whether the setting should be offered on this device. */
    fun isOffered(): Boolean {
        val currentBinding = binding()
        return UnihertzBacklightFirmware.isVerified(currentBinding.buildFingerprint) ||
            boundRecord(currentBinding) != null
    }

    suspend fun refresh(): BacklightSnapshot = operate { context ->
        context.snapshot(context.readAndReconcile().outcome)
    }

    suspend fun enable(): BacklightSnapshot = operate { context ->
        val read = context.readAndReconcile()
        val current = read.timeout ?: return@operate context.snapshot(read.outcome)
        var record = context.record
        if (current == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY) {
            return@operate context.snapshot(
                if (record != null) BacklightOutcome.Enabled else BacklightOutcome.AlreadyFollowingWithoutOriginal
            )
        }
        // A record left by an interrupted enable keeps its original; it is never overwritten.
        if (record == null) {
            if (!UnihertzBacklightVendorCommand.isRestorableTimeout(current)) {
                return@operate context.snapshot(BacklightOutcome.UnrestorableCurrentValue)
            }
            record = BacklightRecoveryRecord(
                binding = context.binding,
                originalTimeout = current,
                phase = BacklightRecoveryRecord.Phase.Enabling,
                createdAtMillis = clock()
            )
            if (!store.writeRecord(record)) return@operate context.snapshot(BacklightOutcome.RecordFailed)
            context.record = record
        }
        withContext(NonCancellable) {
            shell.run(UnihertzBacklightVendorCommand.WriteTimeout.followDisplay())
            val after = context.readAndReconcile()
            context.snapshot(
                when (after.timeout) {
                    UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY -> BacklightOutcome.Enabled
                    null -> BacklightOutcome.Uncertain
                    else -> BacklightOutcome.WriteFailed
                }
            )
        }
    }

    suspend fun disable(): BacklightSnapshot = operate { context ->
        val read = context.readAndReconcile()
        val current = read.timeout ?: return@operate context.snapshot(read.outcome)
        val record = context.record
        if (current != UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY) {
            return@operate context.snapshot(BacklightOutcome.Disabled)
        }
        val restore = record?.let { UnihertzBacklightVendorCommand.WriteTimeout.restore(it.originalTimeout) }
            ?: return@operate context.snapshot(BacklightOutcome.NoOriginalToRestore)
        withContext(NonCancellable) {
            shell.run(restore)
            val after = context.readAndReconcile()
            context.snapshot(
                when (after.timeout) {
                    record.originalTimeout -> BacklightOutcome.Disabled
                    null -> BacklightOutcome.Uncertain
                    else -> BacklightOutcome.WriteFailed
                }
            )
        }
    }

    private suspend fun operate(block: suspend (OperationContext) -> BacklightSnapshot): BacklightSnapshot =
        mutex.withLock {
            withContext(ioDispatcher) {
                val currentBinding = binding()
                val context = OperationContext(currentBinding, boundRecord(currentBinding))
                block(context)
            }
        }

    private fun boundRecord(currentBinding: BacklightDeviceBinding): BacklightRecoveryRecord? =
        store.readRecord()?.takeIf { it.binding.deviceKey == currentBinding.deviceKey }

    private fun support(currentBinding: BacklightDeviceBinding, record: BacklightRecoveryRecord?) = when {
        UnihertzBacklightFirmware.isVerified(currentBinding.buildFingerprint) -> BacklightSupport.Supported
        record != null -> BacklightSupport.FirmwareChanged
        else -> BacklightSupport.Unsupported
    }

    private data class ReadResult(val timeout: String?, val outcome: BacklightOutcome)

    private inner class OperationContext(
        val binding: BacklightDeviceBinding,
        var record: BacklightRecoveryRecord?
    ) {
        private var fresh: String? = null

        /**
         * Reads the timeout after the guards pass and settles the recovery record against it.
         * The record is removed when the original value is in effect again, or when someone
         * replaced the verified `-1` with another value outside Plektra.
         */
        fun readAndReconcile(): ReadResult {
            fresh = null
            if (support(binding, record) != BacklightSupport.Supported) {
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
            fresh = timeout
            store.writeVerified(BacklightVerifiedReading(binding, timeout, clock()))
            reconcile(timeout)
            return ReadResult(timeout, BacklightOutcome.None)
        }

        private fun reconcile(timeout: String) {
            val current = record ?: return
            val followsDisplay = timeout == UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY
            when {
                timeout == current.originalTimeout ||
                    (!followsDisplay && current.phase == BacklightRecoveryRecord.Phase.Enabled) -> {
                    if (store.clearRecord()) record = null
                }
                followsDisplay && current.phase == BacklightRecoveryRecord.Phase.Enabling -> {
                    val enabled = current.copy(phase = BacklightRecoveryRecord.Phase.Enabled)
                    // If this fails, the Enabling record still restores the same original.
                    if (store.writeRecord(enabled)) record = enabled
                }
            }
        }

        fun snapshot(outcome: BacklightOutcome): BacklightSnapshot {
            val shizukuStatus = if (fresh != null) ShizukuStatus.Connected else shell.status()
            val observation = fresh?.let { BacklightObservation.Fresh(it) }
                ?: store.readVerified()
                    ?.takeIf { it.binding == binding }
                    ?.let { BacklightObservation.LastVerified(it.timeout, it.verifiedAtMillis) }
                ?: BacklightObservation.Unknown
            return BacklightSnapshot(support(binding, record), shizukuStatus, observation, record, outcome)
        }
    }
}
