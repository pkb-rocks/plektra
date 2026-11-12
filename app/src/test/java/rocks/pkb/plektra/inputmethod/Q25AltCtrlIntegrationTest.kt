package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import org.junit.Assert.*
import org.junit.Before
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
 * Integration tests for Q25 Alt and Ctrl behavior bugs:
 * 1. Alt always latches even when alt_tap_latches setting is OFF
 * 2. Ctrl shortcuts must beat Alt symbol mappings
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Q25AltCtrlIntegrationTest {

    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var recorder: RecordingInputConnection
    private lateinit var inputConnection: InputConnection
    private lateinit var editorInfo: EditorInfo

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        
        // Clear any test overrides
        DeviceSpecific.clearTestOverrides()
        
        // Configure Q25 profile with device fingerprint
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        
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
    }

    private fun createAndBindService(altTapLatches: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        
        // Set alt_tap_latches preference BEFORE service creation
        val prefs = context.getSharedPreferences("plektra_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("alt_tap_latches", altTapLatches).apply()
        
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
        
        println("\n=== Service Setup Complete ===")
        println("Profile: Q25")
        println("Alt binding: device_sym:Q25")
        println("alt_tap_latches: ${SettingsManager.getAltTapLatches(context)}")
        
    }

    // =========================
    // Bug 1: Alt tap latch tests
    // =========================
    
    @Test
    fun bug1_altTapOff_shouldBeOneShot() {
        println("\n=== BUG 1 TEST: Alt tap OFF -> A -> A (one-shot) ===")
        createAndBindService(altTapLatches = false)
        
        val downTime = 1000L
        
        // Step 1: Alt tap (Q25 physical Alt = KEYCODE_ALT_LEFT = 57)
        println("\nStep 1: Alt tap (keycode 57)")
        val altDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime)
        val altUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime + 50)
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, altDown)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, altUp)
        
        println("  Alt tap sent to service")
        
        // Step 2: Press 'A' (first time - should produce Alt+A symbol)
        println("\nStep 2: First 'A' press (should use Alt modifier)")
        val aDown1 = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
        val aUp1 = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 100, downTime + 150)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown1)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp1)
        
        println("  Editor commits so far: ${recorder.committedTexts}")
        
        // Step 3: Press 'A' again (should produce normal 'a')
        println("\nStep 3: Second 'A' press (should be normal 'a')")
        val aDown2 = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
        val aUp2 = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 200, downTime + 250)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown2)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp2)
        
        println("  All editor commits: ${recorder.committedTexts}")
        
        // Assertions
        assertEquals("Should have exactly 2 commits", 2, recorder.committedTexts.size)
        
        val firstCommit = recorder.committedTexts[0]
        val secondCommit = recorder.committedTexts[1]
        
        println("\n=== Final Verification ===")
        println("First commit: '$firstCommit' (expected: Alt+A symbol, NOT 'a')")
        println("Second commit: '$secondCommit' (expected: 'a')")
        
        // First press: Alt modifier should be active
        assertNotEquals("First A should NOT produce plain 'a' (Alt modifier active)", 
            "a", firstCommit)
        
        // Second press: Alt should be consumed, producing normal 'a'
        assertEquals("Second A should produce plain 'a' (Alt consumed)", 
            "a", secondCommit)
    }
    
    @Test
    fun bug1_altTapOn_shouldGiveOneShot_notLatch() {
        println("\n=== BUG 1 FIX: Alt tap with alt_tap_latches=true should give ONE-SHOT (not latch) ===")
        createAndBindService(altTapLatches = true)
        
        val downTime = 2000L
        
        // Alt tap - with obsolete alt_tap_latches=true, this used to latch but now gives one-shot
        val altDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime)
        val altUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime + 50)
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, altDown)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, altUp)
        
        println("Alt tap sent")
        
        // Send 'A' key - should produce Alt+A symbol due to one-shot
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
        val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 100, downTime + 150)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
        
        println("First 'A' pressed, commits: ${recorder.committedTexts}")
        
        // Send 'A' again - should produce plain 'a' because one-shot was consumed
        val aDown2 = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
        val aUp2 = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 200, downTime + 250)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown2)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp2)
        
        println("Second 'A' pressed, all commits: ${recorder.committedTexts}")
        
        // First press uses Alt (one-shot), second press is plain 'a' (one-shot consumed)
        assertTrue("Should have at least 2 commits", recorder.committedTexts.size >= 2)
        assertNotEquals("First A should NOT produce plain 'a' (one-shot applies)", 
            "a", recorder.committedTexts[0])
        assertEquals("Second A should produce plain 'a' (one-shot consumed)", 
            "a", recorder.committedTexts[1])
    }

    @Test
    fun bug1_altHold_shouldActAsModifier() {
        println("\n=== BUG 1 TEST: Alt HOLD -> A (held modifier) ===")
        createAndBindService(altTapLatches = false)
        
        val downTime = 3000L
        
        // Alt DOWN (hold)
        val altDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, altDown)
        
        // Press 'A' while Alt is held
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 50, downTime + 50, metaState = KeyEvent.META_ALT_ON)
        val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 50, downTime + 100, metaState = KeyEvent.META_ALT_ON)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
        
        // Alt UP (release)
        val altUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime + 150)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, altUp)
        
        println("Commits after Alt+A hold: ${recorder.committedTexts}")
        
        // Should produce Alt+A symbol
        assertTrue("Should have at least 1 commit", recorder.committedTexts.size >= 1)
        assertNotEquals("Alt+A hold should NOT produce plain 'a'", 
            "a", recorder.committedTexts[0])
    }

    @Test
    fun bug1_altDoubleTap_shouldToggleLock() {
        println("\n=== BUG 1 TEST: Alt double-tap (lock toggle) ===")
        createAndBindService(altTapLatches = false)
        
        val downTime = 4000L
        
        // First tap
        val alt1Down = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime)
        val alt1Up = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, downTime, downTime + 50)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, alt1Down)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, alt1Up)
        
        // Second tap within threshold (< 500ms)
        val alt2Down = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, downTime + 100, downTime + 100)
        val alt2Up = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, downTime + 100, downTime + 150)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, alt2Down)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, alt2Up)
        
        println("Double-tap sent")
        
        // Press 'A' twice - both should use Alt (lock active)
        val aDown1 = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
        val aUp1 = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 200, downTime + 250)
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown1)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp1)
        
        val aDown2 = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 300, downTime + 300)
        val aUp2 = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 300, downTime + 350)
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown2)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp2)
        
        println("Commits: ${recorder.committedTexts}")
        
        assertTrue("Should have at least 2 commits", recorder.committedTexts.size >= 2)
        assertNotEquals("First A should use Alt (lock active)", "a", recorder.committedTexts[0])
        assertNotEquals("Second A should ALSO use Alt (lock persists)", "a", recorder.committedTexts[1])
    }

    // =========================
    // Bug 2: Ctrl priority tests
    // =========================
    
    @Test
    fun bug2_ctrlA_withAltMapping_shouldSelectAll() {
        println("\n=== BUG 2 TEST: Ctrl+A with Alt mapping should select all ===")
        createAndBindService(altTapLatches = false)
        
        // Setup: put some text in the editor
        recorder.textBeforeCursor = "Hello World"
        
        val downTime = 5000L
        
        // Hold Ctrl (Q25 physical Ctrl = SHIFT_RIGHT, remapped to CTRL_LEFT)
        val ctrlDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime)
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlDown)
        
        // Press A (while Ctrl is held, even if A has Alt mapping)
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 50, downTime + 50, metaState = KeyEvent.META_CTRL_ON)
        val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 50, downTime + 100, metaState = KeyEvent.META_CTRL_ON)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
        
        // Release Ctrl
        val ctrlUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime + 150)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlUp)
        
        println("Context menu actions: ${recorder.contextMenuActions}")
        println("Committed texts: ${recorder.committedTexts}")
        
        // Should perform SELECT_ALL, NOT commit Alt+A symbol
        assertTrue("Should perform select_all action", 
            recorder.contextMenuActions.contains(android.R.id.selectAll))
        assertFalse("Should NOT commit any text (Ctrl shortcut, not Alt symbol)", 
            recorder.committedTexts.any { it.isNotEmpty() })
    }

    @Test
    fun bug2_ctrlC_shouldCopy() {
        println("\n=== BUG 2 TEST: Ctrl+C should copy ===")
        createAndBindService(altTapLatches = false)
        
        recorder.textBeforeCursor = "Test"
        
        val downTime = 6000L
        
        val ctrlDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime)
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlDown)
        
        val cDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, downTime + 50, downTime + 50, metaState = KeyEvent.META_CTRL_ON)
        val cUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, downTime + 50, downTime + 100, metaState = KeyEvent.META_CTRL_ON)
        
        service.onKeyDown(KeyEvent.KEYCODE_C, cDown)
        service.onKeyUp(KeyEvent.KEYCODE_C, cUp)
        
        val ctrlUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime + 150)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlUp)
        
        println("Context menu actions: ${recorder.contextMenuActions}")
        println("Committed texts: ${recorder.committedTexts}")
        
        assertTrue("Should perform copy action", 
            recorder.contextMenuActions.contains(android.R.id.copy))
        assertFalse("Should NOT commit any text (Ctrl shortcut, not Alt symbol)", 
            recorder.committedTexts.any { it.isNotEmpty() })
    }

    @Test
    fun bug2_ctrlV_shouldPaste() {
        println("\n=== BUG 2 TEST: Ctrl+V should paste ===")
        createAndBindService(altTapLatches = false)
        
        val downTime = 7000L
        
        val ctrlDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime)
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlDown)
        
        val vDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, downTime + 50, downTime + 50, metaState = KeyEvent.META_CTRL_ON)
        val vUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, downTime + 50, downTime + 100, metaState = KeyEvent.META_CTRL_ON)
        
        service.onKeyDown(KeyEvent.KEYCODE_V, vDown)
        service.onKeyUp(KeyEvent.KEYCODE_V, vUp)
        
        val ctrlUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime + 150)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlUp)
        
        println("Context menu actions: ${recorder.contextMenuActions}")
        println("Committed texts: ${recorder.committedTexts}")
        
        assertTrue("Should perform paste action", 
            recorder.contextMenuActions.contains(android.R.id.paste))
        assertFalse("Should NOT commit any text (Ctrl shortcut, not Alt symbol)", 
            recorder.committedTexts.any { it.isNotEmpty() })
    }

    @Test
    fun bug2_ctrlLatchPlusA_shouldSelectAll() {
        println("\n=== BUG 2 TEST: Ctrl latch + A should select all ===")
        createAndBindService(altTapLatches = false)
        
        recorder.textBeforeCursor = "Sample Text"
        
        val downTime = 8000L
        
        // Tap Ctrl to latch
        val ctrlDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, downTime, downTime)
        val ctrlUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, downTime, downTime + 50)
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlDown)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlUp)
        
        // Press A
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
        val aUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, downTime + 100, downTime + 150)
        
        service.onKeyDown(KeyEvent.KEYCODE_A, aDown)
        service.onKeyUp(KeyEvent.KEYCODE_A, aUp)
        
        println("Context menu actions: ${recorder.contextMenuActions}")
        println("Committed texts: ${recorder.committedTexts}")
        
        assertTrue("Should perform select_all action", 
            recorder.contextMenuActions.contains(android.R.id.selectAll))
        assertFalse("Should NOT commit any text (Ctrl shortcut, not Alt symbol)", 
            recorder.committedTexts.any { it.isNotEmpty() })
    }

    // Helper functions
    
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
                            println("  [IC] performContextMenuAction: $action")
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
