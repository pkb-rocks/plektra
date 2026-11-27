package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState

/**
 * A replacement undone with Backspace, typed on the real keys. Letters on keys with an Alt
 * character, which is nearly every letter, are typed through the long-press path; a word whose
 * replacement was undone there was never replaced again until the keyboard restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplacementAfterUndoKeysTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<PhysicalKeyboardInputMethodService>
    private lateinit var service: PhysicalKeyboardInputMethodService
    private var text = ""
    private var clock = 10_000L

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setAutoCorrectEnabled(context, true)
        // Undo with Backspace comes with auto-replace
        SettingsManager.setAutoReplaceOnSpaceEnter(context, true)
        // The test keyboard has no language of its own to go by
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("en"))
        controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller.get()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        val connection = connection()
        setField("mInputConnection", connection)
        setField("mStartedInputConnection", connection)
        setField("mInputEditorInfo", editorInfo)
        setField("isInputViewActive", true)
        setField("inputContextState", InputContextState.fromEditorInfo(editorInfo))
        // As on the Titan keyboards: every letter used here has an Alt character
        val alt = getField<AlternateCharacterManager>("alternateCharacterManager")
        "ime".forEachIndexed { index, letter -> alt.addAltKeyMapping(keyCodeOf(letter), index.toString()) }
        text = "so "
    }

    @After
    fun tearDown() {
        controller.destroy()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun anUndoneReplacementAppliesAgainOnceTypingGoesOn() {
        type("im ")
        assertEquals("so I'm ", text)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("so im", text)

        // Space leaves the word as it was put back; after another word it is replaced again
        type(" e im ")
        assertEquals("so im e I'm ", text)
    }

    @Test
    fun backspaceAfterMoreTypingDoesNotUndoAnEarlierReplacement() {
        type("im e")
        assertEquals("so I'm e", text)
        // The letter is deleted by the app, as an ordinary Backspace is
        text = text.dropLast(1)
        press(KeyEvent.KEYCODE_DEL)

        // An ordinary Backspace (whoever deletes the space): the replacement stays
        assertEquals("so I'm", text.trimEnd())
    }

    private fun type(keys: String) = keys.forEach { key -> press(keyCodeOf(key)) }

    private fun keyCodeOf(char: Char): Int =
        if (char == ' ') KeyEvent.KEYCODE_SPACE else KeyEvent.KEYCODE_A + (char - 'a')

    private fun press(keyCode: Int) {
        val start = clock
        clock += 200L
        service.onKeyDown(keyCode, KeyEvent(start, start, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        service.onKeyUp(keyCode, KeyEvent(start, start + 30L, KeyEvent.ACTION_UP, keyCode, 0, 0))
    }

    private fun connection(): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        when (method.name) {
            "commitText" -> {
                text += args?.getOrNull(0)?.toString().orEmpty()
                true
            }
            "deleteSurroundingText" -> {
                val before = (args?.getOrNull(0) as? Int) ?: 0
                text = text.dropLast(before.coerceAtMost(text.length))
                true
            }
            "getTextBeforeCursor" -> text.takeLast((args?.getOrNull(0) as? Int) ?: text.length)
            "getTextAfterCursor" -> ""
            "getExtractedText" -> ExtractedText().apply {
                this.text = this@ReplacementAfterUndoKeysTest.text
                selectionStart = this@ReplacementAfterUndoKeysTest.text.length
                selectionEnd = selectionStart
            }
            "beginBatchEdit", "endBatchEdit", "finishComposingText", "sendKeyEvent" -> true
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    } as InputConnection

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(name: String): T = field(name).get(service) as T

    private fun setField(name: String, value: Any?) = field(name).set(service, value)

    private fun field(name: String): java.lang.reflect.Field {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            runCatching { return type.getDeclaredField(name).apply { isAccessible = true } }
            type = type.superclass
        }
        error("No field $name")
    }
}
