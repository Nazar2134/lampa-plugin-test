package app.taptranslate

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : Exception(message)

/** Minimal client for DeepSeek's OpenAI-compatible API, using streaming so text shows up as it is generated. */
class DeepSeekClient(private val baseUrl: String, private val apiKey: String) {

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
        connection?.disconnect()
    }

    /**
     * Streams a chat completion. [onDelta] receives answer chunks as they arrive; [onThinking] fires while
     * a reasoning model is still thinking. Returns the full answer.
     */
    fun streamChat(
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
        onThinking: () -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.5)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )

        val conn = open("/chat/completions", "POST")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        checkStatus(conn)

        val answer = StringBuilder()
        conn.inputStream.bufferedReader().use { reader ->
            while (!isCancelled) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue // blank separators and ": keep-alive" comments
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val delta = JSONObject(data).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val content = delta.stringOrNull("content")
                if (!content.isNullOrEmpty()) {
                    answer.append(content)
                    onDelta(content)
                } else if (!delta.stringOrNull("reasoning_content").isNullOrEmpty()) {
                    onThinking()
                }
            }
        }
        return answer.toString()
    }

    /** Returns a human-readable account balance; also serves as an API-key check. */
    fun balance(): String {
        val conn = open("/user/balance", "GET")
        checkStatus(conn)
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val info = json.optJSONArray("balance_infos")?.optJSONObject(0)
        val amount = info?.let { "${it.optString("total_balance")} ${it.optString("currency")}" } ?: "unknown"
        return if (json.optBoolean("is_available", true)) "balance $amount"
        else "balance $amount (too low, top up to use it)"
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        connection = conn
        return conn
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
            .getOrNull() ?: raw.take(200)
        throw ApiException(
            when (code) {
                401 -> "DeepSeek rejected the API key. Check it in settings."
                402 -> "Your DeepSeek balance is empty. Top up at platform.deepseek.com."
                429 -> "Too many requests. Wait a moment and try again."
                in 500..599 -> "DeepSeek is busy right now (error $code). Try again."
                else -> "DeepSeek error $code: $detail"
            }
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
