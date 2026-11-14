package rocks.pkb.plektra.inputmethod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * Ctrl+C/X act only on the active search-field selection; Ctrl+V replaces the
 * selection or appends at the caret.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherSelectionClipboardTest {

    private lateinit var activity: QuickLauncherActivity
    private lateinit var clipboard: ClipboardManager
    private var t = 10_000L

    @Before
    fun setUp() {
        SettingsManager.setQuickLauncherAnimationDurationMs(RuntimeEnvironment.getApplication(), 0)
        activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()
        clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("seed", "clip"))
    }

    @Test
    fun ctrlCWithoutSelectionLeavesQueryAndClipboardUnchanged() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T)

        assertTrue(ctrl(KeyEvent.KEYCODE_C))

        assertEquals("test", activity.debugQueryText())
        assertSelection(4, 4)
        assertEquals("clip", clipboardText())
    }

    @Test
    fun ctrlXWithoutSelectionLeavesQueryAndClipboardUnchanged() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T)

        assertTrue(ctrl(KeyEvent.KEYCODE_X))

        assertEquals("test", activity.debugQueryText())
        assertSelection(4, 4)
        assertEquals("clip", clipboardText())
    }

    @Test
    fun ctrlCWithSelectionCopiesSelectionAndKeepsQuery() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T)
        assertTrue(ctrl(KeyEvent.KEYCODE_A))

        assertTrue(ctrl(KeyEvent.KEYCODE_C))

        assertEquals("test", activity.debugQueryText())
        assertSelection(0, 4)
        assertEquals("test", clipboardText())
    }

    @Test
    fun ctrlXWithSelectionCutsSelectionAndCollapsesCaret() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T)
        assertTrue(ctrl(KeyEvent.KEYCODE_A))

        assertTrue(ctrl(KeyEvent.KEYCODE_X))

        assertEquals("", activity.debugQueryText())
        assertSelection(0, 0)
        assertEquals("test", clipboardText())

        // A second Ctrl+X without selection must not clobber the cut text.
        assertTrue(ctrl(KeyEvent.KEYCODE_X))
        assertEquals("test", clipboardText())

        // Typing after the cut starts a fresh query.
        type(KeyEvent.KEYCODE_A)
        assertEquals("a", activity.debugQueryText())
        assertSelection(1, 1)
    }

    @Test
    fun ctrlVWithoutSelectionAppendsAtCaret() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E)

        assertTrue(ctrl(KeyEvent.KEYCODE_V))

        assertEquals("teclip", activity.debugQueryText())
        assertSelection(6, 6)
        assertEquals("clip", clipboardText())
    }

    @Test
    fun ctrlVWithSelectionReplacesSelection() {
        type(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T)
        assertTrue(ctrl(KeyEvent.KEYCODE_A))

        assertTrue(ctrl(KeyEvent.KEYCODE_V))

        assertEquals("clip", activity.debugQueryText())
        assertSelection(4, 4)
        assertEquals("clip", clipboardText())
    }

    private fun type(vararg codes: Int) {
        codes.forEach { code ->
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, metaState = 0)))
            activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, metaState = 0))
        }
    }

    /** Remapped Q25/Titan Ctrl shape: deviceId=-1, META_CTRL_ON | META_CTRL_RIGHT_ON. */
    private fun ctrl(code: Int): Boolean {
        val meta = 0x5000
        assertTrue(
            activity.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_RIGHT, metaState = meta, deviceId = -1)
            )
        )
        val handled = activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, metaState = meta, deviceId = -1))
        activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, metaState = meta, deviceId = -1))
        activity.dispatchKeyEvent(
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_RIGHT, metaState = 0, deviceId = -1)
        )
        return handled
    }

    private fun key(action: Int, keyCode: Int, metaState: Int, deviceId: Int = 6): KeyEvent {
        t += 40
        return KeyEvent(t, t, action, keyCode, /* repeat */ 0, metaState, deviceId, /* scanCode */ 0)
    }

    private fun clipboardText(): String? =
        clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun assertSelection(start: Int, end: Int) {
        assertEquals(
            start to end,
            activity.debugQuerySelectionStart() to activity.debugQuerySelectionEnd()
        )
    }
}
