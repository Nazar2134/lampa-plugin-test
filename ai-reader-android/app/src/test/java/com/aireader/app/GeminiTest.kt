package com.aireader.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Runs the Gemini client against a local server that records requests and replays canned responses. */
class GeminiTest {
    private lateinit var server: MockServer

    @Before
    fun setUp() {
        server = MockServer()
        Gemini.baseUrl = server.url
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, status: Int = 200, type: String = "text/event-stream") {
        server.fallback = MockServer.Response(status, body, type)
    }

    @Test
    fun streamsTextSkipsThoughtsAndSendsConversation() {
        respond(sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hallo"}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":" Welt"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":9}}""",
        ))
        val text = StringBuilder()
        val outcome = Gemini.stream("key-123", Gemini.MODEL_FLASH, "SYSTEM", listOf(Turn(true, "Q1"), Turn(false, "A1"), Turn(true, "Q2")), {}) { text.append(it) }

        assertEquals(AiOutcome.FINISHED, outcome.outcome)
        assertEquals("Hallo Welt", text.toString())
        assertEquals("POST /v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse HTTP/1.1", server.last.line)
        assertEquals("key-123", server.last.headers["x-goog-api-key"])
        val body = JSONObject(server.last.body)
        assertEquals("SYSTEM", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })
        assertEquals("Q2", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun reportsBlockedAndTruncated() {
        respond(sse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(AiOutcome.BLOCKED, Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {}.outcome)
        respond(sse("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"MAX_TOKENS"}]}"""))
        assertEquals(AiOutcome.TRUNCATED, Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {}.outcome)
    }

    @Test
    fun mapsQuotaAndKeyErrors() {
        respond("""{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}""", 429, "application/json")
        try {
            Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {}
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
            assertTrue(Gemini.describe(e), Gemini.describe(e).startsWith("The free Gemini limit is used up"))
        }
        respond("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", 400, "application/json")
        val e = runCatching { Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {} }.exceptionOrNull()!!
        assertEquals("Your Gemini key was rejected. Check it in Settings.", Gemini.describe(e))
    }

    @Test
    fun checkFallsBackToNewestFlashWhenAliasIsMissing() {
        val list = """{"models":[
            {"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.0-flash-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        respond(list, 200, "application/json")
        val models = Gemini.listModels("k")
        assertEquals("gemini-3.0-flash-preview", Gemini.pickModel(models, lite = false))
        assertEquals("gemini-2.5-flash-lite", Gemini.pickModel(models, lite = true))
        assertEquals(Gemini.MODEL_FLASH, Gemini.pickModel(models + Gemini.ModelInfo(Gemini.MODEL_FLASH, listOf("generateContent")), lite = false))
    }

    @Test
    fun checkReplacesAMissingModel() {
        server.queue += MockServer.Response(404, """{"error":{"code":404,"message":"not found","status":"NOT_FOUND"}}""", "application/json")
        server.queue += MockServer.Response(200, """{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}""", "application/json")
        assertEquals("gemini-2.5-flash", Gemini.check("k", Gemini.MODEL_FLASH))
        assertEquals("GET /v1beta/models/gemini-flash-latest HTTP/1.1", server.requests[0].line)
    }

    @Test
    fun readsUsage() {
        respond(sse("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":7,"thoughtsTokenCount":3}}"""))
        assertEquals(AiResult(AiOutcome.FINISHED, 50, 10), Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {})
    }

    @Test
    fun buildsHandoffPrompts() {
        val s = AppSettings(language = "Ukrainian")
        val book = BookInfo("Der kleine Garten", "Anna Beispiel", "German")
        val handoff = Prompts.handoff(book, s, AiRequest(AiAction.EXPLAIN, "hätte", "Sie hätte gern länger geschlafen."), "")
        assertTrue(handoff, handoff.startsWith("I'm reading \"Der kleine Garten\" by Anna Beispiel."))
        assertTrue(handoff, handoff.contains("explain this in Ukrainian"))
        assertTrue(handoff, handoff.contains("It appears here: \"Sie hätte gern länger geschlafen.\""))
        val chat = Prompts.handoff(book, s, AiRequest(AiAction.CHAT, question = "Who is Jonas?"), "page text")
        assertTrue(chat, chat.contains("Here's the page I'm on") && chat.contains("Who is Jonas?"))
    }
}
