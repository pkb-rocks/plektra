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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private var ownShows = 0

    private fun startInput(inputType: Int, restarting: Boolean = false, packageName: String = "keypad.test"): EditorInfo {
        val info = EditorInfo().apply { this.inputType = inputType; this.packageName = packageName }
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

    private fun awaiting() = (getField(service, "hardwareKeypadAutoOpen") as HardwareKeypadAutoOpen).awaitingShowRequest

    /** Counts Plektra's own show requests; they still pass through the service's central hook. */
    private fun countOwnShows() {
        @Suppress("UNCHECKED_CAST")
        val original = getField(visibility(), "requestShowInputView") as () -> Unit
        setField(visibility(), "requestShowInputView", { ownShows++; original() })
    }

    private fun appShowRequest(flags: Int = InputMethod.SHOW_EXPLICIT) = service.onShowInputRequested(flags, false)

    private fun pressHardwareKey(keyCode: Int = KeyEvent.KEYCODE_1) {
        service.onKeyDown(keyCode, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0))
        service.onKeyUp(keyCode, KeyEvent(0L, 50L, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun pressKeypadButton() =
        (getField(service, "candidatesBarController") as CandidatesBarController).onKeypadRequested!!.invoke()

    // --- Hardware keypad auto-open while app display defaults are respected ---

    private fun assertFieldStartWaitsForTheApp(candidatesBackend: Boolean) {
        SettingsManager.setExperimentalCandidatesViewEnabled(context, candidatesBackend)
        countOwnShows()
        startInput(InputType.TYPE_CLASS_NUMBER)
        idle()
        assertEquals("no own show while the decision is open", 0, ownShows)
        assertTrue(awaiting())
        // Without an own request, even an unflagged request is the app's or Android's.
        appShowRequest(flags = 0)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        idle()
        assertTrue("the keypad itself is shown by own requests", ownShows > 0)
    }

    @Test
    fun fieldStart_requestsNoOwnShowUntilTheAppAsks_inputViewBackend() = assertFieldStartWaitsForTheApp(false)

    @Test
    fun fieldStart_requestsNoOwnShowUntilTheAppAsks_candidatesBackend() = assertFieldStartWaitsForTheApp(true)

    @Test
    fun appFirst_opensKeypad_andTheEchoOfItsShowChangesNothing() {
        countOwnShows()
        startInput(InputType.TYPE_CLASS_PHONE)
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        idle()
        appShowRequest(flags = 0)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        assertFalse(awaiting())
        service.onFinishInput()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun hardwareFirst_showsOnlyTheBar_lateEchoAndLaterRequestsKeepIt_keypadButtonStays() {
        countOwnShows()
        startInput(InputType.TYPE_CLASS_NUMBER)
        pressHardwareKey()
        assertFalse(awaiting())
        idle()
        assertEquals(1, ownShows)
        // Echo of the bar request at any time, with or without flags: no timer decides.
        appShowRequest(flags = 0)
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        pressKeypadButton()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
    }

    @Test
    fun backKeyAndOnScreenKeys_doNotSettleTheDecision() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        service.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        service.onKeyDown(KeyEvent.KEYCODE_1, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD))
        assertTrue(awaiting())
    }

    @Test
    fun hardwareConnectionRecovery_settlesTheDecisionBeforeItsOwnShow() {
        countOwnShows()
        startInput(InputType.TYPE_CLASS_NUMBER)
        // The bar of the previous field is still on screen.
        service.onWindowShown()
        visibility().onHardwareKeyboardConnected()
        idle()
        assertEquals(1, ownShows)
        assertFalse(awaiting())
        appShowRequest(flags = 0)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun configurationReshowAndRestart_neitherDecideNorStartADecision() {
        val info = startInput(InputType.TYPE_CLASS_PHONE)
        service.onShowInputRequested(InputMethod.SHOW_EXPLICIT, true)
        service.onStartInput(info, true)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertTrue(awaiting())
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())

        service.onFinishInput()
        val settled = startInput(InputType.TYPE_CLASS_NUMBER)
        pressHardwareKey()
        service.onStartInput(settled, true)
        service.onShowInputRequested(0, true)
        assertFalse(awaiting())
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun appSwitch_startsAFreshDecision_textFieldDropsIt() {
        startInput(InputType.TYPE_CLASS_NUMBER, packageName = "first.app")
        pressHardwareKey()
        service.onFinishInput()
        startInput(InputType.TYPE_CLASS_NUMBER, packageName = "second.app")
        assertTrue(awaiting())
        startInput(InputType.TYPE_CLASS_TEXT, packageName = "second.app")
        assertFalse(awaiting())
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        startInput(InputType.TYPE_CLASS_NUMBER, packageName = "first.app")
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        service.onFinishInput()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun settingsChange_appliesFromTheNextField() {
        startInput(InputType.TYPE_CLASS_NUMBER)
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertTrue(awaiting())
        service.onFinishInput()

        startInput(InputType.TYPE_CLASS_PHONE)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        assertFalse(awaiting())
        service.onFinishInput()

        SettingsManager.setHardwareKeypadAutoOpen(context, false)
        SettingsManager.setAutoShowRespectAppVisibility(context, true)
        startInput(InputType.TYPE_CLASS_NUMBER)
        assertFalse(awaiting())
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
    }

    @Test
    fun hardwareBarShowsItselfInEveryOtherField_autoOpenIsOffByDefault() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SoftwareKeyboardMode.FORCE_HARDWARE)
        countOwnShows()
        startInput(InputType.TYPE_CLASS_NUMBER)
        idle()
        assertFalse(awaiting())
        assertEquals(1, ownShows)

        SettingsManager.setHardwareKeypadAutoOpen(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, true)
        startInput(InputType.TYPE_CLASS_TEXT)
        idle()
        assertEquals(2, ownShows)
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
