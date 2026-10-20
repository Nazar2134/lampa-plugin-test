package com.aireader.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** DeepSeek's OpenAI-style chat completions API, paid per token from the user's DeepSeek balance. */
object DeepSeek {
    const val MODEL_FLASH = "deepseek-flash"
    const val MODEL_PRO = "deepseek-v4-pro"

    /** Points the client at a local server in tests. */
    internal var baseUrl = "https://api.deepseek.com"

    /** Streams a reply, calling [onText] for each piece of the answer (reasoning is skipped). Blocking. */
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        history: List<Turn>,
        onConnect: (HttpURLConnection) -> Unit,
        onText: (String) -> Unit,
    ): AiOutcome {
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            onConnect(conn)
            conn.outputStream.use { it.write(requestBody(model, system, history).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code >= 400) throw apiError(code, conn.errorStream)

            var outcome = AiOutcome.FINISHED
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val choice = runCatching { JSONObject(data).optJSONArray("choices")?.optJSONObject(0) }.getOrNull() ?: continue
                    val content = choice.optJSONObject("delta")?.opt("content")
                    if (content is String && content.isNotEmpty()) onText(content)
                    when (choice.opt("finish_reason")) {
                        "length" -> outcome = AiOutcome.TRUNCATED
                        "content_filter" -> outcome = AiOutcome.BLOCKED
                        "insufficient_system_resource", "aborted" -> throw ApiException(503, "busy", "DeepSeek stopped the answer early")
                    }
                }
            }
            return outcome
        } finally {
            conn.disconnect()
        }
    }

    internal fun requestBody(model: String, system: String, history: List<Turn>): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (turn in history) {
            messages.put(JSONObject().put("role", if (turn.fromUser) "user" else "assistant").put("content", turn.text))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
        // Flash answers straight away. Pro thinks briefly first, which is slower but more careful.
        if (model == MODEL_PRO) {
            body.put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "low")
        } else {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        return body.toString()
    }

    data class CheckResult(val model: String, val balance: String?)

    /** Validates the key and model; also reads the account balance when DeepSeek reports it. */
    fun check(apiKey: String, model: String): CheckResult {
        val models = get(apiKey, "/models").optJSONArray("data") ?: JSONArray()
        val ids = (0 until models.length()).mapNotNull { models.optJSONObject(it)?.optString("id") }
        val usable = when {
            ids.isEmpty() || model in ids -> model
            MODEL_FLASH in ids -> MODEL_FLASH
            else -> ids.first()
        }
        val balance = runCatching {
            val info = get(apiKey, "/user/balance").getJSONArray("balance_infos").getJSONObject(0)
            "${info.getString("total_balance")} ${info.getString("currency")}"
        }.getOrNull()
        return CheckResult(usable, balance)
    }

    private fun get(apiKey: String, path: String): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code >= 400) throw apiError(code, conn.errorStream)
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun apiError(code: Int, body: InputStream?): ApiException {
        val error = runCatching { JSONObject(body!!.bufferedReader().readText()).getJSONObject("error") }.getOrNull()
        return ApiException(code, error?.optString("type").orEmpty(), error?.optString("message").orEmpty().ifEmpty { "HTTP $code" })
    }

    fun describe(e: Throwable): String = when {
        e is ApiException && e.code == 401 -> "Your DeepSeek key was rejected. Check it in Settings."
        e is ApiException && e.code == 402 ->
            "Your DeepSeek balance is empty. Top it up at platform.deepseek.com, or switch to free Gemini in Settings."
        e is ApiException && e.code == 429 -> "DeepSeek is getting too many requests. Wait a moment and try again."
        e is ApiException && (e.code == 400 || e.code == 422) -> "DeepSeek rejected the request: ${e.message}"
        e is ApiException && e.code >= 500 -> "DeepSeek is busy right now. Try again in a moment."
        e is ApiException -> "DeepSeek error (${e.code}): ${e.message}"
        e is IOException -> "Couldn't reach DeepSeek. Check your internet connection."
        else -> "Something went wrong: ${e.message}"
    }
}
