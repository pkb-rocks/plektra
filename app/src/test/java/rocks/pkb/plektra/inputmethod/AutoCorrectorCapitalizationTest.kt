package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.core.suggestions.AutoReplaceController
import rocks.pkb.plektra.core.suggestions.CurrentWordTracker
import rocks.pkb.plektra.core.suggestions.FakeDictionaryRepository
import rocks.pkb.plektra.core.suggestions.SuggestionEngine
import rocks.pkb.plektra.core.suggestions.SuggestionSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCorrectorCapitalizationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AutoCorrector.loadCorrections(context.assets, context)
    }

    private fun replace(text: String, known: Set<String> = setOf("i", "I")): Pair<String, String>? =
        AutoCorrector.processText(text, locale = "en", context = context, isKnownWord = { it in known })

    @Test
    fun lowercaseI_becomesCapitalI() {
        assertEquals("i" to "I", replace("i "))
        assertEquals("i" to "I", replace("so i "))
    }

    @Test
    fun capitalisedContraction_keepsItsCapital() {
        assertEquals("Dont" to "Don't", replace("Dont "))
        assertEquals("Dont" to "Don't", replace("Hello. Dont "))
        assertEquals("dont" to "don't", replace("dont "))
    }

    private fun typeThenSpace(before: String, word: String): Pair<String, AutoReplaceController.ReplaceResult> {
        val repository = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("i", 206)
            addTestEntry("don't", 150)
        }
        val known = setOf("i", "don't")
        val controller = AutoReplaceController(
            repository = repository,
            suggestionEngine = SuggestionEngine(repository),
            settingsProvider = { SuggestionSettings(autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = 1) },
            knownWordProvider = { it.lowercase() in known },
            exactReplacementProvider = { w, boundary ->
                AutoCorrector.processText(
                    textBeforeCursor = w + (boundary ?: ' '),
                    locale = "en",
                    context = context,
                    isKnownWord = { it.lowercase() in known }
                )?.takeIf { (original, replacement) -> original == w && replacement != w }?.second
            }
        )
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        tracker.setWord(word)
        val connection = FakeInputConnection(context, before + word)
        val result = controller.handleBoundary(
            keyCode = KeyEvent.KEYCODE_SPACE,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
            tracker = tracker,
            inputConnection = connection
        )
        return connection.text to result
    }

    @Test
    fun boundaryPath_capitalisesI() {
        assertEquals("so I " to true, typeThenSpace("so ", "i").let { it.first to it.second.replaced })
    }

    @Test
    fun boundaryPath_restoresApostropheAndKeepsCapital() {
        assertEquals("Don't " to true, typeThenSpace("", "Dont").let { it.first to it.second.replaced })
        assertEquals("Hi. Don't " to true, typeThenSpace("Hi. ", "Dont").let { it.first to it.second.replaced })
        assertEquals("don't " to true, typeThenSpace("", "dont").let { it.first to it.second.replaced })
    }

    private class FakeInputConnection(context: Context, initialText: String) :
        BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)
        val text: String get() = buffer.toString()
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)
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
