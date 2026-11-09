package rocks.pkb.plektra.inputmethod

/**
 * Splits the height above the software keyboard between the emoji search popup and the keyboard.
 * When the visible space above the keyboard cannot hold a usable popup, the keyboard yields its
 * top rows to it, but keeps at least half of its height.
 */
internal object EmojiSearchPopupSplit {
    data class Split(val keyboardTopInset: Int, val popupHeight: Int)

    fun resolve(spaceAbove: Int, keyboardHeight: Int, preferredPopupHeight: Int, minimumPopupHeight: Int): Split {
        val space = spaceAbove.coerceAtLeast(0)
        if (space >= minimumPopupHeight) {
            return Split(0, minOf(maxOf(preferredPopupHeight, minimumPopupHeight), space))
        }
        val inset = minOf(minimumPopupHeight - space, keyboardHeight.coerceAtLeast(0) / 2)
        return Split(inset, space + inset)
    }
}
