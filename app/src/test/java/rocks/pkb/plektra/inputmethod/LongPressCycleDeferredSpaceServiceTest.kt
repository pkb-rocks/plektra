package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker
import rocks.pkb.plektra.core.InputContextState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * Punctuation spacing with ':' set to "Before next text" must survive a long-press cycle
 * that ends on ':' (Shift, then Device SYM, keep the last character), exactly as it does
 * for a plain Device SYM long press. The editor here reports selection changes the way a
 * TextView does (one report per edit outside a batch edit), and the reports are delivered
 * to onUpdateSelection only after the key handler returns, as they are on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LongPressCycleDeferredSpaceServiceTest {

    private lateinit var context: Context
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var editor: FakeEditor
    private var clock = 10_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setSpaceAfterPunctuation(context, ":")
        SettingsManager.setLongPressThreshold(context, 50L)
        DeferredPunctuationSpaceTracker.clear()
    }

    @After
    fun tearDown() {
        SettingsManager.setLongPressModifier(context, "alt")
        SettingsManager.setLongPressCycleNext(context, "off")
        SettingsManager.setLongPressCycleEnd(context, "stop")
        SettingsManager.setSpaceAfterPunctuation(context, "")
        DeferredPunctuationSpaceTracker.clear()
    }

    @Test
    fun plainDeviceSymLongPress_colonDefersSpaceUntilNextText() {
        SettingsManager.setLongPressModifier(context, "alt")
        SettingsManager.setLongPressCycleNext(context, "off")
        startService(FakeEditor(honorBatchEdits = true))

        typeAColonB()

        assertEquals("a: b", editor.text)
    }

    @Test
    fun longPressCycleEndingOnColon_defersSpaceUntilNextText() {
        configureShiftThenDeviceSymCycle()
        startService(FakeEditor(honorBatchEdits = true))

        typeAColonB()

        assertEquals("a: b", editor.text)
    }

    @Test
    fun longPressCycleEndingOnColon_defersSpaceWhenEditorReportsEveryEdit() {
        // Some editors ignore batch edits and report the delete half of the final
        // re-commit on its own, after the re-commit has already happened.
        configureShiftThenDeviceSymCycle()
        startService(FakeEditor(honorBatchEdits = false))

        typeAColonB()

        assertEquals("a: b", editor.text)
    }

    @Test
    fun longPressCycleEndingOnColon_realCursorMoveStillDropsDeferredSpace() {
        configureShiftThenDeviceSymCycle()
        startService(FakeEditor(honorBatchEdits = true))

        tap(KeyEvent.KEYCODE_A)
        settleKeyboardWindow()
        hold(KeyEvent.KEYCODE_H, holdMs = 1500L)
        assertEquals("a:", editor.text)
        editor.moveCursorByUser(0)
        deliverSelectionUpdates()
        tap(KeyEvent.KEYCODE_B)

        assertEquals("ba:", editor.text)
    }

    private fun configureShiftThenDeviceSymCycle() {
        SettingsManager.setLongPressModifier(context, "shift")
        SettingsManager.setLongPressCycleNext(context, "alt")
        SettingsManager.setLongPressCycleEnd(context, "stop")
    }

    private fun typeAColonB() {
        tap(KeyEvent.KEYCODE_A)
        assertEquals("a", editor.text)
        settleKeyboardWindow()
        hold(KeyEvent.KEYCODE_H, holdMs = 1500L)
        assertEquals("a:", editor.text)
        tap(KeyEvent.KEYCODE_B)
    }

    private fun startService(fakeEditor: FakeEditor) {
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        editor = fakeEditor
        val inputConnection = editor.asProxy()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        // The manager's H mapping must be ':' for this scenario, as on the Titan 2 Elite.
        service.addAltKeyMapping(KeyEvent.KEYCODE_H, ":")
    }

    private fun tap(keyCode: Int) {
        val down = clock
        service.onKeyDown(keyCode, KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        deliverSelectionUpdates()
        clock += 20L
        service.onKeyUp(keyCode, KeyEvent(down, clock, KeyEvent.ACTION_UP, keyCode, 0, 0))
        deliverSelectionUpdates()
        idle(20L)
    }

    private fun hold(keyCode: Int, holdMs: Long) {
        val down = clock
        service.onKeyDown(keyCode, KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        deliverSelectionUpdates()
        var held = 0L
        while (held < holdMs) {
            idle(10L)
            deliverSelectionUpdates()
            held += 10L
        }
        clock += holdMs
        service.onKeyUp(keyCode, KeyEvent(down, clock, KeyEvent.ACTION_UP, keyCode, 0, 0))
        deliverSelectionUpdates()
        idle(20L)
    }

    /**
     * The first key shows the keyboard surface. Robolectric cannot render it, so the
     * visibility controller retries and finally hides the window, which resets transient
     * key state. Let that finish before a long press, so it cannot cut the press short.
     */
    private fun settleKeyboardWindow() {
        idle(5_000L)
        deliverSelectionUpdates()
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        clock += ms
    }

    private fun deliverSelectionUpdates() {
        while (editor.pendingReports.isNotEmpty()) {
            val report = editor.pendingReports.removeAt(0)
            service.onUpdateSelection(
                report.oldStart,
                report.oldEnd,
                report.newStart,
                report.newEnd,
                report.candidatesStart,
                report.candidatesEnd
            )
        }
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

    private data class SelectionReport(
        val oldStart: Int,
        val oldEnd: Int,
        val newStart: Int,
        val newEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int
    )

    /**
     * A small editor model: edits apply at once, and a selection report is queued when the
     * outermost batch edit ends (or after every edit when [honorBatchEdits] is false) and
     * the selection or composing region changed since the last report.
     */
    private class FakeEditor(private val honorBatchEdits: Boolean) {
        var text: String = ""
            private set
        private var selectionStart = 0
        private var selectionEnd = 0
        private var composingStart = -1
        private var composingEnd = -1
        private var batchDepth = 0
        private var reported = SelectionReport(0, 0, 0, 0, -1, -1)
        val pendingReports = mutableListOf<SelectionReport>()

        fun moveCursorByUser(position: Int) {
            selectionStart = position
            selectionEnd = position
            composingStart = -1
            composingEnd = -1
            report()
        }

        private fun editDone() {
            if (!honorBatchEdits || batchDepth == 0) report()
        }

        private fun report() {
            val next = SelectionReport(
                reported.newStart,
                reported.newEnd,
                selectionStart,
                selectionEnd,
                composingStart,
                composingEnd
            )
            if (next.newStart != reported.newStart ||
                next.newEnd != reported.newEnd ||
                next.candidatesStart != reported.candidatesStart ||
                next.candidatesEnd != reported.candidatesEnd
            ) {
                pendingReports += next
                reported = next
            }
        }

        private fun replace(start: Int, end: Int, replacement: String) {
            text = text.substring(0, start) + replacement + text.substring(end)
        }

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "beginBatchEdit" -> {
                        batchDepth++
                        true
                    }
                    "endBatchEdit" -> {
                        if (batchDepth > 0) batchDepth--
                        editDone()
                        batchDepth > 0
                    }
                    "commitText", "setComposingText" -> {
                        val value = args?.getOrNull(0)?.toString().orEmpty()
                        val (start, end) = if (composingStart >= 0) {
                            composingStart to composingEnd
                        } else {
                            selectionStart to selectionEnd
                        }
                        replace(start, end, value)
                        selectionStart = start + value.length
                        selectionEnd = selectionStart
                        if (method.name == "setComposingText" && value.isNotEmpty()) {
                            composingStart = start
                            composingEnd = start + value.length
                        } else {
                            composingStart = -1
                            composingEnd = -1
                        }
                        editDone()
                        true
                    }
                    "setComposingRegion" -> {
                        val a = args?.getOrNull(0) as Int
                        val b = args.getOrNull(1) as Int
                        composingStart = minOf(a, b).coerceIn(0, text.length)
                        composingEnd = maxOf(a, b).coerceIn(0, text.length)
                        editDone()
                        true
                    }
                    "finishComposingText" -> {
                        composingStart = -1
                        composingEnd = -1
                        editDone()
                        true
                    }
                    "deleteSurroundingText" -> {
                        val before = args?.getOrNull(0) as Int
                        val after = args.getOrNull(1) as Int
                        val start = (selectionStart - before).coerceAtLeast(0)
                        val end = (selectionEnd + after).coerceAtMost(text.length)
                        val selected = text.substring(selectionStart, selectionEnd)
                        text = text.substring(0, start) + selected + text.substring(end)
                        selectionStart = start
                        selectionEnd = start + selected.length
                        composingStart = -1
                        composingEnd = -1
                        editDone()
                        true
                    }
                    "setSelection" -> {
                        selectionStart = (args?.getOrNull(0) as Int).coerceIn(0, text.length)
                        selectionEnd = (args.getOrNull(1) as Int).coerceIn(0, text.length)
                        editDone()
                        true
                    }
                    "getTextBeforeCursor" -> {
                        val count = args?.getOrNull(0) as Int
                        text.substring((selectionStart - count).coerceAtLeast(0), selectionStart)
                    }
                    "getTextAfterCursor" -> {
                        val count = args?.getOrNull(0) as Int
                        text.substring(selectionEnd, (selectionEnd + count).coerceAtMost(text.length))
                    }
                    "getSelectedText" -> if (selectionStart == selectionEnd) {
                        null
                    } else {
                        text.substring(selectionStart, selectionEnd)
                    }
                    "getExtractedText" -> ExtractedText().apply {
                        this.text = this@FakeEditor.text
                        startOffset = 0
                        selectionStart = this@FakeEditor.selectionStart
                        selectionEnd = this@FakeEditor.selectionEnd
                    }
                    "sendKeyEvent", "performEditorAction", "performContextMenuAction",
                    "requestCursorUpdates", "clearMetaKeyStates" -> true
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
}
