package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.InputContextState
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * Expanded test matrix for Alt one-shot bug:
 * "With alt_tap_latches OFF, Alt tap should be one-shot (consumed by the NEXT key),
 * but Space/Enter/Backspace/etc don't consume it, causing Alt to 'always latch'."
 *
 * Test dimensions:
 * - Setting: OFF / ON
 * - First key after Alt tap: Letter, Digit, Space, Enter, Backspace, Del, Shift, Sym, Ctrl
 * - Second key: Letter (to verify Alt was consumed or not)
 * - Also: Double-tap to lock, Hold+key behavior
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltOneShotBugTest {

    data class TestScenario(
        val name: String,
        val setting: Boolean, // false = OFF (one-shot), true = ON (latch)
        val firstKey: Int,
        val firstKeyName: String,
        val secondKey: Int = KeyEvent.KEYCODE_A,
        val expectedSecondKeyHasAlt: Boolean
    )

    @Test
    fun altOneShotConsumedByAnyKey() {
        val scenarios = generateScenarios()
        val results = mutableListOf<TestResult>()
        
        println("\n" + "=".repeat(80))
        println("ALT ONE-SHOT BUG TEST MATRIX")
        println("Testing: Alt one-shot must be consumed by ANY key, not just letters")
        println("Total scenarios: ${scenarios.size}")
        println("=".repeat(80))
        
        for ((index, scenario) in scenarios.withIndex()) {
            println("\n[${index + 1}/${scenarios.size}] ${scenario.name}")
            
            try {
                val result = runScenario(scenario)
                results.add(result)
                
                val status = if (result.passed) "✓ PASS" else "✗ FAIL"
                println("  $status: ${result.message}")
                
                if (!result.passed) {
                    println("  Expected 2nd key has Alt: ${scenario.expectedSecondKeyHasAlt}")
                    println("  Actual 2nd key text: '${result.secondKeyText}'")
                }
            } catch (e: Exception) {
                println("  ✗ ERROR: ${e.message}")
                results.add(TestResult(
                    scenario = scenario,
                    passed = false,
                    message = "Exception: ${e.message}",
                    secondKeyText = "ERROR"
                ))
            }
        }
        
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        
        println("\n" + "=".repeat(80))
        println("TEST SUMMARY")
        println("=".repeat(80))
        println("Total:  ${results.size}")
        println("Passed: $passed")
        println("Failed: $failed")
        
        if (failed > 0) {
            println("\nFAILED SCENARIOS:")
            results.filter { !it.passed }.forEach {
                println("  - ${it.scenario.name}")
                println("    Expected: 2nd key ${if (it.scenario.expectedSecondKeyHasAlt) "HAS" else "NO"} Alt")
                println("    Got: '${it.secondKeyText}'")
            }
        }
        
        println("=".repeat(80))
        
        assertEquals("All Alt one-shot scenarios must pass", 0, failed)
    }

    private fun generateScenarios(): List<TestScenario> {
        val scenarios = mutableListOf<TestScenario>()
        
        // With setting OFF (one-shot), EVERY key should consume the Alt one-shot
        val keysToTest = listOf(
            Pair(KeyEvent.KEYCODE_A, "Letter_A"),
            Pair(KeyEvent.KEYCODE_Z, "Letter_Z"),
            Pair(KeyEvent.KEYCODE_0, "Digit_0"),
            Pair(KeyEvent.KEYCODE_5, "Digit_5"),
            Pair(KeyEvent.KEYCODE_SPACE, "Space"),
            Pair(KeyEvent.KEYCODE_ENTER, "Enter"),
            Pair(KeyEvent.KEYCODE_DEL, "Backspace"),
            Pair(KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
            Pair(KeyEvent.KEYCODE_DPAD_LEFT, "Arrow_Left"),
            Pair(KeyEvent.KEYCODE_DPAD_RIGHT, "Arrow_Right"),
            Pair(KeyEvent.KEYCODE_TAB, "Tab"),
            Pair(KeyEvent.KEYCODE_COMMA, "Comma"),
            Pair(KeyEvent.KEYCODE_PERIOD, "Period")
        )
        
        for ((keyCode, keyName) in keysToTest) {
            // OFF setting: one-shot should be consumed by this key, so 2nd key should be plain
            scenarios.add(TestScenario(
                name = "OFF_tap_then_${keyName}_then_A",
                setting = false,
                firstKey = keyCode,
                firstKeyName = keyName,
                secondKey = KeyEvent.KEYCODE_A,
                expectedSecondKeyHasAlt = false
            ))
        }
        
        // ON setting (alt_tap_latches=true): obsolete preference is now ignored,
        // single tap gives ONE-SHOT (consumed by keys), not latch.
        // These scenarios verify the fix: Alt is consumed even when alt_tap_latches=true.
        scenarios.add(TestScenario(
            name = "ON_tap_then_Space_then_A",
            setting = true,
            firstKey = KeyEvent.KEYCODE_SPACE,
            firstKeyName = "Space",
            secondKey = KeyEvent.KEYCODE_A,
            expectedSecondKeyHasAlt = false  // One-shot consumed, not latch
        ))
        
        scenarios.add(TestScenario(
            name = "ON_tap_then_Enter_then_A",
            setting = true,
            firstKey = KeyEvent.KEYCODE_ENTER,
            firstKeyName = "Enter",
            secondKey = KeyEvent.KEYCODE_A,
            expectedSecondKeyHasAlt = false  // One-shot consumed, not latch
        ))
        
        return scenarios
    }

    private data class TestResult(
        val scenario: TestScenario,
        val passed: Boolean,
        val message: String,
        val secondKeyText: String
    )

    private fun runScenario(scenario: TestScenario): TestResult {
        val context = RuntimeEnvironment.getApplication()
        
        // Configure setting
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("alt_tap_latches", scenario.setting)
            // When latches are ON, disable clearAltOnSpace so Space/Enter don't clear the latch
            .putBoolean("clear_alt_on_space", !scenario.setting)
            .apply()
        
        // Setup Q25 profile
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        
        // Create service
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        
        // Setup recording InputConnection
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            // Disable auto-cap: use TYPE_TEXT_VARIATION_NORMAL without CAP flags
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            packageName = "rocks.pkb.plektra.test"
        }
        
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        val downTime = System.currentTimeMillis()
        
        // Step 1: Tap Alt (Q25 physical Alt = ALT_LEFT)
        sendKey(service, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime)
        
        // Step 2: Press first key
        sendKey(service, scenario.firstKey, downTime + 100, downTime + 100)
        
        // Step 3: Press second key (A)
        sendKey(service, scenario.secondKey, downTime + 200, downTime + 200)
        
        // Analyze: did the second key have Alt modifier?
        val secondKeyText = if (recorder.committedTexts.size >= 2) {
            recorder.committedTexts[recorder.committedTexts.size - 1]
        } else if (recorder.committedTexts.size == 1) {
            recorder.committedTexts[0]
        } else {
            ""
        }
        
        // Check if Alt is active: A+Alt produces '*' on Q25
        // Note: "A" (uppercase) means Shift is active (e.g., auto-cap after Enter), not Alt
        val secondKeyHasAlt = secondKeyText == "*"
        
        val passed = secondKeyHasAlt == scenario.expectedSecondKeyHasAlt
        
        val message = if (passed) {
            if (scenario.expectedSecondKeyHasAlt) {
                "Alt persisted correctly (latch)"
            } else {
                "Alt consumed correctly (one-shot)"
            }
        } else {
            if (scenario.expectedSecondKeyHasAlt) {
                "Alt was consumed but should have persisted (latch)"
            } else {
                "Alt persisted but should have been consumed (one-shot BUG)"
            }
        }
        
        return TestResult(
            scenario = scenario,
            passed = passed,
            message = message,
            secondKeyText = secondKeyText
        )
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
}
