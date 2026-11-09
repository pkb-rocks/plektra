package rocks.pkb.plektra.data.symbols

/**
 * The symbols offered page by page on the symbols page, 23 per page (one per key from W along
 * the rows). Text symbols only, none that phones draw as emoji.
 *
 * Adapted from Flux Keyboard (GPL-3.0), SymbolPages.kt.
 */
object SymbolPages {
    val PAGES: List<List<String>> = listOf(
        // Common
        "@ # & * % ^ _ ~ | \\ / < > { } [ ] = + - ! ? :",
        // Arrows
        "← → ↑ ↓ ↔ ↕ ⇐ ⇒ ⇑ ⇓ ⇔ ↖ ↗ ↘ ↙ ↩ ↪ ⟵ ⟶ ➜ ↻ ↺ ⇄",
        // Maths
        "± × ÷ ≈ ≠ ≤ ≥ ∞ √ ∑ ∏ ∫ ∂ ∆ π µ ° ‰ ¹ ² ³ ½ ¼",
        // Currency and units
        "€ £ ¥ ¢ $ ₹ ₽ ₩ ₺ ₿ ₴ ₫ ₪ ₦ ₱ ₣ ℃ ℉ № ℓ ㎏ ㎝ ㎡",
        // Punctuation and typography
        "“ ” ‘ ’ « » ‹ › „ ‚ … – — · • ¡ ¿ § ¶ † ‡ ‽ ′",
        // Shapes, stars and marks
        "★ ☆ ✦ ✧ ● ○ ◆ ◇ ■ □ ▲ △ ▼ ▽ ♡ ♢ ♧ ♤ ✓ ✗ ✘ ☐ ☒",
        // Music, keys and odds and ends
        "♪ ♫ ♬ ♩ ☾ ✎ ⌘ ⌥ ⇧ ⌫ ⏎ ⎋ ⇥ ✱ ❖ ∴ ∵ ※ ⁂ ∅ ∈ ∩ ∪",
    ).map { page -> page.split(' ').filter { it.isNotEmpty() } }

    val ALL: List<String> get() = PAGES.flatten()
}
