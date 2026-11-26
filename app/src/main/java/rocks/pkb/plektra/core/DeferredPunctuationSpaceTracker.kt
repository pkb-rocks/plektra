package rocks.pkb.plektra.core

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager

/**
 * Defers spaces after configured punctuation until more text is actually typed.
 * This avoids trailing whitespace when punctuation ends a message.
 */
object DeferredPunctuationSpaceTracker {
    private const val NO_SPACE_BEFORE: String = ".,;:!?/\\)]}»›"

    /** Words in a single-line field's hint or name that mark it as an address or a sign-in. */
    private val ADDRESS_HINT_PATTERN =
        Regex("""\b(?:email|e-mail|username|user name|login|sign in|url|website)\b""")

    private val ADDRESS_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_FILTER
    )

    @Volatile
    private var pending: Boolean = false

    /** The punctuation whose space is pending. */
    @Volatile
    private var pendingAfter: Char? = null

    /** Off in fields where a space after "." or "@" would break what is typed: see [appliesTo]. */
    @Volatile
    private var enabled: Boolean = true

    /** Called as each field starts. */
    fun startField(info: EditorInfo?) {
        enabled = appliesTo(info)
        clear()
    }

    /**
     * Spaces after punctuation belong in prose: not in email addresses, web addresses,
     * passwords, sign-in names, numbers, phone numbers or dates.
     */
    fun appliesTo(info: EditorInfo?): Boolean {
        if (info == null) return true
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return type == InputType.TYPE_NULL
        if (type and InputType.TYPE_MASK_VARIATION in ADDRESS_VARIATIONS) return false
        // Sign-in fields often say what they are only in their hint or name. Single-line
        // fields only, so a message body hinted "Compose email" keeps its spaces.
        if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return true
        val hints = "${info.hintText ?: ""} ${info.fieldName ?: ""}".lowercase()
        return !ADDRESS_HINT_PATTERN.containsMatchIn(hints)
    }

    fun prepareForTextCommit(
        context: Context,
        inputConnection: InputConnection,
        text: CharSequence
    ): Boolean {
        val first = text.firstOrNull() ?: return false
        if (!enabled) {
            clear()
            return false
        }
        if (first.isWhitespace()) {
            clear()
            return false
        }

        val hadPending = pending
        val after = pendingAfter
        // Numbers, times and decimals (1,000  12:30  3.14) stay together
        if (hadPending && after != null && first.isDigit() && followsDigit(inputConnection, after)) {
            clear()
            return false
        }
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
        pendingAfter = when {
            first in configured -> first
            pending -> after
            else -> null
        }
        return insertedSpace
    }

    /** Whether the text before the cursor ends in a digit then [punctuation], as in "12:". */
    private fun followsDigit(inputConnection: InputConnection, punctuation: Char): Boolean {
        val before = inputConnection.getTextBeforeCursor(2, 0) ?: return false
        return before.length == 2 && before[0].isDigit() && before[1] == punctuation
    }

    fun clear() {
        pending = false
        pendingAfter = null
    }

    fun onTextCommitted(context: Context, text: CharSequence) {
        if (!enabled) return
        val first = text.firstOrNull() ?: return
        if (first in SettingsManager.getSpaceAfterPunctuation(context)) {
            pending = true
            pendingAfter = first
        }
    }

    internal fun isPending(): Boolean = pending
}
