package rocks.pkb.plektra.core

/**
 * Smart toggle: a latched modifier switches itself off when what was just typed shows it is
 * done with (Modifiers, both off by default).
 *
 * - Alt: with Alt latched or locked, an opening quote or bracket typed with Alt means words
 *   come next.
 * - Ctrl: with Ctrl tap latches on, one shortcut (copy, paste, undo, ...) is all a tap was
 *   for; cursor and selection moves keep the latch, since those are pressed again and again.
 */
object SmartModifierToggle {
    private val OPENERS = setOf('(', '[', '{', '<', '“', '‘', '«', '‹', '„', '‚', '¿', '¡', '「', '『', '（')
    private val STRAIGHT_QUOTES = setOf('"', '\'', '`')

    /**
     * [typed] (what Alt typed) opens a quote or bracket. A straight quote only opens after a
     * space, the start of the text or another opener; after a word it closes.
     */
    fun opensQuoteOrBracket(typed: String, textBefore: CharSequence?): Boolean {
        val char = typed.singleOrNull() ?: return false
        if (char in OPENERS) return true
        if (char !in STRAIGHT_QUOTES) return false
        val previous = textBefore?.lastOrNull() ?: return true
        return previous.isWhitespace() || previous in OPENERS
    }

    private val NAVIGATION_KEYCODES = setOf(
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
        "PAGE_UP", "PAGE_DOWN", "MOVE_HOME", "MOVE_END", "TAB"
    )

    private val PAGE_ACTIONS = setOf("page_start", "page_end")

    /** A Ctrl mapping that moves the cursor or the selection (keeps a Ctrl latch on). */
    fun isNavigation(type: String?, value: String?): Boolean = when (type) {
        "keycode" -> value in NAVIGATION_KEYCODES
        "action" -> value != null &&
            (value.startsWith("expand_selection") || value.startsWith("move_") || value in PAGE_ACTIONS)
        else -> false
    }
}
