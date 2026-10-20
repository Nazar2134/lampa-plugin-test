package com.aireader.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekTest {
    private lateinit var server: MockServer

    @Before
    fun setUp() {
        server = MockServer()
        DeepSeek.baseUrl = server.url
    }

    @After
    fun tearDown() = server.close()

    private fun chunk(content: String?, reasoning: String? = null, finish: String? = null): String {
        val delta = JSONObject().put("role", "assistant")
        if (content != null) delta.put("content", content) else delta.put("content", JSONObject.NULL)
        if (reasoning != null) delta.put("reasoning_content", reasoning)
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)
        return JSONObject().put("id", "x").put("object", "chat.completion.chunk").put("model", "deepseek-flash")
            .put("choices", org.json.JSONArray().put(choice)).toString()
    }

    @Test
    fun streamsAnswerSkipsReasoningAndSendsConversation() {
        server.fallback = MockServer.Response(200, sse(
            chunk(null, reasoning = "Let me think"),
            chunk("Привіт"),
            chunk(", світе", finish = "stop"),
            "[DONE]",
        ))
        val text = StringBuilder()
        val outcome = DeepSeek.stream("sk-test", DeepSeek.MODEL_FLASH, "SYSTEM", listOf(Turn(true, "Q1"), Turn(false, "A1"), Turn(true, "Q2")), {}) { text.append(it) }

        assertEquals(AiOutcome.FINISHED, outcome)
        assertEquals("Привіт, світе", text.toString())
        assertEquals("POST /chat/completions HTTP/1.1", server.last.line)
        assertEquals("Bearer sk-test", server.last.headers["authorization"])
        val body = JSONObject(server.last.body)
        assertEquals("deepseek-flash", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("SYSTEM", messages.getJSONObject(0).getString("content"))
        assertEquals("Q2", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun proThinksBriefly() {
        server.fallback = MockServer.Response(200, sse(chunk("ok", finish = "stop"), "[DONE]"))
        DeepSeek.stream("k", DeepSeek.MODEL_PRO, "s", listOf(Turn(true, "q")), {}) {}
        val body = JSONObject(server.last.body)
        assertEquals("deepseek-v4-pro", body.getString("model"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getString("reasoning_effort"))
    }

    @Test
    fun reportsLengthAndFilter() {
        server.fallback = MockServer.Response(200, sse(chunk("x", finish = "length"), "[DONE]"))
        assertEquals(AiOutcome.TRUNCATED, DeepSeek.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {})
        server.fallback = MockServer.Response(200, sse(chunk("", finish = "content_filter"), "[DONE]"))
        assertEquals(AiOutcome.BLOCKED, DeepSeek.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {})
    }

    @Test
    fun mapsErrors() {
        fun errorFor(status: Int): String {
            server.fallback = MockServer.Response(status, """{"error":{"message":"msg $status","type":"t","code":"c"}}""", "application/json")
            return DeepSeek.describe(runCatching { DeepSeek.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {} }.exceptionOrNull()!!)
        }
        assertEquals("Your DeepSeek key was rejected. Check it in Settings.", errorFor(401))
        assertTrue(errorFor(402).startsWith("Your DeepSeek balance is empty."))
        assertTrue(errorFor(429).startsWith("DeepSeek is getting too many requests"))
        assertEquals("DeepSeek rejected the request: msg 422", errorFor(422))
        assertTrue(errorFor(503).startsWith("DeepSeek is busy"))
    }

    @Test
    fun checkReadsModelsAndBalance() {
        server.queue += MockServer.Response(200, """{"object":"list","data":[{"id":"deepseek-flash","object":"model"},{"id":"deepseek-v4-pro","object":"model"}]}""", "application/json")
        server.queue += MockServer.Response(200, """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.87","granted_balance":"0.00","topped_up_balance":"4.87"}]}""", "application/json")
        val result = DeepSeek.check("k", DeepSeek.MODEL_PRO)
        assertEquals(DeepSeek.CheckResult("deepseek-v4-pro", "4.87 USD"), result)
        assertEquals(listOf("GET /models HTTP/1.1", "GET /user/balance HTTP/1.1"), server.requests.map { it.line })

        // A model that no longer exists falls back to Flash; a missing balance endpoint is fine.
        server.queue += MockServer.Response(200, """{"data":[{"id":"deepseek-flash"}]}""", "application/json")
        server.queue += MockServer.Response(404, "{}", "application/json")
        assertEquals(DeepSeek.CheckResult("deepseek-flash", null), DeepSeek.check("k", "deepseek-old"))
    }
}
