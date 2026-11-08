package rocks.pkb.plektra.training

import java.text.Normalizer

/**
 * Display-only alignment of typed words to target words, so that a missed or extra space does
 * not shift the highlighted word for the rest of the session. Recordings keep the raw text.
 */
object TypingTrainingAlignment {

    /** Target index each completed typed word was assigned to, plus the index of the word in progress. */
    data class Result(val tokenTargets: List<Int>, val currentTarget: Int)

    fun align(typed: String, targets: List<String>, ignoreCase: Boolean, ignoreAccents: Boolean = false): Result {
        val tokens = typed.split(' ')
        val completed = tokens.dropLast(1)
        fun norm(token: String) = fold(token, ignoreCase, ignoreAccents)
        val normalizedTargets = targets.map(::norm)
        fun target(index: Int) = normalizedTargets.getOrNull(index)
        val assigned = MutableList(completed.size) { -1 }
        var i = 0
        var j = 0
        while (i < completed.size) {
            val token = norm(completed[i])
            if (token.isEmpty()) {
                i++
                continue
            }
            val current = target(j) ?: break
            val single = editDistance(token, current)
            val merged = target(j + 1)?.let { editDistance(token, current + it) }
            val nextToken = completed.getOrNull(i + 1)?.let(::norm)?.takeIf { it.isNotEmpty() }
            val split = nextToken?.let { editDistance(token + it, current) }
            val nextSingle = nextToken?.let { next -> target(j + 1)?.let { editDistance(next, it) } } ?: Int.MAX_VALUE / 2
            when {
                merged != null && merged < single -> {
                    assigned[i] = j
                    i++
                    j += 2
                }
                split != null && split < single + nextSingle && split < single -> {
                    assigned[i] = j
                    assigned[i + 1] = j
                    i += 2
                    j++
                }
                else -> {
                    assigned[i] = j
                    i++
                    j++
                }
            }
        }
        return Result(assigned, j)
    }

    /**
     * Comparison form of a word: optionally lower case and optionally reduced to base letters
     * (é → e, ł → l), one character per input character.
     */
    fun fold(text: String, ignoreCase: Boolean, ignoreAccents: Boolean): String {
        // Per character: String.lowercase() expands »İ« to »i̇« and would shift every later offset.
        val cased = if (ignoreCase) buildString(text.length) { text.forEach { append(it.lowercaseChar()) } } else text
        if (!ignoreAccents) return cased
        return buildString(cased.length) { cased.forEach { append(baseLetter(it)) } }
    }

    private fun baseLetter(char: Char): Char {
        if (char.code < 0x80) return char
        BASE_LETTERS[char]?.let { return it }
        val decomposed = Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
        val marksOnly = decomposed.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        return if (decomposed.length > 1 && marksOnly) decomposed[0] else char
    }

    /** Letters with a stroke have no canonical decomposition. */
    private val BASE_LETTERS = mapOf(
        'ł' to 'l', 'Ł' to 'L', 'ø' to 'o', 'Ø' to 'O', 'đ' to 'd', 'Đ' to 'D', 'ı' to 'i', 'ħ' to 'h', 'Ħ' to 'H'
    )

    internal fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = x
            for (y in 1..b.length) {
                row[y] = minOf(previous[y] + 1, row[y - 1] + 1, previous[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            }
            previous = row
        }
        return previous[b.length]
    }
}
