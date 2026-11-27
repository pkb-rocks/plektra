package rocks.pkb.plektra.core.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SuggestionEngineTest {

    @Test
    fun overlongInputSkipsDictionaryLookups() {
        val repository = object : DictionaryRepository by FakeDictionaryRepository() {
            override val isReady = true
            override fun lookupByPrefixMerged(prefix: String, maxSize: Int): List<DictionaryEntry> =
                error("Overlong input must not search dictionary prefixes")
            override fun lookupByPrefixMerged(
                prefix: String,
                maxSize: Int,
                usePersonalization: Boolean
            ): List<DictionaryEntry> =
                error("Overlong input must not search dictionary prefixes")
            override fun symSpellLookup(term: String, maxSuggestions: Int): List<SymSpell.SuggestItem> =
                error("Overlong input must not calculate edit distances")
            override fun symSpellLookup(
                term: String,
                maxSuggestions: Int,
                usePersonalization: Boolean
            ): List<SymSpell.SuggestItem> =
                error("Overlong input must not calculate edit distances")
        }
        assertTrue(SuggestionEngine(repository).suggest("a".repeat(1000)).isEmpty())
        assertTrue(SuggestionEngine(repository).suggest("a".repeat(1000), usePersonalization = false).isEmpty())
    }


    private val locale = Locale.ITALIAN
    private val fakeRepo = FakeDictionaryRepository()
    private val engine = SuggestionEngine(fakeRepo, locale = locale)

    @Test
    fun testIsReadyCheck() {
        fakeRepo.addTestEntry("hallo", 100)
        fakeRepo.isReady = false
        
        val results = engine.suggest("hallo")
        assertTrue("Sollte leere Liste zurückgeben, wenn Repo nicht bereit ist", results.isEmpty())
        
        fakeRepo.isReady = true
        val resultsReady = engine.suggest("hall") // Prefix search
        assertTrue("Sollte Ergebnisse liefern, wenn Repo bereit ist", resultsReady.isNotEmpty())
    }

    @Test
    fun testKeyboardProximity_QWERTY() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("hallo", 200)
        engine.setKeyboardLayout("qwerty")

        // 'hsllo' (S ist neben A -> Distanz 1.0)
        val resultsNear = engine.suggest("hsllo")
        val scoreNear = resultsNear.find { it.candidate == "hallo" }?.score ?: 0.0

        // 'hmllo' (M ist weit weg von A -> Distanz ~8.0)
        val resultsFar = engine.suggest("hmllo")
        val scoreFar = resultsFar.find { it.candidate == "hallo" }?.score ?: 0.0

        assertTrue("Nahgelegene Taste ($scoreNear) sollte höheren Score haben als ferne Taste ($scoreFar)", scoreNear > scoreFar)
    }

    @Test
    fun testProximityFiltering() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("hallo", 200)
        engine.setKeyboardLayout("qwerty")

        // 'hmllo' -> 'hallo' ist eine Substitution von 'm' für 'a'. 
        // Distanz ist > 2.5, sollte also gefiltert werden, wenn proximity aktiv ist.
        val results = engine.suggest("hmllo", useKeyboardProximity = true)
        assertTrue("Sollte weit entfernte Substitution 'm' -> 'a' filtern", results.none { it.candidate == "hallo" })
        
        // Ohne Proximity-Filterung sollte es gefunden werden
        val resultsNoFilter = engine.suggest("hmllo", useKeyboardProximity = false)
        assertTrue("Sollte ohne Proximity-Filterung gefunden werden", resultsNoFilter.any { it.candidate == "hallo" })
    }

    @Test
    fun testAccentMatching() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("perché", 200)
        
        // User tippt "perche" (ohne Akzent)
        val results = engine.suggest("perche", includeAccentMatching = true)
        assertEquals("Sollte 'perché' als Top-Vorschlag finden", "perché", results.firstOrNull()?.candidate)
    }

    /** Frequencies from fr_base: "très" 169 ranked below the completion "trésor" 120 before. */
    @Test
    fun unknownWordPutsItsAccentOnlyFormAheadOfLongerCompletions() {
        val repo = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("très", 169)
            addTestEntry("trésor", 120)
            addTestEntry("tresse", 80)
        }
        val results = SuggestionEngine(repo, locale = Locale.FRENCH).suggest("tres", limit = 3)
        assertEquals(listOf("très", "trésor", "tresse"), results.map { it.candidate })
    }

    /** "fête" was missing entirely: completions and the bonus-carrying "fêté" pushed it out. */
    @Test
    fun theMostFrequentAccentOnlyFormLeadsLikeAutoCorrection() {
        val repo = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("fête", 135)
            addTestEntry("fêté", 109)
            addTestEntry("fêtes", 127)
            addTestEntry("fêter", 104)
            addTestEntry("fêtée", 92)
        }
        val results = SuggestionEngine(repo, locale = Locale.FRENCH).suggest("fete", limit = 3)
        assertEquals("fête", results.first().candidate)
        assertEquals(
            AutoReplaceController.orthographicVariant(repo, "fete", usePersonalization = true)?.word,
            results.first().candidate
        )
    }

    @Test
    fun knownWordsAndDisabledAccentMatchingKeepTheirRanking() {
        val repo = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("wurde", 195)
            addTestEntry("würde", 143)
            addTestEntry("wurden", 179)
            addTestEntry("würden", 134)
        }
        val known = SuggestionEngine(repo, locale = Locale.GERMAN).suggest("wurde", limit = 3)
        assertTrue(known.first().candidate != "würde")

        repo.addTestEntry("très", 169)
        repo.addTestEntry("trésor", 120)
        val disabled = SuggestionEngine(repo, locale = Locale.FRENCH)
            .suggest("tres", limit = 3, includeAccentMatching = false)
        assertEquals("trésor", disabled.first().candidate)
    }

    @Test
    fun completeFrenchElisionsKeepTheirUnboostedRanking() {
        for ((root, accented) in listOf("amuse" to "amusé", "incline" to "incliné", "exile" to "exilé")) {
            val word = "s’$root"
            val repo = FakeDictionaryRepository().apply {
                isReady = true
                addTestEntry(word, 150)
                addTestEntry(accented, 120)
                addTestEntry(root + "r", 200)
            }
            val french = SuggestionEngine(repo, locale = Locale.FRENCH)
            assertEquals(french.suggest(word, allowAccentPromotion = false), french.suggest(word))
        }
    }

    @Test
    fun englishFurPreventsTheGermanAccentBonus() {
        val english = FakeDictionaryRepository().apply { isReady = true; addTestEntry("fur", 130) }
        val german = FakeDictionaryRepository().apply {
            isReady = true
            addTestEntry("für", 210)
            addTestEntry("furchtbar", 130)
        }
        val germanEngine = SuggestionEngine(german, locale = Locale.GERMAN)
        val promote = listOf(english, german).allowsAccentPromotion("fur", false)
        assertTrue(!promote)
        assertEquals(germanEngine.suggest("fur", allowAccentPromotion = false),
            germanEngine.suggest("fur", allowAccentPromotion = promote))
        assertEquals("für", germanEngine.suggest("fur").first().candidate)
    }

    @Test
    fun accentlessFrenchWordSuggestsAccentedDictionaryEntry() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("derrière", 200)

        val results = engine.suggest("derriere", includeAccentMatching = true)

        assertEquals("derrière", results.firstOrNull()?.candidate)
        assertEquals(0, results.firstOrNull()?.distance)
    }

    @Test
    fun testCaseOnlyVariantIsSuggestedForLowercaseInput() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("Problem", 200)
        fakeRepo.addTestEntry("Probleme", 250)

        val results = engine.suggest("problem")

        assertTrue("Sollte gleichlange Case-Variante 'Problem' vorschlagen", results.any { it.candidate == "Problem" })
    }

    @Test
    fun testUserDictionaryRanking() {
        fakeRepo.isReady = true
        // "hallo" im Hauptwörterbuch
        fakeRepo.addTestEntry("hallo", 100, SuggestionSource.MAIN)
        // "hallx" im User-Wörterbuch
        fakeRepo.addTestEntry("hallx", 100, SuggestionSource.USER)
        
        val results = engine.suggest("hall")
        
        assertEquals("User-Wort sollte an erster Stelle stehen", "hallx", results.firstOrNull()?.candidate)
        assertEquals("User-Wort sollte als USER gekennzeichnet sein", SuggestionSource.USER, results.first().source)
    }

    @Test
    fun testDynamicLayoutSwitch() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("apple", 200)
        
        // QWERTY: A is (1,0), Q is (0,0) -> Distance 1.0 (Nearby)
        engine.setKeyboardLayout("qwerty")
        val resultsQwerty = engine.suggest("qpple")
        assertTrue("QWERTY: qpple sollte apple finden (Q neben A)", resultsQwerty.any { it.candidate == "apple" })

        // AZERTY: A is (0,0), Q is (1,0) -> Hier sind A und Q auch nebeneinander, aber vertauscht.
        // Let's take 'q' and 'w'.
        // QWERTY: Q(0,0), W(0,1) -> Distance 1.0
        // AZERTY: A(0,0), Z(0,1) -> Q is at (1,0), W is at (0,1) -> Distance sqrt(1^2 + 1^2) = 1.41
        
        fakeRepo.addTestEntry("queen", 200)
        
        // QWERTY: 'w' statt 'q' -> 'ween' -> 'queen'
        engine.setKeyboardLayout("qwerty")
        val scoreQwerty = engine.suggest("ween").find { it.candidate == "queen" }?.score ?: 0.0
        
        // AZERTY: 'w' (0,1) ist weit weg von 'a' (0,0), aber 'q' ist bei AZERTY 'a'.
        // In AZERTY mapping: "KEYCODE_Q" -> 'a', "KEYCODE_W" -> 'z', "KEYCODE_A" -> 'q'
        // Physical Key Q (0,0) -> 'a'
        // Physical Key W (0,1) -> 'z'
        // Physical Key A (1,0) -> 'q'
        // So in AZERTY, 'a' (0,0) and 'z' (0,1) are neighbors.
        // 'q' (1,0) and 'a' (0,0) are neighbors.
        
        engine.setKeyboardLayout("azerty")
        // 'a' statt 'q' -> 'aueen' (in AZERTY ist 'a' an Position (0,0), 'q' an Position (1,0))
        val resultsAzerty = engine.suggest("aueen")
        assertTrue("AZERTY: aueen sollte queen finden", resultsAzerty.any { it.candidate == "queen" })
    }

    @Test
    fun testDynamicDownloadSimulation() {
        fakeRepo.isReady = true
        
        // Zuerst leer
        assertTrue(engine.suggest("hallo").isEmpty())
        
        // Dynamisch "runterladen"
        fakeRepo.addTestEntry("hallo", 200)
        
        val results = engine.suggest("hall")
        assertEquals(1, results.size)
        assertEquals("hallo", results[0].candidate)
    }

    @Test
    fun testLigatureFoldPrefersOeilOverNeil() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("œil", 116)
        fakeRepo.addTestEntry("Neil", 97)

        val results = engine.suggest("oeil")
        assertEquals("œil", results.firstOrNull()?.candidate)
    }

    @Test
    fun testApostropheLigatureRecompose() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("œil", 116)
        fakeRepo.addTestEntry("Neil", 97)

        val results = engine.suggest("l'oeil")
        assertEquals("l'œil", results.firstOrNull()?.candidate)
    }

    @Test
    fun testLongItalianElisionRecompose() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("amico", 120)

        val results = engine.suggest("dell'amivo")
        assertEquals("dell'amico", results.firstOrNull()?.candidate)
    }

    @Test
    fun testProperNameScenario_LorealVsLoral() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("L'Oréal", 120)
        fakeRepo.addTestEntry("l'oral", 220)

        val results = engine.suggest("l'oreal")
        assertEquals("l'Oréal", results.firstOrNull()?.candidate)
    }

    @Test
    fun testProperNameScenario_GenericCloserThanProperName() {
        fakeRepo.isReady = true
        fakeRepo.addTestEntry("L'Oréal", 120)
        fakeRepo.addTestEntry("l'oral", 220)

        // Typo closer to generic word: "l'orak" -> "l'oral" (distance 1)
        // while "L'Oréal" requires more edits.
        val results = engine.suggest("l'orak")
        assertEquals("l'oral", results.firstOrNull()?.candidate)
    }
}
