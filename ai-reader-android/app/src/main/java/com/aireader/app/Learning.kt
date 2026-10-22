package com.aireader.app

import android.content.Context
import org.json.JSONObject

object WordStatus {
    const val NEW = 0
    const val LEARNING = 1
    const val KNOWN = 2
}

/** What a tapped word means, as returned by the AI and cached per word form. */
data class Gloss(
    val word: String,
    /** The word, or the whole unit it belongs to here: a phrasal verb, idiom or German separable verb. */
    val expression: String,
    val lemma: String,
    val pos: String,
    /** German nouns: der, die or das. */
    val article: String,
    val plural: String,
    /** Principal parts of verbs, e.g. "geht, ging, ist gegangen". */
    val forms: String,
    val translation: String,
    val inContext: String,
    val note: String,
    /** The sentence this was looked up in; a cached look-up may come from a different one. */
    val sentence: String = "",
) {
    /** The heading: the article and dictionary form for German nouns, else the expression or the word. */
    val headword: String
        get() = when {
            article.isNotBlank() && lemma.isNotBlank() -> "$article $lemma"
            expression.isNotBlank() && !expression.equals(word, ignoreCase = true) -> expression
            lemma.isNotBlank() -> lemma
            else -> word
        }

    companion object {
        /** Reads the JSON object in an AI reply, tolerating code fences or text around it. */
        fun parse(word: String, reply: String): Gloss? {
            val o = Vocabulary.parseCard(reply) ?: return null
            fun f(name: String) = o.optString(name).trim().let { if (it == "null") "" else it }
            val g = Gloss(word, f("expression"), f("lemma"), f("pos"), f("article").lowercase().takeIf { it in setOf("der", "die", "das") } ?: "",
                f("plural"), f("forms"), f("translation"), f("in_context"), f("note"), f("sentence"))
            return if (g.translation.isBlank() && g.inContext.isBlank()) null else g
        }
    }

    fun toJson(): String = JSONObject().put("word", word).put("expression", expression).put("lemma", lemma).put("pos", pos)
        .put("article", article).put("plural", plural).put("forms", forms).put("translation", translation)
        .put("in_context", inContext).put("note", note).put("sentence", sentence).toString()
}

/**
 * Word statuses for a language, with inflected forms grouped under their dictionary form once it is known
 * ("Häuser" → "haus"): a form's own status wins, otherwise its lemma's.
 */
class WordStore(private val db: ReaderDb, val lang: String) {
    private val statuses: MutableMap<String, Int> = db.wordStatuses(lang).toMutableMap()
    private val lemmas: MutableMap<String, String> = db.lemmas(lang).toMutableMap()

    @Synchronized
    fun status(form: String): Int {
        val f = normalize(form)
        return statuses[f] ?: lemmas[f]?.let { statuses[it] } ?: WordStatus.NEW
    }

    /** Every form that isn't new, including looked-up forms whose dictionary form has a status, for the reader page. */
    @Synchronized
    fun pageStatuses(): Map<String, Int> {
        val out = HashMap(statuses)
        for ((form, lemma) in lemmas) if (form !in out) statuses[lemma]?.let { out[form] = it }
        return out
    }

    /** Sets the status of a form and, when known, of its dictionary form (so other forms follow). */
    @Synchronized
    fun setStatus(form: String, status: Int, lemma: String? = null) {
        val f = normalize(form)
        val keys = listOfNotNull(f, (lemma?.takeIf { it.isNotBlank() } ?: lemmas[f])?.let { normalize(it) }?.takeIf { it.isNotBlank() }).distinct()
        db.setWordStatus(lang, keys, status)
        for (k in keys) if (status == WordStatus.NEW) statuses.remove(k) else statuses[k] = status
    }

    @Synchronized
    fun setStatuses(forms: Collection<String>, status: Int) {
        val keys = forms.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        db.setWordStatus(lang, keys, status)
        for (k in keys) if (status == WordStatus.NEW) statuses.remove(k) else statuses[k] = status
    }

    fun cachedGloss(form: String): Gloss? = db.lexicon(lang, normalize(form))?.let { Gloss.parse(form, it) }

    @Synchronized
    fun saveGloss(form: String, gloss: Gloss) {
        val f = normalize(form)
        // Multi-word expressions ("fing … an") aren't a dictionary form for grouping.
        val lemma = normalize(gloss.lemma).takeIf { ' ' !in it && '…' !in it }.orEmpty()
        db.saveLexicon(lang, f, lemma, gloss.toJson())
        if (lemma.isNotBlank() && lemma != f) lemmas[f] = lemma
    }

    companion object {
        fun normalize(form: String) = form.trim().trim('\'', '’', '-').lowercase()

        @Volatile private var cached: WordStore? = null

        fun get(context: Context, lang: String): WordStore {
            cached?.takeIf { it.lang == lang }?.let { return it }
            return WordStore(ReaderDb.get(context), lang).also { cached = it }
        }

        /** A word reviewed to "known" in the vocabulary is known wherever it was being learnt. */
        fun learntInVocabulary(context: Context, word: String) {
            val form = normalize(word)
            if (form.isEmpty() || ' ' in form) return
            ReaderDb.get(context).promoteLearningWord(form)
            cached = null
        }

        /** Tests reset the database between runs. */
        internal fun reset() { cached = null }
    }
}

object LanguageGuess {
    private val german = setOf("der", "die", "und", "ich", "nicht", "das", "ist", "zu", "sie", "es", "ein", "mit", "den", "auf", "sich", "dem", "auch", "war")
    private val english = setOf("the", "and", "of", "to", "a", "i", "is", "was", "he", "you", "it", "that", "in", "her", "his", "with", "had", "she")
    private val ukrainian = setOf("і", "в", "на", "не", "що", "з", "та", "я", "він", "вона", "це", "як", "до", "у", "його", "але")

    /** The two-letter code in the book's metadata ("de-DE" → "de"), or "". */
    fun ofMetadata(metadata: String?): String =
        metadata?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }.orEmpty()

    /** "de", "en" or "uk" from the book's metadata, or else from the words of its text; "" if unclear. */
    fun of(metadata: String?, text: BookText?): String {
        ofMetadata(metadata).takeIf { it.isNotEmpty() }?.let { return it }
        val words = text?.chapters?.asSequence()?.flatMap { it.paragraphs.asSequence() }?.flatMap { BookIndex.tokenize(it).asSequence() }?.take(4000)?.toList()
            ?: return ""
        return ofWords(words)
    }

    /** Guesses from some plain text, such as one chapter. */
    fun ofText(text: String): String = ofWords(BookIndex.tokenize(text).take(4000))

    private fun ofWords(words: List<String>): String {
        val scores = mapOf("de" to words.count { it in german }, "en" to words.count { it in english }, "uk" to words.count { it in ukrainian })
        val best = scores.maxBy { it.value }
        return if (best.value >= 5) best.key else ""
    }
}
