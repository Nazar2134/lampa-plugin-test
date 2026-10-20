package com.aireader.app

import java.io.IOException
import java.net.HttpURLConnection

data class Turn(val fromUser: Boolean, val text: String)

enum class AiProvider { GEMINI, DEEPSEEK }

enum class AiOutcome { FINISHED, BLOCKED, TRUNCATED }

/** An HTTP error from an AI provider, with the provider's own status string and message. */
class ApiException(val code: Int, val status: String, message: String) : IOException(message)

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

    fun stream(
        s: AppSettings,
        system: String,
        history: List<Turn>,
        onConnect: (HttpURLConnection) -> Unit,
        onText: (String) -> Unit,
    ): AiOutcome = when (s.provider) {
        AiProvider.GEMINI -> Gemini.stream(s.geminiKey, s.geminiModel, system, history, onConnect, onText)
        AiProvider.DEEPSEEK -> DeepSeek.stream(s.deepseekKey, s.deepseekModel, system, history, onConnect, onText)
    }

    fun describe(provider: AiProvider, e: Throwable) = when (provider) {
        AiProvider.GEMINI -> Gemini.describe(e)
        AiProvider.DEEPSEEK -> DeepSeek.describe(e)
    }
}
