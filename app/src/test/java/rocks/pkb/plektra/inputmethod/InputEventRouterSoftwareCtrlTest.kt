package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.InputDevice
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InputEventRouterSoftwareCtrlTest {
    private lateinit var context: Context
    private lateinit var router: InputEventRouter
    private lateinit var connection: InputConnection
    private lateinit var mappings: Map<Int, KeyMappingLoader.CtrlMapping>
    private var slimToggles = 0
    private var cleared = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        LayoutMappingRepository.loadLayout(context.assets, "qwerty", context)
        router = InputEventRouter(context, NavModeController(context, ModifierStateController(300L)))
        connection = mock(InputConnection::class.java)
        `when`(connection.sendKeyEvent(any(KeyEvent::class.java))).thenReturn(true)
        `when`(connection.performContextMenuAction(anyInt())).thenReturn(true)
        mappings = KeyMappingLoader.loadCtrlKeyMappings(context.assets, context)
    }

    private fun press(key: Int, oneShot: Boolean = true, shift: Boolean = false, physical: Boolean = false): Boolean = router.handleCtrlModifiedKey(
        keyCode = key,
        event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, key, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0),
            if (physical) 1 else KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            if (physical) 0 else KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD),
        inputConnection = connection, ctrlKeyMap = mappings,
        ctrlLatchFromNavMode = false, ctrlOneShot = oneShot, ctrlPhysicallyPressed = true,
        selectionShiftActive = shift,
        clearCtrlOneShot = { cleared++ }, updateStatusBar = {}, callSuper = { false },
        toggleMinimalUi = { slimToggles++ }
    )

    @Test fun softwareCtrlPInvokesSlimToggleInsteadOfSendingAppShortcut() {
        assertTrue(press(KeyEvent.KEYCODE_P))
        assertTrue(press(KeyEvent.KEYCODE_P, oneShot = false))
        assertEquals(2, slimToggles)
        assertEquals(1, cleared)
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test fun softwareCtrlBTogglesBothDirectionsWithoutChangingSavedMode() {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        SettingsManager.setSoftwareKeyboardModeToggleToastsEnabled(context, false)
        assertTrue(press(KeyEvent.KEYCODE_B))
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE,
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context))
        assertTrue(press(KeyEvent.KEYCODE_B))
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL,
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context))
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL, SettingsManager.getSoftwareKeyboardMode(context))
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test fun softwareCtrlEditingActionsUseConfiguredContextMenuActions() {
        listOf(KeyEvent.KEYCODE_A to android.R.id.selectAll, KeyEvent.KEYCODE_X to android.R.id.cut,
            KeyEvent.KEYCODE_C to android.R.id.copy, KeyEvent.KEYCODE_V to android.R.id.paste,
            KeyEvent.KEYCODE_Z to android.R.id.undo).forEach { (key, action) ->
            assertTrue(press(key))
            verify(connection).performContextMenuAction(action)
        }
        verify(connection, never()).sendKeyEvent(any(KeyEvent::class.java))
    }

    @Test fun softwareCtrlShiftVPastesPlainTextUnlessSwitchedOff() {
        assertTrue(press(KeyEvent.KEYCODE_V, shift = true))
        verify(connection).performContextMenuAction(android.R.id.pasteAsPlainText)
        SettingsManager.setClipboardCtrlShiftVPlainText(context, false)
        assertTrue(press(KeyEvent.KEYCODE_V, shift = true))
        verify(connection).performContextMenuAction(android.R.id.paste)
    }

    @Test fun physicalCtrlShiftVReachesTheEditorWithShiftOnlyWhilePlainPasteIsOn() {
        val events = ArgumentCaptor.forClass(KeyEvent::class.java)
        assertTrue(press(KeyEvent.KEYCODE_V, shift = true, physical = true))
        SettingsManager.setClipboardCtrlShiftVPlainText(context, false)
        assertTrue(press(KeyEvent.KEYCODE_V, shift = true, physical = true))
        verify(connection, times(2)).sendKeyEvent(events.capture())
        assertEquals(listOf(true, false), events.allValues.map { it.isShiftPressed })
        assertTrue(events.allValues.all { it.isCtrlPressed && it.keyCode == KeyEvent.KEYCODE_V })
        verify(connection, never()).performContextMenuAction(anyInt())
    }

    @Test fun softwareCtrlNavigationUsesMappedArrowWithoutCtrlMetaState() {
        assertTrue(press(KeyEvent.KEYCODE_D))
        val events = ArgumentCaptor.forClass(KeyEvent::class.java)
        verify(connection, times(2)).sendKeyEvent(events.capture())
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.allValues.map { it.action })
        assertTrue(events.allValues.all { it.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !it.isCtrlPressed })
    }
}
