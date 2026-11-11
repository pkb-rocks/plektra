package rocks.pkb.plektra.inputmethod

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextSelectionHelperWordNavigationTest {

    @Test
    fun moveCursorWordLeft_movesToPreviousWordStart() {
        val inputConnection = inputConnectionWithText(
            text = "alpha beta gamma",
            selectionStart = 16,
            selectionEnd = 16
        )

        assertTrue(TextSelectionHelper.moveCursorWordLeft(inputConnection))

        verify(inputConnection).setSelection(11, 11)
    }

    @Test
    fun moveCursorWordRight_movesToNextWordStart() {
        val inputConnection = inputConnectionWithText(
            text = "alpha beta gamma",
            selectionStart = 0,
            selectionEnd = 0
        )

        assertTrue(TextSelectionHelper.moveCursorWordRight(inputConnection))

        verify(inputConnection).setSelection(6, 6)
    }

    @Test
    fun expandSelectionWordLeft_extendsSelectionToPreviousWordStart() {
        val inputConnection = inputConnectionWithText(
            text = "alpha beta gamma",
            selectionStart = 11,
            selectionEnd = 16
        )

        assertTrue(TextSelectionHelper.expandSelectionWordLeft(inputConnection))

        verify(inputConnection).setSelection(6, 16)
    }

    @Test
    fun expandSelectionWordRight_extendsSelectionToNextWordStart() {
        val inputConnection = inputConnectionWithText(
            text = "alpha beta gamma",
            selectionStart = 0,
            selectionEnd = 5
        )

        assertTrue(TextSelectionHelper.expandSelectionWordRight(inputConnection))

        verify(inputConnection).setSelection(0, 10)
    }

    @Test
    fun wordSelection_reversingDirectionShrinksSelection() {
        val text = "alpha beta gamma"
        val firstInputConnection = inputConnectionWithText(
            text = text,
            selectionStart = 0,
            selectionEnd = 0
        )
        assertTrue(TextSelectionHelper.expandSelectionWordRight(firstInputConnection))
        verify(firstInputConnection).setSelection(0, 5)

        val secondInputConnection = inputConnectionWithText(
            text = text,
            selectionStart = 0,
            selectionEnd = 5
        )
        assertTrue(TextSelectionHelper.expandSelectionWordLeft(secondInputConnection))
        verify(secondInputConnection).setSelection(0, 0)
    }

    @Test
    fun characterSelection_reversingDirectionShrinksSelection() {
        val text = "alpha"
        val firstInputConnection = inputConnectionWithText(
            text = text,
            selectionStart = 5,
            selectionEnd = 5
        )
        assertTrue(TextSelectionHelper.expandSelectionLeft(firstInputConnection))
        verify(firstInputConnection).setSelection(4, 5)

        val secondInputConnection = inputConnectionWithText(
            text = text,
            selectionStart = 4,
            selectionEnd = 5
        )
        assertTrue(TextSelectionHelper.expandSelectionRight(secondInputConnection))
        verify(secondInputConnection).setSelection(5, 5)
    }

    // An app may hand over only part of a long field: the extract starts at startOffset, its
    // selection is relative to the extract, and setSelection takes whole-field positions

    @Test
    fun moveCursorWordLeft_inAPartialExtract_movesInWholeFieldPositions() {
        val inputConnection = inputConnectionWithText("alpha beta gamma", 16, 16, startOffset = 1000)

        assertTrue(TextSelectionHelper.moveCursorWordLeft(inputConnection))

        verify(inputConnection).setSelection(1011, 1011)
    }

    @Test
    fun moveCursorWordRight_inAPartialExtract_movesInWholeFieldPositions() {
        val inputConnection = inputConnectionWithText("alpha beta gamma", 0, 0, startOffset = 1000)

        assertTrue(TextSelectionHelper.moveCursorWordRight(inputConnection))

        verify(inputConnection).setSelection(1006, 1006)
    }

    @Test
    fun expandSelectionWordRight_inAPartialExtract_extendsInWholeFieldPositions() {
        val inputConnection = inputConnectionWithText("alpha beta gamma", 0, 5, startOffset = 1000)

        assertTrue(TextSelectionHelper.expandSelectionWordRight(inputConnection))

        verify(inputConnection).setSelection(1000, 1010)
    }

    @Test
    fun moveCursorRight_atTheEndOfAPartialExtract_stillMovesWithinTheField() {
        // The extract ends mid-field; the cursor at its last character moves one on
        val inputConnection = inputConnectionWithText("alpha beta", 9, 9, startOffset = 1000)

        assertTrue(TextSelectionHelper.moveCursorRight(inputConnection))

        verify(inputConnection).setSelection(1010, 1010)
    }

    private fun inputConnectionWithText(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        startOffset: Int = 0
    ): InputConnection {
        val inputConnection = mock(InputConnection::class.java)
        `when`(inputConnection.getExtractedText(any(ExtractedTextRequest::class.java), anyInt())).thenReturn(
            ExtractedText().apply {
                this.text = text
                this.startOffset = startOffset
                this.selectionStart = selectionStart
                this.selectionEnd = selectionEnd
            }
        )
        `when`(inputConnection.setSelection(anyInt(), anyInt())).thenReturn(true)
        return inputConnection
    }
}
