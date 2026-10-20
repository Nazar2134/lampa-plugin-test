package com.aireader.app

import android.content.Context
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = ReaderDb.reset()

    @After
    fun tearDown() = ReaderDb.reset()

    /** A new helper instance reads the same database file, as after an app restart. */
    private fun reopen(): ReaderDb {
        ReaderDb.reset()
        return ReaderDb.get(context)
    }

    @Test
    fun notesBookmarksAndVocabularySurviveRestart() {
        val db = ReaderDb.get(context)
        val bm = db.addBookmark(Bookmark(bookId = "b1", chapter = 2, fraction = 0.4f, anchorText = "Später las sie", anchorOccurrence = 1, label = "Später las sie"))
        val hl = db.addHighlight(Highlight(bookId = "b1", chapter = 2, text = "hätte gern", occurrence = 0))
        db.updateHighlight(hl, "blue", "Konjunktiv II")
        val v = db.addVocab(VocabEntry(word = "Schuppen", sentence = "Die Spuren führten zum alten Schuppen.", bookId = "b1", bookTitle = "Der kleine Garten", chapter = 0))
        db.addChat("b1", ChatMessage(true, "Who is Jonas?"))
        db.addChat("b1", ChatMessage(false, "Her brother (Ch. 2)."))

        val again = reopen()
        assertEquals(listOf(bm), again.bookmarks("b1").map { it.id })
        assertEquals(Bookmark(bm, "b1", 2, 0.4f, "Später las sie", 1, "Später las sie", again.bookmarks("b1")[0].created), again.bookmarks("b1")[0])
        assertEquals("blue" to "Konjunktiv II", again.highlight(hl)!!.let { it.color to it.note })
        assertEquals(1, again.highlights("b1", chapter = 2).size)
        assertEquals(0, again.highlights("b1", chapter = 1).size)
        assertEquals("Schuppen", again.vocab(v)!!.word)
        assertEquals(listOf(true, false), again.chat("b1").map { it.fromUser })
    }

    @Test
    fun forgettingABookKeepsItsVocabulary() {
        val db = ReaderDb.get(context)
        db.addBookmark(Bookmark(bookId = "b1", chapter = 0, fraction = 0f, anchorText = "", anchorOccurrence = 0, label = ""))
        db.addHighlight(Highlight(bookId = "b1", chapter = 0, text = "x", occurrence = 0))
        db.addChat("b1", ChatMessage(true, "q"))
        val kept = db.addVocab(VocabEntry(word = "Nebel", bookId = "b1", bookTitle = "Der kleine Garten"))
        db.addBookmark(Bookmark(bookId = "b2", chapter = 0, fraction = 0f, anchorText = "", anchorOccurrence = 0, label = ""))

        db.forgetBook("b1")
        assertTrue(db.bookmarks("b1").isEmpty())
        assertTrue(db.highlights("b1").isEmpty())
        assertTrue(db.chat("b1").isEmpty())
        assertEquals(1, db.bookmarks("b2").size)
        val v = db.vocab(kept)!!
        assertNull(v.bookId)
        assertEquals("Der kleine Garten", v.bookTitle)
    }

    @Test
    fun vocabularySearchAndFilters() {
        val db = ReaderDb.get(context)
        db.addVocab(VocabEntry(word = "Schuppen", translation = "shed", bookId = "b1", created = 1))
        db.addVocab(VocabEntry(word = "Nebel", translation = "fog", notes = "50% of mornings", status = VocabStatus.LEARNING, due = 100, bookId = "b1", created = 2))
        db.addVocab(VocabEntry(word = "стежка", translation = "path", status = VocabStatus.KNOWN, due = 10_000, bookId = "b2", created = 3))
        assertEquals(listOf("стежка", "Nebel", "Schuppen"), db.vocabulary().map { it.word })
        assertEquals(listOf("Schuppen"), db.vocabulary(search = "SHED").map { it.word })
        assertEquals("LIKE wildcards are literal", listOf("Nebel"), db.vocabulary(search = "50%").map { it.word })
        assertTrue(db.vocabulary(search = "_").isEmpty())
        assertEquals(listOf("Nebel"), db.vocabulary(status = VocabStatus.LEARNING).map { it.word })
        assertEquals(listOf("стежка"), db.vocabulary(bookId = "b2").map { it.word })
        assertEquals(listOf("Schuppen", "Nebel"), db.vocabulary(dueBefore = 500).map { it.word })
        assertEquals("Schuppen", db.findVocab("schuppen", "b1")?.word)
        assertNull(db.findVocab("schuppen", "b2"))
    }

    @Test
    fun answerCacheKeepsTheMostRecent() {
        val db = ReaderDb.get(context)
        val key = ReaderDb.cacheKey("DEEPSEEK", "deepseek-flash", "EXPLAIN", "English", "hätte")
        assertNull(db.cached(key))
        db.cache(key, "answer")
        assertEquals("answer", db.cached(key))
        assertFalse(key == ReaderDb.cacheKey("DEEPSEEK", "deepseek-flash", "EXPLAIN", "German", "hätte"))
        for (i in 0 until 405) db.cache("k$i", "v$i")
        assertNull("oldest entries are evicted", db.cached("k0"))
        assertEquals("v404", db.cached("k404"))
    }

    @Test
    fun usageIsSummedPerProvider() {
        val db = ReaderDb.get(context)
        db.addUsage(AiProvider.DEEPSEEK, "deepseek-flash", 1000, 200)
        db.addUsage(AiProvider.DEEPSEEK, "deepseek-flash", 500, 100)
        db.addUsage(AiProvider.GEMINI, "gemini-flash-latest", 10, 1)
        assertEquals(UsageSummary(2, 1500, 300), db.usage(AiProvider.DEEPSEEK, 0))
        assertEquals(UsageSummary(0, 0, 0), db.usage(AiProvider.DEEPSEEK, System.currentTimeMillis() + 60_000))
    }

    @Test
    fun settingsMigrateFromVersionOne() {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit().clear().putBoolean("serif", false).putString("deepseek_key", "sk-old-plain").putString("provider", "DEEPSEEK").putInt("font_size", 22).commit()
        val s = Prefs(context).load()
        assertEquals(ReaderFont.SANS, s.font)
        assertEquals("sk-old-plain", s.deepseekKey)
        assertEquals(AiProvider.DEEPSEEK, s.provider)
        assertEquals(22, s.fontSize)
        assertEquals(1.55f, s.lineHeight)
        // Saving and loading round-trips every field.
        val changed = s.copy(lineHeight = 1.8f, margin = ReaderMargin.WIDE, justify = false, theme = ReaderTheme.DARK, cefr = "A2", language = "German")
        Prefs(context).save(changed)
        assertEquals(changed, Prefs(context).load())
    }

    @Test
    fun readingPositionKeepsItsAnchor() {
        val prefs = Prefs(context)
        prefs.setPosition("b1", ReadingPosition(3, 0.25f, "Ihr Großvater", 2), 0.4f)
        assertEquals(ReadingPosition(3, 0.25f, "Ihr Großvater", 2), Prefs(context).position("b1"))
        assertEquals(0.4f, prefs.progress("b1"))
        prefs.forget("b1")
        assertEquals(ReadingPosition(0, 0f, "", 0), prefs.position("b1"))
    }

    private fun copyFixture(name: String): Uri {
        val file = File(context.cacheDir, name)
        BookTest.fixture(name).copyTo(file, overwrite = true)
        return Uri.fromFile(file)
    }

    @Test
    fun libraryImportsOnceAndRemovesCleanly() {
        val library = Library(context)
        val entry = library.import(copyFixture("sample.epub"))
        assertEquals("Der kleine Garten", entry.title)
        assertTrue(library.file(entry).exists())
        assertTrue("cover saved", library.cover(entry) != null)
        val booksDir = File(context.filesDir, "books")
        val before = booksDir.listFiles()!!.size
        assertEquals("the same book isn't stored twice", entry.id, library.import(copyFixture("sample.epub")).id)
        assertEquals(before, booksDir.listFiles()!!.size)

        val fb2 = library.import(copyFixture("sample.fb2"))
        assertEquals(setOf(entry.id, fb2.id), library.all().map { it.id }.toSet())

        val db = ReaderDb.get(context)
        db.addHighlight(Highlight(bookId = entry.id, chapter = 0, text = "Nebel", occurrence = 0))
        val word = db.addVocab(VocabEntry(word = "Nebel", bookId = entry.id, bookTitle = entry.title))
        Prefs(context).setPosition(entry.id, ReadingPosition(1, 0.5f), 0.7f)

        library.remove(entry)
        assertFalse(library.file(entry).exists())
        assertNull(library.get(entry.id))
        assertTrue(db.highlights(entry.id).isEmpty())
        assertNull(db.vocab(word)!!.bookId)
        assertEquals(0f, Prefs(context).progress(entry.id))
        assertEquals(listOf(fb2.id), Library(context).all().map { it.id })
    }

    @Test
    fun libraryRejectsOtherFiles() {
        val file = File(context.cacheDir, "notes.txt").apply { writeText("just text") }
        val result = runCatching { Library(context).import(Uri.fromFile(file)) }
        assertTrue(result.exceptionOrNull() is UnsupportedBookException)
        assertTrue("no temporary files left", File(context.filesDir, "books").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun cappedStreamsStopOversizedFiles() {
        val input = CappedInputStream(java.io.ByteArrayInputStream(ByteArray(2048)), max = 1024)
        assertTrue(runCatching { input.readBytes() }.exceptionOrNull() is BookTooLargeException)
        assertEquals(1000, CappedInputStream(java.io.ByteArrayInputStream(ByteArray(1000)), max = 1024).readBytes().size)
    }
}
