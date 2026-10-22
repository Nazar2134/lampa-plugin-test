package com.aireader.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * Writes chapters exactly as the WebView would receive them (security policy, reader CSS/JS, settings, marks)
 * to build/reader-preview, so reader.js can be exercised in a desktop Chromium. pages.json lists each page
 * with the policy header to send and what the check script should expect.
 */
class PreviewExport {
    companion object {
        @BeforeClass
        @JvmStatic
        fun useKxml() {
            Xml.newParser = { KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) } }
        }

        const val NONCE = "TESTNONCE"
    }

    private val pages = JSONArray()
    private val out = File("build/reader-preview")

    private fun write(kind: String, path: String, html: String, settings: AppSettings, layout: ChapterRenderer.Layout, init: JSONObject, name: String) {
        val prepared = ChapterRenderer.prepare(html, ChapterRenderer.head(settings, layout, init.toString(), NONCE), NONCE)
        File(out, "$kind/$path").apply { parentFile!!.mkdirs() }.writeText(prepared)
        pages.put(JSONObject().put("name", name).put("kind", kind).put("url", Paths.url(path)).put("csp", ChapterRenderer.contentSecurityPolicy(NONCE)))
    }

    private fun emptyInit() = JSONObject().put("atEnd", false)

    @Test
    fun export() {
        out.deleteRecursively()
        out.mkdirs()
        val sepia = AppSettings(fontSize = 19, theme = ReaderTheme.SEPIA)
        val phone = ChapterRenderer.Layout(padTop = 44, padBottom = 40, padX = 22)

        BookOpener.open(BookTest.fixture("sample.epub")).use { book ->
            for (i in 0 until book.chapterCount) {
                val path = Paths.decode(book.chapterUrl(i).removePrefix(Paths.ORIGIN))
                val html = String(book.load(path)!!.bytes)
                val init = emptyInit()
                if (i == 0) {
                    // A green highlight with a note on the 2nd "Garten" sentence fragment, and a bookmark on later text.
                    init.put("marks", JSONObject()
                        .put("highlights", JSONArray().put(JSONObject().put("id", 7).put("text", "hätte gern länger geschlafen").put("occurrence", 1).put("color", "green").put("note", true)))
                        .put("bookmarks", JSONArray().put(JSONObject().put("id", 9).put("text", "Später las sie im Tagebuch").put("occurrence", 0))))
                }
                write("epub", path, html, sepia, phone, init, "epub-$i")
            }
            for (path in listOf("OEBPS/Styles/style.css", "OEBPS/Images/cover.png")) {
                File(out, "epub/$path").apply { parentFile!!.mkdirs() }.writeBytes(book.load(path)!!.bytes)
            }
            val chapter1 = String(book.load("OEBPS/Text/chapter1.xhtml")!!.bytes)

            // Restoring a saved position from its text anchor (third occurrence of a repeated paragraph start).
            write("epub", "OEBPS/Text/restore.xhtml", chapter1, sepia, phone,
                emptyInit().put("position", JSONObject().put("text", "Ihr Großvater, der den Garten").put("occurrence", 2).put("fraction", 0)), "restore")

            // Reopening a book: the saved spot gets a "You stopped here" marker.
            write("epub", "OEBPS/Text/resume.xhtml", chapter1, sepia, phone,
                emptyInit().put("position", JSONObject().put("text", "Marta zog ihre Stiefel an").put("occurrence", 1).put("fraction", 0))
                    .put("resume", JSONObject().put("label", "You stopped here")), "resume")

            // Jumping to a search result.
            write("epub", "OEBPS/Text/search.xhtml", chapter1, sepia, phone,
                emptyInit().put("target", JSONObject().put("text", "Jonas war in der Stadt").put("occurrence", 1).put("flash", true)), "search")

            // A hostile chapter: an inline script, a remote image and a remote stylesheet must all be blocked.
            val hostile = chapter1.replace("</head>", "<script>window.evil = 1; Android.openExternal('https://evil.example/');</script>" +
                "<link rel=\"stylesheet\" href=\"https://evil.example/x.css\"/></head>")
                .replace("<h1", "<img src=\"https://evil.example/track.png\"/><script>window.evil2 = 1;</script><h1")
            write("epub", "OEBPS/Text/hostile.xhtml", hostile, sepia, phone, emptyInit(), "hostile")

            // Learning mode: "Garten" being learnt, a few function words known, new words underlined.
            write("epub", "OEBPS/Text/learning.xhtml", chapter1, sepia, phone,
                emptyInit().put("learning", JSONObject().put("enabled", true).put("underlineNew", true)
                    .put("statuses", JSONObject().put("garten", 1).put("der", 2).put("die", 2).put("und", 2).put("sie", 2))), "learning")

            // Tablet in landscape: two columns per screen, left-aligned, sans, wide spacing.
            write("epub", "OEBPS/Text/tablet.xhtml", chapter1, AppSettings(fontSize = 20, font = ReaderFont.SANS, justify = false, lineHeight = 1.8f),
                ChapterRenderer.Layout(padTop = 30, padBottom = 40, padX = 32, columns = 2), emptyInit(), "tablet")
        }

        BookOpener.open(BookTest.fixture("sample.fb2")).use { book ->
            for (i in 0 until book.chapterCount) {
                val path = Paths.decode(book.chapterUrl(i).removePrefix(Paths.ORIGIN))
                write("fb2", path, String(book.load(path)!!.bytes), sepia, phone, emptyInit(), "fb2-$i")
            }
            File(out, "fb2/__fb2img/cover.png").apply { parentFile!!.mkdirs() }.writeBytes(book.load("__fb2img/cover.png")!!.bytes)
        }
        File(out, "pages.json").writeText(pages.toString(2))
    }
}
