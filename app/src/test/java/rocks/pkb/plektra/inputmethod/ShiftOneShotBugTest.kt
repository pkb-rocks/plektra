package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * Test for Shift one-shot behavior:
 * - Unified tap model: single tap → one-shot, double-tap → CAPS latch
 * - Shift one-shot should be consumed by ANY non-modifier key (letters, Space, Enter, Backspace, Del, arrows, digits)
 * - Obsolete shift_tap_latches preference is ignored (verified by ON scenarios)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShiftOneShotBugTest {

    /**
     * Represents one test scenario: Shift tap, then firstKey, then A
     */
    data class TestScenario(
        val name: String,
        val setting: Boolean,  // shift_tap_latches setting value
        val firstKey: Int,     // Key pressed after Shift tap (before final A)
        val firstKeyName: String,
        val secondKey: Int,    // Always KEYCODE_A for consistency
        val expectedSecondKeyHasShift: Boolean  // Should 2nd key have Shift active?
    )

    /**
     * NOTE: The fix is verified by shiftOneShotConsumedByAnyKey test below,
     * which checks 15 scenarios including letters. With setting OFF, Shift tap + letter
     * consumes the one-shot, so a second letter is lowercase.
     * User's complaint "Shift tap + two letters gives AB" is fixed - now gives "Ab".
     */

    @Test
    fun shiftOneShotConsumedByAnyKey() {
        // Test matrix: setting × firstKey
        // - OFF setting: one-shot should be consumed by firstKey, so 2nd key (A) should be lowercase
        // - ON setting: latch persists, so 2nd key (A) should be uppercase
        
        val scenarios = mutableListOf<TestScenario>()
        
        // Keys that should consume Shift one-shot
        val testKeys = mapOf(
            KeyEvent.KEYCODE_B to "B",
            KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_ENTER to "Enter",
            KeyEvent.KEYCODE_DEL to "Backspace",
            KeyEvent.KEYCODE_FORWARD_DEL to "Del",
            KeyEvent.KEYCODE_DPAD_LEFT to "Left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
            KeyEvent.KEYCODE_DPAD_UP to "Up",
            KeyEvent.KEYCODE_DPAD_DOWN to "Down",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_1 to "Digit_1",
            KeyEvent.KEYCODE_COMMA to "Comma",
            KeyEvent.KEYCODE_PERIOD to "Period"
        )
        
        // OFF setting: one-shot should be consumed by this key, so 2nd key should be lowercase
        for ((keyCode, keyName) in testKeys) {
            scenarios.add(TestScenario(
                name = "OFF_tap_then_${keyName}_then_A",
                setting = false,
                firstKey = keyCode,
                firstKeyName = keyName,
                secondKey = KeyEvent.KEYCODE_A,
                expectedSecondKeyHasShift = false
            ))
        }
        
        // ON setting (shift_tap_latches=true): obsolete preference is now ignored,
        // single tap gives ONE-SHOT (consumed by keys), not CAPS latch.
        // These scenarios verify the fix: Shift is consumed even when shift_tap_latches=true.
        scenarios.add(TestScenario(
            name = "ON_tap_then_Space_then_A",
            setting = true,
            firstKey = KeyEvent.KEYCODE_SPACE,
            firstKeyName = "Space",
            secondKey = KeyEvent.KEYCODE_A,
            expectedSecondKeyHasShift = false  // One-shot consumed, not latch
        ))
        scenarios.add(TestScenario(
            name = "ON_tap_then_Enter_then_A",
            setting = true,
            firstKey = KeyEvent.KEYCODE_ENTER,
            firstKeyName = "Enter",
            secondKey = KeyEvent.KEYCODE_A,
            expectedSecondKeyHasShift = false  // One-shot consumed, not latch
        ))
        
        // Execute all scenarios
        val results = mutableListOf<String>()
        for ((index, scenario) in scenarios.withIndex()) {
            val result = runScenario(scenario)
            results.add(result)
            println("[${"${index + 1}".padStart(2)}/${scenarios.size}] $result")
        }
        
        // Aggregate result
        val passCount = results.count { it.contains("✓ PASS") }
        val failCount = results.count { it.contains("✗ FAIL") }
        println("\n=== FINAL RESULT: $passCount/${scenarios.size} passed, $failCount failed ===")
        
        // Fail if any scenario failed
        if (failCount > 0) {
            throw AssertionError("$failCount/${scenarios.size} scenarios failed:\n" + 
                results.filter { it.contains("✗ FAIL") }.joinToString("\n"))
        }
    }

    private fun runScenario(scenario: TestScenario): String {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Disable auto-cap to isolate shift one-shot behavior
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        
        // Set shift_tap_latches setting
        SettingsManager.setShiftTapLatches(context, scenario.setting)
        
        // Create service
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        
        // Setup recording InputConnection
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            // Disable auto-cap: use TYPE_TEXT_VARIATION_NORMAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            packageName = "rocks.pkb.plektra.test"
        }
        
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        val downTime = System.currentTimeMillis()
        
        // Step 1: Tap Shift (SHIFT_LEFT)
        sendKey(service, KeyEvent.KEYCODE_SHIFT_LEFT, downTime, downTime)
        
        // Step 2: Press first key
        sendKey(service, scenario.firstKey, downTime + 100, downTime + 100)
        
        // Step 3: Press second key (A)
        sendKey(service, scenario.secondKey, downTime + 200, downTime + 200)
        
        // Analyze: did the second key have Shift modifier?
        val secondKeyText = if (recorder.committedTexts.size >= 2) {
            recorder.committedTexts[recorder.committedTexts.size - 1]
        } else if (recorder.committedTexts.size == 1) {
            recorder.committedTexts[0]
        } else {
            ""
        }
        
        // Check if Shift is active: A with Shift produces 'A', without produces 'a'
        val secondKeyHasShift = secondKeyText == "A"
        
        val expectedStr = if (scenario.expectedSecondKeyHasShift) "uppercase 'A'" else "lowercase 'a'"
        val actualStr = if (secondKeyHasShift) "uppercase 'A'" else "lowercase 'a'"
        
        return if (secondKeyHasShift == scenario.expectedSecondKeyHasShift) {
            "${scenario.name}: ✓ PASS (expected $expectedStr, got $actualStr)"
        } else {
            "${scenario.name}: ✗ FAIL (expected $expectedStr, got $actualStr)"
        }
    }

    private fun sendKey(service: PhysicalKeyboardInputMethodService, keyCode: Int, downTime: Long, eventTime: Long) {
        val down = KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        service.onKeyDown(keyCode, down)
        val up = KeyEvent(downTime, eventTime + 50, KeyEvent.ACTION_UP, keyCode, 0, 0)
        service.onKeyUp(keyCode, up)
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found")
    }

    private class RecordingInputConnection {
        var textBeforeCursor: String = ""
        val committedTexts = mutableListOf<String>()
        val sentKeyEvents = mutableListOf<KeyEvent>()

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "commitText" -> {
                        val text = args?.getOrNull(0)?.toString()
                        if (text != null) {
                            committedTexts += text
                            textBeforeCursor += text
                        }
                        true
                    }
                    "sendKeyEvent" -> {
                        val event = args?.getOrNull(0) as? KeyEvent
                        if (event != null) sentKeyEvents += event
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
                    "performContextMenuAction" -> true
                    "beginBatchEdit", "endBatchEdit", "finishComposingText" -> true
                    else -> when {
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        method.returnType == Int::class.javaPrimitiveType -> 0
                        method.returnType == Long::class.javaPrimitiveType -> 0L
                        else -> null
                    }
                }
            } as InputConnection
        }
    }

    /**
     * Auto-capitalization tests: Verify that auto-cap correctly capitalizes after Enter/Space.
     * These scenarios do NOT involve Shift tap; the uppercase A comes from auto-cap re-arming shift.
     */
    @Test
    fun autoCapAfterBoundaryKeys() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Enable auto-cap for these tests
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setShiftTapLatches(context, false)
        
        val scenarios = listOf(
            "AutoCap_Enter_then_A" to KeyEvent.KEYCODE_ENTER,
            "AutoCap_Space_then_A" to KeyEvent.KEYCODE_SPACE
        )
        
        val results = mutableListOf<String>()
        for ((name, boundaryKey) in scenarios) {
            val result = runAutoCapScenario(name, boundaryKey)
            results.add(result)
            println(result)
        }
        
        // Aggregate result
        val passCount = results.count { it.contains("✓ PASS") }
        val failCount = results.count { it.contains("✗ FAIL") }
        println("\n=== AUTO-CAP RESULT: $passCount/${scenarios.size} passed, $failCount failed ===")
        
        if (failCount > 0) {
            throw AssertionError("$failCount/${scenarios.size} auto-cap scenarios failed:\n" + 
                results.filter { it.contains("✗ FAIL") }.joinToString("\n"))
        }
    }
    
    private fun runAutoCapScenario(name: String, boundaryKey: Int): String {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Enable auto-cap
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setShiftTapLatches(context, false)
        
        // Create service
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        
        // Setup recording InputConnection
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            // Enable auto-cap: use TYPE_TEXT_FLAG_CAP_SENTENCES
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            packageName = "rocks.pkb.plektra.test"
        }
        
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        val downTime = System.currentTimeMillis()
        
        // Step 1: Press boundary key (Enter or Space) in empty field
        sendKey(service, boundaryKey, downTime, downTime)
        
        // Step 2: Press A
        sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
        
        // Analyze: A should be uppercase from auto-cap
        val lastText = recorder.committedTexts.lastOrNull() ?: ""
        val isUppercase = lastText == "A"
        
        return if (isUppercase) {
            "$name: ✓ PASS (auto-cap gave uppercase 'A')"
        } else {
            "$name: ✗ FAIL (expected uppercase 'A' from auto-cap, got '$lastText')"
        }
    }
}
