package rocks.pkb.plektra

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MarkdownStyledTextTest {
    private fun styled(text: String, vararg spans: Triple<Any, Int, Int>) = SpannableStringBuilder(text).apply {
        spans.forEach { (span, start, end) -> setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    @Test fun characterStylesAndLinksBecomeMarkdown() {
        val text = styled("bold italic both gone code link",
            Triple(StyleSpan(Typeface.BOLD), 0, 4),
            Triple(StyleSpan(Typeface.ITALIC), 5, 11),
            Triple(StyleSpan(Typeface.BOLD_ITALIC), 12, 16),
            Triple(StrikethroughSpan(), 17, 21),
            Triple(TypefaceSpan("monospace"), 22, 26),
            Triple(URLSpan("https://pkb.rocks/a b"), 27, 31))
        assertEquals("**bold** *italic* ***both*** ~~gone~~ `code` [link](https://pkb.rocks/a%20b)",
            MarkdownStyledText.toMarkdown(text))
    }

    @Test fun nestedAndOverlappingStylesStayBalanced() {
        val text = styled("one two three",
            Triple(StyleSpan(Typeface.BOLD), 0, 13),
            Triple(StyleSpan(Typeface.ITALIC), 4, 7))
        assertEquals("**one *two* three**", MarkdownStyledText.toMarkdown(text))
        val overlapping = styled("abcdef",
            Triple(StyleSpan(Typeface.BOLD), 0, 4),
            Triple(StyleSpan(Typeface.ITALIC), 2, 6))
        val markdown = MarkdownStyledText.toMarkdown(overlapping)!!
        val rendered = io.noties.markwon.Markwon.create(org.robolectric.RuntimeEnvironment.getApplication()).toMarkdown(markdown)
        assertEquals(markdown, "abcdef", rendered.toString())
    }

    @Test fun markersHugTextAndRestartOnEveryLine() {
        val text = styled(" bold \nnext line", Triple(StyleSpan(Typeface.BOLD), 0, 16))
        assertEquals(" **bold** \n**next line**", MarkdownStyledText.toMarkdown(text))
    }

    @Test fun linkTextAndCodeWithBackticksStayLiteral() {
        val text = styled("[x] a`b", Triple(URLSpan("mailto:a@b.c"), 0, 3), Triple(TypefaceSpan("monospace"), 4, 7))
        assertEquals("[\\[x\\]](mailto:a@b.c) ``a`b``", MarkdownStyledText.toMarkdown(text))
    }

    @Test fun unsupportedLinksAndUnderlineLeavePlainText() {
        val text = styled("file under", Triple(URLSpan("file:///data/x"), 0, 4), Triple(UnderlineSpan(), 5, 10))
        assertEquals("file under", MarkdownStyledText.toMarkdown(text))
    }

    @Test fun textWithoutFormattingIsLeftAlone() {
        assertNull(MarkdownStyledText.toMarkdown(SpannableStringBuilder("*plain* text")))
    }
}
