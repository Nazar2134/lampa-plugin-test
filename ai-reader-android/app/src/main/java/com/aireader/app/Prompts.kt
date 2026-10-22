package com.aireader.app

enum class AiAction(val label: String) {
    EXPLAIN("Explain"),
    TRANSLATE("Translate"),
    VOCABULARY("Vocabulary"),
    GRAMMAR("Grammar"),
    SIMPLIFY("Simplify"),
    ASK("Ask"),
    CHAT("Chat");

    /** One-shot actions on a selection; their answers can be cached. */
    val isSelectionAction get() = this != ASK && this != CHAT
}

data class BookInfo(
    val title: String,
    val author: String,
    val languageName: String?,
    /** How far the reader has got, e.g. "chapter 4 (“The Shed”)". */
    val readingPoint: String? = null,
)

/** What an AI request is about: the selection, its sentence, and labelled excerpts from the book. */
data class AiRequest(
    val action: AiAction,
    val selection: String = "",
    val sentence: String = "",
    /** Labelled excerpts around the selection or the current page. */
    val context: String = "",
    /** Labelled earlier passages relevant to the question. */
    val related: String = "",
    val question: String? = null,
)

object Prompts {
    val languages = listOf("English", "Ukrainian", "German")
    val cefrLevels = listOf("A1", "A2", "B1", "B2", "C1", "C2")

    fun system(book: BookInfo, s: AppSettings): String {
        val by = if (book.author.isNotBlank()) " by ${book.author}" else ""
        val lang = book.languageName?.let { ", written in $it" } ?: ""
        val point = book.readingPoint?.let {
            " They have read up to $it. Don't reveal what happens later in the book unless they explicitly ask for spoilers."
        } ?: ""
        val length = if (s.detailed) "Be thorough, but organized so it's easy to scan on a phone." else "Keep answers short and focused unless the user asks for more."
        return """
            You are a reading companion built into an e-book reader app. The user is reading "${book.title}"$by$lang on their phone and wants clear, accurate help so they can get back to the book.$point

            Always answer in ${s.language}, unless the user asks for another language.

            The app sends you excerpts from the book, labelled like [Chapter 3 “Title”, ¶12]. They are the only parts of the book you can see.
            - When you state what the book says, base it on the excerpts and say where it comes from, e.g. "(Ch. 3)".
            - Mark interpretations as interpretations ("this suggests…", "probably…").
            - If the excerpts don't settle a question, say so plainly instead of guessing, and suggest what would help (for example, selecting the passage in question).
            - Quote only words that appear in the excerpts, exactly as written. Never invent quotations, events, names or details.
            - General knowledge of language, history and culture is welcome. Anything about this particular book that comes from memory rather than the excerpts must be labelled as uncertain.

            $length

            Formatting: answers appear in a small panel that understands only a little Markdown. You may use **bold**, lines starting with "## " for short headings, and "- " bullet lists. No tables, code blocks or links.
        """.trimIndent()
    }

    private fun isShort(selection: String) = BookIndex.tokenize(selection).size <= 4

    /** The first user message of a conversation. */
    fun first(r: AiRequest, s: AppSettings, bookLanguage: String?): String {
        val parts = ArrayList<String>()
        if (r.selection.isNotBlank()) parts += "<selection>\n${r.selection}\n</selection>"
        if (r.sentence.isNotBlank() && r.sentence != r.selection) parts += "<sentence>\n${r.sentence}\n</sentence>"
        if (r.context.isNotBlank()) parts += "<book_context>\n${r.context}\n</book_context>"
        if (r.related.isNotBlank()) parts += "<earlier_passages>\n${r.related}\n</earlier_passages>"
        val material = parts.joinToString("\n\n")
        val original = bookLanguage ?: "the language of the original"
        val task = when (r.action) {
            AiAction.EXPLAIN ->
                "Explain the selected text so I understand what it means here: the literal meaning, anything implied, and any references, wordplay or difficult words. " +
                    "Use the book context. If the selection is not in ${s.language}, start with a short translation."

            AiAction.TRANSLATE ->
                "Translate the selected text into ${s.language}. Keep its meaning, tone, register and literary style; render idioms with natural ${s.language} equivalents rather than word for word. " +
                    "Translate only the selection; the rest is context. Give the translation first, then at most two short notes if something has no direct equivalent (an idiom, a pun, a culturally specific word)."

            AiAction.VOCABULARY ->
                if (isShort(r.selection)) {
                    "Help me learn the selected word or expression. Use these headings:\n" +
                        "## In this passage — what it means here, in ${s.language}\n" +
                        "## Translation — ${s.language} translations that fit this context\n" +
                        "## Dictionary form — lemma and part of speech, plus gender/plural or principal parts where relevant\n" +
                        "## Other meanings — general dictionary meanings that differ from this one (skip if none)\n" +
                        "## Examples — two short example sentences in $original with translations\n" +
                        "## Synonyms — only if useful\n" +
                        "## Usage — register (formal, colloquial, literary, archaic, regional…) and common collocations"
                } else {
                    "List the 3–6 words or expressions in the selection that a learner is least likely to know. For each: the dictionary form, its meaning in this passage, and a ${s.language} translation. Note any that are idiomatic, literary or archaic."
                }

            AiAction.GRAMMAR ->
                "Explain the grammar of the selected text. Give a one-line ${s.language} translation first. Then cover what matters here: verb forms (tense, mood, voice, aspect), cases and agreement, " +
                    "word order and clause structure, and idioms or fixed expressions. Break the sentence into its parts where that helps."

            AiAction.SIMPLIFY ->
                "Rewrite the selected text in simpler $original, at about CEFR level ${s.cefr}, keeping its essential meaning. Use vocabulary and grammar suitable for ${s.cefr} " +
                    "and don't add information. Then give a one-line ${s.language} translation of your simpler version."

            AiAction.ASK -> "My question about the selected text: ${r.question.orEmpty()}"
            AiAction.CHAT -> r.question.orEmpty()
        }
        return if (material.isEmpty()) task else "$material\n\n$task"
    }

    /** A follow-up question; fresh excerpts relevant to it are attached when there are any. */
    fun followUp(question: String, related: String): String =
        if (related.isBlank()) question else "<earlier_passages>\n$related\n</earlier_passages>\n\n$question"

    /** Asks for a compact JSON card used to fill a vocabulary entry. */
    fun vocabularyCard(word: String, sentence: String, s: AppSettings): String = """
        Return only a JSON object, with no other text, for this word or expression as used in the sentence:
        {"translation": "${s.language} translation that fits this sentence, a few words", "lemma": "dictionary form", "part_of_speech": "…", "meaning": "one sentence in ${s.language} on what it means here"}

        Word: $word
        Sentence: $sentence
    """.trimIndent()

    /** Asks for a compact JSON description of a tapped word, in context, for the learning-mode card. */
    fun gloss(word: String, sentence: String, bookLanguage: String?, s: AppSettings): String = """
        A reader learning ${bookLanguage ?: "this language"} tapped a word in a book. Describe it as used in the sentence. Reply with only a JSON object, no other text:
        {"expression": "the word, or the whole unit it belongs to in this sentence: a phrasal verb, an idiom, or a German separable verb with its prefix (e.g. \"anfangen\" for \"fing … an\")",
         "lemma": "dictionary form",
         "pos": "part of speech, in ${s.language}",
         "article": "der, die or das for German nouns, otherwise empty",
         "plural": "plural of nouns (German with its article, e.g. \"die Häuser\"), otherwise empty",
         "forms": "principal parts of verbs (German: \"geht, ging, ist gegangen\"; English irregular verbs: \"go, went, gone\"), otherwise empty",
         "translation": "1–3 short ${s.language} translations of the dictionary meaning",
         "in_context": "what it means in this sentence, in ${s.language}, a few words",
         "note": "one short ${s.language} note only if useful (false friend, register, case it governs, separable verb), otherwise empty"}

        Word: $word
        Sentence: $sentence
    """.trimIndent()

    /** A self-contained message for the Claude or ChatGPT app, which knows nothing about the book. */
    fun handoff(book: BookInfo, s: AppSettings, r: AiRequest, pageText: String): String {
        val reading = "I'm reading \"${book.title}\"" + (if (book.author.isNotBlank()) " by ${book.author}" else "") + "."
        val sentence = (r.sentence.ifBlank { r.context }).take(800).let { if (it.isNotBlank() && it != r.selection) "\n\nIt appears here: \"$it\"" else "" }
        val sel = "\"${r.selection}\""
        val answerIn = "\n\nPlease answer in ${s.language}."
        val ask = r.question?.takeIf { it.isNotBlank() }
        return when {
            r.action == AiAction.CHAT -> buildString {
                append(reading)
                if (pageText.isNotBlank()) append(" Here's the page I'm on:\n\n\"").append(pageText.take(1500)).append("\"")
                append("\n\n").append(ask ?: "Can you help me understand this page?").append(answerIn)
            }
            ask != null -> "$reading About this part: $sel$sentence\n\n$ask$answerIn"
            r.action == AiAction.TRANSLATE -> "$reading Translate this into ${s.language}, keeping its tone and style: $sel$sentence"
            r.action == AiAction.VOCABULARY -> "$reading Explain the word or expression $sel as used here: meaning in this context, translation into ${s.language}, dictionary form, examples and usage.$sentence$answerIn"
            r.action == AiAction.GRAMMAR -> "$reading Explain the grammar of this, for a language learner: $sel$sentence$answerIn"
            r.action == AiAction.SIMPLIFY -> "$reading Rewrite this in simpler language at about CEFR level ${s.cefr}, keeping the meaning: $sel$sentence"
            else -> "$reading Please explain this in ${s.language}: what it means here and anything implied or difficult.\n\n$sel$sentence"
        }
    }
}
