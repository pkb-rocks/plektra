package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedRowPriorityTest {
    private fun select(suggestions: Boolean, variations: Boolean, variationsFirst: Boolean = false) =
        SharedRowPriority.select(suggestions, variations, variationsFirst)

    @Test fun wordSuggestionsComeFirstByDefault() {
        assertEquals(SharedRowContent.SUGGESTIONS, select(suggestions = true, variations = true))
        assertEquals(SharedRowContent.SUGGESTIONS, select(suggestions = true, variations = false))
    }

    @Test fun variationsFirstSwapsOnlySuggestionsAndDynamicVariations() {
        assertEquals(SharedRowContent.DYNAMIC_VARIATIONS, select(suggestions = true, variations = true, variationsFirst = true))
        assertEquals(SharedRowContent.SUGGESTIONS, select(suggestions = true, variations = false, variationsFirst = true))
    }

    @Test fun missingSuggestionsFallBackToDynamicThenStaticCharacters() {
        listOf(false, true).forEach { variationsFirst ->
            assertEquals(SharedRowContent.DYNAMIC_VARIATIONS, select(false, true, variationsFirst))
            assertEquals(SharedRowContent.STATIC_VARIATIONS, select(false, false, variationsFirst))
        }
    }
}
