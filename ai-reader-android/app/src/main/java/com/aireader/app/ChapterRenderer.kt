package com.aireader.app

/** Turns a book's chapter document into a page the reader can paginate. */
object ChapterRenderer {
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val selfClosing = Regex("""<([A-Za-z][\w:.-]*)(\s[^<>]*?)?\s*/>""")
    private val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val bodyOpen = Regex("<body[\\s>]", RegexOption.IGNORE_CASE)

    data class Layout(val padTop: Int, val padBottom: Int, val padX: Int)

    /**
     * EPUB chapters are XHTML but are served as HTML so that slightly broken files still render.
     * HTML parsers ignore "/>" on non-void elements (a self-closed <title/> would swallow the whole
     * page), so those are expanded first. Then the reader's CSS and JS go at the end of <head> so
     * they override the publisher's styles.
     */
    fun prepare(html: String, head: String): String {
        val fixed = selfClosing.replace(html) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase() in voidTags) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }
        headClose.find(fixed)?.let { return fixed.substring(0, it.range.first) + head + fixed.substring(it.range.first) }
        bodyOpen.find(fixed)?.let { return fixed.substring(0, it.range.first) + "<head>$head</head>" + fixed.substring(it.range.first) }
        return head + fixed
    }

    fun head(settings: AppSettings, layout: Layout, initJson: String): String {
        val t = settings.theme
        val font = if (settings.serif) "Georgia, \"Noto Serif\", serif" else "Roboto, \"Noto Sans\", sans-serif"
        val vars = "--bg:${cssColor(t.bg)};--fg:${cssColor(t.fg)};--muted:${cssColor(t.muted)};--accent:${cssColor(t.accent)};" +
            "--font:$font;--font-size:${settings.fontSize}px;--line-height:1.55;" +
            "--pad-top:${layout.padTop}px;--pad-bottom:${layout.padBottom}px;--pad-x:${layout.padX}px;"
        return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<link rel=\"stylesheet\" href=\"${Paths.ORIGIN}__reader/reader.css\">" +
            "<style id=\"reader-vars\">:root{$vars}</style>" +
            "<script>window.READER_INIT=${initJson.replace("</", "<\\/")};</script>" +
            "<script src=\"${Paths.ORIGIN}__reader/reader.js\"></script>"
    }
}
