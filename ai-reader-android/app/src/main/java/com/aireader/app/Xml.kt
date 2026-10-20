package com.aireader.app

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

object Xml {
    /** Swapped for a plain KXmlParser in JVM unit tests, where Android's factory is a stub. */
    internal var newParser: () -> XmlPullParser = {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    }

    /** A namespace-aware parser; a null [encoding] means "read it from the XML declaration". */
    fun parser(input: InputStream, encoding: String? = null): XmlPullParser = newParser().apply {
        // Relaxed mode tolerates HTML entities such as &nbsp; in EPUB navigation files.
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(input, encoding)
    }

    /** Reads all text inside the current start tag, including nested elements, and stops at its end tag. */
    fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val text = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> if (p.depth == depth) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return text.toString()
    }

    fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    /** xlink:href, whatever prefix the file uses for the xlink namespace. */
    fun href(p: XmlPullParser): String? {
        p.getAttributeValue("http://www.w3.org/1999/xlink", "href")?.let { return it }
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == "href") return p.getAttributeValue(i)
        }
        return null
    }
}

fun String.collapseSpaces() = replace(Regex("\\s+"), " ").trim()

fun String.htmlEscape(): String {
    val out = StringBuilder(length + 16)
    for (c in this) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            else -> out.append(c)
        }
    }
    return out.toString()
}
