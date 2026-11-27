package rocks.pkb.plektra.inputmethod

/** What the combined keyboard bar row shows between its menu buttons, apart from expansions. */
internal enum class SharedRowContent { SUGGESTIONS, DYNAMIC_VARIATIONS, STATIC_VARIATIONS }

/**
 * Precedence of the combined row. Word suggestions come first by default; dynamic variations
 * of the last typed letter fill the row when there are none, and static characters when there
 * are neither, so the row never loses its characters. [variationsFirst] swaps only the first
 * two levels. Without suggestions the row shows variations as before.
 */
internal object SharedRowPriority {
    fun select(
        suggestionsAvailable: Boolean,
        dynamicVariationsAvailable: Boolean,
        variationsFirst: Boolean
    ): SharedRowContent = when {
        dynamicVariationsAvailable && (variationsFirst || !suggestionsAvailable) -> SharedRowContent.DYNAMIC_VARIATIONS
        suggestionsAvailable -> SharedRowContent.SUGGESTIONS
        else -> SharedRowContent.STATIC_VARIATIONS
    }
}
