package rocks.pkb.plektra.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymPagedLayerTest {
    private val sections = listOf(
        SymPagedLayer.Section("A", SymPagedLayer.Kind.EMOJI, (1..30).map { "a$it" }),
        SymPagedLayer.Section("B", SymPagedLayer.Kind.EMOJI, (1..5).map { "b$it" }),
        SymPagedLayer.Section("K", SymPagedLayer.Kind.KAOMOJI, listOf("(^_^)"))
    )
    private val pages = SymPagedLayer.buildPages(
        mapOf(KeyEvent.KEYCODE_Q to "q", KeyEvent.KEYCODE_A to "x"), SymPagedLayer.Kind.EMOJI, sections
    )

    @Test
    fun ownPageFirstThenEverySectionFromAFreshPage() {
        assertEquals(listOf("home", "A", "A", "B", "K"), pages.map { it.section })
        assertEquals(23, pages[1].items.size)
        assertEquals(7, pages[2].items.size)
    }

    @Test
    fun keysPutItemsFromWAndKeepArrowsAndSearch() {
        val keys = SymPagedLayer.keys(pages[1])
        assertEquals("a1", keys[KeyEvent.KEYCODE_W])
        assertEquals("a9", keys[KeyEvent.KEYCODE_S])
        assertEquals("a23", keys[KeyEvent.KEYCODE_M])
        assertEquals(SymPagedLayer.PREVIOUS_LABEL, keys[KeyEvent.KEYCODE_Q])
        assertEquals(SymPagedLayer.NEXT_LABEL, keys[KeyEvent.KEYCODE_P])
        assertEquals(SymPagedLayer.SEARCH_LABEL, keys[KeyEvent.KEYCODE_A])
        assertNull(SymPagedLayer.keys(pages[2])[KeyEvent.KEYCODE_M])
    }

    @Test
    fun ownPageKeepsItsMappingButQAndPTurn() {
        val keys = SymPagedLayer.keys(pages[0])
        assertEquals("x", keys[KeyEvent.KEYCODE_A])
        assertEquals(SymPagedLayer.PREVIOUS_LABEL, keys[KeyEvent.KEYCODE_Q])
    }

    @Test
    fun turningGoesRoundAndSectionJumpsLandOnFirstPages() {
        assertEquals(pages.lastIndex, SymPagedLayer.turn(pages, 0, -1))
        assertEquals(0, SymPagedLayer.turn(pages, pages.lastIndex, 1))
        assertEquals(3, SymPagedLayer.jumpSection(pages, 2, 1))
        assertEquals(1, SymPagedLayer.jumpSection(pages, 3, -1))
        assertEquals(0, SymPagedLayer.jumpSection(pages, 4, 1))
    }

    @Test
    fun variantsGoOnePerKeyWithQBack() {
        val keys = SymPagedLayer.variantKeys(listOf("👍", "👍🏻", "👍🏼"))
        assertEquals("👍", keys[KeyEvent.KEYCODE_W])
        assertEquals("👍🏼", keys[KeyEvent.KEYCODE_R])
        assertEquals(SymPagedLayer.PREVIOUS_LABEL, keys[KeyEvent.KEYCODE_Q])
        assertNull(keys[KeyEvent.KEYCODE_P])
    }
}
