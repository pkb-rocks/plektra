package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Smart Shift after an opening quote or bracket at the start of a sentence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCapitalizeHelperOpeningPunctuationTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
    }

    @Test
    fun openingQuoteAfterSentenceEnd_armsShift() {
        assertTrue(armsShift("He said. \""))
        assertTrue(armsShift("Really? “"))
        assertTrue(armsShift("Wow! ("))
        assertTrue(armsShift("Er sagte. „"))
        assertTrue(armsShift("Il a dit. «"))
        assertTrue(armsShift("Dijo. ¿"))
        assertTrue(armsShift("He said. \"("))
    }

    @Test
    fun openingQuoteAtStartOfFieldOrLine_armsShift() {
        assertTrue(armsShift("\""))
        assertTrue(armsShift("("))
        assertTrue(armsShift("First line\n'"))
    }

    @Test
    fun quoteOutsideASentenceStart_doesNotArmShift() {
        assertFalse(armsShift("He said \""))
        assertFalse(armsShift("The end.\""))
        assertFalse(armsShift("The end.)"))
        assertFalse(armsShift("He said. \"hello"))
        assertFalse(armsShift("He said. »"))
    }

    @Test
    fun capSentencesField_openingQuoteAfterSentenceEnd_armsShift() {
        val state = InputContextState.fromEditorInfo(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                packageName = "rocks.pkb.plektra.test"
            }
        )

        assertTrue(armsShift("He said. \"", state))
        assertFalse(armsShift("He said \"", state))
    }

    @Test
    fun isAtStartOfSentence_skipsOpeningPunctuation() {
        assertTrue(AutoCapitalizeHelper.isAtStartOfSentence(connection("He said. \"")))
        assertTrue(AutoCapitalizeHelper.isAtStartOfSentence(connection("(")))
        assertFalse(AutoCapitalizeHelper.isAtStartOfSentence(connection("He said \"")))
    }

    private fun armsShift(textBeforeCursor: String, state: InputContextState? = null): Boolean {
        var enabled = false
        AutoCapitalizeHelper.maybeEnableSmartShift(
            context = context,
            inputConnection = connection(textBeforeCursor),
            shouldDisableAutoCapitalize = false,
            enableShift = {
                enabled = true
                true
            },
            disableShift = { true },
            onUpdateStatusBar = {},
            inputContextState = state
        )
        return enabled
    }

    private fun connection(textBeforeCursor: String): InputConnection =
        Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getExtractedText" -> ExtractedText().apply {
                    text = textBeforeCursor
                    selectionStart = textBeforeCursor.length
                    selectionEnd = textBeforeCursor.length
                }
                "getTextBeforeCursor" -> {
                    val count = (args?.getOrNull(0) as? Int) ?: textBeforeCursor.length
                    textBeforeCursor.takeLast(count)
                }
                "getTextAfterCursor", "getSelectedText" -> ""
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as InputConnection
}
