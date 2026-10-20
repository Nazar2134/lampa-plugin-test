package com.aireader.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class ReaderActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var settings: AppSettings
    private lateinit var bookId: String
    @Volatile private var book: BookContent? = null
    @Volatile private var pendingInit = "{}"
    @Volatile private var layout = ChapterRenderer.Layout(48, 40, 22)

    private var chapter = 0
    private var page = 0
    private var pageCount = 1
    /** Positions to return to after following a link, e.g. a footnote. */
    private val jumps = ArrayDeque<Pair<Int, Float>>()

    private lateinit var root: View
    private lateinit var web: ReaderWebView
    private lateinit var topBar: LinearLayout
    private lateinit var bookTitle: TextView
    private lateinit var status: TextView
    private lateinit var stylePanel: LinearLayout
    private lateinit var fontSizeLabel: TextView
    private lateinit var loading: ProgressBar
    private lateinit var ai: AiPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        prefs = Prefs(this)
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
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
        loading = findViewById(R.id.loading)
        ai = AiPanel(this) { bookInfo() }
        bookTitle.text = entry.title

        topBar.setOnApplyWindowInsetsListener { v, insets ->
            val top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        setUpWebView()
        setUpBars()
        applyTheme()
        setBarsVisible(false)

        thread {
            val opened = runCatching { BookOpener.open(library.file(entry), entry.title) }
            runOnUiThread {
                loading.visibility = View.GONE
                opened.onSuccess { b ->
                    if (isDestroyed) {
                        b.close()
                        return@onSuccess
                    }
                    book = b
                    prefs.markOpened(bookId)
                    val (c, fraction) = prefs.position(bookId)
                    openChapter(c.coerceIn(0, b.chapterCount - 1), fraction)
                }.onFailure {
                    Toast.makeText(this, "Couldn't open this book: ${it.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        book?.close()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != Paths.HOST) return null
                return serve(Paths.decode(url.encodedPath.orEmpty().removePrefix("/")))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                runOnUiThread { if (request.url.host == Paths.HOST) openLink(url) else openExternal(url) }
                return true
            }
        }
        web.onAiAction = { action -> askAboutSelection(action) }
    }

    private fun serve(path: String): WebResourceResponse {
        if (path.startsWith("__reader/")) {
            val name = path.removePrefix("__reader/")
            return runCatching {
                WebResourceResponse(Paths.mime(name), "utf-8", assets.open("reader/$name"))
            }.getOrElse { notFound() }
        }
        val resource = book?.load(path) ?: return notFound()
        if (!resource.isChapter) return WebResourceResponse(resource.mime, null, ByteArrayInputStream(resource.bytes))
        val head = ChapterRenderer.head(settings, layout, pendingInit)
        val html = ChapterRenderer.prepare(String(resource.bytes, Charsets.UTF_8), head)
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun openChapter(index: Int, fraction: Float = 0f, atEnd: Boolean = false, anchor: String? = null) {
        val b = book ?: return
        chapter = index
        page = 0
        pageCount = 1
        layout = computeLayout()
        pendingInit = JSONObject()
            .put("fraction", fraction.toDouble())
            .put("atEnd", atEnd)
            .put("anchor", anchor ?: JSONObject.NULL)
            .toString()
        web.loadUrl(b.chapterUrl(index))
        updateStatus()
    }

    /** Page margins in CSS pixels, keeping text clear of the camera cutout and the status line. */
    private fun computeLayout(): ChapterRenderer.Layout {
        val density = resources.displayMetrics.density
        val insets = window.decorView.rootWindowInsets
        val top = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        return ChapterRenderer.Layout(padTop = (top / density).toInt() + 14, padBottom = 40, padX = 22)
    }

    private fun currentFraction() = if (pageCount > 0) page.toFloat() / pageCount else 0f

    private fun reload() = openChapter(chapter, currentFraction())

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
        jumps.addLast(chapter to currentFraction())
        openChapter(target.first, anchor = target.second)
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val b = book ?: return
        val percent = ((chapter + currentFraction()) / b.chapterCount * 100).toInt()
        status.text = "${b.chapterTitle(chapter)}  ·  ${page + 1} / $pageCount  ·  $percent%"
    }

    private fun savePosition() {
        val b = book ?: return
        prefs.setPosition(bookId, chapter, currentFraction(), (chapter + currentFraction()) / b.chapterCount)
    }

    private fun bookInfo(): BookInfo {
        val b = book
        return BookInfo(b?.title ?: bookTitle.text.toString(), b?.author.orEmpty(), b?.languageName)
    }

    private fun askAboutSelection(action: AiAction) {
        web.evaluateJavascript("reader.selectionInfo()") { result ->
            val info = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            val text = info?.optString("text").orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Select some text first", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            web.evaluateJavascript("reader.clearSelection()", null)
            setBarsVisible(false)
            ai.openForSelection(action, text, info?.optString("context").orEmpty())
        }
    }

    private fun askAboutPage() {
        web.evaluateJavascript("reader.pageText()") { result ->
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
            setBarsVisible(false)
            ai.openForChat(text)
        }
    }

    // --- Toolbar, style panel, theme ---

    private fun setUpBars() {
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.toc).setOnClickListener { showToc() }
        findViewById<View>(R.id.style).setOnClickListener {
            stylePanel.visibility = if (stylePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<ImageButton>(R.id.askAi).setOnClickListener { askAboutPage() }

        findViewById<View>(R.id.fontSmaller).setOnClickListener { changeStyle(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(12))) }
        findViewById<View>(R.id.fontBigger).setOnClickListener { changeStyle(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(34))) }
        findViewById<View>(R.id.fontSerif).setOnClickListener { changeStyle(settings.copy(serif = true)) }
        findViewById<View>(R.id.fontSans).setOnClickListener { changeStyle(settings.copy(serif = false)) }
        findViewById<View>(R.id.themeLight).setOnClickListener { changeStyle(settings.copy(theme = ReaderTheme.LIGHT)) }
        findViewById<View>(R.id.themeSepia).setOnClickListener { changeStyle(settings.copy(theme = ReaderTheme.SEPIA)) }
        findViewById<View>(R.id.themeDark).setOnClickListener { changeStyle(settings.copy(theme = ReaderTheme.DARK)) }
    }

    private fun changeStyle(updated: AppSettings) {
        if (updated == settings) return
        // Keep the AI settings from disk; only the reading style changes here.
        settings = prefs.load().copy(fontSize = updated.fontSize, serif = updated.serif, theme = updated.theme)
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
        for (id in listOf(R.id.back, R.id.toc, R.id.askAi)) findViewById<ImageButton>(id).imageTintList = ColorStateList.valueOf(t.fg)
        findViewById<TextView>(R.id.style).setTextColor(t.fg)
        stylePanel.background = GradientDrawable().apply {
            setColor(t.bg)
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x50000000)
        }
        fontSizeLabel.text = "${settings.fontSize}"
        fontSizeLabel.setTextColor(t.fg)
        for ((id, selected) in listOf(R.id.fontSerif to settings.serif, R.id.fontSans to !settings.serif, R.id.fontSmaller to false, R.id.fontBigger to false)) {
            findViewById<TextView>(id).setTextColor(if (selected) t.accent else t.fg)
        }
        for ((id, theme) in listOf(R.id.themeLight to ReaderTheme.LIGHT, R.id.themeSepia to ReaderTheme.SEPIA, R.id.themeDark to ReaderTheme.DARK)) {
            findViewById<TextView>(id).apply {
                setTextColor(theme.fg)
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

    private fun showToc() {
        val b = book ?: return
        val entries = b.toc.ifEmpty { (0 until b.chapterCount).map { TocEntry(b.chapterTitle(it), it, null) } }
        if (entries.isEmpty()) return
        val labels = entries.map { "    ".repeat(it.depth.coerceAtMost(3)) + it.title }.toTypedArray()
        val current = entries.indexOfLast { it.chapter <= chapter }
        AlertDialog.Builder(this)
            .setTitle("Contents")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                setBarsVisible(false)
                jumps.clear()
                openChapter(entries[which].chapter, anchor = entries[which].anchor)
            }
            .show()
    }

    // --- Keys and back ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!ai.isOpen && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            web.evaluateJavascript(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) "reader.next()" else "reader.prev()", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ai.isOpen -> ai.close()
            stylePanel.visibility == View.VISIBLE -> stylePanel.visibility = View.GONE
            jumps.isNotEmpty() -> jumps.removeLast().let { (c, f) -> openChapter(c, f) }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Called from reader.js, on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPage(p: Int, count: Int) = runOnUiThread {
            page = p
            pageCount = count
            savePosition()
            updateStatus()
        }

        @JavascriptInterface
        fun onTap() = runOnUiThread {
            when {
                ai.isOpen -> ai.close()
                stylePanel.visibility == View.VISIBLE -> stylePanel.visibility = View.GONE
                else -> setBarsVisible(topBar.visibility != View.VISIBLE)
            }
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
    }
}
