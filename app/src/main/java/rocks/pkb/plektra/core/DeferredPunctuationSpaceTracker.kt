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

    @Volatile
    private var pending: Boolean = false

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
        return insertedSpace
    }

    fun clear() {
        pending = false
    }

    /**
     * The editor reported a collapsed cursor at [reportedCursor] that is not plain forward
     * typing. A real cursor move drops the pending space. A stale report does not: when the
     * IME replaces text it just typed (delete, then re-commit), the editor may report the
     * delete on its own after the re-commit already happened. The editor's current cursor
     * then differs from the reported one and a later report brings it up to date, so the
     * pending space is kept. If the editor cannot be read, the move counts as real.
     */
    fun onCursorMoved(inputConnection: InputConnection?, reportedCursor: Int) {
        if (!pending) return
        val current = inputConnection?.let(::readCollapsedCursor)
        if (current != null && current != reportedCursor) return
        pending = false
    }

    private fun readCollapsedCursor(inputConnection: InputConnection): Int? {
        val extracted = try {
            inputConnection.getExtractedText(ExtractedTextRequest(), 0)
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
        }
    }

    internal fun isPending(): Boolean = pending
}
