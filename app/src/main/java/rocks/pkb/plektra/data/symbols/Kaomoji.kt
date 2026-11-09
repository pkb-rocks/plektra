package rocks.pkb.plektra.data.symbols

import android.content.Context
import rocks.pkb.plektra.data.search.SearchScorer

/**
 * The bundled kaomoji (assets/common/kaomoji/kaomoji.txt), grouped by mood, expression, action
 * and animal. Each kaomoji is found by its group's words and its own names, in English and German.
 *
 * Adapted from Flux Keyboard (GPL-3.0), Kaomoji.kt.
 */
object Kaomoji {
    private const val ASSET = "common/kaomoji/kaomoji.txt"

    data class Entry(val kaomoji: String, val terms: List<SearchScorer.Term>)

    val recents = RecentItems(key = "recent_kaomoji", max = 40)

    @Volatile
    private var cached: List<Entry>? = null

    fun entries(context: Context): List<Entry> = cached ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { parse(it.readLines()) }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cached = it }

    fun all(context: Context): List<String> = entries(context).map { it.kaomoji }

    internal fun parse(lines: List<String>): List<Entry> {
        val words = LinkedHashMap<String, LinkedHashSet<String>>()
        var groupWords = emptyList<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                // "## Mood · Joy | happy glad": a group; a heading without "|" is a comment
                val heading = line.removePrefix("## ")
                if ('|' in heading) {
                    groupWords = splitWords(heading.replace('·', ' ').replace('|', ' '))
                }
                continue
            }
            val kaomoji = line.substringBefore('\t')
            val own = if ('\t' in line) splitWords(line.substringAfter('\t')) else emptyList()
            words.getOrPut(kaomoji) { LinkedHashSet() }.apply {
                addAll(own)
                addAll(groupWords)
            }
        }
        return words.map { (kaomoji, names) ->
            Entry(kaomoji, names.map { SearchScorer.Term(it, SearchScorer.Kind.KEYWORD, false) })
        }
    }

    private fun splitWords(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+"))
            .map(SearchScorer::normalize)
            .filter { it.isNotEmpty() && it != "and" }

    /** Kaomoji matching [query], best first; recently typed ones ahead among equals. */
    fun search(entries: List<Entry>, query: String, recents: List<String> = emptyList()): List<Entry> {
        val normalized = SearchScorer.normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val rank = recents.withIndex().associate { (index, kaomoji) -> kaomoji to index }
        return entries.asSequence()
            .map { it to SearchScorer.score(it.terms, normalized) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Entry, Int>> { it.second }
                    .thenBy { rank[it.first.kaomoji] ?: Int.MAX_VALUE }
            )
            .map { it.first }
            .toList()
    }
}
