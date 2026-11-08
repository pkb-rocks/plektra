package rocks.pkb.plektra.clipboard

import android.content.ClipData
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.EditorInfo
import androidx.core.text.HtmlCompat

/**
 * Formatting of copied text: stored as HTML, pasted as styled text.
 *
 * Only character styles that read the same in any editor are kept: bold, italic, underline,
 * strikethrough, sub- and superscript, monospace and web, mail or phone links. Colours,
 * sizes, images and paragraph layout of the source are dropped, so pasted text does not
 * inherit a foreign theme.
 */
internal object ClipboardRichText {
    private val linkSchemes = setOf("http", "https", "mailto", "tel")

    /** HTML for the formatting of [item], or null when it carries none. */
    fun htmlOf(item: ClipData.Item): String? {
        item.htmlText?.takeIf { it.isNotBlank() }?.let { return it }
        val text = item.text as? Spanned ?: return null
        val styled = SpannableStringBuilder(text).also(::keepPortableSpans)
        if (styled.getSpans(0, styled.length, Any::class.java).isEmpty()) return null
        return HtmlCompat.toHtml(styled, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    }

    /** Styled text for [html], or null when nothing printable is left. */
    fun styledText(html: String): CharSequence? {
        val styled = SpannableStringBuilder(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
        styled.getSpans(0, styled.length, ImageSpan::class.java).forEach { span ->
            val start = styled.getSpanStart(span)
            val end = styled.getSpanEnd(span)
            styled.removeSpan(span)
            if (start in 0 until end) styled.delete(start, end)
        }
        keepPortableSpans(styled)
        var end = styled.length
        while (end > 0 && styled[end - 1].isWhitespace()) end--
        styled.delete(end, styled.length)
        return styled.takeIf { it.isNotBlank() }
    }

    /**
     * Whether the editor described by [info] is a general text field where formatting makes
     * sense. Passwords, addresses, numbers, filters and unknown editors get plain text.
     */
    fun acceptsFormatting(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> true
            else -> false
        }
    }

    private fun keepPortableSpans(text: SpannableStringBuilder) {
        text.getSpans(0, text.length, Any::class.java).forEach { span ->
            val portable = when (span) {
                is StyleSpan, is UnderlineSpan, is StrikethroughSpan, is SubscriptSpan, is SuperscriptSpan -> true
                is TypefaceSpan -> span.family == "monospace"
                is URLSpan -> span.url?.substringBefore(':', "")?.lowercase() in linkSchemes
                else -> false
            }
            if (!portable) text.removeSpan(span)
        }
    }
}
