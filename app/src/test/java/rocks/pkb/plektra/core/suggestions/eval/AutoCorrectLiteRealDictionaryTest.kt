package rocks.pkb.plektra.core.suggestions.eval

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import rocks.pkb.plektra.core.suggestions.AndroidDictionaryRepository
import rocks.pkb.plektra.core.suggestions.AutoReplaceController
import rocks.pkb.plektra.core.suggestions.CurrentWordTracker
import rocks.pkb.plektra.core.suggestions.SuggestionEngine
import rocks.pkb.plektra.core.suggestions.SuggestionSettings
import rocks.pkb.plektra.core.suggestions.UserDictionaryStore
import rocks.pkb.plektra.inputmethod.AutoCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Typed through `handleBoundary` against the dictionary that ships, with the exact-rule
 * provider the keyboard uses, in English.
 *
 * It pins what the lite change must not disturb: upstream's capitalisation and its tech-word
 * vocabulary. A dictionary or a shape rule that overruled `usb` or lost `i'm` -> `I'm` would
 * show up here and nowhere in the small-vocabulary harness. It also prints a probe table
 * (`PROBE d1|d2 typed -> result`) for the typos the change is meant to help.
 *
 * Opt-in, like the evaluation (13 MB dictionary decode):
 *
 *     ./gradlew :app:testDebugUnitTest -Pplektra.eval.realDictionary=true \
 *         --tests '*AutoCorrectLiteRealDictionaryTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCorrectLiteRealDictionaryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val distance1 = SuggestionSettings(autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = 1)
    private val distance2 = SuggestionSettings(
        autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = 2, useKeyboardProximity = true
    )

    @Before
    fun setUp() {
        assumeTrue(
            "set -Pplektra.eval.realDictionary=true to run the real-dictionary tests",
            System.getProperty("plektra.eval.realDictionary") == "true"
        )
        AutoCorrector.loadCorrections(context.assets, context)
    }

    private val repository: AndroidDictionaryRepository by lazy {
        AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        ).also {
            // loadIfNeeded refuses to run on the main thread, which Robolectric's runBlocking uses.
            runBlocking { withContext(Dispatchers.IO) { it.loadIfNeeded() } }
            assertTrue("the English dictionary did not load", it.isReady)
        }
    }

    @Test
    fun upstreamCapitalisationIsKept() {
        for ((typed, expected) in listOf(
            "i'm" to "I'm", "i'll" to "I'll", "i've" to "I've", "i'd" to "I'd",
            "london" to "London", "monday" to "Monday", "paris" to "Paris"
        )) {
            for ((label, settings) in listOf("d1" to distance1, "d2" to distance2)) {
                assertEquals("$typed at $label", expected, typeThenSpace(typed, settings))
            }
        }
    }

    /**
     * Tech words are never overruled by a different word (`usb` -> `use`, `cpu` -> `cup`,
     * `wifi` -> `wife` is what the other branch did). Upstream does restore their case
     * (`USB`, `CPU`, `WiFi`) and that is kept, hence the case-insensitive comparison.
     */
    @Test
    fun techWordsAreLeftAlone() {
        for (typed in listOf("usb", "wifi", "cpu")) {
            for ((label, settings) in listOf("d1" to distance1, "d2" to distance2)) {
                val result = typeThenSpace(typed, settings)
                assertTrue("$typed -> $result at $label", result.equals(typed, ignoreCase = true))
            }
        }
    }

    @Test
    fun probeTable() {
        val words = listOf(
            // section 3: capitalisation and brands
            "london", "monday", "john", "paris", "january", "iphone", "youtube", "javascript", "ok",
            "nasa", "fbi", "gps", "i'm", "i'll", "i've", "i'd", "im",
            // section 3: tech words B overruled
            "usb", "url", "html", "cpu", "gpu", "tcp", "ssl", "wifi", "wi-fi", "wordpress", "inbox",
            "signup", "selfie", "apps", "idk", "brb", "men's", "centre", "favourite", "organisation",
            // section 3: B's blocked fixes
            "teh", "wich", "Teh", "waht", "wnat", "peice",
            // the requested extras
            "goverment", "probaly", "enviroment", "occured", "comming",
            // other typos from the evaluation
            "neccessary", "immediatly", "writting", "calender", "tomorow", "wierd", "thnks",
            "definetly", "definately", "realy", "sucess", "ios", "salve"
        )
        val sb = StringBuilder()
        for ((label, settings) in listOf("d1" to distance1, "d2" to distance2)) {
            for (w in words) sb.appendLine("PROBE $label $w -> ${typeThenSpace(w, settings)}")
        }
        println(sb)
    }

    /** What the text reads after typing [word] after "so " and pressing space (as the evaluation's probe did). */
    private fun typeThenSpace(word: String, settings: SuggestionSettings): String {
        val controller = AutoReplaceController(
            repository = repository,
            suggestionEngine = SuggestionEngine(repository),
            settingsProvider = { settings },
            languageProvider = { "en" },
            knownWordProvider = { repository.isKnownWord(it) },
            exactReplacementProvider = { w, boundary ->
                AutoCorrector.processText(
                    textBeforeCursor = w + (boundary ?: ' '),
                    locale = "en",
                    context = context,
                    isKnownWord = { repository.isKnownWord(it) }
                )?.takeIf { (original, replacement) -> original == w && replacement != w }?.second
            }
        )
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        tracker.setWord(word)
        val connection = FakeInputConnection(context, "so $word")
        controller.handleBoundary(
            keyCode = KeyEvent.KEYCODE_SPACE,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
            tracker = tracker,
            inputConnection = connection
        )
        return connection.text.trim().removePrefix("so").trim()
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
            buffer.append(text); return true
        }
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
    }
}
