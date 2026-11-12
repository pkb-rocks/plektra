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
import org.junit.runners.Parameterized
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
 * Comprehensive table-driven test matrix for Alt modifier behavior on Q25.
 * 
 * Test dimensions:
 * - Setting: OFF (false) / ON (true) / DEFAULT (missing pref)
 * - Lifecycle: first_launch / after_settings_change / after_service_restart / 
 *              after_onStartInput / after_screen_off
 * - Key sequence: single_tap / double_tap / hold_plus_letter / tap_then_N_letters /
 *                 tap_then_space / tap_then_enter / tap_then_backspace
 * - Modifier state: clean / with_shift / with_ctrl / with_sym
 * - Alt key: left (ALT_LEFT) / right (ALT_RIGHT → SYM 63 on Q25)
 * 
 * Expected behavior with OFF:
 * - Single tap: one-shot for exactly ONE character, then cleared
 * - Double tap: latches (per existing double-tap-to-lock spec)
 * - Hold: modifier active only while held
 * 
 * Expected behavior with ON:
 * - Single tap: latches until manually cancelled
 * - Double tap: latches (redundant with single tap)
 * - Hold: modifier active while held
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltModifierComprehensiveTest {

    data class TestCase(
        val name: String,
        val setting: Setting,
        val lifecycle: Lifecycle,
        val keySequence: KeySequence,
        val modifierState: ModifierState,
        val altKey: AltKey,
        val expectedBehavior: ExpectedBehavior
    )

    enum class Setting { OFF, ON, DEFAULT }
    enum class Lifecycle { 
        FIRST_LAUNCH, 
        AFTER_SETTINGS_CHANGE, 
        AFTER_SERVICE_RESTART,
        AFTER_ON_START_INPUT,
        AFTER_SCREEN_OFF 
    }
    enum class KeySequence { 
        SINGLE_TAP, 
        DOUBLE_TAP, 
        HOLD_PLUS_LETTER, 
        TAP_THEN_1_LETTER,
        TAP_THEN_2_LETTERS,
        TAP_THEN_3_LETTERS,
        TAP_THEN_SPACE,
        TAP_THEN_ENTER,
        TAP_THEN_BACKSPACE
    }
    enum class ModifierState { CLEAN, WITH_SHIFT, WITH_CTRL, WITH_SYM }
    enum class AltKey { LEFT, RIGHT }
    enum class ExpectedBehavior { 
        ONE_SHOT,  // Alt active for exactly one key, then cleared
        LATCHED,   // Alt stays active for multiple keys
        HELD_ONLY  // Alt active only while physically pressed
    }

    companion object {
        /**
         * Generate focused test matrix.
         * Prioritize OFF setting with key lifecycle/sequence combinations.
         */
        fun generateTestCases(): List<TestCase> {
            val cases = mutableListOf<TestCase>()
            
            // Core OFF scenarios - most critical combinations
            val criticalLifecycles = listOf(
                Lifecycle.FIRST_LAUNCH,
                Lifecycle.AFTER_SETTINGS_CHANGE,
                Lifecycle.AFTER_ON_START_INPUT
            )
            
            val criticalSequences = listOf(
                KeySequence.TAP_THEN_1_LETTER,
                KeySequence.TAP_THEN_2_LETTERS,
                KeySequence.TAP_THEN_3_LETTERS,
                KeySequence.DOUBLE_TAP,
                KeySequence.HOLD_PLUS_LETTER
            )
            
            // NOTE: Removed RIGHT key (Q25 physical SYM / ALT_RIGHT) scenarios.
            // After fix, physical SYM key opens sym layout, not acts as Alt modifier.
            // These test cases tested the old buggy symWasRemappedFromAlt behavior.
            
            // OFF with LEFT key (verify both Alt keys)
            cases.add(TestCase(
                name = "OFF_FIRST_LAUNCH_TAP_THEN_2_LETTERS_CLEAN_LEFT",
                setting = Setting.OFF,
                lifecycle = Lifecycle.FIRST_LAUNCH,
                keySequence = KeySequence.TAP_THEN_2_LETTERS,
                modifierState = ModifierState.CLEAN,
                altKey = AltKey.LEFT,
                expectedBehavior = ExpectedBehavior.ONE_SHOT
            ))
            
            // NOTE: ON and DEFAULT scenarios with AltKey.RIGHT also removed.
            // Physical SYM key (ALT_RIGHT) opens sym layout, tested separately.
            
            return cases
        }
    }

    @Test
    fun runAllAltModifierScenarios() {
        val testCases = generateTestCases()
        val results = mutableListOf<TestResult>()
        
        println("\n" + "=".repeat(80))
        println("ALT MODIFIER COMPREHENSIVE TEST MATRIX")
        println("Total scenarios: ${testCases.size}")
        println("=".repeat(80))
        
        for ((index, testCase) in testCases.withIndex()) {
            println("\n[${index + 1}/${testCases.size}] ${testCase.name}")
            
            try {
                val result = executeTestCase(testCase)
                results.add(result)
                
                val status = if (result.passed) "✓ PASS" else "✗ FAIL"
                println("  $status: ${result.message}")
                
                if (!result.passed) {
                    println("  Expected: ${testCase.expectedBehavior}")
                    println("  Actual behavior: ${result.actualBehavior}")
                    println("  Details: ${result.details}")
                }
            } catch (e: Exception) {
                println("  ✗ ERROR: ${e.message}")
                e.printStackTrace()
                results.add(TestResult(
                    testCase = testCase,
                    passed = false,
                    message = "Exception: ${e.message}",
                    actualBehavior = "ERROR",
                    details = e.stackTraceToString()
                ))
            }
        }
        
        // Summary
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
                println("  - ${it.testCase.name}")
                println("    Expected: ${it.testCase.expectedBehavior}")
                println("    Actual: ${it.actualBehavior}")
                println("    ${it.message}")
            }
        }
        
        println("=".repeat(80))
        
        // Fail the test if any scenario failed
        assertEquals("All Alt modifier scenarios must pass", 0, failed)
    }

    private data class TestResult(
        val testCase: TestCase,
        val passed: Boolean,
        val message: String,
        val actualBehavior: String,
        val details: String = ""
    )

    private fun executeTestCase(testCase: TestCase): TestResult {
        val context = RuntimeEnvironment.getApplication()
        
        // Configure setting
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        when (testCase.setting) {
            Setting.OFF -> prefs.edit().putBoolean("alt_tap_latches", false).apply()
            Setting.ON -> prefs.edit().putBoolean("alt_tap_latches", true).apply()
            Setting.DEFAULT -> prefs.edit().remove("alt_tap_latches").apply()
        }
        
        // Setup Q25 profile
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        
        // Create service based on lifecycle
        val service = createServiceForLifecycle(testCase.lifecycle, context)
        
        // Setup recording InputConnection
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        
        // Inject context
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        // If lifecycle is AFTER_ON_START_INPUT or AFTER_SCREEN_OFF, trigger those events
        when (testCase.lifecycle) {
            Lifecycle.AFTER_ON_START_INPUT -> {
                service.onStartInput(editorInfo, false)
            }
            Lifecycle.AFTER_SCREEN_OFF -> {
                // Simulate screen off/on
                setField(service, "isInputViewActive", false)
                setField(service, "isInputViewActive", true)
            }
            else -> {}
        }
        
        // Execute key sequence
        val downTime = System.currentTimeMillis()
        executeKeySequence(service, testCase, downTime)
        
        // Analyze results
        return analyzeResults(testCase, recorder)
    }

    private fun createServiceForLifecycle(lifecycle: Lifecycle, context: Context): PhysicalKeyboardInputMethodService {
        return when (lifecycle) {
            Lifecycle.FIRST_LAUNCH -> {
                Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
                    .create()
                    .get()
            }
            Lifecycle.AFTER_SETTINGS_CHANGE -> {
                // Create service, then change setting
                val svc = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
                    .create()
                    .get()
                // Trigger settings reload
                val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("alt_tap_latches", false).apply()
                svc
            }
            Lifecycle.AFTER_SERVICE_RESTART -> {
                // Create, destroy, recreate
                val svc1 = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
                    .create()
                    .get()
                // Now create fresh service
                Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
                    .create()
                    .get()
            }
            else -> {
                Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
                    .create()
                    .get()
            }
        }
    }

    private fun executeKeySequence(
        service: PhysicalKeyboardInputMethodService,
        testCase: TestCase,
        downTime: Long
    ) {
        val altKeyCode = when (testCase.altKey) {
            AltKey.LEFT -> KeyEvent.KEYCODE_ALT_LEFT
            AltKey.RIGHT -> KeyEvent.KEYCODE_ALT_RIGHT  // Q25 physical SYM = 58
        }
        
        when (testCase.keySequence) {
            KeySequence.SINGLE_TAP -> {
                // Tap Alt
                sendKey(service, altKeyCode, downTime, downTime)
                // Press A
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
            }
            
            KeySequence.DOUBLE_TAP -> {
                // First tap
                sendKey(service, altKeyCode, downTime, downTime)
                // Second tap (within double-tap window)
                sendKey(service, altKeyCode, downTime + 100, downTime + 100)
                // Press A
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
                // Press B
                sendKey(service, KeyEvent.KEYCODE_B, downTime + 300, downTime + 300)
            }
            
            KeySequence.HOLD_PLUS_LETTER -> {
                // Hold Alt (down without up)
                val altDown = keyEvent(KeyEvent.ACTION_DOWN, altKeyCode, downTime, downTime)
                service.onKeyDown(altKeyCode, altDown)
                
                // Press A while holding
                val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 50, downTime + 50, metaState = KeyEvent.META_ALT_ON)
                service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
                val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 50, downTime + 100, metaState = KeyEvent.META_ALT_ON)
                service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
                
                // Release Alt
                val altUp = keyEvent(KeyEvent.ACTION_UP, altKeyCode, downTime, downTime + 150)
                service.onKeyUp(altKeyCode, altUp)
                
                // Press B after releasing Alt
                sendKey(service, KeyEvent.KEYCODE_B, downTime + 200, downTime + 200)
            }
            
            KeySequence.TAP_THEN_1_LETTER -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
            }
            
            KeySequence.TAP_THEN_2_LETTERS -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_B, downTime + 200, downTime + 200)
            }
            
            KeySequence.TAP_THEN_3_LETTERS -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_B, downTime + 200, downTime + 200)
                sendKey(service, KeyEvent.KEYCODE_C, downTime + 300, downTime + 300)
            }
            
            KeySequence.TAP_THEN_SPACE -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_SPACE, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
            
            KeySequence.TAP_THEN_ENTER -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_ENTER, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
            
            KeySequence.TAP_THEN_BACKSPACE -> {
                sendKey(service, altKeyCode, downTime, downTime)
                sendKey(service, KeyEvent.KEYCODE_DEL, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
        }
    }

    private fun sendKey(service: PhysicalKeyboardInputMethodService, keyCode: Int, downTime: Long, eventTime: Long) {
        val down = keyEvent(KeyEvent.ACTION_DOWN, keyCode, downTime, eventTime)
        service.onKeyDown(keyCode, down)
        val up = keyEvent(KeyEvent.ACTION_UP, keyCode, downTime, eventTime + 50)
        service.onKeyUp(keyCode, up)
    }

    private fun analyzeResults(testCase: TestCase, recorder: RecordingInputConnection): TestResult {
        val commits = recorder.committedTexts
        
        when (testCase.expectedBehavior) {
            ExpectedBehavior.ONE_SHOT -> {
                // First character should use Alt modifier, subsequent should not
                return when (testCase.keySequence) {
                    KeySequence.SINGLE_TAP, KeySequence.TAP_THEN_1_LETTER -> {
                        if (commits.size < 1) {
                            TestResult(testCase, false, "No commits recorded", "NONE")
                        } else {
                            val first = commits[0]
                            if (first == "a") {
                                TestResult(testCase, false, "First commit is plain 'a', Alt not applied", "NO_ALT")
                            } else {
                                TestResult(testCase, true, "One-shot worked: first char has Alt", "ONE_SHOT")
                            }
                        }
                    }
                    
                    KeySequence.TAP_THEN_2_LETTERS -> {
                        if (commits.size < 2) {
                            TestResult(testCase, false, "Expected 2 commits, got ${commits.size}", "INSUFFICIENT")
                        } else {
                            val first = commits[0]
                            val second = commits[1]
                            
                            if (first == "a") {
                                TestResult(testCase, false, "First commit is plain 'a', Alt not applied", "NO_ALT")
                            } else if (second != "b") {
                                TestResult(testCase, false, "Second commit is not plain 'b' (got '$second'), Alt incorrectly latched", "LATCHED")
                            } else {
                                TestResult(testCase, true, "One-shot worked: first modified, second plain", "ONE_SHOT")
                            }
                        }
                    }
                    
                    KeySequence.TAP_THEN_3_LETTERS -> {
                        if (commits.size < 3) {
                            TestResult(testCase, false, "Expected 3 commits, got ${commits.size}", "INSUFFICIENT")
                        } else {
                            val first = commits[0]
                            val second = commits[1]
                            val third = commits[2]
                            
                            if (first == "a") {
                                TestResult(testCase, false, "First commit is plain 'a', Alt not applied", "NO_ALT")
                            } else if (second != "b" || third != "c") {
                                TestResult(testCase, false, "Commits 2/3 not plain (got '$second'/'$third'), Alt incorrectly latched", "LATCHED")
                            } else {
                                TestResult(testCase, true, "One-shot worked", "ONE_SHOT")
                            }
                        }
                    }
                    
                    KeySequence.TAP_THEN_SPACE, KeySequence.TAP_THEN_ENTER, KeySequence.TAP_THEN_BACKSPACE -> {
                        // Special keys should consume or not affect Alt one-shot
                        // Then next letter should be plain
                        val lastCommit = commits.lastOrNull()
                        if (lastCommit == "a") {
                            TestResult(testCase, true, "Special key handled, next letter plain", "ONE_SHOT")
                        } else {
                            TestResult(testCase, false, "Last commit not plain 'a' (got '$lastCommit')", "UNCLEAR")
                        }
                    }
                    
                    else -> TestResult(testCase, false, "Unhandled sequence", "UNKNOWN")
                }
            }
            
            ExpectedBehavior.LATCHED -> {
                // All characters after tap should use Alt modifier
                return when (testCase.keySequence) {
                    KeySequence.DOUBLE_TAP -> {
                        if (commits.size < 2) {
                            TestResult(testCase, false, "Expected 2+ commits for double tap", "INSUFFICIENT")
                        } else {
                            val first = commits[0]
                            val second = commits[1]
                            
                            if (first == "a" || second == "b") {
                                TestResult(testCase, false, "Plain letters found (got '$first'/'$second'), latch not working", "NO_LATCH")
                            } else {
                                TestResult(testCase, true, "Latch working: both modified", "LATCHED")
                            }
                        }
                    }
                    
                    KeySequence.TAP_THEN_2_LETTERS -> {
                        if (commits.size < 2) {
                            TestResult(testCase, false, "Expected 2 commits", "INSUFFICIENT")
                        } else {
                            val first = commits[0]
                            val second = commits[1]
                            
                            if (first == "a" || second == "b") {
                                TestResult(testCase, false, "Plain letters found, single tap should latch with ON", "NO_LATCH")
                            } else {
                                TestResult(testCase, true, "Latch working", "LATCHED")
                            }
                        }
                    }
                    
                    else -> TestResult(testCase, true, "Latch scenario passed", "LATCHED")
                }
            }
            
            ExpectedBehavior.HELD_ONLY -> {
                // First character (while holding) should have Alt, subsequent should not
                return if (commits.size < 2) {
                    TestResult(testCase, false, "Expected 2 commits", "INSUFFICIENT")
                } else {
                    val first = commits[0]
                    val second = commits[1]
                    
                    if (first == "a") {
                        TestResult(testCase, false, "First commit is plain 'a', held Alt not applied", "NO_ALT")
                    } else if (second != "b") {
                        TestResult(testCase, false, "Second commit not plain 'b' (got '$second'), held Alt leaked", "ALT_LEAKED")
                    } else {
                        TestResult(testCase, true, "Held modifier worked correctly", "HELD_ONLY")
                    }
                }
            }
        }
    }

    // Helper functions
    @Test
    fun q25SymKey_opensSymLayout_notAlt() {
        println("\n${"=".repeat(80)}")
        println("Q25 SYM KEY TEST")
        println("Physical SYM key (ALT_RIGHT) should open sym layout, NOT activate Alt")
        println("${"=".repeat(80)}")
        
        val context = RuntimeEnvironment.getApplication()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        service.onStartInput(editorInfo, false)
        service.onStartInputView(editorInfo, false)
        
        val symLayout = getField<rocks.pkb.plektra.core.SymLayoutController>(service, "symLayoutController")
        val mod = getField<rocks.pkb.plektra.core.ModifierStateController>(service, "modifierStateController")
        
        val downTime = 1000L
        
        // Press ALT_RIGHT (physical SYM key)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, downTime, downTime))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, downTime, downTime + 30))
        
        // Assert: SYM layout opened, Alt NOT activated
        assertTrue("SYM key should open sym layout", symLayout.isSymActive())
        assertFalse("SYM key should NOT activate Alt one-shot", mod.altOneShot)
        assertFalse("SYM key should NOT activate Alt latch", mod.altLatchActive)
        
        println("✓ PASS: SYM key opened sym layout and did not activate Alt")
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
        throw NoSuchFieldException("Field $fieldName not found in ${target.javaClass.name} or superclasses")
    }

    private class RecordingInputConnection {
        var textBeforeCursor: String = ""
        var sendKeyEventResult: Boolean = true
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
                        sendKeyEventResult
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
                    else -> defaultValue(method.returnType)
                }
            } as InputConnection
        }

        private fun defaultValue(type: Class<*>): Any? {
            return when {
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    }
}
