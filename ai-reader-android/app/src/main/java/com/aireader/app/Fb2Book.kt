package com.aireader.app

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipFile

/** FictionBook 2: converted once into HTML chapters, split at top-level (and second-level) sections. */
class Fb2Book(file: File, zipped: Boolean, fallbackTitle: String) : BookContent {
    private class Chapter(val title: String, val html: String)

    private val chapters = ArrayList<Chapter>()
    private val binaries = HashMap<String, Pair<String, String>>() // id -> (content type, base64)
    private val anchors = HashMap<String, Int>()
    private var coverId: String? = null

    override var title = ""
        private set
    override var author = ""
        private set
    override var language: String? = null
        private set
    override val toc = ArrayList<TocEntry>()
    override val chapterCount get() = chapters.size

    init {
        if (zipped) {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                    ?: throw IOException("No .fb2 inside the archive")
                CappedInputStream(zip.getInputStream(entry), Limits.MAX_FB2_BYTES).buffered().use { convert(it) }
            }
        } else {
            if (file.length() > Limits.MAX_FB2_BYTES) throw BookTooLargeException()
            file.inputStream().buffered().use { convert(it) }
        }
        if (chapters.isEmpty()) throw IOException("FB2 has no text")
        if (title.isBlank()) title = fallbackTitle
        chapters.forEachIndexed { i, chapter -> if (chapter.title.isNotEmpty()) toc += TocEntry(chapter.title, i, null) }
    }

    private fun convert(input: InputStream) {
        val p = Xml.parser(input)
        val authors = ArrayList<String>()
        val out = StringBuilder()
        var outHasContent = false
        var chapterTitle: String? = null
        var titleCapture: StringBuilder? = null
        var chapterSectionDepth = -1

        var inTitleInfo = false
        var bodyKind = 0 // 0 = outside a body, 1 = main text, 2 = notes
        var sectionDepth = 0
        val closers = ArrayList<String>()

        fun flush() {
            if (outHasContent) chapters += Chapter(chapterTitle.orEmpty(), out.toString())
            out.setLength(0)
            outHasContent = false
            chapterTitle = null
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    if (bodyKind == 0) {
                        when (name) {
                            "title-info" -> inTitleInfo = true
                            "author" -> if (inTitleInfo) authors += readAuthor(p)
                            "book-title" -> if (inTitleInfo) title = Xml.readText(p).collapseSpaces()
                            "lang" -> if (inTitleInfo) language = Xml.readText(p).trim()
                            "image" -> if (inTitleInfo && coverId == null) coverId = Xml.href(p)?.removePrefix("#")
                            "binary" -> {
                                val id = Xml.attr(p, "id")
                                val type = Xml.attr(p, "content-type") ?: "image/jpeg"
                                val data = Xml.readText(p)
                                if (id != null) binaries[id] = type to data
                            }
                            "body" -> {
                                bodyKind = if (Xml.attr(p, "name") in setOf("notes", "comments")) 2 else 1
                                sectionDepth = 0
                                if (bodyKind == 2) {
                                    flush()
                                    chapterTitle = "Notes"
                                }
                            }
                        }
                        continue
                    }

                    if (name == "section") {
                        sectionDepth++
                        if (bodyKind == 1 && sectionDepth <= 2) {
                            flush()
                            chapterSectionDepth = sectionDepth
                        }
                    }
                    if (name == "title" && sectionDepth == chapterSectionDepth && chapterTitle == null && bodyKind == 1) {
                        titleCapture = StringBuilder()
                    }
                    val idAttr = Xml.attr(p, "id")?.let {
                        anchors[it] = chapters.size
                        " id=\"${it.htmlEscape()}\""
                    } ?: ""
                    val parentIsParagraph = closers.lastOrNull() == "</p>"
                    val (open, close) = when (name) {
                        "section" -> "<section$idAttr>" to "</section>"
                        "title" -> (if (sectionDepth == 0) "<div class=\"title book-title\"$idAttr>" else "<div class=\"title\"$idAttr>") to "</div>"
                        "subtitle" -> "<p class=\"subtitle\"$idAttr>" to "</p>"
                        "p" -> "<p$idAttr>" to "</p>"
                        "v" -> "<p class=\"v\"$idAttr>" to "</p>"
                        "text-author" -> "<p class=\"text-author\"$idAttr>" to "</p>"
                        "epigraph" -> "<blockquote class=\"epigraph\"$idAttr>" to "</blockquote>"
                        "cite" -> "<blockquote$idAttr>" to "</blockquote>"
                        "annotation" -> "<div class=\"annotation\"$idAttr>" to "</div>"
                        "poem" -> "<div class=\"poem\"$idAttr>" to "</div>"
                        "stanza" -> "<div class=\"stanza\"$idAttr>" to "</div>"
                        "emphasis" -> "<em>" to "</em>"
                        "strong" -> "<strong>" to "</strong>"
                        "strikethrough" -> "<s>" to "</s>"
                        "sub", "sup", "code" -> "<$name>" to "</$name>"
                        "empty-line" -> "<br>" to ""
                        "table", "tr", "td", "th" -> "<$name$idAttr>" to "</$name>"
                        "a" -> {
                            val href = Xml.href(p).orEmpty()
                            val note = if (Xml.attr(p, "type") == "note") " class=\"note\"" else ""
                            "<a href=\"${href.htmlEscape()}\"$note>" to "</a>"
                        }
                        "image" -> {
                            outHasContent = true
                            val src = Paths.ORIGIN + "__fb2img/" + Paths.encode(Xml.href(p).orEmpty().removePrefix("#"))
                            val img = "<img src=\"$src\" alt=\"\">"
                            (if (parentIsParagraph) img else "<div class=\"image\">$img</div>") to ""
                        }
                        else -> "" to ""
                    }
                    out.append(open)
                    closers += close
                }

                XmlPullParser.TEXT -> if (bodyKind != 0) {
                    val text = p.text
                    if (!outHasContent && text.isNotBlank()) outHasContent = true
                    out.append(text.htmlEscape())
                    titleCapture?.append(text)
                }

                XmlPullParser.END_TAG -> {
                    val name = p.name
                    if (bodyKind == 0) {
                        if (name == "title-info") inTitleInfo = false
                        continue
                    }
                    if (name == "body" && closers.isEmpty()) {
                        bodyKind = 0
                        continue
                    }
                    if (closers.isNotEmpty()) out.append(closers.removeAt(closers.lastIndex))
                    when (name) {
                        "p" -> titleCapture?.append(' ')
                        "title" -> titleCapture?.let {
                            chapterTitle = it.toString().collapseSpaces()
                            titleCapture = null
                        }
                        "section" -> sectionDepth--
                    }
                }
            }
        }
        flush()
        author = authors.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun readAuthor(p: XmlPullParser): String {
        val depth = p.depth
        val parts = HashMap<String, String>()
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> parts[p.name] = Xml.readText(p).collapseSpaces()
                XmlPullParser.END_TAG -> if (p.depth == depth) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        val full = listOfNotNull(parts["first-name"], parts["middle-name"], parts["last-name"]).filter { it.isNotEmpty() }.joinToString(" ")
        return full.ifEmpty { parts["nickname"].orEmpty() }
    }

    override fun chapterTitle(index: Int): String =
        (index downTo 0).firstNotNullOfOrNull { chapters[it].title.ifEmpty { null } } ?: title

    override fun chapterUrl(index: Int) = Paths.ORIGIN + "__fb2/ch$index.html"

    override fun chapterIndexForPath(path: String): Int? =
        path.removePrefix("__fb2/ch").removeSuffix(".html").toIntOrNull()?.takeIf { it in chapters.indices }

    override fun chapterOfAnchor(id: String) = anchors[id]

    override fun load(path: String): Resource? {
        chapterIndexForPath(path)?.let { i ->
            val lang = language?.takeIf { it.isNotBlank() }?.let { " lang=\"${it.htmlEscape()}\"" } ?: ""
            val html = "<!DOCTYPE html><html$lang><head><meta charset=\"utf-8\"><title>${chapters[i].title.htmlEscape()}</title></head>" +
                "<body class=\"fb2\">${chapters[i].html}</body></html>"
            return Resource("text/html", html.toByteArray(Charsets.UTF_8), isChapter = true)
        }
        if (path.startsWith("__fb2img/")) {
            val (type, data) = binaries[path.removePrefix("__fb2img/")] ?: return null
            return Resource(type, decodeBase64(data) ?: return null, isChapter = false)
        }
        return null
    }

    override fun cover(): ByteArray? = coverId?.let { binaries[it] }?.let { decodeBase64(it.second) }

    private fun decodeBase64(data: String) = runCatching { Base64.getMimeDecoder().decode(data.trim()) }.getOrNull()

    override fun close() {}
}
