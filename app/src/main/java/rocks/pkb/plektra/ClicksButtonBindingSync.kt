package rocks.pkb.plektra

internal enum class ClicksButtonBindingTarget(val firmwareCommand: Int) {
    RED(ClicksPowerKeyboardProtocol.COMMAND_TAB_REMAP),
    KEYBOARD(ClicksPowerKeyboardProtocol.COMMAND_ALT_REMAP),
    MICROPHONE(ClicksPowerKeyboardProtocol.COMMAND_GEMINI_REMAP);

    val enableFlag = requireNotNull(ClicksPowerKeyboardProtocol.remapTarget(firmwareCommand)).enableFlag
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

/** How far the shown firmware assignment can be trusted. */
internal enum class ClicksButtonBindingReadState {
    /** Nothing was read; the Plektra selection is shown. */
    UNKNOWN,
    /** Read in the current connection. */
    CURRENT,
    /** Read earlier; a fresh read of the connected keyboard has not completed yet. */
    READING,
    /** Read earlier; the keyboard is not connected. */
    LAST_KNOWN
}

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
        readState(state) == ClicksButtonBindingReadState.CURRENT &&
            state.firmwareVersion?.let(ClicksFirmwareVersionReader::isSupported) == true

    fun reconcileAction(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desired: ClicksDesiredButtonBinding?,
        failedInCurrentConnection: Boolean,
        /** A failed write may have changed the firmware without publishing it; see the coordinator. */
        outputUnverified: Boolean = false
    ): ClicksButtonBindingReconcileAction = when {
        desired == null || !desired.pending -> ClicksButtonBindingReconcileAction.NONE
        !outputUnverified && isConfirmed(state, target, desired.firmwareOutput) ->
            ClicksButtonBindingReconcileAction.MARK_APPLIED
        failedInCurrentConnection || !canWrite(state) -> ClicksButtonBindingReconcileAction.NONE
        else -> ClicksButtonBindingReconcileAction.WRITE
    }

    fun readState(state: ClicksPowerKeyboardState): ClicksButtonBindingReadState = when {
        state.specialKeyEnableFlags == null -> ClicksButtonBindingReadState.UNKNOWN
        state.ready && state.sessionValidated && !state.stale -> ClicksButtonBindingReadState.CURRENT
        state.connected -> ClicksButtonBindingReadState.READING
        else -> ClicksButtonBindingReadState.LAST_KNOWN
    }

    /**
     * Returns the Plektra selection that still describes the keyboard. An applied selection whose
     * output differs from the read firmware state was replaced outside Plektra and is not shown.
     */
    fun displayedBinding(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desired: ClicksDesiredButtonBinding?
    ): ClicksDesiredButtonBinding? {
        val output = currentOutput(state, target)
        return desired?.takeIf { it.pending || output == null || output.contentEquals(it.firmwareOutput) }
    }

    /** The firmware output of [target], or null while its remap state has not been read. */
    fun currentOutput(state: ClicksPowerKeyboardState, target: ClicksButtonBindingTarget): ByteArray? {
        val flags = state.specialKeyEnableFlags ?: return null
        if (flags and target.enableFlag == 0) return ClicksPowerKeyboardProtocol.nativeRemapOutput()
        return when (target) {
            ClicksButtonBindingTarget.RED -> state.tabRemap
            ClicksButtonBindingTarget.KEYBOARD -> state.altRemap
            ClicksButtonBindingTarget.MICROPHONE -> state.geminiRemap
        }
    }

    fun isConfirmed(
        state: ClicksPowerKeyboardState,
        target: ClicksButtonBindingTarget,
        desiredOutput: ByteArray
    ): Boolean {
        require(desiredOutput.size == 2)
        return readState(state) == ClicksButtonBindingReadState.CURRENT &&
            currentOutput(state, target)?.contentEquals(desiredOutput) == true
    }
}
