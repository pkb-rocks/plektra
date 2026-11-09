package rocks.pkb.plektra.data.symbols

import android.content.Context
import rocks.pkb.plektra.EmojiPagesSettings
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.emoji.EmojiSearchRepository

/**
 * The text symbols and kaomoji the emoji pickers offer beside the emoji, when turned on: as
 * categories (recent ones first) and as search results after the emoji. Shared by the keyboard's
 * picker and the SYM customization's picker, so both offer the same.
 */
class PickerExtras private constructor(
    val categories: List<EmojiRepository.EmojiCategory>,
    private val symbolIndex: SymbolSearch.Index?,
    private val kaomoji: List<Kaomoji.Entry>
) {
    /** Matching text symbols, then kaomoji. */
    fun search(context: Context, query: String): List<EmojiSearchRepository.EmojiSearchResult> {
        val symbols = symbolIndex?.let { index ->
            SymbolSearch.search(index, query, SymbolSearch.recents.get(context), limit = MAX_RESULTS)
        }.orEmpty().map { result(it.symbol, EmojiRepository.SYMBOLS_CATEGORY_ID) }
        val kaomoji = Kaomoji.search(kaomoji, query, Kaomoji.recents.get(context))
            .take(MAX_RESULTS)
            .map { result(it.kaomoji, EmojiRepository.KAOMOJI_CATEGORY_ID) }
        return symbols + kaomoji
    }

    private fun result(text: String, categoryId: String) =
        EmojiSearchRepository.EmojiSearchResult(EmojiRepository.EmojiEntry(text, emptyList()), categoryId, 0)

    companion object {
        private const val MAX_RESULTS = 60
        val NONE = PickerExtras(emptyList(), null, emptyList())

        /** Reads the symbols and kaomoji; not for the main thread. */
        fun load(context: Context): PickerExtras {
            if (!EmojiPagesSettings.pickerSymbolsAndKaomoji(context)) return NONE
            val kaomojiEntries = Kaomoji.entries(context)
            val symbols = (SymbolSearch.recents.get(context) + SymbolPages.ALL).distinct().filter(SymbolSearch::canRender)
            val kaomoji = (Kaomoji.recents.get(context) + kaomojiEntries.map { it.kaomoji }).distinct()
            val categories = listOf(
                category(EmojiRepository.SYMBOLS_CATEGORY_ID, symbols),
                category(EmojiRepository.KAOMOJI_CATEGORY_ID, kaomoji)
            ).filter { it.emojis.isNotEmpty() }
            return PickerExtras(categories, SymbolSearch.index(context), kaomojiEntries)
        }

        private fun category(id: String, items: List<String>) = EmojiRepository.EmojiCategory(
            id = id,
            displayNameRes = EmojiRepository.categoryNameRes(id),
            emojis = items.map { EmojiRepository.EmojiEntry(it, emptyList()) }
        )

        /** Records [text] chosen from [categoryId] in its recents, when it is a symbol or kaomoji. */
        fun recordChosen(context: Context, text: String, categoryId: String): Boolean = when (categoryId) {
            EmojiRepository.SYMBOLS_CATEGORY_ID -> true.also { SymbolSearch.recents.add(context, text) }
            EmojiRepository.KAOMOJI_CATEGORY_ID -> true.also { Kaomoji.recents.add(context, text) }
            else -> false
        }
    }
}
