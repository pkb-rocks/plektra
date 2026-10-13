package rocks.pkb.plektra.core

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeferredPunctuationSpaceTrackerTest {

    private lateinit var context: Context
    private lateinit var inputConnection: FakeInputConnection

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SettingsManager.setSpaceAfterPunctuation(context, "?!")
        DeferredPunctuationSpaceTracker.clear()
        inputConnection = FakeInputConnection(context)
    }

    @Test
    fun configuredPunctuationDefersSpaceUntilNextText() {
        commit("?")

        assertEquals("?", inputConnection.text)
        assertTrue(DeferredPunctuationSpaceTracker.isPending())

        commit("W")

        assertEquals("? W", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun explicitSpaceConsumesPendingWithoutDuplication() {
        commit("!")
        commit(" ")

        assertEquals("! ", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun punctuationSequenceKeepsSpaceDeferred() {
        commit("?")
        commit("!")
        commit("W")

        assertEquals("?! W", inputConnection.text)
    }

    @Test
    fun clearingBeforeSendLeavesNoTrailingSpace() {
        commit("!")

        DeferredPunctuationSpaceTracker.clear()

        assertEquals("!", inputConnection.text)
        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun staleCursorReportKeepsPendingSpace() {
        commit("a")
        commit("!")

        // The editor reports cursor 1 (the delete half of a delete + re-commit) while it
        // already sits at 2 after the re-commit.
        DeferredPunctuationSpaceTracker.onCursorMoved(inputConnection, 1)

        assertTrue(DeferredPunctuationSpaceTracker.isPending())
        commit("b")
        assertEquals("a! b", inputConnection.text)
    }

    @Test
    fun realCursorMoveDropsPendingSpace() {
        commit("a")
        commit("!")
        inputConnection.cursorOverride = 0

        DeferredPunctuationSpaceTracker.onCursorMoved(inputConnection, 0)

        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    @Test
    fun unreadableEditorTreatsCursorReportAsRealMove() {
        commit("!")
        inputConnection.extractedTextAvailable = false

        DeferredPunctuationSpaceTracker.onCursorMoved(inputConnection, 0)

        assertFalse(DeferredPunctuationSpaceTracker.isPending())
    }

    private fun commit(text: String) {
        DeferredPunctuationSpaceTracker.prepareForTextCommit(context, inputConnection, text)
        inputConnection.commitText(text, 1)
    }

    private class FakeInputConnection(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder()
        var cursorOverride: Int? = null
        var extractedTextAvailable = true

        val text: String
            get() = buffer.toString()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (!extractedTextAvailable) return null
            val cursor = cursorOverride ?: buffer.length
            return ExtractedText().apply {
                this.text = buffer.toString()
                startOffset = 0
                selectionStart = cursor
                selectionEnd = cursor
            }
        }
    }
}
