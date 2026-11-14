package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Replay test using exact event sequences from the Q25 device log
 * (`device-modifier-log-0346.txt`, build 26.10.3 a088e9f6).
 *
 * Device behavior: every physical Alt/Shift press arrives TWICE:
 * 1. Real event: deviceId=0, flags=0x8 (FLAG_FROM_SYSTEM), ~100-180ms duration
 * 2. Synthetic copy: deviceId=-1, flags=0x0, metaState=0x0, eventTime==downTime
 *    (zero duration), arriving ~13-20ms after the real UP
 *
 * Without filtering, ModifierStateController counts real+copy as a double-tap
 * (single physical press → latch/CAPS). With the entry-point filter, each
 * physical press counts as one tap:
 * - single tap → oneShot
 * - tap again (after one-shot window) → off
 * - fast real double tap → latch/CAPS
 * - tap while latched → off
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyntheticModifierDuplicateReplayTest {

    @Test
    fun alt_realPlusSyntheticCopy_shouldGiveOneShot_notLatch() {
        // Log entries [1]-[4]: real Alt press then synthetic echo ~13ms later.
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, realAlt(downTime = 11_214_651L, upEventTime = 11_214_801L, metaDown = 0x12))
        assertTrue("After real press, Alt should be one-shot", mod.altOneShot)
        assertFalse("After real press, Alt should NOT be latched", mod.altLatchActive)

        dispatch(service, syntheticAlt(eventTime = 11_214_814L))
        assertTrue("After synthetic copy, Alt should still be one-shot", mod.altOneShot)
        assertFalse("After synthetic copy, Alt should NOT be latched", mod.altLatchActive)
    }

    @Test
    fun alt_secondPhysicalTap_clearsOneShot() {
        // Physical presses with their synthetic echoes, spaced beyond double-tap window.
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, realAlt(downTime = 11_214_651L, upEventTime = 11_214_801L, metaDown = 0x12))
        dispatch(service, syntheticAlt(eventTime = 11_214_814L))
        assertTrue(mod.altOneShot)

        // Second physical tap ~3s later (log-style spacing) clears one-shot → OFF.
        dispatch(service, realAlt(downTime = 11_220_942L, upEventTime = 11_221_101L, metaDown = 0x12))
        dispatch(service, syntheticAlt(eventTime = 11_221_114L))
        assertFalse("Second physical tap should clear one-shot", mod.altOneShot)
        assertFalse("Second physical tap should NOT latch", mod.altLatchActive)
    }

    @Test
    fun alt_fastRealDoubleTap_shouldLatch_thenTapClears() {
        val service = createService()
        val mod = modifierController(service)

        // Two real presses within double-tap window; each has a synthetic echo that
        // must be ignored so the second *real* down is what latches.
        dispatch(service, realAlt(downTime = 1_000L, upEventTime = 1_030L, metaDown = 0x12))
        dispatch(service, syntheticAlt(eventTime = 1_045L))
        assertTrue(mod.altOneShot)

        dispatch(service, realAlt(downTime = 1_100L, upEventTime = 1_130L, metaDown = 0x12))
        dispatch(service, syntheticAlt(eventTime = 1_145L))
        assertFalse("Fast real double-tap should NOT stay one-shot", mod.altOneShot)
        assertTrue("Fast real double-tap should latch", mod.altLatchActive)

        // Tap while latched → off (with its echo filtered).
        dispatch(service, realAlt(downTime = 2_000L, upEventTime = 2_030L, metaDown = 0x12))
        dispatch(service, syntheticAlt(eventTime = 2_045L))
        assertFalse("Tap while latched should clear one-shot", mod.altOneShot)
        assertFalse("Tap while latched should clear latch", mod.altLatchActive)
    }

    @Test
    fun shift_realPlusSyntheticCopy_shouldGiveOneShot_notCaps() {
        // Log entries [21]-[24]-style Shift: real press then synthetic echo ~20ms later.
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, realShift(downTime = 11_282_093L, upEventTime = 11_282_214L, metaDown = 0x41))
        assertEquals(ShiftState.ONE_SHOT, mod.shiftState)

        dispatch(service, syntheticShift(eventTime = 11_282_234L))
        assertEquals(
            "After synthetic copy, Shift should still be one-shot",
            ShiftState.ONE_SHOT,
            mod.shiftState
        )
        assertFalse("After synthetic copy, Shift should NOT be CAPS", mod.capsLockEnabled)
    }

    @Test
    fun shift_secondPhysicalTap_clearsOneShot() {
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, realShift(downTime = 11_282_093L, upEventTime = 11_282_214L, metaDown = 0x41))
        dispatch(service, syntheticShift(eventTime = 11_282_234L))
        assertEquals(ShiftState.ONE_SHOT, mod.shiftState)

        dispatch(service, realShift(downTime = 11_285_000L, upEventTime = 11_285_120L, metaDown = 0x41))
        dispatch(service, syntheticShift(eventTime = 11_285_140L))
        assertEquals("Second physical tap should clear one-shot", ShiftState.OFF, mod.shiftState)
    }

    @Test
    fun shift_fastRealDoubleTap_shouldCaps_thenTapClears() {
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, realShift(downTime = 1_000L, upEventTime = 1_030L, metaDown = 0x41))
        dispatch(service, syntheticShift(eventTime = 1_045L))
        assertEquals(ShiftState.ONE_SHOT, mod.shiftState)

        dispatch(service, realShift(downTime = 1_100L, upEventTime = 1_130L, metaDown = 0x41))
        dispatch(service, syntheticShift(eventTime = 1_145L))
        assertEquals("Fast real double-tap should give CAPS", ShiftState.CAPS, mod.shiftState)

        dispatch(service, realShift(downTime = 2_000L, upEventTime = 2_030L, metaDown = 0x41))
        dispatch(service, syntheticShift(eventTime = 2_045L))
        assertEquals("Tap while CAPS should clear", ShiftState.OFF, mod.shiftState)
    }

    @Test
    fun alt_syntheticAlone_shouldGiveOneShot() {
        // Log entries [5]-[6]: synthetic arrives with no recent real event tracked → not filtered.
        val service = createService()
        val mod = modifierController(service)

        dispatch(service, syntheticAlt(eventTime = 11_217_947L))
        assertTrue("Synthetic event alone should give one-shot", mod.altOneShot)
        assertFalse("Synthetic event alone should NOT latch", mod.altLatchActive)
    }

    // --- harness (mirrors PhysicalKeyboardModifierConflictTest) ---

    private fun createService(): PhysicalKeyboardInputMethodService {
        val context: Context = RuntimeEnvironment.getApplication()
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)

        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()

        val inputConnection = RecordingInputConnection().asProxy()
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

    private fun dispatch(service: PhysicalKeyboardInputMethodService, pair: Pair<KeyEvent, KeyEvent>) {
        val (down, up) = pair
        service.onKeyDown(down.keyCode, down)
        service.onKeyUp(up.keyCode, up)
    }

    private fun realAlt(downTime: Long, upEventTime: Long, metaDown: Int): Pair<KeyEvent, KeyEvent> {
        val down = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT,
            0, metaDown, 0, 56, 0x8, 0x101
        )
        val up = KeyEvent(
            downTime, upEventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT,
            0, 0x0, 0, 56, 0x8, 0x101
        )
        return down to up
    }

    private fun syntheticAlt(eventTime: Long): Pair<KeyEvent, KeyEvent> {
        val down = KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT,
            0, 0x0, -1, 56, 0x0, 0x101
        )
        val up = KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT,
            0, 0x0, -1, 56, 0x0, 0x101
        )
        return down to up
    }

    private fun realShift(downTime: Long, upEventTime: Long, metaDown: Int): Pair<KeyEvent, KeyEvent> {
        val down = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT,
            0, metaDown, 0, 42, 0x8, 0x101
        )
        val up = KeyEvent(
            downTime, upEventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT,
            0, 0x0, 0, 42, 0x8, 0x101
        )
        return down to up
    }

    private fun syntheticShift(eventTime: Long): Pair<KeyEvent, KeyEvent> {
        val down = KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT,
            0, 0x0, -1, 42, 0x0, 0x101
        )
        val up = KeyEvent(
            eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT,
            0, 0x0, -1, 42, 0x0, 0x101
        )
        return down to up
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
