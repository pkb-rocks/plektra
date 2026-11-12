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
import rocks.pkb.plektra.core.ModifierStateController
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * Test for double-tap to lock timing bug (Task 1):
 * - Owner's settings: 'Single-tap locks' all OFF, Alt binding Q25, Release Alt with Space/Enter ON
 * - Fast double-tap (80-250 ms) does NOT lock
 * - Has to tap slowly to lock
 * - The rule: two taps must be consecutive (no other key between) MUST stay
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoubleTapLockTimingTest {

    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var recorder: RecordingInputConnection
    private lateinit var inputConnection: InputConnection
    private lateinit var editorInfo: EditorInfo

    private fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        
        // Clear any test overrides
        DeviceSpecific.clearTestOverrides()
        
        // Configure Q25 profile
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        
        // Owner's settings: All single-tap locks OFF
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("shift_tap_latches", false)
            putBoolean("ctrl_tap_latches", false)
            putBoolean("alt_tap_latches", false)
            putBoolean("release_alt_with_space", true)
            putBoolean("keep_alt_lock", false)
        }.apply()
        
        // Standard settings
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        
        // Set up recording InputConnection
        recorder = RecordingInputConnection()
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        
        // Create service via Robolectric
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()

        // Simulate onStartInput lifecycle
        service.onStartInput(editorInfo, false)
        service.onStartInputView(editorInfo, false)
        
        // Inject active editable field context
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
    }

    data class DoubleTapScenario(
        val name: String,
        val modifierKeyCode: Int,  // Q25 physical key code (SHIFT_RIGHT for Ctrl, ALT_LEFT for Alt)
        val timeBetweenTaps: Long,  // milliseconds between first release and second press
        val shouldLock: Boolean
    )

    @Test
    fun doubleTapLockTiming_shift_fastAndSlow() {
        val scenarios = listOf(
            DoubleTapScenario("Shift_80ms", KeyEvent.KEYCODE_SHIFT_LEFT, 80L, true),
            DoubleTapScenario("Shift_150ms", KeyEvent.KEYCODE_SHIFT_LEFT, 150L, true),
            DoubleTapScenario("Shift_250ms", KeyEvent.KEYCODE_SHIFT_LEFT, 250L, true),
            DoubleTapScenario("Shift_600ms", KeyEvent.KEYCODE_SHIFT_LEFT, 600L, false)
        )
        
        runDoubleTapScenarios(scenarios, "Shift")
    }

    @Test
    fun doubleTapLockTiming_ctrl_fastAndSlow() {
        val scenarios = listOf(
            DoubleTapScenario("Ctrl_80ms", KeyEvent.KEYCODE_SHIFT_RIGHT, 80L, true),
            DoubleTapScenario("Ctrl_150ms", KeyEvent.KEYCODE_SHIFT_RIGHT, 150L, true),
            DoubleTapScenario("Ctrl_250ms", KeyEvent.KEYCODE_SHIFT_RIGHT, 250L, true),
            DoubleTapScenario("Ctrl_600ms", KeyEvent.KEYCODE_SHIFT_RIGHT, 600L, false)
        )
        
        runDoubleTapScenarios(scenarios, "Ctrl")
    }

    @Test
    fun doubleTapLockTiming_alt_fastAndSlow() {
        val scenarios = listOf(
            DoubleTapScenario("Alt_80ms", KeyEvent.KEYCODE_ALT_LEFT, 80L, true),
            DoubleTapScenario("Alt_150ms", KeyEvent.KEYCODE_ALT_LEFT, 150L, true),
            DoubleTapScenario("Alt_250ms", KeyEvent.KEYCODE_ALT_LEFT, 250L, true),
            DoubleTapScenario("Alt_600ms", KeyEvent.KEYCODE_ALT_LEFT, 600L, false)
        )
        
        runDoubleTapScenarios(scenarios, "Alt")
    }

    private fun runDoubleTapScenarios(scenarios: List<DoubleTapScenario>, modifierName: String) {
        println("\n${"=".repeat(80)}")
        println("DOUBLE-TAP LOCK TIMING TEST - $modifierName")
        println("${"=".repeat(80)}")
        
        val results = mutableListOf<Pair<DoubleTapScenario, Boolean>>()
        
        for (scenario in scenarios) {
            setUp()  // Fresh service for each test
            
            println("\n[${scenario.name}] Time between taps: ${scenario.timeBetweenTaps}ms, Expected lock: ${scenario.shouldLock}")
            
            val t0 = 10_000L
            val tapDuration = 50L
            
            // First tap
            val down1 = keyEvent(KeyEvent.ACTION_DOWN, scenario.modifierKeyCode, t0, t0)
            val up1 = keyEvent(KeyEvent.ACTION_UP, scenario.modifierKeyCode, t0, t0 + tapDuration)
            
            service.onKeyDown(scenario.modifierKeyCode, down1)
            service.onKeyUp(scenario.modifierKeyCode, up1)
            
            // Second tap after specified interval
            val t1 = t0 + tapDuration + scenario.timeBetweenTaps
            val down2 = keyEvent(KeyEvent.ACTION_DOWN, scenario.modifierKeyCode, t1, t1)
            val up2 = keyEvent(KeyEvent.ACTION_UP, scenario.modifierKeyCode, t1, t1 + tapDuration)
            
            service.onKeyDown(scenario.modifierKeyCode, down2)
            service.onKeyUp(scenario.modifierKeyCode, up2)
            
            // Check state
            val controller = getField(service, "modifierStateController") as ModifierStateController
            val isLocked = when (modifierName) {
                "Shift" -> controller.capsLockEnabled
                "Ctrl" -> controller.ctrlLatchActive
                "Alt" -> controller.altLatchActive
                else -> false
            }
            
            val passed = isLocked == scenario.shouldLock
            results.add(scenario to passed)
            
            val status = if (passed) "✓ PASS" else "✗ FAIL"
            println("  $status: Lock active = $isLocked, Expected = ${scenario.shouldLock}")
        }
        
        val failed = results.count { !it.second }
        if (failed > 0) {
            println("\n${"=".repeat(80)}")
            println("FAILURES:")
            results.filter { !it.second }.forEach { (scenario, _) ->
                println("  - ${scenario.name}: Expected lock=${scenario.shouldLock}")
            }
            fail("$failed of ${scenarios.size} scenarios failed")
        } else {
            println("\n${"=".repeat(80)}")
            println("✓ ALL TESTS PASSED")
        }
    }

    @Test
    fun doubleTapMustBeConsecutive_tapLetterTap_noLock() {
        println("\n${"=".repeat(80)}")
        println("DOUBLE-TAP CONSECUTIVE REQUIREMENT TEST")
        println("Tap-Letter-Tap must NOT lock (must be consecutive)")
        println("${"=".repeat(80)}")
        
        val modifiers = listOf(
            "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "Ctrl" to KeyEvent.KEYCODE_SHIFT_RIGHT,  // Q25 Ctrl
            "Alt" to KeyEvent.KEYCODE_ALT_LEFT        // Q25 physical Alt
        )
        
        for ((name, keyCode) in modifiers) {
            setUp()
            
            println("\n[$name] Tap-A-Tap (should NOT lock)")
            
            val t0 = 10_000L
            
            // First tap
            val down1 = keyEvent(KeyEvent.ACTION_DOWN, keyCode, t0, t0)
            val up1 = keyEvent(KeyEvent.ACTION_UP, keyCode, t0, t0 + 50)
            service.onKeyDown(keyCode, down1)
            service.onKeyUp(keyCode, up1)
            
            // Letter 'A' between taps
            val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 100, t0 + 100)
            val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, t0 + 100, t0 + 150)
            service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
            service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
            
            // Second tap (fast, within 250ms)
            val down2 = keyEvent(KeyEvent.ACTION_DOWN, keyCode, t0 + 200, t0 + 200)
            val up2 = keyEvent(KeyEvent.ACTION_UP, keyCode, t0 + 200, t0 + 250)
            service.onKeyDown(keyCode, down2)
            service.onKeyUp(keyCode, up2)
            
            // Check state
            val controller = getField(service, "modifierStateController") as ModifierStateController
            val isLocked = when (name) {
                "Shift" -> controller.capsLockEnabled
                "Ctrl" -> controller.ctrlLatchActive
                "Alt" -> controller.altLatchActive
                else -> false
            }
            
            val status = if (!isLocked) "✓ PASS" else "✗ FAIL"
            println("  $status: Lock active = $isLocked (expected false)")
            
            assertFalse("$name: Tap-A-Tap must NOT lock", isLocked)
        }
    }

    @Test
    fun singleTapIsOneShot() {
        println("\n${"=".repeat(80)}")
        println("SINGLE TAP ONE-SHOT TEST")
        println("${"=".repeat(80)}")
        
        val modifiers = listOf(
            "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "Ctrl" to KeyEvent.KEYCODE_SHIFT_RIGHT,
            "Alt" to KeyEvent.KEYCODE_ALT_LEFT
        )
        
        for ((name, keyCode) in modifiers) {
            setUp()
            
            println("\n[$name] Single tap")
            
            val t0 = 10_000L
            
            val down = keyEvent(KeyEvent.ACTION_DOWN, keyCode, t0, t0)
            val up = keyEvent(KeyEvent.ACTION_UP, keyCode, t0, t0 + 50)
            service.onKeyDown(keyCode, down)
            service.onKeyUp(keyCode, up)
            
            val controller = getField(service, "modifierStateController") as ModifierStateController
            val isOneShot = when (name) {
                "Shift" -> controller.shiftOneShot
                "Ctrl" -> controller.ctrlOneShot
                "Alt" -> controller.altOneShot
                else -> false
            }
            
            val status = if (isOneShot) "✓ PASS" else "✗ FAIL"
            println("  $status: One-shot = $isOneShot (expected true)")
            
            assertTrue("$name: Single tap should enable one-shot", isOneShot)
        }
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

    private fun getField(target: Any, fieldName: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
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
        val contextMenuActions = mutableListOf<Int>()

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
                    "performContextMenuAction" -> {
                        val action = args?.getOrNull(0) as? Int
                        if (action != null) {
                            contextMenuActions += action
                        }
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
                else -> null
            }
        }
    }
}
