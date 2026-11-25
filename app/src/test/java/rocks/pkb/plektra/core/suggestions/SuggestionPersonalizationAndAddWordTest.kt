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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService

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
    fun starterWordsWithoutPersonalizationComeFromTheBundledDictionaryOnly() {
        fakeRepository.addTestEntry("secretuser", 255, SuggestionSource.USER)
        var personalizationEnabled = true
        val controller = newController(
            personalizationProvider = { personalizationEnabled },
            starterWordsProvider = { true }
        )
        typeWord(controller, "badstart")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()

        personalizationEnabled = false
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        val starters = snapshots.last()
        assertEquals(listOf("hello", "ich", "help"), starters.map { it.candidate })
        assertTrue(starters.all { it.kind == SuggestionKind.STARTER_WORD && it.source == SuggestionSource.MAIN })
        assertFalse(controller.currentSuggestions().any { it.candidate == "secretuser" || it.candidate == "badstart" })

        // A new sentence offers them again; a space within a sentence offers no learned next word.
        typeWord(controller, "ich")
        pressSpace(controller)
        assertTrue(snapshots.last().isEmpty())
        typeWord(controller, "dann")
        pressPeriod(controller)
        assertEquals(listOf("hello", "ich", "help"), snapshots.last().map { it.candidate })
    }

    @Test
    fun starterWordsOffKeepsLearnedPredictionsButNoFillers() {
        val controller = newController(personalizationProvider = { true }, starterWordsProvider = { false })
        controller.onContextReset()
        controller.readInitialContext(emptyInputConnection())
        assertTrue(snapshots.last().isEmpty())

        typeWord(controller, "ich")
        pressSpace(controller)
        typeWord(controller, "dann")
        pressPeriod(controller)
        typeWord(controller, "ich")
        pressSpace(controller)
        controller.flushNextWordLearningForTests()
        assertEquals(listOf("dann"), snapshots.last().map { it.candidate })
        assertTrue(snapshots.last().none { it.kind == SuggestionKind.STARTER_WORD })
    }

    @Test
    fun starterWordSettingKeepsTheEarlierCouplingUntilChosen() {
        SettingsManager.setSuggestionsPersonalizationEnabled(context, false)
        // Materialized from the value before the change: what the user saw is kept.
        assertTrue(SettingsManager.getSuggestionStarterWordsEnabled(context))
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.getPreferences(context).edit()
            .putBoolean(SettingsManager.KEY_SUGGESTIONS_PERSONALIZATION_ENABLED, false).commit()
        assertFalse(SettingsManager.getSuggestionStarterWordsEnabled(context))
        SettingsManager.setSuggestionStarterWordsEnabled(context, true)
        SettingsManager.setSuggestionsPersonalizationEnabled(context, true)
        SettingsManager.setSuggestionsPersonalizationEnabled(context, false)
        assertTrue(SettingsManager.getSuggestionStarterWordsEnabled(context))
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
    fun androidDictionaryRepository_personalizationOff_symSpellPreselectionIsIndependentOfUserUsageCounts() {
        fun buildRepo(userFrequency: Int): AndroidDictionaryRepository {
            val repo = AndroidDictionaryRepository(
                context = context,
                assets = context.assets,
                userDictionaryStore = UserDictionaryStore(),
                baseLocale = Locale.ENGLISH
            )
            repo.isReady = true
            val entries = buildList {
                add(DictionaryEntry("azzz", 200, SuggestionSource.MAIN))
                for (letter in 'b'..'m') {
                    add(DictionaryEntry("${letter}zzz", userFrequency, SuggestionSource.USER))
                }
            }
            repo.index(entries)
            repo.buildSymSpell()
            return repo
        }

        val lowUsageRepo = buildRepo(userFrequency = 1)
        val highUsageRepo = buildRepo(userFrequency = 255)

        val offLow = SuggestionEngine(lowUsageRepo, locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = false)
        val offHigh = SuggestionEngine(highUsageRepo, locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = false)

        // Exact results and ordering must be identical regardless of USER usage counts when OFF.
        assertEquals(listOf("azzz", "bzzz", "czzz"), offLow.map { it.candidate })
        assertEquals(offLow, offHigh)

        // Negative control: when ON, high USER usage counts DO personalize and displace MAIN.
        val onHigh = SuggestionEngine(highUsageRepo, locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = true)
        assertFalse(onHigh.any { it.candidate == "azzz" })
        assertTrue(onHigh.all { it.source == SuggestionSource.USER })
    }

    @Test
    fun androidDictionaryRepository_personalizationOff_symSpellWithMoreThanTwelveMainAndUserCandidates() {
        fun buildRepo(userFrequency: Int): AndroidDictionaryRepository {
            val repo = AndroidDictionaryRepository(
                context = context,
                assets = context.assets,
                userDictionaryStore = UserDictionaryStore(),
                baseLocale = Locale.ENGLISH
            )
            repo.isReady = true
            val entries = buildList {
                // 14 MAIN candidates at edit distance 1 from "zzzz", plus 14 USER candidates.
                for ((idx, letter) in ('a'..'n').withIndex()) {
                    add(DictionaryEntry("${letter}zzz", 180 + idx, SuggestionSource.MAIN))
                }
                for (letter in 'o'..'z') {
                    add(DictionaryEntry("${letter}zzz", userFrequency, SuggestionSource.USER))
                }
                add(DictionaryEntry("zzza", userFrequency, SuggestionSource.USER))
                add(DictionaryEntry("zzzb", userFrequency, SuggestionSource.USER))
            }
            repo.index(entries)
            repo.buildSymSpell()
            return repo
        }

        val offLow = SuggestionEngine(buildRepo(1), locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = false)
        val offHigh = SuggestionEngine(buildRepo(255), locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = false)

        assertEquals(listOf("nzzz", "mzzz", "lzzz"), offLow.map { it.candidate })
        assertEquals(offLow, offHigh)

        // Negative control: ON still ranks high-usage USER candidates ahead of MAIN.
        val onHigh = SuggestionEngine(buildRepo(255), locale = Locale.ENGLISH)
            .suggest("zzzz", limit = 3, useKeyboardProximity = false, useEditTypeRanking = false, usePersonalization = true)
        assertTrue(onHigh.all { it.source == SuggestionSource.USER })
    }

    @Test
    fun androidDictionaryRepository_personalizationOff_prefixPreselectionExceedingLimitIgnoresUserUsageCounts() {
        fun buildRepo(userFrequency: Int): AndroidDictionaryRepository {
            val repo = AndroidDictionaryRepository(
                context = context,
                assets = context.assets,
                userDictionaryStore = UserDictionaryStore(),
                baseLocale = Locale.ENGLISH
            )
            repo.isReady = true
            val entries = buildList {
                add(DictionaryEntry("helium", 210, SuggestionSource.MAIN))
                add(DictionaryEntry("helmet", 200, SuggestionSource.MAIN))
                add(DictionaryEntry("helped", 190, SuggestionSource.MAIN))
                // Exceed the 200-entry prefix bucket preselection cap with letter-only USER words
                // of the exact same length (no numeric malus or length penalty bias).
                var count = 0
                for (c1 in 'a'..'i') {
                    for (c2 in 'a'..'z') {
                        if (count >= 220) break
                        val word = "hel${c1}${c2}x"
                        val freq = if (userFrequency == 255) 255 - (count % 50) else 1
                        add(DictionaryEntry(word, freq, SuggestionSource.USER))
                        count++
                    }
                    if (count >= 220) break
                }
                // Deliberate USER word that does not compete with MAIN under a distinct prefix.
                add(DictionaryEntry("zylophonecustom", userFrequency, SuggestionSource.USER))
            }
            repo.index(entries)
            repo.buildSymSpell()
            return repo
        }

        val lowRepo = buildRepo(1)
        val highRepo = buildRepo(255)

        val offLow = SuggestionEngine(lowRepo, locale = Locale.ENGLISH)
            .suggest("hel", limit = 3, usePersonalization = false)
        val offHigh = SuggestionEngine(highRepo, locale = Locale.ENGLISH)
            .suggest("hel", limit = 3, usePersonalization = false)

        assertEquals(listOf("helium", "helmet", "helped"), offLow.map { it.candidate })
        assertEquals(offLow, offHigh)

        // Deliberate USER words are preserved and still suggestible when OFF.
        val customOff = SuggestionEngine(highRepo, locale = Locale.ENGLISH)
            .suggest("zyloph", limit = 3, usePersonalization = false)
        assertEquals(listOf("zylophonecustom"), customOff.map { it.candidate })

        // Negative control: ON still surfaces high-usage USER prefix words.
        val onHigh = SuggestionEngine(highRepo, locale = Locale.ENGLISH)
            .suggest("hel", limit = 3, usePersonalization = true)
        assertTrue(onHigh.all { it.source == SuggestionSource.USER })
    }

    @Test
    fun androidDictionaryRepository_personalizationOff_normalizedListsAndAutoReplaceAccentPathIgnoreUserUsageCounts() {
        fun buildRepo(userFrequency: Int): AndroidDictionaryRepository {
            val repo = AndroidDictionaryRepository(
                context = context,
                assets = context.assets,
                userDictionaryStore = UserDictionaryStore(),
                baseLocale = Locale.FRENCH
            )
            repo.isReady = true
            val entries = buildList {
                // Single-char accented MAIN vs >5 USER variants under normalized "e".
                add(DictionaryEntry("é", 210, SuggestionSource.MAIN))
                add(DictionaryEntry("è", 200, SuggestionSource.MAIN))
                listOf("ê", "ë", "ē", "ĕ", "ė", "ę", "ě").forEachIndexed { idx, variant ->
                    val freq = if (userFrequency == 255) 255 - idx else 1
                    add(DictionaryEntry(variant, freq, SuggestionSource.USER))
                }

                // Multi-char accent variants exceeding AutoReplaceController's topByNormalized(limit = 8) cap.
                add(DictionaryEntry("café", 200, SuggestionSource.MAIN))
                listOf("càfe", "cáfe", "câfe", "cãfe", "cäfe", "cåfe", "cafè", "cafê", "cafë")
                    .forEachIndexed { idx, variant ->
                        val freq = if (userFrequency == 255) 255 - idx else 1
                        add(DictionaryEntry(variant, freq, SuggestionSource.USER))
                    }

                // Deliberate USER-only accent variant (no MAIN counterpart).
                add(DictionaryEntry("zérox", userFrequency, SuggestionSource.USER))

                // Case-variant path: MAIN "Problem" vs >8 high-usage USER variants under normalized "problem".
                add(DictionaryEntry("Problem", 200, SuggestionSource.MAIN))
                listOf("pròblem", "próblem", "prôblem", "pröblem", "problèm", "problém", "problêm", "problëm", "PROBLEM")
                    .forEachIndexed { idx, variant ->
                        val freq = if (userFrequency == 255) 255 - idx else 1
                        add(DictionaryEntry(variant, freq, SuggestionSource.USER))
                    }
            }
            repo.index(entries)
            repo.buildSymSpell()
            return repo
        }

        val lowRepo = buildRepo(1)
        val highRepo = buildRepo(255)

        // Single-char normalized list path when OFF.
        val singleOffLow = SuggestionEngine(lowRepo, locale = Locale.FRENCH)
            .suggest("e", limit = 3, usePersonalization = false)
        val singleOffHigh = SuggestionEngine(highRepo, locale = Locale.FRENCH)
            .suggest("e", limit = 3, usePersonalization = false)
        assertEquals(listOf("é", "è"), singleOffLow.take(2).map { it.candidate })
        assertEquals(singleOffLow, singleOffHigh)

        // AutoReplaceController direct accent-variant path when OFF.
        fun runAutoReplace(repo: AndroidDictionaryRepository, input: String, personalization: Boolean): AutoReplaceController.ReplaceResult {
            val engine = SuggestionEngine(repo, locale = Locale.FRENCH)
            val controller = AutoReplaceController(
                repository = repo,
                suggestionEngine = engine,
                settingsProvider = {
                    SuggestionSettings(
                        autoReplaceOnSpaceEnter = true,
                        accentMatching = true,
                        personalizationEnabled = personalization,
                        maxAutoReplaceDistance = 1
                    )
                }
            )
            val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
            tracker.setWord(input)
            val connection = RecordingInputConnection(context, input)
            return controller.handleBoundary(
                keyCode = KeyEvent.KEYCODE_SPACE,
                event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
                tracker = tracker,
                inputConnection = connection
            )
        }

        val accentOffLow = runAutoReplace(lowRepo, "cafe", personalization = false)
        val accentOffHigh = runAutoReplace(highRepo, "cafe", personalization = false)
        assertTrue(accentOffLow.replaced)
        assertEquals("café", accentOffLow.replacement)
        assertEquals(accentOffLow, accentOffHigh)

        // Deliberate USER-only accent variant still replaces when OFF.
        val userOnlyOff = runAutoReplace(highRepo, "zerox", personalization = false)
        assertTrue(userOnlyOff.replaced)
        assertEquals("zérox", userOnlyOff.replacement)

        // Negative control: when ON, high-usage USER accent variant wins.
        val accentOnHigh = runAutoReplace(highRepo, "cafe", personalization = true)
        assertTrue(accentOnHigh.replaced)
        assertEquals("càfe", accentOnHigh.replacement)

        // Primary dictionary case-variant path when OFF is also independent of USER usage counts.
        val caseOffLow = runAutoReplace(lowRepo, "problem", personalization = false)
        val caseOffHigh = runAutoReplace(highRepo, "problem", personalization = false)
        assertTrue(caseOffLow.replaced)
        assertEquals("Problem", caseOffLow.replacement)
        assertEquals(caseOffLow, caseOffHigh)
    }

    @Test
    fun personalizationOff_withActiveWord_synchronouslyClearsPublishedPersonalizedCacheBeforeRecompute() {
        fakeRepository.addTestEntry("hellhound", 250, SuggestionSource.USER)
        var personalizationEnabled = true
        val controller = newController(
            personalizationProvider = { personalizationEnabled }
        )

        typeWord(controller, "hel")
        val personalized = waitForSuggestionCandidates("hellhound", "help", "held")
        assertEquals("hellhound", personalized.first().candidate)
        assertEquals("hellhound", controller.currentSuggestions().first().candidate)

        val snapshotIndexBeforeOff = snapshots.size
        personalizationEnabled = false
        controller.onSettingsChanged(null)

        // Immediately after handling the OFF setting change, stale personalized cache must be gone.
        assertTrue(
            "currentSuggestions must be cleared synchronously on OFF",
            controller.currentSuggestions().isEmpty()
        )
        assertTrue(
            "Listener must be notified synchronously with cleared suggestions on OFF",
            snapshots.size > snapshotIndexBeforeOff
        )
        assertTrue(
            "Immediate listener emissions on OFF must not republish stale personalized results",
            snapshots.subList(snapshotIndexBeforeOff, snapshots.size).all { it.isEmpty() }
        )

        // Subsequent non-personalized dictionary results appear cleanly after recompute.
        val refreshed = waitForSuggestionCandidates("help", "held", "hello")
        assertEquals(listOf("help", "held", "hello"), refreshed.map { it.candidate })

        // No listener emission from the settings change onward may ever contain the stale personalized entry.
        val emissionsAfterOff = snapshots.subList(snapshotIndexBeforeOff, snapshots.size)
        assertTrue(
            "No listener call after OFF may publish stale personalized candidate",
            emissionsAfterOff.none { list -> list.any { it.candidate == "hellhound" } }
        )
    }

    @Test
    fun personalizationOff_withInFlightMainQueuedPersonalizedJob_neverPublishesStaleResults() {
        fakeRepository.addTestEntry("hellhound", 250, SuggestionSource.USER)
        var personalizationEnabled = true
        val firstLookupCompleted = CountDownLatch(1)
        val instrumentedRepo = object : DictionaryRepository by fakeRepository {
            override fun symSpellLookup(
                term: String,
                maxSuggestions: Int,
                usePersonalization: Boolean
            ): List<SymSpell.SuggestItem> {
                val result = fakeRepository.symSpellLookup(term, maxSuggestions, usePersonalization)
                if (usePersonalization && term == "hel") {
                    firstLookupCompleted.countDown()
                }
                return result
            }
        }
        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationEnabled,
                    showAddWordButton = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> instrumentedRepo },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        // Type active word while ON, wait until the background job has computed and posted to Main Looper
        // WITHOUT idling Main Looper yet.
        controller.onCharacterCommitted("hel", null)
        assertTrue("Expected personalized lookup to finish in background", firstLookupCompleted.await(3, TimeUnit.SECONDS))
        // Give the coroutine a brief moment to execute cursorHandler.post onto the Main Looper queue.
        Thread.sleep(30)

        val snapshotIndexBeforeOff = snapshots.size
        personalizationEnabled = false
        controller.onSettingsChanged(null)

        // Immediately cleared before Main Looper drains queued stale publish.
        assertTrue(controller.currentSuggestions().isEmpty())
        assertTrue(snapshots.subList(snapshotIndexBeforeOff, snapshots.size).all { it.isEmpty() })

        // Drain the Main Looper (which still held the stale personalized publish runnable) and wait for replacement.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Stale Main-queued personalized job must be dropped",
            snapshots.subList(snapshotIndexBeforeOff, snapshots.size)
                .none { list -> list.any { it.candidate == "hellhound" } }
        )

        val refreshed = waitForSuggestionCandidates("help", "held", "hello")
        assertEquals(listOf("help", "held", "hello"), refreshed.map { it.candidate })
        assertTrue(
            "No emission after OFF may ever contain stale personalized candidate",
            snapshots.subList(snapshotIndexBeforeOff, snapshots.size)
                .none { list -> list.any { it.candidate == "hellhound" } }
        )
    }

    @Test
    fun liveRestorePrivacyBarrier_blocksQueuedPersonalizedPublishEvenBeforeOnSettingsChangedRuns() {
        fakeRepository.addTestEntry("hellhound", 250, SuggestionSource.USER)
        var personalizationEnabled = true
        val personalizedLookupDone = CountDownLatch(1)
        val instrumentedRepo = object : DictionaryRepository by fakeRepository {
            override fun symSpellLookup(
                term: String,
                maxSuggestions: Int,
                usePersonalization: Boolean
            ): List<SymSpell.SuggestItem> {
                val result = fakeRepository.symSpellLookup(term, maxSuggestions, usePersonalization)
                if (usePersonalization && term == "hel") {
                    personalizedLookupDone.countDown()
                }
                return result
            }
        }
        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationEnabled,
                    showAddWordButton = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.GERMANY,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> instrumentedRepo },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        controller.onCharacterCommitted("hel", null)
        assertTrue(personalizedLookupDone.await(3, TimeUnit.SECONDS))
        Thread.sleep(30)

        // Simulate live restore flipping preference OFF from a background thread before main-thread onSettingsChanged runs.
        val snapshotIndexBeforeRestore = snapshots.size
        personalizationEnabled = false
        controller.invalidatePersonalizedSuggestionsImmediately()

        // Main Looper drains the previously queued personalized publish BEFORE onSettingsChanged runs.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.currentSuggestions().isEmpty())
        assertTrue(
            "Queued personalized publish must be blocked after live-restore OFF barrier",
            snapshots.subList(snapshotIndexBeforeRestore, snapshots.size)
                .none { list -> list.any { it.candidate == "hellhound" } }
        )

        // Then onSettingsChanged runs on main and recomputes clean dictionary suggestions.
        controller.onSettingsChanged(null)
        assertTrue(controller.currentSuggestions().isEmpty())
        val refreshed = waitForSuggestionCandidates("help", "held", "hello")
        assertEquals(listOf("help", "held", "hello"), refreshed.map { it.candidate })
        assertTrue(
            snapshots.subList(snapshotIndexBeforeRestore, snapshots.size)
                .none { list -> list.any { it.candidate == "hellhound" } }
        )
    }

    @Test
    fun crossThreadInvalidation_beatsAlreadyRunningMainPublish_andRecomputesNeutralDictionaryResults() {
        val realRepo = AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        ).apply {
            isReady = true
            index(
                listOf(
                    DictionaryEntry("hello", 220, SuggestionSource.MAIN),
                    DictionaryEntry("help", 200, SuggestionSource.MAIN),
                    DictionaryEntry("held", 180, SuggestionSource.MAIN),
                    DictionaryEntry("hellhound", 250, SuggestionSource.USER)
                )
            )
            buildSymSpell()
        }

        val personalizationEnabled = AtomicBoolean(true)
        val armedForPublish = AtomicBoolean(false)
        val computedOnBackground = CountDownLatch(1)
        val mainPausedInsideIsKnownWord = CountDownLatch(1)
        val backgroundInvalidationCompleted = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val listenerEventsAfterBarrier = CopyOnWriteArrayList<List<SuggestionResult>>()
        val barrierCompleted = AtomicBoolean(false)
        val listenerCalledOffMain = AtomicBoolean(false)

        val wrappedRepo = Proxy.newProxyInstance(
            DictionaryRepository::class.java.classLoader,
            arrayOf(DictionaryRepository::class.java)
        ) { _, method, args ->
            if (method.name == "isKnownWord" && armedForPublish.compareAndSet(true, false)) {
                mainPausedInsideIsKnownWord.countDown()
                assertTrue(
                    "Background invalidation thread must not deadlock on main publish",
                    backgroundInvalidationCompleted.await(3, TimeUnit.SECONDS)
                )
            }
            val result = if (args == null) method.invoke(realRepo) else method.invoke(realRepo, *args)
            if (method.name == "symSpellLookup") {
                computedOnBackground.countDown()
            }
            result
        } as DictionaryRepository

        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).get()
        val inputContextStateField = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("inputContextState").apply {
            isAccessible = true
        }
        inputContextStateField.set(
            service,
            InputContextState.fromEditorInfo(
                EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                }
            )
        )
        val handleSuggestionsUpdated = PhysicalKeyboardInputMethodService::class.java.getDeclaredMethod(
            "handleSuggestionsUpdated",
            List::class.java
        ).apply { isAccessible = true }
        val latestServiceResultsField = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("latestSuggestionResults").apply {
            isAccessible = true
        }

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationEnabled.get(),
                    showAddWordButton = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { list ->
                if (Thread.currentThread() !== Looper.getMainLooper().thread) {
                    listenerCalledOffMain.set(true)
                }
                snapshots.add(list)
                if (barrierCompleted.get()) {
                    listenerEventsAfterBarrier.add(list)
                }
                handleSuggestionsUpdated.invoke(service, list)
            },
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> wrappedRepo },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        val restoreThread = Thread({
            try {
                assertTrue(mainPausedInsideIsKnownWord.await(3, TimeUnit.SECONDS))
                personalizationEnabled.set(false)
                SettingsManager.setSuggestionsPersonalizationEnabled(context, false)
                controller.invalidatePersonalizedSuggestionsImmediately()
                barrierCompleted.set(true)
                assertTrue(controller.currentSuggestions().isEmpty())
            } catch (t: Throwable) {
                workerFailure.set(t)
            } finally {
                backgroundInvalidationCompleted.countDown()
            }
        }, "r3-2a-live-restore")

        try {
            controller.onCharacterCommitted("hel", null)
            assertTrue(computedOnBackground.await(3, TimeUnit.SECONDS))
            Thread.sleep(50)

            armedForPublish.set(true)
            restoreThread.start()
            // Draining Main enters cursorHandler.post, passes initial checks, pauses inside isKnownWord,
            // lets restoreThread run OFF + invalidatePersonalizedSuggestionsImmediately(), then resumes.
            shadowOf(Looper.getMainLooper()).idle()
            restoreThread.join(3000)
            assertFalse("Restore worker thread must complete", restoreThread.isAlive)
            workerFailure.get()?.let { throw AssertionError(it) }

            @Suppress("UNCHECKED_CAST")
            val serviceAfterBarrier = latestServiceResultsField.get(service) as List<SuggestionResult>
            assertTrue(
                "Controller cache must not be overwritten by stale in-flight main publish",
                controller.currentSuggestions().isEmpty()
            )
            assertTrue(
                "Service must not receive stale personalized list after OFF barrier",
                serviceAfterBarrier.none { it.candidate == "hellhound" }
            )
            assertTrue(
                "No listener callback after OFF barrier may receive stale personalized list",
                listenerEventsAfterBarrier.none { list -> list.any { it.candidate == "hellhound" } }
            )

            // Subsequent main-thread onSettingsChanged recomputes and publishes neutral OFF dictionary results.
            controller.onSettingsChanged(null)
            val refreshed = waitForSuggestionCandidates("help", "held", "hello")
            assertEquals(listOf("help", "held", "hello"), refreshed.map { it.candidate })
            @Suppress("UNCHECKED_CAST")
            val serviceAfterRecompute = latestServiceResultsField.get(service) as List<SuggestionResult>
            assertEquals(
                listOf("help", "held", "hello"),
                serviceAfterRecompute.map { it.candidate }
            )
            assertTrue(
                "No listener callback after OFF barrier may ever contain stale personalized candidate",
                listenerEventsAfterBarrier.none { list -> list.any { it.candidate == "hellhound" } }
            )
            assertFalse(
                "Background invalidation must never invoke UI listener off main thread",
                listenerCalledOffMain.get()
            )
        } finally {
            backgroundInvalidationCompleted.countDown()
            restoreThread.join(3000)
        }
    }

    @Test
    fun crossThreadInvalidation_beatsAlreadyRunningDismiss_andSubsequentOffRecomputePublishesNeutralResults() {
        val realRepo = AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        ).apply {
            isReady = true
            index(
                listOf(
                    DictionaryEntry("hello", 220, SuggestionSource.MAIN),
                    DictionaryEntry("help", 200, SuggestionSource.MAIN),
                    DictionaryEntry("held", 180, SuggestionSource.MAIN),
                    DictionaryEntry("hellhound", 250, SuggestionSource.USER)
                )
            )
            buildSymSpell()
        }

        val personalizationEnabled = AtomicBoolean(true)
        val armedForDismissFill = AtomicBoolean(false)
        val mainPausedInsideTopCommon = CountDownLatch(1)
        val backgroundInvalidationCompleted = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val listenerEventsAfterBarrier = CopyOnWriteArrayList<List<SuggestionResult>>()
        val barrierCompleted = AtomicBoolean(false)
        val listenerCalledOffMain = AtomicBoolean(false)

        val wrappedRepo = Proxy.newProxyInstance(
            DictionaryRepository::class.java.classLoader,
            arrayOf(DictionaryRepository::class.java)
        ) { _, method, args ->
            if (method.name == "topCommonEntries" && armedForDismissFill.compareAndSet(true, false)) {
                mainPausedInsideTopCommon.countDown()
                assertTrue(
                    "Background OFF barrier must complete outside dictionary lookup",
                    backgroundInvalidationCompleted.await(3, TimeUnit.SECONDS)
                )
            }
            if (args == null) method.invoke(realRepo) else method.invoke(realRepo, *args)
        } as DictionaryRepository

        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).get()
        val inputContextStateField = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("inputContextState").apply {
            isAccessible = true
        }
        inputContextStateField.set(
            service,
            InputContextState.fromEditorInfo(
                EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                }
            )
        )
        val handleSuggestionsUpdated = PhysicalKeyboardInputMethodService::class.java.getDeclaredMethod(
            "handleSuggestionsUpdated",
            List::class.java
        ).apply { isAccessible = true }
        val latestServiceResultsField = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("latestSuggestionResults").apply {
            isAccessible = true
        }

        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationEnabled.get(),
                    showAddWordButton = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { list ->
                if (Thread.currentThread() !== Looper.getMainLooper().thread) {
                    listenerCalledOffMain.set(true)
                }
                snapshots.add(list)
                if (barrierCompleted.get()) {
                    listenerEventsAfterBarrier.add(list)
                }
                handleSuggestionsUpdated.invoke(service, list)
            },
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> wrappedRepo },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        val restoreThread = Thread({
            try {
                assertTrue(mainPausedInsideTopCommon.await(3, TimeUnit.SECONDS))
                personalizationEnabled.set(false)
                SettingsManager.setSuggestionsPersonalizationEnabled(context, false)
                controller.invalidatePersonalizedSuggestionsImmediately()
                assertTrue(controller.currentSuggestions().isEmpty())
                barrierCompleted.set(true)
            } catch (t: Throwable) {
                workerFailure.set(t)
            } finally {
                backgroundInvalidationCompleted.countDown()
            }
        }, "r3-3a-dismiss-off")

        try {
            controller.onCharacterCommitted("hel", null)
            val publishedOn = waitForSuggestionCandidates("hellhound", "help", "held")
            assertEquals("hellhound", publishedOn.first().candidate)
            assertTrue(publishedOn.first().score > 20.0)

            armedForDismissFill.set(true)
            restoreThread.start()
            controller.dismissSuggestion("help", hardDeleteUserWord = false)
            restoreThread.join(3000)
            assertFalse("Restore worker thread must complete", restoreThread.isAlive)
            workerFailure.get()?.let { throw AssertionError(it) }

            assertTrue(
                "Dismiss must not resurrect pre-barrier personalized suggestions in controller cache",
                controller.currentSuggestions().isEmpty()
            )
            assertTrue(
                "No listener callback after OFF barrier may receive stale personalized list from dismiss",
                listenerEventsAfterBarrier.none { list ->
                    list.any { it.candidate == "hellhound" && it.score > 20.0 }
                }
            )
            assertFalse(
                "Background OFF invalidation must never invoke listener off main thread",
                listenerCalledOffMain.get()
            )

            // Subsequent main-thread settings change clears service and publishes neutral OFF results.
            controller.onSettingsChanged(null)
            val refreshed = waitForSuggestionCandidates("help", "held", "hello")
            assertEquals(listOf("help", "held", "hello"), refreshed.map { it.candidate })
            @Suppress("UNCHECKED_CAST")
            val serviceAfterRecompute = latestServiceResultsField.get(service) as List<SuggestionResult>
            assertEquals(listOf("help", "held", "hello"), serviceAfterRecompute.map { it.candidate })
            assertTrue(
                "No listener callback after OFF barrier may ever contain stale personalized candidate",
                listenerEventsAfterBarrier.none { list ->
                    list.any { it.candidate == "hellhound" && it.score > 20.0 }
                }
            )
        } finally {
            backgroundInvalidationCompleted.countDown()
            restoreThread.join(3000)
        }
    }

    @Test
    fun dismissSuggestion_preservesNeutralMatchesWhenOff_andRetainsPersonalizedFillWhenOn() {
        val realRepo = AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        ).apply {
            isReady = true
            index(
                listOf(
                    DictionaryEntry("hello", 220, SuggestionSource.MAIN),
                    DictionaryEntry("help", 200, SuggestionSource.MAIN),
                    DictionaryEntry("held", 180, SuggestionSource.MAIN),
                    DictionaryEntry("hellhound", 250, SuggestionSource.USER)
                )
            )
            buildSymSpell()
        }

        // OFF control: dismissing one neutral match keeps the remaining neutral dictionary matches.
        SettingsManager.setSuggestionsPersonalizationEnabled(context, false)
        var personalizationEnabled = false
        val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationEnabled,
                    showAddWordButton = true,
                    maxSuggestions = 3
                )
            },
            onSuggestionsUpdated = { snapshots.add(it) },
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> realRepo },
            nextWordPredictorOverride = NextWordPredictor(store)
        ).also { controllers.add(it) }

        controller.onCharacterCommitted("hel", null)
        assertEquals(
            listOf("help", "held", "hello"),
            waitForSuggestionCandidates("help", "held", "hello").map { it.candidate }
        )
        controller.dismissSuggestion("help", hardDeleteUserWord = false)
        assertEquals(listOf("held", "hello"), controller.currentSuggestions().map { it.candidate })
        assertTrue(controller.currentSuggestions().all { it.kind == SuggestionKind.CURRENT_WORD })

        // Negative control (ON): dismissing a word while ON keeps personalized candidate and refills with starter.
        personalizationEnabled = true
        SettingsManager.setSuggestionsPersonalizationEnabled(context, true)
        controller.onContextReset()
        controller.onCharacterCommitted("hel", null)
        assertEquals(
            listOf("hellhound", "help", "held"),
            waitForSuggestionCandidates("hellhound", "help", "held").map { it.candidate }
        )
        controller.dismissSuggestion("help", hardDeleteUserWord = false)
        assertEquals("hellhound", controller.currentSuggestions().first().candidate)
        assertTrue(controller.currentSuggestions().any { it.kind == SuggestionKind.STARTER_WORD })
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
        autoReplaceOnSpaceEnter: Boolean = false,
        // Start words followed personalization before they had their own setting.
        starterWordsProvider: () -> Boolean = personalizationProvider
    ): SuggestionController {
        return SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    suggestionsEnabled = true,
                    personalizationEnabled = personalizationProvider(),
                    starterWordsEnabled = starterWordsProvider(),
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

    private class TrackingDictionaryRepository private constructor(
        private val delegate: FakeDictionaryRepository
    ) : DictionaryRepository by delegate {
        constructor() : this(FakeDictionaryRepository())

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

        override fun markUsed(word: String) {
            markedUsedWords.add(word)
            delegate.markUsed(word)
        }
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
