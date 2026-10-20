package com.aireader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import kotlin.concurrent.thread

/** What the reader needs to tell the AI panel about the open book. */
interface AiHost {
    val bookId: String
    fun bookInfo(): BookInfo
    fun session(): BookSession.Session?
    /** Current chapter and how far into it the end of the visible page is (0–1). */
    fun location(): Pair<Int, Float>
}

/** A selection in the book, as reported by reader.js. */
data class Selection(val text: String, val context: String, val sentence: String, val chapter: Int, val occurrence: Int)

/** The bottom sheet in the reader where the AI answers about a selection or about the book. */
class AiPanel(private val activity: Activity, private val host: AiHost) {
    private val panel: LinearLayout = activity.findViewById(R.id.aiPanel)
    private val quote: TextView = activity.findViewById(R.id.aiQuote)
    private val chips: LinearLayout = activity.findViewById(R.id.aiChips)
    private val chipScroll: View = activity.findViewById(R.id.aiChipScroll)
    private val languagePill: TextView = activity.findViewById(R.id.aiLanguage)
    private val levelPill: TextView = activity.findViewById(R.id.aiLevel)
    private val regenerate: TextView = activity.findViewById(R.id.aiRegenerate)
    private val saveWord: TextView = activity.findViewById(R.id.aiSaveWord)
    private val newChat: TextView = activity.findViewById(R.id.aiNewChat)
    private val status: TextView = activity.findViewById(R.id.aiStatus)
    private val answer: TextView = activity.findViewById(R.id.aiAnswer)
    private val scroll: ScrollView = activity.findViewById(R.id.aiScroll)
    private val progress: ProgressBar = activity.findViewById(R.id.aiProgress)
    private val openSettings: Button = activity.findViewById(R.id.aiSettings)
    private val input: EditText = activity.findViewById(R.id.aiInput)
    private val send: Button = activity.findViewById(R.id.aiSend)
    private val close: ImageButton = activity.findViewById(R.id.aiClose)
    private val claude: Button = activity.findViewById(R.id.aiClaude)
    private val chatgpt: Button = activity.findViewById(R.id.aiChatgpt)
    private val copy: Button = activity.findViewById(R.id.aiCopy)

    private val db = ReaderDb.get(activity)
    private val main = Handler(Looper.getMainLooper())
    /** What was sent to the AI, turn by turn. The selection material stays in the first turn. */
    private val history = ArrayList<Turn>()
    private val transcript = StringBuilder()
    private var action = AiAction.EXPLAIN
    private var selection: Selection? = null
    private var pageText = ""
    private var lastQuestion: String? = null
    /** Excerpts already sent in this conversation, so follow-ups don't resend them. */
    private val sent = HashSet<Pair<Int, Int>>()
    private var generation = 0
    private var busy = false
    private var theme = ReaderTheme.LIGHT
    @Volatile private var connection: HttpURLConnection? = null

    val isOpen get() = panel.visibility == View.VISIBLE

    init {
        val basePadding = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bottom = maxOf(ime, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            // With the keyboard up, grow to the top of the screen so the answer stays readable.
            if (isOpen) {
                val top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                val screen = (v.parent as View).height
                val height = if (ime > 0) screen - top - dp(8) else (screen * 0.62).toInt()
                if (height > 0 && v.layoutParams.height != height) v.layoutParams = v.layoutParams.apply { this.height = height }
            }
            insets
        }
        panel.setOnClickListener { } // keep taps from reaching the page underneath
        close.setOnClickListener { close() }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        claude.setOnClickListener { handoff(Handoff.Target.CLAUDE) }
        chatgpt.setOnClickListener { handoff(Handoff.Target.CHATGPT) }
        copy.setOnClickListener {
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Answer", answer.text.toString()))
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
        }
        openSettings.setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        languagePill.setOnClickListener { chooseLanguage() }
        levelPill.setOnClickListener { chooseLevel() }
        regenerate.setOnClickListener { if (!busy) startSelectionAction(action, useCache = false) }
        saveWord.setOnClickListener { saveSelectionToVocabulary() }
        newChat.setOnClickListener {
            if (busy) return@setOnClickListener
            db.clearChat(host.bookId)
            openForChat(pageText)
        }
        for (a in AiAction.entries.filter { it != AiAction.CHAT }) {
            val chip = TextView(activity).apply {
                text = a.label
                tag = a
                textSize = 14f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { if (!busy || a != action) startSelectionAction(a) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun applyTheme(t: ReaderTheme) {
        theme = t
        val density = activity.resources.displayMetrics.density
        panel.background = GradientDrawable().apply {
            setColor(if (t == ReaderTheme.LIGHT) 0xFFFAF8F5.toInt() else t.bg)
            cornerRadii = floatArrayOf(20 * density, 20 * density, 20 * density, 20 * density, 0f, 0f, 0f, 0f)
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x40000000)
        }
        quote.background = field(t)
        input.background = field(t)
        quote.setTextColor(t.muted)
        answer.setTextColor(t.fg)
        status.setTextColor(t.muted)
        input.setTextColor(t.fg)
        input.setHintTextColor(t.muted)
        close.imageTintList = ColorStateList.valueOf(t.muted)
        progress.indeterminateTintList = ColorStateList.valueOf(t.accent)
        for (b in listOf(claude, chatgpt, copy)) b.setTextColor(t.accent)
        for (b in listOf(send, openSettings)) b.backgroundTintList = ColorStateList.valueOf(t.accent)
        for (p in listOf(languagePill, levelPill, regenerate, saveWord, newChat)) {
            p.setTextColor(t.accent)
            p.background = field(t, radius = 14f)
        }
        styleChips()
        render()
    }

    private fun field(t: ReaderTheme, radius: Float = 12f) = GradientDrawable().apply {
        setColor((t.fg and 0x00FFFFFF) or 0x10000000)
        cornerRadius = radius * activity.resources.displayMetrics.density
    }

    private fun styleChips() {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as TextView
            val selected = chip.tag == action
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else theme.fg)
            chip.background = GradientDrawable().apply {
                setColor(if (selected) theme.accent else (theme.fg and 0x00FFFFFF) or 0x12000000)
                cornerRadius = 16 * activity.resources.displayMetrics.density
            }
        }
    }

    // --- Opening ---

    fun openForSelection(action: AiAction, selection: Selection) {
        resetConversation()
        this.selection = selection
        pageText = ""
        quote.text = selection.text
        chipScroll.visibility = View.VISIBLE
        show()
        startSelectionAction(action)
    }

    fun openForChat(pageText: String) {
        resetConversation()
        this.selection = null
        this.pageText = pageText
        action = AiAction.CHAT
        quote.text = "Ask about the book: characters, earlier events, what a passage means"
        chipScroll.visibility = View.GONE
        // Saved conversation for this book.
        val saved = db.chat(host.bookId).takeLast(20)
        for (m in saved) {
            history += Turn(m.fromUser, m.text)
            if (m.fromUser) transcript.append(if (transcript.isEmpty()) "" else "\n\n---\n").append("**").append(m.text).append("**\n\n")
            else transcript.append(m.text)
        }
        if (saved.isNotEmpty()) lastQuestion = saved.lastOrNull { it.fromUser }?.text
        input.hint = "e.g. Who is Jonas? Why did she go to the shed?"
        updateOptions()
        show()
        render()
        focusInput()
    }

    fun close() {
        generation++
        connection?.disconnect()
        hideKeyboard()
        panel.visibility = View.GONE
    }

    private fun show() {
        val height = (activity.resources.displayMetrics.heightPixels * 0.62).toInt()
        panel.layoutParams = panel.layoutParams.apply { this.height = height }
        panel.visibility = View.VISIBLE
        panel.requestApplyInsets()
    }

    private fun resetConversation() {
        generation++
        connection?.disconnect()
        history.clear()
        sent.clear()
        transcript.setLength(0)
        lastQuestion = null
        input.text.clear()
        input.hint = "Ask a follow-up…"
        openSettings.visibility = View.GONE
        status.visibility = View.GONE
        setBusy(false)
    }

    private fun updateOptions() {
        val sel = selection != null
        languagePill.text = "In ${Prefs(activity).load().language} ▾"
        levelPill.text = "Level ${Prefs(activity).load().cefr} ▾"
        levelPill.visibility = if (action == AiAction.SIMPLIFY) View.VISIBLE else View.GONE
        regenerate.visibility = if (sel && action.isSelectionAction) View.VISIBLE else View.GONE
        saveWord.visibility = if (sel) View.VISIBLE else View.GONE
        newChat.visibility = if (!sel) View.VISIBLE else View.GONE
        styleChips()
    }

    // --- Requests ---

    private fun startSelectionAction(newAction: AiAction, useCache: Boolean = true) {
        val sel = selection ?: return
        generation++
        connection?.disconnect()
        action = newAction
        history.clear()
        sent.clear()
        transcript.setLength(0)
        lastQuestion = null
        status.visibility = View.GONE
        openSettings.visibility = View.GONE
        setBusy(false)
        updateOptions()
        if (newAction == AiAction.ASK) {
            input.hint = "Ask about this…"
            render()
            focusInput()
            return
        }
        input.hint = "Ask a follow-up…"
        render()
        request(question = null, useCache = useCache, sel = sel)
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || busy) return
        input.text.clear()
        hideKeyboard()
        val previous = lastQuestion
        lastQuestion = question
        transcript.append(if (transcript.isEmpty()) "" else "\n\n---\n").append("**").append(question).append("**\n\n")
        render()
        if (action == AiAction.CHAT) db.addChat(host.bookId, ChatMessage(true, question))
        request(question, useCache = false, sel = selection, previousQuestion = previous)
    }

    /**
     * Builds the request (book context is gathered on a background thread) and streams the answer.
     * The first turn of a selection conversation carries the selection; later turns carry only new excerpts.
     */
    private fun request(question: String?, useCache: Boolean, sel: Selection?, previousQuestion: String? = null, retried: Boolean = false) {
        val settings = Prefs(activity).load()
        val provider = settings.provider
        if (Ai.key(settings).isBlank()) {
            transcript.append(
                if (provider == AiProvider.DEEPSEEK) "Add your DeepSeek API key in Settings to get answers right here.\n\n"
                else "To get answers right here, add a free Gemini key in Settings. It takes a minute and needs no card.\n\n"
            ).append("Or tap **Ask Claude** or **Ask ChatGPT** below to ask in those free apps.")
            openSettings.visibility = View.VISIBLE
            if (question != null && action == AiAction.CHAT) history += Turn(true, question)
            render()
            return
        }
        setBusy(true)
        val gen = ++generation
        val currentAction = action
        val (chapter, fraction) = host.location()
        val book = host.bookInfo()
        val isFirst = history.isEmpty() || (currentAction == AiAction.CHAT && history.none { it.fromUser && it.text.contains("<book_context>") })
        val priorTurns = history.toList()

        thread(isDaemon = true) {
            // Book context: the selection's paragraph and neighbours, recent text, and earlier passages relevant to the question.
            val session = host.session()
            val index = runCatching { session?.prepare() }.getOrNull()
            val text = session?.text
            var context = ""
            var related = ""
            if (text != null && index != null) {
                val position = text.paragraphAt(chapter, fraction)
                val paragraph = sel?.let { text.findParagraph(it.chapter, it.text, near = position) }
                if (isFirst) {
                    val around = when {
                        sel != null && paragraph != null -> {
                            val (before, after) = when (currentAction) {
                                AiAction.ASK -> 2 to 2
                                AiAction.EXPLAIN -> 1 to 1
                                else -> 0 to 0
                            }
                            BookContext.around(text, sel.chapter, paragraph, before, after)
                        }
                        currentAction == AiAction.CHAT -> BookContext.recent(text, chapter, position, maxChars = 1800)
                        else -> emptyList()
                    }
                    sent += around.map { it.chapter to it.paragraph }
                    context = BookContext.format(text, around)
                }
                if (question != null) {
                    val query = listOfNotNull(question, previousQuestion, sel?.text).joinToString(" ")
                    val until = (sel?.chapter ?: chapter) to ((paragraph ?: position) + 2)
                    val found = BookContext.retrieve(text, index, query, until, exclude = sent, maxChars = if (isFirst) 5000 else 3500)
                    sent += found.map { it.chapter to it.paragraph }
                    related = BookContext.format(text, found)
                }
            }
            if (context.isEmpty() && isFirst && sel != null) context = sel.context

            val request = AiRequest(currentAction, sel?.text.orEmpty(), sel?.sentence.orEmpty(), context, related, question)
            val userTurn = if (isFirst) Prompts.first(request, settings, book.languageName) else Prompts.followUp(question.orEmpty(), related)
            // Older turns are sent as asked; only the newest carries fresh excerpts.
            val turns = priorTurns + Turn(true, userTurn)
            val cacheKey = if (useCache && currentAction.isSelectionAction && question == null) {
                ReaderDb.cacheKey(provider.name, Ai.model(settings), currentAction.name, settings.language, settings.cefr, settings.detailed.toString(), request.selection, request.context)
            } else null

            cacheKey?.let { db.cached(it) }?.let { cached ->
                main.post {
                    if (gen != generation) return@post
                    history += Turn(true, userTurn)
                    history += Turn(false, cached)
                    transcript.append(cached)
                    status.text = "Saved answer. Tap ↻ for a new one."
                    status.visibility = View.VISIBLE
                    setBusy(false)
                    render()
                }
                return@thread
            }

            val system = Prompts.system(book, settings)
            val reply = StringBuilder()
            var error: String? = null
            try {
                val result = Ai.stream(settings, system, turns, onConnect = { connection = it }, isCancelled = { gen != generation }) { piece ->
                    reply.append(piece)
                    main.post {
                        if (gen != generation) return@post
                        transcript.append(piece)
                        render()
                    }
                }
                if (result.inputTokens + result.outputTokens > 0) db.addUsage(provider, Ai.model(settings), result.inputTokens, result.outputTokens)
                error = when (result.outcome) {
                    AiOutcome.BLOCKED -> "${Ai.name(provider)} wouldn't answer this one. Try Ask Claude or Ask ChatGPT."
                    AiOutcome.TRUNCATED -> "The answer was cut off."
                    AiOutcome.FINISHED -> null
                }
                if (error == null && cacheKey != null && reply.isNotEmpty()) db.cache(cacheKey, reply.toString())
            } catch (e: ApiException) {
                if (provider == AiProvider.GEMINI && e.code == 404 && !retried && reply.isEmpty() && gen == generation) {
                    // The saved model name no longer exists (Google renames models): switch to the current one and ask again.
                    val replacement = runCatching { Gemini.check(settings.geminiKey, settings.geminiModel) }.getOrNull()
                    if (replacement != null && replacement != settings.geminiModel) {
                        Prefs(activity).saveGeminiModel(replacement)
                        connection = null
                        main.post { if (gen == generation) request(question, useCache, sel, previousQuestion, retried = true) }
                        return@thread
                    }
                }
                error = Ai.describe(provider, e)
            } catch (e: Exception) {
                if (gen == generation) error = Ai.describe(provider, e)
            } finally {
                connection = null
            }
            main.post {
                if (gen != generation) return@post
                if (reply.isNotEmpty()) {
                    history += Turn(true, if (currentAction == AiAction.CHAT) question.orEmpty() else userTurn)
                    history += Turn(false, reply.toString())
                    if (currentAction == AiAction.CHAT) db.addChat(host.bookId, ChatMessage(false, reply.toString()))
                }
                if (error != null) {
                    transcript.append(if (reply.isEmpty()) "" else "\n\n").append("_").append(error).append("_")
                    if ("Settings" in error) openSettings.visibility = View.VISIBLE
                }
                setBusy(false)
                render()
            }
        }
    }

    // --- Options ---

    private fun chooseLanguage() {
        val current = Prefs(activity).load().language
        val options = (Prompts.languages + "Other…").toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Answer in")
            .setSingleChoiceItems(options, Prompts.languages.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                if (which < Prompts.languages.size) setLanguage(Prompts.languages[which]) else askCustomLanguage(current)
            }
            .show()
    }

    private fun askCustomLanguage(current: String) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(if (current in Prompts.languages) "" else current)
            hint = "e.g. Polish"
        }
        AlertDialog.Builder(activity)
            .setTitle("Answer in")
            .setView(LinearLayout(activity).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { setLanguage(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setLanguage(language: String) {
        Prefs(activity).setLanguage(language)
        updateOptions()
        if (selection != null && action.isSelectionAction) startSelectionAction(action)
    }

    private fun chooseLevel() {
        val current = Prefs(activity).load().cefr
        AlertDialog.Builder(activity)
            .setTitle("Simplify to level")
            .setSingleChoiceItems(Prompts.cefrLevels.toTypedArray(), Prompts.cefrLevels.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                Prefs(activity).setCefr(Prompts.cefrLevels[which])
                updateOptions()
                startSelectionAction(AiAction.SIMPLIFY)
            }
            .show()
    }

    private fun saveSelectionToVocabulary() {
        val sel = selection ?: return
        val info = host.bookInfo()
        val explanation = if (action == AiAction.VOCABULARY && !busy) history.lastOrNull { !it.fromUser }?.text.orEmpty() else ""
        val chapterTitle = host.session()?.book?.chapterTitle(sel.chapter).orEmpty()
        Vocabulary.save(activity, WordSource(sel.text, sel.sentence, host.bookId, info.title, sel.chapter, chapterTitle, sel.occurrence), explanation)
    }

    private fun handoff(target: Handoff.Target) {
        val typed = input.text.toString().trim().ifEmpty { null }
        val sel = selection
        val request = AiRequest(action, sel?.text.orEmpty(), sel?.sentence.orEmpty(), sel?.context.orEmpty(), question = typed ?: lastQuestion)
        Handoff.send(activity, target, Prompts.handoff(host.bookInfo(), Prefs(activity).load(), request, pageText))
    }

    private fun render() {
        val atBottom = !scroll.canScrollVertically(1)
        answer.text = SimpleMarkdown.render(transcript.toString(), theme.accent, theme.muted)
        answer.visibility = if (transcript.isEmpty()) View.GONE else View.VISIBLE
        if (atBottom && lastQuestion != null) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        send.isEnabled = !value
        send.alpha = if (value) 0.5f else 1f
    }

    private fun focusInput() {
        input.requestFocus()
        input.post { activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
    }
}
