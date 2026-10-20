package com.readinghelper.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import java.util.concurrent.Executors

/** The popup: shows the selected text, streams Claude's explanation, and takes follow-up questions. */
class ExplainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Turn>()
    private val transcript = StringBuilder()

    @Volatile private var activeStream: StreamResponse<*>? = null
    @Volatile private var closing = false
    private var waitingForClipboard = false
    private var busy = false

    private lateinit var selected: TextView
    private lateinit var answer: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var openSettings: Button
    private lateinit var followUp: EditText
    private lateinit var send: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explain)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72).toInt())
        window.setGravity(Gravity.BOTTOM)

        val root = findViewById<View>(R.id.root)
        val basePadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bars.bottom)
            insets
        }

        selected = findViewById(R.id.selected)
        answer = findViewById(R.id.answer)
        scroll = findViewById(R.id.scroll)
        progress = findViewById(R.id.progress)
        openSettings = findViewById(R.id.openSettings)
        followUp = findViewById(R.id.followUp)
        send = findViewById(R.id.send)

        selected.setOnClickListener { selected.maxLines = if (selected.maxLines == 3) Int.MAX_VALUE else 3 }
        openSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        send.setOnClickListener { askFollowUp() }
        followUp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { askFollowUp(); true } else false
        }
        findViewById<Button>(R.id.copy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Explanation", answer.text.toString()))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.close).setOnClickListener { finish() }

        if (intent.action == ACTION_FROM_CLIPBOARD) {
            // Android only lets the focused app read the clipboard, so wait for focus.
            waitingForClipboard = true
            setBusy(true)
        } else {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            start(text?.toString())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForClipboard) {
            waitingForClipboard = false
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            setBusy(false)
            start(text?.toString())
        }
    }

    override fun onDestroy() {
        closing = true
        activeStream?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun start(raw: String?) {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            selected.visibility = View.GONE
            showMessage("No text received. Select a word or sentence first, or copy it before using the tile.")
            return
        }
        selected.text = text
        history.add(Turn(fromUser = true, text = text))
        ask()
    }

    private fun askFollowUp() {
        val question = followUp.text.toString().trim()
        if (question.isEmpty() || busy || history.isEmpty()) return
        followUp.text.clear()
        history.add(Turn(fromUser = true, text = question))
        transcript.append("\n\n---\n**").append(question).append("**\n\n")
        render()
        ask()
    }

    private fun ask() {
        val settings = Prefs(this).load()
        if (settings.apiKey.isBlank()) {
            showMessage("Add your Claude API key in Settings first.")
            openSettings.visibility = View.VISIBLE
            return
        }
        setBusy(true)
        val turns = history.toList()
        val reply = StringBuilder()
        worker.execute {
            val error: String? = try {
                val outcome = Claude.stream(settings, turns, onOpen = { activeStream = it; if (closing) it.close() }) { delta ->
                    reply.append(delta)
                    main.post {
                        transcript.append(delta)
                        render()
                    }
                }
                when (outcome) {
                    Claude.Outcome.Refused -> "Claude declined to answer this one."
                    Claude.Outcome.Truncated -> "The answer was cut off because it got too long."
                    Claude.Outcome.Finished -> null
                }
            } catch (e: UnauthorizedException) {
                "Your API key was rejected. Check it in Settings."
            } catch (e: PermissionDeniedException) {
                "This API key isn't allowed to use that model. Pick another one in Settings."
            } catch (e: NotFoundException) {
                "That model wasn't found. Pick another one in Settings."
            } catch (e: RateLimitException) {
                "Too many requests right now. Wait a moment and try again."
            } catch (e: AnthropicServiceException) {
                "Claude API error (${e.statusCode()}): ${e.message}"
            } catch (e: AnthropicIoException) {
                if (closing) null else "Couldn't reach Claude. Check your internet connection."
            } catch (e: Exception) {
                if (closing) null else "Something went wrong: ${e.message}"
            } finally {
                activeStream = null
            }
            main.post {
                if (closing) return@post
                if (reply.isNotEmpty()) history.add(Turn(fromUser = false, text = reply.toString()))
                if (error != null) {
                    transcript.append(if (reply.isEmpty()) "" else "\n\n").append("_").append(error).append("_")
                    render()
                    if (error.contains("Settings")) openSettings.visibility = View.VISIBLE
                }
                setBusy(false)
            }
        }
    }

    private fun showMessage(message: String) {
        transcript.setLength(0)
        transcript.append(message)
        render()
        setBusy(false)
    }

    private fun render() {
        val atBottom = !scroll.canScrollVertically(1)
        answer.text = SimpleMarkdown.render(transcript.toString(), getColor(R.color.accent), getColor(R.color.muted))
        if (atBottom && history.size > 1) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        send.isEnabled = !value
        send.alpha = if (value) 0.5f else 1f
    }

    companion object {
        const val ACTION_FROM_CLIPBOARD = "com.readinghelper.app.FROM_CLIPBOARD"
    }
}
