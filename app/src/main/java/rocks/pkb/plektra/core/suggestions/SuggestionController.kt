package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import rocks.pkb.plektra.inputmethod.AutoCorrector
import rocks.pkb.plektra.inputmethod.NotificationHelper

class SuggestionController(
    context: Context,
    private val assets: AssetManager,
    private val settingsProvider: () -> SuggestionSettings,
    private val isEnabled: () -> Boolean = { true },
    debugLogging: Boolean = false,
    private val onSuggestionsUpdated: (List<SuggestionResult>) -> Unit,
    private var currentLocale: Locale = Locale.ITALIAN,
    private val keyboardLayoutProvider: () -> String = { "qwerty" },
    private val dictionaryRepositoryFactory: ((
        Context,
        AssetManager,
        UserDictionaryStore,
        Locale,
        Boolean
    ) -> DictionaryRepository)? = null,
    nextWordPredictorOverride: NextWordPredictor? = null,
    private val activeSuggestionLocalesProvider: (() -> List<Locale>)? = null,
    // False before the user unlocks: dictionaries, learned words and n-grams live in credential storage.
    private val isStorageAvailable: () -> Boolean = { true }
) {

    private val appContext = context.applicationContext
    private val debugLogging: Boolean = debugLogging
    private val userDictionaryStore = UserDictionaryStore()
    private val dictionaryRepositoryCache = mutableMapOf<String, DictionaryRepository>()
    private var dictionaryRepository: DictionaryRepository = createDictionaryRepository(currentLocale)
    private var suggestionEngine = SuggestionEngine(dictionaryRepository, locale = currentLocale, debugLogging = debugLogging).apply {
        setKeyboardLayout(keyboardLayoutProvider())
    }
    private var tracker = CurrentWordTracker(
        onWordChanged = { word ->
            updateSuggestionsForWord(word)
        },
        onWordReset = {
            cancelPendingWordSuggestions()
            pendingAddUserWord = null
            publishEmptySuggestions()
        },
        autoSpacePunctuationProvider = { settingsProvider().autoSpacePunctuation }
    )
    private var autoReplaceController = createAutoReplaceController()
    private val nextWordPredictor = nextWordPredictorOverride ?: NextWordPredictor(UserNGramStore(appContext))
    private val extraSuggestionEngines = mutableMapOf<String, SuggestionLanguageEngine>()

    private data class SuggestionLanguageEngine(
        val locale: Locale,
        val repository: DictionaryRepository,
        val engine: SuggestionEngine
    )

    private fun createDictionaryRepository(locale: Locale): DictionaryRepository {
        val cacheKey = dictionaryCacheKey(locale)
        return dictionaryRepositoryCache.getOrPut(cacheKey) {
            dictionaryRepositoryFactory?.invoke(appContext, assets, userDictionaryStore, locale, debugLogging)
                ?: AndroidDictionaryRepository(
                appContext,
                assets,
                userDictionaryStore,
                baseLocale = locale,
                debugLogging = debugLogging
            )
        }
    }

    private fun dictionaryCacheKey(locale: Locale): String {
        return locale.language
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
            ?: locale.toLanguageTag().lowercase(Locale.ROOT)
    }

    private fun createAutoReplaceController(): AutoReplaceController {
        return AutoReplaceController(
            repository = dictionaryRepository,
            suggestionEngine = suggestionEngine,
            settingsProvider = settingsProvider,
            knownWordProvider = { word -> isKnownWordInActiveDictionaries(word) },
            accentPromotionAllowed = { word, personalized ->
                (listOf(dictionaryRepository) + activeExtraSuggestionEngines().map { it.repository })
                    .allowsAccentPromotion(word, personalized)
            },
            exactReplacementProvider = { word, boundaryChar ->
                val boundary = boundaryChar ?: ' '
                AutoCorrector.processText(
                    textBeforeCursor = word + boundary,
                    locale = currentLocale.language,
                    context = appContext,
                    isKnownWord = { candidate -> isKnownWordInActiveDictionaries(candidate) }
                )?.takeIf { (original, replacement) ->
                    original == word && replacement != word
                }?.second
            }
        )
    }
    
    /**
     * Updates the locale and reloads the dictionary for the new language.
     */
    fun reloadDictionaries() = updateLocale(currentLocale, forceReload = true)

    fun updateLocale(newLocale: Locale, forceReload: Boolean = false) {
        if (newLocale == currentLocale && !forceReload) return
        val preserveInputContext = forceReload && newLocale == currentLocale
        if (forceReload) dictionaryRepositoryCache.clear()
        
        // Cancel previous load job if still running to prevent conflicts
        currentLoadJob?.cancel()
        currentLoadJob = null
        cancelPendingWordSuggestions()
        if (!preserveInputContext) pendingInitialContextConnection = null
        pendingPrimaryRefreshAfterLoad = false
        pendingExtraRefreshAfterLoad = false
        
        currentLocale = newLocale
        dictionaryRepository = createDictionaryRepository(currentLocale)
        suggestionEngine = SuggestionEngine(dictionaryRepository, locale = currentLocale, debugLogging = debugLogging).apply {
            setKeyboardLayout(keyboardLayoutProvider())
        }
        autoReplaceController = createAutoReplaceController()
        extraSuggestionEngines.clear()
        
        if (!preserveInputContext) {
            previousCompletedWord = null
            sentenceStartPending = true
            neutralSentenceStart = false
            tracker.reset()
        }
        pendingAddUserWord = null
        publishEmptySuggestions()
        // Keep the active word and pending cursor context when only dictionary files changed.
        schedulePrimaryDictionaryLoad(refreshAfterLoad = true)
    }

    /**
     * Updates the keyboard layout for proximity-based ranking.
     */
    fun updateKeyboardLayout(layout: String) {
        suggestionEngine.setKeyboardLayout(layout)
        extraSuggestionEngines.values.forEach { it.engine.setKeyboardLayout(layout) }
    }

    private val latestSuggestions: AtomicReference<List<SuggestionResult>> = AtomicReference(emptyList())
    @Volatile private var latestSuggestionsPersonalized: Boolean = false
    // Orders final suggestion publication (cache write + listener delivery) against cross-thread
    // OFF invalidation without ever holding this lock across repository lookups or background UI calls.
    private val publishLock = Any()
    // Dedicated IO scope so dictionary preload never blocks the main thread.
    private val loadScope = CoroutineScope(Dispatchers.IO)
    private val suggestionScope = CoroutineScope(Dispatchers.Default)
    private var currentLoadJob: Job? = null
    private val suggestionJob = AtomicReference<Job?>(null)
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null
    private val cursorDebounceMs = 120L
    private var pendingAddUserWord: String? = null
    private var previousCompletedWord: String? = null
    private var pendingInitialContextConnection: InputConnection? = null
    @Volatile private var pendingPrimaryRefreshAfterLoad: Boolean = false
    @Volatile private var pendingExtraRefreshAfterLoad: Boolean = false
    private val suggestionGeneration = AtomicInteger(0)
    private var sentenceStartPending: Boolean = true
    private var neutralSentenceStart = false

    private data class PublishedSuggestionsSnapshot(
        val suggestions: List<SuggestionResult>,
        val personalized: Boolean,
        val generation: Int
    )

    private fun cancelPendingWordSuggestions() {
        val jobToCancel = synchronized(publishLock) {
            suggestionGeneration.incrementAndGet()
            suggestionJob.getAndSet(null)
        }
        jobToCancel?.cancel()
    }

    private fun publishEmptySuggestions() {
        synchronized(publishLock) {
            latestSuggestionsPersonalized = false
            latestSuggestions.set(emptyList())
            suggestionsListener?.invoke(emptyList())
        }
    }

    /**
     * Publishes start words from the bundled dictionary alone. They carry nothing learned, so the
     * personalization barriers do not apply to them.
     */
    private fun publishNeutralStarterSuggestions(generation: Int) {
        val settings = settingsProvider()
        val suggestions = if (settings.starterWordsEnabled && dictionaryRepository.isReady) {
            starterSuggestions(settings.copy(personalizationEnabled = false))
        } else {
            emptyList()
        }
        synchronized(publishLock) {
            val currentSettings = settingsProvider()
            val published = suggestions.takeIf {
                isEnabled() && currentSettings.suggestionsEnabled && currentSettings.starterWordsEnabled
            }.orEmpty()
            if (published.isNotEmpty() && generation != suggestionGeneration.get()) return
            latestSuggestionsPersonalized = false
            latestSuggestions.set(published)
            suggestionsListener?.invoke(published)
        }
    }

    private fun publishPersonalizedSuggestions(
        suggestions: List<SuggestionResult>,
        generation: Int
    ) {
        synchronized(publishLock) {
            val currentSettings = settingsProvider()
            if (
                !isEnabled() ||
                !currentSettings.suggestionsEnabled ||
                !currentSettings.personalizationEnabled
            ) {
                latestSuggestionsPersonalized = false
                latestSuggestions.set(emptyList())
                suggestionsListener?.invoke(emptyList())
                return
            }
            if (generation != suggestionGeneration.get()) {
                return
            }
            latestSuggestionsPersonalized = suggestions.isNotEmpty()
            latestSuggestions.set(suggestions)
            suggestionsListener?.invoke(suggestions)
        }
    }

    /**
     * Thread-safe immediate privacy barrier used when personalization is turned off from a
     * non-main thread (for example, live backup restore) before [onSettingsChanged] runs on main.
     * Performs no repository lookups or UI callbacks; cancels any active job outside [publishLock].
     */
    fun invalidatePersonalizedSuggestionsImmediately() {
        val jobToCancel = synchronized(publishLock) {
            suggestionGeneration.incrementAndGet()
            latestSuggestionsPersonalized = false
            latestSuggestions.set(emptyList())
            suggestionJob.getAndSet(null)
        }
        jobToCancel?.cancel()
    }

    private fun updateSuggestionsForWord(
        word: String,
        clearPublishedSuggestions: Boolean = false
    ) {
        val settings = settingsProvider()
        if (!isEnabled() || !settings.suggestionsEnabled || word.isBlank() || word.length > 48) {
            cancelPendingWordSuggestions()
            pendingAddUserWord = null
            publishEmptySuggestions()
            return
        }
        if (debugLogging) {
            Log.d("PlektraIME", "trackerWordChanged='$word' len=${word.length}")
        }

        val wordSnapshot = word
        val localeSnapshot = currentLocale
        val layoutSnapshot = keyboardLayoutProvider()
        val primaryRepository = dictionaryRepository
        // Bounded hash lookup stays strictly outside publishLock.
        val initialPendingCandidate = addWordCandidateFor(wordSnapshot, primaryRepository, settings)

        var generation = 0
        var activeSettings = settings
        var shouldLaunch = false
        var previousJob: Job? = null
        synchronized(publishLock) {
            val currentSettings = settingsProvider()
            previousJob = suggestionJob.getAndSet(null)
            generation = suggestionGeneration.incrementAndGet()
            if (!isEnabled() || !currentSettings.suggestionsEnabled) {
                pendingAddUserWord = null
                latestSuggestionsPersonalized = false
                latestSuggestions.set(emptyList())
                suggestionsListener?.invoke(emptyList())
            } else {
                activeSettings = currentSettings
                if (clearPublishedSuggestions || (!currentSettings.personalizationEnabled && latestSuggestionsPersonalized)) {
                    latestSuggestionsPersonalized = false
                    latestSuggestions.set(emptyList())
                }
                pendingAddUserWord = if (currentSettings.showAddWordButton) initialPendingCandidate else null
                suggestionsListener?.invoke(latestSuggestions.get())
                shouldLaunch = true
            }
        }
        previousJob?.cancel()
        if (!shouldLaunch) return

        val personalizedSnapshot = activeSettings.personalizationEnabled
        val extraEngines = activeExtraSuggestionEngines()
        val activeRepositories = listOf(primaryRepository) + extraEngines.map { it.repository }
        val extraRepositories = extraEngines.mapNotNull { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = true)
                null
            } else {
                extra.locale to extra.repository
            }
        }

        val launchedJob = suggestionScope.launch {
            // Coalesce key repeat/backspace bursts before doing CPU-bound dictionary work.
            delay(40)
            if (generation != suggestionGeneration.get()) return@launch
            if (personalizedSnapshot && !settingsProvider().personalizationEnabled) return@launch
            val promoteAccent = activeSettings.accentMatching && activeRepositories.allowsAccentPromotion(wordSnapshot, personalizedSnapshot)
            val primary = if (primaryRepository.isReady) {
                SuggestionEngine(primaryRepository, locale = localeSnapshot, debugLogging = debugLogging).apply {
                    setKeyboardLayout(layoutSnapshot)
                }.suggest(
                    wordSnapshot,
                    activeSettings.maxSuggestions,
                    activeSettings.accentMatching,
                    activeSettings.useKeyboardProximity,
                    activeSettings.useEditTypeRanking,
                    personalizedSnapshot,
                    allowAccentPromotion = promoteAccent
                )
            } else {
                emptyList()
            }

            coroutineContext.ensureActive()
            val extraSuggestions = extraRepositories.flatMap { (locale, repository) ->
                coroutineContext.ensureActive()
                SuggestionEngine(repository, locale = locale, debugLogging = debugLogging).apply {
                    setKeyboardLayout(layoutSnapshot)
                }.suggest(
                    wordSnapshot,
                    activeSettings.maxSuggestions,
                    activeSettings.accentMatching,
                    activeSettings.useKeyboardProximity,
                    activeSettings.useEditTypeRanking,
                    personalizedSnapshot,
                    allowAccentPromotion = promoteAccent
                )
            }

            coroutineContext.ensureActive()
            if (generation != suggestionGeneration.get()) return@launch
            if (personalizedSnapshot && !settingsProvider().personalizationEnabled) return@launch
            val next = mergeSuggestionResults(primary, extraSuggestions, activeSettings.maxSuggestions, localeSnapshot)

            cursorHandler.post {
                val preCheckSettings = settingsProvider()
                if (
                    !isEnabled() ||
                    !preCheckSettings.suggestionsEnabled ||
                    generation != suggestionGeneration.get() ||
                    tracker.currentWord != wordSnapshot ||
                    (personalizedSnapshot && !preCheckSettings.personalizationEnabled)
                ) {
                    return@post
                }
                // Repository lookup runs strictly outside publishLock.
                val pendingCandidate = addWordCandidateFor(wordSnapshot, primaryRepository, preCheckSettings)

                synchronized(publishLock) {
                    val currentSettings = settingsProvider()
                    if (
                        !isEnabled() ||
                        !currentSettings.suggestionsEnabled ||
                        generation != suggestionGeneration.get() ||
                        tracker.currentWord != wordSnapshot ||
                        (personalizedSnapshot && !currentSettings.personalizationEnabled)
                    ) {
                        return@post
                    }
                    pendingAddUserWord = if (currentSettings.showAddWordButton) pendingCandidate else null
                    latestSuggestionsPersonalized = personalizedSnapshot && next.isNotEmpty()
                    latestSuggestions.set(next)
                    suggestionsListener?.invoke(next)
                }
            }
        }
        val jobToCancel = synchronized(publishLock) {
            if (generation == suggestionGeneration.get()) {
                suggestionJob.getAndSet(launchedJob)
            } else {
                launchedJob
            }
        }
        jobToCancel?.cancel()
    }

    private fun addWordCandidateFor(
        word: String?,
        repository: DictionaryRepository = dictionaryRepository,
        settings: SuggestionSettings = settingsProvider()
    ): String? {
        if (!settings.showAddWordButton) return null
        val candidate = word?.trim() ?: return null
        if (candidate.isEmpty() || candidate.length > 48 || candidate.none { it.isLetterOrDigit() }) return null
        if (!repository.isReady) return null
        return if (repository.isKnownWord(candidate)) null else candidate
    }
    var suggestionsListener: ((List<SuggestionResult>) -> Unit)? = onSuggestionsUpdated

    /**
     * Something was typed that the word tracker follows by the cursor: a letter on a key with a
     * long-press layer, which with the default long press is nearly every letter. What a
     * replacement's undo keeps follows it here: typing accepts the last replacement, and a letter
     * lets a word whose replacement was undone be replaced again.
     */
    fun onTypingContinued(text: CharSequence) {
        if (!isEnabled()) return
        autoReplaceController.clearLastReplacement()
        if (text.any { it.isLetterOrDigit() }) {
            autoReplaceController.clearRejectedWords()
        }
    }

    fun onCharacterCommitted(text: CharSequence, inputConnection: InputConnection?) {
        if (!isEnabled()) return
        if (debugLogging) {
            val caller = Throwable().stackTrace.getOrNull(1)?.let { "${it.className}#${it.methodName}:${it.lineNumber}" }
            Log.d("PlektraIME", "SuggestionController.onCharacterCommitted('$text') caller=$caller")
        }
        ensureDictionaryLoaded()

        // Normalize curly/variant apostrophes to straight for tracking and suggestions.
        val normalizedText = text
            .toString()
            .replace("'", "'")
            .replace("'", "'")
            .replace("ʼ", "'")
        
        // Clear last replacement if user types new characters
        autoReplaceController.clearLastReplacement()
        
        // Clear rejected words when user types a new letter (allows re-correction)
        if (normalizedText.isNotEmpty() && normalizedText.any { it.isLetterOrDigit() }) {
            autoReplaceController.clearRejectedWords()
            pendingAddUserWord = null
            neutralSentenceStart = false
        }
        
        tracker.onCharacterCommitted(normalizedText)
    }

    fun refreshFromInputConnection(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        tracker.onBackspace()
    }

    fun onBoundaryKey(
        keyCode: Int,
        event: KeyEvent?,
        inputConnection: InputConnection?,
        boundaryCharOverride: Char? = null
    ): AutoReplaceController.ReplaceResult {
        if (debugLogging) {
            Log.d(
                "PlektraIME",
                "SuggestionController.onBoundaryKey keyCode=$keyCode char=${event?.unicodeChar}"
            )
        }
        ensureDictionaryLoaded()

        // Exact text replacements do not depend on dictionary suggestions. In particular,
        // onCharacterCommitted intentionally does not track characters while experimental
        // suggestions are disabled, so the editor is the source of truth at a boundary.
        // Keep this synchronous and independent of repository readiness: waiting for an
        // asynchronous dictionary load would lose the boundary that triggered the replacement.
        if (inputConnection != null) {
            val word = extractWordAtCursor(inputConnection, includeAfterCursor = false)
            if (!word.isNullOrBlank()) {
                tracker.setWord(word, notify = false)
                Log.d("PlektraIME", "SYNC: Synced tracker to actual word='$word' before boundary")
            }
        }

        val boundaryChar = boundaryCharOverride ?: boundaryCharFor(keyCode, event)
        val wordBeforeBoundary = tracker.currentWord.takeIf { it.isNotBlank() }
        val result = autoReplaceController.handleBoundary(
            keyCode,
            event,
            tracker,
            inputConnection,
            boundaryCharOverride = boundaryChar
        )
        val completedWord = result.replacement ?: wordBeforeBoundary
        if (result.replaced) {
            pendingAddUserWord = addWordCandidateFor(result.replacement)
            NotificationHelper.triggerHapticFeedback(appContext)
        } else {
            pendingAddUserWord = null
        }
        handleCompletedWordBoundary(completedWord, boundaryChar, inputConnection)
        return result
    }

    /**
     * Reads the word at cursor immediately without debounce.
     * Use this when entering a text field to show suggestions right away.
     * If the dictionary is still loading, reads the latest context once it is ready.
     */
    fun readInitialContext(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        if (inputConnection == null) return
        if (!dictionaryRepository.isReady) {
            pendingInitialContextConnection = inputConnection
            ensureDictionaryLoaded(refreshAfterLoad = true)
            return
        }
        publishInitialContext(inputConnection)
    }

    private fun publishInitialContext(inputConnection: InputConnection): Boolean {
        val word = extractWordAtCursor(inputConnection)
        if (!word.isNullOrBlank()) {
            tracker.setWord(word)
        } else if (previousCompletedWord == null) {
            publishSentenceStartPredictionsOrStarter(inputConnection)
        } else {
            return false
        }
        return true
    }

    fun onCursorMoved(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        ensureDictionaryLoaded()
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        if (inputConnection == null) {
            pendingInitialContextConnection = null
            tracker.reset()
            previousCompletedWord = null
            sentenceStartPending = true
            neutralSentenceStart = false
            publishEmptySuggestions()
            return
        }
        cursorRunnable = Runnable {
            if (!isEnabled()) return@Runnable
            if (!dictionaryRepository.isReady) {
                tracker.reset()
                previousCompletedWord = null
                pendingInitialContextConnection = inputConnection
                ensureDictionaryLoaded(refreshAfterLoad = true)
                publishEmptySuggestions()
                return@Runnable
            }
            val word = extractWordAtCursor(inputConnection)
            if (!word.isNullOrBlank()) {
                tracker.setWord(word)
            } else {
                tracker.reset()
                val previous = previousCompletedWord
                val lastChar = lastCharBeforeCursor(inputConnection)
                if (previous != null && isSoftPredictionBoundary(lastChar)) {
                    publishNextWordPredictions(previous)
                } else {
                    previousCompletedWord = null
                    sentenceStartPending = true
                    publishSentenceStartPredictionsOrStarter(inputConnection)
                }
            }
        }
        cursorHandler.postDelayed(cursorRunnable!!, cursorDebounceMs)
    }

    fun onContextReset() {
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        cursorRunnable = null
        pendingInitialContextConnection = null
        cancelPendingWordSuggestions()
        tracker.onContextChanged()
        pendingAddUserWord = null
        previousCompletedWord = null
        sentenceStartPending = true
        neutralSentenceStart = false
        publishEmptySuggestions()
    }

    fun onNavModeToggle() {
        if (!isEnabled()) return
        tracker.onContextChanged()
        previousCompletedWord = null
        sentenceStartPending = true
        neutralSentenceStart = false
    }

    fun addUserWord(word: String) {
        if (!isEnabled()) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.addUserEntryQuick(word) }
    }

    fun removeUserWord(word: String) {
        if (!isEnabled()) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.removeUserEntry(word) }
        refreshUserDictionary()
    }

    fun markUsed(word: String) {
        if (!isEnabled() || !settingsProvider().personalizationEnabled) return
        dictionaryRepositoryCache.values
            .ifEmpty { listOf(dictionaryRepository) }
            .forEach { repository -> repository.markUsed(word) }
    }

    fun isKnownWordInActiveDictionaries(word: String): Boolean {
        if (!isEnabled()) return false
        val candidate = word.trim()
        if (candidate.isEmpty()) return false

        ensureDictionaryLoaded()
        if (dictionaryRepository.isReady && dictionaryRepository.isKnownWord(candidate)) {
            return true
        }

        return activeExtraSuggestionEngines().any { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = false)
                // Defer legacy auto-substitution while an explicitly active
                // extra dictionary is still loading; wrong replacements are
                // worse than skipping one boundary.
                true
            } else {
                extra.repository.isKnownWord(candidate)
            }
        }
    }

    fun currentSuggestions(): List<SuggestionResult> = latestSuggestions.get()

    fun userDictionarySnapshot(): List<UserDictionaryStore.UserEntry> = userDictionaryStore.getSnapshot()

    fun dismissSuggestion(candidate: String, hardDeleteUserWord: Boolean = false) {
        if (!isEnabled()) return
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return

        // Capture published list, provenance, and generation atomically before leaving publishLock.
        val snapshot = synchronized(publishLock) {
            PublishedSuggestionsSnapshot(
                suggestions = latestSuggestions.get(),
                personalized = latestSuggestionsPersonalized,
                generation = suggestionGeneration.get()
            )
        }
        val suggestion = snapshot.suggestions.firstOrNull { it.candidate.equals(trimmed, ignoreCase = true) }
        if (suggestion?.kind == SuggestionKind.NEXT_WORD) {
            forgetNextWordSuggestion(trimmed, hardDeleteEverywhere = hardDeleteUserWord)
        }
        if (hardDeleteUserWord) {
            removeUserWord(trimmed)
            activeExtraSuggestionEngines().forEach { extra ->
                if (extra.repository.isReady) {
                    extra.repository.removeUserEntry(trimmed)
                }
            }
        }

        val remaining = snapshot.suggestions.filterNot { it.candidate.equals(trimmed, ignoreCase = true) }
        val settings = settingsProvider()
        val nextWithStarters = if (settings.personalizationEnabled) {
            fillWithStarterSuggestions(
                remaining,
                settings,
                excludedCandidates = setOf(trimmed)
            )
        } else {
            remaining
        }
        val nextIsPersonalized = snapshot.personalized ||
            nextWithStarters.any { it.kind != SuggestionKind.CURRENT_WORD }

        synchronized(publishLock) {
            val currentSettings = settingsProvider()
            if (!isEnabled() || !currentSettings.suggestionsEnabled) {
                latestSuggestionsPersonalized = false
                latestSuggestions.set(emptyList())
                suggestionsListener?.invoke(emptyList())
                return
            }
            if (snapshot.generation != suggestionGeneration.get()) {
                // Superseded by concurrent OFF invalidation, settings change, context reset,
                // or newer word update; do not resurrect the pre-barrier snapshot.
                return
            }
            if (!currentSettings.personalizationEnabled) {
                if (snapshot.personalized) {
                    // Snapshot originated from a personalized computation; clear rather than
                    // republishing ON-ranked CURRENT_WORD items under OFF.
                    latestSuggestionsPersonalized = false
                    latestSuggestions.set(emptyList())
                    suggestionsListener?.invoke(emptyList())
                    return
                }
                val neutralRemaining = remaining.filter(SuggestionResult::isNeutral)
                latestSuggestionsPersonalized = false
                latestSuggestions.set(neutralRemaining)
                suggestionsListener?.invoke(neutralRemaining)
                return
            }
            latestSuggestionsPersonalized = nextIsPersonalized && nextWithStarters.isNotEmpty()
            latestSuggestions.set(nextWithStarters)
            suggestionsListener?.invoke(nextWithStarters)
        }
    }

    private fun forgetNextWordSuggestion(candidate: String, hardDeleteEverywhere: Boolean) {
        val locales = listOf(currentLocale) + activeExtraLocales()
        locales.forEach { locale ->
            if (hardDeleteEverywhere) {
                nextWordPredictor.forgetNextWordEverywhere(locale, candidate)
            } else {
                val previous = previousCompletedWord
                if (previous != null) {
                    nextWordPredictor.forget(locale, previous, candidate)
                } else {
                    nextWordPredictor.forgetSentenceStart(locale, candidate)
                }
            }
        }
    }

    /**
     * Forces a refresh of user dictionary entries.
     * Should be called when words are added/removed from settings.
     */
    fun refreshUserDictionary() {
        if (!isEnabled()) return
        loadScope.launch {
            try {
                dictionaryRepository.refreshUserEntries()
            } catch (_: CancellationException) {
                // Cancelled due to rapid switches; safe to ignore.
            } catch (e: Exception) {
                Log.e("PlektraIME", "Failed to refresh user dictionary", e)
            }
        }
    }

    fun handleBackspaceUndo(keyCode: Int, inputConnection: InputConnection?): Boolean {
        if (!isEnabled()) return false
        val undone = autoReplaceController.handleBackspaceUndo(keyCode, inputConnection)
        if (undone) {
            val original = autoReplaceController.consumeLastUndoOriginalWord()
            pendingAddUserWord = if (settingsProvider().showAddWordButton) original else null
        }
        return undone
    }

    fun pendingAddWord(): String? = if (settingsProvider().showAddWordButton) pendingAddUserWord else null
    fun clearPendingAddWord() {
        pendingAddUserWord = null
    }

    /**
     * Re-evaluates active suggestions and pending add-word state after suggestion settings change
     * (e.g., toggling personalization or the add-word button, or restoring a backup).
     * Stored dictionary and n-gram data are preserved untouched.
     */
    fun onSettingsChanged(inputConnection: InputConnection? = null) {
        cancelPendingWordSuggestions()
        val settings = settingsProvider()
        if (!isEnabled() || !settings.suggestionsEnabled) {
            pendingAddUserWord = null
            previousCompletedWord = null
            publishEmptySuggestions()
            return
        }
        if (!settings.personalizationEnabled) {
            previousCompletedWord = null
            synchronized(publishLock) {
                latestSuggestionsPersonalized = false
                latestSuggestions.set(emptyList())
            }
        }
        ensureDictionaryLoaded(refreshAfterLoad = true)
        val activeWord = tracker.currentWord.takeIf { it.isNotBlank() }
            ?: extractWordAtCursor(inputConnection)?.takeIf { it.isNotBlank() }
        if (activeWord != null) {
            if (tracker.currentWord != activeWord) {
                tracker.setWord(activeWord, notify = false)
            }
            updateSuggestionsForWord(
                activeWord,
                clearPublishedSuggestions = !settings.personalizationEnabled
            )
        } else {
            pendingAddUserWord = null
            if (!settings.personalizationEnabled) {
                publishSentenceStartPredictionsOrStarter(inputConnection)
            } else if (previousCompletedWord != null) {
                publishNextWordPredictions(previousCompletedWord!!)
            } else if (dictionaryRepository.isReady) {
                publishSentenceStartPredictionsOrStarter()
            } else {
                publishEmptySuggestions()
            }
        }
    }

    internal fun clearLearnedNextWordsForTests() {
        nextWordPredictor.clearAll()
        previousCompletedWord = null
    }

    internal fun flushNextWordLearningForTests() {
        nextWordPredictor.flushLearningForTests()
    }

    fun destroy() {
        currentLoadJob?.cancel()
        suggestionJob.getAndSet(null)?.cancel()
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        nextWordPredictor.destroy()
    }

    private fun handleCompletedWordBoundary(
        completedWord: String?,
        boundaryChar: Char?,
        inputConnection: InputConnection?
    ) {
        val settings = settingsProvider()
        if (!isEnabled() || !settings.suggestionsEnabled || !isStorageAvailable()) {
            previousCompletedWord = null
            publishEmptySuggestions()
            return
        }

        if (!settings.personalizationEnabled) {
            previousCompletedWord = null
            neutralSentenceStart = when {
                isSentenceEnd(boundaryChar) -> true
                isSentencePadding(boundaryChar) -> completedWord.isNullOrBlank() &&
                    (sentenceStartAt(inputConnection) ?: neutralSentenceStart)
                else -> false
            }
            sentenceStartPending = neutralSentenceStart
            if (neutralSentenceStart && settings.starterWordsEnabled) ensureDictionaryLoaded(refreshAfterLoad = true)
            publishSentenceStartPredictionsOrStarter()
            return
        }

        val cleanWord = completedWord?.trim()?.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
        if (cleanWord != null) {
            if (sentenceStartPending) {
                nextWordPredictor.learnSentenceStart(currentLocale, cleanWord)
            }
            previousCompletedWord?.let { previous ->
                nextWordPredictor.learn(currentLocale, previous, cleanWord)
            }
        }

        when {
            cleanWord != null && isSoftPredictionBoundary(boundaryChar) -> {
                previousCompletedWord = cleanWord
                sentenceStartPending = false
                publishNextWordPredictions(cleanWord)
            }
            cleanWord == null && isSoftPredictionBoundary(boundaryChar) -> {
                val previous = previousCompletedWord
                if (previous != null) {
                    publishNextWordPredictions(previous)
                } else {
                    publishEmptySuggestions()
                }
            }
            else -> {
                previousCompletedWord = null
                sentenceStartPending = true
                publishEmptySuggestions()
            }
        }
    }

    private fun publishNextWordPredictions(previousWord: String) {
        val generation = suggestionGeneration.get()
        val settings = settingsProvider()
        if (!settings.suggestionsEnabled || !settings.personalizationEnabled) {
            publishEmptySuggestions()
            return
        }
        val primary = nextWordPredictor.predict(
            currentLocale,
            previousWord,
            settings.maxSuggestions
        )
        val extras = activeExtraLocales().flatMap { locale ->
            nextWordPredictor.predict(locale, previousWord, settings.maxSuggestions)
        }
        val predictions = mergeSuggestionResults(primary, extras, settings.maxSuggestions)
        val suggestions = fillWithStarterSuggestions(predictions, settings)
        if (suggestions.isNotEmpty()) {
            publishPersonalizedSuggestions(suggestions, generation)
        } else {
            publishStarterSuggestions(generation)
        }
    }

    private fun publishSentenceStartPredictionsOrStarter(inputConnection: InputConnection? = null) {
        val generation = suggestionGeneration.get()
        val settings = settingsProvider()
        if (!settings.suggestionsEnabled) {
            publishEmptySuggestions()
            return
        }
        if (!settings.personalizationEnabled) {
            if (inputConnection != null) {
                neutralSentenceStart = sentenceStartAt(inputConnection) == true
                sentenceStartPending = neutralSentenceStart
            }
            if (neutralSentenceStart) publishNeutralStarterSuggestions(generation) else publishEmptySuggestions()
            return
        }
        val primary = nextWordPredictor.predictSentenceStart(currentLocale, settings.maxSuggestions)
        val extras = activeExtraLocales().flatMap { locale ->
            nextWordPredictor.predictSentenceStart(locale, settings.maxSuggestions)
        }
        val predictions = mergeSuggestionResults(primary, extras, settings.maxSuggestions)
        val suggestions = fillWithStarterSuggestions(predictions, settings)
        if (suggestions.isNotEmpty()) {
            publishPersonalizedSuggestions(suggestions, generation)
        } else {
            publishStarterSuggestions(generation)
        }
    }

    private fun publishStarterSuggestions() {
        publishStarterSuggestions(suggestionGeneration.get())
    }

    private fun publishStarterSuggestions(generation: Int) {
        val settings = settingsProvider()
        if (!settings.suggestionsEnabled || !settings.personalizationEnabled || !dictionaryRepository.isReady) {
            publishEmptySuggestions()
            return
        }

        val suggestions = starterSuggestions(settings)
        publishPersonalizedSuggestions(suggestions, generation)
    }

    private fun starterSuggestions(settings: SuggestionSettings): List<SuggestionResult> {
        if (!settings.starterWordsEnabled) return emptyList()
        val personalized = settings.personalizationEnabled
        val primary = starterSuggestionsFor(dictionaryRepository, PRIMARY_SUGGESTION_BOOST, settings.maxSuggestions, personalized)
        val extras = activeExtraSuggestionEngines().flatMap { extra ->
            if (!extra.repository.isReady) {
                scheduleRepositoryLoad(extra.repository, refreshAfterLoad = true)
                emptyList()
            } else {
                starterSuggestionsFor(extra.repository, 0.0, settings.maxSuggestions, personalized)
            }
        }
        return mergeSuggestionResults(primary, extras, settings.maxSuggestions)
    }

    private fun fillWithStarterSuggestions(
        predictions: List<SuggestionResult>,
        settings: SuggestionSettings
    ): List<SuggestionResult> {
        if (predictions.size >= settings.maxSuggestions) return predictions.take(settings.maxSuggestions)

        val seen = predictions
            .mapTo(HashSet()) { it.candidate.lowercase(currentLocale) }
        val fillers = starterSuggestions(settings)
            .filter { seen.add(it.candidate.lowercase(currentLocale)) }
        return (predictions + fillers).take(settings.maxSuggestions)
    }

    private fun fillWithStarterSuggestions(
        predictions: List<SuggestionResult>,
        settings: SuggestionSettings,
        excludedCandidates: Set<String>
    ): List<SuggestionResult> {
        if (predictions.size >= settings.maxSuggestions) return predictions.take(settings.maxSuggestions)

        val excluded = excludedCandidates.mapTo(HashSet()) { it.lowercase(currentLocale) }
        val seen = predictions
            .mapTo(HashSet()) { it.candidate.lowercase(currentLocale) }
        val fillers = starterSuggestions(settings)
            .filter { result ->
                val key = result.candidate.lowercase(currentLocale)
                key !in excluded && seen.add(key)
            }
        return (predictions + fillers).take(settings.maxSuggestions)
    }

    private fun starterSuggestionsFor(
        repository: DictionaryRepository,
        scoreBoost: Double,
        limit: Int,
        usePersonalization: Boolean
    ): List<SuggestionResult> {
        return repository.topCommonEntries(limit * 3, usePersonalization)
            .map { entry ->
                SuggestionResult(
                    candidate = entry.word,
                    distance = 0,
                    score = repository.effectiveFrequency(entry) / 1_600.0 + scoreBoost +
                        if (entry.source == SuggestionSource.USER) 5.0 else 0.0,
                    source = entry.source,
                    kind = SuggestionKind.STARTER_WORD
                )
            }
            .take(limit)
    }

    private fun mergeSuggestionResults(
        primary: List<SuggestionResult>,
        extras: List<SuggestionResult>,
        limit: Int,
        locale: Locale = currentLocale
    ): List<SuggestionResult> {
        val seen = HashSet<String>()
        return (primary.map { it to PRIMARY_SUGGESTION_BOOST } + extras.map { it to 0.0 })
            .sortedWith(
                compareByDescending<Pair<SuggestionResult, Double>> { (result, boost) ->
                    result.score + boost
                }.thenBy { (result, _) -> result.candidate.length }
            )
            .map { it.first }
            .filter { result -> seen.add(result.candidate.lowercase(locale)) }
            .take(limit)
    }

    private fun activeExtraLocales(): List<Locale> {
        val primaryLanguage = currentLocale.language.lowercase(Locale.ROOT)
        return activeSuggestionLocalesProvider?.invoke().orEmpty()
            .filter { it.language.isNotBlank() }
            .filter { it.language.lowercase(Locale.ROOT) != primaryLanguage }
            .distinctBy { it.toLanguageTag().lowercase(Locale.ROOT) }
    }

    private fun activeExtraSuggestionEngines(): List<SuggestionLanguageEngine> {
        val activeLocales = activeExtraLocales()
        val activeTags = activeLocales.map { it.toLanguageTag() }.toSet()
        extraSuggestionEngines.keys
            .filterNot { it in activeTags }
            .forEach { extraSuggestionEngines.remove(it) }
        return activeLocales.map { locale ->
            val tag = locale.toLanguageTag()
            extraSuggestionEngines.getOrPut(tag) {
                val repository = createDictionaryRepository(locale)
                val engine = SuggestionEngine(repository, locale = locale, debugLogging = debugLogging).apply {
                    setKeyboardLayout(keyboardLayoutProvider())
                }
                SuggestionLanguageEngine(locale, repository, engine)
            }
        }
    }

    private fun scheduleRepositoryLoad(repository: DictionaryRepository, refreshAfterLoad: Boolean) {
        if (!isStorageAvailable()) return
        if (refreshAfterLoad) {
            pendingExtraRefreshAfterLoad = true
        }
        if (!repository.isReady && !repository.isLoadStarted) {
            loadScope.launch {
                try {
                    repository.loadIfNeeded()
                    val shouldRefresh = refreshAfterLoad || pendingExtraRefreshAfterLoad
                    if (shouldRefresh && repository.isReady) {
                        pendingExtraRefreshAfterLoad = false
                        cursorHandler.post {
                            val word = tracker.currentWord
                            if (word.isNotBlank()) {
                                updateSuggestionsForWord(word)
                            } else if (previousCompletedWord == null) {
                                publishSentenceStartPredictionsOrStarter()
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Cancelled due to rapid switches; safe to ignore.
                } catch (e: Exception) {
                    Log.e("PlektraIME", "Failed to load extra dictionary", e)
                }
            }
        }
    }

    private fun isSoftPredictionBoundary(boundaryChar: Char?): Boolean {
        return boundaryChar == ' ' || boundaryChar == ',' || boundaryChar == ';' || boundaryChar == ':'
    }

    private fun boundaryCharFor(keyCode: Int, event: KeyEvent?): Char? {
        val unicodeChar = event?.unicodeChar ?: 0
        return when {
            unicodeChar != 0 -> unicodeChar.toChar()
            keyCode == KeyEvent.KEYCODE_SPACE -> ' '
            keyCode == KeyEvent.KEYCODE_ENTER -> '\n'
            keyCode == KeyEvent.KEYCODE_COMMA -> ','
            keyCode == KeyEvent.KEYCODE_SEMICOLON -> ';'
            keyCode == KeyEvent.KEYCODE_PERIOD -> '.'
            keyCode == KeyEvent.KEYCODE_SLASH -> '/'
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> '['
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'
            keyCode == KeyEvent.KEYCODE_BACKSLASH -> '\\'
            else -> KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, 0)
                .takeIf { it != 0 }
                ?.toChar()
        }
    }

    /** True at an empty field or after a sentence end; null when the editor text is unavailable. */
    private fun sentenceStartAt(inputConnection: InputConnection?): Boolean? {
        val before = try {
            inputConnection?.getTextBeforeCursor(SENTENCE_CONTEXT_CHARS, 0)
        } catch (_: Exception) {
            null
        } ?: return null
        val last = before.trimEnd(::isSentencePadding).lastOrNull()
        return if (last == null) before.length < SENTENCE_CONTEXT_CHARS else isSentenceEnd(last)
    }

    private fun isSentenceEnd(char: Char?): Boolean = char != null && char in SENTENCE_ENDS

    private fun isSentencePadding(char: Char?): Boolean = char == ' ' || char == '\t' || char == '\u00A0'

    private fun lastCharBeforeCursor(inputConnection: InputConnection?): Char? {
        return try {
            inputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clears the pending add-word candidate if the cursor is no longer on that word.
     * Keeps the candidate only while the cursor remains on the originating token.
     */
    fun clearPendingAddWordIfCursorOutside(inputConnection: InputConnection?) {
        val pending = pendingAddUserWord ?: return
        val currentWord = extractWordAtCursor(inputConnection)
        if (currentWord == null || !currentWord.equals(pending, ignoreCase = true)) {
            pendingAddUserWord = null
        }
    }

    private fun extractWordAtCursor(
        inputConnection: InputConnection?,
        includeAfterCursor: Boolean = true
    ): String? {
        if (inputConnection == null) return null
        return try {
            val before = inputConnection.getTextBeforeCursor(CURSOR_WORD_CONTEXT_CHARS, 0)?.toString() ?: ""
            val after = if (includeAfterCursor) {
                inputConnection.getTextAfterCursor(CURSOR_WORD_CONTEXT_CHARS, 0)?.toString() ?: ""
            } else {
                ""
            }
            var start = before.length
            while (start > 0) {
                val ch = before[start - 1]
                val prev = before.getOrNull(start - 2)
                val next = before.getOrNull(start)
                if (!rocks.pkb.plektra.core.Punctuation.isWordBoundary(ch, prev, next)) {
                    start--
                    continue
                }
                break
            }
            var end = 0
            if (includeAfterCursor) {
                while (end < after.length) {
                    val ch = after[end]
                    val prev = if (end == 0) before.lastOrNull() else after[end - 1]
                    val next = after.getOrNull(end + 1)
                    if (!rocks.pkb.plektra.core.Punctuation.isWordBoundary(ch, prev, next)) {
                        end++
                        continue
                    }
                    break
                }
            }
            val word = before.substring(start) + after.substring(0, end)
            if (word.isBlank()) null else word
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Preloads the dictionary in background.
     * Should be called during initialization to have dictionary ready when user focuses a field.
     */
    fun preloadDictionary() {
        schedulePrimaryDictionaryLoad(refreshAfterLoad = false)
        activeExtraSuggestionEngines().forEach { scheduleRepositoryLoad(it.repository, refreshAfterLoad = false) }
    }

    private fun ensureDictionaryLoaded(refreshAfterLoad: Boolean = false) {
        if (!dictionaryRepository.isReady) {
            schedulePrimaryDictionaryLoad(refreshAfterLoad)
        }
    }

    private fun schedulePrimaryDictionaryLoad(refreshAfterLoad: Boolean) {
        if (!isStorageAvailable()) return
        val repository = dictionaryRepository
        if (refreshAfterLoad) {
            pendingPrimaryRefreshAfterLoad = true
        }
        if (repository.isReady) {
            if (refreshAfterLoad) {
                pendingPrimaryRefreshAfterLoad = false
                cursorHandler.post { refreshSuggestionsAfterDictionaryReady(repository) }
            }
            return
        }
        if (repository.isLoadStarted) return

        currentLoadJob = loadScope.launch {
            try {
                repository.loadIfNeeded()
                val shouldRefresh = refreshAfterLoad || pendingPrimaryRefreshAfterLoad
                if (shouldRefresh && repository.isReady) {
                    pendingPrimaryRefreshAfterLoad = false
                    cursorHandler.post { refreshSuggestionsAfterDictionaryReady(repository) }
                }
            } catch (_: CancellationException) {
                // Cancelled due to rapid switches; safe to ignore.
            } catch (e: Exception) {
                Log.e("PlektraIME", "Failed to load dictionary", e)
            }
        }
    }

    private fun refreshSuggestionsAfterDictionaryReady(repository: DictionaryRepository) {
        if (!isEnabled() || repository !== dictionaryRepository || !repository.isReady) return

        pendingInitialContextConnection?.let { inputConnection ->
            pendingInitialContextConnection = null
            if (publishInitialContext(inputConnection)) return
        }

        val word = tracker.currentWord
        val previous = previousCompletedWord
        if (word.isNotBlank()) {
            updateSuggestionsForWord(word)
        } else if (previous == null) {
            publishSentenceStartPredictionsOrStarter()
        } else {
            publishNextWordPredictions(previous)
        }
    }

    companion object {
        private const val CURSOR_WORD_CONTEXT_CHARS = 128
        private const val PRIMARY_SUGGESTION_BOOST = 0.35
        private const val SENTENCE_CONTEXT_CHARS = 64
        private val SENTENCE_ENDS = setOf('.', '!', '?', '…', '\n')
    }
}
