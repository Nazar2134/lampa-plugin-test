package com.aireader.app

import java.io.ByteArrayOutputStream

/** Path helpers for files inside a book, which are served to the WebView as https://book.local/<path>. */
object Paths {
    const val HOST = "book.local"
    const val ORIGIN = "https://$HOST/"

    /** Resolves [href] (relative, may carry a #fragment) against [baseDir]. The result keeps the fragment. */
    fun resolve(baseDir: String, href: String): String {
        val path = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val segments = if (path.startsWith("/")) ArrayList() else baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in decode(path).split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        val joined = segments.joinToString("/")
        return if (fragment.isEmpty()) joined else "$joined#$fragment"
    }

    /** Percent-decodes a URL path (UTF-8). Unlike URLDecoder, leaves '+' alone. */
    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val value = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    out.write(value)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    /** Percent-encodes everything except unreserved characters and '/'. */
    fun encode(path: String): String {
        val out = StringBuilder()
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~/") {
                out.append(c.toChar())
            } else {
                out.append('%').append("%02X".format(c))
            }
        }
        return out.toString()
    }

    fun url(path: String) = ORIGIN + encode(path)

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "xhtml", "html", "htm", "xml" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
