package rocks.pkb.plektra.core.suggestions

/**
 * Reads letters typed on keys whose Alt layer prints a digit (BlackBerry-style
 * keyboards: W E R / S D F / Z X C = 1-9) as the number the user meant.
 *
 * Separator rules:
 * - Only '.' and ',' join segments, and only when a letter or digit sits on both sides.
 * - A leading separator, or a run of two or more, is never part of the token.
 * - A single trailing separator is kept verbatim after the converted number on a
 *   boundary; while it is still being typed no number is offered.
 * - An earlier segment that is a dictionary word longer than one letter ends the token,
 *   so "red.w00" converts only "w00".
 */
object NumberSuggestions {
    const val MAX_TOKEN_LENGTH = 48
    const val CONTEXT_CHARS = 64

    data class Token(
        val typed: String,
        val number: String,
        val currentSegment: String
    ) {
        /** Space must not turn a lone "w" or a two-letter fragment into digits. */
        val autoCorrectEligible: Boolean
            get() = typed.any { it.isDigit() } || typed.count { it.isLetterOrDigit() } >= 3
    }

    fun isSeparator(c: Char): Boolean = c == '.' || c == ','

    /** Letters of the active layout whose Alt output is a single digit. */
    fun digitMap(altMappings: Map<Int, String>, letterForKeyCode: (Int) -> String?): Map<Char, Char> {
        val map = HashMap<Char, Char>()
        altMappings.forEach { (keyCode, alt) ->
            val digit = alt.singleOrNull()?.takeIf { it in '0'..'9' } ?: return@forEach
            val letter = letterForKeyCode(keyCode)?.singleOrNull()?.takeIf { it.isLetter() } ?: return@forEach
            map.putIfAbsent(letter.lowercaseChar(), digit)
        }
        return map
    }

    fun tokenAtEnd(
        textBeforeCursor: String,
        digitMap: Map<Char, Char>,
        isKnownWord: (String) -> Boolean,
        allowTrailingSeparator: Boolean = false
    ): Token? {
        if (digitMap.isEmpty() || textBeforeCursor.isEmpty()) return null
        val trailing = textBeforeCursor.last().takeIf { allowTrailingSeparator && isSeparator(it) }
        val body = if (trailing != null) textBeforeCursor.dropLast(1) else textBeforeCursor

        fun isNumberChar(c: Char) = c.isDigit() || digitMap.containsKey(c.lowercaseChar())
        fun segmentStart(end: Int): Int {
            var i = end
            while (i > 0 && body[i - 1].isLetterOrDigit()) i--
            return i
        }

        val currentStart = segmentStart(body.length)
        val currentSegment = body.substring(currentStart)
        if (currentSegment.isEmpty() || !currentSegment.all(::isNumberChar)) return null

        var start = currentStart
        while (start >= 2 && isSeparator(body[start - 1]) && body[start - 2].isLetterOrDigit()) {
            val segStart = segmentStart(start - 1)
            val segment = body.substring(segStart, start - 1)
            if (!segment.all(::isNumberChar)) break
            val isWordSegment = segment.length > 1 && segment.all { it.isLetter() } && isKnownWord(segment)
            if (isWordSegment) break
            start = segStart
        }
        // A token reaching the start of a full context window may be truncated.
        if (start == 0 && textBeforeCursor.length >= CONTEXT_CHARS) return null

        val typedCore = body.substring(start)
        if (typedCore.length > MAX_TOKEN_LENGTH || typedCore.none { it.isLetter() }) return null
        val numberCore = typedCore.map { c ->
            if (c.isLetter()) digitMap.getValue(c.lowercaseChar()) else c
        }.joinToString("")
        val suffix = trailing?.toString().orEmpty()
        return Token(typedCore + suffix, numberCore + suffix, currentSegment)
    }

    /**
     * Length of the typed token a tapped number suggestion stands for, or null when the
     * suggestion is not a separator-joined number matching the text before the cursor.
     */
    fun typedLengthForSuggestion(textBeforeCursor: String, suggestion: String): Int? {
        if (suggestion.length > MAX_TOKEN_LENGTH || suggestion.none(::isSeparator)) return null
        if (!suggestion.all { it.isDigit() || isSeparator(it) }) return null
        if (textBeforeCursor.length < suggestion.length) return null
        val typed = textBeforeCursor.takeLast(suggestion.length)
        val matches = suggestion.indices.all { i ->
            val s = suggestion[i]
            val t = typed[i]
            if (isSeparator(s)) t == s else t.isLetter() || t == s
        }
        if (!matches || typed.none { it.isLetter() }) return null
        val preceding = textBeforeCursor.getOrNull(textBeforeCursor.length - suggestion.length - 1)
        if (preceding != null && preceding.isLetterOrDigit()) return null
        return suggestion.length
    }

    /**
     * Places [number] right after the last real-word candidate, keeping every word in its
     * original order; looser fuzzy matches stay below it. The number always lands within the
     * first [visibleSlots] entries (fewer than [limit] when the bar gives a slot to the
     * add-word button), taking the last of them when real words fill the rest.
     * With a single visible slot a real word keeps it.
     */
    fun insert(
        words: List<SuggestionResult>,
        number: String,
        limit: Int,
        isRealWordCandidate: (SuggestionResult) -> Boolean,
        visibleSlots: Int = limit
    ): List<SuggestionResult> {
        if (limit <= 0) return words
        val slots = visibleSlots.coerceIn(1, limit)
        val others = words.filterNot { it.candidate == number }
        val index = others.take(maxOf(1, slots - 1)).indexOfLast(isRealWordCandidate) + 1
        if (index >= slots) return others.take(limit)
        val result = SuggestionResult(
            candidate = number,
            distance = 0,
            score = 0.0,
            source = SuggestionSource.MAIN,
            kind = SuggestionKind.NUMBER
        )
        return (others.take(index) + result + others.drop(index)).take(limit)
    }

    /**
     * A word candidate outranks the number only if it completes what was typed or is within
     * [maxDistance] edits of it. Suggestion lookup strips digits, so "r00" is searched as "r"
     * and its completions ("reduce") would otherwise bury every digit-key number; distance is
     * therefore measured on the raw typed characters, and capped by the typed length so a
     * high autocorrect distance cannot make "each" a close match for the three keys "e00".
     */
    fun isRealWordCandidate(result: SuggestionResult, typedSegment: String, maxDistance: Int): Boolean {
        if (result.kind != SuggestionKind.CURRENT_WORD) return false
        val typed = typedSegment.lowercase()
        val candidate = result.candidate.lowercase()
        val allowed = minOf(maxDistance, maxOf(1, (typed.length - 1) / 2))
        return candidate.startsWith(typed) || withinDistance(typed, candidate, allowed)
    }

    private fun withinDistance(a: String, b: String, maxDistance: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = curr
        }
        return prev[b.length] <= maxDistance
    }
}
