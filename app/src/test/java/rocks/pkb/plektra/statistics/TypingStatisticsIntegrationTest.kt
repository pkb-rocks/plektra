package rocks.pkb.plektra.statistics

import android.content.res.Configuration
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsDestination
import rocks.pkb.plektra.SettingLinkIds
import rocks.pkb.plektra.SettingLinkRegistry
import rocks.pkb.plektra.core.suggestions.SuggestionController
import rocks.pkb.plektra.core.suggestions.SuggestionSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingStatisticsIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun manualCountingWorksEvenWithSuggestionsDisabledWithoutReadingEditorPerKey() {
        val controller = controller()
        val ic = mock(InputConnection::class.java)
        try {
            controller.onCharacterCommitted("Bonjour", ic)
            controller.onTypingStatisticsBoundary()
            controller.onTypingStatisticsBoundary()
            assertEquals(TypingStatisticsSnapshot(1, 0), TypingStatisticsStore.get(context).snapshot.value)
            verifyNoInteractions(ic)
        } finally { controller.destroy() }
    }

    @Test fun suggestionCallbackConsumesPrefixAndTrailingBoundaryCannotDoubleCount() {
        val controller = controller()
        try {
            controller.onCharacterCommitted("à", null)
            controller.onSuggestionCommitted("à la plage")
            controller.onTypingStatisticsBoundary()
            controller.finishTypingStatistics()
            assertEquals(TypingStatisticsSnapshot(0, 3), TypingStatisticsStore.get(context).snapshot.value)
        } finally { controller.destroy() }
    }

    @Test fun accentVariationThenSuggestionDoesNotCountPrefixAsAnotherWord() {
        val controller = controller()
        try {
            controller.onCharacterCommitted("cafe", null)
            controller.onCharacterCommitted("é", null, replacesPreviousCharacter = true)
            controller.onSuggestionCommitted("café")
            controller.finishTypingStatistics()
            assertEquals(TypingStatisticsSnapshot(0, 1), TypingStatisticsStore.get(context).snapshot.value)
        } finally { controller.destroy() }
    }

    @Test fun statisticsMenuHasItsOwnSearchableRoute() {
        val entry = requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.MAIN_TYPING_STATISTICS))
        assertEquals(SettingsDestination.TypingStatistics, entry.route.destination)
        assertTrue(SettingLinkRegistry.search(context, context.getString(R.string.typing_statistics_title))
            .any { it.id == entry.id })
    }

    @Test fun frenchLabelsMatchRequestedScreen() {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.FRENCH)
        })
        assertEquals("Statistiques de frappe", localized.getString(R.string.typing_statistics_title))
        assertEquals("Nombre total de mots saisis", localized.getString(R.string.typing_statistics_total_words))
        assertEquals("Mots saisis manuellement", localized.getString(R.string.typing_statistics_manual_words))
        assertEquals("Mots saisis par suggestions", localized.getString(R.string.typing_statistics_suggested_words))
    }


    @Test fun existingFieldContextIsReadAtEntryAndNotOnEveryCommittedLetter() {
        val controller = controller()
        val input = mock(InputConnection::class.java)
        `when`(input.getTextBeforeCursor(1, 0)).thenReturn("d")
        `when`(input.getTextAfterCursor(1, 0)).thenReturn("")
        try {
            controller.onTypingStatisticsEditorStarted(input)
            clearInvocations(input)
            controller.onCharacterCommitted("s", input)
            controller.onTypingStatisticsBoundary()
            assertEquals(TypingStatisticsSnapshot(), TypingStatisticsStore.get(context).snapshot.value)
            verifyNoInteractions(input)
        } finally { controller.destroy() }
    }

    private fun controller() = SuggestionController(
        context = context, assets = context.assets, settingsProvider = { SuggestionSettings() },
        isEnabled = { false }, onSuggestionsUpdated = {}
    )
}
