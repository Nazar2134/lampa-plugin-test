package com.aireader.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Google Gemini API over plain HTTPS. The free tier needs only a key from aistudio.google.com. */
object Gemini {
    const val MODEL_FLASH = "gemini-flash-latest"
    const val MODEL_LITE = "gemini-flash-lite-latest"

    /** Points the client at a local server in tests. */
    internal var baseUrl = "https://generativelanguage.googleapis.com"

    /** Streams a reply, calling [onText] for each piece. Blocking. [onConnect] gets the connection so the caller can cancel it. */
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        history: List<Turn>,
        onConnect: (HttpURLConnection) -> Unit,
        onText: (String) -> Unit,
    ): AiResult {
        val conn = URL("$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            onConnect(conn)
            conn.outputStream.use { it.write(requestBody(system, history).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code >= 400) throw apiError(code, conn.errorStream, conn.retryAfter())

            var outcome = AiOutcome.FINISHED
            var inputTokens = 0
            var outputTokens = 0
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val chunk = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                    chunk.optJSONObject("usageMetadata")?.let {
                        inputTokens = it.optInt("promptTokenCount", inputTokens)
                        outputTokens = it.optInt("candidatesTokenCount") + it.optInt("thoughtsTokenCount")
                    }
                    if (chunk.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty()) outcome = AiOutcome.BLOCKED
                    val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            if (part.optBoolean("thought")) continue
                            val text = part.optString("text")
                            if (text.isNotEmpty()) onText(text)
                        }
                    }
                    when (candidate.optString("finishReason")) {
                        "", "STOP", "FINISH_REASON_UNSPECIFIED" -> {}
                        "MAX_TOKENS" -> outcome = AiOutcome.TRUNCATED
                        else -> outcome = AiOutcome.BLOCKED
                    }
                }
            }
            return AiResult(outcome, inputTokens, outputTokens)
        } finally {
            conn.disconnect()
        }
    }

    internal fun requestBody(system: String, history: List<Turn>): String {
        val contents = JSONArray()
        for (turn in history) {
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .toString()
    }

    /** Checks the key against [model]. Returns the model to use: [model] itself, or a replacement if it doesn't exist. */
    fun check(apiKey: String, model: String): String {
        val conn = URL("$baseUrl/v1beta/models/$model").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code == 404) {
                return pickModel(listModels(apiKey), lite = "lite" in model)
                    ?: throw ApiException(404, "NOT_FOUND", "No Gemini Flash model is available for this key")
            }
            if (code >= 400) throw apiError(code, conn.errorStream)
            return model
        } finally {
            conn.disconnect()
        }
    }

    data class ModelInfo(val name: String, val methods: List<String>)

    fun listModels(apiKey: String): List<ModelInfo> {
        val conn = URL("$baseUrl/v1beta/models?pageSize=1000").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code >= 400) throw apiError(code, conn.errorStream)
            val models = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
            return (0 until models.length()).map { i ->
                val m = models.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                ModelInfo(m.getString("name").removePrefix("models/"), (0 until methods.length()).map { methods.getString(it) })
            }
        } finally {
            conn.disconnect()
        }
    }

    private val excluded = listOf("image", "tts", "audio", "live", "embedding", "8b", "exp", "robotics")
    private val version = Regex("""gemini-(\d+)(?:\.(\d+))?""")

    /** Picks the newest general-purpose Flash (or Flash-Lite) model, preferring stable over preview. */
    fun pickModel(models: List<ModelInfo>, lite: Boolean): String? {
        val usable = models.filter { "generateContent" in it.methods }
        val alias = if (lite) MODEL_LITE else MODEL_FLASH
        if (usable.any { it.name == alias }) return alias
        return usable.map { it.name }
            .filter { it.startsWith("gemini-") && "flash" in it && ("lite" in it) == lite && excluded.none { word -> word in it } }
            .sortedWith(
                compareByDescending<String> { name ->
                    version.find(name)?.let { it.groupValues[1].toInt() * 100 + (it.groupValues[2].toIntOrNull() ?: 0) } ?: 0
                }.thenBy { "preview" in it }.thenBy { it.length }
            )
            .firstOrNull()
    }

    private fun apiError(code: Int, body: InputStream?, retryAfter: Int? = null): ApiException {
        val error = runCatching { JSONObject(body!!.bufferedReader().readText()).getJSONObject("error") }.getOrNull()
        return ApiException(code, error?.optString("status").orEmpty(), error?.optString("message").orEmpty().ifEmpty { "HTTP $code" }, retryAfter)
    }

    /** A message for the reader explaining what went wrong and what to do. */
    fun describe(e: Throwable): String = when {
        e is ApiException && (e.code == 400 || e.code == 401 || e.code == 403) && "API key" in e.message.orEmpty() ->
            "Your Gemini key was rejected. Check it in Settings."
        e is ApiException && e.code == 403 ->
            "This key can't use Gemini: ${e.message}"
        e is ApiException && e.code == 429 ->
            "The free Gemini limit is used up for now. Wait a minute (the daily limit resets overnight), switch to Flash-Lite in Settings, or tap Ask Claude or Ask ChatGPT below."
        e is ApiException && e.code == 404 ->
            "That Gemini model isn't available. Pick another in Settings."
        e is ApiException && e.code >= 500 ->
            "Gemini is busy right now. Try again in a moment."
        e is ApiException -> "Gemini error (${e.code}): ${e.message}"
        e is IOException -> "Couldn't reach Gemini. Check your internet connection."
        else -> "Something went wrong: ${e.message}"
    }
}
