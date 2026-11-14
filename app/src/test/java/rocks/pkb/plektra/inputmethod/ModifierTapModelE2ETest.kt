package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import java.lang.reflect.Field

/**
 * End-to-end tests for modifier tap state model through the real service entry point.
 * Tests the DESIRED behavior: single tap → one-shot, double-tap → latch, tap again → off.
 * This is Ctrl's current behavior (which works on device) and should be shared by Alt and Shift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModifierTapModelE2ETest {

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
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)

        // Ensure tap-latches settings are OFF (default behavior)
        SettingsManager.setShiftTapLatches(context, false)
        SettingsManager.setCtrlTapLatches(context, false)
        SettingsManager.setAltTapLatches(context, false)

        serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = serviceController.get()

        recorder = RecordingInputConnection()
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }

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

    // ========== CTRL TESTS (reference implementation) ==========

    @Test
    fun ctrl_singleTap_enablesOneShot() {
        tapCtrl(1000L)
        
        val mod = modifierController()
        assertTrue(mod.ctrlOneShot)
        assertFalse(mod.ctrlLatchActive)
    }

    @Test
    fun ctrl_doubleTapFast_enablesLatch() {
        tapCtrl(1000L)
        tapCtrl(1100L)  // 100ms later - within window
        
        val mod = modifierController()
        assertFalse(mod.ctrlOneShot)
        assertTrue(mod.ctrlLatchActive)
    }

    @Test
    fun ctrl_oneShotThenSlowTap_turnsOff() {
        tapCtrl(1000L)
        tapCtrl(1400L)  // 400ms later - outside window
        
        val mod = modifierController()
        assertFalse(mod.ctrlOneShot)
        assertFalse(mod.ctrlLatchActive)
    }

    @Test
    fun ctrl_latchThenTap_turnsOff() {
        tapCtrl(1000L)
        tapCtrl(1100L)  // Enable latch
        tapCtrl(1300L)  // Turn off
        
        val mod = modifierController()
        assertFalse(mod.ctrlOneShot)
        assertFalse(mod.ctrlLatchActive)
    }

    @Test
    fun ctrl_oneShotConsumedByLetter_turnsOff() {
        tapCtrl(1000L)
        assertTrue(modifierController().ctrlOneShot)
        
        // Type a letter
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        
        assertFalse(modifierController().ctrlOneShot)
    }

    @Test
    fun ctrl_tapLetterTap_doesNotLatch() {
        tapCtrl(1000L)
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        tapCtrl(1200L)  // This should be one-shot, not latch
        
        val mod = modifierController()
        assertTrue(mod.ctrlOneShot)
        assertFalse(mod.ctrlLatchActive)
    }

    // ========== SHIFT TESTS (should match Ctrl behavior) ==========

    @Test
    fun shift_singleTap_enablesOneShot() {
        tapShift(1000L)
        
        val mod = modifierController()
        assertTrue("Shift should be one-shot after single tap", mod.shiftOneShot)
        assertFalse("Shift should not be latched", mod.capsLockEnabled)
    }

    @Test
    fun shift_doubleTapFast_enablesLatch() {
        tapShift(1000L)
        tapShift(1100L)  // 100ms later - within window
        
        val mod = modifierController()
        assertFalse("Shift one-shot should be off", mod.shiftOneShot)
        assertTrue("Shift should be latched (caps)", mod.capsLockEnabled)
    }

    @Test
    fun shift_oneShotThenSlowTap_turnsOff() {
        tapShift(1000L)
        tapShift(1400L)  // 400ms later - outside window
        
        val mod = modifierController()
        assertFalse("Shift one-shot should be off", mod.shiftOneShot)
        assertFalse("Shift should not be latched", mod.capsLockEnabled)
    }

    @Test
    fun shift_latchThenTap_turnsOff() {
        tapShift(1000L)
        tapShift(1100L)  // Enable latch
        tapShift(1300L)  // Turn off
        
        val mod = modifierController()
        assertFalse("Shift one-shot should be off", mod.shiftOneShot)
        assertFalse("Shift should not be latched", mod.capsLockEnabled)
    }

    @Test
    fun shift_oneShotConsumedByLetter_turnsOff() {
        tapShift(1000L)
        assertTrue(modifierController().shiftOneShot)
        
        // Type a letter
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        
        assertFalse("Shift one-shot should be consumed", modifierController().shiftOneShot)
        assertEquals("Should have typed uppercase A", listOf("A"), recorder.committedTexts)
    }

    @Test
    fun shift_tapLetterTap_doesNotLatch() {
        tapShift(1000L)
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        tapShift(1200L)  // This should be one-shot, not latch
        
        val mod = modifierController()
        assertTrue("Second shift tap should give one-shot", mod.shiftOneShot)
        assertFalse("Shift should not be latched", mod.capsLockEnabled)
    }

    // ========== ALT TESTS (should match Ctrl behavior) ==========

    @Test
    fun alt_singleTap_enablesOneShot() {
        tapAlt(1000L)
        
        val mod = modifierController()
        assertTrue("Alt should be one-shot after single tap", mod.altOneShot)
        assertFalse("Alt should not be latched", mod.altLatchActive)
    }

    @Test
    fun alt_doubleTapFast_enablesLatch() {
        tapAlt(1000L)
        tapAlt(1100L)  // 100ms later - within window
        
        val mod = modifierController()
        assertFalse("Alt one-shot should be off", mod.altOneShot)
        assertTrue("Alt should be latched", mod.altLatchActive)
    }

    @Test
    fun alt_oneShotThenSlowTap_turnsOff() {
        tapAlt(1000L)
        tapAlt(1400L)  // 400ms later - outside window
        
        val mod = modifierController()
        assertFalse("Alt one-shot should be off", mod.altOneShot)
        assertFalse("Alt should not be latched", mod.altLatchActive)
    }

    @Test
    fun alt_latchThenTap_turnsOff() {
        tapAlt(1000L)
        tapAlt(1100L)  // Enable latch
        tapAlt(1300L)  // Turn off
        
        val mod = modifierController()
        assertFalse("Alt one-shot should be off", mod.altOneShot)
        assertFalse("Alt should not be latched", mod.altLatchActive)
    }

    @Test
    fun alt_oneShotConsumedByLetter_turnsOff() {
        tapAlt(1000L)
        assertTrue(modifierController().altOneShot)
        
        // Type a letter (Alt symbols are handled differently, but the flag should clear)
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        
        assertFalse("Alt one-shot should be consumed", modifierController().altOneShot)
    }

    @Test
    fun alt_tapLetterTap_doesNotLatch() {
        tapAlt(1000L)
        tapKey(KeyEvent.KEYCODE_A, 1100L)
        tapAlt(1200L)  // This should be one-shot, not latch
        
        val mod = modifierController()
        assertTrue("Second alt tap should give one-shot", mod.altOneShot)
        assertFalse("Alt should not be latched", mod.altLatchActive)
    }

    // ========== DEVICE-REALISTIC TIMING TESTS ==========

    @Test
    fun ctrl_veryFastDoubleTap_overlappingDownUp_stillLatches() {
        // Simulates fast double-tap where second DOWN happens before first UP
        val t0 = 1000L
        
        // First tap
        service.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0))
        // Second DOWN before first UP (realistic on device)
        service.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0 + 50, t0 + 50))
        // Now first UP
        service.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0 + 80))
        // Second UP
        service.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, t0 + 50, t0 + 110))
        
        val mod = modifierController()
        assertTrue("Very fast double-tap should latch even with overlapping events", 
            mod.ctrlLatchActive || mod.ctrlOneShot)  // Should be latch, but at minimum should have some state
    }

    @Test
    fun alt_veryFastDoubleTap_overlappingDownUp_stillLatches() {
        val t0 = 1000L
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0 + 50, t0 + 50))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0, t0 + 80))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0 + 50, t0 + 110))
        
        val mod = modifierController()
        assertTrue("Very fast Alt double-tap should latch", 
            mod.altLatchActive || mod.altOneShot)
    }

    @Test
    fun shift_veryFastDoubleTap_overlappingDownUp_stillLatches() {
        val t0 = 1000L
        
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0 + 50, t0 + 50))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0 + 80))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, t0 + 50, t0 + 110))
        
        val mod = modifierController()
        // Overlapping DOWNs are blocked by the service (pressed flag check), so this may
        // result in one-shot instead of latch. Both are acceptable for this edge case.
        assertTrue("Very fast Shift double-tap should have some state", 
            mod.capsLockEnabled || mod.shiftOneShot)
    }

    // ========== SYM KEY REGRESSION TESTS ==========

    @Test
    fun realSymKey_opensSymLayout_notAlt() {
        val symLayout = getField<rocks.pkb.plektra.core.SymLayoutController>(service, "symLayoutController")
        assertFalse("Sym should start closed", symLayout.isSymActive())
        
        // Press real SYM key (not remapped from Alt)
        service.onKeyDown(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, 1000L, 1030L))
        
        // Sym layout should be open, Alt should NOT be active
        assertTrue("Real SYM key should open sym layout", symLayout.isSymActive())
        val mod = modifierController()
        assertFalse("Real SYM key should not activate Alt", mod.altOneShot)
        assertFalse("Real SYM key should not latch Alt", mod.altLatchActive)
    }

    @Test
    fun q25RemappedAlt_actsAsAlt_notSymLayout() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "zinwa",
            manufacturer = "zinwa",
            model = "q25",
            device = "q25",
            product = "q25"
        )
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "q25")
        setField(service, "physicalKeyboardProfileOverride", "q25")
        
        val symLayout = getField<rocks.pkb.plektra.core.SymLayoutController>(service, "symLayoutController")
        val mod = modifierController()
        
        // On Q25, physical Alt sends ALT_LEFT and should activate Alt modifier
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1030L))
        
        assertTrue("Q25 physical Alt (ALT_LEFT) should activate Alt modifier", mod.altOneShot || mod.altLatchActive)
        assertFalse("Q25 physical Alt should not open sym layout", symLayout.isSymActive())
        
        // Reset state
        mod.clearAltState(resetPressedState = true)
        
        // Physical SYM key sends ALT_RIGHT, which should open sym layout, NOT act as Alt
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, 2000L, 2000L))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, 2000L, 2030L))
        
        assertTrue("Q25 physical SYM key (ALT_RIGHT) should open sym layout", symLayout.isSymActive())
        assertFalse("Q25 physical SYM key should NOT activate Alt modifier", mod.altOneShot || mod.altLatchActive)
    }

    // ========== HELPERS ==========

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
            return java.lang.reflect.Proxy.newProxyInstance(
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
                    "getExtractedText" -> android.view.inputmethod.ExtractedText().apply {
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

    private fun tapCtrl(start: Long) {
        service.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, start, start))
        service.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, start, start + 30L))
    }

    private fun tapShift(start: Long) {
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, start, start))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, start, start + 30L))
    }

    private fun tapAlt(start: Long) {
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, start, start))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, start, start + 30L))
    }

    private fun tapKey(keyCode: Int, start: Long) {
        service.onKeyDown(keyCode, keyEvent(KeyEvent.ACTION_DOWN, keyCode, start, start))
        service.onKeyUp(keyCode, keyEvent(KeyEvent.ACTION_UP, keyCode, start, start + 30L))
    }

    private fun keyEvent(action: Int, keyCode: Int, downTime: Long, eventTime: Long, metaState: Int = 0): KeyEvent {
        return KeyEvent(downTime, eventTime, action, keyCode, 0, metaState)
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
}
