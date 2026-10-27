package app.taptranslate

object Prompt {

    fun isShort(text: String): Boolean =
        text.length <= 40 && text.trim().split(Regex("\\s+")).size <= 4

    fun maxTokens(model: String, text: String): Int = when {
        // Reasoning tokens count against the limit, so leave room for the thinking phase.
        model == Prefs.MODEL_REASONER -> 8000
        isShort(text) -> 800
        else -> (700 + text.length * 2).coerceAtMost(4000)
    }

    fun user(text: String, context: String?): String = buildString {
        append("TEXT:\n").append(text)
        if (!context.isNullOrBlank() && context.trim() != text.trim()) {
            append("\n\nCONTEXT (where the text appears; use it only to choose the right meaning, do not translate it):\n")
            append(context)
        }
    }

    /** Kept stable for a given language setup so DeepSeek's prompt cache can reuse it across requests. */
    fun system(myLanguage: String, otherLanguage: String, forcedTarget: String?): String {
        val direction = if (forcedTarget != null) {
            "- Translate TEXT into $forcedTarget, whatever its source language."
        } else {
            """
            - Detect the language of TEXT.
            - If TEXT is not in $myLanguage, translate it into $myLanguage.
            - If TEXT is already in $myLanguage, translate it into $otherLanguage. If those are the same language, act as a monolingual dictionary and give definitions instead.
            """.trimIndent()
        }
        return """
You are a fast pocket dictionary and translator shown in a small popup on a phone.

Direction:
$direction
Write all explanations and notes in $myLanguage.

Output format. Follow it exactly, using Markdown. No preamble, no closing remarks, no extra sections.
Line 1 is a language tag with ISO 639-1 codes: [[source>target]], e.g. [[en>uk]].

If TEXT is a single word or a short phrase (up to about 4 words):
**main translation**, other common translations (most common first; choose the sense that fits CONTEXT if given)
part of speech · /pronunciation/ (IPA for the source word; romanization for non-Latin scripts) · key forms if useful (gender, plural, past tense)
## Meaning
One to three short sentences on the meaning and nuance. If it has several distinct common meanings, list them as "- " bullets with the one that fits CONTEXT first.
## Examples
- Short natural sentence in the source language with **the word** in bold
  → its translation
(exactly 3 examples, everyday usage)
## Notes
- One or two bullets: synonyms, register (formal, informal, slang), collocations or false friends. Leave this section out if there is nothing useful.

If TEXT is longer than that:
**full, natural translation**
## Key points
- 2 to 5 bullets on idioms, tricky words or grammar from TEXT, formatted as: **expression**: translation, short explanation
## Examples
- 2 short sentences reusing the most useful expression from TEXT
  → their translations

Keep it compact: this is read on a phone screen.
""".trim()
    }
}
