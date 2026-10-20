package rocks.pkb.plektra.core

import android.os.SystemClock
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import rocks.pkb.plektra.inputmethod.ModifierKeyHandler
import rocks.pkb.plektra.inputmethod.NavModeHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModifierStateControllerTest {

    private val doubleTapThreshold = 300L

    @Test
    fun testShiftTransitions() {
        val controller = ModifierStateController(doubleTapThreshold)
        
        // Initial state: OFF
        assertEquals(ShiftState.OFF, controller.shiftState)
        
        // 1. OFF -> ONE_SHOT
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.ONE_SHOT, controller.shiftState)
        assertTrue(controller.shiftPressed)
        assertTrue(controller.shiftPhysicallyPressed)
        
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.ONE_SHOT, controller.shiftState)
        assertFalse(controller.shiftPressed)
        assertFalse(controller.shiftPhysicallyPressed)
        
        // 2. ONE_SHOT -> OFF (if tap again after threshold or non-consecutive)
        // Simulate waiting longer than threshold (or just non-consecutive tap)
        // Here we test non-consecutive by using a different key in between
        controller.registerNonModifierKey()
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.OFF, controller.shiftState)
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    @Test
    fun testCapsLockTransition() {
        val controller = ModifierStateController(doubleTapThreshold)
        
        // OFF -> ONE_SHOT
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.ONE_SHOT, controller.shiftState)
        
        // ONE_SHOT -> CAPS (Double tap / Consecutive tap)
        // Since we are in the same test and no other keys pressed, it should be consecutive
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.CAPS, controller.shiftState)
        assertTrue(controller.capsLockEnabled)
        
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.CAPS, controller.shiftState)
        
        // CAPS -> OFF (Tap during CAPS)
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.OFF, controller.shiftState)
        assertFalse(controller.capsLockEnabled)
    }

    @Test
    fun testNonModifierBetweenShiftTapsPreventsCapsLock() {
        val controller = ModifierStateController(doubleTapThreshold)

        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        controller.registerNonModifierKey()
        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)

        assertEquals(ShiftState.OFF, controller.shiftState)
        assertFalse(controller.capsLockEnabled)
    }

    @Test
    fun testConsumeOneShot() {
        val controller = ModifierStateController(doubleTapThreshold)
        
        // Set ONE_SHOT
        controller.shiftOneShot = true
        assertEquals(ShiftState.ONE_SHOT, controller.shiftState)
        
        // Consume it
        assertTrue(controller.consumeShiftOneShot())
        assertEquals(ShiftState.OFF, controller.shiftState)
        
        // Consume again (should be false)
        assertFalse(controller.consumeShiftOneShot())
    }

    @Test
    fun testCapsLockStaysActive() {
        val controller = ModifierStateController(doubleTapThreshold)
        
        // Enable CAPS_LOCK
        controller.capsLockEnabled = true
        assertEquals(ShiftState.CAPS, controller.shiftState)
        
        // Simulate typing letters and spaces
        controller.registerNonModifierKey()
        assertFalse(controller.consumeShiftOneShot()) // CAPS shouldn't be consumed by ONE_SHOT consumer
        assertEquals(ShiftState.CAPS, controller.shiftState)
        
        controller.registerNonModifierKey()
        assertEquals(ShiftState.CAPS, controller.shiftState)
        
        // Reset via space logic usually happens in the InputEventRouter, 
        // but let's see if the controller itself stays in CAPS
        controller.registerNonModifierKey()
        assertEquals(ShiftState.CAPS, controller.shiftState)
    }
    
    @Test
    fun testResetModifiers() {
        val controller = ModifierStateController(doubleTapThreshold)
        
        controller.capsLockEnabled = true
        controller.ctrlLatchActive = true
        
        var navModeCancelled = false
        controller.resetModifiers(preserveNavMode = false) {
            navModeCancelled = true
        }
        
        assertEquals(ShiftState.OFF, controller.shiftState)
        assertFalse(controller.ctrlLatchActive)
        assertTrue(navModeCancelled)
    }

    @Test
    fun testAltOneShotAndLatchTransitions() {
        val controller = ModifierStateController(doubleTapThreshold)
        val t0 = 2_000L

        // First tap: OFF -> one-shot
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = t0)
        assertTrue(controller.altOneShot)
        assertFalse(controller.altLatchActive)
        assertTrue(controller.altPressed)
        assertTrue(controller.altPhysicallyPressed)

        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now = t0 + 30L)
        assertTrue(controller.altOneShot)
        assertFalse(controller.altLatchActive)
        assertFalse(controller.altPressed)
        assertFalse(controller.altPhysicallyPressed)

        // Second consecutive tap quickly: one-shot -> latch
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = t0 + 120L)
        assertFalse(controller.altOneShot)
        assertTrue(controller.altLatchActive)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now = t0 + 150L)
        assertTrue(controller.altLatchActive)

        // Tap while latched: latch -> off (Issue #135 expectation)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = t0 + 260L)
        assertFalse(controller.altLatchActive)
        assertFalse(controller.altOneShot)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now = t0 + 290L)
        assertFalse(controller.altLatchActive)
        assertFalse(controller.altOneShot)
    }

    @Test
    fun testCtrlOneShotAndLatchTransitions() {
        val controller = ModifierStateController(doubleTapThreshold)
        val t0 = 1_000L

        // First tap: OFF -> one-shot
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = t0)
        assertTrue(controller.ctrlOneShot)
        assertFalse(controller.ctrlLatchActive)
        assertTrue(controller.ctrlPressed)
        assertTrue(controller.ctrlPhysicallyPressed)

        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = t0 + 30L)
        assertTrue(controller.ctrlOneShot)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlPressed)
        assertFalse(controller.ctrlPhysicallyPressed)

        // Second consecutive tap quickly: one-shot -> latch
        controller.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            isInputViewActive = true,
            now = t0 + 120L
        )
        assertFalse(controller.ctrlOneShot)
        assertTrue(controller.ctrlLatchActive)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = t0 + 150L)
        assertTrue(controller.ctrlLatchActive)

        // Tap while latched: latch -> off
        controller.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            isInputViewActive = true,
            now = t0 + 260L
        )
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlOneShot)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = t0 + 290L)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlOneShot)
    }

    @Test
    fun shiftTapLatchSetting_locksOnSingleTap() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.shiftTapLatches = true

        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.CAPS, controller.shiftState)
        assertTrue(controller.capsLockEnabled)
        controller.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)

        controller.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.OFF, controller.shiftState)
        assertFalse(controller.capsLockEnabled)
    }

    @Test
    fun altTapLatchSetting_locksOnSingleTap() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.altTapLatches = true

        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
        assertFalse(controller.altOneShot)
        assertTrue(controller.altLatchActive)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT)

        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
        assertFalse(controller.altOneShot)
        assertFalse(controller.altLatchActive)
    }

    @Test
    fun ctrlTapLatchSetting_locksOnSingleTapWithoutNavMode() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.ctrlTapLatches = true

        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true)
        assertFalse(controller.ctrlOneShot)
        assertTrue(controller.ctrlLatchActive)
        assertFalse(controller.ctrlLatchFromNavMode)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)

        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true)
        assertFalse(controller.ctrlOneShot)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlLatchFromNavMode)
    }

    @Test
    fun testCtrlNavLatchTapOutsideInputViewDeactivatesAndConsumes() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.ctrlLatchActive = true
        controller.ctrlLatchFromNavMode = true

        var callbackCalled = false
        val result = controller.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            isInputViewActive = false
        ) {
            callbackCalled = true
        }

        assertTrue(result.shouldConsume)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlLatchFromNavMode)
        assertTrue(callbackCalled)
    }

    @Test
    fun testCtrlNavLatchTapInsideInputViewFallbackClearsState() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.ctrlLatchActive = true
        controller.ctrlLatchFromNavMode = true

        var callbackCalled = false
        val result = controller.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            isInputViewActive = true
        ) {
            callbackCalled = true
        }

        assertFalse(result.shouldConsume)
        assertTrue(result.shouldUpdateStatusBar)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlLatchFromNavMode)
        assertTrue(callbackCalled)
    }

    @Test
    fun testClearCtrlStateResetsStuckShortcutFlags() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.ctrlPressed = true
        controller.ctrlPhysicallyPressed = true
        controller.ctrlOneShot = true
        controller.ctrlLatchActive = true
        controller.ctrlLatchFromNavMode = true

        controller.clearCtrlState(resetPressedState = true)

        assertFalse(controller.ctrlPressed)
        assertFalse(controller.ctrlPhysicallyPressed)
        assertFalse(controller.ctrlOneShot)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlLatchFromNavMode)
    }

    @Test
    fun testClearAltStateResetsStuckShortcutFlags() {
        val controller = ModifierStateController(doubleTapThreshold)
        controller.altPressed = true
        controller.altPhysicallyPressed = true
        controller.altOneShot = true
        controller.altLatchActive = true

        controller.clearAltState(resetPressedState = true)

        assertFalse(controller.altPressed)
        assertFalse(controller.altPhysicallyPressed)
        assertFalse(controller.altOneShot)
        assertFalse(controller.altLatchActive)
    }

    private val wallClockEra = 1_700_000_000_000L

    @Test
    fun ctrlReleaseStampedInWallClockEra_doesNotLatchOnNextPress() {
        val controller = ModifierStateController(doubleTapThreshold)

        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = 1_000L)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = wallClockEra)
        assertTrue(controller.ctrlOneShot)

        // A uptime-base press after a wall-clock-era release is a negative delta.
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = 1_100L)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlOneShot)
    }

    @Test
    fun altReleaseStampedInWallClockEra_doesNotLatchOnNextPress() {
        val controller = ModifierStateController(doubleTapThreshold)

        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = 1_000L)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now = wallClockEra)
        assertTrue(controller.altOneShot)

        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = 1_100L)
        assertFalse(controller.altLatchActive)
        assertFalse(controller.altOneShot)
    }

    @Test
    fun releaseTimeAfterPress_neverCountsAsDoubleTap() {
        val controller = ModifierStateController(doubleTapThreshold)

        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = 4_000L)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = 5_000L)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = 4_900L)
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlOneShot)
    }

    @Test
    fun softwareTapThenHardwarePressMinutesLater_clearsOneShot() {
        val controller = ModifierStateController(doubleTapThreshold)
        val tap = 2_000_000L

        // On-screen Ctrl tap (uptime base), then a hardware Ctrl press minutes later.
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true, now = tap)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now = tap + 40L)
        assertTrue(controller.ctrlOneShot)

        controller.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            isInputViewActive = true,
            now = tap + 3 * 60_000L
        )
        assertFalse(controller.ctrlLatchActive)
        assertFalse(controller.ctrlOneShot)
    }

    @Test
    fun softwareAltTapThenHardwarePressMinutesLater_clearsOneShot() {
        val controller = ModifierStateController(doubleTapThreshold)
        val tap = 2_000_000L

        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = tap)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now = tap + 40L)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now = tap + 3 * 60_000L)
        assertFalse(controller.altLatchActive)
        assertFalse(controller.altOneShot)
    }

    @Test
    fun defaultClockIsUptime_soQuickDefaultTimedTapsStillLatch() {
        val controller = ModifierStateController(doubleTapThreshold)

        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, isInputViewActive = true)
        assertTrue(controller.ctrlLatchActive)
    }

    @Test
    fun eventTimeOrUptime_usesEventTimeWhenPositive() {
        val event = KeyEvent(0L, 7_777L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0)
        assertEquals(7_777L, ModifierKeyHandler.eventTimeOrUptime(event))
    }

    @Test
    fun eventTimeOrUptime_zeroOrMissingEventTimeFallsBackToUptime() {
        val zeroTime = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)
        assertEquals(0L, zeroTime.eventTime)

        val before = SystemClock.uptimeMillis()
        val fromZero = ModifierKeyHandler.eventTimeOrUptime(zeroTime)
        val fromNull = ModifierKeyHandler.eventTimeOrUptime(null)
        val after = SystemClock.uptimeMillis()

        assertTrue(fromZero in before..after)
        assertTrue(fromNull in before..after)
        // Uptime base, nowhere near wall-clock.
        assertTrue(fromZero < wallClockEra)
    }

    @Test
    fun navModeCtrl_wallClockEraReleaseDoesNotLatch() {
        val (_, stale) = NavModeHandler.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            ctrlPressed = false,
            ctrlLatchActive = false,
            lastCtrlReleaseTime = wallClockEra,
            isConsecutiveTap = true,
            now = 1_100L
        )
        assertTrue(stale.ctrlLatchActive != true)

        val (_, quick) = NavModeHandler.handleCtrlKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            ctrlPressed = false,
            ctrlLatchActive = false,
            lastCtrlReleaseTime = 1_000L,
            isConsecutiveTap = true,
            now = 1_100L
        )
        assertEquals(true, quick.ctrlLatchActive)
    }
}
