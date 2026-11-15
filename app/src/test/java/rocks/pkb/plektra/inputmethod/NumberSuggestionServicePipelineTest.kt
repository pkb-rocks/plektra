package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.suggestions.SuggestionController
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar

/**
 * Number suggestions through the real IME service: Q25 key events, the bundled English
 * dictionary, the editor reporting selection changes, and a WhatsApp-style message field.
 * Assertions read the three slots the suggestion bar actually renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberSuggestionServicePipelineTest {

    private lateinit var context: Context
    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var controller: SuggestionController
    private val editor = StringBuilder()
    private var eventTime = 1_000L

    private val inputConnection: InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        when (method.name) {
            "commitText" -> edit { editor.append(args!![0].toString()) }
            "deleteSurroundingText" -> edit {
                val count = args!![0] as Int
                editor.delete((editor.length - count).coerceAtLeast(0), editor.length)
            }
            "getTextBeforeCursor" -> editor.takeLast(args!![0] as Int).toString()
            "getTextAfterCursor", "getSelectedText" -> ""
            "getExtractedText" -> ExtractedText().apply {
                text = editor.toString()
                selectionStart = editor.length
                selectionEnd = editor.length
            }
            "sendKeyEvent", "beginBatchEdit", "endBatchEdit", "finishComposingText",
            "setComposingText", "setComposingRegion", "commitCorrection", "requestCursorUpdates" -> true
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    } as InputConnection

    private val pendingSelectionUpdates = mutableListOf<Pair<Int, Int>>()

    private fun edit(block: () -> Unit): Boolean {
        val before = editor.length
        block()
        pendingSelectionUpdates += before to editor.length
        return true
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setNumberSuggestionsEnabled(context, true)
        val imm = context.getSystemService(InputMethodManager::class.java)
        shadowOf(imm).setCurrentInputMethodSubtype(
            InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale("en_US")
                .setSubtypeMode("keyboard")
                .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
                .build()
        )
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        controller = field("suggestionController") as SuggestionController
        startField()
        waitUntil("English dictionary loaded") { controller.isKnownWordInActiveDictionaries("each") }
    }

    @After
    fun tearDown() {
        DeviceSpecific.clearTestOverrides()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    // ---------------------------------------------------------------------------------
    // Reported case
    // ---------------------------------------------------------------------------------

    @Test
    fun reportedWhatsAppSentenceShowsEachNumberFirst() {
        type("Testing number suggestions w00")
        assertEquals("bar at w00: ${bar()}", "100", bar().first())
        type(" e00")
        assertEquals("bar at e00: ${bar()}", "200", bar().first())
    }

    @Test
    fun reportedWhatsAppSentenceCommitsNumbersOnSpace() {
        SettingsManager.setAutoReplaceOnSpaceEnter(context, true)
        startField()
        type("testing number suggestions w00 e00 ")
        assertEquals("testing number suggestions 100 200 ", editor.toString().lowercase())
    }

    // ---------------------------------------------------------------------------------
    // Matrix through the real pipeline
    // ---------------------------------------------------------------------------------

    private data class Case(val typed: String, val number: String, val first: Boolean?)

    private val cases = listOf(
        Case("w00", "100", first = true),
        Case("e00", "200", first = true),
        Case("r00", "300", first = true),
        Case("s00", "400", first = true),
        Case("d00", "500", first = true),
        Case("f00", "600", first = true),
        Case("z00", "700", first = true),
        Case("x00", "800", first = true),
        Case("c00", "900", first = true),
        Case("ed0", "250", first = true),
        Case("ed0.000", "250.000", first = true),
        Case("ed0,000", "250,000", first = true),
        Case("w,d00", "1,500", first = true),
        Case("e,d00.00", "2,500.00", first = true),
        Case("dxz", "587", first = null),
        Case("zxc", "789", first = null),
        Case("red", "325", first = false),
        Case("see", "422", first = false),
        Case("wed", "125", first = false),
        Case("fed", "625", first = false)
    )

    @Test
    fun numberIsAlwaysVisibleAndFirstOnlyWithoutCloseWords() {
        val failures = mutableListOf<String>()
        listOf(1, 3).forEach { distance ->
            SettingsManager.setMaxAutoReplaceDistance(context, distance)
            cases.forEach { case ->
                startField()
                type("message ${case.typed}")
                val bar = bar()
                val label = "'${case.typed}' distance=$distance bar=$bar"
                val index = bar.indexOf(case.number)
                when {
                    index < 0 -> failures += "$label: ${case.number} not visible"
                    case.first == true && index != 0 -> failures += "$label: expected ${case.number} first"
                    case.first == false && index == 0 -> failures += "$label: real word must rank above ${case.number}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun spaceCommitsNumberWhenItIsFirstAndAutoCorrectIsOn() {
        val expected = listOf(
            "w00 " to "100 ",
            "e00 " to "200 ",
            "ed0.000 " to "250.000 ",
            "w,d00 " to "1,500 ",
            "red " to "red ",
            "see " to "see "
        )
        val failures = mutableListOf<String>()
        listOf(1, 3).forEach { distance ->
            SettingsManager.setMaxAutoReplaceDistance(context, distance)
            listOf(true, false).forEach { autoCorrect ->
                SettingsManager.setAutoReplaceOnSpaceEnter(context, autoCorrect)
                expected.forEach { (typed, converted) ->
                    startField()
                    type("message $typed")
                    val want = "message " + if (autoCorrect) converted else typed
                    if (editor.toString().lowercase() != want) {
                        failures += "'$typed' autocorrect=$autoCorrect distance=$distance: got '$editor', expected '$want'"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun numberStaysVisibleNextToTheAddWordButton() {
        val failures = mutableListOf<String>()
        listOf("w00", "e00", "ed0.000", "dxz", "zxc").forEach { typed ->
            startField()
            type("message $typed")
            val rendered = renderedSlots()
            val number = cases.first { it.typed == typed }.number
            if (number !in rendered) failures += "'$typed' rendered=$rendered add=${controller.pendingAddWord()}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun featureOffOrExcludedAppShowsNoNumber() {
        SettingsManager.setNumberSuggestionsEnabled(context, false)
        startField()
        type("message e00")
        assertTrue(bar().toString(), "200" !in bar())

        SettingsManager.setNumberSuggestionsEnabled(context, true)
        SettingsManager.setNumberSuggestionsExcludedApp(context, WHATSAPP, true)
        startField()
        type("message e00")
        assertTrue(bar().toString(), "200" !in bar())
    }

    // ---------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------

    /** A fresh EditorInfo per field, as the framework hands one to onStartInput and onStartInputView. */
    private fun startField() {
        editor.clear()
        pendingSelectionUpdates.clear()
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            packageName = WHATSAPP
        }
        setField("mInputConnection", inputConnection)
        setField("mStartedInputConnection", inputConnection)
        setField("mInputEditorInfo", info)
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        settle()
    }

    private fun type(text: String) {
        text.forEach { ch ->
            val keyCode = when {
                ch == ' ' -> KeyEvent.KEYCODE_SPACE
                ch == '.' -> KeyEvent.KEYCODE_PERIOD
                ch == ',' -> KeyEvent.KEYCODE_COMMA
                ch.isDigit() -> KeyEvent.KEYCODE_0 + (ch - '0')
                ch.isLetter() -> KeyEvent.KEYCODE_A + (ch.lowercaseChar() - 'a')
                else -> error("no key for '$ch'")
            }
            val meta = if (ch.isUpperCase()) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            service.onKeyDown(keyCode, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            eventTime += 40
            service.onKeyUp(keyCode, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, meta))
            eventTime += 40
            deliverSelectionUpdates()
        }
        settle()
    }

    private fun deliverSelectionUpdates() {
        while (pendingSelectionUpdates.isNotEmpty()) {
            val (old, new) = pendingSelectionUpdates.removeAt(0)
            service.onUpdateSelection(old, old, new, new, -1, -1)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun settle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            controller.awaitSuggestionJobForTests()
            deliverSelectionUpdates()
        }
    }

    /** Suggestions in rank order, as the service hands them to the bar. */
    @Suppress("UNCHECKED_CAST")
    private fun bar(): List<String> {
        val method = service.javaClass.getDeclaredMethod("visibleSuggestionStrings").apply { isAccessible = true }
        return method.invoke(service) as List<String>
    }

    /** What the full suggestion bar draws: left, center and right. */
    private fun renderedSlots(): List<String?> = FullSuggestionsBar.buildSlots(bar(), controller.pendingAddWord())

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        settle()
    }

    private fun field(name: String): Any? {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(service)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name")
    }

    private fun setField(name: String, value: Any?) {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name")
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
    }
}
