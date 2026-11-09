package rocks.pkb.plektra.core

import android.os.SystemClock
import android.view.KeyEvent
import rocks.pkb.plektra.inputmethod.ModifierKeyHandler

enum class ShiftState { OFF, ONE_SHOT, CAPS }

/**
 * Centralizes modifier key state (Shift/Ctrl/Alt) and keeps one-shot / latch
 * bookkeeping in sync with the UI and auto-capitalization helpers.
 */
class ModifierStateController(
    private val doubleTapThreshold: Long,
    /** Whether the active layout has letter case, so field capitalization may select its shift layer. */
    private val layoutSupportsAutomaticShift: () -> Boolean = { true }
) {
    private val modifierKeyHandler = ModifierKeyHandler(doubleTapThreshold)
    private var lastKeyWasModifier = false
    private var lastModifierKeyCode: Int = 0

    private class ShiftStateMachine(
        private val doubleTapThreshold: Long
    ) {
        var state: ShiftState = ShiftState.OFF
            private set(value) {
                field = value
                automaticCapsLock = false
            }

        /** True while Caps Lock is the one a field's CAP_CHARACTERS flag turned on. */
        var automaticCapsLock: Boolean = false
            private set

        private var lastTapTime: Long = 0

        fun tap(
            now: Long = System.currentTimeMillis(),
            isConsecutiveTap: Boolean,
            singleTapLatches: Boolean = false
        ): ShiftState {
            val doubleTap = isConsecutiveTap && now - lastTapTime < doubleTapThreshold
            lastTapTime = now
            state = when {
                doubleTap -> if (state == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
                singleTapLatches -> if (state == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
                state == ShiftState.OFF -> ShiftState.ONE_SHOT
                else -> ShiftState.OFF
            }
            return state
        }

        fun requestOneShot(): Boolean {
            if (state == ShiftState.CAPS) {
                return false
            }
            if (state != ShiftState.ONE_SHOT) {
                state = ShiftState.ONE_SHOT
                return true
            }
            return false
        }

        fun consumeOneShot(): Boolean {
            return if (state == ShiftState.ONE_SHOT) {
                state = ShiftState.OFF
                true
            } else {
                false
            }
        }

        fun setCapsLock(enabled: Boolean) {
            state = if (enabled) ShiftState.CAPS else ShiftState.OFF
        }

        fun setAutomaticCapsLock() {
            state = ShiftState.CAPS
            automaticCapsLock = true
        }

        fun restore(newState: ShiftState, automatic: Boolean = false) {
            state = newState
            automaticCapsLock = automatic && newState == ShiftState.CAPS
        }

        fun reset() {
            state = ShiftState.OFF
            lastTapTime = 0
        }
    }

    private val shiftStateMachine = ShiftStateMachine(doubleTapThreshold)

    // The current field asks for Caps Lock (CAP_CHARACTERS). Field state, never part of a hold snapshot.
    private var fieldCapsLockRequested = false
    // Counts field (re)starts, so a state captured before one does not bring back the old field's request.
    private var fieldGeneration = 0
    // The field asked for Caps Lock while an uncased layout was active; it returns on a cased one.
    private var fieldCapsLockSuspended = false
    // Counts layout changes so a state captured before one can be re-applied to the new layout.
    private var layoutGeneration = 0
    private var shiftPressedFlag = false
    private var shiftPhysicallyPressedFlag = false

    private val ctrlState = ModifierKeyHandler.CtrlState()
    private val altState = ModifierKeyHandler.AltState()

    var shiftTapLatches: Boolean = false
    var ctrlTapLatches: Boolean = false
    var altTapLatches: Boolean = false

    fun registerModifierTap(keyCode: Int): Boolean {
        val isConsecutive = lastKeyWasModifier && lastModifierKeyCode == keyCode
        lastKeyWasModifier = true
        lastModifierKeyCode = keyCode
        return isConsecutive
    }

    fun registerNonModifierKey() {
        lastKeyWasModifier = false
        lastModifierKeyCode = 0
    }

    data class Snapshot(
        val capsLockEnabled: Boolean,
        val shiftPhysicallyPressed: Boolean,
        val shiftOneShot: Boolean,
        val ctrlLatchActive: Boolean,
        val ctrlPhysicallyPressed: Boolean,
        val ctrlOneShot: Boolean,
        val ctrlLatchFromNavMode: Boolean,
        val altLatchActive: Boolean,
        val altPhysicallyPressed: Boolean,
        val altOneShot: Boolean
    )

    data class LogicalState(
        val shiftState: ShiftState,
        val ctrlOneShot: Boolean,
        val ctrlLatchActive: Boolean,
        val ctrlLatchFromNavMode: Boolean,
        val altOneShot: Boolean,
        val altLatchActive: Boolean,
        val automaticCapsLock: Boolean = false,
        val fieldCapsLockSuspended: Boolean = false,
        val layoutGeneration: Int = 0,
        val fieldGeneration: Int = 0
    )

    fun captureLogicalState(): LogicalState {
        return LogicalState(
            shiftState = shiftStateMachine.state,
            ctrlOneShot = ctrlState.oneShot,
            ctrlLatchActive = ctrlState.latchActive,
            ctrlLatchFromNavMode = ctrlState.latchFromNavMode,
            altOneShot = altState.oneShot,
            altLatchActive = altState.latchActive,
            automaticCapsLock = shiftStateMachine.automaticCapsLock,
            fieldCapsLockSuspended = fieldCapsLockSuspended,
            layoutGeneration = layoutGeneration,
            fieldGeneration = fieldGeneration
        )
    }

    /**
     * Restores a state captured before a modifier hold. A layout change during the hold
     * is applied again to the restored state, so field Caps Lock follows the current layout.
     * When the field restarted during the hold, only the user's Shift state is restored and
     * the current field's Caps Lock request is applied to it instead of the captured one.
     */
    fun restoreLogicalState(captured: LogicalState) {
        if (captured.fieldGeneration == fieldGeneration) {
            shiftStateMachine.restore(captured.shiftState, captured.automaticCapsLock)
            fieldCapsLockSuspended = captured.fieldCapsLockSuspended
            if (captured.layoutGeneration != layoutGeneration) {
                applyLayoutCasing()
            }
        } else {
            shiftStateMachine.restore(if (captured.automaticCapsLock) ShiftState.OFF else captured.shiftState)
            fieldCapsLockSuspended = false
            if (captured.layoutGeneration != layoutGeneration) {
                applyLayoutCasing()
            }
            if (fieldCapsLockRequested) applyFieldCapsLockRequest()
        }
        ctrlState.oneShot = captured.ctrlOneShot
        ctrlState.latchActive = captured.ctrlLatchActive
        ctrlState.latchFromNavMode = captured.ctrlLatchFromNavMode
        altState.oneShot = captured.altOneShot
        altState.latchActive = captured.altLatchActive
    }

    val shiftState: ShiftState
        get() = shiftStateMachine.state

    var capsLockEnabled: Boolean
        get() = shiftStateMachine.state == ShiftState.CAPS
        set(value) { shiftStateMachine.setCapsLock(value) }

    /**
     * Caps Lock turned on by the field's capitalization flags rather than by the user.
     * Any later change to the Shift state makes it a regular, user-owned state again.
     */
    val capsLockAutomatic: Boolean
        get() = shiftStateMachine.automaticCapsLock

    /**
     * Applies a field's Caps Lock request (CAP_CHARACTERS). Caps Lock that is already on
     * keeps its origin, so a user's Caps Lock stays user-owned across field restarts.
     * On an uncased layout the request waits until a cased layout becomes active.
     */
    fun requestFieldCapsLock() {
        fieldCapsLockRequested = true
        applyFieldCapsLockRequest()
    }

    private fun applyFieldCapsLockRequest() {
        if (shiftStateMachine.state == ShiftState.CAPS) return
        if (layoutSupportsAutomaticShift()) {
            shiftStateMachine.setAutomaticCapsLock()
        } else {
            fieldCapsLockSuspended = true
        }
    }

    /**
     * Forgets the previous field's Caps Lock request before the field flags are applied
     * again: Caps Lock the request turned on goes with it, user Caps Lock stays.
     */
    fun clearFieldCapsLockRequest() {
        fieldGeneration++
        fieldCapsLockRequested = false
        fieldCapsLockSuspended = false
        if (shiftStateMachine.automaticCapsLock) shiftStateMachine.setCapsLock(false)
    }

    /**
     * Call after the active layout changed. On an uncased layout the shift layer holds
     * other letters, so automatic Shift state is dropped (field Caps Lock is suspended);
     * a cased layout gets the suspended field Caps Lock back. User Caps Lock stays.
     */
    fun onLayoutChanged() {
        layoutGeneration++
        applyLayoutCasing()
    }

    private fun applyLayoutCasing() {
        if (!layoutSupportsAutomaticShift()) {
            shiftStateMachine.consumeOneShot()
            if (shiftStateMachine.automaticCapsLock) {
                shiftStateMachine.setCapsLock(false)
                fieldCapsLockSuspended = true
            }
        } else if (fieldCapsLockSuspended) {
            fieldCapsLockSuspended = false
            if (shiftStateMachine.state == ShiftState.OFF) {
                shiftStateMachine.setAutomaticCapsLock()
            }
        }
    }

    var shiftPressed: Boolean
        get() = shiftPressedFlag
        set(value) { shiftPressedFlag = value }

    var shiftPhysicallyPressed: Boolean
        get() = shiftPhysicallyPressedFlag
        set(value) { shiftPhysicallyPressedFlag = value }

    var shiftOneShot: Boolean
        get() = shiftStateMachine.state == ShiftState.ONE_SHOT
        set(value) {
            if (value) {
                shiftStateMachine.requestOneShot()
            } else {
                shiftStateMachine.consumeOneShot()
            }
        }

    var ctrlLatchActive: Boolean
        get() = ctrlState.latchActive
        set(value) { ctrlState.latchActive = value }

    var ctrlPressed: Boolean
        get() = ctrlState.pressed
        set(value) { ctrlState.pressed = value }

    var ctrlPhysicallyPressed: Boolean
        get() = ctrlState.physicallyPressed
        set(value) { ctrlState.physicallyPressed = value }

    var ctrlOneShot: Boolean
        get() = ctrlState.oneShot
        set(value) { ctrlState.oneShot = value }

    var ctrlLatchFromNavMode: Boolean
        get() = ctrlState.latchFromNavMode
        set(value) { ctrlState.latchFromNavMode = value }

    var ctrlLastReleaseTime: Long
        get() = ctrlState.lastReleaseTime
        set(value) { ctrlState.lastReleaseTime = value }

    var altLatchActive: Boolean
        get() = altState.latchActive
        set(value) { altState.latchActive = value }

    var altPressed: Boolean
        get() = altState.pressed
        set(value) { altState.pressed = value }

    var altPhysicallyPressed: Boolean
        get() = altState.physicallyPressed
        set(value) { altState.physicallyPressed = value }

    var altOneShot: Boolean
        get() = altState.oneShot
        set(value) { altState.oneShot = value }

    fun snapshot(): Snapshot {
        return Snapshot(
            capsLockEnabled = capsLockEnabled,
            shiftPhysicallyPressed = shiftPhysicallyPressed,
            shiftOneShot = shiftOneShot,
            ctrlLatchActive = ctrlLatchActive,
            ctrlPhysicallyPressed = ctrlPhysicallyPressed,
            ctrlOneShot = ctrlOneShot,
            ctrlLatchFromNavMode = ctrlLatchFromNavMode,
            altLatchActive = altLatchActive,
            altPhysicallyPressed = altPhysicallyPressed,
            altOneShot = altOneShot
        )
    }

    fun handleShiftKeyDown(keyCode: Int): ModifierKeyHandler.ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT &&
            keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
        ) {
            return ModifierKeyHandler.ModifierKeyResult()
        }

        if (shiftPressedFlag) {
            return ModifierKeyHandler.ModifierKeyResult()
        }

        shiftPhysicallyPressedFlag = true
        shiftPressedFlag = true
        val previous = shiftStateMachine.state
        val isConsecutiveTap = registerModifierTap(keyCode)
        val current = shiftStateMachine.tap(
            isConsecutiveTap = isConsecutiveTap,
            singleTapLatches = shiftTapLatches
        )
        val changed = previous != current
        return ModifierKeyHandler.ModifierKeyResult(
            shouldUpdateStatusBar = changed,
            shouldRefreshStatusBar = changed
        )
    }

    fun handleShiftKeyUp(keyCode: Int): ModifierKeyHandler.ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT &&
            keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
        ) {
            return ModifierKeyHandler.ModifierKeyResult()
        }

        shiftPressedFlag = false
        shiftPhysicallyPressedFlag = false
        return ModifierKeyHandler.ModifierKeyResult(shouldUpdateStatusBar = true)
    }

    fun handleCtrlKeyDown(
        keyCode: Int,
        isInputViewActive: Boolean,
        now: Long = SystemClock.uptimeMillis(),
        onNavModeDeactivated: (() -> Unit)? = null
    ): ModifierKeyHandler.ModifierKeyResult {
        if (ctrlPressed) {
            return ModifierKeyHandler.ModifierKeyResult()
        }
        val isConsecutiveTap = registerModifierTap(keyCode)
        val result = modifierKeyHandler.handleCtrlKeyDown(
            keyCode,
            ctrlState,
            isInputViewActive,
            isConsecutiveTap = isConsecutiveTap,
            singleTapLatches = ctrlTapLatches,
            now = now,
            onNavModeDeactivated = onNavModeDeactivated
        )
        ctrlPressed = true
        return result
    }

    fun handleCtrlKeyUp(
        keyCode: Int,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyHandler.ModifierKeyResult {
        val result = modifierKeyHandler.handleCtrlKeyUp(keyCode, ctrlState, now = now)
        ctrlPressed = false
        return result
    }

    fun handleAltKeyDown(
        keyCode: Int,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyHandler.ModifierKeyResult {
        if (altPressed) {
            return ModifierKeyHandler.ModifierKeyResult()
        }
        val isConsecutiveTap = registerModifierTap(keyCode)
        val result = modifierKeyHandler.handleAltKeyDown(
            keyCode,
            altState,
            isConsecutiveTap = isConsecutiveTap,
            singleTapLatches = altTapLatches,
            now = now
        )
        altPressed = true
        return result
    }

    fun handleAltKeyUp(
        keyCode: Int,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyHandler.ModifierKeyResult {
        val result = modifierKeyHandler.handleAltKeyUp(keyCode, altState, now = now)
        altPressed = false
        return result
    }

    /**
     * Clears Shift state (one-shot/caps) and, when requested, resets pressed tracking.
     * Used when a visual shift latch is explicitly toggled off by tapping Shift again.
     */
    fun clearShiftState(resetPressedState: Boolean = false) {
        shiftStateMachine.reset()
        if (resetPressedState) {
            shiftPressedFlag = false
            shiftPhysicallyPressedFlag = false
        }
    }

    /**
     * Clears Alt latch/one-shot state (used when Space should auto-disable Alt).
     * Optionally resets pressed flags if they are not reliable anymore.
     */
    fun clearAltState(resetPressedState: Boolean = false) {
        altState.latchActive = false
        altState.oneShot = false
        altState.lastReleaseTime = 0
        if (resetPressedState) {
            altState.pressed = false
            altState.physicallyPressed = false
        }
    }

    /**
     * Clears Ctrl state (latch/one-shot/nav mode flags) and, when requested,
     * resets pressed tracking to avoid leaving Ctrl active after shortcuts.
     */
    fun clearCtrlState(resetPressedState: Boolean = false) {
        ctrlState.latchActive = false
        ctrlState.oneShot = false
        ctrlState.latchFromNavMode = false
        ctrlState.lastReleaseTime = 0
        if (resetPressedState) {
            ctrlState.pressed = false
            ctrlState.physicallyPressed = false
        }
    }

    fun requestShiftOneShotFromAutoCap(): Boolean {
        return shiftStateMachine.requestOneShot()
    }

    fun consumeShiftOneShot(): Boolean {
        return shiftStateMachine.consumeOneShot()
    }

    fun resetModifiers(
        preserveNavMode: Boolean,
        onNavModeCancelled: () -> Unit
    ) {
        val savedCtrlLatch = if (preserveNavMode && (ctrlLatchActive || ctrlLatchFromNavMode)) {
            if (ctrlLatchActive) {
                ctrlLatchFromNavMode = true
                true
            } else {
                ctrlLatchFromNavMode
            }
        } else {
            false
        }

        shiftStateMachine.reset()
        fieldGeneration++
        fieldCapsLockRequested = false
        fieldCapsLockSuspended = false
        shiftPressedFlag = false
        shiftPhysicallyPressedFlag = false
        lastKeyWasModifier = false
        lastModifierKeyCode = 0

        if (preserveNavMode && savedCtrlLatch) {
            ctrlLatchActive = true
        } else {
            if (ctrlLatchFromNavMode || ctrlLatchActive) {
                onNavModeCancelled()
            }
            modifierKeyHandler.resetCtrlState(ctrlState, preserveNavMode = false)
        }
        modifierKeyHandler.resetAltState(altState)
    }
}
