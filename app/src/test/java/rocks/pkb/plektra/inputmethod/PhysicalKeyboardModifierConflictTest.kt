package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
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
import rocks.pkb.plektra.core.ShiftState
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * Tests physical keyboard modifier behavior with sticky variation layer setting ON.
 * 
 * Reproduces Ömer's bug: when isStaticVariationBarLayerStickyEnabled=true,
 * the duplicate double-tap logic in onKeyUp conflicts with ModifierStateController,
 * producing: single tap latches, double-tap gives one-shot (opposite of intended).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysicalKeyboardModifierConflictTest {

    @Test
    fun alt_stickyVariationOn_singleTap_shouldGiveOneShot_notLatch() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Enable sticky variation layer (Ömer's setting)
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        
        val service = createService(context)
        
        // Single tap Alt via physical keyboard (onKeyDown/onKeyUp)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1030L))
        
        // Check state: should be ONE-SHOT
        val mod = modifierController(service)
        assertTrue("Single tap should give one-shot", mod.altOneShot)
        assertFalse("Single tap should NOT latch", mod.altLatchActive)
    }

    @Test
    fun alt_stickyVariationOn_doubleTap_shouldGiveLatch() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        
        val service = createService(context)
        
        // Fast double-tap Alt (within 300ms threshold)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 1000L, 1030L))
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 1100L, 1100L))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 1100L, 1130L))
        
        // Check state: should be LATCH
        val mod = modifierController(service)
        assertFalse("Double-tap should NOT be one-shot", mod.altOneShot)
        assertTrue("Double-tap should give latch", mod.altLatchActive)
    }

    @Test
    fun shift_stickyVariationOn_singleTap_shouldGiveOneShot_notCaps() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        
        val service = createService(context)
        
        // Single tap Shift via physical keyboard
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 1000L, 1030L))
        
        // Check state: should be ONE_SHOT
        val mod = modifierController(service)
        assertEquals("Single tap should give one-shot", ShiftState.ONE_SHOT, mod.shiftState)
    }

    @Test
    fun shift_stickyVariationOn_doubleTap_shouldGiveCaps() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        
        val service = createService(context)
        
        // Fast double-tap Shift (within 300ms threshold)
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 1000L, 1000L))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 1000L, 1030L))
        
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 1100L, 1100L))
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 1100L, 1130L))
        
        // Check state: should be CAPS
        val mod = modifierController(service)
        assertEquals("Double-tap should give CAPS", ShiftState.CAPS, mod.shiftState)
    }

    // Helper functions

    private fun createService(context: Context): PhysicalKeyboardInputMethodService {
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        
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
        
        return service
    }

    private fun keyEvent(action: Int, keyCode: Int, downTime: Long, eventTime: Long, metaState: Int = 0): KeyEvent {
        return KeyEvent(downTime, eventTime, action, keyCode, 0, metaState)
    }

    private fun modifierController(service: PhysicalKeyboardInputMethodService): ModifierStateController {
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
        throw NoSuchFieldException("Field $fieldName not found in ${target.javaClass} or its superclasses")
    }

    private class RecordingInputConnection {
        val committedTexts = mutableListOf<String>()
        
        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "commitText" -> {
                        val text = args?.get(0)?.toString() ?: ""
                        committedTexts.add(text)
                        true
                    }
                    "getTextBeforeCursor" -> ""
                    "getTextAfterCursor" -> ""
                    else -> when {
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        method.returnType == Int::class.javaPrimitiveType -> 0
                        else -> null
                    }
                }
            } as InputConnection
        }
    }
}
