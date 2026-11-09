package rocks.pkb.plektra.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SettingsManager.SoftwareKeyboardMode
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.SymLayoutController

/** Hardware keypad auto-open and SYM cycling through the real service callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoftwareKeypadServiceIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<PhysicalKeyboardInputMethodService>
    private lateinit var service: PhysicalKeyboardInputMethodService

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setHardwareKeypadAutoOpen(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, true)
        controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, null)
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    private fun startInput(inputType: Int, restarting: Boolean = false): EditorInfo {
        val info = EditorInfo().apply { this.inputType = inputType; packageName = "keypad.test" }
        val ic = mock(InputConnection::class.java)
        setField(service, "mInputConnection", ic)
        setField(service, "mStartedInputConnection", ic)
        setField(service, "mInputEditorInfo", info)
        service.onStartInput(info, restarting)
        return info
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun visibility() = getField(service, "keyboardVisibilityController") as KeyboardVisibilityController

    private fun mode() = SettingsManager.resolveEffectiveSoftwareKeyboardMode(context)

    // --- Hardware keypad auto-open: order of own and external show requests ---

    @Test
    fun externalShowBeforePostedOwnShow_opensKeypad() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        // Plektra's hardware-bar presentation is pending but has not asked Android yet.
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
    }

    @Test
    fun echoOfOwnHardwareBarShow_keepsHardwareBar_untilExternalShow() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        idle()
        assertNotNull("own show request must have been issued", getField(visibility(), "ownShowRequestedAt"))
        service.onShowInputRequested(0, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertEquals(true, getField(service, "pendingKeypadAutoOpen"))

        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
    }

    @Test
    fun configurationReshowAfterRestart_doesNotOpenKeypad() {
        val info = startInput(InputType.TYPE_CLASS_PHONE)
        service.onStartInput(info, true)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, true)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        // The decision still waits for a new request from the app or Android.
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
    }

    @Test
    fun configurationReshowOfOwnShow_doesNotConsumeTheEchoMarker() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        idle()
        service.onShowInputRequested(0, true)
        service.onShowInputRequested(0, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun withoutRespectingAppDefaults_keypadOpensOnInputStart() {
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        startInput(InputType.TYPE_CLASS_PHONE)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
    }

    @Test
    fun autoOpenOffOrTextField_neverOpensKeypad() {
        SettingsManager.setHardwareKeypadAutoOpen(context, false)
        startInput(InputType.TYPE_CLASS_NUMBER)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        service.onFinishInput()
        SettingsManager.setHardwareKeypadAutoOpen(context, true)
        startInput(InputType.TYPE_CLASS_TEXT)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun newEditorDropsPendingAutoOpenAndFinishRestoresHardware() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        startInput(InputType.TYPE_CLASS_TEXT)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        startInput(InputType.TYPE_CLASS_NUMBER)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, false)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        service.onFinishInput()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    // --- SYM cycle: physical key versus on-screen key ---

    private fun prepareSymField() {
        SettingsManager.setSymPagesConfig(context, SymPagesConfig(deviceEnabled = false, emojiEnabled = true, symbolsEnabled = true))
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "sym.test" }
        val ic = mock(InputConnection::class.java)
        setField(service, "mInputConnection", ic)
        setField(service, "mStartedInputConnection", ic)
        setField(service, "mInputEditorInfo", info)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(info))
    }

    private fun tapSym(onScreen: Boolean, at: Long): Int {
        fun event(action: Int, time: Long) = if (onScreen) {
            KeyEvent(at, time, action, KeyEvent.KEYCODE_SYM, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
        } else {
            KeyEvent(at, time, action, KeyEvent.KEYCODE_SYM, 0)
        }
        service.onKeyDown(KeyEvent.KEYCODE_SYM, event(KeyEvent.ACTION_DOWN, at))
        service.onKeyUp(KeyEvent.KEYCODE_SYM, event(KeyEvent.ACTION_UP, at + 50L))
        return (getField(service, "symLayoutController") as SymLayoutController).currentSymPage()
    }

    @Test
    fun physicalSym_keepsEmojiLayerWithVirtualSurface() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SoftwareKeyboardMode.FORCE_VIRTUAL)
        prepareSymField()
        assertEquals(1, tapSym(onScreen = false, at = 1_000L))
        assertEquals(2, tapSym(onScreen = false, at = 2_000L))
    }

    @Test
    fun physicalSym_keepsEmojiLayerInTemporaryKeypadMode() {
        SettingsManager.beginFieldScopedVirtualKeyboard(context)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        prepareSymField()
        assertEquals(1, tapSym(onScreen = false, at = 1_000L))
    }

    @Test
    fun onScreenSym_skipsEmojiLayerByDefault() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SoftwareKeyboardMode.FORCE_VIRTUAL)
        prepareSymField()
        assertEquals(2, tapSym(onScreen = true, at = 1_000L))
    }

    private fun getField(owner: Any, name: String): Any? = findField(owner.javaClass, name).get(owner)

    private fun setField(owner: Any, name: String, value: Any?) = findField(owner.javaClass, name).set(owner, value)

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        error("Field $name not found")
    }
}
