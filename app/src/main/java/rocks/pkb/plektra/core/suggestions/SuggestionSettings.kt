package rocks.pkb.plektra.core.suggestions

data class SuggestionSettings(
    val textReplacementsEnabled: Boolean = true,
    val suggestionsEnabled: Boolean = true,
    val personalizationEnabled: Boolean = true,
    /** Start words fill an empty field; without personalization they come from the bundled dictionary only. */
    val starterWordsEnabled: Boolean = true,
    val showAddWordButton: Boolean = true,
    val accentMatching: Boolean = true,
    val autoReplaceOnSpaceEnter: Boolean = false,
    val maxAutoReplaceDistance: Int = 1,
    val maxSuggestions: Int = 3,
    val useKeyboardProximity: Boolean = false,
    val useEditTypeRanking: Boolean = false,
    val frenchPunctuationSpacing: Boolean = false,
    val commaSpace: Boolean = false,
    val autoSpacePunctuation: String = rocks.pkb.plektra.core.Punctuation.DEFAULT_AUTO_SPACE
)
