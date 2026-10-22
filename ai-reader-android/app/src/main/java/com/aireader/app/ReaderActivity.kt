package com.aireader.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.concurrent.thread

class ReaderActivity : Activity(), AiHost {
    private lateinit var prefs: Prefs
    private lateinit var db: ReaderDb
    @Volatile private var settings = AppSettings()
    override lateinit var bookId: String
    @Volatile private var session: BookSession.Session? = null
    private val book get() = session?.book
    @Volatile private var pendingInit = "{}"
    @Volatile private var layout = ChapterRenderer.Layout(48, 40, 22)

    private var chapter = 0
    private var page = 0
    private var pageCount = 1
    private var pageAnchor: JSONObject? = null
    private var currentBookmark = 0L
    /**
     * Positions to return to with Back after following a link or jumping from the contents screen.
     * The first one is the reading place: while there are jumps the saved position is left alone, so a
     * look at a footnote or a search result doesn't lose where the reader stopped.
     */
    private val jumps = ArrayDeque<ReadingPosition>()
    /** Pages turned since the last jump; reading on this far makes the new spot the reading place. */
    private var awayPages = 0
    private var lastAwayPage = ""

    private lateinit var root: View
    private lateinit var web: ReaderWebView
    private lateinit var topBar: LinearLayout
    private lateinit var bookTitle: TextView
    private lateinit var status: TextView
    private lateinit var stylePanel: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var bookmarkButton: ImageButton
    private lateinit var selectionBar: LinearLayout
    private lateinit var backToPlace: LinearLayout
    private lateinit var ai: AiPanel
    private lateinit var glossCard: LinearLayout
    private lateinit var speakBar: TextView

    // Learning mode
    @Volatile private var learningOn = false
    /** The book's language code ("de", "en"…), once known. */
    @Volatile private var learnLang: String? = null
    private class WordStats(val total: Int, val fresh: Int, val learning: Int)
    private var wordStats: WordStats? = null
    /** A word tapped in learning mode, as reported by reader.js. */
    private class TappedWord(val word: String, val form: String, val sentence: String, val top: Int, val bottom: Int, val occurrence: Int)
    private var tapped: TappedWord? = null
    private var gloss: Gloss? = null
    private var glossGeneration = 0

    // Pronunciation and reading aloud; the speech engine starts on first use.
    private var speechEngine: Speech? = null
    private var readAloudOnLoad = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        prefs = Prefs(this)
        db = ReaderDb.get(this)
        settings = prefs.load()
        val library = Library(this)
        val entry = intent.getStringExtra(EXTRA_BOOK_ID)?.let { library.get(it) }
        if (entry == null) {
            finish()
            return
        }
        bookId = entry.id

        root = findViewById(R.id.root)
        web = findViewById(R.id.web)
        topBar = findViewById(R.id.topBar)
        bookTitle = findViewById(R.id.bookTitle)
        status = findViewById(R.id.status)
        stylePanel = findViewById(R.id.stylePanel)
        loading = findViewById(R.id.loading)
        bookmarkButton = findViewById(R.id.bookmark)
        selectionBar = findViewById(R.id.selectionBar)
        backToPlace = findViewById(R.id.backToPlace)
        glossCard = findViewById(R.id.glossCard)
        speakBar = findViewById(R.id.speakBar)
        ai = AiPanel(this, this)
        ai.onClosed = { updateBackToPlace() }
        learningOn = prefs.learningMode
        bookTitle.text = entry.title

        topBar.setOnApplyWindowInsetsListener { v, insets ->
            val top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        setUpWebView()
        setUpBars()
        setUpSelectionBar()
        setUpLearning()
        applyTheme()
        setBarsVisible(false)

        thread {
            val opened = runCatching { BookSession.open(bookId, library.file(entry), entry.title) }
            runOnUiThread {
                loading.visibility = View.GONE
                opened.onSuccess { s ->
                    if (isDestroyed) return@onSuccess
                    session = s
                    prefs.markOpened(bookId)
                    val saved = prefs.position(bookId)
                    val target = intent.getStringExtra(EXTRA_TARGET)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val requested = intent.getIntExtra(EXTRA_CHAPTER, -1).takeIf { it >= 0 }
                    if (requested != null || target != null) {
                        // Opened at a specific spot (e.g. a saved word): keep the reading place to come back to.
                        if (saved.isStarted()) leavePlace(saved)
                        val chapterToOpen = (requested ?: saved.chapter).coerceIn(0, s.book.chapterCount - 1)
                        openChapter(chapterToOpen, ReadingPosition(chapterToOpen, 0f), target = target)
                    } else {
                        openChapter(saved.chapter.coerceIn(0, s.book.chapterCount - 1), saved, resume = saved.isStarted())
                    }
                    // Text and index for search, progress and AI context; built once per book session.
                    thread(isDaemon = true) {
                        runCatching { s.prepare() }
                        runOnUiThread { if (!isDestroyed) updateStatus() }
                    }
                }.onFailure {
                    val message = if (it is BookTooLargeException) "This book is too large to open." else "Couldn't open this book: ${it.message}"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        speechEngine?.shutdown()
        speechEngine = null
        if (::bookId.isInitialized && !isChangingConfigurations) BookSession.close(bookId)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed (language, provider); reading style changes reload the page.
        val fresh = prefs.load()
        val styleChanged = fresh.copy(provider = settings.provider, geminiKey = settings.geminiKey, geminiModel = settings.geminiModel,
            deepseekKey = settings.deepseekKey, deepseekModel = settings.deepseekModel, language = settings.language,
            detailed = settings.detailed, cefr = settings.cefr) != settings
        settings = fresh
        if (styleChanged && session != null) {
            applyTheme()
            reload()
        }
    }

    // --- Web view and book files ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                // Book content may only load files from the book itself.
                if (url.host != Paths.HOST || url.scheme != "https") return blocked()
                return serve(Paths.decode(url.encodedPath.orEmpty().removePrefix("/")))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                runOnUiThread { if (request.url.host == Paths.HOST) openLink(url) else openExternal(url) }
                return true
            }
        }
        web.onSelectionAction = { action -> onSelectionAction(action) }
    }

    private fun serve(path: String): WebResourceResponse {
        if (path.startsWith("__reader/")) {
            val name = path.removePrefix("__reader/")
            if ("/" in name || name.startsWith(".")) return blocked()
            return runCatching { WebResourceResponse(Paths.mime(name), "utf-8", assets.open("reader/$name")) }.getOrElse { notFound() }
        }
        val b = book ?: return notFound()
        val resource = runCatching { b.load(path) }.getOrNull() ?: return notFound()
        if (!resource.isChapter) return WebResourceResponse(resource.mime, null, ByteArrayInputStream(resource.bytes))
        val index = b.chapterIndexForPath(path) ?: chapter
        val init = JSONObject(pendingInit).put("marks", marksJson(index))
        if (learningOn) init.put("learning", learningJson(String(resource.bytes, Charsets.UTF_8)))
        val nonce = ChapterRenderer.newNonce()
        val html = ChapterRenderer.prepare(String(resource.bytes, Charsets.UTF_8), ChapterRenderer.head(settings, layout, init.toString(), nonce), nonce)
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf("Content-Security-Policy" to ChapterRenderer.contentSecurityPolicy(nonce), "Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun marksJson(chapter: Int): JSONObject {
        val highlights = JSONArray()
        for (h in db.highlights(bookId, chapter)) {
            highlights.put(JSONObject().put("id", h.id).put("text", h.text).put("occurrence", h.occurrence).put("color", h.color).put("note", h.note.isNotBlank()))
        }
        val bookmarks = JSONArray()
        for (b in db.bookmarks(bookId, chapter)) bookmarks.put(JSONObject().put("id", b.id).put("text", b.anchorText).put("occurrence", b.anchorOccurrence))
        return JSONObject().put("highlights", highlights).put("bookmarks", bookmarks)
    }

    private fun refreshMarks() {
        val json = marksJson(chapter).toString()
        web.evaluateJavascript("window.reader && reader.setMarks(${JSONObject.quote(json)})", null)
    }

    // --- Navigation ---

    private fun ReadingPosition.isStarted() = chapter > 0 || fraction > 0f

    /**
     * Opens a chapter at a position, at its end, or at a [target] (element id or text anchor).
     * [resume] marks the position with "You stopped here".
     */
    private fun openChapter(index: Int, position: ReadingPosition? = null, atEnd: Boolean = false, target: JSONObject? = null, resume: Boolean = false) {
        val b = book ?: return
        chapter = index.coerceIn(0, b.chapterCount - 1)
        page = 0
        pageCount = 1
        pageAnchor = null
        currentBookmark = 0
        layout = computeLayout()
        val init = JSONObject().put("atEnd", atEnd)
        if (position != null && position.chapter == chapter) {
            init.put("position", JSONObject().put("fraction", position.fraction.toDouble()).put("text", position.anchorText).put("occurrence", position.anchorOccurrence))
        }
        if (target != null) init.put("target", target)
        if (resume) init.put("resume", JSONObject().put("label", "You stopped here"))
        pendingInit = init.toString()
        hideSelectionBar()
        hideWordCard()
        wordStats = null
        web.loadUrl(b.chapterUrl(chapter))
        updateStatus()
    }

    private fun currentPosition() = ReadingPosition(
        chapter, if (pageCount > 0) page.toFloat() / pageCount else 0f,
        pageAnchor?.optString("text").orEmpty(), pageAnchor?.optInt("occurrence") ?: 0,
    )

    private fun reload() = openChapter(chapter, currentPosition())

    /**
     * Page margins in CSS pixels: text clears the camera cutout and the status line, lines stay a comfortable
     * length on tablets, and wide screens get two columns.
     */
    private fun computeLayout(): ChapterRenderer.Layout {
        val density = resources.displayMetrics.density
        val insets = window.decorView.rootWindowInsets
        val top = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        val width = resources.configuration.screenWidthDp
        val columns = if (width >= 840) 2 else 1
        var padX = settings.margin.px
        if (columns == 1 && width > 720) padX = maxOf(padX, (width - 680) / 2)
        if (columns == 2) padX = maxOf(padX, 32)
        return ChapterRenderer.Layout(padTop = (top / density).toInt() + 14, padBottom = 40, padX = padX, columns = columns)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (session != null && computeLayout() != layout) reload()
        if (stylePanel.visibility == View.VISIBLE) placeStylePanel()
    }

    private fun openLink(href: String) {
        val b = book ?: return
        val target: Pair<Int, String?> = if (href.startsWith("#")) {
            val id = href.drop(1)
            (b.chapterOfAnchor(id) ?: return) to id
        } else {
            val uri = Uri.parse(href)
            if (uri.host != Paths.HOST) return openExternal(href)
            val path = Paths.decode(uri.encodedPath.orEmpty().removePrefix("/"))
            (b.chapterIndexForPath(path) ?: return) to uri.fragment
        }
        leavePlace(currentPosition())
        openChapter(target.first, target = target.second?.let { JSONObject().put("id", it) })
    }

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http" && uri.scheme != "mailto") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val b = book ?: return
        val fraction = if (pageCount > 0) page.toFloat() / pageCount else 0f
        val percent = (progress(fraction) * 100).toInt()
        val stats = wordStats?.takeIf { learningOn && it.total > 0 }
        status.text = if (stats != null) {
            // Learning mode: how much of this page is new.
            val familiar = (stats.total - stats.fresh) * 100 / stats.total
            "${page + 1} / $pageCount  ·  $percent%  ·  ${if (stats.fresh == 1) "1 new word" else "${stats.fresh} new words"}  ·  $familiar% familiar"
        } else {
            "${b.chapterTitle(chapter)}  ·  ${page + 1} / $pageCount  ·  $percent%"
        }
        status.isClickable = stats != null && stats.fresh > 0
        bookmarkButton.setImageResource(if (currentBookmark != 0L) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        bookmarkButton.contentDescription = if (currentBookmark != 0L) "Remove bookmark" else "Bookmark this page"
    }

    /** Progress through the whole book, weighted by chapter length once the text has been read. */
    private fun progress(fraction: Float): Float {
        val b = book ?: return 0f
        return session?.text?.progress(chapter, fraction) ?: ((chapter + fraction) / b.chapterCount)
    }

    private fun savePosition() {
        if (book == null) return
        val position = currentPosition()
        prefs.setPosition(bookId, position, progress(position.fraction))
    }

    /** Remembers [from] before jumping elsewhere and offers the way back. */
    private fun leavePlace(from: ReadingPosition) {
        jumps.addLast(from)
        awayPages = 0
        lastAwayPage = ""
        updateBackToPlace()
    }

    /** Goes back to where the reader stopped (the place before the first jump). */
    private fun returnToPlace() {
        val place = jumps.firstOrNull() ?: prefs.position(bookId)
        jumps.clear()
        updateBackToPlace()
        openChapter(place.chapter, place, resume = true)
    }

    /** Makes the current spot the reading place (the reader chose to stay, or read on from here). */
    private fun adoptCurrentPlace() {
        jumps.clear()
        savePosition()
        updateBackToPlace()
    }

    private fun updateBackToPlace() {
        val free = selectionBar.visibility != View.VISIBLE && !ai.isOpen && glossCard.visibility != View.VISIBLE
        backToPlace.visibility = if (jumps.isNotEmpty() && free && speakBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    // --- Selection bar ---

    private fun setUpSelectionBar() {
        val js = { code: String -> web.evaluateJavascript(code, null) }
        findViewById<View>(R.id.selWord).setOnClickListener { js("reader.expandSelection('word')") }
        findViewById<View>(R.id.selSentence).setOnClickListener { js("reader.expandSelection('sentence')") }
        findViewById<View>(R.id.selParagraph).setOnClickListener { js("reader.expandSelection('paragraph')") }
        findViewById<View>(R.id.selLeft).apply {
            setOnClickListener { js("reader.nudgeSelection('start', -1)") }
            setOnLongClickListener { js("reader.nudgeSelection('start', 1)"); true }
        }
        findViewById<View>(R.id.selRight).apply {
            setOnClickListener { js("reader.nudgeSelection('end', 1)") }
            setOnLongClickListener { js("reader.nudgeSelection('end', -1)"); true }
        }
        mapOf(
            R.id.selExplain to SelectionAction.EXPLAIN, R.id.selTranslate to SelectionAction.TRANSLATE,
            R.id.selHighlight to SelectionAction.HIGHLIGHT, R.id.selSave to SelectionAction.SAVE_WORD, R.id.selAsk to SelectionAction.ASK,
        ).forEach { (id, action) -> findViewById<View>(id).setOnClickListener { onSelectionAction(action) } }
        findViewById<View>(R.id.selChatgpt).setOnClickListener {
            withSelection { sel ->
                val request = AiRequest(AiAction.EXPLAIN, sel.text, sel.sentence, sel.context)
                Handoff.send(this, Handoff.Target.CHATGPT, Prompts.handoff(bookInfo(), prefs.load(), request, ""), settings.theme)
            }
        }
        findViewById<View>(R.id.selListen).setOnClickListener { withSelection { sel -> speech().say(sel.text, learningLanguage(), prefs.speechRate) } }
        findViewById<View>(R.id.selCopy).setOnClickListener {
            withSelection { sel ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Text", sel.text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        backToPlace.setOnClickListener { returnToPlace() }
        findViewById<View>(R.id.backToPlaceClose).setOnClickListener {
            adoptCurrentPlace()
            Toast.makeText(this, "This is now your reading place", Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows the selection bar on the opposite half of the screen from the selection ([top]/[bottom] in CSS px). */
    private fun showSelectionBar(top: Int, bottom: Int) {
        if (ai.isOpen) return
        hideWordCard()
        placeOpposite(selectionBar, top, bottom)
        selectionBar.visibility = View.VISIBLE
        updateBackToPlace()
    }

    /** Puts [bar] on the opposite half of the screen from the text between [top] and [bottom] (CSS px). */
    private fun placeOpposite(bar: View, top: Int, bottom: Int) {
        val density = resources.displayMetrics.density
        val heightDp = root.height / density
        val params = bar.layoutParams as FrameLayout.LayoutParams
        val textLow = (top + bottom) / 2f > heightDp * 0.5f
        if (textLow) {
            val inset = window.decorView.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            params.gravity = Gravity.TOP
            params.topMargin = inset + (8 * density).toInt()
            params.bottomMargin = 0
        } else {
            params.gravity = Gravity.BOTTOM
            params.topMargin = 0
            params.bottomMargin = (30 * density).toInt()
        }
        bar.layoutParams = params
    }

    private fun hideSelectionBar() {
        selectionBar.visibility = View.GONE
        updateBackToPlace()
    }

    // --- Learning mode: tap a word, word statuses, pronunciation, reading aloud ---

    private fun setUpLearning() {
        findViewById<View>(R.id.glossClose).setOnClickListener { hideWordCard() }
        findViewById<View>(R.id.glossSpeak).apply {
            setOnClickListener { tapped?.let { speech().say(it.word, learningLanguage(), prefs.speechRate) } }
            setOnLongClickListener { tapped?.let { speech().say(it.sentence, learningLanguage(), prefs.speechRate) }; true }
        }
        findViewById<View>(R.id.glossLearning).setOnClickListener { setTappedStatus(WordStatus.LEARNING) }
        findViewById<View>(R.id.glossKnown).setOnClickListener { setTappedStatus(WordStatus.KNOWN) }
        findViewById<View>(R.id.glossMore).setOnClickListener {
            val t = tapped ?: return@setOnClickListener
            openAi(AiAction.VOCABULARY, Selection(t.word, t.sentence, t.sentence, chapter, t.occurrence))
        }
        status.setOnClickListener { markPageKnown() }
        status.isClickable = false
        speakBar.setOnClickListener { stopReadAloud() }
    }

    /** The book's language for word statuses and voices: from its metadata, else guessed from its text. */
    private fun learningLanguage(chapterHtml: String? = null): String {
        learnLang?.let { return it }
        val b = book ?: return ""
        val lang = LanguageGuess.ofMetadata(b.language)
            .ifEmpty { session?.text?.let { LanguageGuess.of(null, it) }.orEmpty() }
            .ifEmpty { chapterHtml?.let { LanguageGuess.ofText(it.replace(Regex("<[^>]*>"), " ")) }.orEmpty() }
        if (lang.isNotEmpty()) learnLang = lang
        return lang
    }

    private fun languageName(): String? = book?.languageName
        ?: learningLanguage().takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }

    private fun wordStore(chapterHtml: String? = null) = WordStore.get(this, learningLanguage(chapterHtml))

    /** What reader.js needs for learning mode: whether it's on, and the status of every word that isn't new. */
    private fun learningJson(chapterHtml: String? = null): JSONObject =
        JSONObject().put("enabled", true).put("underlineNew", prefs.underlineNew).put("statuses", JSONObject(wordStore(chapterHtml).pageStatuses() as Map<*, *>))

    private fun pushLearning() {
        val json = if (learningOn) learningJson() else JSONObject().put("enabled", false)
        web.evaluateJavascript("window.reader && reader.setLearning(${JSONObject.quote(json.toString())})", null)
    }

    private fun setLearning(on: Boolean, underlineNew: Boolean = prefs.underlineNew) {
        val first = on && !learningOn
        prefs.learningMode = on
        prefs.underlineNew = underlineNew
        learningOn = on
        if (!on) {
            hideWordCard()
            wordStats = null
        }
        applyTheme()
        pushLearning()
        updateStatus()
        if (first) Toast.makeText(this, "Tap any word to see what it means", Toast.LENGTH_SHORT).show()
    }

    private fun showWordCard(t: TappedWord) {
        if (ai.isOpen || book == null) return
        tapped = t
        gloss = null
        val gen = ++glossGeneration
        findViewById<TextView>(R.id.glossHeadword).text = t.word
        for (id in listOf(R.id.glossMeta, R.id.glossTranslation, R.id.glossContext, R.id.glossNote)) findViewById<View>(id).visibility = View.GONE
        showGlossStatus("Looking up…", busy = true)
        updateGlossButtons()
        placeOpposite(glossCard, t.top, t.bottom)
        glossCard.visibility = View.VISIBLE
        updateBackToPlace()
        thread(isDaemon = true) {
            val store = wordStore()
            val cached = store.cachedGloss(t.form)
            val settings = prefs.load()
            val sameSentence = cached != null && (cached.sentence.isBlank() || cached.sentence == t.sentence)
            runOnUiThread {
                if (gen != glossGeneration) return@runOnUiThread
                when {
                    cached != null && (sameSentence || Ai.key(settings).isBlank()) -> showGloss(cached, otherSentence = false)
                    cached != null -> showGloss(cached, otherSentence = true)
                    Ai.key(settings).isBlank() -> showGlossStatus("Meanings come from your AI key. Tap More… to add one (DeepSeek, or a free Gemini key).", busy = false)
                    else -> lookUp(t, gen)
                }
            }
        }
    }

    /** Asks the AI what the tapped word means in its sentence, and keeps the answer for next time. */
    private fun lookUp(t: TappedWord, gen: Int) {
        showGlossStatus("Looking up…", busy = true)
        val settings = prefs.load()
        val provider = settings.provider
        val language = languageName()
        thread(isDaemon = true) {
            val reply = StringBuilder()
            val outcome = runCatching {
                val result = Ai.stream(
                    settings, "You are a concise dictionary for language learners. Reply with JSON only.",
                    listOf(Turn(true, Prompts.gloss(t.word, t.sentence, language, settings))), onConnect = {}, isCancelled = { gen != glossGeneration },
                ) { reply.append(it) }
                if (result.inputTokens + result.outputTokens > 0) db.addUsage(provider, Ai.model(settings), result.inputTokens, result.outputTokens)
                Gloss.parse(t.word, reply.toString())?.copy(sentence = t.sentence)?.also { wordStore().saveGloss(t.form, it) }
            }
            runOnUiThread {
                if (gen != glossGeneration) return@runOnUiThread
                outcome.onSuccess { g ->
                    if (g != null) showGloss(g, otherSentence = false)
                    else showGlossStatus("Couldn't read the answer. Tap More… for a full explanation.", busy = false)
                }.onFailure { e ->
                    showGlossStatus(
                        if (Ai.isKeyProblem(provider, e)) "${Ai.name(provider)} didn't accept your key. Tap More… to enter another one."
                        else Ai.describe(provider, e),
                        busy = false,
                    )
                }
            }
        }
    }

    private fun showGlossStatus(text: String, busy: Boolean, onClick: (() -> Unit)? = null) {
        findViewById<View>(R.id.glossLoading).visibility = View.VISIBLE
        findViewById<View>(R.id.glossProgress).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.glossStatus).apply {
            this.text = text
            setOnClickListener(onClick?.let { { _: View -> it() } })
            isClickable = onClick != null
            setTextColor(if (onClick != null) settings.theme.accent else settings.theme.muted)
        }
    }

    /** Fills the word card. [otherSentence]: the saved look-up was made in a different sentence. */
    private fun showGloss(g: Gloss, otherSentence: Boolean) {
        val t = tapped ?: return
        gloss = g
        val t2 = settings.theme
        val head = SpannableStringBuilder(g.headword)
        if (g.article.isNotBlank() && g.headword.startsWith(g.article)) {
            head.setSpan(ForegroundColorSpan(articleColor(g.article, t2.isDark)), 0, g.article.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!g.headword.equals(t.word, ignoreCase = true)) {
            val from = head.length
            head.append("   ").append(t.word)
            head.setSpan(ForegroundColorSpan(t2.muted), from, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            head.setSpan(StyleSpan(Typeface.NORMAL), from, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            head.setSpan(android.text.style.RelativeSizeSpan(0.7f), from, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        findViewById<TextView>(R.id.glossHeadword).text = head
        fun set(id: Int, text: String) = findViewById<TextView>(id).apply {
            this.text = text
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        set(R.id.glossMeta, listOf(g.pos, g.forms, g.plural.takeIf { it.isNotBlank() }?.let { "pl. $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "))
        set(R.id.glossTranslation, g.translation)
        set(R.id.glossContext, if (otherSentence || g.inContext.isBlank() || g.inContext.equals(g.translation, ignoreCase = true)) "" else "Here: ${g.inContext}")
        set(R.id.glossNote, g.note)
        if (otherSentence) {
            showGlossStatus("Saved from another sentence. Check this one ↻", busy = false) { lookUp(t, glossGeneration) }
        } else {
            findViewById<View>(R.id.glossLoading).visibility = View.GONE
        }
        updateGlossButtons()
    }

    /** German genders in the usual learner colours: der blue, die red, das green. */
    private fun articleColor(article: String, dark: Boolean): Int = when (article) {
        "der" -> if (dark) 0xFF7AB0FF.toInt() else 0xFF2563C9.toInt()
        "die" -> if (dark) 0xFFFF8A80.toInt() else 0xFFC62828.toInt()
        else -> if (dark) 0xFF7BD88F.toInt() else 0xFF2E7D32.toInt()
    }

    private fun updateGlossButtons() {
        val t = tapped ?: return
        val current = wordStore().status(t.form)
        val theme = settings.theme
        val density = resources.displayMetrics.density
        for ((id, value) in listOf(R.id.glossLearning to WordStatus.LEARNING, R.id.glossKnown to WordStatus.KNOWN, R.id.glossMore to -1)) {
            val on = value == current
            findViewById<TextView>(id).apply {
                setTextColor(if (on) 0xFFFFFFFF.toInt() else theme.accent)
                isSelected = on
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(if (on) theme.accent else (theme.accent and 0x00FFFFFF) or 0x1A000000)
                }
            }
        }
        findViewById<TextView>(R.id.glossLearning).text = if (current == WordStatus.LEARNING) "★ Learning" else "☆ Learning"
    }

    /** Marks the tapped word as being learnt (and saves it to the vocabulary) or known; tapping again makes it new. */
    private fun setTappedStatus(status: Int) {
        val t = tapped ?: return
        val store = wordStore()
        val g = gloss
        val next = if (store.status(t.form) == status) WordStatus.NEW else status
        store.setStatus(t.form, next, g?.lemma)
        if (next == WordStatus.LEARNING) {
            val explanation = g?.let {
                listOf(listOf(it.headword, it.pos).filter { s -> s.isNotBlank() }.joinToString(", "), it.forms, it.plural.takeIf { p -> p.isNotBlank() }?.let { p -> "pl. $p" }.orEmpty(), it.inContext)
                    .filter { s -> s.isNotBlank() }.joinToString(" — ")
            }.orEmpty()
            Vocabulary.save(this, WordSource(t.word, t.sentence, bookId, bookInfo().title, chapter, book?.chapterTitle(chapter).orEmpty(), t.occurrence),
                explanation = explanation, translation = g?.translation.orEmpty())
        }
        pushLearning()
        if (next == WordStatus.KNOWN) hideWordCard() else updateGlossButtons()
    }

    private fun hideWordCard() {
        if (!::glossCard.isInitialized) return
        glossGeneration++
        if (glossCard.visibility == View.VISIBLE) {
            glossCard.visibility = View.GONE
            web.evaluateJavascript("window.reader && reader.clearTap()", null)
        }
        tapped = null
        gloss = null
        updateBackToPlace()
    }

    /** Lists the new words on the page so the ones the reader already knows can be marked in one go. */
    private fun markPageKnown() {
        if (!learningOn) return
        web.evaluateJavascript("reader.newWordsOnPage()") { result ->
            val words = runCatching { JSONArray(JSONTokener(result).nextValue() as String) }.getOrNull()
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (words.isEmpty()) return@evaluateJavascript
            val checked = BooleanArray(words.size) { true }
            AlertDialog.Builder(this)
                .setTitle("Which of these do you know?")
                .setMultiChoiceItems(words.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Mark as known") { _, _ ->
                    wordStore().setStatuses(words.filterIndexed { i, _ -> checked[i] }, WordStatus.KNOWN)
                    pushLearning()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun speech(): Speech = speechEngine ?: Speech(this).also { sp ->
        speechEngine = sp
        sp.onSentence = { i -> web.evaluateJavascript("window.reader && reader.speaking($i)", null) }
        sp.onReadingEnded = { finished ->
            web.evaluateJavascript("window.reader && reader.stopSpeaking()", null)
            val b = book
            if (finished && b != null && chapter < b.chapterCount - 1) {
                readAloudOnLoad = true
                openChapter(chapter + 1)
            } else {
                showSpeakBar(false)
            }
        }
        sp.onMissingVoice = { locale ->
            showSpeakBar(false)
            AlertDialog.Builder(this)
                .setTitle("No ${locale.getDisplayLanguage(Locale.ENGLISH)} voice")
                .setMessage("Your phone has no text-to-speech voice for ${locale.getDisplayLanguage(Locale.ENGLISH)} yet. Install one (Settings → Text-to-speech, or Speech Services by Google) and try again.")
                .setPositiveButton("Install voices") { _, _ ->
                    runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                        .onFailure { Toast.makeText(this, "Open Settings → Text-to-speech to add voices", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleReadAloud() {
        if (speechEngine?.isReading == true || speakBar.visibility == View.VISIBLE) stopReadAloud() else startReadAloud()
    }

    private fun stopReadAloud() {
        readAloudOnLoad = false
        speechEngine?.stop()
        web.evaluateJavascript("window.reader && reader.stopSpeaking()", null)
        showSpeakBar(false)
    }

    /** Reads from the top of the current page to the end of the chapter, then carries on in the next one. */
    private fun startReadAloud() {
        setBarsVisible(false)
        hideWordCard()
        web.evaluateJavascript("reader.sentencesFromPage()") { result ->
            val sentences = runCatching { JSONArray(JSONTokener(result).nextValue() as String) }.getOrNull()
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val b = book ?: return@evaluateJavascript
            if (sentences.isEmpty()) {
                if (chapter < b.chapterCount - 1) {
                    readAloudOnLoad = true
                    openChapter(chapter + 1)
                } else {
                    showSpeakBar(false)
                }
                return@evaluateJavascript
            }
            showSpeakBar(true)
            speech().read(sentences, learningLanguage(), prefs.speechRate)
        }
    }

    private fun showSpeakBar(visible: Boolean) {
        speakBar.visibility = if (visible) View.VISIBLE else View.GONE
        updateBackToPlace()
    }

    private fun changeSpeechRate(delta: Float) {
        prefs.speechRate = ((prefs.speechRate + delta) * 10).let { Math.round(it) / 10f }.coerceIn(0.5f, 2f)
        applyTheme()
        // Carry on reading at the new speed.
        if (speechEngine?.isReading == true) startReadAloud()
    }

    // --- AiHost ---

    override fun session() = session

    override fun location(): Pair<Int, Float> = chapter to if (pageCount > 0) (page + 1f) / pageCount else 1f

    override fun bookInfo(): BookInfo {
        val b = book
        return BookInfo(
            b?.title ?: bookTitle.text.toString(), b?.author.orEmpty(), b?.languageName,
            b?.let { "chapter ${chapter + 1} of ${it.chapterCount} (“${it.chapterTitle(chapter)}”)" },
        )
    }

    // --- Selection, highlights, bookmarks ---

    private fun withSelection(block: (Selection) -> Unit) {
        web.evaluateJavascript("reader.selectionInfo()") { result ->
            val info = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            val text = info?.optString("text").orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Select some text first", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            web.evaluateJavascript("reader.clearSelection()", null)
            val anchor = info?.optJSONObject("anchor")
            block(Selection(text, info?.optString("context").orEmpty(), info?.optString("sentence").orEmpty(), chapter, anchor?.optInt("occurrence") ?: 0))
        }
    }

    private fun onSelectionAction(action: SelectionAction) = withSelection { sel ->
        when (action) {
            SelectionAction.EXPLAIN -> openAi(AiAction.EXPLAIN, sel)
            SelectionAction.TRANSLATE -> openAi(AiAction.TRANSLATE, sel)
            SelectionAction.ASK -> openAi(AiAction.ASK, sel)
            SelectionAction.HIGHLIGHT -> {
                val id = db.addHighlight(Highlight(bookId = bookId, chapter = chapter, text = sel.text, occurrence = sel.occurrence))
                refreshMarks()
                Toast.makeText(this, "Highlighted. Tap it to add a note.", Toast.LENGTH_SHORT).show()
                if (id < 0) Toast.makeText(this, "Couldn't save the highlight", Toast.LENGTH_SHORT).show()
            }
            SelectionAction.SAVE_WORD -> Vocabulary.save(
                this, WordSource(sel.text, sel.sentence, bookId, bookInfo().title, chapter, book?.chapterTitle(chapter).orEmpty(), sel.occurrence),
            )
        }
    }

    private fun openAi(action: AiAction, sel: Selection) {
        setBarsVisible(false)
        hideSelectionBar()
        hideWordCard()
        ai.openForSelection(action, sel)
        updateBackToPlace()
    }

    private fun askAboutBook() {
        web.evaluateJavascript("reader.pageText()") { result ->
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
            setBarsVisible(false)
            ai.openForChat(text)
        }
    }

    private fun toggleBookmark() {
        if (book == null) return
        if (currentBookmark != 0L) {
            db.deleteBookmark(currentBookmark)
            currentBookmark = 0
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val position = currentPosition()
            val label = position.anchorText.ifBlank { book?.chapterTitle(chapter).orEmpty() }
            db.addBookmark(Bookmark(bookId = bookId, chapter = chapter, fraction = position.fraction, anchorText = position.anchorText,
                anchorOccurrence = position.anchorOccurrence, label = label))
            Toast.makeText(this, "Page bookmarked", Toast.LENGTH_SHORT).show()
        }
        refreshMarks()
        updateStatus()
    }

    private fun editHighlight(id: Long) {
        val h = db.highlight(id) ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        var color = h.color
        val note = EditText(this).apply {
            setText(h.note)
            hint = "Add a note"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val colorValues = mapOf("yellow" to 0xFFF2C94C.toInt(), "green" to 0xFF6FCF97.toInt(), "blue" to 0xFF56A0F5.toInt(), "pink" to 0xFFF27AA8.toInt())
        fun paint() {
            for (i in 0 until swatches.childCount) {
                val v = swatches.getChildAt(i)
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorValues.getValue(v.tag as String))
                    if (v.tag == color) setStroke(pad / 6, settings.theme.fg)
                }
            }
        }
        for ((name, _) in colorValues) {
            swatches.addView(View(this).apply {
                tag = name
                contentDescription = "$name highlight"
                setOnClickListener { color = name; paint() }
            }, LinearLayout.LayoutParams(pad * 2, pad * 2).apply { marginEnd = pad / 2 })
        }
        paint()
        val quote = TextView(this).apply {
            text = "“${h.text.take(300)}”"
            setPadding(0, 0, 0, pad / 2)
            maxLines = 4
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(quote)
            addView(swatches)
            addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        }
        AlertDialog.Builder(this)
            .setTitle("Highlight")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                db.updateHighlight(id, color, note.text.toString().trim())
                refreshMarks()
            }
            .setNegativeButton("Delete") { _, _ ->
                db.deleteHighlight(id)
                refreshMarks()
            }
            .setNeutralButton("Explain") { _, _ -> openAi(AiAction.EXPLAIN, Selection(h.text, "", h.text, h.chapter, h.occurrence)) }
            .show()
    }

    // --- Toolbar, style panel, theme ---

    private fun setUpBars() {
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.toc).setOnClickListener { openNavigator() }
        findViewById<View>(R.id.style).setOnClickListener {
            if (stylePanel.visibility == View.VISIBLE) stylePanel.visibility = View.GONE else {
                placeStylePanel()
                stylePanel.visibility = View.VISIBLE
            }
        }
        findViewById<ImageButton>(R.id.askAi).setOnClickListener { askAboutBook() }
        findViewById<ImageButton>(R.id.readAloud).setOnClickListener { toggleReadAloud() }
        bookmarkButton.setOnClickListener { toggleBookmark() }

        val s = { update: (AppSettings) -> AppSettings -> changeStyle(update(settings)) }
        findViewById<View>(R.id.fontSmaller).setOnClickListener { s { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }
        findViewById<View>(R.id.fontBigger).setOnClickListener { s { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(34)) } }
        findViewById<View>(R.id.fontSerif).setOnClickListener { s { it.copy(font = ReaderFont.SERIF) } }
        findViewById<View>(R.id.fontSans).setOnClickListener { s { it.copy(font = ReaderFont.SANS) } }
        findViewById<View>(R.id.fontPublisher).setOnClickListener { s { it.copy(font = ReaderFont.PUBLISHER) } }
        findViewById<View>(R.id.spacingLess).setOnClickListener { s { it.copy(lineHeight = ((it.lineHeight * 20 - 1) / 20).coerceAtLeast(1.2f)) } }
        findViewById<View>(R.id.spacingMore).setOnClickListener { s { it.copy(lineHeight = ((it.lineHeight * 20 + 1) / 20).coerceAtMost(2.2f)) } }
        findViewById<View>(R.id.marginNarrow).setOnClickListener { s { it.copy(margin = ReaderMargin.NARROW) } }
        findViewById<View>(R.id.marginNormal).setOnClickListener { s { it.copy(margin = ReaderMargin.NORMAL) } }
        findViewById<View>(R.id.marginWide).setOnClickListener { s { it.copy(margin = ReaderMargin.WIDE) } }
        findViewById<View>(R.id.alignJustify).setOnClickListener { s { it.copy(justify = true) } }
        findViewById<View>(R.id.alignLeft).setOnClickListener { s { it.copy(justify = false) } }
        findViewById<View>(R.id.themeLight).setOnClickListener { s { it.copy(theme = ReaderTheme.LIGHT) } }
        findViewById<View>(R.id.themeSepia).setOnClickListener { s { it.copy(theme = ReaderTheme.SEPIA) } }
        findViewById<View>(R.id.themeDark).setOnClickListener { s { it.copy(theme = ReaderTheme.DARK) } }
        findViewById<View>(R.id.learnOff).setOnClickListener { setLearning(false) }
        findViewById<View>(R.id.learnOn).setOnClickListener { setLearning(true) }
        findViewById<View>(R.id.learnUnderline).setOnClickListener { setLearning(true, underlineNew = !(learningOn && prefs.underlineNew)) }
        findViewById<View>(R.id.rateSlower).setOnClickListener { changeSpeechRate(-0.1f) }
        findViewById<View>(R.id.rateFaster).setOnClickListener { changeSpeechRate(0.1f) }
    }

    private fun placeStylePanel() {
        topBar.post {
            val params = stylePanel.layoutParams as FrameLayout.LayoutParams
            params.topMargin = topBar.bottom + (8 * resources.displayMetrics.density).toInt()
            stylePanel.layoutParams = params
        }
    }

    private fun changeStyle(updated: AppSettings) {
        if (updated == settings) return
        // Keep the AI settings from disk; only the reading style changes here.
        settings = prefs.load().copy(
            fontSize = updated.fontSize, font = updated.font, lineHeight = updated.lineHeight,
            margin = updated.margin, justify = updated.justify, theme = updated.theme,
        )
        prefs.save(settings)
        applyTheme()
        reload()
    }

    private fun applyTheme() {
        val t = settings.theme
        val density = resources.displayMetrics.density
        root.setBackgroundColor(t.bg)
        web.setBackgroundColor(t.bg)
        topBar.setBackgroundColor(t.bg)
        status.setTextColor(t.muted)
        bookTitle.setTextColor(t.fg)
        loading.indeterminateTintList = ColorStateList.valueOf(t.accent)
        for (id in listOf(R.id.back, R.id.toc, R.id.askAi, R.id.readAloud, R.id.bookmark)) findViewById<ImageButton>(id).imageTintList = ColorStateList.valueOf(t.fg)
        findViewById<TextView>(R.id.style).setTextColor(t.fg)
        stylePanel.background = GradientDrawable().apply {
            setColor(t.bg)
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x50000000)
        }
        findViewById<TextView>(R.id.fontSizeLabel).text = "${settings.fontSize}"
        findViewById<TextView>(R.id.spacingLabel).text = "%.2f".format(settings.lineHeight).trimEnd('0').trimEnd('.')
        findViewById<TextView>(R.id.rateLabel).text = "%.1f×".format(Locale.US, prefs.speechRate)
        val selected = mapOf(
            R.id.fontSerif to (settings.font == ReaderFont.SERIF), R.id.fontSans to (settings.font == ReaderFont.SANS),
            R.id.fontPublisher to (settings.font == ReaderFont.PUBLISHER),
            R.id.marginNarrow to (settings.margin == ReaderMargin.NARROW), R.id.marginNormal to (settings.margin == ReaderMargin.NORMAL),
            R.id.marginWide to (settings.margin == ReaderMargin.WIDE),
            R.id.alignJustify to settings.justify, R.id.alignLeft to !settings.justify,
            R.id.fontSmaller to false, R.id.fontBigger to false, R.id.spacingLess to false, R.id.spacingMore to false,
            R.id.fontSizeLabel to false, R.id.spacingLabel to false,
            R.id.learnOff to !learningOn, R.id.learnOn to learningOn, R.id.learnUnderline to (learningOn && prefs.underlineNew),
            R.id.rateSlower to false, R.id.rateFaster to false, R.id.rateLabel to false,
        )
        val labels = setOf(R.id.fontSizeLabel, R.id.spacingLabel, R.id.rateLabel)
        for ((id, on) in selected) findViewById<TextView>(id).apply {
            setTextColor(if (on) t.accent else t.fg)
            if (id !in labels) background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (on) (t.accent and 0x00FFFFFF) or 0x22000000 else 0)
            }
            isSelected = on
        }
        for (i in 0 until stylePanel.childCount) {
            ((stylePanel.getChildAt(i) as LinearLayout).getChildAt(0) as TextView).setTextColor(t.muted)
        }
        for ((id, theme) in listOf(R.id.themeLight to ReaderTheme.LIGHT, R.id.themeSepia to ReaderTheme.SEPIA, R.id.themeDark to ReaderTheme.DARK)) {
            findViewById<TextView>(id).apply {
                setTextColor(theme.fg)
                isSelected = theme == t
                background = GradientDrawable().apply {
                    setColor(theme.bg)
                    cornerRadius = 10 * density
                    setStroke((if (theme == t) 2 * density else 1 * density).toInt(), if (theme == t) t.accent else (t.muted and 0x00FFFFFF) or 0x60000000)
                }
            }
        }
        val light = if (t.isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        selectionBar.background = GradientDrawable().apply {
            setColor(if (t == ReaderTheme.LIGHT) 0xFFFAF8F5.toInt() else t.bg)
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x50000000)
        }
        for (id in listOf(R.id.selLeft, R.id.selWord, R.id.selSentence, R.id.selParagraph, R.id.selRight)) findViewById<TextView>(id).setTextColor(t.fg)
        for (id in listOf(R.id.selExplain, R.id.selTranslate, R.id.selHighlight, R.id.selSave, R.id.selAsk, R.id.selChatgpt, R.id.selListen, R.id.selCopy)) findViewById<TextView>(id).setTextColor(t.accent)
        glossCard.background = GradientDrawable().apply {
            setColor(if (t == ReaderTheme.LIGHT) 0xFFFAF8F5.toInt() else t.bg)
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x50000000)
        }
        findViewById<TextView>(R.id.glossHeadword).setTextColor(t.fg)
        findViewById<TextView>(R.id.glossTranslation).setTextColor(t.fg)
        for (id in listOf(R.id.glossMeta, R.id.glossNote)) findViewById<TextView>(id).setTextColor(t.muted)
        findViewById<TextView>(R.id.glossContext).setTextColor(t.fg)
        findViewById<TextView>(R.id.glossStatus).setTextColor(t.muted)
        for (id in listOf(R.id.glossSpeak, R.id.glossClose)) findViewById<ImageButton>(id).imageTintList = ColorStateList.valueOf(t.muted)
        findViewById<ProgressBar>(R.id.glossProgress).indeterminateTintList = ColorStateList.valueOf(t.accent)
        updateGlossButtons()
        speakBar.background = GradientDrawable().apply {
            setColor(t.accent)
            cornerRadius = 20 * density
        }
        findViewById<View>(R.id.selDivider).setBackgroundColor((t.muted and 0x00FFFFFF) or 0x40000000)
        backToPlace.background = GradientDrawable().apply {
            setColor(t.accent)
            cornerRadius = 20 * density
        }
        ai.applyTheme(t)
    }

    private fun setBarsVisible(visible: Boolean) {
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) stylePanel.visibility = View.GONE
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) show(WindowInsets.Type.systemBars()) else hide(WindowInsets.Type.systemBars())
        }
    }

    private fun openNavigator() {
        if (book == null) return
        setBarsVisible(false)
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, NavigatorActivity::class.java).putExtra(NavigatorActivity.EXTRA_BOOK_ID, bookId).putExtra(NavigatorActivity.EXTRA_CHAPTER, chapter),
            NAVIGATE,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != NAVIGATE || resultCode != RESULT_OK || data == null) return
        if (data.getBooleanExtra(EXTRA_RESUME, false)) {
            if (jumps.isNotEmpty()) returnToPlace() else prefs.position(bookId).let { openChapter(it.chapter, it, resume = true) }
            return
        }
        val target = data.getStringExtra(EXTRA_TARGET)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fraction = data.getFloatExtra(EXTRA_FRACTION, 0f)
        leavePlace(currentPosition())
        openChapter(data.getIntExtra(EXTRA_CHAPTER, chapter), ReadingPosition(data.getIntExtra(EXTRA_CHAPTER, chapter), fraction), target = target)
    }

    // --- Keys and back ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ai.isOpen || currentFocus is EditText) return super.dispatchKeyEvent(event)
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> false
            KeyEvent.KEYCODE_SPACE -> !event.isShiftPressed
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) web.evaluateJavascript(if (forward) "reader.next()" else "reader.prev()", null)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ai.isOpen -> ai.close()
            stylePanel.visibility == View.VISIBLE -> stylePanel.visibility = View.GONE
            glossCard.visibility == View.VISIBLE -> hideWordCard()
            selectionBar.visibility == View.VISIBLE -> web.evaluateJavascript("reader.clearSelection()", null)
            jumps.isNotEmpty() -> jumps.removeLast().let {
                updateBackToPlace()
                openChapter(it.chapter, it, resume = jumps.isEmpty())
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Called from reader.js, on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPage(p: Int, count: Int, anchor: String, bookmark: Long) = runOnUiThread {
            if (p != page) hideWordCard()
            page = p
            pageCount = count
            pageAnchor = anchor.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            currentBookmark = bookmark
            if (jumps.isEmpty()) {
                savePosition()
            } else {
                val key = "$chapter:$p"
                if (key != lastAwayPage) {
                    lastAwayPage = key
                    if (++awayPages > AWAY_PAGES_TO_STAY) adoptCurrentPlace()
                }
            }
            updateStatus()
            if (readAloudOnLoad) {
                readAloudOnLoad = false
                startReadAloud()
            }
        }

        @JavascriptInterface
        fun onTap() = runOnUiThread {
            when {
                ai.isOpen -> ai.close()
                glossCard.visibility == View.VISIBLE -> hideWordCard()
                stylePanel.visibility == View.VISIBLE -> stylePanel.visibility = View.GONE
                else -> setBarsVisible(topBar.visibility != View.VISIBLE)
            }
        }

        @JavascriptInterface
        fun onHighlightTap(id: Long) = runOnUiThread { editHighlight(id) }

        @JavascriptInterface
        fun onSelectionChanged(active: Boolean, top: Int, bottom: Int) = runOnUiThread {
            if (active) showSelectionBar(top, bottom) else hideSelectionBar()
        }

        @JavascriptInterface
        fun onWordTap(json: String) = runOnUiThread {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@runOnUiThread
            val word = o.optString("word").trim()
            if (word.isEmpty()) return@runOnUiThread
            if (ai.isOpen || stylePanel.visibility == View.VISIBLE) {
                // The tap closes what's open, as elsewhere on the page.
                if (ai.isOpen) ai.close() else stylePanel.visibility = View.GONE
                web.evaluateJavascript("window.reader && reader.clearTap()", null)
                return@runOnUiThread
            }
            setBarsVisible(false)
            showWordCard(TappedWord(word, o.optString("form").ifBlank { WordStore.normalize(word) }, o.optString("sentence"),
                o.optInt("top"), o.optInt("bottom"), o.optInt("occurrence")))
        }

        @JavascriptInterface
        fun onWordStats(total: Int, fresh: Int, learning: Int) = runOnUiThread {
            wordStats = WordStats(total, fresh, learning)
            updateStatus()
        }

        @JavascriptInterface
        fun nextChapter() = runOnUiThread {
            val b = book ?: return@runOnUiThread
            if (chapter < b.chapterCount - 1) openChapter(chapter + 1)
            else Toast.makeText(this@ReaderActivity, "That's the end of the book", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun prevChapter() = runOnUiThread { if (chapter > 0) openChapter(chapter - 1, atEnd = true) }

        @JavascriptInterface
        fun openLink(href: String) = runOnUiThread { this@ReaderActivity.openLink(href) }

        @JavascriptInterface
        fun openExternal(url: String) = runOnUiThread { this@ReaderActivity.openExternal(url) }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_TARGET = "target"
        const val EXTRA_FRACTION = "fraction"
        const val EXTRA_RESUME = "resume"
        /** Turning this many pages after a jump makes the new spot the reading place. */
        private const val AWAY_PAGES_TO_STAY = 3
        private const val NAVIGATE = 2
    }
}
