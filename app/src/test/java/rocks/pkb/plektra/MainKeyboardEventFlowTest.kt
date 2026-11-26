package rocks.pkb.plektra

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.KeyboardEventTracker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainKeyboardEventFlowTest {
    // Writing the tracker's snapshot state outside a composition schedules Compose's global apply on
    // the main looper. Robolectric drops that queue at teardown, which leaves Compose's shared
    // AndroidUiDispatcher waiting forever, so no later Compose test in this sandbox becomes idle.
    @After fun deliverPendingSnapshotApply() { shadowOf(Looper.getMainLooper()).idle() }

    private fun sendTracked(event: KeyEvent) {
        KeyboardEventTracker.notifyHardwareEdge(event, fromIme = true)
        KeyboardEventTracker.notifyKeyEvent(event.keyCode, event,
            if (event.action == KeyEvent.ACTION_DOWN) "KEY_DOWN" else "KEY_UP")
    }

    @Test fun burstReleasesEveryKeyBeforeComposeCanRender() {
        val view = MotionBackgroundView(RuntimeEnvironment.getApplication())
        val state = mutableStateOf<KeyboardEventTracker.KeyEventInfo?>(null)
        val listener: (KeyboardEventTracker.KeyEventInfo) -> Unit = view::acceptHardwareEvent
        KeyboardEventTracker.registerState(state)
        KeyboardEventTracker.addListener(listener)
        try {
            for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                sendTracked(KeyEvent(0, 10, KeyEvent.ACTION_DOWN, code, 0))
            }
            assertEquals(26, view.heldLabels().size)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2))
            assertEquals(26, view.heldLabels().size)
            for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                sendTracked(KeyEvent(0, 20, KeyEvent.ACTION_UP, code, 0))
            }
            assertTrue(view.heldLabels().isEmpty())
            // The old state-only transport exposes just Z-Up after this burst.
            assertEquals(KeyEvent.KEYCODE_Z, state.value!!.keyCode)
        } finally {
            KeyboardEventTracker.removeListener(listener)
            KeyboardEventTracker.unregisterState()
        }
    }

    @Test fun legacyConflatedTransportLeavesEarlierKeysHeld() {
        val view = MotionBackgroundView(RuntimeEnvironment.getApplication())
        val state = mutableStateOf<KeyboardEventTracker.KeyEventInfo?>(null)
        KeyboardEventTracker.registerState(state)
        try {
            for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                sendTracked(KeyEvent(0, 10, KeyEvent.ACTION_DOWN, code, 0))
                view.acceptHardwareEvent(state.value!!)
            }
            for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                sendTracked(KeyEvent(0, 20, KeyEvent.ACTION_UP, code, 0))
            }
            view.acceptHardwareEvent(state.value!!)
            assertEquals(25, view.heldLabels().size)
        } finally { KeyboardEventTracker.unregisterState() }
    }

    @Test fun overlappingModifiersRepeatCancelAndFocusLoss() {
        val view = MotionBackgroundView(RuntimeEnvironment.getApplication())
        val listener: (KeyboardEventTracker.KeyEventInfo) -> Unit = view::acceptHardwareEvent
        KeyboardEventTracker.addListener(listener)
        fun send(code: Int, action: Int, flags: Int = 0, repeats: Int = 0) {
            sendTracked(KeyEvent(0, 10, action, code, repeats, 0, 2, 0, flags))
        }
        try {
            send(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN)
            send(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.ACTION_DOWN)
            send(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP)
            assertTrue("Shift" in view.heldLabels())
            send(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN)
            send(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, repeats = 100)
            send(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED)
            assertEquals(setOf("Shift"), view.heldLabels())
            view.onWindowFocusChanged(false)
            assertTrue(view.heldLabels().isEmpty())
            send(KeyEvent.KEYCODE_B, KeyEvent.ACTION_DOWN)
            view.active = false
            assertTrue(view.heldLabels().isEmpty())
        } finally { KeyboardEventTracker.removeListener(listener) }
    }
}
