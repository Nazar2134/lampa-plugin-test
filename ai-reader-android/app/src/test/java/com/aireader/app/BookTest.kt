package com.aireader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class BookTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun useKxml() {
            Xml.newParser = { KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) } }
        }

        fun fixture(name: String) = File(BookTest::class.java.classLoader!!.getResource(name)!!.toURI())
    }

    @Test
    fun detectsFormats() {
        assertEquals(BookFormat.EPUB, BookOpener.detect(fixture("sample.epub")))
        assertEquals(BookFormat.FB2, BookOpener.detect(fixture("sample.fb2")))
        val junk = File.createTempFile("junk", ".txt").apply { writeText("hello") }
        assertNull(BookOpener.detect(junk))
    }

    @Test
    fun readsEpub() {
        BookOpener.open(fixture("sample.epub")).use { book ->
            assertEquals("Der kleine Garten", book.title)
            assertEquals("Anna Beispiel", book.author)
            assertEquals("de", book.language)
            assertEquals("German", book.languageName)
            assertEquals(2, book.chapterCount)
            assertEquals(listOf("Kapitel 1: Der Nebel", "Das Ende", "Kapitel 2: Der Schuppen"), book.toc.map { it.title })
            assertEquals(listOf(0, 0, 1), book.toc.map { it.chapter })
            assertEquals("end", book.toc[1].anchor)
            assertEquals(1, book.toc[1].depth)
            assertEquals("Kapitel 2: Der Schuppen", book.chapterTitle(1))

            // A file name with a space is URL-encoded, and maps back to its chapter.
            assertEquals("https://book.local/OEBPS/Text/chapter%20two.xhtml", book.chapterUrl(1))
            assertEquals(1, book.chapterIndexForPath(Paths.decode("OEBPS/Text/chapter%20two.xhtml")))

            val chapter = book.load("OEBPS/Text/chapter1.xhtml")!!
            assertTrue(chapter.isChapter)
            assertTrue(String(chapter.bytes).contains("Als Marta am Morgen"))
            assertEquals("text/css", book.load("OEBPS/Styles/style.css")!!.mime)
            assertEquals("image/png", book.load("OEBPS/Images/cover.png")!!.mime)
            assertNotNull(book.cover())
            assertNull(book.load("OEBPS/missing.png"))
        }
    }

    @Test
    fun readsFb2InWindows1251() {
        BookOpener.open(fixture("sample.fb2")).use { book ->
            assertEquals("Бабусин сад", book.title)
            assertEquals("Олена Приклад", book.author)
            assertEquals("uk", book.language)
            assertEquals("Ukrainian", book.languageName)
            // Body title page, chapter 1, chapter 2 (part title), its subsection, notes
            val titles = (0 until book.chapterCount).map { book.chapterTitle(it) }
            assertEquals(listOf("Бабусин сад", "Розділ перший Ранок", "Розділ другий", "Ліс", "Notes"), titles)
            assertEquals(4, book.chapterOfAnchor("n1"))
            assertEquals(1, book.chapterOfAnchor("ch1"))

            val html = String(book.load("__fb2/ch1.html")!!.bytes, Charsets.UTF_8)
            assertTrue(html, html.contains("<html lang=\"uk\">"))
            assertTrue(html, html.contains("<body class=\"fb2\">"))
            assertTrue(html, html.contains("Того ранку Оксана прокинулася"))
            assertTrue(html, html.contains("<em>кожну</em>"))
            assertTrue(html, html.contains("<a href=\"#n1\" class=\"note\">[1]</a>"))
            assertTrue(html, html.contains("<blockquote class=\"epigraph\">"))
            assertTrue(html, html.contains("<p class=\"v\">Ой у полі жито,</p>"))
            assertTrue(html, html.contains("Прислів’я"))

            val chapter3 = String(book.load("__fb2/ch3.html")!!.bytes, Charsets.UTF_8)
            assertTrue(chapter3, chapter3.contains("<div class=\"image\"><img src=\"https://book.local/__fb2img/cover.png\""))
            assertEquals("image/png", book.load("__fb2img/cover.png")!!.mime)
            assertNotNull(book.cover())
            assertTrue(book.cover()!!.size > 50)
        }
    }

    @Test
    fun preparesChapterHtml() {
        val head = "<!--HEAD-->"
        val prepared = ChapterRenderer.prepare("<html><head><title/><link rel=\"x\" href=\"a.css\"/></head><body><div class=\"a\"/><br/><p>x</p></body></html>", head)
        assertTrue(prepared, prepared.contains("<title></title>"))
        assertTrue(prepared, prepared.contains("<div class=\"a\"></div>"))
        assertTrue(prepared, prepared.contains("<br/>"))
        assertTrue(prepared, prepared.contains("href=\"a.css\"/><!--HEAD--></head>"))
        assertEquals("<!--HEAD-->plain", ChapterRenderer.prepare("plain", head))
        assertFalse(ChapterRenderer.head(AppSettings(), ChapterRenderer.Layout(1, 2, 3), "{\"a\":\"</script>\"}").contains("</script>\"}"))
    }

    @Test
    fun resolvesPaths() {
        assertEquals("OEBPS/Images/a b.png", Paths.resolve("OEBPS/Text", "../Images/a%20b.png"))
        assertEquals("OEBPS/Text/ch.xhtml#n1", Paths.resolve("OEBPS/Text", "ch.xhtml#n1"))
        assertEquals("root.css", Paths.resolve("OEBPS", "/root.css"))
        assertEquals("a%20b/%C3%BC.html", Paths.encode("a b/ü.html"))
        assertEquals("a b/ü.html", Paths.decode("a%20b/%C3%BC.html"))
        assertEquals("100%", Paths.decode("100%"))
    }
}
