package rocks.pkb.plektra.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScorerTest {
    private fun terms(vararg values: String) = values.map {
        SearchScorer.Term(SearchScorer.normalize(it), SearchScorer.Kind.KEYWORD, false)
    }

    @Test
    fun wordsInAnyOrderMatchAcrossTerms() {
        val heart = listOf(SearchScorer.Term("red heart", SearchScorer.Kind.NAME, false)) + terms("love")
        assertTrue(SearchScorer.score(heart, "heart red") > 0)
        assertTrue(SearchScorer.score(heart, "red love") > 0)
        assertEquals(0, SearchScorer.score(heart, "red car"))
    }

    @Test
    fun wholePhraseOutranksScatteredWords() {
        val name = listOf(SearchScorer.Term("red heart", SearchScorer.Kind.NAME, false))
        assertTrue(SearchScorer.score(name, "red heart") > SearchScorer.score(name, "heart red"))
        assertTrue(SearchScorer.score(name, "red") > SearchScorer.score(name, "heart red"))
    }

    @Test
    fun normalizationIgnoresCaseAndDiacritics() {
        assertEquals("uberrascht gesicht", SearchScorer.normalize("Überrascht-Gesicht"))
        assertTrue(SearchScorer.score(terms("Überraschung"), SearchScorer.normalize("uberr")) > 0)
    }
}
