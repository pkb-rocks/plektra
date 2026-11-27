package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AutoCorrector

/**
 * A word whose replacement was undone with Backspace stays as typed only until typing moves on.
 * The letters of most keys never reached the code that forgave it, so it was never replaced
 * again until the keyboard restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplacementAfterUndoTest {

    private lateinit var context: Context
    private val controllers = mutableListOf<SuggestionController>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("en"))
        AutoCorrector.loadCorrections(context.assets, context)
    }

    @After
    fun tearDown() {
        controllers.forEach(SuggestionController::destroy)
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun aFinishedWordEndsTheRejectionEvenWhenItsLettersWereNotReported() {
        val controller = newController()
        val input = FakeInputConnection(context)

        undoAReplacementOfIm(controller, input)
        // Typed on keys with a long-press layer: the editor has the letters, the controller was
        // never told
        input.commitText("we", 1)
        controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        input.commitText("im", 1)

        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        assertEquals("im we I'm ", input.text)
    }

    @Test
    fun theUndoneWordStaysAsTypedWhileItIsStillTheWordAtTheCursor() {
        val controller = newController()
        val input = FakeInputConnection(context)

        undoAReplacementOfIm(controller, input)
        // The space is deleted and typed again, with nothing else in between
        input.deleteSurroundingText(1, 0)

        assertFalse(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        assertEquals("im ", input.text)
    }

    @Test
    fun aLetterTypedOnALongPressKeyForgivesTheWordAtOnce() {
        val controller = newController()
        val input = FakeInputConnection(context)

        undoAReplacementOfIm(controller, input)
        // The same word again straight away, its letters heard only as "typing continued"
        "im".forEach { letter ->
            input.commitText(letter.toString(), 1)
            controller.onTypingContinued(letter.toString())
        }

        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        assertEquals("im I'm ", input.text)
    }

    @Test
    fun backspaceAfterMoreTypingIsAnOrdinaryDelete() {
        val controller = newController()
        val input = FakeInputConnection(context)

        input.commitText("im", 1)
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        input.commitText("o", 1)
        controller.onTypingContinued("o")
        // The letter is deleted again, by the editor: the cursor is back after "I'm "
        input.deleteSurroundingText(1, 0)

        assertFalse(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
        assertEquals("I'm ", input.text)
    }

    /** "im" replaced by "I'm" and put back with Backspace, the space after it kept. */
    private fun undoAReplacementOfIm(controller: SuggestionController, input: FakeInputConnection) {
        input.commitText("im", 1)
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        assertEquals("I'm ", input.text)
        assertTrue(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
        assertEquals("im ", input.text)
    }

    private fun newController(): SuggestionController {
        val repository = FakeDictionaryRepository()
        return SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(textReplacementsEnabled = true, autoReplaceOnSpaceEnter = true)
            },
            onSuggestionsUpdated = {},
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> repository }
        ).also { controllers += it }
    }

    private class FakeInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder()

        val text: String
            get() = buffer.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val start = (buffer.length - beforeLength).coerceAtLeast(0)
            buffer.delete(start, buffer.length)
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true
    }
}
