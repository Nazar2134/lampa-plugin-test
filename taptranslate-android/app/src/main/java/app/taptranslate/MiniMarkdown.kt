package app.taptranslate

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Renders the small Markdown subset the prompt asks for. Tolerates half-streamed input. */
object MiniMarkdown {

    class Parsed(val body: String, val source: String?, val target: String?)

    private val LANG_TAG = Regex("""^\s*\[\[\s*([A-Za-z-]{2,10})\s*>\s*([A-Za-z-]{2,10})\s*]]\s*""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|\*([^*\s][^*]*?)\*|`([^`]+)`""")
    private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /** Splits off the hidden "[[en>uk]]" language tag the model writes on line 1. */
    fun parse(raw: String): Parsed {
        LANG_TAG.find(raw)?.let { m ->
            return Parsed(raw.substring(m.range.last + 1), m.groupValues[1].lowercase(), m.groupValues[2].lowercase())
        }
        val t = raw.trimStart()
        // The tag is still streaming in: hide it rather than flash "[[en>" on screen.
        if (t.length < 24 && !t.contains('\n') && (t.startsWith("[[") || t == "[")) return Parsed("", null, null)
        return Parsed(raw, null, null)
    }

    /** The main translation (first line), as plain text. */
    fun headline(body: String): String =
        body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.replace("**", "")?.replace("*", "")?.trim().orEmpty()

    fun render(body: String, accent: Int, muted: Int, density: Float): CharSequence {
        val sb = SpannableStringBuilder()
        val indent = (18 * density).toInt()
        var lineNo = 0
        var pendingGap = false

        for (raw in body.lines()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                pendingGap = true
                continue
            }
            val isHeader = t.startsWith("#")
            if (sb.isNotEmpty()) {
                sb.append('\n')
                if (isHeader || pendingGap) {
                    val s = sb.length
                    sb.append(" \n")
                    sb.setSpan(RelativeSizeSpan(0.45f), s, sb.length, EXCL)
                }
            }
            pendingGap = false
            val start = sb.length

            when {
                isHeader -> {
                    sb.append(t.trimStart('#').trim().uppercase())
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(ForegroundColorSpan(accent), start, sb.length, EXCL)
                    sb.setSpan(RelativeSizeSpan(0.78f), start, sb.length, EXCL)
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> {
                    sb.append("•  ")
                    appendInline(sb, t.substring(2).trim())
                    sb.setSpan(LeadingMarginSpan.Standard(0, indent), start, sb.length, EXCL)
                }
                t.startsWith("→") || t.startsWith("->") -> {
                    sb.append("→ ")
                    appendInline(sb, t.removePrefix("->").removePrefix("→").trim())
                    sb.setSpan(ForegroundColorSpan(muted), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, EXCL)
                    sb.setSpan(LeadingMarginSpan.Standard(indent), start, sb.length, EXCL)
                }
                else -> {
                    appendInline(sb, t)
                    when (lineNo) {
                        0 -> sb.setSpan(RelativeSizeSpan(1.45f), start, sb.length, EXCL)
                        1 -> {
                            sb.setSpan(ForegroundColorSpan(muted), start, sb.length, EXCL)
                            sb.setSpan(RelativeSizeSpan(0.9f), start, sb.length, EXCL)
                        }
                    }
                }
            }
            lineNo++
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var i = 0
        for (m in INLINE.findAll(text)) {
            sb.append(text, i, m.range.first)
            val s = sb.length
            when {
                m.groups[1] != null -> {
                    sb.append(m.groupValues[1])
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, EXCL)
                }
                m.groups[2] != null -> {
                    sb.append(m.groupValues[2])
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, EXCL)
                }
                else -> {
                    sb.append(m.groupValues[3])
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, EXCL)
                }
            }
            i = m.range.last + 1
        }
        // Drop a dangling "**" from a bold run that is still streaming.
        sb.append(text.substring(i).replace("**", ""))
    }
}
