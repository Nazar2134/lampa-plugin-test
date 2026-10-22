package com.aireader.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Pronunciation and reading aloud with the phone's text-to-speech voices. Requests made before the engine
 * is ready are run once it is. Callbacks arrive on the main thread.
 */
class Speech(context: Context) {
    /** Reading aloud moved on to sentence [index]. */
    var onSentence: ((index: Int) -> Unit)? = null
    /** Reading aloud reached the last sentence ([finished]) or was stopped. */
    var onReadingEnded: ((finished: Boolean) -> Unit)? = null
    /** There's no voice for the language; the user can install one. */
    var onMissingVoice: ((Locale) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var pending: (() -> Unit)? = null
    private var readingCount = 0
    private var readingRun = 0
    val isReading get() = readingCount > 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            val run = pending
            pending = null
            if (ready) run?.invoke() else if (run != null) onMissingVoice?.invoke(Locale.getDefault())
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val (run, index) = parse(utteranceId) ?: return
                main.post { if (run == readingRun && readingCount > 0) onSentence?.invoke(index) }
            }

            override fun onDone(utteranceId: String) {
                val (run, index) = parse(utteranceId) ?: return
                main.post {
                    if (run == readingRun && readingCount > 0 && index == readingCount - 1) {
                        readingCount = 0
                        onReadingEnded?.invoke(true)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {}
        })
    }

    private fun parse(id: String): Pair<Int, Int>? {
        if (!id.startsWith("read:")) return null
        val parts = id.split(':')
        return (parts.getOrNull(1)?.toIntOrNull() ?: return null) to (parts.getOrNull(2)?.toIntOrNull() ?: return null)
    }

    private fun whenReady(block: () -> Unit) {
        when {
            ready -> block()
            failed -> onMissingVoice?.invoke(Locale.getDefault())
            else -> pending = block
        }
    }

    /** Chooses the voice for [lang] ("de", "en"…); false (and [onMissingVoice]) when the phone has none. */
    private fun useLanguage(lang: String, rate: Float): Boolean {
        val locale = localeFor(lang)
        val result = tts.setLanguage(locale)
        tts.setSpeechRate(rate)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onMissingVoice?.invoke(locale)
            return false
        }
        return true
    }

    /** Says a word or a sentence, interrupting anything else (including reading aloud). */
    fun say(text: String, lang: String, rate: Float) = whenReady {
        endReading(notify = true)
        if (useLanguage(lang, rate)) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    /** Reads [sentences] in order; [onSentence] reports each as it starts. */
    fun read(sentences: List<String>, lang: String, rate: Float) = whenReady {
        endReading(notify = false)
        if (sentences.isEmpty() || !useLanguage(lang, rate)) {
            onReadingEnded?.invoke(sentences.isEmpty())
            return@whenReady
        }
        val run = ++readingRun
        readingCount = sentences.size
        tts.stop()
        sentences.forEachIndexed { i, s -> tts.speak(s, TextToSpeech.QUEUE_ADD, null, "read:$run:$i") }
    }

    fun stop() {
        pending = null
        endReading(notify = true)
        if (ready) tts.stop()
    }

    private fun endReading(notify: Boolean) {
        if (readingCount == 0) return
        readingCount = 0
        readingRun++
        if (ready) tts.stop()
        if (notify) onReadingEnded?.invoke(false)
    }

    fun shutdown() {
        pending = null
        readingCount = 0
        tts.shutdown()
    }

    companion object {
        fun localeFor(lang: String): Locale = when (lang) {
            "en" -> Locale.US
            "de" -> Locale.GERMANY
            "uk" -> Locale.forLanguageTag("uk-UA")
            "" -> Locale.getDefault()
            else -> Locale.forLanguageTag(lang)
        }
    }
}
