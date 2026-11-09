package rocks.pkb.plektra.core

import android.view.KeyEvent

/**
 * The emoji and symbols pages shown page by page: Q turns back, P forward (round and round),
 * Shift+Q and Shift+P jump to the previous and next section (recents, an emoji category, the
 * kaomoji). Each page puts one item on every key from W along the rows (23 per page; A opens
 * the search). The layer's own key mapping stays its first page.
 *
 * Holding an emoji shows its variants (skin tones) the same way, as many pages as they need:
 * Q and P turn them, Q on their first page goes back. For an emoji of the layer's own mapping,
 * A opens that key's customization, which the hold would otherwise open.
 *
 * Pure model: the [SymLayoutController] keeps the page index and supplies the items.
 */
object SymPagedLayer {
    const val PREVIOUS_KEY = KeyEvent.KEYCODE_Q
    const val NEXT_KEY = KeyEvent.KEYCODE_P
    const val SEARCH_KEY = KeyEvent.KEYCODE_A
    const val EDIT_KEY = KeyEvent.KEYCODE_A

    const val PREVIOUS_LABEL = "‹" // ‹
    const val NEXT_LABEL = "›" // ›
    const val SEARCH_LABEL = "⌕" // ⌕
    const val EDIT_LABEL = "✎" // ✎

    /** Every letter key but Q, P and A, in reading order. */
    val SLOTS: List<Int> = listOf(
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T,
        KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
        KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G,
        KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M
    )
    val PER_PAGE: Int = SLOTS.size

    /** The keys a generated page or the variants put items, arrows, search or edit on. */
    val KEYS: Set<Int> = SLOTS.toSet() + setOf(PREVIOUS_KEY, NEXT_KEY, SEARCH_KEY, EDIT_KEY)

    /** The [KEYS] without anything on them in [keys] (a page's or the variants' keys): they do nothing. */
    fun freeKeys(keys: Map<Int, String>): Set<Int> = KEYS - keys.keys

    /** What a page shows: emoji, text symbols or kaomoji (drawn smaller, to fit). */
    enum class Kind { EMOJI, SYMBOLS, KAOMOJI }

    const val SECTION_HOME = "home"
    const val SECTION_RECENTS = "recents"

    /**
     * One page. [mapping] is set on the layer's own page (the user's key mapping) instead of
     * [items]; [section] is a category id, [SECTION_HOME] or [SECTION_RECENTS].
     */
    data class Page(
        val kind: Kind,
        val section: String,
        val items: List<String> = emptyList(),
        val mapping: Map<Int, String>? = null
    )

    data class Section(val id: String, val kind: Kind, val items: List<String>)

    /** The layer's pages: its own [mapping] first, then each section from a fresh page. */
    fun buildPages(mapping: Map<Int, String>, homeKind: Kind, sections: List<Section>): List<Page> {
        val pages = mutableListOf(Page(homeKind, SECTION_HOME, mapping = mapping))
        sections.forEach { section ->
            section.items.chunked(PER_PAGE).forEach { chunk ->
                pages += Page(section.kind, section.id, items = chunk)
            }
        }
        return pages
    }

    /** What each key shows and types on [page]. */
    fun keys(page: Page): Map<Int, String> {
        val keys = LinkedHashMap<Int, String>()
        val mapping = page.mapping
        if (mapping != null) {
            keys.putAll(mapping)
        } else {
            SLOTS.zip(page.items).forEach { (key, item) -> keys[key] = item }
            keys[SEARCH_KEY] = SEARCH_LABEL
        }
        keys[PREVIOUS_KEY] = PREVIOUS_LABEL
        keys[NEXT_KEY] = NEXT_LABEL
        return keys
    }

    /**
     * An emoji's variants shown on [page] of their pages. [customizableKey] is the key whose own
     * mapping holds the emoji, or null for an emoji of a generated page.
     */
    data class Variants(val items: List<String>, val page: Int = 0, val customizableKey: Int? = null) {
        val pageCount: Int get() = (items.size + PER_PAGE - 1) / PER_PAGE
    }

    /** An emoji's skin tones (and other variants), one per key; Q goes back, P on when they need more pages. */
    fun variantKeys(variants: Variants): Map<Int, String> {
        val keys = LinkedHashMap<Int, String>()
        SLOTS.zip(variants.items.drop(variants.page * PER_PAGE)).forEach { (key, item) -> keys[key] = item }
        keys[PREVIOUS_KEY] = PREVIOUS_LABEL
        if (variants.pageCount > 1) keys[NEXT_KEY] = NEXT_LABEL
        if (variants.customizableKey != null) keys[EDIT_KEY] = EDIT_LABEL
        return keys
    }

    fun isNavigationKey(page: Page, keyCode: Int): Boolean =
        keyCode == PREVIOUS_KEY || keyCode == NEXT_KEY || (page.mapping == null && keyCode == SEARCH_KEY)

    /** The page [by] pages on from [index], going round. */
    fun turn(pages: List<Page>, index: Int, by: Int): Int {
        if (pages.isEmpty()) return 0
        return ((index + by) % pages.size + pages.size) % pages.size
    }

    /** The first page of the section [by] sections on from the one [index] is in, going round. */
    fun jumpSection(pages: List<Page>, index: Int, by: Int): Int {
        if (pages.isEmpty()) return 0
        val starts = pages.indices.filter { it == 0 || pages[it].section != pages[it - 1].section }
        val current = starts.indexOfLast { it <= index }.coerceAtLeast(0)
        val target = ((current + by) % starts.size + starts.size) % starts.size
        return starts[target]
    }
}
