package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LearnedWordRankingTest {
    private val locale = Locale.ENGLISH
    private fun repository(source: SuggestionSource = SuggestionSource.MAIN) = FakeDictionaryRepository().apply {
        isReady = true
        addTestEntry("planet", 100, source)
        addTestEntry("planer", 100, source)
    }

    @Test fun personalUsageCanReorderOtherwiseEquivalentCandidatesWithoutChangingCorpusData() {
        val repo = repository()
        val baseline = SuggestionEngine(repo, locale).suggest("plan")
        assertEquals(2, baseline.size)
        val target = baseline.last().candidate
        val ranked = SuggestionEngine(repo, locale, exactUsageCountProvider = { language, word ->
            assertEquals(locale, language)
            if (word == target) 40 else 0
        }).suggest("plan")
        assertEquals(target, ranked.first().candidate)
        assertEquals(baseline.map { it.candidate }.toSet(), ranked.map { it.candidate }.toSet())
        assertEquals(100, repo.getExactWordFrequency(target))
    }

    @Test fun missingAndInvalidCountsPreserveExistingRankingAndScores() {
        val repo = repository()
        val baseline = SuggestionEngine(repo, locale).suggest("plan")
        for (count in listOf(0, -100)) {
            val ranked = SuggestionEngine(repo, locale, exactUsageCountProvider = { _, _ -> count }).suggest("plan")
            assertEquals(baseline, ranked)
        }
    }

    @Test fun usageBonusIsBoundedAndNeverAmplifiedByUserDictionaryBoost() {
        for (source in listOf(SuggestionSource.MAIN, SuggestionSource.USER, SuggestionSource.DEFAULT_USER)) {
            val repo = repository(source)
            val baseline = SuggestionEngine(repo, locale).suggest("plan").associateBy { it.candidate }
            for (count in listOf(1, 4, 100, Int.MAX_VALUE)) {
                val ranked = SuggestionEngine(repo, locale, exactUsageCountProvider = { _, _ -> count }).suggest("plan")
                assertEquals(2, ranked.size)
                for (result in ranked) {
                    val difference = result.score - baseline.getValue(result.candidate).score
                    assertTrue(difference > 0.0)
                    assertTrue(difference <= 1.0 + 1e-9)
                    assertEquals(minOf(1.0, kotlin.math.ln(1.0 + count) * 0.25), difference, 1e-9)
                }
            }
        }
    }

    @Test fun unavailablePersonalCountsKeepOrdinaryDictionarySuggestions() {
        val repo=repository()
        val baseline=SuggestionEngine(repo,locale).suggest("plan")
        val fallback=SuggestionEngine(repo,locale,exactUsageCountProvider={_,_->throw IllegalStateException("synthetic read failure")}).suggest("plan")
        assertEquals(baseline,fallback)
    }
}
