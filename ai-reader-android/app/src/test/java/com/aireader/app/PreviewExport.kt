package com.aireader.app

import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * Writes the sample books' chapters, exactly as the WebView would receive them, to build/reader-preview,
 * so the pagination script can be exercised in a desktop Chromium.
 */
class PreviewExport {
    companion object {
        @BeforeClass
        @JvmStatic
        fun useKxml() {
            Xml.newParser = { KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) } }
        }
    }

    @Test
    fun export() {
        val out = File("build/reader-preview").apply { deleteRecursively(); mkdirs() }
        val settings = AppSettings(fontSize = 19, theme = ReaderTheme.SEPIA)
        val layout = ChapterRenderer.Layout(padTop = 44, padBottom = 40, padX = 22)
        for ((name, file) in listOf("epub" to "sample.epub", "fb2" to "sample.fb2")) {
            BookOpener.open(BookTest.fixture(file)).use { book ->
                val dir = File(out, name)
                val manifest = StringBuilder()
                for (i in 0 until book.chapterCount) {
                    val path = Paths.decode(book.chapterUrl(i).removePrefix(Paths.ORIGIN))
                    val res = book.load(path)!!
                    val init = JSONObject().put("fraction", 0).put("atEnd", false).put("anchor", JSONObject.NULL).toString()
                    val html = ChapterRenderer.prepare(String(res.bytes), ChapterRenderer.head(settings, layout, init))
                    File(dir, path).apply { parentFile!!.mkdirs() }.writeText(html)
                    manifest.append(book.chapterUrl(i)).append('\n')
                }
                File(dir, "chapters.txt").writeText(manifest.toString())
                // Other resources referenced by the chapters
                for (path in listOf("OEBPS/Styles/style.css", "OEBPS/Images/cover.png", "__fb2img/cover.png")) {
                    book.load(path)?.let { File(dir, path).apply { parentFile!!.mkdirs() }.writeBytes(it.bytes) }
                }
            }
        }
    }
}
