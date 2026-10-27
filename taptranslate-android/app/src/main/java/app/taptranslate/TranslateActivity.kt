package app.taptranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/**
 * Bottom-sheet popup that translates the text it was opened with. Entry points: the text-selection menu
 * (PROCESS_TEXT), the Share sheet, the floating tap button and the clipboard tile.
 */
open class TranslateActivity : Activity() {

    companion object {
        const val ACTION_CLIPBOARD = "app.taptranslate.action.CLIPBOARD"
        const val ACTION_TAP = "app.taptranslate.action.TAP"
        const val EXTRA_TEXT = "app.taptranslate.extra.TEXT"
        const val EXTRA_START = "app.taptranslate.extra.START"
        const val EXTRA_END = "app.taptranslate.extra.END"

        private const val MAX_SOURCE = 4000
        private const val CONTEXT_LIMIT = 600
        private const val SHOWN_SOURCE_LIMIT = 400
        private const val AUTO_TRANSLATE_LIMIT = 200
    }

    private lateinit var prefs: Prefs
    private lateinit var card: View
    private lateinit var langLabel: TextView
    private lateinit var sourceView: TextView
    private lateinit var translateAll: TextView
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var settingsButton: Button
    private lateinit var replaceButton: Button
    private val main = Handler(Looper.getMainLooper())
    private var cardBottomPadding = 0

    // What the popup is about: the full text, and the part of it being translated.
    private var fullText = ""
    private var selStart = 0
    private var selEnd = 0
    private var canReplace = false
    private var awaitingClipboard = false
    private var targetOverride: String? = null

    // Current request.
    private var client: DeepSeekClient? = null
    private var requestId = 0
    private var answer = ""
    private var finished = false
    private var renderScheduled = false
    private var sourceLang: String? = null
    private var targetLang: String? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_translate)

        card = findViewById(R.id.card)
        langLabel = findViewById(R.id.lang_label)
        sourceView = findViewById(R.id.source)
        translateAll = findViewById(R.id.translate_all)
        statusView = findViewById(R.id.status)
        resultView = findViewById(R.id.result)
        progress = findViewById(R.id.progress)
        settingsButton = findViewById(R.id.settings)
        replaceButton = findViewById(R.id.replace)

        val root = findViewById<View>(R.id.root)
        root.setOnClickListener { finish() }
        cardBottomPadding = card.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            card.setPadding(card.paddingLeft, card.paddingTop, card.paddingRight, cardBottomPadding + insets.bottomBarsAndIme())
            insets
        }

        sourceView.movementMethod = LinkMovementMethod.getInstance()
        sourceView.highlightColor = Color.TRANSPARENT
        findViewById<ImageButton>(R.id.close).setOnClickListener { finish() }
        langLabel.setOnClickListener { chooseLanguage() }
        translateAll.setOnClickListener {
            selStart = 0
            selEnd = fullText.length
            renderSource()
            translate()
        }
        findViewById<ImageButton>(R.id.speak).apply {
            setOnClickListener { speak(query(), sourceLang) }
            setOnLongClickListener {
                MiniMarkdown.headline(MiniMarkdown.parse(answer).body).takeIf { it.isNotEmpty() }
                    ?.let { speak(it, targetLang) }
                true
            }
        }
        findViewById<ImageButton>(R.id.copy).apply {
            setOnClickListener { copy(MiniMarkdown.headline(MiniMarkdown.parse(answer).body)) }
            setOnLongClickListener { copy(resultView.text.toString()); true }
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        replaceButton.setOnClickListener {
            val translation = MiniMarkdown.headline(MiniMarkdown.parse(answer).body)
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, translation))
            finish()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard, so wait for focus.
        if (hasFocus && awaitingClipboard) readClipboard()
    }

    override fun onStop() {
        super.onStop()
        // Don't leave a stale popup behind when the user moves on.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        client?.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        canReplace = false
        awaitingClipboard = false
        targetOverride = null
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                canReplace = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                setSource(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            }
            Intent.ACTION_SEND -> setSource(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            ACTION_TAP -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                val start = intent.getIntExtra(EXTRA_START, -1)
                val end = intent.getIntExtra(EXTRA_END, -1)
                if (text != null && start in 0 until end && end <= text.length) setWord(text, start, end)
                else setSource(text, autoTranslate = (text?.trim()?.length ?: 0) <= AUTO_TRANSLATE_LIMIT)
            }
            ACTION_CLIPBOARD -> {
                awaitingClipboard = true
                showStatus(getString(R.string.reading_clipboard))
                if (hasWindowFocus()) readClipboard()
            }
            else -> setSource(intent.getStringExtra(EXTRA_TEXT))
        }
    }

    private fun readClipboard() {
        awaitingClipboard = false
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) showError(getString(R.string.clipboard_empty)) else setSource(text)
    }

    /** Shows [text] and translates all of it (or waits for the user to tap a word, for long text). */
    private fun setSource(text: String?, autoTranslate: Boolean = true) {
        val t = text?.trim().orEmpty().take(MAX_SOURCE)
        if (t.isEmpty()) {
            showError(getString(R.string.no_text))
            return
        }
        fullText = t
        selStart = 0
        selEnd = t.length
        renderSource()
        if (autoTranslate) translate() else showStatus(getString(R.string.pick_word), showTranslateAll = true)
    }

    /** Shows [text] with the word at [start, end) highlighted, and translates that word in context. */
    private fun setWord(text: String, start: Int, end: Int) {
        fullText = text
        selStart = start
        selEnd = end
        renderSource()
        translate()
    }

    private fun isWhole() = selStart == 0 && selEnd == fullText.length
    private fun query() = fullText.substring(selStart, selEnd).trim()
    private fun contextForQuery(): String? = if (isWhole()) null else sentenceAround(selStart, selEnd)

    private fun sentenceBounds(start: Int, end: Int): Pair<Int, Int> {
        val bi = BreakIterator.getSentenceInstance()
        bi.setText(fullText)
        var a = bi.preceding(start + 1).let { if (it == BreakIterator.DONE) 0 else it }
        var b = bi.following(maxOf(start, end - 1)).let { if (it == BreakIterator.DONE) fullText.length else it }
        if (b - a > CONTEXT_LIMIT) {
            a = maxOf(a, start - CONTEXT_LIMIT / 2)
            b = minOf(b, end + CONTEXT_LIMIT / 2)
        }
        return a to b
    }

    private fun sentenceAround(start: Int, end: Int): String {
        val (a, b) = sentenceBounds(start, end)
        return fullText.substring(a, b).trim()
    }

    /** Draws the original text with every word tappable and the current word highlighted. */
    private fun renderSource() {
        val (shownStart, shownEnd) =
            if (isWhole() || fullText.length <= SHOWN_SOURCE_LIMIT) 0 to fullText.length
            else sentenceBounds(selStart, selEnd)
        val shown = fullText.substring(shownStart, shownEnd)
        val sb = SpannableStringBuilder()
        if (shownStart > 0) sb.append("… ")
        val offset = sb.length - shownStart
        sb.append(shown)
        if (shownEnd < fullText.length) sb.append(" …")

        val bi = BreakIterator.getWordInstance()
        bi.setText(shown)
        var a = bi.first()
        var b = bi.next()
        while (b != BreakIterator.DONE) {
            if (shown.substring(a, b).any { it.isLetterOrDigit() }) {
                val wordStart = shownStart + a
                val wordEnd = shownStart + b
                sb.setSpan(WordSpan(wordStart, wordEnd), wordStart + offset, wordEnd + offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            a = b
            b = bi.next()
        }
        if (!isWhole()) {
            sb.setSpan(BackgroundColorSpan(getColor(R.color.highlight)), selStart + offset, selEnd + offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), selStart + offset, selEnd + offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sourceView.textSize = if (fullText.length > 60) 16f else 20f
        sourceView.text = sb
        translateAll.visibility = if (isWhole()) View.GONE else View.VISIBLE
    }

    private inner class WordSpan(private val start: Int, private val end: Int) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (start == selStart && end == selEnd) return
            selStart = start
            selEnd = end
            renderSource()
            translate()
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false // keep the normal text color too
        }
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_language)
            .setItems(Languages.LIST) { _, which ->
                targetOverride = Languages.LIST[which]
                translate()
            }
            .show()
    }

    private fun translate() {
        client?.cancel()
        val text = query()
        if (text.isEmpty()) return
        val apiKey = prefs.apiKey
        if (apiKey.isEmpty()) {
            showError(getString(R.string.need_key), showSettings = true)
            return
        }

        val id = ++requestId
        val model = prefs.model
        val system = Prompt.system(prefs.myLanguage, prefs.otherLanguage, targetOverride)
        val user = Prompt.user(text, contextForQuery())
        val cacheKey = ResultCache.key(model, system, user)
        val c = DeepSeekClient(prefs.baseUrl, apiKey)
        client = c
        answer = ""
        finished = false
        sourceLang = null
        targetLang = null
        updateLangLabel()
        showStatus(getString(R.string.translating))
        progress.visibility = View.VISIBLE

        Thread {
            val cached = ResultCache.get(this, cacheKey)
            if (cached != null) {
                main.post { if (id == requestId) complete(cached) }
                return@Thread
            }
            try {
                val full = c.streamChat(
                    model, system, user, Prompt.maxTokens(model, text),
                    onThinking = {
                        main.post { if (id == requestId && answer.isEmpty()) statusView.text = getString(R.string.thinking) }
                    },
                    onDelta = { delta ->
                        main.post {
                            if (id == requestId) {
                                answer += delta
                                scheduleRender()
                            }
                        }
                    },
                )
                if (c.isCancelled) return@Thread
                if (full.isNotBlank()) ResultCache.put(this, cacheKey, full)
                main.post { if (id == requestId) complete(full) }
            } catch (e: Exception) {
                if (!c.isCancelled) main.post { if (id == requestId) showError(friendlyError(e)) }
            }
        }.start()
    }

    private fun complete(full: String) {
        answer = full
        finished = true
        progress.visibility = View.INVISIBLE
        if (MiniMarkdown.parse(full).body.isBlank()) showError(getString(R.string.empty_answer)) else render()
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        main.postDelayed({
            renderScheduled = false
            render()
        }, 40)
    }

    private fun render() {
        val parsed = MiniMarkdown.parse(answer)
        if (parsed.source != null && parsed.source != sourceLang) {
            sourceLang = parsed.source
            targetLang = parsed.target
            updateLangLabel()
        }
        if (parsed.body.isBlank()) return
        statusView.visibility = View.GONE
        settingsButton.visibility = View.GONE
        resultView.visibility = View.VISIBLE
        resultView.setTextColor(getColor(R.color.text_primary))
        resultView.text = MiniMarkdown.render(
            parsed.body, getColor(R.color.accent), getColor(R.color.text_muted), resources.displayMetrics.density
        )
        replaceButton.visibility = if (finished && canReplace && isWhole()) View.VISIBLE else View.GONE
    }

    private fun updateLangLabel() {
        val from = Languages.nameOf(sourceLang) ?: getString(R.string.auto_detect)
        val to = targetOverride ?: Languages.nameOf(targetLang) ?: prefs.myLanguage
        langLabel.text = "$from  →  $to  ▾"
    }

    private fun showStatus(message: String, showTranslateAll: Boolean = false) {
        statusView.visibility = View.VISIBLE
        statusView.setTextColor(getColor(R.color.text_muted))
        statusView.text = message
        resultView.visibility = View.GONE
        settingsButton.visibility = View.GONE
        replaceButton.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        if (showTranslateAll) translateAll.visibility = View.VISIBLE
        updateLangLabel()
    }

    private fun showError(message: String, showSettings: Boolean = false) {
        showStatus(message)
        statusView.setTextColor(getColor(R.color.error))
        settingsButton.visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ApiException -> e.message.orEmpty()
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "DeepSeek took too long to answer. Try again."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Translation", text))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String, languageCode: String?) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ttsReady) {
            say(engine, text, languageCode)
            return
        }
        pendingSpeech = text to languageCode
        if (engine == null) {
            tts = TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val pending = pendingSpeech
                pendingSpeech = null
                if (ttsReady && pending != null) tts?.let { say(it, pending.first, pending.second) }
            }
        }
    }

    private fun say(engine: TextToSpeech, text: String, languageCode: String?) {
        if (languageCode != null) {
            val locale = Locale.forLanguageTag(languageCode)
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tap-translate")
    }
}

/** The same popup in its own task, for launches from the floating button and the Quick Settings tile. */
class FloatingTranslateActivity : TranslateActivity()
