package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The two small autocorrect changes on top of upstream's rule:
 *
 *  a. English may fix a dropped or added letter (TypoShapeProfile), bounded by the user's
 *     Maximum correction distance; every other language keeps the same-length rule.
 *  b. A typed word that is not known, whose top suggestion is a distance-0 prefix completion
 *     (`wich` -> `wichita`), is corrected with the best real edit instead of being refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCorrectLiteTest {

    private fun candidate(word: String, distance: Int) =
        SuggestionResult(candidate = word, distance = distance, score = 100.0, source = SuggestionSource.MAIN)

    private fun safe(input: String, candidate: String, distance: Int, language: String, maxDistance: Int = 2) =
        AutoReplaceController.isSafeAutoReplaceCandidate(
            input = input,
            lookupWord = input,
            candidate = candidate(candidate, distance),
            settings = SuggestionSettings(maxAutoReplaceDistance = maxDistance),
            isOrthographicVariant = false,
            languageCode = language
        )

    // ---- a. TypoShapeProfile ---------------------------------------------------------------

    @Test
    fun profile_onlyEnglishIsOpenedUp() {
        assertEquals(2, TypoShapeProfile.forLanguage("en").maxLengthDelta)
        assertEquals(2, TypoShapeProfile.forLanguage("EN").maxLengthDelta)
        assertEquals(2, TypoShapeProfile.forLanguage("en-US").maxLengthDelta)
        for (other in listOf("it", "de", "fr", "es", "tr", "hu", "fi", "pt", "", null)) {
            assertEquals("language $other", 0, TypoShapeProfile.forLanguage(other).maxLengthDelta)
        }
    }

    @Test
    fun affixChange_isPrefixOrSuffixGrowthOnly() {
        assertTrue(TypoShapeProfile.isPureAffixChange("work", "works"))
        assertTrue(TypoShapeProfile.isPureAffixChange("behavio", "behavior"))
        assertTrue(TypoShapeProfile.isPureAffixChange("unhappy", "happy"))
        assertTrue(TypoShapeProfile.isPureAffixChange("teh", "tehran"))
        assertFalse(TypoShapeProfile.isPureAffixChange("goverment", "government"))
        assertFalse(TypoShapeProfile.isPureAffixChange("comming", "coming"))
        assertFalse(TypoShapeProfile.isPureAffixChange("same", "same"))
    }

    @Test
    fun english_droppedLetterIsAccepted() {
        assertTrue(safe("goverment", "government", 1, "en"))
        assertTrue(safe("probaly", "probably", 1, "en"))
        assertTrue(safe("enviroment", "environment", 1, "en"))
        assertTrue(safe("wich", "which", 1, "en"))
    }

    @Test
    fun english_addedLetterIsAccepted() {
        assertTrue(safe("comming", "coming", 1, "en"))
        assertTrue(safe("writting", "writing", 1, "en"))
        assertTrue(safe("occured", "occurred", 1, "en")) // doubled-letter insertion, upstream rule
    }

    @Test
    fun english_boundByTheDistanceSetting() {
        // distance 2 candidate is refused at the default setting of 1, accepted at 2
        assertFalse(safe("definetly", "definitely", 2, "en", maxDistance = 1))
        assertFalse(safe("govrnmnt", "government", 2, "en", maxDistance = 1))
        assertTrue(safe("govrnmnt", "government", 2, "en", maxDistance = 2))
    }

    @Test
    fun english_lengthChangeBeyondTwoIsRefused() {
        assertFalse(safe("govnt", "government", 5, "en", maxDistance = 5))
    }

    @Test
    fun english_shortCandidatesAreRefused_ios() {
        assertFalse(safe("ios", "is", 1, "en"))
        assertFalse(safe("cpu", "cu", 1, "en"))
        assertFalse(safe("ti", "tie", 1, "en"))
        assertFalse(safe("cp", "cup", 1, "en"))
    }

    @Test
    fun english_fourLetterCandidateIsTheThreshold() {
        assertTrue(safe("frm", "from", 1, "en"))
        assertTrue(safe("hte", "hate", 1, "en"))
        assertTrue(safe("wich", "which", 1, "en"))
    }

    @Test
    fun english_pureAffixChangeIsStillRefused() {
        assertFalse(safe("work", "works", 1, "en"))
        assertFalse(safe("behavio", "behavior", 1, "en"))
        assertFalse(safe("teh", "tehran", 0, "en"))
    }

    @Test
    fun english_firstLetterChangeIsStillRefused() {
        // delta -1 and not an affix change, so only the first-letter guard stands in the way
        assertFalse(safe("xtime", "tine", 2, "en"))
    }

    @Test
    fun otherLanguages_keepTheSameLengthRule() {
        for (language in listOf("", "it", "de", "fr", "es", "tr", "hu", "fi")) {
            assertFalse("language '$language'", safe("goverment", "government", 1, language))
            assertFalse("language '$language'", safe("comming", "coming", 1, language))
        }
    }

    @Test
    fun sameLengthEdit_isUnaffectedByTheProfile() {
        for (language in listOf("", "en", "it")) {
            assertTrue("language '$language'", safe("hause", "house", 1, language))
        }
    }

    @Test
    fun defaultAutoReplaceDistanceIsOne() {
        assertEquals(1, SuggestionSettings().maxAutoReplaceDistance)
    }

    // ---- b. prefix-completion fallback -----------------------------------------------------

    @Test
    fun pick_returnsTopWhenItIsAnEdit() {
        val calls = mutableListOf<Int>()
        val result = AutoReplaceController.pickReplacementCandidate(isKnownWord = false, typedLength = 3) { limit ->
            calls += limit
            listOf(candidate("the", 1), candidate("tea", 1))
        }
        assertEquals("the", result?.candidate)
        assertEquals("only one fetch, as before", listOf(1), calls)
    }

    @Test
    fun pick_takesTheBestEditWhenTopIsAPrefixCompletionOfAnUnknownWord() {
        val calls = mutableListOf<Int>()
        val result = AutoReplaceController.pickReplacementCandidate(isKnownWord = false, typedLength = 3) { limit ->
            calls += limit
            listOf(candidate("tehran", 0), candidate("the", 1), candidate("then", 1)).take(limit)
        }
        assertEquals("the", result?.candidate)
        assertEquals(2, calls.size)
        assertEquals(1, calls[0])
        assertTrue(calls[1] > 1)
    }

    @Test
    fun pick_leavesAKnownWordsPrefixCompletionAlone() {
        val calls = mutableListOf<Int>()
        val result = AutoReplaceController.pickReplacementCandidate(isKnownWord = true, typedLength = 6) { limit ->
            calls += limit
            listOf(candidate("workshop", 0), candidate("worm", 1)).take(limit)
        }
        assertEquals("workshop", result?.candidate)
        assertEquals(listOf(1), calls)
    }

    @Test
    fun pick_leavesShortCompletionsAlone_untieAndLoathe() {
        // 1 and 2 letters longer: the typed word or an inflection of it, not a typo.
        for ((typed, completion, edit) in listOf(
            Triple("untie", "untied", "unite"),
            Triple("loathe", "loathed", "lathe"),
            Triple("untie", "unties", "unite"),
            Triple("imbue", "imbuing", "immune")
        )) {
            val calls = mutableListOf<Int>()
            val result = AutoReplaceController.pickReplacementCandidate(false, typed.length) { limit ->
                calls += limit
                listOf(candidate(completion, 0), candidate(edit, 1)).take(limit)
            }
            assertEquals(typed, completion, result?.candidate)
            assertEquals("no second fetch for $typed", listOf(1), calls)
        }
    }

    @Test
    fun pick_completionThreeLettersLongerIsTheThreshold() {
        val edit = candidate("the", 1)
        for ((extra, fires) in listOf(1 to false, 2 to false, 3 to true, 4 to true)) {
            val completion = "teh" + "x".repeat(extra)
            val result = AutoReplaceController.pickReplacementCandidate(false, 3) { limit ->
                listOf(candidate(completion, 0), edit).take(limit)
            }
            assertEquals("extra=$extra", if (fires) "the" else completion, result?.candidate)
        }
    }

    @Test
    fun pick_fallsBackToTheCompletionWhenNoEditExists() {
        val result = AutoReplaceController.pickReplacementCandidate(isKnownWord = false, typedLength = 3) { limit ->
            listOf(candidate("tehran", 0), candidate("tehrans", 0)).take(limit)
        }
        assertEquals("tehran", result?.candidate)
    }

    @Test
    fun pick_ignoresNonCurrentWordCandidates() {
        val result = AutoReplaceController.pickReplacementCandidate(isKnownWord = false, typedLength = 3) { limit ->
            listOf(
                candidate("tehran", 0),
                candidate("then", 1).copy(kind = SuggestionKind.NEXT_WORD),
                candidate("the", 1)
            ).take(limit)
        }
        assertEquals("the", result?.candidate)
    }

    @Test
    fun pick_emptyIsNull() {
        assertNull(AutoReplaceController.pickReplacementCandidate(isKnownWord = false, typedLength = 3) { emptyList() })
    }

    // ---- through handleBoundary ------------------------------------------------------------

    private fun boundary(
        repository: FakeDictionaryRepository,
        typed: String,
        language: String?,
        maxDistance: Int = 1
    ): Pair<AutoReplaceController.ReplaceResult, String> {
        val context = RuntimeEnvironment.getApplication()
        val controller = if (language == null) {
            AutoReplaceController(
                repository = repository,
                suggestionEngine = SuggestionEngine(repository),
                settingsProvider = {
                    SuggestionSettings(autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = maxDistance)
                }
            )
        } else {
            AutoReplaceController(
                repository = repository,
                suggestionEngine = SuggestionEngine(repository),
                settingsProvider = {
                    SuggestionSettings(autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = maxDistance)
                },
                languageProvider = { language }
            )
        }
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        tracker.setWord(typed)
        val connection = FakeInputConnection(context, typed)
        val result = controller.handleBoundary(
            keyCode = KeyEvent.KEYCODE_SPACE,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
            tracker = tracker,
            inputConnection = connection
        )
        return result to connection.text
    }

    private fun englishDictionary() = FakeDictionaryRepository().apply {
        isReady = true
        addTestEntry("government", 255)
        addTestEntry("coming", 255)
        addTestEntry("which", 255)
        addTestEntry("wichita", 255)
        addTestEntry("house", 255)
        addTestEntry("hausenberg", 255)
    }

    @Test
    fun handleBoundary_englishFixesADroppedLetterAtDistanceOne() {
        val (result, text) = boundary(englishDictionary(), "goverment", language = "en")
        assertTrue(result.replaced)
        assertEquals("government ", text)
    }

    @Test
    fun handleBoundary_englishFixesAnAddedLetterAtDistanceOne() {
        val (result, text) = boundary(englishDictionary(), "comming", language = "en")
        assertTrue(result.replaced)
        assertEquals("coming ", text)
    }

    @Test
    fun handleBoundary_otherLanguagesAndTheDefaultLeaveItAlone() {
        for (language in listOf(null, "", "it", "de")) {
            val (result, text) = boundary(englishDictionary(), "goverment", language = language)
            assertFalse("language $language", result.replaced)
            assertEquals("language $language", "goverment ", text)
        }
    }

    @Test
    fun handleBoundary_prefixCompletionDoesNotBlockTheRealFix_english() {
        val repository = englishDictionary()
        val top = SuggestionEngine(repository).suggest("wich", limit = 1).first()
        assertEquals("precondition: the completion outranks the fix", "wichita", top.candidate)
        assertEquals(0, top.distance)

        val (result, text) = boundary(repository, "wich", language = "en")
        assertTrue(result.replaced)
        assertEquals("which ", text)
    }

    @Test
    fun handleBoundary_prefixCompletionDoesNotBlockTheRealFix_sameLengthEdit() {
        // Needs no language profile: a same-length substitution blocked by a completion.
        val repository = englishDictionary()
        val top = SuggestionEngine(repository).suggest("hause", limit = 1).first()
        assertEquals("precondition: the completion outranks the fix", "hausenberg", top.candidate)
        assertEquals(0, top.distance)

        for (language in listOf(null, "en", "it")) {
            val (result, text) = boundary(repository, "hause", language = language)
            assertTrue("language $language", result.replaced)
            assertEquals("language $language", "house ", text)
        }
    }

    @Test
    fun handleBoundary_untieLoatheAndIosStayUnchanged() {
        val repository = FakeDictionaryRepository().apply {
            isReady = true
            for (w in listOf("untied", "unite", "until", "loathed", "lathe", "is")) addTestEntry(w, 255)
        }
        for (typed in listOf("untie", "loathe", "ios")) {
            for (distance in listOf(1, 2)) {
                val (result, text) = boundary(repository, typed, language = "en", maxDistance = distance)
                assertFalse("$typed at d$distance", result.replaced)
                assertEquals("$typed at d$distance", "$typed ", text)
            }
        }
        // the completion is what stood in the way of the old unguarded fallback
        assertEquals("untied", SuggestionEngine(repository).suggest("untie", limit = 1).first().candidate)
    }

    @Test
    fun handleBoundary_theTypoFixesStillLand() {
        val repository = FakeDictionaryRepository().apply {
            isReady = true
            for (w in listOf(
                "government", "probably", "environment", "coming", "necessary", "writing",
                "which", "wichita", "immediately"
            )) addTestEntry(w, 255)
        }
        for ((typed, expected) in listOf(
            "goverment" to "government", "probaly" to "probably", "enviroment" to "environment",
            "comming" to "coming", "neccessary" to "necessary", "writting" to "writing",
            "immediatly" to "immediately", "wich" to "which"
        )) {
            for (distance in listOf(1, 2)) {
                val (result, text) = boundary(repository, typed, language = "en", maxDistance = distance)
                assertTrue("$typed at d$distance", result.replaced)
                assertEquals("$typed at d$distance", "$expected ", text)
            }
        }
    }

    @Test
    fun handleBoundary_aKnownWordIsNeverReplaced() {
        val repository = englishDictionary().apply {
            addTestEntry("wich", 40)
        }
        val (result, text) = boundary(repository, "wich", language = "en")
        assertFalse(result.replaced)
        assertEquals("wich ", text)
    }

    private class FakeInputConnection(
        context: Context,
        initialText: String
    ) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)

        val text: String
            get() = buffer.toString()

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
