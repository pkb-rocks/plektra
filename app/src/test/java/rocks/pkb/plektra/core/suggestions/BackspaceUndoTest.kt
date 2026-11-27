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

/** Backspace undoes a text replacement with auto-replace and the suggestion engine off, keeping what was typed after it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackspaceUndoTest {
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
    fun aTextReplacementIsUndoneWithAutoReplaceAndTheEngineOff() {
        for (engineOn in listOf(false, true)) {
            val controller = newController(engineOn)
            val input = FakeInputConnection(context)
            val state = "suggestion engine on=$engineOn"

            input.commitText("im", 1)
            assertTrue(state, controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
            assertEquals(state, "I'm ", input.text)
            assertTrue(state, controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
            assertEquals(state, "im ", input.text)
            // A second Backspace is an ordinary delete
            assertFalse(state, controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
        }
    }

    @Test
    fun punctuationTypedAfterTheWordStays() {
        val controller = newController(engineOn = false)
        val input = FakeInputConnection(context)

        input.commitText("im", 1)
        val comma = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0)
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_COMMA, comma, input).replaced)
        assertEquals("I'm,", input.text)
        assertTrue(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
        assertEquals("im,", input.text)
    }

    @Test
    fun replacementEndingWithPunctuationUndoesCleanlyWithTrailingSpaceOrComma() {
        val repository = FakeDictionaryRepository()
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        val autoReplace = AutoReplaceController(
            repository = repository,
            suggestionEngine = SuggestionEngine(repository),
            settingsProvider = { SuggestionSettings(textReplacementsEnabled = true, suggestionsEnabled = false) },
            exactReplacementProvider = { word, _ -> if (word == "etc") "etc." else null }
        )

        val spaceInput = FakeInputConnection(context)
        spaceInput.commitText("so etc", 1)
        tracker.setWord("etc")
        assertTrue(autoReplace.handleBoundary(KeyEvent.KEYCODE_SPACE, null, tracker, spaceInput, ' ').replaced)
        assertEquals("so etc. ", spaceInput.text)
        assertTrue(autoReplace.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, spaceInput))
        assertEquals("so etc ", spaceInput.text)

        autoReplace.clearRejectedWords()
        val commaInput = FakeInputConnection(context)
        commaInput.commitText("so etc", 1)
        tracker.setWord("etc")
        val comma = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0)
        assertTrue(autoReplace.handleBoundary(KeyEvent.KEYCODE_COMMA, comma, tracker, commaInput, ',').replaced)
        assertEquals("so etc.,", commaInput.text)
        assertTrue(autoReplace.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, commaInput))
        assertEquals("so etc,", commaInput.text)
    }

    @Test
    fun typingOnEndsTheUndoWithTheEngineOff() {
        val controller = newController(engineOn = false)
        val input = FakeInputConnection(context)

        input.commitText("im", 1)
        assertTrue(controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input).replaced)
        input.commitText("o", 1)
        controller.onCharacterCommitted("o", input)
        input.deleteSurroundingText(1, 0)

        assertFalse(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, input))
        assertEquals("I'm ", input.text)
    }

    private fun newController(engineOn: Boolean): SuggestionController {
        val repository = FakeDictionaryRepository()
        return SuggestionController(
            context = context,
            assets = context.assets,
            // Auto-replace off, as by default; text replacements on
            settingsProvider = { SuggestionSettings(textReplacementsEnabled = true, suggestionsEnabled = engineOn) },
            isEnabled = { engineOn },
            onSuggestionsUpdated = {},
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> repository }
        ).also { controllers += it }
    }

    private class FakeInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder()
        val text: String get() = buffer.toString()
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            buffer.delete((buffer.length - beforeLength).coerceAtLeast(0), buffer.length)
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
