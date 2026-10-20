package com.readinghelper.app

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** One message in the conversation shown in the popup. */
data class Turn(val fromUser: Boolean, val text: String)

object Claude {
    const val MODEL_OPUS = "claude-opus-5-5"
    const val MODEL_SONNET = "claude-sonnet-5-5"
    const val MODEL_HAIKU = "claude-haiku-5-5"

    // Re-runs a request on Anthropic's recommended model if a safety classifier
    // declines it. Not offered for Haiku, so it is only sent for Opus and Sonnet.
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /** Points the client at a local server in tests. */
    internal var baseUrl: String? = null

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun clientFor(apiKey: String): AnthropicClient {
        val key = "$apiKey|$baseUrl"
        val existing = client
        if (existing != null && clientKey == key) return existing
        existing?.close()
        val builder = AnthropicOkHttpClient.builder().apiKey(apiKey)
        baseUrl?.let { builder.baseUrl(it) }
        return builder.build().also {
            client = it
            clientKey = key
        }
    }

    private fun systemPrompt(language: String, detailed: Boolean): String {
        val length = if (detailed) {
            "Be thorough, but keep it organized so it is easy to scan on a phone."
        } else {
            "Keep it short: the reader should be able to take it in within about 30 seconds and get back to the book."
        }
        return """
            You help someone who is reading a book in a foreign language on their phone. They selected a word or passage in their e-reader because they want to understand it.

            Write your whole answer in $language. Work out the source language yourself.

            For a single word or short expression: give its meaning in this context (and other common meanings if they differ), its dictionary form, part of speech, and the grammatical details that explain the form on the page, such as gender, case, number, tense, mood or conjugation. Add one short example sentence with its translation.

            For a phrase or sentence: start with a natural translation. Then explain the grammar and constructions a learner is likely to stumble on (tenses and moods, cases, word order, separable verbs, idioms and set phrases), and briefly gloss uncommon words.

            If the text is already in $language, explain what it means and any difficult words or grammar instead of translating it.

            $length

            Formatting: the answer is shown in a small popup that understands only a little Markdown. You may use **bold**, lines starting with "## " for short headings, and "- " bullet lists. Do not use tables, code blocks or links. Follow-up questions refer to the same selected text.
        """.trimIndent()
    }

    sealed interface Outcome {
        data object Finished : Outcome
        data object Refused : Outcome
        data object Truncated : Outcome
    }

    /**
     * Streams an answer for [history], calling [onText] for each text delta.
     * Blocking: call from a background thread. [onOpen] receives the stream so
     * the caller can close it to cancel.
     */
    fun stream(
        settings: Settings,
        history: List<Turn>,
        onOpen: (StreamResponse<*>) -> Unit,
        onText: (String) -> Unit,
    ): Outcome {
        val params = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(16000L)
            .system(systemPrompt(settings.language, settings.detailed))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (settings.detailed) BetaOutputConfig.Effort.MEDIUM else BetaOutputConfig.Effort.LOW)
                    .build()
            )
            .apply {
                history.forEachIndexed { i, turn ->
                    when {
                        i == 0 -> addUserMessage("Selected text:\n\n<selected_text>\n${turn.text}\n</selected_text>")
                        turn.fromUser -> addUserMessage(turn.text)
                        else -> addAssistantMessage(turn.text)
                    }
                }
                if (settings.model != MODEL_HAIKU) {
                    addBeta(FALLBACK_BETA)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        var stopReason: BetaStopReason? = null
        clientFor(settings.apiKey).beta().messages().createStreaming(params).use { response ->
            onOpen(response)
            response.stream().forEach { event ->
                event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onText(it.text()) }
                event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { stopReason = it }
            }
        }
        return when (stopReason) {
            BetaStopReason.REFUSAL -> Outcome.Refused
            BetaStopReason.MAX_TOKENS -> Outcome.Truncated
            else -> Outcome.Finished
        }
    }
}
