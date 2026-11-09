package rocks.pkb.plektra.data.symbols

import android.content.Context
import android.graphics.Paint
import java.util.concurrent.ConcurrentHashMap
import rocks.pkb.plektra.data.emoji.EmojiSearchRepository
import rocks.pkb.plektra.data.search.SearchScorer

/**
 * Finds text symbols by their Unicode names ("degree", "right arrow") and by their CLDR names
 * and keywords in the search languages ("Grad", "Pfeil nach rechts").
 *
 * The bundled list (assets/common/symbols/symbols.tsv) holds every Unicode symbol without emoji
 * presentation; a symbol the phone's fonts can't draw is left out when it is about to be shown.
 */
object SymbolSearch {
    private const val SYMBOLS_ASSET = "common/symbols/symbols.tsv"
    private const val LOCALIZED_ASSET_DIR = "common/symbol_search"

    data class Entry(val symbol: String, val terms: List<SearchScorer.Term>)

    class Index internal constructor(val entries: List<Entry>)

    val recents = RecentItems(key = "recent_symbols", max = 40)

    private val indexCache = ConcurrentHashMap<String, Index>()
    private val glyphVerdicts = ConcurrentHashMap<String, Boolean>()
    private val glyphPaint = ThreadLocal.withInitial { Paint() }

    /** The index for the device's search languages (read once per language chain). */
    fun index(context: Context): Index {
        val chain = EmojiSearchRepository.getPreferredLocaleChain(context)
        val key = chain.joinToString("|")
        indexCache[key]?.let { return it }
        val assets = context.assets
        val names = runCatching {
            assets.open(SYMBOLS_ASSET).bufferedReader().use { parseSymbols(it.readText()) }
        }.getOrDefault(emptyList())
        val localized = chain.mapIndexedNotNull { position, locale ->
            localizedAsset(context, locale)?.let { it to (position == 0) }
        }
        return buildIndex(names, localized).also { indexCache[key] = it }
    }

    private fun localizedAsset(context: Context, locale: String): Map<String, List<String>>? {
        val language = locale.replace('-', '_').substringBefore('_').lowercase()
        return runCatching {
            context.assets.open("$LOCALIZED_ASSET_DIR/$language.tsv").bufferedReader().use {
                parseLocalized(it.readText())
            }
        }.getOrNull()
    }

    /** "code point (hex) TAB name" lines; # starts a comment. */
    internal fun parseSymbols(text: String): List<Pair<String, String>> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val codePoint = line.substring(0, tab).toIntOrNull(16) ?: return@mapNotNull null
            String(Character.toChars(codePoint)) to line.substring(tab + 1)
        }
        .toList()

    /** "symbol TAB name TAB keyword|keyword" lines, as for the emoji search. */
    internal fun parseLocalized(text: String): Map<String, List<String>> = text.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            val symbol = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            symbol to (listOfNotNull(parts.getOrNull(1)?.takeIf { it.isNotBlank() }) +
                parts.getOrNull(2)?.split('|').orEmpty().filter { it.isNotBlank() })
        }
        .toMap()

    /**
     * Joins the Unicode names with the [localized] names (and whether each language is the
     * preferred one). The curated page symbols come first, so they lead among equal matches.
     */
    internal fun buildIndex(
        names: List<Pair<String, String>>,
        localized: List<Pair<Map<String, List<String>>, Boolean>>
    ): Index {
        val curated = SymbolPages.ALL.withIndex().associate { (index, symbol) -> symbol to index }
        val entries = names.map { (symbol, name) ->
            val terms = LinkedHashMap<String, SearchScorer.Term>()
            localized.forEach { (records, preferred) ->
                records[symbol]?.forEachIndexed { position, value ->
                    val normalized = SearchScorer.normalize(value)
                    if (normalized.isNotEmpty() && normalized !in terms) {
                        val kind = if (position == 0) SearchScorer.Kind.NAME else SearchScorer.Kind.KEYWORD
                        terms[normalized] = SearchScorer.Term(normalized, kind, preferred)
                    }
                }
            }
            val unicodeName = SearchScorer.normalize(name)
            if (unicodeName.isNotEmpty() && unicodeName !in terms) {
                terms[unicodeName] = SearchScorer.Term(unicodeName, SearchScorer.Kind.NAME, false)
            }
            Entry(symbol, terms.values.toList())
        }.sortedBy { curated[it.symbol] ?: Int.MAX_VALUE }
        return Index(entries)
    }

    /**
     * Symbols matching [query], best first, the [recents] ahead among equals. A query that is
     * a symbol itself finds that symbol first.
     */
    fun search(
        index: Index,
        query: String,
        recents: List<String> = emptyList(),
        limit: Int = 200,
        canDraw: (String) -> Boolean = ::canRender
    ): List<Entry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val normalized = SearchScorer.normalize(trimmed)
        val rank = recents.withIndex().associate { (position, symbol) -> symbol to position }
        return index.entries.asSequence()
            .map { entry ->
                entry to if (entry.symbol == trimmed) Int.MAX_VALUE else SearchScorer.score(entry.terms, normalized)
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Entry, Int>> { it.second }
                    .thenBy { rank[it.first.symbol] ?: Int.MAX_VALUE }
            )
            .map { it.first }
            .filter { canDraw(it.symbol) }
            .take(limit)
            .toList()
    }

    /** Whether the phone's fonts can draw [symbol]; checked once per process. */
    fun canRender(symbol: String): Boolean = glyphVerdicts.getOrPut(symbol) {
        runCatching { glyphPaint.get()!!.hasGlyph(symbol) }.getOrDefault(false)
    }
}
