package rocks.pkb.plektra.inputmethod

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import java.lang.reflect.Proxy

/** Automatic Shift in a long field that the app hands over only in part (ExtractedText.startOffset). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCapitalizeHelperPartialFieldTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
    }

    @Test
    fun partOfALongFieldStartingAtTheCursorIsNotTheStartOfTheField() {
        // The cursor mid-sentence, 5,000 characters in; the app's extract starts at the cursor
        val connection = connection(beforeCursor = "and so on and ", extractStart = 5_000, extract = "the rest of it")

        assertFalse(AutoCapitalizeHelper.shouldAutoCapitalizeAtCursor(context, connection, shouldDisableAutoCapitalize = false))
    }

    @Test
    fun partStartingAtTheCursorAfterASentenceStillCapitalizes() {
        val connection = connection(beforeCursor = "The end. ", extractStart = 5_000, extract = "next sentence")

        assertTrue(AutoCapitalizeHelper.shouldAutoCapitalizeAtCursor(context, connection, shouldDisableAutoCapitalize = false))
    }

    @Test
    fun wholeFieldWithTheCursorAtItsStartCapitalizes() {
        val connection = connection(beforeCursor = "", extractStart = 0, extract = "rest of the field")

        assertTrue(AutoCapitalizeHelper.shouldAutoCapitalizeAtCursor(context, connection, shouldDisableAutoCapitalize = false))
    }

    /** An editor whose extract is [extract] from [extractStart], with the cursor at the extract's start. */
    private fun connection(beforeCursor: String, extractStart: Int, extract: String): InputConnection =
        Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getExtractedText" -> ExtractedText().apply {
                    text = extract
                    startOffset = extractStart
                    selectionStart = 0
                    selectionEnd = 0
                }
                "getTextBeforeCursor" -> {
                    val count = (args?.getOrNull(0) as? Int) ?: beforeCursor.length
                    beforeCursor.takeLast(count)
                }
                "getTextAfterCursor" -> extract
                "getSelectedText" -> ""
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as InputConnection
}
