package com.aireader.app

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class EpubBook(file: File, fallbackTitle: String) : BookContent {
    private val zip = ZipFile(file)
    private val manifest = HashMap<String, String>() // id -> path
    private val mediaTypes = HashMap<String, String>() // path -> media type
    private val spine = ArrayList<String>()
    private val spineIndex = HashMap<String, Int>()
    private var coverPath: String? = null

    override var title = ""
        private set
    override var author = ""
        private set
    override var language: String? = null
        private set
    override val toc = ArrayList<TocEntry>()
    override val chapterCount get() = spine.size

    init {
        try {
            parse()
        } catch (e: Exception) {
            zip.close()
            throw e
        }
        if (spine.isEmpty()) {
            zip.close()
            throw IOException("EPUB has no chapters")
        }
        if (title.isBlank()) title = fallbackTitle
    }

    private fun stream(path: String) = zip.getEntry(path)?.let { CappedInputStream(zip.getInputStream(it), Limits.MAX_ENTRY_BYTES) }

    private fun bytes(path: String): ByteArray? = stream(path)?.use { it.readBytes() }

    private fun parse() {
        val opfPath = stream("META-INF/container.xml")?.use { input ->
            val p = Xml.parser(input)
            var found: String? = null
            while (found == null && p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") found = Xml.attr(p, "full-path")
            }
            found
        } ?: zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf") }
            ?: throw IOException("No package document")

        val opfDir = opfPath.substringBeforeLast('/', "")
        val authors = ArrayList<String>()
        var coverId: String? = null
        var navPath: String? = null
        var ncxId: String? = null
        val spineIds = ArrayList<String>()

        stream(opfPath)?.use { input ->
            val p = Xml.parser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "title" -> if (title.isEmpty()) title = Xml.readText(p).collapseSpaces()
                    "creator" -> authors += Xml.readText(p).collapseSpaces()
                    "language" -> if (language == null) language = Xml.readText(p).trim()
                    "meta" -> if (Xml.attr(p, "name") == "cover") coverId = Xml.attr(p, "content")
                    "item" -> {
                        val id = Xml.attr(p, "id") ?: continue
                        val href = Xml.attr(p, "href") ?: continue
                        val path = Paths.resolve(opfDir, href)
                        val properties = Xml.attr(p, "properties").orEmpty().split(' ')
                        manifest[id] = path
                        mediaTypes[path] = Xml.attr(p, "media-type").orEmpty()
                        if ("cover-image" in properties) coverPath = path
                        if ("nav" in properties) navPath = path
                    }
                    "spine" -> ncxId = Xml.attr(p, "toc")
                    "itemref" -> Xml.attr(p, "idref")?.let { spineIds += it }
                }
            }
        } ?: throw IOException("Missing $opfPath")

        for (id in spineIds) {
            val path = manifest[id] ?: continue
            // Chapters are (X)HTML; image-only spine items such as SVG covers are skipped.
            val type = mediaTypes[path].orEmpty()
            if ("html" !in type && type != "application/xml" && type != "text/xml") continue
            spineIndex.putIfAbsent(path, spine.size)
            spine += path
        }
        author = authors.filter { it.isNotBlank() }.joinToString(", ")
        if (coverPath == null) coverPath = coverId?.let { manifest[it] }

        val ncxPath = ncxId?.let { manifest[it] }
            ?: mediaTypes.entries.firstOrNull { it.value == "application/x-dtbncx+xml" }?.key
        if (ncxPath != null) runCatching { parseNcx(ncxPath) }
        if (toc.isEmpty() && navPath != null) runCatching { parseNav(navPath!!) }
    }

    private fun addToc(title: String, dir: String, href: String, depth: Int) {
        val resolved = Paths.resolve(dir, href)
        val chapter = spineIndex[resolved.substringBefore('#')] ?: return
        val clean = title.collapseSpaces()
        if (clean.isEmpty()) return
        toc += TocEntry(clean, chapter, resolved.substringAfter('#', "").ifEmpty { null }, depth)
    }

    private fun parseNcx(path: String) {
        val dir = path.substringBeforeLast('/', "")
        stream(path)?.use { input ->
            val p = Xml.parser(input)
            var depth = 0
            var label = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "navPoint" -> depth++
                        "text" -> if (depth > 0) label = Xml.readText(p)
                        "content" -> if (depth > 0) Xml.attr(p, "src")?.let { addToc(label, dir, it, depth - 1) }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "navPoint") depth--
                }
            }
        }
    }

    private fun parseNav(path: String) {
        val dir = path.substringBeforeLast('/', "")
        stream(path)?.use { input ->
            val p = Xml.parser(input)
            var navDepth = -1
            var listDepth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when {
                        p.name == "nav" && navDepth < 0 -> {
                            val type = p.getAttributeValue("http://www.idpf.org/2007/ops", "type") ?: Xml.attr(p, "role")
                            if (type == null || "toc" in type) navDepth = p.depth
                        }
                        navDepth >= 0 && p.name == "ol" -> listDepth++
                        navDepth >= 0 && p.name == "a" -> {
                            val href = Xml.attr(p, "href")
                            val text = Xml.readText(p)
                            if (href != null) addToc(text, dir, href, (listDepth - 1).coerceAtLeast(0))
                        }
                    }
                    XmlPullParser.END_TAG -> when {
                        p.name == "ol" && navDepth >= 0 -> listDepth--
                        p.name == "nav" && p.depth == navDepth -> return
                    }
                }
            }
        }
    }

    override fun chapterTitle(index: Int) = toc.lastOrNull { it.chapter <= index }?.title ?: title
    override fun chapterUrl(index: Int) = Paths.url(spine[index])
    override fun chapterIndexForPath(path: String) = spineIndex[path]
    override fun chapterOfAnchor(id: String): Int? = null

    override fun load(path: String): Resource? {
        val bytes = bytes(path) ?: return null
        val mime = mediaTypes[path]?.takeIf { it.isNotBlank() } ?: Paths.mime(path)
        val isChapter = path in spineIndex
        return Resource(if (isChapter) "text/html" else mime, bytes, isChapter)
    }

    override fun cover(): ByteArray? {
        val path = coverPath ?: mediaTypes.keys.firstOrNull { it.substringAfterLast('/').startsWith("cover", ignoreCase = true) && mediaTypes[it].orEmpty().startsWith("image/") }
        return path?.let { bytes(it) }
    }

    override fun close() = zip.close()
}
