package rocks.pkb.plektra.inputmethod

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Handles modifier key state management and double-tap detection
 * for Ctrl (latch) and Alt (latch).
 */
class ModifierKeyHandler(
    private val doubleTapThreshold: Long = 500L
) {
    data class CtrlState(
        var pressed: Boolean = false,
        var oneShot: Boolean = false,
        var latchActive: Boolean = false,
        var physicallyPressed: Boolean = false,
        var lastReleaseTime: Long = 0,
        var latchFromNavMode: Boolean = false
    )

    data class AltState(
        var pressed: Boolean = false,
        var oneShot: Boolean = false,
        var latchActive: Boolean = false,
        var physicallyPressed: Boolean = false,
        var lastReleaseTime: Long = 0
    )

    data class ModifierKeyResult(
        val shouldConsume: Boolean = false,
        val shouldUpdateStatusBar: Boolean = false,
        val shouldRefreshStatusBar: Boolean = false
    )

    private fun isDoubleTap(now: Long, lastReleaseTime: Long): Boolean =
        isWithinDoubleTap(now, lastReleaseTime, doubleTapThreshold)

    companion object {
        /** The event's own time, or the current uptime when it has none (eventTime <= 0). */
        fun eventTimeOrUptime(event: KeyEvent?): Long =
            event?.eventTime?.takeIf { it > 0L } ?: SystemClock.uptimeMillis()

        /**
         * True when [now] falls within [threshold] after a recorded release. A negative delta
         * (a release stamped on another clock, or from a later event) never counts, so a stale or
         * foreign timestamp cannot latch a modifier. All times are in
         * [android.os.SystemClock.uptimeMillis], the base of [KeyEvent.getEventTime].
         */
        fun isWithinDoubleTap(now: Long, lastReleaseTime: Long, threshold: Long): Boolean =
            lastReleaseTime > 0 && now - lastReleaseTime in 0 until threshold
    }

    // ========== Ctrl Handling ==========

    fun handleCtrlKeyDown(
        keyCode: Int,
        state: CtrlState,
        isInputViewActive: Boolean,
        isConsecutiveTap: Boolean,
        singleTapLatches: Boolean = false,
        now: Long = SystemClock.uptimeMillis(),
        onNavModeDeactivated: (() -> Unit)? = null
    ): ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_CTRL_LEFT && keyCode != KeyEvent.KEYCODE_CTRL_RIGHT) {
            return ModifierKeyResult()
        }

        if (state.pressed) {
            return ModifierKeyResult()
        }

        state.physicallyPressed = true
        val allowDoubleTap = isConsecutiveTap
        if (!allowDoubleTap) {
            state.lastReleaseTime = 0
        }

        when {
            state.latchActive -> {
                // Latch active: single tap disables it
                // Special handling for nav mode
                if (state.latchFromNavMode && !isInputViewActive) {
                    state.latchActive = false
                    state.latchFromNavMode = false
                    onNavModeDeactivated?.invoke()
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldConsume = true)
                } else if (!state.latchFromNavMode) {
                    state.latchActive = false
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                } else {
                    // Nav mode in text field (should not happen)
                    state.latchActive = false
                    state.latchFromNavMode = false
                    onNavModeDeactivated?.invoke()
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
            }
            state.oneShot -> {
                // One-shot active: check for double-tap
                if (allowDoubleTap && isDoubleTap(now, state.lastReleaseTime)) {
                    state.oneShot = false
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                } else {
                    // Single tap: disable one-shot
                    state.oneShot = false
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
            }
            else -> {
                if (singleTapLatches) {
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
                // Check for double-tap to enable latch
                if (allowDoubleTap && isDoubleTap(now, state.lastReleaseTime)) {
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                } else {
                    // Single tap: enable one-shot
                    state.oneShot = true
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
            }
        }
    }

    fun handleCtrlKeyUp(
        keyCode: Int,
        state: CtrlState,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_CTRL_LEFT && keyCode != KeyEvent.KEYCODE_CTRL_RIGHT) {
            return ModifierKeyResult()
        }

        if (state.pressed) {
            state.lastReleaseTime = now
            state.pressed = false
            state.physicallyPressed = false
            return ModifierKeyResult(shouldUpdateStatusBar = true)
        }
        return ModifierKeyResult()
    }

    // ========== Alt Handling ==========

    fun handleAltKeyDown(
        keyCode: Int,
        state: AltState,
        isConsecutiveTap: Boolean,
        singleTapLatches: Boolean = false,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_ALT_LEFT && keyCode != KeyEvent.KEYCODE_ALT_RIGHT) {
            return ModifierKeyResult()
        }

        if (state.pressed) {
            return ModifierKeyResult()
        }

        state.physicallyPressed = true
        val allowDoubleTap = isConsecutiveTap
        if (!allowDoubleTap) {
            state.lastReleaseTime = 0
        }

        when {
            state.latchActive -> {
                // Latch active: single tap disables it
                state.latchActive = false
                state.lastReleaseTime = 0
                return ModifierKeyResult(shouldUpdateStatusBar = true)
            }
            state.oneShot -> {
                // One-shot active: check for double-tap
                if (allowDoubleTap && isDoubleTap(now, state.lastReleaseTime)) {
                    state.oneShot = false
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                } else {
                    // Single tap: disable one-shot
                    state.oneShot = false
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
            }
            else -> {
                if (singleTapLatches) {
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
                // Check for double-tap to enable latch
                if (allowDoubleTap && isDoubleTap(now, state.lastReleaseTime)) {
                    state.latchActive = true
                    state.lastReleaseTime = 0
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                } else {
                    // Single tap: enable one-shot
                    state.oneShot = true
                    return ModifierKeyResult(shouldUpdateStatusBar = true)
                }
            }
        }
    }

    fun handleAltKeyUp(
        keyCode: Int,
        state: AltState,
        now: Long = SystemClock.uptimeMillis()
    ): ModifierKeyResult {
        if (keyCode != KeyEvent.KEYCODE_ALT_LEFT && keyCode != KeyEvent.KEYCODE_ALT_RIGHT) {
            return ModifierKeyResult()
        }

        if (state.pressed) {
            state.lastReleaseTime = now
            state.pressed = false
            state.physicallyPressed = false
            return ModifierKeyResult(shouldUpdateStatusBar = true)
        }
        return ModifierKeyResult()
    }

    // ========== Reset Helpers ==========

    fun resetCtrlState(state: CtrlState, preserveNavMode: Boolean = false) {
        if (!preserveNavMode || !state.latchFromNavMode) {
            state.latchActive = false
            state.latchFromNavMode = false
        }
        state.pressed = false
        state.oneShot = false
        state.lastReleaseTime = 0
    }

    fun resetAltState(state: AltState) {
        state.pressed = false
        state.oneShot = false
        state.latchActive = false
        state.lastReleaseTime = 0
    }
}
