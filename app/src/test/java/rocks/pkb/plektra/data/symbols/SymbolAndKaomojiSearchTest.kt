package rocks.pkb.plektra.data.symbols

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymbolAndKaomojiSearchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    private val index by lazy {
        SymbolSearch.buildIndex(
            SymbolSearch.parseSymbols(asset("common/symbols/symbols.tsv")),
            listOf(
                SymbolSearch.parseLocalized(asset("common/symbol_search/de.tsv")) to true,
                SymbolSearch.parseLocalized(asset("common/symbol_search/en.tsv")) to false
            )
        )
    }

    private fun symbols(query: String) = SymbolSearch.search(index, query, canDraw = { true }).map { it.symbol }

    @Test
    fun symbolsAreFoundByUnicodeAndLocalizedNames() {
        assertTrue(symbols("degree").take(3).contains("°"))
        assertTrue(symbols("grad").take(3).contains("°"))
        assertTrue(symbols("pfeil rechts").take(5).contains("→"))
        assertTrue(symbols("rechts pfeil").take(5).contains("→"))
        assertTrue(symbols("square root").take(3).contains("√"))
        assertEquals("€", symbols("€").first())
    }

    @Test
    fun symbolListLeavesOutEmojiPresentation() {
        val all = index.entries.map { it.symbol }.toSet()
        assertTrue(all.size > 8000)
        assertFalse("⌚" in all)
        assertFalse("❤" in all.filter { it == "❤️" })
        assertTrue("©" in all)
    }

    @Test
    fun symbolPagesHoldTwentyThreeTextSymbolsEach() {
        SymbolPages.PAGES.forEach { page -> assertEquals(page.toString(), 23, page.size) }
        assertEquals(SymbolPages.ALL.size, SymbolPages.ALL.toSet().size)
        val known = index.entries.map { it.symbol }.toSet()
        SymbolPages.ALL.forEach { assertTrue("$it is not a text symbol", it in known || it.length == 1 && (it[0].code < 0x80 || it[0].isLetter())) }
    }

    @Test
    fun kaomojiAreFoundInEnglishAndGerman() {
        val entries = Kaomoji.entries(context)
        assertTrue(entries.size > 350)
        assertEquals("(╯°□°）╯︵ ┻━┻", Kaomoji.search(entries, "table flip").first().kaomoji)
        val cats = Kaomoji.search(entries, "katze").map { it.kaomoji }
        assertTrue(cats.isNotEmpty())
        assertEquals(cats.toSet(), Kaomoji.search(entries, "cat").map { it.kaomoji }.toSet())
        val recent = cats.last()
        assertEquals(recent, Kaomoji.search(entries, "katze", recents = listOf(recent)).first().kaomoji)
    }

    @Test
    fun recentsKeepNewestFirstWithoutDuplicates() {
        val recents = RecentItems("test_recents", max = 3)
        listOf("a", "b", "c", "a", "d").forEach { recents.add(context, it) }
        assertEquals(listOf("d", "a", "c"), recents.get(context))
    }
}
