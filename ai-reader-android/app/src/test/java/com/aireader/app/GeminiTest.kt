package com.aireader.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Runs the Gemini client against a local server that records requests and replays canned responses. */
class GeminiTest {
    private lateinit var server: ServerSocket
    private var status = 200
    private var responseBody = ""
    private var contentType = "text/event-stream"
    private var requestLine = ""
    private var requestKey: String? = null
    private var requestBody = ""

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { handle(it.getInputStream(), it.getOutputStream()) }
            }
        }
        Gemini.baseUrl = "http://127.0.0.1:${server.localPort}"
    }

    @After
    fun tearDown() = server.close()

    private fun handle(input: InputStream, output: OutputStream) {
        val reader = input.buffered()
        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }
        requestLine = readLine()
        var length = 0
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            val name = header.substringBefore(':').trim()
            val value = header.substringAfter(':').trim()
            if (name.equals("content-length", true)) length = value.toInt()
            if (name.equals("x-goog-api-key", true)) requestKey = value
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) read += reader.read(bytes, read, length - read)
        requestBody = bytes.decodeToString()
        val body = responseBody.toByteArray()
        output.write("HTTP/1.1 $status X\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" }

    @Test
    fun streamsTextSkipsThoughtsAndSendsConversation() {
        responseBody = sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hallo"}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":" Welt"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":9}}""",
        )
        val text = StringBuilder()
        val outcome = Gemini.stream("key-123", Gemini.MODEL_FLASH, "SYSTEM", listOf(Turn(true, "Q1"), Turn(false, "A1"), Turn(true, "Q2")), {}) { text.append(it) }

        assertEquals(Gemini.Outcome.FINISHED, outcome)
        assertEquals("Hallo Welt", text.toString())
        assertEquals("POST /v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse HTTP/1.1", requestLine)
        assertEquals("key-123", requestKey)
        val body = JSONObject(requestBody)
        assertEquals("SYSTEM", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })
        assertEquals("Q2", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun reportsBlockedAndTruncated() {
        responseBody = sse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals(Gemini.Outcome.BLOCKED, Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {})
        responseBody = sse("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"MAX_TOKENS"}]}""")
        assertEquals(Gemini.Outcome.TRUNCATED, Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {})
    }

    @Test
    fun mapsQuotaAndKeyErrors() {
        status = 429
        contentType = "application/json"
        responseBody = """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}"""
        try {
            Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {}
            fail("expected an error")
        } catch (e: Gemini.ApiException) {
            assertEquals(429, e.code)
            assertTrue(Gemini.describe(e), Gemini.describe(e).startsWith("The free Gemini limit is used up"))
        }
        status = 400
        responseBody = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val e = runCatching { Gemini.stream("k", "m", "s", listOf(Turn(true, "q")), {}) {} }.exceptionOrNull()!!
        assertEquals("Your Gemini key was rejected. Check it in Settings.", Gemini.describe(e))
    }

    @Test
    fun checkFallsBackToNewestFlashWhenAliasIsMissing() {
        // First request (the model itself) gets 404, the model list follows on the next connection.
        val list = """{"models":[
            {"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.0-flash-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        val models = Gemini.run {
            contentType = "application/json"
            responseBody = list
            listModels("k")
        }
        assertEquals("gemini-3.0-flash-preview", Gemini.pickModel(models, lite = false))
        assertEquals("gemini-2.5-flash-lite", Gemini.pickModel(models, lite = true))
        assertEquals(Gemini.MODEL_FLASH, Gemini.pickModel(models + Gemini.ModelInfo(Gemini.MODEL_FLASH, listOf("generateContent")), lite = false))
    }

    @Test
    fun buildsPrompts() {
        val s = AppSettings(language = "Ukrainian")
        val book = BookInfo("Der kleine Garten", "Anna Beispiel", "German")
        val system = Prompts.system(book, s)
        assertTrue(system, system.contains("\"Der kleine Garten\" by Anna Beispiel, written in German"))
        assertTrue(system, system.contains("Always answer in Ukrainian"))
        val explain = Prompts.first(AiAction.EXPLAIN, s, "hätte", "Sie hätte gern länger geschlafen.", "", null)
        assertTrue(explain, explain.contains("<selection>\nhätte\n</selection>\n<context>\nSie hätte gern länger geschlafen.\n</context>"))
        assertTrue(explain, explain.lines().none { it.startsWith("            ") })
        val handoff = Prompts.handoff(book, s, AiAction.EXPLAIN, "hätte", "Sie hätte gern länger geschlafen.", "", null)
        assertTrue(handoff, handoff.startsWith("I'm reading \"Der kleine Garten\" by Anna Beispiel."))
        assertTrue(handoff, handoff.contains("explain this in Ukrainian"))
    }
}
