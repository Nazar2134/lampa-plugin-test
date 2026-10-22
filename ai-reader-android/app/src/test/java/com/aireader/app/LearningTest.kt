package com.aireader.app

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.util.Locale

/** Learning mode: word look-ups and their cache, word statuses, the database upgrade, pronunciation and reading aloud. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class LearningTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockServer
    private var controller: ActivityController<ReaderActivity>? = null
    private lateinit var activity: ReaderActivity
    private lateinit var bridge: Any
    private lateinit var bookId: String

    private val clockmaker = JSONObject()
        .put("word", "clockmaker").put("form", "clockmaker")
        .put("sentence", "He had become a clockmaker in the town by the river.")
        .put("top", 200).put("bottom", 224).put("occurrence", 0)

    private fun reply(json: String) = MockServer.Response(200, sse(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", json)).put("finish_reason", "stop")))
            .put("usage", JSONObject().put("prompt_tokens", 120).put("completion_tokens", 60)).toString(),
        "[DONE]",
    ))

    private val clockmakerGloss = """{"expression": "clockmaker", "lemma": "clockmaker", "pos": "noun", "article": "", "plural": "clockmakers",
        "forms": "", "translation": "годинникар", "in_context": "his trade in the town", "note": ""}"""

    @Before
    fun setUp() {
        ReaderDb.reset()
        ShadowTextToSpeech.reset()
        server = MockServer()
        DeepSeek.baseUrl = server.url
        Ai.retryDelayCap = 0
        Prefs(context).save(AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = "sk-test", language = "Ukrainian"))
        Prefs(context).learningMode = true
        val file = File(context.cacheDir, "retrieval.epub")
        BookTest.fixture("retrieval.epub").copyTo(file, overwrite = true)
        bookId = Library(context).import(Uri.fromFile(file)).id
    }

    private fun open() {
        controller = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)).setup()
        activity = controller!!.get()
        settle()
        bridge = shadowOf(activity.findViewById<ReaderWebView>(R.id.web)).getJavascriptInterface("Android")
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        server.close()
        ReaderDb.reset()
    }

    private fun settle(rounds: Int = 6) = repeat(rounds) {
        Thread.sleep(120)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun call(name: String, vararg args: Any) {
        val method = bridge.javaClass.declaredMethods.first { it.name == name }
        method.isAccessible = true
        method.invoke(bridge, *args)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text(id: Int) = activity.findViewById<TextView>(id).let { if (it.visibility == View.VISIBLE) it.text.toString() else "" }
    private fun card() = activity.findViewById<View>(R.id.glossCard)
    private fun store() = WordStore.get(context, "en")

    private fun capture(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun tappedWordIsLookedUpOnceInItsSentenceAndThenComesFromTheCache() {
        open()
        server.queue += reply("```json\n$clockmakerGloss\n```")
        call("onWordTap", clockmaker.toString())
        assertEquals(View.VISIBLE, card().visibility)
        settle()
        assertEquals(1, server.requests.size)
        val prompt = JSONObject(server.last.body).getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
        assertTrue(prompt, "Word: clockmaker" in prompt && "Sentence: He had become a clockmaker in the town by the river." in prompt && "Ukrainian" in prompt)
        assertEquals("clockmaker", text(R.id.glossHeadword))
        assertEquals("noun  ·  pl. clockmakers", text(R.id.glossMeta))
        assertEquals("годинникар", text(R.id.glossTranslation))
        assertEquals("Here: his trade in the town", text(R.id.glossContext))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.glossLoading).visibility)
        assertEquals("usage is recorded", 120, ReaderDb.get(context).usage(AiProvider.DEEPSEEK, 0).let { it.inputTokens.toInt() })
        capture("gloss-card")

        // The same word again: from the cache, without a request.
        call("onTap")
        assertEquals(View.GONE, card().visibility)
        call("onWordTap", clockmaker.toString())
        settle(2)
        assertEquals(1, server.requests.size)
        assertEquals("годинникар", text(R.id.glossTranslation))

        // In another sentence the saved meaning shows at once, with an offer to check this sentence.
        call("onWordTap", JSONObject(clockmaker.toString()).put("sentence", "The clockmaker smiled.").toString())
        settle(2)
        assertEquals(1, server.requests.size)
        assertEquals("годинникар", text(R.id.glossTranslation))
        assertEquals("", text(R.id.glossContext))
        assertTrue(text(R.id.glossStatus).startsWith("Saved from another sentence"))
        server.queue += reply(clockmakerGloss.replace("his trade in the town", "the man who mends clocks"))
        activity.findViewById<View>(R.id.glossStatus).performClick()
        settle()
        assertEquals(2, server.requests.size)
        assertEquals("Here: the man who mends clocks", text(R.id.glossContext))
    }

    @Test
    fun learningAndKnownSetTheWordStatusAndLearningSavesItToVocabulary() {
        open()
        server.queue += reply(clockmakerGloss)
        call("onWordTap", clockmaker.toString())
        settle()
        activity.findViewById<View>(R.id.glossLearning).performClick()
        settle(2)
        assertEquals(WordStatus.LEARNING, store().status("Clockmaker"))
        assertEquals("★ Learning", text(R.id.glossLearning))
        val saved = ReaderDb.get(context).findVocab("clockmaker", bookId)
        assertNotNull(saved)
        assertEquals("годинникар", saved!!.translation)
        assertEquals("the card already has the meaning; nothing more is asked", 1, server.requests.size)

        // A word reviewed to "known" in the vocabulary is known in the book too.
        WordStore.learntInVocabulary(context, "clockmaker")
        assertEquals(WordStatus.KNOWN, store().status("clockmaker"))

        activity.findViewById<View>(R.id.glossKnown).performClick()
        assertEquals("tapping the current status again makes the word new", WordStatus.NEW, store().status("clockmaker"))
        activity.findViewById<View>(R.id.glossKnown).performClick()
        assertEquals(WordStatus.KNOWN, store().status("clockmaker"))
        assertEquals("known words close the card", View.GONE, card().visibility)
    }

    @Test
    fun withoutAKeyTheCardExplainsWhereMeaningsComeFrom() {
        Prefs(context).save(AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = ""))
        open()
        call("onWordTap", clockmaker.toString())
        settle(2)
        assertTrue(text(R.id.glossStatus), text(R.id.glossStatus).startsWith("Meanings come from your AI key"))
        assertEquals(0, server.requests.size)
        // "More…" opens the AI panel, which asks for a key right there.
        activity.findViewById<View>(R.id.glossMore).performClick()
        settle(2)
        assertEquals(View.GONE, card().visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiSetup).visibility)
    }

    @Test
    fun aRejectedKeyIsExplainedOnTheCard() {
        open()
        server.queue += MockServer.Response(401, """{"error":{"message":"Authentication Fails","type":"authentication_error"}}""", "application/json")
        call("onWordTap", clockmaker.toString())
        settle()
        assertTrue(text(R.id.glossStatus), text(R.id.glossStatus).startsWith("DeepSeek didn't accept your key"))
    }

    @Test
    fun statusLineCountsNewWordsAndTheRestCanBeMarkedKnown() {
        open()
        call("onPage", 2, 9, "", 0L)
        call("onWordStats", 100, 6, 3)
        val status = activity.findViewById<TextView>(R.id.status)
        assertEquals("3 / 9  ·  ${status.text.split("  ·  ")[1]}  ·  6 new words  ·  94% familiar", status.text.toString())
        assertTrue(status.isClickable)
        call("onWordStats", 100, 0, 3)
        assertFalse(status.isClickable)

        // Turning learning mode off from the style panel restores the usual line.
        activity.findViewById<View>(R.id.learnOff).performClick()
        assertFalse(Prefs(context).learningMode)
        assertTrue(status.text.toString().contains(" / 9"))
        assertFalse(status.text.toString().contains("new words"))
        activity.findViewById<View>(R.id.learnUnderline).performClick()
        assertTrue(Prefs(context).learningMode && Prefs(context).underlineNew)
    }

    @Test
    fun chaptersCarryTheStatusesOfWordsAndOfFormsOfThem() {
        store().setStatus("clockmaker", WordStatus.LEARNING)
        store().saveGloss("clockmakers", Gloss.parse("clockmakers", clockmakerGloss)!!)
        store().setStatuses(listOf("The", "and"), WordStatus.KNOWN)
        open()
        val book = BookSession.get(bookId)!!.book
        val path = Paths.decode(book.chapterUrl(1).removePrefix(Paths.ORIGIN))
        val serve = activity.javaClass.getDeclaredMethod("serve", String::class.java).apply { isAccessible = true }
        val html = (serve.invoke(activity, path) as android.webkit.WebResourceResponse).data.readBytes().toString(Charsets.UTF_8)
        val init = JSONObject(html.substringAfter("window.READER_INIT=").substringBefore(";</script>").replace("<\\/", "</"))
        val learning = init.getJSONObject("learning")
        assertTrue(learning.getBoolean("enabled"))
        val statuses = learning.getJSONObject("statuses")
        assertEquals(1, statuses.getInt("clockmaker"))
        assertEquals("a form follows its dictionary form", 1, statuses.getInt("clockmakers"))
        assertEquals(2, statuses.getInt("the"))
        assertFalse(statuses.has("river"))
    }

    @Test
    fun wordsArePronouncedAndPagesReadAloudSentenceBySentence() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        open()
        server.queue += reply(clockmakerGloss)
        call("onWordTap", clockmaker.toString())
        settle()
        activity.findViewById<View>(R.id.glossSpeak).performClick()
        settle(2)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        if (shadowOf(tts).lastSpokenText == null) {
            shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
            settle(1)
        }
        assertEquals("clockmaker", shadowOf(tts).lastSpokenText)
        assertEquals(Locale.US, shadowOf(tts).currentLanguage)
        activity.findViewById<View>(R.id.glossSpeak).performLongClick()
        assertEquals("He had become a clockmaker in the town by the river.", shadowOf(tts).lastSpokenText)

        // Reading aloud: the speech engine gets the page's sentences; each is shown as it starts.
        val speech = activity.javaClass.getDeclaredField("speechEngine").apply { isAccessible = true }.get(activity) as Speech
        speech.read(listOf("First sentence.", "Second sentence."), "en", 1.2f)
        assertEquals(listOf("First sentence.", "Second sentence."), shadowOf(tts).spokenTextList.takeLast(2))
        assertTrue(speech.isReading)
        var shown = -1
        var ended: Boolean? = null
        speech.onSentence = { shown = it }
        speech.onReadingEnded = { ended = it }
        shadowOf(tts).utteranceProgressListener.onStart("read:1:1")
        settle(1)
        assertEquals(1, shown)
        shadowOf(tts).utteranceProgressListener.onDone("read:1:1")
        settle(1)
        assertEquals(true, ended)
        assertFalse(speech.isReading)
    }

    @Test
    fun aMissingVoiceOffersToInstallOne() {
        open()
        // No English voice is installed here.
        val speech = activity.javaClass.getDeclaredMethod("speech").apply { isAccessible = true }.invoke(activity) as Speech
        speech.say("clock", "en", 1f)
        settle(2)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        if (org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() == null) {
            shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
            settle(2)
        }
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals("No English voice", shadowOf(dialog).title.toString())
        assertNull("nothing was spoken", shadowOf(tts).lastSpokenText)
    }

    @Test
    fun upgradingFromVersion1KeepsEverythingAndAddsTheLearningTables() {
        ReaderDb.reset()
        val path = context.getDatabasePath(ReaderDb.NAME)
        path.parentFile!!.mkdirs()
        path.delete()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, chapter INTEGER NOT NULL,
               fraction REAL NOT NULL, anchor_text TEXT NOT NULL, anchor_occ INTEGER NOT NULL, label TEXT NOT NULL, created INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, chapter INTEGER NOT NULL,
               text TEXT NOT NULL, occurrence INTEGER NOT NULL, color TEXT NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE vocab (id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL, sentence TEXT NOT NULL,
               translation TEXT NOT NULL, explanation TEXT NOT NULL, book_id TEXT, book_title TEXT NOT NULL, chapter INTEGER NOT NULL,
               chapter_title TEXT NOT NULL, anchor_occ INTEGER NOT NULL, notes TEXT NOT NULL, created INTEGER NOT NULL,
               status TEXT NOT NULL, due INTEGER NOT NULL, interval_days REAL NOT NULL, ease REAL NOT NULL, reviews INTEGER NOT NULL)""")
            db.execSQL("CREATE TABLE chat (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, from_user INTEGER NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE ai_cache (key TEXT PRIMARY KEY, response TEXT NOT NULL, used INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, model TEXT NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("""INSERT INTO vocab (word, sentence, translation, explanation, book_id, book_title, chapter, chapter_title, anchor_occ, notes, created, status, due, interval_days, ease, reviews)
               VALUES ('Nebel', 'Es lag Nebel.', 'туман', '', 'b1', 'Der Garten', 0, 'Kapitel 1', 0, '', 1, 'LEARNING', 0, 1.0, 2.5, 1)""")
            db.execSQL("INSERT INTO highlights (book_id, chapter, text, occurrence, color, note, created) VALUES ('b1', 0, 'lag Nebel', 0, 'green', 'weather', 1)")
            db.version = 1
        }
        val db = ReaderDb.get(context)
        assertEquals(ReaderDb.VERSION, db.readableDatabase.version)
        assertEquals("туман", db.findVocab("Nebel", "b1")!!.translation)
        assertEquals("weather", db.highlights("b1").single().note)
        WordStore.get(context, "de").setStatus("Nebel", WordStatus.LEARNING)
        assertEquals(mapOf("nebel" to WordStatus.LEARNING), db.wordStatuses("de"))
    }

    @Test
    fun glossesAndLanguagesAreReadSensibly() {
        val g = Gloss.parse("Häusern", """Sure! {"expression":"Häusern","lemma":"Haus","pos":"noun","article":"Das","plural":"die Häuser","forms":null,"translation":"дім, будинок","in_context":"будинках","note":""}""")!!
        assertEquals("das Haus", g.headword)
        assertEquals("", g.forms)
        val verb = Gloss.parse("fing", """{"expression":"anfangen","lemma":"fangen","pos":"verb","article":"","translation":"починати","in_context":"почала"}""")!!
        assertEquals("the separable verb is shown, not the bare stem", "anfangen", verb.headword)
        assertNull(Gloss.parse("x", "no json here"))
        assertEquals("de", LanguageGuess.ofMetadata("de-DE"))
        assertEquals("", LanguageGuess.ofMetadata("und"))
        assertEquals("de", LanguageGuess.ofText("Der Hund und die Katze sind nicht im Haus, und ich bin es auch nicht, sagte sie mit dem Ball auf dem Tisch."))
        assertEquals("en", LanguageGuess.ofText("The dog and the cat were not in the house, and it was raining when she came with her friend."))
    }
}
