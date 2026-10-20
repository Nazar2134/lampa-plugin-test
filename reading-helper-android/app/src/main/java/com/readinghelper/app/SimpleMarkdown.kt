package com.readinghelper.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Renders the small Markdown subset the system prompt allows: headings, bullets, bold, italic, code. */
object SimpleMarkdown {
    private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])|(?<!\w)_(?![\s_])(.+?)(?<![\s_])_(?!\w)""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val bullet = Regex("""^(\s*)[-*•]\s+""")
    private val rule = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

    fun render(markdown: String, accent: Int, muted: Int): CharSequence {
        val out = SpannableStringBuilder()
        markdown.lines().forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            val headingMatch = heading.find(line)
            val bulletMatch = bullet.find(line)
            when {
                rule.matches(line) -> out.styled("──────────", ForegroundColorSpan(muted))
                headingMatch != null -> {
                    val start = out.length
                    appendInline(out, line.substring(headingMatch.range.last + 1))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.08f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bulletMatch != null -> {
                    val depth = bulletMatch.groupValues[1].length / 2
                    out.append("    ".repeat(depth)).append("•  ")
                    appendInline(out, line.substring(bulletMatch.range.last + 1))
                }
                else -> appendInline(out, line)
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (match in inline.findAll(text)) {
            out.append(text, last, match.range.first)
            val (bold, code, star, underscore) = match.destructured
            when {
                bold.isNotEmpty() -> out.styled(bold, StyleSpan(Typeface.BOLD))
                code.isNotEmpty() -> out.styled(code, TypefaceSpan("monospace"))
                star.isNotEmpty() -> out.styled(star, StyleSpan(Typeface.ITALIC))
                else -> out.styled(underscore, StyleSpan(Typeface.ITALIC))
            }
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
    }

    private fun SpannableStringBuilder.styled(text: String, span: Any) {
        val start = length
        append(text)
        setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
