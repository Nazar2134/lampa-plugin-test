package com.aireader.app

import kotlin.math.ln

/** A book's plain text, split into chapters and paragraphs. Used for search, progress and AI context. */
class BookText(val chapters: List<Chapter>) {
    class Chapter(val title: String, val paragraphs: List<String>) {
        val length = paragraphs.sumOf { it.length + 1 }
    }

    /** Characters before each chapter, for progress that reflects how long chapters are. */
    private val starts = IntArray(chapters.size + 1).also { s ->
        chapters.forEachIndexed { i, c -> s[i + 1] = s[i] + c.length }
    }
    val totalLength get() = starts.last()

    fun progress(chapter: Int, fraction: Float): Float {
        if (totalLength == 0 || chapter !in chapters.indices) return 0f
        return ((starts[chapter] + fraction.coerceIn(0f, 1f) * chapters[chapter].length) / totalLength).coerceIn(0f, 1f)
    }

    /** The paragraph at roughly [fraction] of a chapter. */
    fun paragraphAt(chapter: Int, fraction: Float): Int {
        val paragraphs = chapters.getOrNull(chapter)?.paragraphs ?: return 0
        val target = fraction.coerceIn(0f, 1f) * chapters[chapter].length
        var seen = 0
        paragraphs.forEachIndexed { i, p ->
            seen += p.length + 1
            if (seen >= target) return i
        }
        return (paragraphs.size - 1).coerceAtLeast(0)
    }

    /** The paragraph containing [text], preferring the occurrence closest to [near]. */
    fun findParagraph(chapter: Int, text: String, near: Int): Int? {
        val paragraphs = chapters.getOrNull(chapter)?.paragraphs ?: return null
        val needle = TextExtractor.normalize(text).lowercase()
        if (needle.isEmpty()) return null
        return paragraphs.indices
            .filter { needle in paragraphs[it].lowercase() }
            .minByOrNull { kotlin.math.abs(it - near) }
    }

    companion object {
        fun from(book: BookContent): BookText {
            val chapters = (0 until book.chapterCount).map { i ->
                val path = Paths.decode(book.chapterUrl(i).removePrefix(Paths.ORIGIN))
                val html = book.load(path)?.let { String(it.bytes, Charsets.UTF_8) }.orEmpty()
                Chapter(book.chapterTitle(i), TextExtractor.paragraphs(html))
            }
            return BookText(chapters)
        }
    }
}

/** Plain text from (X)HTML, one string per block element. Whitespace is normalized the same way reader.js does it. */
object TextExtractor {
    private val dropped = Regex("(?is)<(head|script|style|svg|title)\\b.*?</\\1\\s*>|<!--.*?-->")
    private val blockBoundary = Regex(
        "(?i)</?(p|div|h[1-6]|li|ul|ol|blockquote|br|tr|section|article|aside|header|footer|figure|figcaption|dd|dt|pre|table|hr|nav)\\b[^>]*>"
    )
    private val tag = Regex("<[^>]*>")
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "shy" to "",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "copy" to "©", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )
    private val spaces = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    fun normalize(s: String) = s.replace("­", "").replace(spaces, " ").trim()

    fun paragraphs(html: String): List<String> {
        val body = html.replace(dropped, " ").replace(blockBoundary, "\n").replace(tag, "")
        return decode(body).split('\n').map { normalize(it) }.filter { it.isNotEmpty() }
    }

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }
}

/**
 * Full-text search over a book's paragraphs with BM25 ranking. Words are matched on a short stem (their first
 * few letters) so that inflected forms in Ukrainian or German still match ("Schuppen"/"Schuppens", "бабуся"/"бабусі").
 */
class BookIndex(private val text: BookText) {
    data class Hit(val chapter: Int, val paragraph: Int, val score: Double)

    private val chapterOf: IntArray
    private val paragraphOf: IntArray
    private val lengths: IntArray
    private val postings = HashMap<String, MutableList<IntArray>>() // stem -> [doc, count]
    private val averageLength: Double

    init {
        val docs = text.chapters.flatMapIndexed { c, ch -> ch.paragraphs.indices.map { c to it } }
        chapterOf = IntArray(docs.size) { docs[it].first }
        paragraphOf = IntArray(docs.size) { docs[it].second }
        lengths = IntArray(docs.size)
        docs.forEachIndexed { doc, (c, p) ->
            val counts = HashMap<String, Int>()
            val words = tokenize(text.chapters[c].paragraphs[p])
            lengths[doc] = words.size
            for (w in words) counts[stem(w)] = (counts[stem(w)] ?: 0) + 1
            for ((stem, n) in counts) postings.getOrPut(stem) { ArrayList() }.add(intArrayOf(doc, n))
        }
        averageLength = if (docs.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
    }

    /**
     * Paragraphs ranked by relevance to [query]. Only paragraphs before [until] (chapter, paragraph) are
     * considered when it is given, so answers can't draw on parts of the book the reader hasn't reached.
     */
    fun search(query: String, limit: Int = 8, until: Pair<Int, Int>? = null): List<Hit> {
        val stems = tokenize(query).filter { it !in stopWords }.map { stem(it) }.distinct()
        if (stems.isEmpty()) return emptyList()
        val n = lengths.size.toDouble()
        val scores = HashMap<Int, Double>()
        val matched = HashMap<Int, Int>()
        for (s in stems) {
            val list = postings[s] ?: continue
            val idf = ln(1 + (n - list.size + 0.5) / (list.size + 0.5))
            for ((doc, tf) in list.map { it[0] to it[1] }) {
                if (until != null && (chapterOf[doc] > until.first || (chapterOf[doc] == until.first && paragraphOf[doc] > until.second))) continue
                val norm = tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths[doc] / averageLength))
                scores[doc] = (scores[doc] ?: 0.0) + idf * norm
                matched[doc] = (matched[doc] ?: 0) + 1
            }
        }
        // Paragraphs that match more of the question's words come first.
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { matched[it.key] ?: 0 }.thenByDescending { it.value })
            .take(limit)
            .map { Hit(chapterOf[it.key], paragraphOf[it.key], it.value) }
    }

    data class Match(val chapter: Int, val paragraph: Int, val start: Int, val text: String, val occurrence: Int)

    /**
     * Literal, case-insensitive search for the in-book search screen. [Match.occurrence] counts earlier matches
     * of the same exact text in the chapter, which is how reader.js finds the spot again.
     */
    fun find(query: String, limit: Int = 300): List<Match> {
        val needle = TextExtractor.normalize(query).lowercase()
        if (needle.length < 2) return emptyList()
        val out = ArrayList<Match>()
        text.chapters.forEachIndexed { c, ch ->
            val seen = HashMap<String, Int>()
            ch.paragraphs.forEachIndexed { p, para ->
                val lower = para.lowercase()
                var from = 0
                while (out.size < limit) {
                    val i = lower.indexOf(needle, from)
                    if (i < 0) break
                    val exact = para.substring(i, i + needle.length)
                    // Earlier occurrences of the exact text, including inside other paragraphs of this chapter.
                    val occurrence = seen[exact] ?: countBefore(ch.paragraphs, p, exact)
                    seen[exact] = occurrence + 1
                    out += Match(c, p, i, exact, occurrence)
                    from = i + needle.length
                }
            }
        }
        return out
    }

    private fun countBefore(paragraphs: List<String>, upTo: Int, exact: String): Int {
        var n = 0
        for (p in 0 until upTo) {
            var from = 0
            while (true) {
                val i = paragraphs[p].indexOf(exact, from)
                if (i < 0) break
                n++
                from = i + exact.length
            }
        }
        return n
    }

    companion object {
        private val word = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

        fun tokenize(s: String): List<String> =
            word.findAll(s.lowercase())
                .map { it.value.trim('\'', '’', '-').removeSuffix("'s").removeSuffix("’s") }
                .filter { it.length >= 2 }.toList()

        /**
         * A crude, language-neutral stem: a fixed-length prefix, so that inflected forms share it
         * ("schuppen"/"schuppens", "бабуся"/"бабусі"/"бабусею", "letter"/"letters", "key"/"keys").
         */
        fun stem(w: String): String = when {
            w.length >= 6 -> w.take(5)
            w.length == 5 -> w.take(4)
            w.length == 4 && w.endsWith('s') -> w.take(3)
            else -> w
        }

        private val stopWords = setOf(
            // English
            "the", "and", "or", "of", "to", "in", "on", "at", "is", "are", "was", "were", "be", "been", "it", "its", "this",
            "that", "what", "who", "whom", "why", "how", "when", "where", "which", "did", "does", "do", "has", "have", "had",
            "he", "she", "they", "them", "his", "her", "their", "a", "an", "for", "with", "from", "by", "as", "about", "into",
            "me", "my", "you", "your", "we", "our", "so", "not", "no", "can", "could", "would", "should", "will", "there",
            "book", "chapter", "happened", "happen", "earlier", "before", "after", "again", "tell", "explain",
            // German
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "und", "oder", "ist", "sind", "war",
            "waren", "hat", "hatte", "wer", "was", "wie", "warum", "wann", "wo", "mit", "von", "zu", "im", "auf", "für", "nicht",
            "sie", "er", "es", "ich", "du", "wir", "ihr", "sich", "dass", "als", "auch", "noch",
            // Ukrainian
            "і", "й", "та", "або", "що", "хто", "як", "чому", "коли", "де", "це", "той", "та", "він", "вона", "воно", "вони",
            "був", "була", "було", "були", "є", "в", "у", "на", "з", "із", "до", "по", "за", "про", "від", "не", "так", "ще",
            "його", "її", "їх", "мене", "мені", "розділі", "книзі", "сталося",
        )
    }
}
