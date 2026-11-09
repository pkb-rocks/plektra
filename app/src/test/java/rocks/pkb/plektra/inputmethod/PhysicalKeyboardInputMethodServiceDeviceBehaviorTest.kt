package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import java.lang.reflect.Field
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhysicalKeyboardInputMethodServiceDeviceBehaviorTest {

    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var serviceController: ServiceController<PhysicalKeyboardInputMethodService>
    private lateinit var recorder: RecordingInputConnection
    private lateinit var inputConnection: InputConnection
    private lateinit var editorInfo: EditorInfo

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        SettingsManager.setAutoCapitalizeRespectManualShiftOff(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, false)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setAppEnterBehaviorEnabled(context, true)
        SettingsManager.setAppEnterBehaviorPreset(
            context,
            SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_SEND_SHIFT_NEWLINE
        )
        SettingsManager.setAppEnterBehaviorOverrides(context, emptyList())

        serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = serviceController.get()

        recorder = RecordingInputConnection()
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }

        // Inject active editable-field context so onKeyDown/onKeyUp run the same path as on device.
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
    }

    @After
    fun tearDown() {
        try {
            if (::serviceController.isInitialized) serviceController.destroy()
        } finally {
            DeviceSpecific.clearTestOverrides()
        }
    }

    @Test
    fun mp01DedicatedEmojiKey_isIgnoredWhenMp01IsNotActive() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "titan2",
            product = "titan2"
        )
        setField(service, "physicalKeyboardProfileOverride", "auto")

        val handled = service.onKeyDown(
            666,
            keyEvent(KeyEvent.ACTION_DOWN, 666, 6_000L, 6_000L)
        )

        assertFalse(handled)
    }

    @Test
    fun mp01DedicatedEmojiKey_isHandledWhenManualMp01OverrideIsActive() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "titan2",
            product = "titan2"
        )
        setField(service, "physicalKeyboardProfileOverride", "mp01")

        val handled = service.onKeyDown(
            666,
            keyEvent(KeyEvent.ACTION_DOWN, 666, 6_100L, 6_100L)
        )

        assertTrue(handled)
    }

    @Test
    fun ctrlHoldShortcutRelease_doesNotLeaveCtrlActive() {
        val t0 = 1_000L

        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 120L,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 150L,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_CTRL_LEFT,
                downTime = t0,
                eventTime = t0 + 420L
            )
        )

        val modifier = modifierController()
        assertFalse(modifier.ctrlPressed)
        assertFalse(modifier.ctrlPhysicallyPressed)
        assertFalse(modifier.ctrlOneShot)
        assertFalse(modifier.ctrlLatchActive)
    }

    /** The emoji picker's search open for typing, as the software keyboard's picker page has it. */
    private fun openEmojiPickerSearch(): rocks.pkb.plektra.inputmethod.ui.EmojiPickerView {
        val context = RuntimeEnvironment.getApplication()
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        if (sym.currentSymPage() != 4) sym.openEmojiPickerPage()
        val bars: CandidatesBarController = getField(service, "candidatesBarController")
        val picker = rocks.pkb.plektra.inputmethod.ui.EmojiPickerView(context)
        setField(getField<StatusBarController>(bars, "inputStatusBar"), "emojiPickerView", picker)
        picker.showSearch()
        assertTrue(bars.isEmojiPickerSearchInputActive())
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("emoji", "🐱"))
        return picker
    }

    private fun searchText(picker: rocks.pkb.plektra.inputmethod.ui.EmojiPickerView): String =
        getField<android.widget.EditText>(picker, "searchField").text.toString()

    private fun pressKey(keyCode: Int, start: Long, metaState: Int = 0) {
        service.onKeyDown(keyCode, keyEvent(KeyEvent.ACTION_DOWN, keyCode, start, start, metaState))
        service.onKeyUp(keyCode, keyEvent(KeyEvent.ACTION_UP, keyCode, start, start + 30L, metaState))
    }

    @Test
    fun hardwareCtrlVIntoTheEmojiSearchPastesAndLeavesCtrlOff() {
        val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for ((round, holdMs) in listOf(150L, 420L).withIndex()) {
            val picker = openEmojiPickerSearch()
            val t0 = 6_000L + round * 1_000L
            service.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0, ctrlMeta))
            pressKey(KeyEvent.KEYCODE_V, t0 + 60L, ctrlMeta)
            service.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0 + holdMs))

            assertEquals("held ${holdMs}ms", "🐱", searchText(picker))
            val modifier = modifierController()
            assertFalse("held ${holdMs}ms", modifier.ctrlOneShot)
            assertFalse(modifier.ctrlLatchActive)
            assertFalse(modifier.ctrlPressed)
            assertFalse(modifier.ctrlPhysicallyPressed)
            // The next key types its letter into the search, without Ctrl
            pressKey(KeyEvent.KEYCODE_A, t0 + 600L)
            assertEquals("🐱a", searchText(picker))
            assertEquals(emptyList<String>(), recorder.committedTexts)
        }
    }

    @Test
    fun ctrlVIntoTheEmojiSearchUsesUpACtrlOneShotButKeepsALatchedCtrl() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.ctrlOneShot = true
        pressKey(KeyEvent.KEYCODE_V, 7_000L)
        assertEquals("🐱", searchText(picker))
        assertFalse(modifier.ctrlOneShot)
        pressKey(KeyEvent.KEYCODE_A, 7_100L)
        assertEquals("🐱a", searchText(picker))

        // A latched Ctrl stays latched: the user wants several shortcuts
        modifier.ctrlLatchActive = true
        pressKey(KeyEvent.KEYCODE_V, 7_200L)
        assertEquals("🐱a🐱", searchText(picker))
        assertTrue(modifier.ctrlLatchActive)
    }

    @Test
    fun altDoubleTapLatch_thenSingleTapFullyDeactivates() {
        val t0 = 2_000L

        tapAlt(t0)          // one-shot
        tapAlt(t0 + 120L)   // latch (+ visual altModifierLayerLatched on quick double tap)
        tapAlt(t0 + 240L)   // user expects off; device reports still active

        val modifier = modifierController()
        val altModifierLayerLatched = getField<Boolean>(service, "altModifierLayerLatched")

        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)
        assertFalse(altModifierLayerLatched)
    }

    @Test
    fun shiftDoubleTapLatch_thenSingleTapFullyDeactivates() {
        val t0 = 2_500L

        tapShift(t0)          // one-shot
        tapShift(t0 + 120L)   // latch (+ visual shiftLayerLatched on quick double tap)
        tapShift(t0 + 240L)   // user expects off

        val modifier = modifierController()
        val shiftLayerLatched = getField<Boolean>(service, "shiftLayerLatched")

        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
        assertFalse(shiftLayerLatched)
    }

    @Test
    fun clicksPower_bouncedShiftTapDoesNotEnableCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        val t0 = 2_600L

        tapShift(t0)
        tapShift(t0 + 60L)

        val modifier = modifierController()
        assertTrue(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
        assertFalse(getField<Boolean>(service, "shiftLayerLatched"))
    }

    @Test
    fun clicksPower_intentionalShiftDoubleTapStillEnablesCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        val t0 = 2_700L

        tapShift(t0)
        tapShift(t0 + 120L)

        val modifier = modifierController()
        assertFalse(modifier.shiftOneShot)
        assertTrue(modifier.capsLockEnabled)
        assertTrue(getField<Boolean>(service, "shiftLayerLatched"))
    }

    @Test
    fun clicksPower_sameFrameShiftMacroDoesNotToggleLogicalShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")

        repeat(4) { index ->
            typeClicksSyntheticAtMacro(3_000L + index * 400L)
            val modifier = modifierController()
            assertFalse(modifier.shiftOneShot)
            assertFalse(modifier.capsLockEnabled)
            assertFalse(modifier.shiftPressed)
            assertFalse(modifier.shiftPhysicallyPressed)
        }
    }

    @Test
    fun shiftEnter_consumesManualShift_whenAutoCapitalizeAtLineStartIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        val t0 = 2_700L

        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0 + 120L)
        )

        val modifier = modifierController()
        assertFalse(modifier.shiftPressed)
        assertFalse(modifier.shiftPhysicallyPressed)
        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)

        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 200L, t0 + 200L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("a"))
        assertFalse("commits=${recorder.committedTexts}", recorder.committedTexts.contains("A"))
    }

    @Test
    fun appEnter_knownFacebookMessengerWithoutOverride_usesEditorAction() {
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            packageName = "com.facebook.orca"
        )
        recorder.performEditorActionResult = true

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperWithoutOverride_usesEditorAction() {
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperShiftEnter_insertsNewlineInsteadOfSending() {
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 2_740L, 2_740L))
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L, KeyEvent.META_SHIFT_ON))
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperNativeOverride_preservesNativeEnterAfterUpdate() {
        SettingsManager.setAppEnterBehaviorOverride(RuntimeEnvironment.getApplication(),
            "com.beeper.android", SettingsManager.ENTER_BEHAVIOR_APP_DEFAULT)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun appEnter_beeperExplicitNewline_winsOverSendPreset() {
        SettingsManager.setAppEnterBehaviorOverride(RuntimeEnvironment.getApplication(),
            "com.beeper.android", SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperDisabled_doesNotSend() {
        SettingsManager.setAppEnterBehaviorEnabled(RuntimeEnvironment.getApplication(), false)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun appEnter_facebookMessengerManualEditorStrategy_usesEditorAction() {
        configureAppEnterOverride(
            packageName = "com.facebook.orca",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_EDITOR_ACTION
        )
        recorder.performEditorActionResult = true

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_755L, 2_755L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualPlainEnter_sendsExactKeyPair_forUnlistedApp() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_PLAIN_ENTER
        )
        recorder.sendKeyEventResult = false

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_760L, 2_760L)
        )

        assertTrue(handled)
        assertEquals(2, recorder.sentKeyEvents.size)
        assertTrue(recorder.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_ENTER })
        assertFalse(recorder.sentKeyEvents.any { it.isCtrlPressed })
        assertTrue(recorder.editorActions.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualCtrlEnter_sendsExactModifiedKeyPair_forUnlistedApp() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_CTRL_ENTER
        )

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_770L, 2_770L)
        )

        assertTrue(handled)
        assertEquals(2, recorder.sentKeyEvents.size)
        assertTrue(recorder.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_ENTER })
        assertTrue(recorder.sentKeyEvents.all { it.isCtrlPressed })
        assertTrue(recorder.editorActions.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualEditorAction_doesNotFallBackToNewline_whenRejected() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_EDITOR_ACTION
        )
        recorder.performEditorActionResult = false

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_780L, 2_780L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun capWords_allAutoCapDisabled_clicksPowerAltPThenAStaysLowercase() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        getField<AlternateCharacterManager>(service, "alternateCharacterManager")
            .reloadModifierAndDeviceSymMappings()
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )

        assertFalse(modifierController().shiftOneShot)

        val t0 = 2_800L
        tapAlt(t0)
        service.onKeyDown(
            KeyEvent.KEYCODE_P,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, t0 + 40L, t0 + 40L)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_P,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, t0 + 40L, t0 + 60L)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 80L, t0 + 80L)
        )

        assertEquals("@a", recorder.textBeforeCursor)
        assertFalse(
            AutoCapitalizeHelper.shouldCapitalizeAfterBoundary(
                context = context,
                state = InputContextState.fromEditorInfo(editorInfo),
                inputConnection = inputConnection,
                keyCode = KeyEvent.KEYCODE_SPACE
            )
        )
    }

    @Test
    fun autoCap_manualShiftOff_doesNotLeakIntoAnotherEmptyField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(2_900L)
        assertFalse(modifierController().shiftOneShot)

        focusNewField(RecordingInputConnection())

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun uyghurLayout_neverAutomaticallySelectsExtraLettersButManualShiftWorks() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "uyghur")
        LayoutMappingRepository.loadLayout(service.assets, "uyghur", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

        service.onStartInput(editorInfo, false)
        assertFalse(modifierController().shiftOneShot)
        assertFalse(LayoutMappingRepository.supportsAutoCapitalization)
        tapShift(3_000L)
        assertTrue(modifierController().shiftOneShot)

        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertFalse(modifierController().capsLockEnabled)

        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        assertTrue(LayoutMappingRepository.supportsAutoCapitalization)
    }

    @Test
    fun switchingToUyghur_clearsPendingCapitalizationFromEnglish() {
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)
        switchSubtype("ug:uyghur")
        assertFalse(modifierController().shiftOneShot)
        assertEquals("د", LayoutMappingRepository.getLowercase(KeyEvent.KEYCODE_D))
    }

    @Test
    fun switchingToUyghur_suspendsFieldCapsLockAndRestoresItOnLatin() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        SettingsManager.setAutoCorrectEnabled(service, false)
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().capsLockEnabled)

        switchSubtype("ug:uyghur")
        assertFalse(modifierController().capsLockEnabled)
        service.onKeyDown(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 10_000L, 10_000L))
        service.onKeyUp(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, 10_000L, 10_030L))
        assertEquals("د", recorder.committedTexts.joinToString(""))

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockEnabled)
    }

    @Test
    fun switchingToUyghur_keepsCapsLockTheUserTurnedOn() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        service.onStartInput(editorInfo, false)
        modifierController().capsLockEnabled = true

        switchSubtype("ug:uyghur")
        assertTrue(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartOnUyghur_keepsFieldCapsLockSuspendedForLatin() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
        assertFalse(modifierController().capsLockEnabled)

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun manualCapsLock_staysUserOwnedAcrossFieldRestartAndUyghurSwitch() {
        SettingsManager.setStaticVariationBarLayerStickyEnabled(service, false)
        startCapCharactersFieldOnQwerty()
        assertTrue(modifierController().capsLockAutomatic)
        tapShift(10_000L)
        tapShift(10_050L)
        assertTrue(modifierController().capsLockEnabled)
        assertFalse(modifierController().capsLockAutomatic)

        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
        assertFalse(modifierController().capsLockAutomatic)

        switchSubtype("ug:uyghur")
        assertTrue(modifierController().capsLockEnabled)
        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockEnabled)
        assertFalse(modifierController().capsLockAutomatic)
    }

    @Test
    fun shiftHeldAcrossSwitchToUyghur_doesNotRestoreFieldCapsLock() {
        SettingsManager.setAutoCorrectEnabled(service, false)
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        switchSubtype("ug:uyghur")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)

        service.onKeyDown(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 11_000L, 11_000L))
        service.onKeyUp(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, 11_000L, 11_030L))
        assertEquals("د", recorder.committedTexts.joinToString(""))

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun shiftHeldAcrossSwitchToLatin_restoresSuspendedFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        assertFalse(modifierController().capsLockEnabled)

        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        switchSubtype("en_US:qwertz")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHoldOnUyghur_doesNotBringFieldCapsLockBackOnLatin() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockAutomatic)
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHoldOnLatin_doesNotRestoreFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)

        switchSubtype("ug:uyghur")
        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHeldAcrossSwitchToLatin_doesNotRestoreFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        switchSubtype("en_US:qwertz")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHeldAcrossSwitchToUyghur_doesNotRestoreFieldCapsLockOnLatin() {
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        switchSubtype("ug:uyghur")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsOnUyghurWithoutHold_keepsRequestCleared() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        restartFieldWithoutCapCharacters()
        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsOnLatinWithoutHold_turnsFieldCapsLockOff() {
        startCapCharactersFieldOnQwerty()
        restartFieldWithoutCapCharacters()
        assertFalse(modifierController().capsLockEnabled)
    }

    private fun restartFieldWithoutCapCharacters() {
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
    }

    private fun startCapCharactersFieldOnQwerty() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun midWordQuoteToApostrophe_keepsTheLayoutOutputOfTheNextKey() {
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals("ب'د", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_D))
        assertEquals("ب'لا", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_L, shift))
        assertEquals("ب'ئ", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_SLASH))
        assertEquals("а'в", typeAfterMidWordQuote("russian_standard", "а", KeyEvent.KEYCODE_D))
        assertEquals("α'δ", typeAfterMidWordQuote("greek", "α", KeyEvent.KEYCODE_D))
        assertEquals("a'd", typeAfterMidWordQuote("qwerty", "a", KeyEvent.KEYCODE_D))
        assertEquals("a'D", typeAfterMidWordQuote("qwerty", "a", KeyEvent.KEYCODE_D, shift))
    }

    @Test
    fun greekPunctuationOnLetterKey_isCommittedOnceAsPunctuation() {
        useLayout("greek")
        recorder.textBeforeCursor = "αβ"
        val time = 10_000L
        service.onKeyDown(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, time, time))
        service.onKeyUp(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, time, time + 30L))
        assertEquals("αβ;", recorder.textBeforeCursor)
    }

    @Test
    fun softShiftOnUyghur_isConsumedOnceByTheMultiCharacterTextOfTheNextSoftKey() {
        useLayout("uyghur")
        val keyboard = SoftwareKeyboardHarness()

        keyboard.tap("⇧")
        assertTrue(modifierController().shiftOneShot)
        keyboard.tap("l")
        assertFalse(modifierController().shiftOneShot)
        keyboard.tap("k")

        assertEquals(listOf("لا", "ك"), recorder.committedTexts)
    }

    @Test
    fun softShiftOnLatin_isConsumedOnceByTheNextSoftKey() {
        useLayout("qwerty")
        val keyboard = SoftwareKeyboardHarness()

        keyboard.tap("⇧")
        keyboard.tap("a")
        keyboard.tap("b")

        assertEquals(listOf("A", "b"), recorder.committedTexts)
    }

    /**
     * The software keyboard view wired to the service's own callbacks: taps go through
     * AospKeyboardView touch handling, and every tap re-renders it from the service's
     * modifier state, as the status bar does on device.
     */
    private inner class SoftwareKeyboardHarness {
        private val context = RuntimeEnvironment.getApplication()
        private val statusBar: StatusBarController =
            getField(getField<Any>(service, "candidatesBarController"), "inputStatusBar")
        private val view = rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView(context)
        private val parent = android.widget.FrameLayout(context).apply {
            addView(view, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        private var time = 0L

        init {
            render()
        }

        fun tap(label: String) {
            val key = getField<List<Any>>(view, "keys").first { key ->
                getField<String>(getField<Any>(key, "spec"), "label") == label
            }
            val rect = getField<android.graphics.RectF>(key, "hitRect")
            time += 100L
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, time, android.view.MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, time + 20L, android.view.MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))
            render()
        }

        private fun render() {
            val modifiers = modifierController().snapshot()
            val snapshot = StatusBarController.StatusSnapshot(
                capsLockEnabled = modifiers.capsLockEnabled,
                shiftPhysicallyPressed = modifiers.shiftPhysicallyPressed,
                shiftOneShot = modifiers.shiftOneShot,
                ctrlLatchActive = modifiers.ctrlLatchActive,
                ctrlPhysicallyPressed = modifiers.ctrlPhysicallyPressed,
                ctrlOneShot = modifiers.ctrlOneShot,
                ctrlLatchFromNavMode = modifiers.ctrlLatchFromNavMode,
                altLatchActive = modifiers.altLatchActive,
                altPhysicallyPressed = modifiers.altPhysicallyPressed,
                altOneShot = modifiers.altOneShot,
                symPage = 0,
                activeKeyboardLayoutName = SettingsManager.getKeyboardLayout(context)
            )
            StatusBarController::class.java.getDeclaredMethod(
                "configureSoftwareKeyboard",
                view.javaClass,
                snapshot.javaClass,
                InputConnection::class.java,
                Map::class.java
            ).apply { isAccessible = true }.invoke(statusBar, view, snapshot, inputConnection, null)
            val width = android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
            val height = android.view.View.MeasureSpec.makeMeasureSpec(240, android.view.View.MeasureSpec.EXACTLY)
            parent.measure(width, height)
            parent.layout(0, 0, 1000, 240)
        }
    }

    private fun useLayout(layout: String) {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, layout)
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setMidWordQuoteToApostrophe(service, true)
        LayoutMappingRepository.loadLayout(service.assets, layout, service)
        service.onStartInput(editorInfo, false)
        // The tests type mid-sentence; drop the auto-cap one-shot of the empty field.
        modifierController().consumeShiftOneShot()
        recorder.committedTexts.clear()
    }

    private fun typeAfterMidWordQuote(layout: String, letter: String, keyCode: Int, meta: Int = 0): String {
        useLayout(layout)
        recorder.textBeforeCursor = "$letter\""
        val time = 10_000L
        service.onKeyDown(keyCode, keyEvent(KeyEvent.ACTION_DOWN, keyCode, time, time, meta))
        service.onKeyUp(keyCode, keyEvent(KeyEvent.ACTION_UP, keyCode, time, time + 30L, meta))
        return recorder.textBeforeCursor
    }

    private fun switchSubtype(prefValue: String) {
        val subtype = rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils.createAdditionalSubtypesArray(
            prefValue, service.assets, service
        ).single()
        PhysicalKeyboardInputMethodService::class.java.getDeclaredMethod(
            "onCurrentInputMethodSubtypeChanged", android.view.inputmethod.InputMethodSubtype::class.java
        ).apply { isAccessible = true }.invoke(service, subtype)
    }

    @Test
    fun uyghurOnQ25_routesAllLettersAndPunctuationThroughRealImePath() {
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(service, "Q25")
        setField(service, "physicalKeyboardProfileOverride", "Q25")
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "uyghur")
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setLongPressModifier(service, "alt")
        LayoutMappingRepository.loadLayout(service.assets, "uyghur", service)
        service.onStartInput(editorInfo, false)

        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        var time = 10_000L
        fun press(key: Int, meta: Int = 0) {
            service.onKeyDown(key, keyEvent(KeyEvent.ACTION_DOWN, key, time, time, meta))
            service.onKeyUp(key, keyEvent(KeyEvent.ACTION_UP, key, time, time + 30L, meta))
            time += 1_000L
        }
        "QWERTYUIOPASDFGHJKLZXCVBNM".forEach { press(KeyEvent.KEYCODE_A + (it - 'A')) }
        "DFGHJKLMNVX".forEach { press(KeyEvent.KEYCODE_A + (it - 'A'), shift) }
        listOf(KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_SEMICOLON).forEach { press(it) }
        "NVJRHBIM".forEach { press(KeyEvent.KEYCODE_A + (it - 'A'), alt) }

        assertEquals(
            "چۋېرتيۇڭوپھسداەىقكلزشغۈبنمژفگخجۆلائنۈشئ،؛،؟؛3:!-.",
            recorder.committedTexts.joinToString("")
        )
    }

    @Test
    fun autoCap_manualShiftOff_survivesRestartOfCurrentField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(3_000L)
        assertFalse(modifierController().shiftOneShot)

        service.onStartInput(editorInfo, true)

        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_manualShiftOff_doesNotSurviveRestart_whenRespectIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        SettingsManager.setAutoCapitalizeRespectManualShiftOff(context, false)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(3_100L)
        assertFalse(modifierController().shiftOneShot)

        service.onStartInput(editorInfo, true)

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun literalEditorOptOutSurvivesRestrictedCapitalizationAndAllowsManualShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)
        editorInfo.privateImeOptions = InputContextState.LITERAL_ENTRY_IME_OPTION
        service.onStartInput(editorInfo, false)
        assertFalse("Literal spelling must not inherit automatic Shift", modifierController().shiftOneShot)

        tapShift(20_000L)
        assertTrue("Deliberate capitalization remains available", modifierController().shiftOneShot)
        tapShift(21_000L)
        assertFalse(modifierController().shiftOneShot)

        focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT)
        assertTrue("Ordinary editors retain the user's capitalization preference", modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_restrictedFieldsSetting_startsUriFieldWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)

        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_restrictedFieldsSetting_neverStartsPasswordWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)

        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_newFieldAfterNewline_startsWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        val nextField = RecordingInputConnection().apply {
            textBeforeCursor = "Previous line\n"
        }

        focusNewField(nextField)

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun deviceSanity_ctrlLatch_canBeDisabledByAnotherCtrlTap() {
        // Latch timing uses KeyEvent.eventTime (not wall-clock), so these gaps stay inside the
        // 500ms double-tap window regardless of how slowly the test runner schedules the calls.
        val t0 = 3_000L
        tapCtrl(t0)
        tapCtrl(t0 + 120L) // latch
        assertTrue(modifierController().ctrlLatchActive)
        tapCtrl(t0 + 260L) // toggle off

        val modifier = modifierController()
        assertFalse(modifier.ctrlOneShot)
        assertFalse(modifier.ctrlLatchActive)
    }

    @Test
    fun deviceSanity_ctrlStickyThenA_triggersShortcutPath() {
        val t0 = 3_600L
        tapCtrl(t0) // one-shot

        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 60L, t0 + 60L)
        )

        assertTrue(
            recorder.contextMenuActions.contains(android.R.id.selectAll) ||
                recorder.sentKeyEvents.isNotEmpty()
        )
    }

    @Test
    fun deviceSanity_altHoldThenA_doesNotStickAlt() {
        val t0 = 3_800L
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 110L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0, t0 + 300L)
        )

        val modifier = modifierController()
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altLatchActive)
    }

    @Test
    fun altShiftLayoutSwitch_consumesShortcutAndClearsModifiers_whenEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltShiftLayoutSwitchEnabled(context, true)
        val t0 = 3_900L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        assertTrue(modifierController().altPressed)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val modifier = modifierController()
        assertTrue(handled)
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)
        assertFalse(modifier.shiftPressed)
        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
    }

    @Test
    fun altShiftLayoutSwitch_ignoresAltOneShot_whenShiftPressedLater() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltShiftLayoutSwitchEnabled(context, true)
        val t0 = 3_950L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0, t0 + 40L)
        )
        assertTrue(modifierController().altOneShot)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0 + 120L, t0 + 120L)
        )

        val modifier = modifierController()
        assertFalse(handled)
        assertTrue(modifier.altOneShot)
        assertTrue(modifier.shiftOneShot)
        assertFalse(modifier.altPhysicallyPressed)
    }

    @Test
    fun altEnterLayoutSwitch_consumesShortcutAndClearsModifiers_whenEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_900L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        assertTrue(modifierController().altPressed)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val modifier = modifierController()
        assertTrue(handled)
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)

        val keyUpHandled = service.onKeyUp(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 100L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        assertTrue(keyUpHandled)
    }

    @Test
    fun altEnterLayoutSwitch_doesNotConsumeWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, false)
        setField(service, "clearAltOnSpaceEnabled", false)
        val t0 = 3_920L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        assertTrue(modifierController().altPhysicallyPressed)
    }

    @Test
    fun altEnterLayoutSwitch_consumesKeyRepeatsUntilKeyUp() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_940L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val repeatedHandled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 160L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
                repeatCount = 1
            )
        )

        assertTrue(repeatedHandled)
        assertFalse(recorder.committedTexts.contains("\n"))
        assertTrue(recorder.sentKeyEvents.isEmpty())
    }

    @Test
    fun altEnterLayoutSwitch_doesNotTriggerWhenEnterIsPressedFirst() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_960L

        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ALT_LEFT,
                downTime = t0 + 80L,
                eventTime = t0 + 80L
            )
        )

        assertTrue(modifierController().altPressed)
        assertTrue(modifierController().altPhysicallyPressed)
    }

    @Test
    fun deviceSanity_symACyclesDeviceThenEmojiThenSymbols_exactMappings() {
        val t0 = 4_000L

        tapSym(t0)
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 20L, t0 + 20L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("@"))

        tapSym(t0 + 200L) // re-open SYM (Device SYM)
        tapSym(t0 + 260L) // next page -> emoji
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 300L, t0 + 300L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("😢"))

        tapSym(t0 + 400L) // re-open SYM (Device SYM)
        tapSym(t0 + 460L) // next page -> emoji
        tapSym(t0 + 520L) // next page -> symbols
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 560L, t0 + 560L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("="))
    }

    /** The emoji page's first People & Body page, opened as the service has it (no recents). */
    private fun openPeopleEmojiPage(): rocks.pkb.plektra.core.SymLayoutController {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recent_emojis_prefs", 0).edit().clear().commit()
        rocks.pkb.plektra.data.emoji.TestEmojiFont.install(context)
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        sym.openEmojiPage()
        sym.turnPage(1, section = true)
        sym.turnPage(1, section = true)
        assertEquals("👋", sym.currentSymMappings()!![KeyEvent.KEYCODE_W])
        return sym
    }

    private fun rollOverWAndE(t0: Long) {
        service.onKeyDown(KeyEvent.KEYCODE_W, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_E, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, t0 + 30L, t0 + 30L))
        service.onKeyUp(KeyEvent.KEYCODE_W, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, t0, t0 + 60L))
        service.onKeyUp(KeyEvent.KEYCODE_E, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E, t0 + 30L, t0 + 90L))
    }

    @Test
    fun emojiPageRolloverWithAutoCloseTypesTheSecondKeyAsALetterOnce() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setSymAutoClose(context, true)
        val sym = openPeopleEmojiPage()

        rollOverWAndE(5_000L)

        assertEquals(listOf("👋", "e"), recorder.committedTexts)
        assertFalse(sym.isSymActive())
    }

    @Test
    fun emojiPageRolloverWithoutAutoCloseTypesBothEmojiOnce() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSymAutoClose(context, false)
        val sym = openPeopleEmojiPage()
        val keys = sym.currentSymMappings()!!

        rollOverWAndE(5_200L)

        assertEquals(listOf(keys[KeyEvent.KEYCODE_W], keys[KeyEvent.KEYCODE_E]), recorder.committedTexts)
        assertTrue(sym.isSymActive())
    }

    @Test
    fun heldSymTypesTheOwnMappingOfQAndPThatThePagesTurnWith() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSymAutoClose(context, false)
        rocks.pkb.plektra.data.emoji.TestEmojiFont.install(context)
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        val own: AlternateCharacterManager = getField(service, "alternateCharacterManager")
        val ownQ = own.getSymMappings()[KeyEvent.KEYCODE_Q]
        sym.openEmojiPage()
        assertEquals(rocks.pkb.plektra.core.SymPagedLayer.PREVIOUS_LABEL, sym.currentSymMappings()!![KeyEvent.KEYCODE_Q])
        assertTrue(!ownQ.isNullOrEmpty())

        val t0 = 5_400L
        service.onKeyDown(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, t0 + 30L, t0 + 30L))
        service.onKeyUp(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, t0 + 30L, t0 + 60L))
        service.onKeyUp(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, t0, t0 + 90L))

        assertEquals(listOf(ownQ), recorder.committedTexts)
        assertTrue("the chord leaves the page open", sym.isSymActive())
    }

    @Test
    fun heldSymBoundaryConsumesTappedShiftBeforeNextLetter() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setShiftTapLatches(context, false)
        SettingsManager.setSymPagesConfig(context, SymPagesConfig(deviceEnabled = false, emojiEnabled = false))
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)

        val t0 = 4_800L
        tapShift(t0)
        assertTrue(modifierController().shiftOneShot)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0 + 40L, t0 + 40L)))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_S,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, t0 + 60L, t0 + 60L)))
        assertEquals(listOf(";"), recorder.committedTexts)
        assertFalse("Boundary handling must consume the same one-shot as a direct SYM commit",
            modifierController().shiftOneShot)
        service.onKeyUp(KeyEvent.KEYCODE_S,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, t0 + 60L, t0 + 70L))
        service.onKeyUp(KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, t0 + 40L, t0 + 80L))
        service.onKeyDown(KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 100L, t0 + 100L))
        assertEquals(listOf(";", "a"), recorder.committedTexts)
    }

    @Test
    fun symPlusQuickLauncherShortcut_opensQuickLauncherFromEditableField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setQuickLauncherTextFieldShortcuts(context, true)
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_SPACE)
        val t0 = 4_200L

        val symHandled = service.onKeyDown(
            KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0, t0)
        )
        val spaceHandled = service.onKeyDown(
            KeyEvent.KEYCODE_SPACE,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, t0, t0 + 40L)
        )

        val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertTrue(symHandled)
        assertTrue(spaceHandled)
        assertEquals(QuickLauncherActivity::class.java.name, startedIntent.component?.className)
        assertTrue(startedIntent.getBooleanExtra(QuickLauncherActivity.EXTRA_TOGGLE_REQUEST, false))
        assertFalse("Space should not be committed when opening QuickLauncher", recorder.committedTexts.contains(" "))
    }

    @Test
    fun heldSymPlusQuickLauncherShortcut_reopensFromEditableFieldAfterLauncherCloses() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setQuickLauncherTextFieldShortcuts(context, true)
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_SPACE)
        val t0 = 4_600L

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SPACE,
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SPACE,
                t0,
                t0 + 40L,
                KeyEvent.META_SYM_ON
            )
        )

        val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertTrue(handled)
        assertEquals(QuickLauncherActivity::class.java.name, startedIntent.component?.className)
        assertTrue(startedIntent.getBooleanExtra(QuickLauncherActivity.EXTRA_TOGGLE_REQUEST, false))
        assertFalse("Space should not be committed when reopening QuickLauncher", recorder.committedTexts.contains(" "))
    }

    @Test
    fun vietnameseTelexLayout_rewritesThroughImePath() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 5_000L, 5_000L)
        )

        assertTrue(handled)
        assertTrue(recorder.deleteSurroundingTextCalls.contains(2 to 0))
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("câ"))
    }

    @Test
    fun vietnameseTelexLayout_ctrlA_shortcutWinsOverTelexRewrite_midWord() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        tapCtrl(5_200L) // ctrl one-shot
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 5_260L, 5_260L)
        )

        assertTrue(
            "Expected shortcut path, commits=${recorder.committedTexts}, deleteCalls=${recorder.deleteSurroundingTextCalls}",
            recorder.contextMenuActions.contains(android.R.id.selectAll) || recorder.sentKeyEvents.isNotEmpty()
        )
        assertTrue("Telex rewrite should not delete text", recorder.deleteSurroundingTextCalls.isEmpty())
        assertFalse("Telex rewrite should not commit transformed text", recorder.committedTexts.contains("câ"))
    }

    @Test
    fun vietnameseTelexLayout_physicalCtrlHold_shortcutWinsWhenEventCtrlMetaMissing() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        val t0 = 5_400L
        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = 0
            )
        )

        assertTrue(
            "Expected shortcut path with physical Ctrl fallback, commits=${recorder.committedTexts}, deleteCalls=${recorder.deleteSurroundingTextCalls}",
            recorder.contextMenuActions.contains(android.R.id.selectAll) || recorder.sentKeyEvents.isNotEmpty()
        )
        assertTrue("Telex rewrite should not delete text", recorder.deleteSurroundingTextCalls.isEmpty())
        assertFalse("Telex rewrite should not commit transformed text", recorder.committedTexts.contains("câ"))
    }

    private fun tapAlt(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, start, start + 30L)
        )
    }

    private fun tapShift(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, start, start + 30L)
        )
    }

    private fun tapCtrl(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, start, start + 30L)
        )
    }

    private fun typeClicksSyntheticAtMacro(start: Long) {
        val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = start,
                eventTime = start,
                metaState = shiftMeta
            )
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_2,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_2,
                downTime = start,
                eventTime = start,
                metaState = shiftMeta
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_2,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_2,
                downTime = start,
                eventTime = start + 90L,
                metaState = shiftMeta
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = start,
                eventTime = start + 120L
            )
        )
    }

    private fun tapSym(start: Long) {
        service.onKeyDown(
            63,
            keyEvent(KeyEvent.ACTION_DOWN, 63, start, start)
        )
        service.onKeyUp(
            63,
            keyEvent(KeyEvent.ACTION_UP, 63, start, start + 20L)
        )
    }

    @Test
    fun rawMode_plainFieldDoesNotCapitalize() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT)
        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun rawMode_fieldCapitalizationFlagsDoNotArmShiftOrCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        for (flag in listOf(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, InputType.TYPE_TEXT_FLAG_CAP_WORDS, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) {
            focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT or flag)
            assertFalse("Shift for flag $flag", modifierController().shiftOneShot)
            assertFalse("Caps Lock for flag $flag", modifierController().capsLockEnabled)
            val state = InputContextState.fromEditorInfo(editorInfo, isRawModeApp = true)
            assertFalse(AutoCapitalizeHelper.shouldCapitalizeAfterBoundary(context, state, inputConnection, KeyEvent.KEYCODE_SPACE))
        }
    }

    @Test
    fun rawMode_fieldRestrictionsDoNotAllowAutoCapitalizeOptIn() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT or variation)
            assertFalse("Shift for variation $variation", modifierController().shiftOneShot)
        }
    }

    @Test
    fun hiddenBarApp_refusesShowRequestsButHardwareTypingAndSymLayerStillWork() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setKeyboardBarHiddenInApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)

        assertFalse(visibilityController().allowsSurface())
        assertFalse(service.onEvaluateInputViewShown())
        assertFalse(service.onShowInputRequested(0, false))
        assertTrue(visibilityController().shouldRecoverSurfaceOnHardwareKey())

        val t0 = 9_000L
        service.onKeyDown(KeyEvent.KEYCODE_B, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, t0, t0))
        service.onKeyUp(KeyEvent.KEYCODE_B, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, t0, t0 + 20L))
        tapSym(t0 + 100L)
        service.onKeyDown(KeyEvent.KEYCODE_A, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 140L, t0 + 140L))
        assertEquals("commits=${recorder.committedTexts}", listOf("b", "@"), recorder.committedTexts)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(visibilityController().allowsSurface())
    }

    @Test
    fun hiddenBarApp_dedicatedEmojiKeyAndPickerRequestsDoNotOpenInvisiblePickers() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setKeyboardBarHiddenInApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)
        setField(service, "physicalKeyboardProfileOverride", "mp01")

        assertTrue(service.onKeyDown(666, keyEvent(KeyEvent.ACTION_DOWN, 666, 9_500L, 9_500L)))
        assertEquals(0, symLayout().currentSymPage())
        assertFalse(symLayout().openClipboardPage())
        assertEquals(0, symLayout().currentSymPage())
        recorder.committedTexts.clear()
        service.onKeyDown(KeyEvent.KEYCODE_C, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 9_600L, 9_600L))
        assertEquals(listOf("c"), recorder.committedTexts)
    }

    @Test
    fun hiddenBarApp_appSwitchAndSettingsChangeReevaluateTheBlock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.blocked", true)

        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.blocked")
        assertFalse(visibilityController().allowsSurface())
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.allowed")
        assertTrue(visibilityController().allowsSurface())
        assertTrue(symLayout().openEmojiPickerPage())

        // Adding the current app while a picker is open closes the picker and blocks the bar.
        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.allowed", true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(visibilityController().allowsSurface())
        assertEquals(0, symLayout().currentSymPage())
        assertFalse(service.onShowInputRequested(0, false))

        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.allowed", false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(visibilityController().allowsSurface())
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.blocked")
        assertFalse(visibilityController().allowsSurface())
    }

    private fun visibilityController(): KeyboardVisibilityController =
        getField(service, "keyboardVisibilityController")

    private fun symLayout(): rocks.pkb.plektra.core.SymLayoutController =
        getField(service, "symLayoutController")

    private fun focusNewField(
        newRecorder: RecordingInputConnection,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        packageName: String = "rocks.pkb.plektra.test"
    ) {
        recorder = newRecorder
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            this.inputType = inputType
            this.packageName = packageName
        }
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        service.onStartInput(editorInfo, false)
    }

    private fun configureAppEnterOverride(packageName: String, strategy: String) {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAppEnterBehaviorEnabled(context, true)
        SettingsManager.setAppEnterBehaviorOverrides(
            context,
            listOf(
                SettingsManager.AppEnterBehaviorOverride(
                    packageName = packageName,
                    behavior = SettingsManager.ENTER_BEHAVIOR_ENTER_SEND_SHIFT_NEWLINE,
                    sendStrategy = strategy
                )
            )
        )
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            packageName = packageName
        )
    }

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        metaState: Int = 0,
        repeatCount: Int = 0
    ): KeyEvent {
        return KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState)
    }

    private fun modifierController(): ModifierStateController {
        return getField(service, "modifierStateController")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, fieldName: String): T {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target) as T
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field not found: $fieldName")
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field not found: $fieldName")
    }

    @Test
    fun blackberryCurrencyKeyCommitsConfiguredCurrencyOnceThroughRealServiceRouting() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setPhysicalKeyboardCurrencySymbol(context, "£")
        setField(service, "physicalKeyboardProfileOverride", "Q25")
        recorder.committedTexts.clear()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_GRAVE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE)))
        assertEquals(listOf("£"), recorder.committedTexts)
    }

    private class RecordingInputConnection {
        var commitAttempts = 0
        var commitResult = true
        var textBeforeCursor: String = ""
        var sendKeyEventResult: Boolean = true
        var performEditorActionResult: Boolean = false
        val committedTexts = mutableListOf<String>()
        val sentKeyEvents = mutableListOf<KeyEvent>()
        val editorActions = mutableListOf<Int>()
        val contextMenuActions = mutableListOf<Int>()
        val deleteSurroundingTextCalls = mutableListOf<Pair<Int, Int>>()

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                if (method.name == "commitText") commitAttempts++
                when (method.name) {
                    "commitText" -> if (!commitResult) false else {
                        val text = args?.getOrNull(0)?.toString()
                        if (text != null) {
                            committedTexts += text
                            textBeforeCursor += text
                        }
                        true
                    }
                    "deleteSurroundingText" -> {
                        val before = (args?.getOrNull(0) as? Int) ?: 0
                        val after = (args?.getOrNull(1) as? Int) ?: 0
                        deleteSurroundingTextCalls += before to after
                        if (before > 0 && textBeforeCursor.isNotEmpty()) {
                            val keep = (textBeforeCursor.length - before).coerceAtLeast(0)
                            textBeforeCursor = textBeforeCursor.take(keep)
                        }
                        true
                    }
                    "sendKeyEvent" -> {
                        val event = args?.getOrNull(0) as? KeyEvent
                        if (event != null) sentKeyEvents += event
                        sendKeyEventResult
                    }
                    "performEditorAction" -> {
                        val action = args?.getOrNull(0) as? Int
                        if (action != null) editorActions += action
                        performEditorActionResult
                    }
                    "performContextMenuAction" -> {
                        val id = args?.getOrNull(0) as? Int
                        if (id != null) contextMenuActions += id
                        true
                    }
                    "getTextBeforeCursor" -> {
                        val count = (args?.getOrNull(0) as? Int) ?: textBeforeCursor.length
                        textBeforeCursor.takeLast(count)
                    }
                    "getExtractedText" -> ExtractedText().apply {
                        text = textBeforeCursor
                        selectionStart = textBeforeCursor.length
                        selectionEnd = textBeforeCursor.length
                    }
                    "beginBatchEdit", "endBatchEdit", "finishComposingText" -> true
                    else -> defaultValue(method.returnType)
                }
            } as InputConnection
        }

        private fun defaultValue(type: Class<*>): Any? {
            return when {
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Char::class.javaPrimitiveType -> 0.toChar()
                else -> null
            }
        }
    }

    @Test
    fun longPress_realBoundaryCallbackRejectedCommit_doesNotReportAlternate() {
        useLayout("uyghur")
        SettingsManager.setLongPressModifier(service, "alt")
        SettingsManager.setLongPressThreshold(service, 50L)
        SettingsManager.setFrenchPunctuationSpacing(service, true)
        SettingsManager.setFrenchPunctuationOnlyFrenchLayouts(service, false)
        recorder.textBeforeCursor = "ب"
        val manager = getField<AlternateCharacterManager>(service, "alternateCharacterManager")
        manager.addAltKeyMapping(KeyEvent.KEYCODE_L, "?")
        val alternates = mutableListOf<Char>()
        manager.onAltCharInserted = { alternates += it }
        manager.handleKeyWithAltMapping(KeyEvent.KEYCODE_L,
            KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 0, KeyEvent.META_SHIFT_ON), false, inputConnection)
        assertEquals("بلا", recorder.textBeforeCursor)
        val attemptsBeforeReplacement = recorder.commitAttempts
        recorder.commitResult = false
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, false)
        assertEquals("ب", recorder.textBeforeCursor)
        assertTrue("Real service boundary callback reported rejected alternate: $alternates", alternates.isEmpty())
        assertEquals("only the handled replacement commit is attempted",
            attemptsBeforeReplacement + 1, recorder.commitAttempts)
    }


    @Test
    fun longPress_deferredSpaceMustNotSwallowOpeningBracket() {
        useLayout("uyghur")
        SettingsManager.setLongPressModifier(service, "alt")
        SettingsManager.setLongPressThreshold(service, 50L)
        SettingsManager.setFrenchPunctuationSpacing(service, false)
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setSpaceAfterPunctuation(service, ".")
        rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker.clear()
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        recorder.textBeforeCursor = "ب"
        val manager = getField<AlternateCharacterManager>(service, "alternateCharacterManager")
        manager.addAltKeyMapping(KeyEvent.KEYCODE_PERIOD, "(")
        val alternates = mutableListOf<Char>()
        manager.onAltCharInserted = { alternates += it }
        service.onKeyDown(KeyEvent.KEYCODE_PERIOD, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PERIOD))
        assertEquals("ب.", recorder.textBeforeCursor)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        service.onKeyUp(KeyEvent.KEYCODE_PERIOD, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PERIOD))
        assertEquals("ب (", recorder.textBeforeCursor)
        assertEquals(listOf('('), alternates)
    }


    @Test
    fun emojiSearchHeldInSearchIsConsumed() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10000L, 10000L, meta))
        pressKey(KeyEvent.KEYCODE_A, 10050L, meta)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10000L, 10450L))
        assertEquals("A", searchText(picker))
        pressKey(KeyEvent.KEYCODE_B, 10500L)
        assertEquals("Ab", searchText(picker))
    }

    @Test
    fun emojiSearchSoftOneShotInSearchIsConsumed() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.shiftOneShot = true
        pressKey(KeyEvent.KEYCODE_A, 10000L)
        assertEquals("A", searchText(picker))
        pressKey(KeyEvent.KEYCODE_B, 10100L)
        assertEquals("Ab", searchText(picker))
    }

    @Test
    fun emojiSearchOnUyghur_softOneShotTypesTheWholeMultiCharacterLetter() {
        useLayout("uyghur")
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.shiftOneShot = true
        pressKey(KeyEvent.KEYCODE_L, 10000L)
        assertEquals("لا", searchText(picker))
        assertFalse(modifier.shiftOneShot)
        pressKey(KeyEvent.KEYCODE_L, 10100L)
        assertEquals("لال", searchText(picker))
    }

    @Test
    fun emojiSearchManualLatchSurvivesSearch() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.capsLockEnabled = true
        pressKey(KeyEvent.KEYCODE_A,10000L)
        pressKey(KeyEvent.KEYCODE_B,10100L)
        assertTrue(modifier.capsLockEnabled)
    }

}
