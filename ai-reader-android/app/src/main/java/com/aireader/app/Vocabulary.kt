package com.aireader.app

import android.app.Activity
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Where a saved word came from. */
data class WordSource(
    val word: String,
    val sentence: String,
    val bookId: String?,
    val bookTitle: String,
    val chapter: Int,
    val chapterTitle: String,
    val occurrence: Int,
)

object Vocabulary {
    /**
     * Saves a word straight away, then asks the AI (if a key is set) for a translation and a short explanation
     * and fills them in. [explanation] and [translation] are kept as is when given, e.g. a Vocabulary answer already
     * on screen or a word card; with both given there is nothing to ask.
     */
    fun save(activity: Activity, source: WordSource, explanation: String = "", translation: String = "", onSaved: (VocabEntry) -> Unit = {}) {
        val db = ReaderDb.get(activity)
        val word = source.word.trim().trim('.', ',', ';', ':', '!', '?', '«', '»', '"', '“', '”', '„', '(', ')').take(200)
        if (word.isEmpty()) return
        db.findVocab(word, source.bookId)?.let {
            Toast.makeText(activity, "“$word” is already in your vocabulary", Toast.LENGTH_SHORT).show()
            return
        }
        val entry = VocabEntry(
            word = word, sentence = source.sentence.take(1000), translation = translation, explanation = explanation, bookId = source.bookId,
            bookTitle = source.bookTitle, chapter = source.chapter, chapterTitle = source.chapterTitle, anchorOccurrence = source.occurrence,
        )
        val saved = entry.copy(id = db.addVocab(entry))
        Toast.makeText(activity, "Saved “$word” to vocabulary", Toast.LENGTH_SHORT).show()
        onSaved(saved)

        val settings = Prefs(activity).load()
        if (Ai.key(settings).isBlank() || (translation.isNotBlank() && explanation.isNotBlank())) return
        thread(isDaemon = true) {
            runCatching {
                val reply = StringBuilder()
                val result = Ai.stream(settings, "You produce compact dictionary data as JSON.", listOf(Turn(true, Prompts.vocabularyCard(word, source.sentence, settings))), {}) { reply.append(it) }
                db.addUsage(settings.provider, Ai.model(settings), result.inputTokens, result.outputTokens)
                val card = parseCard(reply.toString()) ?: return@thread
                val current = db.vocab(saved.id) ?: return@thread
                db.updateVocab(
                    current.copy(
                        translation = current.translation.ifBlank { card.optString("translation").trim() },
                        explanation = current.explanation.ifBlank {
                            listOf(
                                listOf(card.optString("lemma"), card.optString("part_of_speech")).filter { it.isNotBlank() }.joinToString(", "),
                                card.optString("meaning"),
                            ).filter { it.isNotBlank() }.joinToString(" — ")
                        },
                    )
                )
            }
        }
    }

    /** The JSON object in a reply, tolerating surrounding text or code fences. */
    internal fun parseCard(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
    }

    /** Spaced-repetition answer buttons. */
    enum class Grade { AGAIN, HARD, GOOD, EASY }

    /** A simplified SM-2 schedule. Returns the entry with its next review time and status. */
    fun review(e: VocabEntry, grade: Grade, now: Long = System.currentTimeMillis()): VocabEntry {
        val day = 24 * 60 * 60 * 1000.0
        var ease = e.ease
        val interval = when (grade) {
            Grade.AGAIN -> { ease = (ease - 0.2).coerceAtLeast(1.3); 0.0 }
            Grade.HARD -> { ease = (ease - 0.15).coerceAtLeast(1.3); maxOf(1.0, e.intervalDays * 1.2) }
            Grade.GOOD -> if (e.intervalDays < 1) 1.0 else if (e.intervalDays < 3) 3.0 else e.intervalDays * ease
            Grade.EASY -> { ease += 0.15; if (e.intervalDays < 1) 4.0 else e.intervalDays * ease * 1.3 }
        }
        // "Again" comes back in ten minutes, within the same session.
        val due = if (grade == Grade.AGAIN) now + 10 * 60 * 1000L else now + (interval * day).toLong()
        val status = when {
            interval >= 21 -> VocabStatus.KNOWN
            else -> VocabStatus.LEARNING
        }
        return e.copy(ease = ease, intervalDays = interval, due = due, status = status, reviews = e.reviews + 1)
    }
}
