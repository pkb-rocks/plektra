package rocks.pkb.plektra.core.suggestions

import java.util.Locale
import kotlin.math.pow

class FakeDictionaryRepository : DictionaryRepository {
    override var isReady: Boolean = false
    override var isLoadStarted: Boolean = false
    
    private val entries = mutableListOf<DictionaryEntry>()
    private val baseLocale = Locale.ITALIAN

    fun addTestEntry(word: String, frequency: Int, source: SuggestionSource = SuggestionSource.MAIN) {
        entries.add(DictionaryEntry(word, frequency, source))
    }

    override suspend fun loadIfNeeded() {
        isLoadStarted = true
        isReady = true
    }

    override suspend fun refreshUserEntries() {
        // No-op for fake
    }

    override fun addUserEntryQuick(word: String) {
        addTestEntry(word, 1, SuggestionSource.USER)
    }

    override fun removeUserEntry(word: String) {
        entries.removeAll { it.word.equals(word, ignoreCase = true) && it.source == SuggestionSource.USER }
    }

    override fun markUsed(word: String) {
        // No-op for fake
    }

    override fun effectiveFrequency(entry: DictionaryEntry): Int {
        val raw = entry.frequency.coerceAtLeast(0).coerceAtMost(255)
        val normalized = raw / 255.0
        val scaled = (normalized.pow(0.75) * 1600.0).toInt()
        return scaled.coerceAtLeast(1)
    }

    override fun effectiveFrequency(entry: DictionaryEntry, usePersonalization: Boolean): Int {
        if (usePersonalization || entry.source != SuggestionSource.USER) {
            return effectiveFrequency(entry)
        }
        return effectiveFrequency(entry.copy(frequency = 1))
    }

    override fun getExactWordFrequency(word: String): Int =
        getExactWordFrequency(word, usePersonalization = true)

    override fun getExactWordFrequency(word: String, usePersonalization: Boolean): Int {
        return entries
            .filter { it.word.equals(word, ignoreCase = true) }
            .maxOfOrNull { if (usePersonalization || it.source != SuggestionSource.USER) it.frequency else 1 } ?: 0
    }

    override fun lookupByPrefixMerged(prefix: String, maxSize: Int): List<DictionaryEntry> =
        lookupByPrefixMerged(prefix, maxSize, usePersonalization = true)

    override fun lookupByPrefixMerged(
        prefix: String,
        maxSize: Int,
        usePersonalization: Boolean
    ): List<DictionaryEntry> {
        if (maxSize <= 0) return emptyList()
        val normalizedPrefix = normalize(prefix)
        val seen = LinkedHashMap<String, DictionaryEntry>()
        for (entry in entries.filter { normalize(it.word).startsWith(normalizedPrefix) }.sortedWith(entryComparator(usePersonalization))) {
            val key = entry.word.lowercase(baseLocale)
            if (!seen.containsKey(key)) {
                seen[key] = entry
                if (seen.size >= maxSize) break
            }
        }
        return seen.values.toList()
    }

    override fun symSpellLookup(term: String, maxSuggestions: Int): List<SymSpell.SuggestItem> =
        symSpellLookup(term, maxSuggestions, usePersonalization = true)

    override fun symSpellLookup(
        term: String,
        maxSuggestions: Int,
        usePersonalization: Boolean
    ): List<SymSpell.SuggestItem> {
        if (maxSuggestions <= 0) return emptyList()
        val normalizedTerm = normalize(term)
        val bestByTerm = LinkedHashMap<String, SymSpell.SuggestItem>()
        for (entry in entries) {
            val entryTerm = normalize(entry.word)
            val dist = levenshtein(normalizedTerm, entryTerm)
            if (dist <= 2) {
                val freq = effectiveFrequency(entry, usePersonalization)
                val existing = bestByTerm[entryTerm]
                if (existing == null || freq > existing.frequency) {
                    bestByTerm[entryTerm] = SymSpell.SuggestItem(entryTerm, dist, freq)
                }
            }
        }
        return bestByTerm.values
            .sortedWith(
                compareBy<SymSpell.SuggestItem> { it.distance }
                    .thenByDescending { it.frequency }
                    .thenBy { it.term.length }
                    .thenBy { it.term }
            )
            .take(maxSuggestions)
    }

    override fun bestEntryForNormalized(normalized: String): DictionaryEntry? =
        bestEntryForNormalized(normalized, usePersonalization = true)

    override fun bestEntryForNormalized(normalized: String, usePersonalization: Boolean): DictionaryEntry? {
        return entries
            .filter { normalize(it.word) == normalized }
            .minWithOrNull(entryComparator(usePersonalization))
    }

    override fun topByNormalized(normalized: String, limit: Int): List<DictionaryEntry> =
        topByNormalized(normalized, limit, usePersonalization = true)

    override fun topByNormalized(
        normalized: String,
        limit: Int,
        usePersonalization: Boolean
    ): List<DictionaryEntry> {
        if (limit <= 0) return emptyList()
        return entries
            .filter { normalize(it.word) == normalized }
            .sortedWith(entryComparator(usePersonalization))
            .take(limit)
    }

    private fun entryComparator(usePersonalization: Boolean): Comparator<DictionaryEntry> {
        return if (usePersonalization) {
            compareByDescending<DictionaryEntry> { effectiveFrequency(it) }
        } else {
            compareByDescending<DictionaryEntry> { effectiveFrequency(it, usePersonalization = false) }
                .thenBy { it.source.ordinal }
                .thenBy { it.word.lowercase(baseLocale) }
                .thenBy { it.word }
        }
    }

    override fun topCommonEntries(limit: Int): List<DictionaryEntry> {
        return entries
            .filter {
                it.word.length > 1 &&
                    it.source != SuggestionSource.DEFAULT_USER &&
                    it.word.any { ch -> ch.isLetter() }
            }
            .sortedWith(
                compareByDescending<DictionaryEntry> { it.source == SuggestionSource.USER }
                    .thenByDescending { effectiveFrequency(it) }
                    .thenBy { it.word.length }
            )
            .take(limit)
    }

    override fun isKnownWord(word: String): Boolean {
        val normalized = normalize(word)
        return entries.any { normalize(it.word) == normalized }
    }

    override fun ensureLoadScheduled(background: () -> Unit) {
        if (!isLoadStarted) {
            background()
        }
    }

    private fun normalize(word: String): String {
        return WordNormalization.normalizeForDictionary(word, baseLocale)
    }

    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        if (s.isEmpty()) return t.length
        if (t.isEmpty()) return s.length

        val v0 = IntArray(t.length + 1) { it }
        val v1 = IntArray(t.length + 1)

        for (i in s.indices) {
            v1[0] = i + 1
            for (j in t.indices) {
                val cost = if (s[i] == t[j]) 0 else 1
                v1[j + 1] = minOf(v1[j] + 1, v0[j + 1] + 1, v0[j] + cost)
            }
            for (j in v0.indices) v0[j] = v1[j]
        }
        return v0[t.length]
    }
}
