package rocks.pkb.plektra.statistics

import android.view.View
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingStatisticsCommitCallbacksTest {
    @Test fun failedSuggestionCommitDoesNotProduceStatistics() {
        assertEquals(emptyList<String>(), clickSuggestion(false))
    }

    @Test fun successfulMultiwordCommitNotifiesExactlyOnce() {
        assertEquals(listOf("three example words"), clickSuggestion(true))
    }

    private fun clickSuggestion(committed: Boolean): List<String> {
        val input = mock(InputConnection::class.java)
        `when`(input.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("")
        `when`(input.getTextAfterCursor(anyInt(), anyInt())).thenReturn("")
        `when`(input.commitText(any(CharSequence::class.java), anyInt())).thenReturn(committed)
        val accepted = mutableListOf<String>()
        val listener = SuggestionButtonHandler.createSuggestionClickListener(
            suggestion = "three example words",
            inputConnection = input,
            shouldDisableAutoCapitalize = true,
            onSuggestionCommitted = { accepted.add(it) }
        )
        listener.onClick(View(RuntimeEnvironment.getApplication()))
        return accepted
    }
}
