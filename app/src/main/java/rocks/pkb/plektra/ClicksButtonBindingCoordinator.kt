package rocks.pkb.plektra

import android.content.Context

/** Writes one firmware button remap; [onComplete] runs after the written state was published. */
internal fun interface ClicksButtonRemapWriter {
    fun writeButtonRemap(command: Int, output: ByteArray, onComplete: (Boolean) -> Unit)
}

internal interface ClicksButtonBindingStore {
    fun desired(target: ClicksButtonBindingTarget): ClicksDesiredButtonBinding?
    fun setDesired(target: ClicksButtonBindingTarget, binding: ClicksDesiredButtonBinding)
    fun markApplied(target: ClicksButtonBindingTarget, output: ByteArray)
}

internal class SettingsClicksButtonBindingStore(private val context: Context) : ClicksButtonBindingStore {
    override fun desired(target: ClicksButtonBindingTarget) =
        SettingsManager.getClicksDesiredButtonBinding(context, target)

    override fun setDesired(target: ClicksButtonBindingTarget, binding: ClicksDesiredButtonBinding) =
        SettingsManager.setClicksDesiredButtonBinding(context, target, binding)

    override fun markApplied(target: ClicksButtonBindingTarget, output: ByteArray) =
        SettingsManager.markClicksButtonBindingApplied(context, target, output)
}

/**
 * Brings pending Plektra button selections onto the keyboard, one firmware write per target at a
 * time.
 *
 * While a write is in flight for a target, the firmware state of that target is about to change.
 * A selection is therefore only marked as applied once no older write can still replace it; the
 * completion of the older write reconciles the latest selection instead.
 *
 * A failed write can leave the firmware changed without a published state: the GATT client writes
 * the remap bytes and the enable flags in two steps and publishes only after both succeeded. The
 * published output of that target is therefore not trusted to confirm a selection until the
 * target was written successfully again or the keyboard state was read again. Every complete read
 * starts by publishing an unvalidated session, so observing one marks the end of that doubt.
 */
internal class ClicksButtonBindingCoordinator(
    private val store: ClicksButtonBindingStore,
    private val keyboardState: () -> ClicksPowerKeyboardState,
    private val writer: () -> ClicksButtonRemapWriter?,
    /** Runs after a stored selection or its pending state changed. */
    private val onBindingsChanged: () -> Unit = {}
) {
    private val writesInProgress = mutableSetOf<ClicksButtonBindingTarget>()
    private val callbacks = mutableMapOf<ClicksButtonBindingTarget, (Boolean) -> Unit>()
    // A rejected write is retried on the next connection or selection instead of in a loop.
    private val failedTargets = mutableSetOf<ClicksButtonBindingTarget>()
    private val unverifiedTargets = mutableSetOf<ClicksButtonBindingTarget>()

    fun request(
        target: ClicksButtonBindingTarget,
        binding: ClicksDesiredButtonBinding,
        onComplete: (Boolean) -> Unit
    ): ClicksButtonBindingRequestStatus {
        store.setDesired(target, binding)
        failedTargets -= target
        val state = observe(keyboardState())
        val writeInProgress = target in writesInProgress
        val status = when {
            !writeInProgress && isVerified(state, target, binding.firmwareOutput) -> {
                store.markApplied(target, binding.firmwareOutput)
                ClicksButtonBindingRequestStatus.CONFIRMED
            }
            !ClicksButtonBindingSyncPolicy.canWrite(state) || writer() == null ->
                ClicksButtonBindingRequestStatus.PENDING_CONNECTION
            else -> {
                // A newer selection supersedes the UI callback of an older in-flight write. The
                // older firmware write may still finish, but only the latest request owns the
                // visible result.
                callbacks[target] = onComplete
                if (!writeInProgress) startWrite(target, binding)
                ClicksButtonBindingRequestStatus.APPLYING
            }
        }
        onBindingsChanged()
        return status
    }

    fun reconcile() {
        observe(keyboardState())
        if (writer() == null) return
        ClicksButtonBindingTarget.entries.forEach { target ->
            // The completion of the running write reconciles this target with its final state.
            if (target in writesInProgress) return@forEach
            val desired = store.desired(target) ?: return@forEach
            when (
                ClicksButtonBindingSyncPolicy.reconcileAction(
                    keyboardState(),
                    target,
                    desired,
                    failedInCurrentConnection = target in failedTargets,
                    outputUnverified = target in unverifiedTargets
                )
            ) {
                ClicksButtonBindingReconcileAction.NONE -> Unit
                ClicksButtonBindingReconcileAction.MARK_APPLIED -> {
                    store.markApplied(target, desired.firmwareOutput)
                    onBindingsChanged()
                }
                ClicksButtonBindingReconcileAction.WRITE -> startWrite(target, desired)
            }
        }
    }

    /** Ends all visible requests of a closed connection; their writes no longer complete. */
    fun onConnectionReset() {
        val pendingCallbacks = callbacks.values.toList()
        callbacks.clear()
        failedTargets.clear()
        unverifiedTargets.clear()
        pendingCallbacks.forEach { it(false) }
    }

    private fun startWrite(target: ClicksButtonBindingTarget, desired: ClicksDesiredButtonBinding) {
        val activeWriter = writer()
        if (activeWriter == null) {
            finishCallback(target, success = false)
            return
        }
        if (!writesInProgress.add(target)) return
        activeWriter.writeButtonRemap(target.firmwareCommand, desired.firmwareOutput) { written ->
            writesInProgress.remove(target)
            val state = keyboardState()
            when {
                written -> unverifiedTargets -= target
                // An already invalid session is read completely before it can confirm anything.
                state.sessionValidated && writer() === activeWriter -> unverifiedTargets += target
            }
            observe(state)
            val latestDesired = store.desired(target)
            // The state of a failed write predates it and cannot confirm the latest selection.
            val latestDesiredConfirmed = written && latestDesired != null &&
                isVerified(state, target, latestDesired.firmwareOutput)
            when (
                ClicksButtonBindingCompletionPolicy.resolve(
                    attemptedOutput = desired.firmwareOutput,
                    latestDesiredOutput = latestDesired?.firmwareOutput,
                    latestDesiredConfirmed = latestDesiredConfirmed
                )
            ) {
                ClicksButtonBindingCompletion.SUCCESS -> {
                    latestDesired?.let { store.markApplied(target, it.firmwareOutput) }
                    onBindingsChanged()
                    finishCallback(target, success = true)
                }
                ClicksButtonBindingCompletion.FAILURE -> {
                    if (writer() === activeWriter) failedTargets += target
                    finishCallback(target, success = false)
                }
                ClicksButtonBindingCompletion.KEEP_PENDING -> Unit
            }
            reconcile()
            // The latest selection stays pending but cannot be written in this session now.
            if (target !in writesInProgress) finishCallback(target, success = false)
        }
    }

    /** Forgets doubts about published outputs once a new complete read has begun. */
    private fun observe(state: ClicksPowerKeyboardState): ClicksPowerKeyboardState {
        if (!state.sessionValidated) unverifiedTargets.clear()
        return state
    }

    private fun isVerified(state: ClicksPowerKeyboardState, target: ClicksButtonBindingTarget, output: ByteArray) =
        target !in unverifiedTargets && ClicksButtonBindingSyncPolicy.isConfirmed(state, target, output)

    private fun finishCallback(target: ClicksButtonBindingTarget, success: Boolean) {
        callbacks.remove(target)?.invoke(success)
    }
}
