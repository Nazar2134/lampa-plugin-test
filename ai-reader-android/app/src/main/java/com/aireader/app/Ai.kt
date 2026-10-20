package com.aireader.app

import java.io.IOException
import java.net.HttpURLConnection

data class Turn(val fromUser: Boolean, val text: String)

enum class AiProvider { GEMINI, DEEPSEEK }

enum class AiOutcome { FINISHED, BLOCKED, TRUNCATED }

/** How a reply ended, and the tokens the provider reported for it (0 when not reported). */
data class AiResult(val outcome: AiOutcome, val inputTokens: Int = 0, val outputTokens: Int = 0)

/** An HTTP error from an AI provider, with the provider's own status string and message. */
class ApiException(val code: Int, val status: String, message: String, val retryAfterSeconds: Int? = null) : IOException(message)

internal fun HttpURLConnection.retryAfter(): Int? = getHeaderField("Retry-After")?.trim()?.toIntOrNull()

/** Sends a conversation to whichever provider the user picked in Settings. */
object Ai {
    fun key(s: AppSettings) = when (s.provider) {
        AiProvider.GEMINI -> s.geminiKey
        AiProvider.DEEPSEEK -> s.deepseekKey
    }

    fun name(provider: AiProvider) = when (provider) {
        AiProvider.GEMINI -> "Gemini"
        AiProvider.DEEPSEEK -> "DeepSeek"
    }

    fun model(s: AppSettings) = when (s.provider) {
        AiProvider.GEMINI -> s.geminiModel
        AiProvider.DEEPSEEK -> s.deepseekModel
    }

    private val retryable = setOf(429, 500, 502, 503, 504)

    /** Seconds to wait before the single retry; tests set this to 0. */
    internal var retryDelayCap = 8

    /**
     * Streams a reply from the chosen provider. A rate limit, server error or timeout that happens before
     * any text arrives is retried once, after the provider's Retry-After (capped) or 2 seconds.
     */
    fun stream(
        s: AppSettings,
        system: String,
        history: List<Turn>,
        onConnect: (HttpURLConnection) -> Unit,
        isCancelled: () -> Boolean = { false },
        onText: (String) -> Unit,
    ): AiResult {
        var emitted = false
        val forward: (String) -> Unit = { emitted = true; onText(it) }
        for (attempt in 0..1) {
            try {
                return when (s.provider) {
                    AiProvider.GEMINI -> Gemini.stream(s.geminiKey, s.geminiModel, system, history, onConnect, forward)
                    AiProvider.DEEPSEEK -> DeepSeek.stream(s.deepseekKey, s.deepseekModel, system, history, onConnect, forward)
                }
            } catch (e: IOException) {
                val canRetry = attempt == 0 && !emitted && !isCancelled() &&
                    ((e is ApiException && e.code in retryable) || e is java.net.SocketTimeoutException)
                if (!canRetry) throw e
                val wait = ((e as? ApiException)?.retryAfterSeconds ?: 2).coerceIn(0, retryDelayCap)
                Thread.sleep(wait * 1000L)
                if (isCancelled()) throw e
            }
        }
        error("unreachable")
    }

    fun describe(provider: AiProvider, e: Throwable) = when (provider) {
        AiProvider.GEMINI -> Gemini.describe(e)
        AiProvider.DEEPSEEK -> DeepSeek.describe(e)
    }
}
