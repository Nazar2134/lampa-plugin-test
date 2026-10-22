package com.aireader.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File

/** The reader's AI panel end to end, against a mock DeepSeek server: what is sent, what is shown, what is kept. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class AiPanelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockServer
    private lateinit var controller: ActivityController<ReaderActivity>
    private lateinit var activity: ReaderActivity
    private lateinit var ai: AiPanel
    private lateinit var bookId: String

    private fun answer(text: String) = MockServer.Response(200, sse(
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)).put("finish_reason", "stop")))
            .put("usage", JSONObject().put("prompt_tokens", 900).put("completion_tokens", 60)).toString(),
        "[DONE]",
    ))

    @Before
    fun setUp() {
        ReaderDb.reset()
        server = MockServer()
        DeepSeek.baseUrl = server.url
        Ai.retryDelayCap = 0
        Prefs(context).save(AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = "sk-test", language = "English"))
        val file = File(context.cacheDir, "retrieval.epub")
        BookTest.fixture("retrieval.epub").copyTo(file, overwrite = true)
        bookId = Library(context).import(Uri.fromFile(file)).id
        controller = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)).setup()
        activity = controller.get()
        settle()
        ai = activity.javaClass.getDeclaredField("ai").apply { isAccessible = true }.get(activity) as AiPanel
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        server.close()
        ReaderDb.reset()
    }

    /** Lets background work (book loading, AI requests) finish and delivers its results to the main thread. */
    private fun settle(rounds: Int = 6) {
        repeat(rounds) {
            Thread.sleep(120)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun shown() = ai.shownText()

    private fun capture(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sentMessages() = JSONObject(server.last.body).getJSONArray("messages")
    private fun lastUserMessage(): String { val m = sentMessages(); return m.getJSONObject(m.length() - 1).getString("content") }

    private val shed = Selection("brass key", "On the first of May Marta took the brass key from her pocket and walked down to the shed.",
        "On the first of May Marta took the brass key from her pocket and walked down to the shed.", 2, 0)

    @Test
    fun explainSendsSelectionWithItsParagraphsAndCachesTheAnswer() {
        server.queue += answer("The key Marta found earlier (Ch. 1).")
        ai.openForSelection(AiAction.EXPLAIN, shed)
        settle()
        assertEquals("The key Marta found earlier (Ch. 1).", shown())
        val user = lastUserMessage()
        assertTrue(user, user.startsWith("<selection>\nbrass key\n</selection>"))
        assertTrue(user, user.contains("<book_context>\n[Chapter 3 “The Shed”, ¶1–3]\nThe Shed\nOn the first of May"))
        assertTrue("one paragraph either side", user.contains("The lock was stiff, but it turned."))
        assertFalse("nothing from later chapters", user.contains("The diary explained"))
        val system = sentMessages().getJSONObject(0).getString("content")
        assertTrue(system, system.contains("The Brass Key") && system.contains("read up to chapter 1 of 4"))
        assertEquals(UsageSummary(1, 900, 60), ReaderDb.get(context).usage(AiProvider.DEEPSEEK, 0))

        // The same explanation again comes from the cache, without a request.
        ai.close()
        ai.openForSelection(AiAction.EXPLAIN, shed)
        settle()
        assertEquals(1, server.requests.size)
        assertEquals("The key Marta found earlier (Ch. 1).", shown())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiStatus).visibility)

        // ↻ asks again.
        server.queue += answer("A small brass key.")
        activity.findViewById<View>(R.id.aiRegenerate).performClick()
        settle()
        assertEquals(2, server.requests.size)
        assertEquals("A small brass key.", shown())
    }

    @Test
    fun askRetrievesEarlierChaptersForTheQuestion() {
        server.queue += answer("Under the stone owl by the gate (Ch. 1).")
        ai.openForSelection(AiAction.ASK, shed)
        settle()
        assertEquals("Ask waits for a question", 0, server.requests.size)
        activity.findViewById<EditText>(R.id.aiInput).setText("Where did Marta find it?")
        activity.findViewById<View>(R.id.aiSend).performClick()
        settle()
        val user = lastUserMessage()
        assertTrue(user, user.contains("<earlier_passages>"))
        val earlier = user.substringAfter("<earlier_passages>").substringBefore("</earlier_passages>")
        assertTrue(earlier, Regex("\\[Chapter 1 “The Stone Owl”, ¶\\d+–\\d+]").containsMatchIn(earlier) && earlier.contains("Under the stone owl by the gate"))
        assertFalse("never from chapters not yet read", user.contains("letters for Jonas"))
        assertTrue(user.endsWith("My question about the selected text: Where did Marta find it?"))
        assertTrue(shown(), shown().contains("**Where did Marta find it?**") || shown().contains("Where did Marta find it?"))

        // The first answer already carried the neighbouring passages, including the clockmaker one.
        assertTrue(user.contains("clockmaker's apprentice"))

        // A follow-up keeps the conversation and attaches only passages not sent yet.
        server.queue += answer("Frost silvered it (Ch. 1).")
        activity.findViewById<EditText>(R.id.aiInput).setText("What happened to the lettuce that night?")
        activity.findViewById<View>(R.id.aiSend).performClick()
        settle()
        val messages = sentMessages()
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertTrue(messages.getJSONObject(1).getString("content").contains("<selection>"))
        val followUp = lastUserMessage()
        assertTrue(followUp, followUp.contains("frost silvered the lettuce") && followUp.endsWith("What happened to the lettuce that night?"))
        assertFalse("passages sent before aren't repeated", followUp.contains("Under the stone owl"))
    }

    @Test
    fun bookChatIsSavedPerBook() {
        server.queue += answer("Her brother, who lives in Hamburg (Ch. 2).")
        ai.openForChat("Marta did not answer that question.")
        activity.findViewById<EditText>(R.id.aiInput).setText("Who is Jonas?")
        activity.findViewById<View>(R.id.aiSend).performClick()
        settle()
        assertTrue(lastUserMessage().contains("<book_context>"))
        assertEquals(listOf(true to "Who is Jonas?", false to "Her brother, who lives in Hamburg (Ch. 2)."),
            ReaderDb.get(context).chat(bookId).map { it.fromUser to it.text })

        // Reopening shows the conversation; New chat clears it.
        ai.close()
        ai.openForChat("")
        assertTrue(shown(), shown().contains("Her brother, who lives in Hamburg"))
        activity.findViewById<View>(R.id.aiNewChat).performClick()
        assertTrue(ReaderDb.get(context).chat(bookId).isEmpty())
        assertEquals("", shown())
    }

    @Test
    fun providerErrorsAreExplained() {
        server.fallback = MockServer.Response(402, """{"error":{"message":"Insufficient Balance","type":"unknown_error"}}""", "application/json")
        ai.openForSelection(AiAction.TRANSLATE, shed)
        settle()
        assertTrue(shown(), shown().contains("Your DeepSeek balance is empty"))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiSettings).visibility)
        assertEquals("no retry for a billing error", 1, server.requests.size)

        server.fallback = MockServer.Response(429, """{"error":{"message":"Rate limit reached"}}""", "application/json")
        ai.openForSelection(AiAction.GRAMMAR, shed)
        settle(10)
        assertTrue(shown(), shown().contains("too many requests"))
        assertEquals("one retry for a rate limit", 3, server.requests.size)
    }

    @Test
    fun renamedGeminiModelIsReplacedAndRetried() {
        Gemini.baseUrl = server.url
        Prefs(context).save(AppSettings(provider = AiProvider.GEMINI, geminiKey = "g-key", geminiModel = Gemini.MODEL_LITE))
        val notFound = MockServer.Response(404, """{"error":{"code":404,"message":"models/gemini-flash-lite-latest is not found","status":"NOT_FOUND"}}""", "application/json")
        server.queue += notFound // the request
        server.queue += notFound // check: the model itself
        server.queue += MockServer.Response(200, """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}]}""", "application/json")
        server.queue += MockServer.Response(200, sse("""{"candidates":[{"content":{"parts":[{"text":"Ein Schlüssel."}]},"finishReason":"STOP"}]}"""))
        ai.openForSelection(AiAction.TRANSLATE, shed)
        settle(10)
        assertEquals("Ein Schlüssel.", shown())
        assertEquals("gemini-3.5-flash-lite", Prefs(context).load().geminiModel)
        assertTrue(server.requests.last().line.contains("/models/gemini-3.5-flash-lite:streamGenerateContent"))
    }

    @Test
    fun withoutAKeyTheKeyIsEnteredInThePanelAndTheAnswerStaysHere() {
        Prefs(context).save(AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = "", language = "Ukrainian"))
        ai.openForSelection(AiAction.EXPLAIN, shed)
        settle()
        assertEquals("nothing is sent without a key", 0, server.requests.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiSetup).visibility)
        assertEquals("the other apps are offered only now", View.VISIBLE, activity.findViewById<View>(R.id.aiClaude).visibility)
        capture("chat-setup")

        server.queue += answer("Ключ, який Марта знайшла раніше.")
        activity.findViewById<EditText>(R.id.aiSetupKey).setText("sk-new")
        activity.findViewById<View>(R.id.aiSetupSave).performClick()
        settle()
        assertEquals("Bearer sk-new", server.last.headers["authorization"])
        assertEquals("sk-new", Prefs(context).load().deepseekKey)
        assertEquals("Ключ, який Марта знайшла раніше.", shown())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.aiSetup).visibility)
    }

    @Test
    fun chatGptOpensInAWindowOverTheBookNotItsApp() {
        server.fallback = answer("x")
        ai.openForSelection(AiAction.EXPLAIN, shed)
        settle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiChatgpt).visibility)
        activity.findViewById<View>(R.id.aiChatgpt).performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Handoff.REQUEST_CODE, started.requestCode)
        val intent = started.intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("chatgpt.com", intent.data!!.host)
        val question = intent.data!!.getQueryParameter("q")!!
        assertTrue(question, question.contains("\"brass key\"") && question.contains("The Brass Key"))
        assertTrue("shown as a bottom sheet", intent.getIntExtra("androidx.browser.customtabs.extra.INITIAL_ACTIVITY_HEIGHT_PX", 0) > 0)
        assertTrue("a Custom Tab", intent.hasExtra("android.support.customtabs.extra.SESSION"))
        assertTrue("not the ChatGPT app", intent.`package` != "com.openai.chatgpt")
        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(question, clip)

        activity.findViewById<View>(R.id.aiClaude).performClick()
        assertEquals("claude.ai", shadowOf(activity).nextStartedActivityForResult.intent.data!!.host)
    }

    @Test
    fun aRejectedKeyIsAskedForAgain() {
        server.fallback = MockServer.Response(401, """{"error":{"message":"Authentication Fails","type":"authentication_error"}}""", "application/json")
        ai.openForSelection(AiAction.TRANSLATE, shed)
        settle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiSetup).visibility)
        assertTrue(activity.findViewById<TextView>(R.id.aiSetupError).text.toString().contains("rejected"))
        server.fallback = answer("brass key → латунний ключ")
        activity.findViewById<View>(R.id.aiSetupGemini).performClick()
        activity.findViewById<View>(R.id.aiSetupDeepseek).performClick()
        activity.findViewById<EditText>(R.id.aiSetupKey).setText("sk-fixed")
        activity.findViewById<View>(R.id.aiSetupSave).performClick()
        settle()
        assertEquals("brass key → латунний ключ", shown())
    }

    @Test
    fun conversationIsShownAsChatMessages() {
        server.queue += answer("Under the stone owl (Ch. 1).")
        ai.openForSelection(AiAction.ASK, shed)
        settle()
        activity.findViewById<EditText>(R.id.aiInput).setText("Where did Marta find it?")
        activity.findViewById<View>(R.id.aiSend).performClick()
        settle()
        val list = activity.findViewById<android.widget.LinearLayout>(R.id.aiMessages)
        assertEquals(2, list.childCount)
        val question = list.getChildAt(0) as TextView
        val reply = list.getChildAt(1) as TextView
        assertEquals("Where did Marta find it?", question.text.toString())
        assertEquals("your message on the right", android.view.Gravity.END, (question.layoutParams as android.widget.LinearLayout.LayoutParams).gravity)
        assertEquals("Under the stone owl (Ch. 1).", reply.text.toString())
        assertEquals(android.view.Gravity.START, (reply.layoutParams as android.widget.LinearLayout.LayoutParams).gravity)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.aiCopy).visibility)
        capture("chat-conversation")
    }

    @Test
    fun theChatExpandsToFullScreen() {
        server.fallback = answer("x")
        ai.openForSelection(AiAction.EXPLAIN, shed)
        settle()
        val panel = activity.findViewById<View>(R.id.aiPanel)
        val normal = panel.layoutParams.height
        activity.findViewById<View>(R.id.aiGrabber).performClick()
        assertTrue("${panel.layoutParams.height} > $normal", panel.layoutParams.height > normal)
        activity.findViewById<View>(R.id.aiGrabber).performClick()
        assertEquals(normal, panel.layoutParams.height)
    }

    @Test
    fun selectionMenuHidesOtherAppsItems() {
        val menu = org.robolectric.fakes.RoboMenu(context)
        menu.add(0, 1, 0, "Copy")
        menu.add(0, 2, 1, "ChatGPT").intent = Intent(Intent.ACTION_PROCESS_TEXT).setPackage("com.openai.chatgpt")
        menu.add(0, 3, 2, "Claude").intent = Intent(Intent.ACTION_PROCESS_TEXT).setPackage("com.anthropic.claude")
        ReaderWebView.hideOtherApps(menu)
        assertTrue(menu.findItem(1).isVisible)
        assertFalse(menu.findItem(2).isVisible)
        assertFalse(menu.findItem(3).isVisible)
    }

    @Test
    fun languageAndLevelReachThePrompt() {
        Prefs(context).setLanguage("German")
        Prefs(context).setCefr("A2")
        server.fallback = answer("Marta nahm den Schlüssel.")
        ai.openForSelection(AiAction.SIMPLIFY, shed)
        settle()
        val user = lastUserMessage()
        assertTrue(user, user.contains("simpler English, at about CEFR level A2") && user.contains("one-line German translation"))
        assertTrue(sentMessages().getJSONObject(0).getString("content").contains("Always answer in German"))
        assertEquals("Level A2 ▾", activity.findViewById<TextView>(R.id.aiLevel).text.toString())
        assertEquals("In German ▾", activity.findViewById<TextView>(R.id.aiLanguage).text.toString())
    }
}
