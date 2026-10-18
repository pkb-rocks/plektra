package rocks.pkb.plektra.core

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager

/**
 * Defers spaces after configured punctuation until more text is actually typed.
 * This avoids trailing whitespace when punctuation ends a message.
 */
object DeferredPunctuationSpaceTracker {
    private const val NO_SPACE_BEFORE: String = ".,;:!?/\\)]}»›"
    private const val NO_CURSOR: Int = -1

    // Only the cursor is read, so the editor need not send the document across.
    private const val CURSOR_HINT_MAX_CHARS: Int = 64

    @Volatile
    private var pending: Boolean = false

    // Where the editor's cursor sat once the punctuation that made the space pending was
    // typed; -1 until the editor has reported it.
    @Volatile
    private var settledCursor: Int = NO_CURSOR

    fun prepareForTextCommit(
        context: Context,
        inputConnection: InputConnection,
        text: CharSequence
    ): Boolean {
        val first = text.firstOrNull() ?: return false
        if (first.isWhitespace()) {
            pending = false
            return false
        }

        val hadPending = pending
        var insertedSpace = false
        if (hadPending && first !in NO_SPACE_BEFORE) {
            inputConnection.commitText(" ", 1)
            pending = false
            insertedSpace = true
        }

        val configured = SettingsManager.getSpaceAfterPunctuation(context)
        pending = when {
            first in configured -> true
            hadPending && first in NO_SPACE_BEFORE -> true
            else -> false
        }
        settledCursor = NO_CURSOR
        return insertedSpace
    }

    fun clear() {
        pending = false
        settledCursor = NO_CURSOR
    }

    /**
     * The editor reported plain forward typing that ended at [cursor]. While a space is
     * pending, the first such report is the cursor right after the punctuation.
     */
    fun onCursorAdvanced(cursor: Int) {
        if (pending && settledCursor == NO_CURSOR) settledCursor = cursor
    }

    /**
     * The editor reported a collapsed cursor at [reportedCursor] that is not plain forward
     * typing. A real cursor move drops the pending space. A stale report does not: when the
     * IME replaces text it just typed (delete, then re-commit), the editor may report the
     * delete on its own after the re-commit already happened. The editor's current cursor
     * then differs from the reported one and a later report brings it up to date, so the
     * pending space is kept, but only when the editor sits where the punctuation left it. If
     * the editor cannot be read, the move counts as real.
     */
    fun onCursorMoved(inputConnection: InputConnection?, reportedCursor: Int) {
        if (!pending) return
        val current = inputConnection?.let(::readCollapsedCursor)
        if (current != null && current != reportedCursor &&
            (settledCursor == NO_CURSOR || current == settledCursor)
        ) return
        clear()
    }

    private fun readCollapsedCursor(inputConnection: InputConnection): Int? {
        val extracted = try {
            inputConnection.getExtractedText(
                ExtractedTextRequest().apply { hintMaxChars = CURSOR_HINT_MAX_CHARS },
                0
            )
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionStart != extracted.selectionEnd) return null
        return extracted.startOffset + extracted.selectionStart
    }

    fun onTextCommitted(context: Context, text: CharSequence) {
        val first = text.firstOrNull() ?: return
        if (first in SettingsManager.getSpaceAfterPunctuation(context)) {
            pending = true
            settledCursor = NO_CURSOR
        }
    }

    internal fun isPending(): Boolean = pending
}
