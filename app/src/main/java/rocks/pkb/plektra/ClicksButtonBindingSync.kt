package rocks.pkb.plektra

internal enum class ClicksButtonBindingTarget(val firmwareCommand: Int) {
    RED(ClicksPowerKeyboardProtocol.COMMAND_TAB_REMAP),
    KEYBOARD(ClicksPowerKeyboardProtocol.COMMAND_ALT_REMAP),
    MICROPHONE(ClicksPowerKeyboardProtocol.COMMAND_GEMINI_REMAP)
}

/**
 * A Plektra button selection and its firmware output.
 *
 * [pending] is true only until Plektra has written or verified that output on the keyboard. An
 * applied binding is never written again, so an assignment changed later in the Clicks app stays
 * in effect.
 */
internal data class ClicksDesiredButtonBinding(
    val choiceId: String,
    val firmwareOutput: ByteArray,
    val pending: Boolean = true
)

internal enum class ClicksButtonBindingRequestStatus {
    CONFIRMED,
    APPLYING,
    PENDING_CONNECTION
}

internal enum class ClicksButtonBindingReconcileAction {
    NONE,
    MARK_APPLIED,
    WRITE
}

internal enum class ClicksButtonBindingCompletion {
    SUCCESS,
    FAILURE,
    KEEP_PENDING
}

/** Resolves an in-flight firmware write against the most recently requested binding. */
internal object ClicksButtonBindingCompletionPolicy {
    fun resolve(
        attemptedOutput: ByteArray,
        latestDesiredOutput: ByteArray?,
        latestDesiredConfirmed: Boolean
    ): ClicksButtonBindingCompletion = when {
        latestDesiredOutput == null -> ClicksButtonBindingCompletion.FAILURE
        latestDesiredConfirmed -> ClicksButtonBindingCompletion.SUCCESS
        attemptedOutput.contentEquals(latestDesiredOutput) -> ClicksButtonBindingCompletion.FAILURE
        else -> ClicksButtonBindingCompletion.KEEP_PENDING
    }
}

internal object ClicksButtonBindingSyncPolicy {
    fun canWrite(state: ClicksPowerKeyboardState): Boolean =
        state.ready &&
            state.sessionValidated &&
            !state.stale &&
            state.specialKeyEnableFlags != null &&
            state.firmwareVersion?.let(ClicksFirmwareVersionReader::isSupported) == true

    fun reconcileAction(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desired: ClicksDesiredButtonBinding?,
        failedInCurrentConnection: Boolean
    ): ClicksButtonBindingReconcileAction = when {
        desired == null || !desired.pending || !canWrite(state) -> ClicksButtonBindingReconcileAction.NONE
        isConfirmed(state, target, desired.firmwareOutput) -> ClicksButtonBindingReconcileAction.MARK_APPLIED
        failedInCurrentConnection -> ClicksButtonBindingReconcileAction.NONE
        else -> ClicksButtonBindingReconcileAction.WRITE
    }

    fun isPending(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desired: ClicksDesiredButtonBinding?
    ): Boolean = desired?.pending == true && !isConfirmed(state, target, desired.firmwareOutput)

    /**
     * Returns the Plektra selection that still describes the keyboard. An applied selection whose
     * output differs from the read firmware state was replaced outside Plektra and is not shown.
     */
    fun displayedBinding(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desired: ClicksDesiredButtonBinding?
    ): ClicksDesiredButtonBinding? = desired?.takeIf {
        it.pending || state.specialKeyEnableFlags == null || isConfirmed(state, target, it.firmwareOutput)
    }

    /** The firmware output of [target], or null while its remap state has not been read. */
    fun currentOutput(state: ClicksPowerKeyboardState, target: ClicksButtonBindingTarget): ByteArray? {
        if (state.specialKeyEnableFlags == null) return null
        val currentOutput = when (target) {
            ClicksButtonBindingTarget.RED -> state.tabRemap
            ClicksButtonBindingTarget.KEYBOARD -> state.altRemap
            ClicksButtonBindingTarget.MICROPHONE -> state.geminiRemap
        }
        return currentOutput ?: ClicksPowerKeyboardProtocol.nativeRemapOutput()
    }

    fun isConfirmed(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desiredOutput: ByteArray
    ): Boolean {
        require(desiredOutput.size == 2)
        val currentOutput = when (target) {
            ClicksButtonBindingTarget.RED -> state.tabRemap
            ClicksButtonBindingTarget.KEYBOARD -> state.altRemap
            ClicksButtonBindingTarget.MICROPHONE -> state.geminiRemap
        }
        return if (desiredOutput.isNativeOutput()) {
            currentOutput == null
        } else {
            currentOutput?.contentEquals(desiredOutput) == true
        }
    }

    private fun ByteArray.isNativeOutput(): Boolean = all { it == 0.toByte() }
}
