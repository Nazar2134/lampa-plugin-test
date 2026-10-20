package com.aireader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser

/** Book text extraction, search and the context chosen for AI questions, using a book whose facts are spread over chapters. */
class RetrievalTest {
    companion object {
        lateinit var text: BookText
        lateinit var index: BookIndex

        @BeforeClass
        @JvmStatic
        fun load() {
            Xml.newParser = { KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) } }
            BookOpener.open(BookTest.fixture("retrieval.epub")).use { book ->
                text = BookText.from(book)
            }
            index = BookIndex(text)
        }

        fun lastParagraph(chapter: Int) = text.chapters[chapter].paragraphs.lastIndex
    }

    @Test
    fun extractsChaptersAndParagraphs() {
        // The SVG cover in the spine is not a chapter.
        assertEquals(4, text.chapters.size)
        assertEquals("The Stone Owl", text.chapters[0].title)
        assertEquals(listOf("The Stone Owl", "Marta spent the first warm afternoon of April clearing the beds behind the house."), text.chapters[0].paragraphs.take(2))
        assertEquals(5, text.chapters[0].paragraphs.size)
    }

    @Test
    fun progressFollowsChapterLength() {
        assertEquals(0f, text.progress(0, 0f), 0.0001f)
        assertEquals(1f, text.progress(3, 1f), 0.0001f)
        // The last chapter is short, so starting it means most of the book has been read.
        assertTrue(text.progress(3, 0f) > 0.75f)
        val midway = text.progress(1, 0.5f)
        assertTrue("$midway", midway > text.progress(1, 0f) && midway < text.progress(2, 0f))
    }

    @Test
    fun answersComeFromEarlierChapters() {
        val readingChapter3 = 2 to lastParagraph(2)
        val key = BookContext.retrieve(text, index, "Where did Marta find the key?", readingChapter3)
        assertTrue(key.toString(), key.isNotEmpty())
        assertTrue(key.toString(), key.any { it.chapter == 0 && "stone owl" in it.text })

        val job = BookContext.retrieve(text, index, "What does Jonas do in Hamburg?", readingChapter3)
        assertTrue(job.toString(), job.any { it.chapter == 1 && "clockmaker" in it.text })
        assertEquals("first hit is the clockmaker paragraph", 1, index.search("What does Jonas do in Hamburg?", until = readingChapter3).first().chapter)
    }

    @Test
    fun neverUsesChaptersTheReaderHasNotReached() {
        val question = "Who were the letters under the workbench for?"
        val readingChapter3 = 2 to lastParagraph(2)
        assertTrue(BookContext.retrieve(text, index, question, readingChapter3).none { it.chapter == 3 })
        // The same question asked at the end of the book finds the answer.
        assertTrue(BookContext.retrieve(text, index, question, 3 to lastParagraph(3)).any { it.chapter == 3 && "letters for Jonas" in it.text })
    }

    @Test
    fun formatsExcerptsWithChapterReferences() {
        val around = BookContext.around(text, 0, 2, before = 1, after = 1)
        assertEquals(listOf(1, 2, 3), around.map { it.paragraph })
        val formatted = BookContext.format(text, around)
        assertTrue(formatted, formatted.startsWith("[Chapter 1 “The Stone Owl”, ¶2–4]\nMarta spent"))
        assertEquals("consecutive paragraphs share one label", 1, Regex("\\[Chapter").findAll(formatted).count())

        // Recent text runs back into the previous chapter; the oldest paragraph is cut to fit.
        val recent = BookContext.recent(text, 1, 1, maxChars = 200)
        assertEquals(listOf(0 to 3, 0 to 4, 1 to 0, 1 to 1), recent.map { it.chapter to it.paragraph })
        assertTrue(recent.first().text.startsWith("… "))
        assertTrue(recent.sumOf { it.text.length } <= 205)
    }

    @Test
    fun locatesTheSelectionParagraph() {
        assertEquals(2, text.findParagraph(0, "brass  key", near = 0))
        assertEquals(1, text.findParagraph(2, "brass key", near = 4))
        assertEquals(null, text.findParagraph(3, "stone owl", near = 0))
    }

    @Test
    fun literalSearchCountsOccurrences() {
        val matches = index.find("brass key")
        assertEquals(listOf(0, 2), matches.map { it.chapter })
        assertTrue(matches.all { it.occurrence == 0 && it.text == "brass key" })
        val brass = index.find("BRASS")
        assertEquals(listOf(0, 1, 2), brass.map { it.chapter })
        assertTrue(brass.all { it.text == "brass" && it.occurrence == 0 })
        // A second occurrence of the same exact text in a chapter counts up; other capitalisations count separately.
        val and = index.find("and").filter { it.chapter == 0 }
        assertEquals(listOf(0, 1), and.map { it.occurrence })
        assertEquals(listOf(0, 0), index.find("she").filter { it.chapter == 0 }.map { it.occurrence })
        assertTrue(index.find("x").isEmpty())
    }

    @Test
    fun stemmingMatchesInflectedForms() {
        assertEquals(BookIndex.stem("schuppen"), BookIndex.stem("schuppens"))
        assertEquals(BookIndex.stem("бабуся"), BookIndex.stem("бабусі"))
        assertEquals(BookIndex.stem("бабуся"), BookIndex.stem("бабусею"))
        assertEquals(BookIndex.stem("letter"), BookIndex.stem("letters"))
        assertEquals(BookIndex.stem("key"), BookIndex.stem("keys"))
        assertEquals(listOf("jonas", "shop"), BookIndex.tokenize("Jonas's shop"))

        BookOpener.open(BookTest.fixture("sample.fb2")).use { fb2 ->
            val uk = BookIndex(BookText.from(fb2))
            assertTrue(uk.search("Що пекла бабусі?").isNotEmpty())
        }
        BookOpener.open(BookTest.fixture("sample.epub")).use { de ->
            val hits = BookIndex(BookText.from(de)).search("Warum war die Tür des Schuppens offen?")
            assertTrue(hits.toString(), hits.isNotEmpty())
        }
    }

    @Test
    fun promptsCarrySelectionContextAndRules() {
        val s = AppSettings(language = "Ukrainian", cefr = "A2")
        val book = BookInfo("The Brass Key", "Test Author", "English", "chapter 3 of 4 (“The Shed”)")
        val system = Prompts.system(book, s)
        assertTrue(system.contains("They have read up to chapter 3 of 4 (“The Shed”). Don't reveal what happens later"))
        assertTrue(system.contains("Never invent quotations"))
        assertTrue(system.contains("Always answer in Ukrainian"))

        val context = BookContext.format(text, BookContext.around(text, 0, 2, 0, 0))
        val simplify = Prompts.first(AiRequest(AiAction.SIMPLIFY, "half buried in moss", "Under the stone owl…", context), s, "English")
        assertTrue(simplify, simplify.startsWith("<selection>\nhalf buried in moss\n</selection>\n\n<sentence>"))
        assertTrue(simplify, simplify.contains("<book_context>\n[Chapter 1 “The Stone Owl”, ¶3]\nUnder the stone owl"))
        assertTrue(simplify, simplify.contains("simpler English, at about CEFR level A2"))
        assertFalse(simplify.contains("<earlier_passages>"))

        val vocab = Prompts.first(AiRequest(AiAction.VOCABULARY, "apprentice", "He had become a clockmaker's apprentice"), s, "English")
        assertTrue(vocab, vocab.contains("## In this passage") && vocab.contains("## Other meanings"))
        val longVocab = Prompts.first(AiRequest(AiAction.VOCABULARY, "half buried in moss by the gate"), s, "English")
        assertTrue(longVocab.contains("List the 3–6 words"))

        val ask = Prompts.first(AiRequest(AiAction.ASK, "brass key", question = "Who left it here?", related = "[Chapter 1, ¶2]\n…"), s, "English")
        assertTrue(ask, ask.contains("<earlier_passages>") && ask.endsWith("My question about the selected text: Who left it here?"))
        assertEquals("plain follow-up when nothing new was found", "Why?", Prompts.followUp("Why?", ""))
    }
}
