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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils

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
    fun firstHardwareAltSpace_settlesBeforeLanguageSwitch_andLaterAppShowKeepsHardwareMode() {
        SettingsManager.setAltSpacePreviousLanguageEnabled(context, true)
        startInput(InputType.TYPE_CLASS_NUMBER)
        service.onKeyDown(KeyEvent.KEYCODE_SPACE,
            KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_ALT_ON))
        assertFalse("a hardware shortcut must settle the keypad choice", awaiting())
        idle()
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
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

    // --- Per-app "never show the bar" outranks every keypad policy ---

    private fun symPage() = (getField(service, "symLayoutController") as SymLayoutController).currentSymPage()

    private fun chooseOnScreenPage(id: String) {
        (getField(service, "candidatesBarController") as CandidatesBarController).onSoftwareKeyboardPageChoice!!.invoke(id)
        idle()
    }

    @Test
    fun blockedApp_autoKeypadNeitherWaitsNorOpens_appRequestsAreRefused() {
        SettingsManager.setKeyboardBarHiddenInApp(context, "blocked.app", true)
        countOwnShows()
        startInput(InputType.TYPE_CLASS_NUMBER, packageName = "blocked.app")
        idle()
        assertFalse("no decision may wait for a later unblock", awaiting())
        assertFalse(appShowRequest())
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        service.onFinishInput()
        startInput(InputType.TYPE_CLASS_PHONE, packageName = "blocked.app")
        idle()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertEquals(0, ownShows)
    }

    @Test
    fun blockedApp_keypadButtonAndPageChooserOpenNothing() {
        SettingsManager.setSymPagesConfig(context, SymPagesConfig(clipboardEnabled = true, emojiPickerEnabled = true))
        SettingsManager.setKeyboardBarHiddenInApp(context, "blocked.app", true)
        startInput(InputType.TYPE_CLASS_TEXT, packageName = "blocked.app")
        pressKeypadButton()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        chooseOnScreenPage(SoftwareKeyboardPageChoices.pageId(4))
        chooseOnScreenPage(SoftwareKeyboardPageChoices.pageId(2))
        assertEquals(0, symPage())
        chooseOnScreenPage(SoftwareKeyboardPageChoices.NUMBER)
        assertEquals(null, (getField(service, "softwareKeypadSession") as SoftwareKeypadSession).manualKeypad)
    }

    @Test
    fun blockingTheCurrentApp_settlesAnOpenDecisionAndEndsTheFieldKeypad() {
        startInput(InputType.TYPE_CLASS_NUMBER, packageName = "later.blocked")
        assertTrue(awaiting())
        SettingsManager.setKeyboardBarHiddenInApp(context, "later.blocked", true)
        idle()
        assertFalse(awaiting())
        SettingsManager.setKeyboardBarHiddenInApp(context, "later.blocked", false)
        idle()
        appShowRequest()
        assertEquals("the app's request after the unblock belongs to no decision", SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        service.onFinishInput()
        startInput(InputType.TYPE_CLASS_PHONE, packageName = "later.blocked")
        appShowRequest()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        SettingsManager.setKeyboardBarHiddenInApp(context, "later.blocked", true)
        idle()
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertFalse(visibility().allowsSurface())
    }

    @Test
    fun blockedApp_physicalSymCycleSkipsOnlyThePickers() {
        SettingsManager.setSymPagesConfig(context,
            SymPagesConfig(deviceEnabled = false, emojiEnabled = true, symbolsEnabled = true,
                clipboardEnabled = true, emojiPickerEnabled = true))
        SettingsManager.setKeyboardBarHiddenInApp(context, "sym.test", true)
        prepareSymField()
        setField(service, "keyboardBarHiddenInEditor", true)
        assertEquals(1, tapSym(onScreen = false, at = 1_000L))
        assertEquals(2, tapSym(onScreen = false, at = 2_000L))
        assertEquals(0, tapSym(onScreen = false, at = 3_000L))
    }

    @Test
    fun blockedApp_emojiLayerSearchKeyOpensNoPickerAndLeavesNoSearchPending() {
        prepareSymField()
        SettingsManager.setSymPagesConfig(context,
            SymPagesConfig(deviceEnabled = false, emojiEnabled = true, symbolsEnabled = true, emojiPickerEnabled = true))
        SettingsManager.setKeyboardBarHiddenInApp(context, "sym.test", true)
        setField(service, "keyboardBarHiddenInEditor", true)
        assertEquals(1, tapSym(onScreen = false, at = 1_000L))
        // P leaves the layer's own page for a generated one, where A is the search key.
        pressHardwareKey(KeyEvent.KEYCODE_P)
        pressHardwareKey(KeyEvent.KEYCODE_A)
        assertEquals("the text layer stays, no picker opens", 1, symPage())
        assertFalse(bar().isEmojiPickerSearchInputActive())
        for (statusBar in listOf("inputStatusBar", "candidatesStatusBar")) {
            assertFalse("no search may wait for the picker's next opening ($statusBar)",
                getField(getField(bar(), statusBar)!!, "emojiPickerSearchRequested") as Boolean)
        }

        SettingsManager.setKeyboardBarHiddenInApp(context, "sym.test", false)
        setField(service, "keyboardBarHiddenInEditor", false)
        pressHardwareKey(KeyEvent.KEYCODE_A)
        assertEquals("unblocked, the same key opens the picker", 4, symPage())
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

    @Test
    fun pagedLayers_physicalSymTurnsTheEmojiPages_onScreenSymStartsOnTheSymbolPages() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SoftwareKeyboardMode.FORCE_VIRTUAL)
        prepareSymField()
        val sym = getField(service, "symLayoutController") as SymLayoutController
        assertEquals(1, tapSym(onScreen = false, at = 1_000L))
        sym.currentSymMappings()
        assertEquals(rocks.pkb.plektra.core.SymPagedLayer.Kind.EMOJI, SymLayoutController.shownPagedKind)
        pressHardwareKey(KeyEvent.KEYCODE_P)
        assertEquals(1, getField(sym, "pageIndex"))
        assertEquals("a page turn keeps the layer open", 1, symPage())

        assertEquals(2, tapSym(onScreen = false, at = 2_000L))
        assertEquals(0, tapSym(onScreen = false, at = 3_000L))
        assertEquals("the on-screen SYM key leaves out the emoji layer", 2, tapSym(onScreen = true, at = 4_000L))
        sym.currentSymMappings()
        assertEquals(rocks.pkb.plektra.core.SymPagedLayer.Kind.SYMBOLS, SymLayoutController.shownPagedKind)
        assertEquals(0, getField(sym, "pageIndex"))
    }

    // --- Uyghur layout switches inside keypad policies ---

    private fun modifiers() = getField(service, "modifierStateController") as ModifierStateController

    private fun bar() = getField(service, "candidatesBarController") as CandidatesBarController

    private fun startOnQwerty(inputType: Int, packageName: String = "keypad.test") {
        SettingsManager.setKeyboardLayoutAutoByLocale(context, false)
        SettingsManager.setKeyboardLayout(context, "qwerty")
        SettingsManager.setAutoCorrectEnabled(context, false)
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        startInput(inputType, packageName = packageName)
    }

    private fun switchSubtype(prefValue: String) {
        val subtype = AdditionalSubtypeUtils.createAdditionalSubtypesArray(prefValue, service.assets, service).single()
        PhysicalKeyboardInputMethodService::class.java.getDeclaredMethod(
            "onCurrentInputMethodSubtypeChanged", android.view.inputmethod.InputMethodSubtype::class.java
        ).apply { isAccessible = true }.invoke(service, subtype)
    }

    @Test
    fun blockedApp_switchingToUyghurAndBack_opensNothing_fieldCapsLockFollowsTheLayout() {
        SettingsManager.setKeyboardBarHiddenInApp(context, "blocked.app", true)
        countOwnShows()
        startOnQwerty(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, packageName = "blocked.app")
        assertTrue(modifiers().capsLockEnabled)

        switchSubtype("ug:uyghur")
        idle()
        assertFalse(modifiers().capsLockEnabled)
        pressKeypadButton()
        assertFalse(appShowRequest())
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())

        switchSubtype("en_US:qwertz")
        idle()
        assertTrue(modifiers().capsLockEnabled)
        assertTrue(modifiers().capsLockAutomatic)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertEquals(0, ownShows)
        assertFalse(visibility().allowsSurface())
    }

    /**
     * Stands in for Android's side of the window: Plektra's own show and hide requests are
     * recorded, and the test answers a show the way a successful one ends, with
     * onStartInputView and onWindowShown. Robolectric would answer neither.
     */
    private inner class ModeledImeWindow {
        var shown = false
        var showRequests = 0
        var hideRequests = 0

        init {
            val controller = visibility()
            @Suppress("UNCHECKED_CAST")
            val ownShow = getField(controller, "requestShowInputView") as () -> Unit
            setField(controller, "requestShowInputView", { showRequests++; ownShow() })
            setField(controller, "requestHideInputView", { hideRequests++ })
            setField(controller, "hideInputWindow", { hideRequests++ })
            setField(controller, "isInputViewShown", { shown })
            setField(controller, "renderedSurface", {
                if (shown) KeyboardVisibilityController.RenderedSurface.FULL_INPUT_VIEW
                else KeyboardVisibilityController.RenderedSurface.HIDDEN
            })
        }

        /** The framework shows the window for Plektra's pending own request. */
        fun answerShow(info: EditorInfo) {
            service.onStartInputView(info, false)
            shown = true
            service.onWindowShown()
            idle()
        }

        /** Back: the framework hides the window, the editor session stays. */
        fun hideByUser() {
            service.onFinishInputView(false)
            shown = false
            service.onWindowHidden()
            idle()
        }
    }

    @Test
    fun temporaryKeypadOnUyghur_reopenThroughHideAndShow_fieldCapsLockFollowsTheLayout_softShiftTypesTheMultiCharacterLetter() {
        startOnQwerty(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val info = getField(service, "mInputEditorInfo") as EditorInfo
        val window = ModeledImeWindow()
        idle()
        assertEquals("the hardware bar shows itself", 1, window.showRequests)
        window.answerShow(info)
        pressKeypadButton()
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        idle()
        assertEquals("the shown input view takes the keypad without a new show", 1, window.showRequests)

        window.hideByUser()
        assertEquals("Back keeps the field's temporary keypad", SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        pressKeypadButton()
        idle()
        assertEquals(2, window.showRequests)
        window.answerShow(info)
        assertEquals("an answered show neither retries nor closes", 2, window.showRequests)
        assertEquals(0, window.hideRequests)
        assertTrue(modifiers().capsLockEnabled)
        assertTrue(modifiers().capsLockAutomatic)

        switchSubtype("ug:uyghur")
        idle()
        assertFalse("Uyghur suspends the field's Caps Lock", modifiers().capsLockEnabled)
        bar().onSoftwareKeyboardShiftTapped!!.invoke()
        assertTrue(modifiers().shiftOneShot)
        val ic = getField(service, "mStartedInputConnection") as InputConnection
        `when`(ic.commitText(any(), anyInt())).thenReturn(true)
        val snapshot = StatusBarController.StatusSnapshot(
            capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = true,
            ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
            ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
            altOneShot = false, symPage = 0, activeKeyboardLayoutName = "uyghur"
        )
        assertTrue(bar().onSoftwareKeyboardTextInput!!.invoke("لا", ic, snapshot))
        assertFalse(modifiers().shiftOneShot)

        switchSubtype("en_US:qwertz")
        idle()
        assertTrue("Latin restores the field's Caps Lock", modifiers().capsLockEnabled)
        assertTrue(modifiers().capsLockAutomatic)
        assertEquals(SoftwareKeyboardMode.FORCE_VIRTUAL, mode())
        assertTrue(window.shown)
        assertEquals(2, window.showRequests)
        assertEquals(0, window.hideRequests)

        service.onFinishInput()
        startInput(InputType.TYPE_CLASS_TEXT)
        assertEquals(SoftwareKeyboardMode.FORCE_HARDWARE, mode())
        assertFalse(modifiers().capsLockEnabled)
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
