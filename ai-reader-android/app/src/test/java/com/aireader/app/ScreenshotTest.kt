package com.aireader.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the native screens as PNG files in build/screens and checks that their controls are wired up. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreenshotTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val out = File("build/screens").apply { mkdirs() }

    @Before
    fun setUp() = ReaderDb.reset()

    @After
    fun tearDown() = ReaderDb.reset()

    private fun idle(ms: Long = 0) {
        if (ms > 0) Thread.sleep(ms)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(activity: Activity, name: String) {
        idle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun importFixture(name: String): BookEntry {
        val file = File(context.cacheDir, name)
        BookTest.fixture(name).copyTo(file, overwrite = true)
        return Library(context).import(Uri.fromFile(file))
    }

    @Test
    fun emptyLibrary() {
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty).visibility)
        capture(activity, "library-empty")
    }

    @Test
    fun libraryWithBooks() {
        val garden = importFixture("sample.epub")
        importFixture("sample.fb2")
        Prefs(context).setPosition(garden.id, ReadingPosition(1, 0.3f), 0.42f)
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        assertEquals(2, activity.findViewById<ListView>(R.id.list).adapter.count)
        capture(activity, "library")
        // The vocabulary button opens the vocabulary screen.
        activity.findViewById<View>(R.id.vocabulary).performClick()
        assertEquals(VocabularyActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test
    fun vocabularyListAndReview() {
        val db = ReaderDb.get(context)
        db.addVocab(VocabEntry(word = "Schuppen", sentence = "Die Spuren führten zum alten Schuppen, dessen Tür einen Spalt offen stand.", translation = "shed",
            explanation = "der Schuppen, noun (masc.) — a small building for tools", bookId = "b1", bookTitle = "Der kleine Garten", chapterTitle = "Kapitel 1: Der Nebel", created = 3))
        db.addVocab(VocabEntry(word = "hätte gern", sentence = "Sie hätte gern länger geschlafen.", translation = "would have liked to",
            bookTitle = "Der kleine Garten", status = VocabStatus.LEARNING, due = Long.MAX_VALUE, created = 2))
        db.addVocab(VocabEntry(word = "стежка", sentence = "Стежка вилася між високими соснами.", translation = "path, trail", bookTitle = "Бабусин сад",
            status = VocabStatus.KNOWN, due = Long.MAX_VALUE, created = 1))
        val activity = Robolectric.buildActivity(VocabularyActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.list)
        assertEquals(3, list.adapter.count)
        capture(activity, "vocabulary")

        activity.findViewById<EditText>(R.id.query).setText("path")
        assertEquals(1, list.adapter.count)
        activity.findViewById<EditText>(R.id.query).setText("")
        activity.findViewById<View>(R.id.filterLearning).performClick()
        assertEquals(1, list.adapter.count)
        activity.findViewById<View>(R.id.filterAll).performClick()

        // Only "Schuppen" is due; reviewing it schedules it for tomorrow.
        activity.findViewById<View>(R.id.review).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.reviewPanel).visibility)
        capture(activity, "review-question")
        activity.findViewById<View>(R.id.showAnswer).performClick()
        capture(activity, "review-answer")
        activity.findViewById<View>(R.id.gradeGood).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.reviewPanel).visibility)
        val reviewed = db.vocabulary(search = "Schuppen").single()
        assertEquals(1, reviewed.reviews)
        assertEquals(VocabStatus.LEARNING, reviewed.status)
        assertTrue(reviewed.due > System.currentTimeMillis())
    }

    @Test
    fun settingsWithDeepSeek() {
        Prefs(context).save(AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = "sk-test", language = "Ukrainian"))
        ReaderDb.get(context).addUsage(AiProvider.DEEPSEEK, DeepSeek.MODEL_FLASH, 182_000, 41_000)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.deepseekSection).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.geminiSection).visibility)
        val usage = activity.findViewById<android.widget.TextView>(R.id.usage).text.toString()
        assertTrue(usage, usage.contains("DeepSeek: 1 request, 182,000 tokens in, 41,000 out · at most about \$0.104"))
        capture(activity, "settings")
        activity.findViewById<View>(R.id.providerGemini).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.geminiSection).visibility)
    }

    @Test
    fun navigatorContentsAndSearch() {
        val entry = importFixture("retrieval.epub")
        val session = BookSession.open(entry.id, Library(context).file(entry), entry.title)
        val db = ReaderDb.get(context)
        db.addHighlight(Highlight(bookId = entry.id, chapter = 0, text = "a small brass key with a red ribbon", occurrence = 0, color = "yellow", note = "Where does it fit?"))
        db.addBookmark(Bookmark(bookId = entry.id, chapter = 2, fraction = 0.5f, anchorText = "Inside, under a tarpaulin, stood a workbench", anchorOccurrence = 0, label = "Inside, under a tarpaulin, stood a workbench"))
        try {
            val intent = Intent(context, NavigatorActivity::class.java).putExtra(NavigatorActivity.EXTRA_BOOK_ID, entry.id).putExtra(NavigatorActivity.EXTRA_CHAPTER, 2)
            val activity = Robolectric.buildActivity(NavigatorActivity::class.java, intent).setup().get()
            val list = activity.findViewById<ListView>(R.id.list)
            assertEquals(4, list.adapter.count)
            capture(activity, "navigator-contents")
            activity.findViewById<View>(R.id.tabNotes).performClick()
            assertEquals(1, list.adapter.count)
            capture(activity, "navigator-notes")
            activity.findViewById<View>(R.id.tabBookmarks).performClick()
            assertEquals(1, list.adapter.count)
            activity.findViewById<View>(R.id.tabSearch).performClick()
            activity.findViewById<EditText>(R.id.query).setText("brass")
            // Typing is debounced on the main looper's (simulated) clock; the search itself runs on a thread.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            idle(500)
            assertEquals(3, list.adapter.count)
            capture(activity, "navigator-search")
            // Choosing a result returns the chapter and the text to find.
            shadowOf(list).performItemClick(2)
            val result = shadowOf(activity).resultIntent
            assertEquals(2, result.getIntExtra(ReaderActivity.EXTRA_CHAPTER, -1))
            assertTrue(result.getStringExtra(ReaderActivity.EXTRA_TARGET)!!.contains("\"text\":\"brass\""))
        } finally {
            BookSession.close(session.bookId)
        }
    }

    @Test
    fun readerPanels() {
        val entry = importFixture("retrieval.epub")
        Prefs(context).save(AppSettings(theme = ReaderTheme.SEPIA, provider = AiProvider.DEEPSEEK, language = "Ukrainian"))
        val intent = Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, entry.id)
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, intent).setup()
        val activity = controller.get()
        idle(500)
        idle()
        // Page turns from hardware keys (keyboard, volume buttons).
        val web = activity.findViewById<ReaderWebView>(R.id.web)
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("reader.next()", shadowOf(web).lastEvaluatedJavascript)
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("reader.prev()", shadowOf(web).lastEvaluatedJavascript)
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals("reader.next()", shadowOf(web).lastEvaluatedJavascript)
        // The page is served with the security policy header and the reader script.
        assertTrue(shadowOf(web).lastLoadedUrl.startsWith("https://book.local/OPS/c0.xhtml"))

        // Toolbar and style panel
        activity.javaClass.getDeclaredMethod("setBarsVisible", Boolean::class.java).apply { isAccessible = true }.invoke(activity, true)
        activity.findViewById<View>(R.id.style).performClick()
        idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.stylePanel).visibility)
        capture(activity, "reader-style")
        activity.findViewById<View>(R.id.themeDark).performClick()
        assertEquals(ReaderTheme.DARK, Prefs(context).load().theme)
        activity.findViewById<View>(R.id.spacingMore).performClick()
        assertEquals(1.6f, Prefs(context).load().lineHeight, 0.001f)
        activity.findViewById<View>(R.id.style).performClick()

        // AI panel without a key: explains how to add one, offers the free hand-off buttons.
        val ai = activity.javaClass.getDeclaredField("ai").apply { isAccessible = true }.get(activity) as AiPanel
        ai.openForSelection(AiAction.VOCABULARY, Selection("apprentice", "He had become a clockmaker's apprentice…", "He had become a clockmaker's apprentice in a narrow shop near the harbour.", 1, 0))
        idle()
        assertTrue(ai.isOpen)
        val answer = activity.findViewById<android.widget.TextView>(R.id.aiAnswer).text.toString()
        assertTrue(answer, answer.startsWith("Add your DeepSeek API key in Settings"))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiSaveWord).visibility)
        capture(activity, "reader-ai-panel")

        // Saving the selection stores it with its sentence and source.
        activity.findViewById<View>(R.id.aiSaveWord).performClick()
        val saved = ReaderDb.get(context).vocabulary().single()
        assertEquals("apprentice", saved.word)
        assertEquals(entry.id, saved.bookId)
        assertEquals("Letters from Hamburg", saved.chapterTitle)

        // Simplify shows the level picker.
        activity.findViewById<android.widget.LinearLayout>(R.id.aiChips).getChildAt(AiAction.SIMPLIFY.ordinal).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiLevel).visibility)
        controller.pause().stop().destroy()
    }
}
