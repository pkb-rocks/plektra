package rocks.pkb.plektra

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan

/**
 * Turns styled text, as pasted or committed by a keyboard, into Markdown source, because the
 * notes editor stores plain strings. Bold, italic, strikethrough, monospace and web, mail or
 * phone links become Markdown; underline, sub- and superscript have no Markdown form and are
 * dropped. The text itself is not escaped, just like a plain paste.
 */
internal object MarkdownStyledText {
    private val linkSchemes = setOf("http", "https", "mailto", "tel")

    private enum class Kind(val open: String, val close: String) {
        LINK("[", ")"), STRIKE("~~", "~~"), BOLD("**", "**"), ITALIC("*", "*"), CODE("`", "`")
    }

    private data class Run(val kind: Kind, val start: Int, val end: Int, val url: String? = null)

    /** Markdown for [text], or null when it carries no formatting the editor would show. */
    fun toMarkdown(text: Spanned): String? {
        val spans = text.getSpans(0, text.length, Any::class.java)
        if (spans.none(::isFormatting)) return null
        val raw = mutableListOf<Run>()
        spans.forEach { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            when (span) {
                is StyleSpan -> {
                    if (span.style and Typeface.BOLD != 0) raw += Run(Kind.BOLD, start, end)
                    if (span.style and Typeface.ITALIC != 0) raw += Run(Kind.ITALIC, start, end)
                }
                is StrikethroughSpan -> raw += Run(Kind.STRIKE, start, end)
                is TypefaceSpan -> if (span.family == "monospace") raw += Run(Kind.CODE, start, end)
                is URLSpan -> span.url?.takeIf { it.substringBefore(':', "").lowercase() in linkSchemes }
                    ?.let { raw += Run(Kind.LINK, start, end, it) }
            }
        }
        val plain = text.toString()
        val runs = raw.flatMap { splitIntoLines(plain, it) }.mapNotNull { trim(plain, it) }.let(::merge)
        if (runs.isEmpty()) return plain
        return render(plain, runs)
    }

    private fun isFormatting(span: Any) = span is StyleSpan || span is StrikethroughSpan || span is TypefaceSpan ||
        span is URLSpan || span is UnderlineSpan || span is SubscriptSpan || span is SuperscriptSpan

    // Inline Markdown cannot continue across a line break, so each line gets its own markers.
    private fun splitIntoLines(text: String, run: Run): List<Run> {
        val parts = mutableListOf<Run>()
        var start = run.start
        while (start < run.end) {
            val newline = text.indexOf('\n', start).takeIf { it in start until run.end } ?: run.end
            parts += run.copy(start = start, end = newline)
            start = newline + 1
        }
        return parts
    }

    // Markers next to spaces do not count as emphasis, so the spaces move outside.
    private fun trim(text: String, run: Run): Run? {
        var start = run.start
        var end = run.end
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) run.copy(start = start, end = end) else null
    }

    private fun merge(runs: List<Run>): List<Run> = runs.groupBy { it.kind to it.url }.values.flatMap { group ->
        group.sortedBy { it.start }.fold(mutableListOf<Run>()) { merged, run ->
            val last = merged.lastOrNull()
            if (last != null && run.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, run.end))
            else merged += run
            merged
        }
    }

    private fun render(text: String, runs: List<Run>): String {
        val boundaries = (runs.flatMap { listOf(it.start, it.end) } + 0 + text.length).distinct().sorted()
        val out = StringBuilder()
        val open = mutableListOf<Run>()
        val underscored = mutableSetOf<Run>()
        for (index in 0 until boundaries.lastIndex) {
            val from = boundaries[index]
            val to = boundaries[index + 1]
            // Links stay outermost and code innermost; the others nest by where they begin.
            val wanted = runs.filter { it.start <= from && it.end >= to }
                .sortedWith(compareBy<Run>({ it.kind != Kind.LINK }, { it.kind == Kind.CODE }, { it.start }, { -it.end }, { it.kind }))
            var common = 0
            while (common < open.size && common < wanted.size && open[common] == wanted[common]) common++
            val closed = open.size > common
            while (open.size > common) out.append(closing(open.removeAt(open.lastIndex), text, underscored))
            wanted.drop(common).forEach { run ->
                // A reopened `*` right after a closing `*` would merge into one delimiter run;
                // underscores keep them apart where they can close, which is before a non-word.
                if (closed && out.endsWith("*") && (run.kind == Kind.BOLD || run.kind == Kind.ITALIC) &&
                    text.getOrNull(run.end)?.isLetterOrDigit() != true) underscored += run
                out.append(if (run in underscored) run.kind.open.replace('*', '_') else opening(run, text))
                open += run
            }
            val content = text.substring(from, to)
            out.append(if (open.lastOrNull()?.kind == Kind.LINK) content.replace("[", "\\[").replace("]", "\\]") else content)
        }
        while (open.isNotEmpty()) out.append(closing(open.removeAt(open.lastIndex), text, underscored))
        return out.toString()
    }

    private fun opening(run: Run, text: String): String = when (run.kind) {
        Kind.CODE -> text.substring(run.start, run.end).let { codeFence(it) + if (it.startsWith('`')) " " else "" }
        else -> run.kind.open
    }

    private fun closing(run: Run, text: String, underscored: Set<Run>): String = when {
        run in underscored -> run.kind.close.replace('*', '_')
        else -> closing(run, text)
    }

    private fun closing(run: Run, text: String): String = when (run.kind) {
        Kind.LINK -> "](${linkDestination(run.url.orEmpty())})"
        Kind.CODE -> text.substring(run.start, run.end).let { (if (it.endsWith('`')) " " else "") + codeFence(it) }
        else -> run.kind.close
    }

    private fun codeFence(content: String): String {
        var longest = 0
        var current = 0
        content.forEach { char ->
            current = if (char == '`') current + 1 else 0
            longest = maxOf(longest, current)
        }
        return "`".repeat(longest + 1)
    }

    private fun linkDestination(url: String): String = buildString {
        url.forEach { char ->
            when (char) {
                ' ' -> append("%20")
                '(' -> append("%28")
                ')' -> append("%29")
                '<' -> append("%3C")
                '>' -> append("%3E")
                else -> append(char)
            }
        }
    }
}
