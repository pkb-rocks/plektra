package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Held Sym+C/V/X/A keys: once the edit action ran, the key's repeats and release stay away from the editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymEditShortcutsServiceTest {
    private lateinit var context: Context
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var connection: InputConnection

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setSymEditShortcutsEnabled(context, true)
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        connection = mock(InputConnection::class.java)
        `when`(connection.commitText(any(CharSequence::class.java), anyInt())).thenReturn(true)
        `when`(connection.performContextMenuAction(anyInt())).thenReturn(true)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "plektra.test" }
        field("mInputConnection", connection)
        field("mStartedInputConnection", connection)
        field("mInputEditorInfo", info)
        field("isInputViewActive", true)
        field("inputContextState", InputContextState.fromEditorInfo(info))
    }

    @After fun tearDown() { service.onDestroy(); DeviceSpecific.clearTestOverrides() }

    private fun field(name: String, value: Any?) {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            try { cls.getDeclaredField(name).apply { isAccessible = true }.set(service, value); return }
            catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        error(name)
    }

    private fun down(key: Int, meta: Int = 0, repeat: Int = 0) =
        service.onKeyDown(key, KeyEvent(1000L, 1100L + repeat * 50L, KeyEvent.ACTION_DOWN, key, repeat, meta))
    private fun up(key: Int, meta: Int = 0) =
        service.onKeyUp(key, KeyEvent(1000L, 2000L, KeyEvent.ACTION_UP, key, 0, meta))

    private fun verifyNothingTyped() {
        verify(connection, never()).commitText(any(CharSequence::class.java), anyInt())
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test fun forwardedShiftAndItsRepeatReachTheHeaderOnceWhileActivityOnlyKeysStillArrive() {
        val activity = Robolectric.buildActivity(rocks.pkb.plektra.MainActivity::class.java).get()
        val seen = mutableListOf<Pair<String, Int>>()
        val listener: (KeyboardEventTracker.KeyEventInfo) -> Unit = { seen += it.action to it.repeatCount }
        KeyboardEventTracker.addListener(listener)
        try {
            val shift = KeyEvent.KEYCODE_SHIFT_LEFT
            for (repeat in 0..1) {
                val event = KeyEvent(1000, 1100L + repeat, KeyEvent.ACTION_DOWN, shift, repeat)
                service.onKeyDown(shift, event)
                activity.onKeyDown(shift, KeyEvent(event))
            }
            val release = KeyEvent(1000, 1200, KeyEvent.ACTION_UP, shift, 0)
            service.onKeyUp(shift, release)
            activity.onKeyUp(shift, KeyEvent(release))
            val letter = KeyEvent.KEYCODE_A
            activity.onKeyDown(letter, KeyEvent(1300, 1300, KeyEvent.ACTION_DOWN, letter, 0))
            activity.onKeyUp(letter, KeyEvent(1300, 1400, KeyEvent.ACTION_UP, letter, 0))
            org.junit.Assert.assertEquals(listOf("KEY_DOWN" to 0, "KEY_DOWN" to 1, "KEY_UP" to 0,
                "KEY_DOWN" to 0, "KEY_UP" to 0), seen)
        } finally {
            KeyboardEventTracker.removeListener(listener)
        }
    }

    @Test fun symbolChordThatCommitsTextStillReleasesItsPhysicalKey() {
        val symbols = service.javaClass.getDeclaredField("symLayoutController").let {
            it.isAccessible = true
            it.get(service) as rocks.pkb.plektra.core.SymLayoutController
        }
        assertTrue(symbols.openSymbolsPage())
        val key = (KeyEvent.KEYCODE_E..KeyEvent.KEYCODE_U).first { symbols.resolveChordSymbol(it, false) != null }
        val symbol = symbols.resolveChordSymbol(key, false)
        val actions = mutableListOf<String>()
        val listener: (KeyboardEventTracker.KeyEventInfo) -> Unit = { if (it.keyCode == key) actions += it.action }
        KeyboardEventTracker.addListener(listener)
        try {
            down(KeyEvent.KEYCODE_SYM)
            assertTrue(down(key, KeyEvent.META_SYM_ON))
            verify(connection).commitText(symbol, 1)
            up(key, KeyEvent.META_SYM_ON)
            org.junit.Assert.assertEquals(listOf("KEY_DOWN", "KEY_UP"), actions)
        } finally {
            KeyboardEventTracker.removeListener(listener)
        }
    }

    @Test fun consumedSelectAllReleasesTheHeaderKeyExactlyOnce() {
        val view = rocks.pkb.plektra.MotionBackgroundView(context)
        val events = mutableListOf<String>()
        val listener: (KeyboardEventTracker.KeyEventInfo) -> Unit = { event ->
            view.acceptHardwareEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_A) events += event.action
        }
        KeyboardEventTracker.addListener(listener)
        try {
            down(KeyEvent.KEYCODE_SYM)
            assertTrue(down(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON))
            assertTrue("A" in view.heldLabels())
            assertTrue(up(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON))
            up(KeyEvent.KEYCODE_SYM)
            org.junit.Assert.assertFalse("A" in view.heldLabels())
            org.junit.Assert.assertEquals(listOf("KEY_DOWN", "KEY_UP"), events)
        } finally {
            KeyboardEventTracker.removeListener(listener)
        }
    }

    @Test fun repeatsWhileSymIsHeldDoNotReplaceTheSelection() {
        down(KeyEvent.KEYCODE_SYM)
        assertTrue(down(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON))
        assertTrue(down(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON, repeat = 1))
        assertTrue(down(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON, repeat = 2))
        assertTrue(up(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON))
        up(KeyEvent.KEYCODE_SYM)
        verify(connection, times(1)).performContextMenuAction(android.R.id.selectAll)
        verifyNothingTyped()
    }

    @Test fun repeatsAfterSymReleaseStayConsumedAndTheNextPressTypesAgain() {
        down(KeyEvent.KEYCODE_SYM)
        assertTrue(down(KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON))
        up(KeyEvent.KEYCODE_SYM)
        assertTrue(down(KeyEvent.KEYCODE_A, repeat = 1))
        assertTrue(down(KeyEvent.KEYCODE_A, repeat = 2))
        assertTrue(up(KeyEvent.KEYCODE_A))
        verify(connection, times(1)).performContextMenuAction(android.R.id.selectAll)
        verifyNothingTyped()

        down(KeyEvent.KEYCODE_A)
        up(KeyEvent.KEYCODE_A)
        verify(connection, times(1)).performContextMenuAction(android.R.id.selectAll)
        verify(connection, times(1)).commitText(any(CharSequence::class.java), anyInt())
    }

    @Test fun repeatsStayConsumedWhenAltJoinsOrTheSettingIsTurnedOffMidHold() {
        down(KeyEvent.KEYCODE_SYM)
        assertTrue(down(KeyEvent.KEYCODE_V, KeyEvent.META_SYM_ON))
        down(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON)
        assertTrue(down(KeyEvent.KEYCODE_V, KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON, repeat = 1))
        SettingsManager.setSymEditShortcutsEnabled(context, false)
        assertTrue(down(KeyEvent.KEYCODE_V, KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON, repeat = 2))
        assertTrue(up(KeyEvent.KEYCODE_V, KeyEvent.META_SYM_ON or KeyEvent.META_ALT_ON))
        verify(connection, times(1)).performContextMenuAction(android.R.id.paste)
        verifyNothingTyped()
    }

    @Test fun aFreshPressAfterALostReleaseTypesAgain() {
        down(KeyEvent.KEYCODE_SYM)
        assertTrue(down(KeyEvent.KEYCODE_X, KeyEvent.META_SYM_ON))
        up(KeyEvent.KEYCODE_SYM)
        // The release of X never arrives; the next initial press is a new key press.
        down(KeyEvent.KEYCODE_X)
        verify(connection, times(1)).performContextMenuAction(android.R.id.cut)
        verify(connection, times(1)).commitText(any(CharSequence::class.java), anyInt())
    }
}
