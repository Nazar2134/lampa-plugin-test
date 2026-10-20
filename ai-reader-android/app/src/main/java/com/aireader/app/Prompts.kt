package com.aireader.app

enum class AiAction { EXPLAIN, TRANSLATE, ASK, CHAT }

data class BookInfo(val title: String, val author: String, val languageName: String?)

object Prompts {
    fun system(book: BookInfo, s: AppSettings): String {
        val by = if (book.author.isNotBlank()) " by ${book.author}" else ""
        val lang = book.languageName?.let { ", written in $it" } ?: ""
        val length = if (s.detailed) "Be thorough, but organized so it's easy to scan on a phone." else "Keep answers short unless the user asks for more."
        return """
            You are a reading companion built into an e-book reader app. The user is reading "${book.title}"$by$lang on their phone and wants quick, clear help so they can get back to the book.

            Always answer in ${s.language}, whatever language the book or the question is in.

            Base answers on the text the user shares. For questions about the plot or characters that go beyond it, you may use what you know about the book, but don't reveal later events unless the user asks for spoilers.

            $length

            Formatting: answers appear in a small panel that understands only a little Markdown. You may use **bold**, lines starting with "## " for short headings, and "- " bullet lists. No tables, code blocks or links.
        """.trimIndent()
    }

    private fun quoted(selection: String, context: String) = buildString {
        append("<selection>\n").append(selection).append("\n</selection>")
        if (context.isNotBlank()) append("\n<context>\n").append(context).append("\n</context>")
    }

    /** The first user message of a conversation. */
    fun first(action: AiAction, s: AppSettings, selection: String, context: String, pageText: String, question: String?): String = when (action) {
        AiAction.EXPLAIN -> """
            Explain this selection from the book. The context is the paragraph it comes from.

            {QUOTE}

            If it is a single word or short expression: give its meaning here (and other common meanings if they differ), its dictionary form, part of speech, and the grammar of the form used (gender, case, number, tense, mood, aspect, conjugation, as relevant). Add one short example sentence with a translation.
            If it is a phrase or sentence: start with a natural translation, then explain the grammar and constructions a learner would stumble on (tenses, cases, word order, idioms, set phrases), and briefly gloss uncommon words.
            If it is already in ${s.language}, explain its meaning and any difficult words or grammar instead of translating.
        """.trimIndent().replace("{QUOTE}", quoted(selection, context))

        AiAction.TRANSLATE -> """
            Translate the selection into ${s.language}. Use the context only to get the meaning right, and translate just the selection. Reply with the translation, plus at most one short note if something has no direct equivalent.

            {QUOTE}
        """.trimIndent().replace("{QUOTE}", quoted(selection, context))

        AiAction.ASK -> "${quoted(selection, context)}\n\nMy question about this selection: ${question.orEmpty()}"

        AiAction.CHAT ->
            if (pageText.isBlank()) question.orEmpty()
            else "This is the page of the book I'm on right now:\n\n<page>\n$pageText\n</page>\n\n${question.orEmpty()}"
    }

    /** A self-contained message for the Claude or ChatGPT app, which knows nothing about the book. */
    fun handoff(book: BookInfo, s: AppSettings, action: AiAction, selection: String, context: String, pageText: String, question: String?): String {
        val reading = "I'm reading \"${book.title}\"" + (if (book.author.isNotBlank()) " by ${book.author}" else "") + "."
        val ctx = context.take(800).let { if (it.isNotBlank()) "\n\nIt appears in this passage: \"$it\"" else "" }
        val ask = question?.takeIf { it.isNotBlank() }
        return when {
            action == AiAction.CHAT -> buildString {
                append(reading)
                if (pageText.isNotBlank()) append(" Here's the page I'm on:\n\n\"").append(pageText.take(1500)).append("\"")
                append("\n\n").append(ask ?: "Can you help me understand this page?")
                append("\n\nPlease answer in ${s.language}.")
            }
            ask != null -> "$reading About this part: \"$selection\"$ctx\n\n$ask\n\nPlease answer in ${s.language}."
            action == AiAction.TRANSLATE -> "$reading Translate this into ${s.language}: \"$selection\"$ctx"
            else -> "$reading I'm learning the language. Please explain this in ${s.language}: give a natural translation and explain the grammar and any tricky words.\n\n\"$selection\"$ctx"
        }
    }
}
