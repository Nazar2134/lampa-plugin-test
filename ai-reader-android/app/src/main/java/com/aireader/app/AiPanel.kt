package com.aireader.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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

/** The bottom sheet in the reader where Gemini answers about the selection or page. */
class AiPanel(private val activity: Activity, private val book: () -> BookInfo) {
    private val panel: LinearLayout = activity.findViewById(R.id.aiPanel)
    private val quote: TextView = activity.findViewById(R.id.aiQuote)
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

    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Turn>()
    private val transcript = StringBuilder()
    private var action = AiAction.EXPLAIN
    private var selection = ""
    private var context = ""
    private var pageText = ""
    private var lastQuestion: String? = null
    private var generation = 0
    private var busy = false
    private var theme = ReaderTheme.LIGHT
    @Volatile private var connection: HttpURLConnection? = null

    val isOpen get() = panel.visibility == View.VISIBLE

    init {
        val basePadding = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
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
    }

    fun applyTheme(t: ReaderTheme) {
        theme = t
        val density = activity.resources.displayMetrics.density
        panel.background = GradientDrawable().apply {
            setColor(if (t == ReaderTheme.LIGHT) 0xFFFAF8F5.toInt() else t.bg)
            cornerRadii = floatArrayOf(20 * density, 20 * density, 20 * density, 20 * density, 0f, 0f, 0f, 0f)
            setStroke((1 * density).toInt(), (t.muted and 0x00FFFFFF) or 0x40000000)
        }
        val field = GradientDrawable().apply {
            setColor((t.fg and 0x00FFFFFF) or 0x10000000)
            cornerRadius = 12 * density
        }
        quote.background = field
        input.background = field.constantState!!.newDrawable().mutate()
        quote.setTextColor(t.muted)
        answer.setTextColor(t.fg)
        input.setTextColor(t.fg)
        input.setHintTextColor(t.muted)
        close.imageTintList = ColorStateList.valueOf(t.muted)
        progress.indeterminateTintList = ColorStateList.valueOf(t.accent)
        for (b in listOf(claude, chatgpt, copy)) b.setTextColor(t.accent)
        for (b in listOf(send, openSettings)) b.backgroundTintList = ColorStateList.valueOf(t.accent)
        render()
    }

    fun openForSelection(action: AiAction, selection: String, context: String) {
        reset()
        this.action = action
        this.selection = selection
        this.context = context
        quote.text = selection
        quote.visibility = View.VISIBLE
        show()
        if (action == AiAction.ASK) {
            input.hint = "Ask about this…"
            focusInput()
        } else {
            history += Turn(true, Prompts.first(action, Prefs(activity).load(), selection, context, "", null))
            run()
        }
    }

    fun openForChat(pageText: String) {
        reset()
        action = AiAction.CHAT
        this.pageText = pageText
        quote.text = "Ask anything about the book or this page"
        quote.visibility = View.VISIBLE
        input.hint = "e.g. Who is this character? What just happened?"
        show()
        focusInput()
    }

    fun close() {
        generation++
        connection?.disconnect()
        hideKeyboard()
        panel.visibility = View.GONE
    }

    private fun show() {
        val height = (activity.resources.displayMetrics.heightPixels * 0.6).toInt()
        panel.layoutParams = panel.layoutParams.apply { this.height = height }
        panel.visibility = View.VISIBLE
        panel.requestApplyInsets()
    }

    private fun reset() {
        generation++
        connection?.disconnect()
        history.clear()
        transcript.setLength(0)
        selection = ""
        context = ""
        pageText = ""
        lastQuestion = null
        input.text.clear()
        input.hint = "Ask a follow-up…"
        openSettings.visibility = View.GONE
        setBusy(false)
        render()
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || busy) return
        input.text.clear()
        hideKeyboard()
        lastQuestion = question
        if (history.isEmpty()) {
            history += Turn(true, Prompts.first(action, Prefs(activity).load(), selection, context, pageText, question))
            transcript.append("**").append(question).append("**\n\n")
        } else {
            history += Turn(true, question)
            transcript.append("\n\n---\n**").append(question).append("**\n\n")
        }
        input.hint = "Ask a follow-up…"
        render()
        run()
    }

    private fun run(retried: Boolean = false) {
        val settings = Prefs(activity).load()
        if (settings.geminiKey.isBlank()) {
            transcript.append(
                "To get answers right here, add a free Gemini key in Settings. It takes a minute and needs no card.\n\n" +
                    "Or tap **Ask Claude** or **Ask ChatGPT** below to ask in those free apps."
            )
            history.removeAt(history.lastIndex)
            openSettings.visibility = View.VISIBLE
            render()
            return
        }
        setBusy(true)
        val gen = ++generation
        val turns = history.toList()
        val system = Prompts.system(book(), settings)
        val reply = StringBuilder()
        thread(isDaemon = true) {
            var error: String? = null
            try {
                val outcome = Gemini.stream(settings.geminiKey, settings.geminiModel, system, turns, onConnect = { connection = it }) { text ->
                    reply.append(text)
                    main.post {
                        if (gen != generation) return@post
                        transcript.append(text)
                        render()
                    }
                }
                error = when (outcome) {
                    Gemini.Outcome.BLOCKED -> "Gemini wouldn't answer this one. Try Ask Claude or Ask ChatGPT."
                    Gemini.Outcome.TRUNCATED -> "The answer was cut off."
                    Gemini.Outcome.FINISHED -> null
                }
            } catch (e: Gemini.ApiException) {
                if (e.code == 404 && !retried) {
                    // The saved model name no longer exists: find the current one and try again.
                    val replacement = runCatching { Gemini.check(settings.geminiKey, settings.geminiModel) }.getOrNull()
                    if (replacement != null && replacement != settings.geminiModel) {
                        Prefs(activity).saveModel(replacement)
                        main.post { if (gen == generation) run(retried = true) }
                        return@thread
                    }
                }
                error = Gemini.describe(e)
            } catch (e: Exception) {
                if (gen == generation) error = Gemini.describe(e)
            } finally {
                connection = null
            }
            main.post {
                if (gen != generation) return@post
                if (reply.isNotEmpty()) history += Turn(false, reply.toString())
                else history.removeAt(history.lastIndex)
                if (error != null) {
                    transcript.append(if (reply.isEmpty()) "" else "\n\n").append("_").append(error).append("_")
                    if ("Settings" in error) openSettings.visibility = View.VISIBLE
                }
                setBusy(false)
                render()
            }
        }
    }

    private fun handoff(target: Handoff.Target) {
        val typed = input.text.toString().trim().ifEmpty { null }
        val prompt = Prompts.handoff(book(), Prefs(activity).load(), action, selection, context, pageText, typed ?: lastQuestion)
        Handoff.send(activity, target, prompt)
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
