package com.aireader.app

import java.security.SecureRandom
import java.util.Base64

/** Turns a book's chapter document into a page the reader can paginate. */
object ChapterRenderer {
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val selfClosing = Regex("""<([A-Za-z][\w:.-]*)(\s[^<>]*?)?\s*/>""")
    private val headOpen = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val bodyOpen = Regex("<body[\\s>]", RegexOption.IGNORE_CASE)
    private val random = SecureRandom()

    data class Layout(val padTop: Int, val padBottom: Int, val padX: Int, val columns: Int = 1)

    fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /**
     * Book files are untrusted. Only the reader's own scripts may run (book scripts could otherwise call
     * the app's JavaScript bridge), and nothing may load from outside the book. Inline styles stay allowed
     * because publishers rely on them.
     */
    fun contentSecurityPolicy(nonce: String) =
        "default-src 'none'; script-src 'nonce-$nonce'; style-src ${Paths.ORIGIN} 'unsafe-inline'; " +
            "img-src ${Paths.ORIGIN} data:; font-src ${Paths.ORIGIN} data:; media-src ${Paths.ORIGIN}; " +
            "connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"

    /**
     * EPUB chapters are XHTML but are served as HTML so that slightly broken files still render.
     * HTML parsers ignore "/>" on non-void elements (a self-closed <title/> would swallow the whole
     * page), so those are expanded first. The security policy goes first in <head>, before anything
     * from the book; the reader's CSS and JS go at the end so they override the publisher's styles.
     */
    fun prepare(html: String, head: String, nonce: String? = null): String {
        val fixed = selfClosing.replace(html) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase() in voidTags) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }
        val csp = nonce?.let { "<meta http-equiv=\"Content-Security-Policy\" content=\"${contentSecurityPolicy(it)}\">" } ?: ""
        val open = headOpen.find(fixed)
        val close = headClose.find(fixed)
        if (open != null && close != null && close.range.first > open.range.last) {
            return fixed.substring(0, open.range.last + 1) + csp +
                fixed.substring(open.range.last + 1, close.range.first) + head + fixed.substring(close.range.first)
        }
        bodyOpen.find(fixed)?.let { return fixed.substring(0, it.range.first) + "<head>$csp$head</head>" + fixed.substring(it.range.first) }
        return csp + head + fixed
    }

    fun head(settings: AppSettings, layout: Layout, initJson: String, nonce: String = ""): String {
        val t = settings.theme
        val font = when (settings.font) {
            ReaderFont.SERIF -> "Georgia, \"Noto Serif\", serif"
            ReaderFont.SANS -> "Roboto, \"Noto Sans\", sans-serif"
            ReaderFont.PUBLISHER -> null
        }
        val vars = "--bg:${cssColor(t.bg)};--fg:${cssColor(t.fg)};--muted:${cssColor(t.muted)};--accent:${cssColor(t.accent)};" +
            "--font-size:${settings.fontSize}px;--line-height:${settings.lineHeight};" +
            "--pad-top:${layout.padTop}px;--pad-bottom:${layout.padBottom}px;--pad-x:${layout.padX}px;"
        val rules = StringBuilder()
        if (font != null) {
            rules.append("body,body p,body div,body span,body li,body blockquote,body h1,body h2,body h3,body h4,body h5,body h6{font-family:$font !important}")
        }
        rules.append(
            if (settings.justify) "body{text-align:justify}"
            else "body,body p,body div,body li,body blockquote{text-align:left !important}"
        )
        if (layout.columns == 2) {
            rules.append("body{column-width:auto !important;column-count:2 !important}")
        }
        val nonceAttr = if (nonce.isNotEmpty()) " nonce=\"$nonce\"" else ""
        return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<link rel=\"stylesheet\" href=\"${Paths.ORIGIN}__reader/reader.css\">" +
            "<style id=\"reader-vars\">:root{$vars}$rules</style>" +
            "<script$nonceAttr>window.READER_INIT=${initJson.replace("</", "<\\/")};</script>" +
            "<script$nonceAttr src=\"${Paths.ORIGIN}__reader/reader.js\"></script>"
    }
}
