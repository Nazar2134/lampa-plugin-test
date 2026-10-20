package com.aireader.app

/**
 * Chooses the few passages of a book an AI request needs, so that the whole book is never sent.
 * Every excerpt keeps its chapter and paragraph so the AI can cite it.
 */
object BookContext {
    data class Excerpt(val chapter: Int, val paragraph: Int, val text: String)

    /** The paragraph at [paragraph] and its neighbours. */
    fun around(text: BookText, chapter: Int, paragraph: Int, before: Int, after: Int, maxChars: Int = 2500): List<Excerpt> {
        val paragraphs = text.chapters.getOrNull(chapter)?.paragraphs ?: return emptyList()
        if (paragraph !in paragraphs.indices) return emptyList()
        val out = ArrayList<Excerpt>()
        var budget = maxChars
        val order = listOf(paragraph) + (1..maxOf(before, after)).flatMap { d -> listOfNotNull((paragraph - d).takeIf { d <= before }, (paragraph + d).takeIf { d <= after }) }
        for (p in order) {
            if (p !in paragraphs.indices) continue
            val t = paragraphs[p]
            if (out.isNotEmpty() && t.length > budget) continue
            out += Excerpt(chapter, p, clip(t, maxOf(budget, 400)))
            budget -= t.length
        }
        return out.sortedBy { it.paragraph }
    }

    /** Text the reader has just read, ending at [paragraph]. */
    fun recent(text: BookText, chapter: Int, paragraph: Int, maxChars: Int = 2000): List<Excerpt> {
        val out = ArrayList<Excerpt>()
        var budget = maxChars
        var c = chapter
        var p = paragraph
        while (c >= 0 && budget > 0) {
            val paragraphs = text.chapters[c].paragraphs
            if (p >= paragraphs.size) p = paragraphs.size - 1
            while (p >= 0 && budget > 0) {
                val t = paragraphs[p]
                out += Excerpt(c, p, if (t.length > budget) "… " + t.takeLast(budget) else t)
                budget -= t.length
                p--
            }
            c--
            if (c >= 0) p = text.chapters[c].paragraphs.size - 1
        }
        return out.reversed()
    }

    /**
     * Passages relevant to [query] from the part of the book already read (up to [until]), skipping [exclude]d
     * ones. Each hit brings its neighbouring paragraphs, because answers often sit next to the paragraph that
     * names the person or thing asked about ("Jonas wrote…" / "He had become a clockmaker…"). Results come back
     * in reading order.
     */
    fun retrieve(
        text: BookText,
        index: BookIndex,
        query: String,
        until: Pair<Int, Int>,
        exclude: Set<Pair<Int, Int>> = emptySet(),
        maxChars: Int = 6000,
        limit: Int = 6,
    ): List<Excerpt> {
        val out = LinkedHashMap<Pair<Int, Int>, Excerpt>()
        var budget = maxChars
        fun readable(c: Int, p: Int) = c < until.first || (c == until.first && p <= until.second)
        fun add(c: Int, p: Int, max: Int): Boolean {
            val paragraphs = text.chapters[c].paragraphs
            if (p !in paragraphs.indices || (c to p) in out || (c to p) in exclude || !readable(c, p)) return false
            val t = clip(paragraphs[p], minOf(max, budget))
            if (t.length < 20 && paragraphs[p].length >= 20) return false
            out[c to p] = Excerpt(c, p, t)
            budget -= t.length
            return true
        }
        var hits = 0
        for (hit in index.search(query, limit = limit * 3, until = until)) {
            if (hits >= limit || budget <= 200) break
            if (!add(hit.chapter, hit.paragraph, 900) && (hit.chapter to hit.paragraph) !in out) continue
            hits++
            add(hit.chapter, hit.paragraph + 1, 600)
            add(hit.chapter, hit.paragraph - 1, 400)
        }
        return out.values.sortedWith(compareBy({ it.chapter }, { it.paragraph }))
    }

    /** Excerpts as labelled text; consecutive paragraphs share one label with their range, e.g. "¶3–5". */
    fun format(text: BookText, excerpts: List<Excerpt>): String {
        val runs = ArrayList<MutableList<Excerpt>>()
        for (e in excerpts) {
            val run = runs.lastOrNull()
            if (run != null && run.last().chapter == e.chapter && run.last().paragraph + 1 == e.paragraph) run += e else runs += mutableListOf(e)
        }
        return runs.joinToString("\n\n") { run -> label(text, run.first(), run.last()) + "\n" + run.joinToString("\n") { it.text } }
    }

    fun label(text: BookText, first: Excerpt, last: Excerpt = first): String {
        val title = text.chapters.getOrNull(first.chapter)?.title?.takeIf { it.isNotBlank() }
        val paragraphs = if (last.paragraph > first.paragraph) "¶${first.paragraph + 1}–${last.paragraph + 1}" else "¶${first.paragraph + 1}"
        return "[Chapter ${first.chapter + 1}" + (title?.let { " “$it”" } ?: "") + ", $paragraphs]"
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max).substringBeforeLast(' ') + " …"
}
