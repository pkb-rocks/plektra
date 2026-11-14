package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/**
 * Remapped Ctrl on Unihertz Q25/Titan arrives at Quick Launcher as
 * KEYCODE_CTRL_RIGHT with deviceId=-1, scanCode=0, metaState=0x5000
 * (META_CTRL_ON | META_CTRL_RIGHT_ON). Ctrl+A must select the full query;
 * typing or Backspace then replaces/deletes that selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCtrlASelectAllTest {

    @Before
    fun setUp() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 0)
    }

    @Test
    fun q25CtrlASelectsFullQueryAndTypingOrBackspaceReplacesSelection() {
        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()

        var t = 10_000L
        listOf(
            KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_T
        ).forEach { code ->
            assertTrue(
                activity.dispatchKeyEvent(
                    hardwareKey(KeyEvent.ACTION_DOWN, code, t, scanCode = 0, metaState = 0)
                )
            )
            activity.dispatchKeyEvent(
                hardwareKey(KeyEvent.ACTION_UP, code, t + 10, scanCode = 0, metaState = 0)
            )
            t += 40
        }
        assertEquals("test", activity.debugQueryText())

        // Exact remapped-Ctrl shape observed on device.
        val ctrlDown = hardwareKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_CTRL_RIGHT,
            eventTime = t,
            scanCode = 0,
            metaState = 0x5000,
            deviceId = -1
        )
        val aDown = hardwareKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_A,
            eventTime = t + 40,
            scanCode = 30,
            metaState = 0x5000,
            deviceId = -1
        )
        val aUp = hardwareKey(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_A,
            eventTime = t + 80,
            scanCode = 30,
            metaState = 0x5000,
            deviceId = -1
        )
        val ctrlUp = hardwareKey(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_CTRL_RIGHT,
            eventTime = t + 120,
            scanCode = 0,
            metaState = 0x0,
            deviceId = -1
        )

        assertTrue(activity.dispatchKeyEvent(ctrlDown))
        assertTrue(activity.dispatchKeyEvent(aDown))
        activity.dispatchKeyEvent(aUp)
        activity.dispatchKeyEvent(ctrlUp)

        assertEquals("test", activity.debugQueryText())
        assertEquals(
            "Ctrl+A must select the full query text",
            0 to 4,
            activity.debugQuerySelectionStart() to activity.debugQuerySelectionEnd()
        )

        t += 200
        assertTrue(
            activity.dispatchKeyEvent(
                hardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, t, scanCode = 45, metaState = 0)
            )
        )
        assertEquals("x", activity.debugQueryText())
        assertEquals(
            1 to 1,
            activity.debugQuerySelectionStart() to activity.debugQuerySelectionEnd()
        )

        t += 100
        assertTrue(
            activity.dispatchKeyEvent(
                hardwareKey(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_CTRL_RIGHT,
                    t,
                    scanCode = 0,
                    metaState = 0x5000,
                    deviceId = -1
                )
            )
        )
        assertTrue(
            activity.dispatchKeyEvent(
                hardwareKey(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_A,
                    t + 20,
                    scanCode = 30,
                    metaState = 0x5000,
                    deviceId = -1
                )
            )
        )
        assertEquals(0 to 1, activity.debugQuerySelectionStart() to activity.debugQuerySelectionEnd())
        assertTrue(
            activity.dispatchKeyEvent(
                hardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, t + 60, scanCode = 14, metaState = 0)
            )
        )
        assertEquals("", activity.debugQueryText())
        assertEquals(0 to 0, activity.debugQuerySelectionStart() to activity.debugQuerySelectionEnd())
    }

    private fun hardwareKey(
        action: Int,
        keyCode: Int,
        eventTime: Long,
        scanCode: Int,
        metaState: Int,
        deviceId: Int = 6,
        downTime: Long = eventTime
    ): KeyEvent {
        return KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            /* repeat */ 0,
            metaState,
            deviceId,
            scanCode
        )
    }
}
