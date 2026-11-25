package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionPersonalizationAndAddWordTest {

    private lateinit var context: Context
    private lateinit var store: UserNGramStore
    private lateinit var fakeRepository: TrackingDictionaryRepository
    private val snapshots = mutableListOf<List<SuggestionResult>>()
    private val controllers = mutableListOf<SuggestionController>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        store = UserNGramStore(context).apply { clearAll() }
        fakeRepository = TrackingDictionaryRepository().apply {
            isReady = true
            addTestEntry("hello", 220, SuggestionSource.MAIN)
            addTestEntry("help", 200, SuggestionSource.MAIN)
            addTestEntry("held", 180, SuggestionSource.MAIN)
            addTestEntry("ich", 210, SuggestionSource.MAIN)
            addTestEntry("dann", 190, SuggestionSource.MAIN)
        }
        snapshots.clear()
        controllers.clear()
    }

    @After
    fun tearDown() {
        controllers.forEach { controller ->
            controller.flushNextWordLearningForTests()
            controller.destroy()
        }
        store.clearAll()
        store.close()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun settingsDefaultsPreserveExistingBehavior() {
        assertTrue(SettingsManager.getSuggestionsPersonalizationEnabled(context))
        assertTrue(SettingsManager.getSuggestionsAddWordButtonEnabled(context))
    }

    @Test
    fun personalizationOff_hidesLearnedNGramsOnEmptyFieldAndAfterSpace_whileKeepingDictionarySuggestions() {
        var personalizationEnabled = true
        val controller = newController(
            personalizationProvider = { personalizationEnabled }
        )

        // Learn sentence-start and bigram while personalization is ON.
        typeWord(controller, "badstart")
        pressSpace(controller)
        typeWord(controller, "secretword")
        pressPeriod(controller)
        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "secretword")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        // Turn personalization OFF without deleting stored data.
        personalizationEnabled = false
        controller.onSettingsChanged(emptyInputConnection())
        assertTrue(snapshots.last().isEmpty())

        // Empty field must not leak learned sentence-start or random starter words.
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        assertTrue(snapshots.last().isEmpty())

        // After space, learned bigram must not appear.
        typeWord(controller, "ich")
        pressSpace(controller)
        assertTrue(snapshots.last().isEmpty())

        // Ordinary dictionary suggestions continue working while typing.
        typeWord(controller, "hel")
        val typed = waitForSuggestionCandidates("help", "held", "hello")
        assertEquals(listOf("help", "held", "hello"), typed.map { it.candidate })
        assertTrue(typed.all { it.kind == SuggestionKind.CURRENT_WORD })
    }

    @Test
    fun personalizationOff_doesNotLearnNewNGramsOrUsageCountsWhileOff() {
        var personalizationEnabled = false
        val controller = newController(
            personalizationProvider = { personalizationEnabled },
            autoReplaceOnSpaceEnter = true
        )

        // Type sentences while personalization is OFF.
        typeWord(controller, "alpha")
        pressSpace(controller)
        typeWord(controller, "beta")
        pressSpace(controller)
        typeWord(controller, "gamma")
        pressPeriod(controller)
        controller.flushNextWordLearningForTests()

        // Explicit markUsed and auto-replace must not record usage while OFF.
        controller.markUsed("hello")
        val input = RecordingInputConnection(context, "helo")
        val replaced = controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, input)
        assertTrue(replaced.replaced)
        assertEquals("help ", input.text)
        controller.flushNextWordLearningForTests()

        assertTrue(store.predict("de-DE", "__sentence_start__", limit = 5).isEmpty())
        assertTrue(store.predict("de-DE", "alpha", limit = 5).isEmpty())
        assertTrue(store.predict("de-DE", "beta", limit = 5).isEmpty())
        assertTrue(fakeRepository.markedUsedWords.isEmpty())

        // Turning personalization ON resumes learning.
        personalizationEnabled = true
        controller.onSettingsChanged(emptyInputConnection())
        controller.onContextReset()
        typeWord(controller, "alpha")
        pressSpace(controller)
        typeWord(controller, "beta")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        assertEquals(listOf("alpha"), store.predict("de-DE", "__sentence_start__", limit = 5).map { it.word })
        assertEquals(listOf("beta"), store.predict("de-DE", "alpha", limit = 5).map { it.word })
    }

    @Test
    fun personalizationToggleOffAndBackOn_preservesStoredDataWithoutReset() {
        fakeRepository.addTestEntry("mycustomword", 25, SuggestionSource.USER)
        var personalizationEnabled = true
        val controller = newController(
            personalizationProvider = { personalizationEnabled }
        )

        typeWord(controller, "Hallo")
        pressPeriod(controller)
        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "bin")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        // Disable personalization: data stays intact, UI shows no learned predictions.
        personalizationEnabled = false
        controller.onSettingsChanged(emptyInputConnection())
        assertTrue(snapshots.last().isEmpty())
        assertTrue(fakeRepository.isKnownWord("mycustomword"))

        // Re-enable personalization: previously learned data immediately returns.
        personalizationEnabled = true
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        val restoredSentenceStarts = snapshots.last()
        assertTrue(restoredSentenceStarts.any { it.candidate == "Hallo" && it.kind == SuggestionKind.NEXT_WORD })
        assertTrue(restoredSentenceStarts.any { it.candidate == "ich" && it.kind == SuggestionKind.NEXT_WORD })

        typeWord(controller, "ich")
        pressSpace(controller)
        assertEquals("bin", snapshots.last().first().candidate)
        assertEquals(SuggestionKind.NEXT_WORD, snapshots.last().first().kind)
        assertTrue(fakeRepository.isKnownWord("mycustomword"))
    }

    @Test
    fun suggestionEngine_personalizationOffRemovesUsageBoostButKeepsDeliberateCustomWords() {
        fakeRepository.addTestEntry("hellhound", 250, SuggestionSource.USER)
        fakeRepository.addTestEntry("mycustomterm", 1, SuggestionSource.USER)
        fakeRepository.addTestEntry("Plektra", 30, SuggestionSource.DEFAULT_USER)

        val engine = SuggestionEngine(fakeRepository, locale = Locale.ENGLISH)

        val personalized = engine.suggest("hel", limit = 3, usePersonalization = true)
        assertEquals("hellhound", personalized.first().candidate)

        val dictionaryOnly = engine.suggest("hel", limit = 3, usePersonalization = false)
        assertEquals(listOf("help", "held", "hello"), dictionaryOnly.map { it.candidate })
        assertFalse(dictionaryOnly.any { it.candidate == "hellhound" })

        // Same word present in both MAIN and USER retains its MAIN frequency when personalization is OFF.
        fakeRepository.addTestEntry("help", 255, SuggestionSource.USER)
        val withDuplicateUserEntry = engine.suggest("hel", limit = 3, usePersonalization = false)
        assertEquals("help", withDuplicateUserEntry.first().candidate)
        assertFalse(withDuplicateUserEntry.any { it.candidate == "hellhound" })

        // Deliberate custom dictionary entries remain suggestible when matching user input.
        val customMatch = engine.suggest("mycustom", limit = 3, usePersonalization = false)
        assertEquals(listOf("mycustomterm"), customMatch.map { it.candidate })

        val defaultUserMatch = engine.suggest("Plek", limit = 3, usePersonalization = false)
        assertEquals(listOf("Plektra"), defaultUserMatch.map { it.candidate })
    }

    @Test
    fun personalizationAndAddWordButtonSettings_operateIndependentlyAndRefreshOnToggle() {
        var personalizationEnabled = true
        var showAddWordButton = true
        val controller = newController(
            personalizationProvider = { personalizationEnabled },
            showAddWordButtonProvider = { showAddWordButton }
        )

        // Learn a sentence-start word while personalization is ON.
        typeWord(controller, "Hallo")
        pressPeriod(controller)
        controller.flushNextWordLearningForTests()

        // Personalization ON + Add Word Button OFF:
        // Learned predictions still work, unknown word produces no add-word candidate.
        showAddWordButton = false
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        assertEquals("Hallo", snapshots.last().first().candidate)

        typeWord(controller, "qwxyz")
        assertNull(controller.pendingAddWord())

        // Toggling Add Word Button ON mid-word immediately restores the pending candidate.
        showAddWordButton = true
        controller.onSettingsChanged(null)
        assertEquals("qwxyz", controller.pendingAddWord())

        // Personalization OFF + Add Word Button ON:
        // Pending add-word candidate still appears, empty field has no learned words.
        personalizationEnabled = false
        controller.onSettingsChanged(null)
        assertEquals("qwxyz", controller.pendingAddWord())
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        assertTrue(snapshots.last().isEmpty())

        // Both OFF:
        // Neither learned empty-field predictions nor add-word candidate appear,
        // while ordinary dictionary suggestions continue working.
        showAddWordButton = false
        typeWord(controller, "hel")
        assertNull(controller.pendingAddWord())
        assertEquals(listOf("help", "held", "hello"), waitForSuggestionCandidates("help", "held", "hello").map { it.candidate })
    }

    @Test
    fun privacyGates_passwordAndNoPersonalizedLearningPreventLearning() {
        val incognitoInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val incognitoState = InputContextState.fromEditorInfo(incognitoInfo)
        assertTrue(incognitoState.noPersonalizedLearning)
        assertTrue(incognitoState.shouldDisablePersonalizedLearning)
        assertFalse(incognitoState.shouldDisableSuggestions)

        val passwordInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passwordState = InputContextState.fromEditorInfo(passwordInfo)
        assertTrue(passwordState.isPasswordField)
        assertTrue(passwordState.shouldDisableSuggestions)
        assertTrue(passwordState.shouldDisablePersonalizedLearning)

        // Restricted/password controller (isEnabled = false) must never learn on boundary.
        val restrictedController = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = { SuggestionSettings(suggestionsEnabled = true, personalizationEnabled = true) },
            isEnabled = { false },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> fakeRepository },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        val passwordInput = RecordingInputConnection(context, "topsecret")
        restrictedController.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, passwordInput)
        restrictedController.flushNextWordLearningForTests()

        assertTrue(store.predict("de-DE", "__sentence_start__", limit = 5).isEmpty())
    }

    private fun newController(
        personalizationProvider: () -> Boolean = { true },
        showAddWordButtonProvider: () -> Boolean = { true },
        autoReplaceOnSpaceEnter: Boolean = false
    ): SuggestionController {
        return SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationProvider(),
                    showAddWordButton = showAddWordButtonProvider(),
                    autoReplaceOnSpaceEnter = autoReplaceOnSpaceEnter,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> fakeRepository },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }
    }

    private fun typeWord(controller: SuggestionController, word: String) {
        word.forEach { ch ->
            controller.onCharacterCommitted(ch.toString(), null)
        }
    }

    private fun pressSpace(controller: SuggestionController) {
        controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, null)
    }

    private fun pressPeriod(controller: SuggestionController) {
        val event = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(charArrayOf('.'))
            ?.firstOrNull { it.action == KeyEvent.ACTION_DOWN }
            ?: KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PERIOD)
        controller.onBoundaryKey(KeyEvent.KEYCODE_PERIOD, event, null)
    }

    private fun waitForSuggestionCandidates(vararg candidates: String): List<SuggestionResult> {
        val expected = candidates.toList()
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            snapshots.lastOrNull()?.let { latest ->
                if (latest.map { it.candidate } == expected) {
                    return latest
                }
            }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return snapshots.last()
    }

    private fun emptyInputConnection(): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> null
            }
        } as InputConnection
    }

    private class TrackingDictionaryRepository : DictionaryRepository by FakeDictionaryRepository() {
        private val delegate = FakeDictionaryRepository()
        val markedUsedWords = mutableListOf<String>()

        override var isReady: Boolean
            get() = delegate.isReady
            set(value) { delegate.isReady = value }

        override var isLoadStarted: Boolean
            get() = delegate.isLoadStarted
            set(value) { delegate.isLoadStarted = value }

        fun addTestEntry(word: String, frequency: Int, source: SuggestionSource = SuggestionSource.MAIN) {
            delegate.addTestEntry(word, frequency, source)
        }

        override suspend fun loadIfNeeded() = delegate.loadIfNeeded()
        override suspend fun refreshUserEntries() = delegate.refreshUserEntries()
        override fun addUserEntryQuick(word: String) = delegate.addUserEntryQuick(word)
        override fun removeUserEntry(word: String) = delegate.removeUserEntry(word)
        override fun markUsed(word: String) {
            markedUsedWords.add(word)
            delegate.markUsed(word)
        }
        override fun effectiveFrequency(entry: DictionaryEntry) = delegate.effectiveFrequency(entry)
        override fun getExactWordFrequency(word: String) = delegate.getExactWordFrequency(word)
        override fun lookupByPrefixMerged(prefix: String, maxSize: Int) = delegate.lookupByPrefixMerged(prefix, maxSize)
        override fun symSpellLookup(term: String, maxSuggestions: Int) = delegate.symSpellLookup(term, maxSuggestions)
        override fun bestEntryForNormalized(normalized: String) = delegate.bestEntryForNormalized(normalized)
        override fun topByNormalized(normalized: String, limit: Int) = delegate.topByNormalized(normalized, limit)
        override fun topCommonEntries(limit: Int) = delegate.topCommonEntries(limit)
        override fun isKnownWord(word: String) = delegate.isKnownWord(word)
        override fun ensureLoadScheduled(background: () -> Unit) = delegate.ensureLoadScheduled(background)
    }

    private class RecordingInputConnection(
        context: Context,
        initialText: String
    ) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)
        val text: String get() = buffer.toString()

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
