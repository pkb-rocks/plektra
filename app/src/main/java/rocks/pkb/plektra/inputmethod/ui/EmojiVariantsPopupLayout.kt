package rocks.pkb.plektra.inputmethod.ui

/** Arranges the skin-tone popup so that it stays inside the IME window, however narrow. */
internal object EmojiVariantsPopupLayout {
    /**
     * Options per row: at most [maxPerRow], fewer when such a row and [horizontalPadding] would be
     * wider than [windowWidth]. Rows are filled evenly, so six tones on five columns become 3 + 3.
     */
    fun perRow(optionCount: Int, cellWidth: Int, horizontalPadding: Int, windowWidth: Int, maxPerRow: Int): Int {
        val fitting = ((windowWidth - horizontalPadding) / cellWidth.coerceAtLeast(1)).coerceIn(1, maxPerRow)
        val rows = (optionCount + fitting - 1) / fitting
        return (optionCount + rows - 1) / rows.coerceAtLeast(1)
    }

    /** Left edge centered on the anchor and kept inside the window; a wider popup starts at its left edge. */
    fun left(anchorLeft: Int, anchorWidth: Int, popupWidth: Int, windowWidth: Int): Int =
        (anchorLeft + (anchorWidth - popupWidth) / 2).coerceAtMost(windowWidth - popupWidth).coerceAtLeast(0)
}
