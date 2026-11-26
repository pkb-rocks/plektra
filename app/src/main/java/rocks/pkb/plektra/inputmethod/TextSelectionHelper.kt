package rocks.pkb.plektra.inputmethod

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.util.Log

/**
 * Helper for handling text selection operations.
 */
object TextSelectionHelper {
    private const val TAG = "TextSelectionHelper"
    private const val WORD_CONTEXT_CHARS = 1000

    private data class SelectionAnchorState(
        val anchor: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private enum class SelectionDirection {
        Left,
        Right
    }

    private var selectionAnchorState: SelectionAnchorState? = null

    /**
     * The field's text and selection. An app may hand over only part of a long field ([text]
     * starting at [offset]). Store selection positions relative to the entire field.
     */
    private class Extracted(val text: String?, val offset: Int, val selectionStart: Int, val selectionEnd: Int) {
        val end: Int get() = offset + (text?.length ?: 0)
        val firstSelectionEdge: Int get() = minOf(selectionStart, selectionEnd)
        val lastSelectionEdge: Int get() = maxOf(selectionStart, selectionEnd)
        /** [position] in the whole field, as a position in [text]. */
        fun local(position: Int): Int = (position - offset).coerceIn(0, text?.length ?: 0)
    }

    private fun extract(inputConnection: InputConnection): Extracted? {
        val extracted = inputConnection.getExtractedText(
            ExtractedTextRequest().apply { flags = android.view.inputmethod.ExtractedText.FLAG_SELECTING },
            0
        ) ?: return null
        val offset = extracted.startOffset.coerceAtLeast(0)
        fun whole(position: Int) = if (position < 0) position else position + offset
        return Extracted(extracted.text?.toString(), offset, whole(extracted.selectionStart), whole(extracted.selectionEnd))
    }

    fun clearSelectionAnchor() {
        selectionAnchorState = null
    }

    private fun Extracted.hasCharacterAfter(connection: InputConnection, position: Int): Boolean =
        position < end ||
            (position == lastSelectionEdge && !connection.getTextAfterCursor(1, 0).isNullOrEmpty())

    private fun Extracted.wordStartToLeft(connection: InputConnection, position: Int): Int {
        val value = text ?: return position
        val localStart = previousWordStart(value, local(position))
        if (localStart > 0 || offset == 0) return offset + localStart

        val earlier = connection.getTextBeforeCursor(WORD_CONTEXT_CHARS, 0)?.toString()
            ?: return offset
        val firstSelected = local(firstSelectionEdge)
        if (earlier.length <= firstSelected) return offset
        val context = earlier + value.substring(firstSelected, local(position))
        return position - (context.length - previousWordStart(context, context.length))
    }

    private fun Extracted.wordBoundaryToRight(
        connection: InputConnection,
        position: Int,
        extendSelection: Boolean
    ): Int {
        val value = text ?: return position
        val findBoundary = if (extendSelection) ::nextWordEnd else ::nextWordStart
        val boundary = findBoundary(value, local(position))
        if (boundary < value.length) return offset + boundary

        val later = connection.getTextAfterCursor(WORD_CONTEXT_CHARS, 0)?.toString()
            ?: return end
        val lastSelected = local(lastSelectionEdge)
        if (later.length <= value.length - lastSelected) return end
        val context = value.substring(local(position), lastSelected) + later
        return position + findBoundary(context, 0)
    }

    private fun selectionAnchor(
        selectionStart: Int,
        selectionEnd: Int,
        direction: SelectionDirection
    ): Int {
        val matchingAnchor = selectionAnchorState
            ?.takeIf { it.selectionStart == selectionStart && it.selectionEnd == selectionEnd }
            ?.anchor
        if (matchingAnchor != null) {
            return matchingAnchor
        }
        return when (direction) {
            SelectionDirection.Left -> selectionEnd
            SelectionDirection.Right -> selectionStart
        }
    }

    private fun movingSelectionEdge(selectionStart: Int, selectionEnd: Int, anchor: Int): Int {
        return if (anchor == selectionStart) selectionEnd else selectionStart
    }

    private fun applySelectionMove(
        inputConnection: InputConnection,
        anchor: Int,
        newMovingEdge: Int
    ): Boolean {
        if (newMovingEdge == anchor) {
            if (!inputConnection.setSelection(anchor, anchor)) return false
            clearSelectionAnchor()
            return true
        }

        val newStart = minOf(anchor, newMovingEdge)
        val newEnd = maxOf(anchor, newMovingEdge)
        if (!inputConnection.setSelection(newStart, newEnd)) return false
        selectionAnchorState = SelectionAnchorState(
            anchor = anchor,
            selectionStart = newStart,
            selectionEnd = newEnd
        )
        return true
    }

    private fun previousWordStart(text: String, cursorPosition: Int): Int {
        var position = cursorPosition.coerceIn(0, text.length)
        while (position > 0 && text[position - 1].isWhitespace()) {
            position--
        }
        while (position > 0 && !text[position - 1].isWhitespace()) {
            position--
        }
        return position
    }

    private fun nextWordStart(text: String, cursorPosition: Int): Int {
        var position = cursorPosition.coerceIn(0, text.length)
        while (position < text.length && !text[position].isWhitespace()) {
            position++
        }
        while (position < text.length && text[position].isWhitespace()) {
            position++
        }
        return position
    }

    private fun nextWordEnd(text: String, cursorPosition: Int): Int {
        var position = cursorPosition.coerceIn(0, text.length)
        while (position < text.length && text[position].isWhitespace()) {
            position++
        }
        while (position < text.length && !text[position].isWhitespace()) {
            position++
        }
        return position
    }
    
    /**
     * Expands selection one character to the left.
     * If there's no selection, creates a selection of one character to the left of cursor.
     */
    fun expandSelectionLeft(inputConnection: InputConnection): Boolean {
        try {
            // Ottieni la selezione corrente usando ExtractedTextRequest
            val extractedText = extract(inputConnection)
            
            if (extractedText == null) {
                // Fallback: usa getTextBeforeCursor e getTextAfterCursor per stimare la posizione
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)
                val textAfter = inputConnection.getTextAfterCursor(1, 0)
                
                if (textBefore != null && textBefore.isNotEmpty()) {
                    // If there's text after, there's probably a selection
                    // For simplicity, assume cursor is at end of text before
                    val currentPos = textBefore.length
                    val newStart = currentPos - 1
                    
                    if (newStart >= 0) {
                        // Create or expand selection one character to the left
                        inputConnection.setSelection(newStart, currentPos)
                        selectionAnchorState = SelectionAnchorState(currentPos, newStart, currentPos)
                        Log.d(TAG, "expandSelectionLeft: selection created/expanded to [$newStart, $currentPos]")
                        return true
                    }
                }
                return false
            }
            
            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            
            if (selectionStart < 0 || selectionEnd < 0) {
                Log.d(TAG, "expandSelectionLeft: unable to get selection")
                return false
            }
            
            val anchor = selectionAnchor(selectionStart, selectionEnd, SelectionDirection.Left)
            val movingEdge = movingSelectionEdge(selectionStart, selectionEnd, anchor)
            val newMovingEdge = movingEdge - 1
            if (newMovingEdge < 0) {
                Log.d(TAG, "expandSelectionLeft: selection already at start of text, can't move edge left")
                return false
            }

            val handled = applySelectionMove(inputConnection, anchor, newMovingEdge)
            Log.d(TAG, "expandSelectionLeft: selection moved from [$selectionStart, $selectionEnd] to edge $newMovingEdge with anchor $anchor")
            return handled
        } catch (e: Exception) {
            Log.e(TAG, "Error in expandSelectionLeft", e)
        }
        return false
    }
    
    /**
     * Expands selection one character to the right.
     * If there's no selection, creates a selection of one character to the right of cursor.
     */
    fun expandSelectionRight(inputConnection: InputConnection): Boolean {
        try {
            // Ottieni la selezione corrente usando ExtractedTextRequest
            val extractedText = extract(inputConnection)
            
            if (extractedText == null) {
                // Fallback: usa getTextBeforeCursor e getTextAfterCursor per stimare la posizione
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)
                val textAfter = inputConnection.getTextAfterCursor(1000, 0)
                
                if (textAfter != null && textAfter.isNotEmpty()) {
                    // If there's text after, we can expand selection
                    val currentPos = textBefore?.length ?: 0
                    val newEnd = currentPos + 1
                    
                    // Create or expand selection one character to the right
                    inputConnection.setSelection(currentPos, newEnd)
                    selectionAnchorState = SelectionAnchorState(currentPos, currentPos, newEnd)
                    Log.d(TAG, "expandSelectionRight: selection created/expanded to [$currentPos, $newEnd]")
                    return true
                }
                return false
            }
            
            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            
            if (selectionStart < 0 || selectionEnd < 0) {
                Log.d(TAG, "expandSelectionRight: unable to get selection")
                return false
            }
            
            val anchor = selectionAnchor(selectionStart, selectionEnd, SelectionDirection.Right)
            val movingEdge = movingSelectionEdge(selectionStart, selectionEnd, anchor)
            if (!extractedText.hasCharacterAfter(inputConnection, movingEdge)) return false
            val newMovingEdge = movingEdge + 1
            val handled = applySelectionMove(inputConnection, anchor, newMovingEdge)
            Log.d(TAG, "expandSelectionRight: selection moved from [$selectionStart, $selectionEnd] to edge $newMovingEdge with anchor $anchor")
            return handled
        } catch (e: Exception) {
            Log.e(TAG, "Error in expandSelectionRight", e)
        }
        return false
    }
    
    /**
     * Moves cursor one character to the left (without creating a selection).
     * This is safer than using DPAD keys as it only affects the text field, not UI navigation.
     * 
     * @return true if cursor was moved, false if already at start or error occurred
     */
    fun moveCursorLeft(inputConnection: InputConnection): Boolean {
        try {
            // Get current cursor position using ExtractedTextRequest
            val extractedText = extract(inputConnection)
            
            if (extractedText == null) {
                // Fallback: use getTextBeforeCursor to estimate position
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)
                
                if (textBefore != null && textBefore.isNotEmpty()) {
                    val currentPos = textBefore.length
                    val newPos = currentPos - 1
                    
                    if (newPos >= 0) {
                        // Move cursor without creating selection (same start and end)
                        inputConnection.setSelection(newPos, newPos)
                        clearSelectionAnchor()
                        Log.d(TAG, "moveCursorLeft: cursor moved from $currentPos to $newPos")
                        return true
                    }
                }
                return false
            }
            
            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            
            if (selectionStart < 0 || selectionEnd < 0) {
                Log.d(TAG, "moveCursorLeft: unable to get cursor position")
                return false
            }
            
            // If there's a selection, collapse it to the start position first
            val currentPos = if (selectionStart != selectionEnd) {
                selectionStart // Collapse selection to start
            } else {
                selectionStart // Already at cursor position
            }
            
            // Can't move left if already at start
            if (currentPos <= 0) {
                Log.d(TAG, "moveCursorLeft: cursor already at start of text")
                return false
            }
            
            val newPos = currentPos - 1
            
            // Move cursor without creating selection (same start and end)
            inputConnection.setSelection(newPos, newPos)
            clearSelectionAnchor()
            Log.d(TAG, "moveCursorLeft: cursor moved from $currentPos to $newPos")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in moveCursorLeft", e)
        }
        return false
    }
    
    /**
     * Moves cursor one character to the right (without creating a selection).
     * This is safer than using DPAD keys as it only affects the text field, not UI navigation.
     * 
     * @return true if cursor was moved, false if already at end or error occurred
     */
    fun moveCursorRight(inputConnection: InputConnection): Boolean {
        try {
            // Get current cursor position using ExtractedTextRequest
            val extractedText = extract(inputConnection)
            
            if (extractedText == null) {
                // Fallback: use getTextBeforeCursor and getTextAfterCursor to estimate position
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)
                val textAfter = inputConnection.getTextAfterCursor(1, 0)
                
                if (textAfter != null && textAfter.isNotEmpty()) {
                    val currentPos = textBefore?.length ?: 0
                    val newPos = currentPos + 1
                    
                    // Move cursor without creating selection (same start and end)
                    inputConnection.setSelection(newPos, newPos)
                    clearSelectionAnchor()
                    Log.d(TAG, "moveCursorRight: cursor moved from $currentPos to $newPos")
                    return true
                }
                return false
            }
            
            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            
            if (selectionStart < 0 || selectionEnd < 0) {
                Log.d(TAG, "moveCursorRight: unable to get cursor position")
                return false
            }
            
            val currentPos = extractedText.lastSelectionEdge
            if (!extractedText.hasCharacterAfter(inputConnection, currentPos)) return false
            val newPos = currentPos + 1
            if (!inputConnection.setSelection(newPos, newPos)) return false
            clearSelectionAnchor()
            Log.d(TAG, "moveCursorRight: cursor moved from $currentPos to $newPos")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in moveCursorRight", e)
        }
        return false
    }

    fun moveCursorWordLeft(inputConnection: InputConnection): Boolean {
        try {
            val extractedText = extract(inputConnection)

            if (extractedText == null) {
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: return false
                val newPosition = previousWordStart(textBefore, textBefore.length)
                if (newPosition == textBefore.length) return false
                inputConnection.setSelection(newPosition, newPosition)
                clearSelectionAnchor()
                return true
            }

            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            if (selectionStart < 0 || selectionEnd < 0) return false

            val currentPosition = extractedText.firstSelectionEdge
            val newPosition = extractedText.wordStartToLeft(inputConnection, currentPosition)
            if (newPosition == currentPosition) return false
            if (!inputConnection.setSelection(newPosition, newPosition)) return false
            clearSelectionAnchor()
            Log.d(TAG, "moveCursorWordLeft: cursor moved from $currentPosition to $newPosition")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in moveCursorWordLeft", e)
        }
        return false
    }

    fun moveCursorWordRight(inputConnection: InputConnection): Boolean {
        try {
            val extractedText = extract(inputConnection)

            if (extractedText == null) {
                val textBefore = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                val textAfter = inputConnection.getTextAfterCursor(1000, 0)?.toString() ?: return false
                val combinedText = textBefore + textAfter
                val currentPosition = textBefore.length
                val newPosition = nextWordStart(combinedText, currentPosition)
                if (newPosition == currentPosition) return false
                inputConnection.setSelection(newPosition, newPosition)
                clearSelectionAnchor()
                return true
            }

            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            if (selectionStart < 0 || selectionEnd < 0) return false

            val currentPosition = extractedText.lastSelectionEdge
            val newPosition = extractedText.wordBoundaryToRight(inputConnection, currentPosition, extendSelection = false)
            if (newPosition == currentPosition) return false
            if (!inputConnection.setSelection(newPosition, newPosition)) return false
            clearSelectionAnchor()
            Log.d(TAG, "moveCursorWordRight: cursor moved from $currentPosition to $newPosition")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in moveCursorWordRight", e)
        }
        return false
    }

    fun expandSelectionWordLeft(inputConnection: InputConnection): Boolean {
        try {
            val extractedText = extract(inputConnection) ?: return false

            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            if (selectionStart < 0 || selectionEnd < 0) return false

            val anchor = selectionAnchor(selectionStart, selectionEnd, SelectionDirection.Left)
            val movingEdge = movingSelectionEdge(selectionStart, selectionEnd, anchor)
            val newMovingEdge = extractedText.wordStartToLeft(inputConnection, movingEdge)
            if (newMovingEdge == movingEdge) return false
            val handled = applySelectionMove(inputConnection, anchor, newMovingEdge)
            Log.d(TAG, "expandSelectionWordLeft: selection moved from [$selectionStart, $selectionEnd] to edge $newMovingEdge with anchor $anchor")
            return handled
        } catch (e: Exception) {
            Log.e(TAG, "Error in expandSelectionWordLeft", e)
        }
        return false
    }

    fun expandSelectionWordRight(inputConnection: InputConnection): Boolean {
        try {
            val extractedText = extract(inputConnection) ?: return false

            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            if (selectionStart < 0 || selectionEnd < 0) return false

            val anchor = selectionAnchor(selectionStart, selectionEnd, SelectionDirection.Right)
            val movingEdge = movingSelectionEdge(selectionStart, selectionEnd, anchor)
            val newMovingEdge = extractedText.wordBoundaryToRight(inputConnection, movingEdge, extendSelection = true)
            if (newMovingEdge == movingEdge) return false
            val handled = applySelectionMove(inputConnection, anchor, newMovingEdge)
            Log.d(TAG, "expandSelectionWordRight: selection moved from [$selectionStart, $selectionEnd] to edge $newMovingEdge with anchor $anchor")
            return handled
        } catch (e: Exception) {
            Log.e(TAG, "Error in expandSelectionWordRight", e)
        }
        return false
    }
    
    /**
     * Deletes last word before cursor.
     */
    fun deleteLastWord(inputConnection: InputConnection): Boolean {
        try {
            // Get text before cursor (up to 100 characters)
            val textBeforeCursor = inputConnection.getTextBeforeCursor(100, 0)
            
            if (textBeforeCursor != null && textBeforeCursor.isNotEmpty()) {
                // Find last word (separated by spaces or at start of text)
                var endIndex = textBeforeCursor.length
                var startIndex = endIndex
                
                // Find end of last word (ignore spaces at end)
                while (startIndex > 0 && textBeforeCursor[startIndex - 1].isWhitespace()) {
                    startIndex--
                }
                
                // Find start of last word (first space or start of text)
                while (startIndex > 0 && !textBeforeCursor[startIndex - 1].isWhitespace()) {
                    startIndex--
                }
                
                // Calculate how many characters to delete
                val charsToDelete = endIndex - startIndex
                
                if (charsToDelete > 0) {
                    // Delete last word (including any spaces after)
                    inputConnection.deleteSurroundingText(charsToDelete, 0)
                    Log.d(TAG, "deleteLastWord: deleted $charsToDelete characters")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteLastWord", e)
        }
        return false
    }
}
