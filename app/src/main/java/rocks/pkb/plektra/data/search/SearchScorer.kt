package rocks.pkb.plektra.data.search

import java.text.Normalizer
import java.util.Locale

/**
 * Shared ranking for the emoji, symbol and kaomoji search.
 *
 * A term is a name or keyword in any search language. The whole query matching a term
 * (exactly, as its start, or inside it) ranks highest; otherwise every query word has to
 * match a word of some term, so "heart red" and "rot herz" find ❤️ like "red heart" does.
 */
object SearchScorer {
    enum class Kind { NAME, KEYWORD }

    class Term(val text: String, val kind: Kind, val preferred: Boolean) {
        val words: List<String> = text.split(' ').filter { it.isNotEmpty() }
    }

    const val EXACT_NAME = 1700
    const val EXACT_KEYWORD = 1600
    const val PREFIX_NAME = 1400
    const val PREFIX_KEYWORD = 1300
    private const val WORDS_BASE = 1100
    private const val WORDS_MAX = 1290
    const val CONTAINS_NAME = 1000
    const val CONTAINS_KEYWORD = 900
    private const val PREFERRED_BONUS = 25

    /** Score of [terms] for an already [normalize]d [query]; 0 when they don't match. */
    fun score(terms: List<Term>, query: String): Int {
        if (query.isEmpty()) return 0
        val allowContains = query.length >= 2
        var best = 0
        for (term in terms) {
            val value = term.text
            if (value.isEmpty()) continue
            val bonus = if (term.preferred) PREFERRED_BONUS else 0
            val name = term.kind == Kind.NAME
            val candidate = when {
                value == query -> (if (name) EXACT_NAME else EXACT_KEYWORD) + bonus
                value.startsWith(query) -> (if (name) PREFIX_NAME else PREFIX_KEYWORD) + bonus
                allowContains && value.contains(query) -> (if (name) CONTAINS_NAME else CONTAINS_KEYWORD) + bonus
                else -> 0
            }
            if (candidate > best) best = candidate
        }
        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        if (queryWords.size > 1 && best < WORDS_MAX) {
            val words = wordsScore(terms, queryWords)
            if (words > best) best = words
        }
        return best
    }

    private fun wordsScore(terms: List<Term>, queryWords: List<String>): Int {
        var total = 0
        for (queryWord in queryWords) {
            var wordBest = 0
            for (term in terms) {
                for (word in term.words) {
                    val points = when {
                        word == queryWord -> 3
                        word.startsWith(queryWord) -> 2
                        queryWord.length >= 3 && word.contains(queryWord) -> 1
                        else -> 0
                    }
                    if (points > wordBest) wordBest = points
                }
            }
            if (wordBest == 0) return 0
            total += wordBest
        }
        return (WORDS_BASE + total * 20).coerceAtMost(WORDS_MAX)
    }

    /** Lower case, no diacritics, words separated by single spaces. */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return ""

        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            var previousSpace = false
            decomposed.forEach { ch ->
                val type = Character.getType(ch)
                if (type == Character.NON_SPACING_MARK.toInt()) return@forEach
                if (Character.isLetterOrDigit(ch)) {
                    append(ch)
                    previousSpace = false
                } else if (Character.isWhitespace(ch) || ch == '-' || ch == '_') {
                    if (!previousSpace && isNotEmpty()) {
                        append(' ')
                        previousSpace = true
                    }
                }
            }
            if (isNotEmpty() && last() == ' ') {
                deleteCharAt(lastIndex)
            }
        }
    }
}
