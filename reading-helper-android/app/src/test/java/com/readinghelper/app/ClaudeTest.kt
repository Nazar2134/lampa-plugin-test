package com.readinghelper.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Runs [Claude.stream] against a local server that records the request and replays a canned SSE stream. */
class ClaudeTest {

    private lateinit var server: ServerSocket
    private var stopReason = "end_turn"
    private var requestBody = ""
    private var requestBeta: String? = null
    private var requestPath = ""

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { handle(it.getInputStream(), it.getOutputStream()) }
            }
        }
        Claude.baseUrl = "http://127.0.0.1:${server.localPort}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Minimal HTTP/1.1: read the request line, headers and Content-Length body, reply with SSE and close. */
    private fun handle(input: java.io.InputStream, output: java.io.OutputStream) {
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
        requestPath = readLine().split(" ")[1]
        var length = 0
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            val (name, value) = header.split(":", limit = 2).map { it.trim() }
            if (name.equals("content-length", true)) length = value.toInt()
            if (name.equals("anthropic-beta", true)) requestBeta = value
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) read += reader.read(bytes, read, length - read)
        requestBody = bytes.decodeToString()

        val body = sse(stopReason).toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun settings(model: String, detailed: Boolean = false) =
        Settings(apiKey = "test-key", language = "Ukrainian", model = model, detailed = detailed)

    @Test
    fun streamsTextAndSendsFallbacksForOpus() {
        val text = StringBuilder()
        val outcome = Claude.stream(
            settings(Claude.MODEL_OPUS),
            listOf(Turn(true, "Ich hätte es gewusst."), Turn(false, "Earlier answer"), Turn(true, "Why hätte?")),
            onOpen = {},
        ) { text.append(it) }

        assertEquals(Claude.Outcome.Finished, outcome)
        assertEquals("Hello world", text.toString())
        assertTrue(requestPath, requestPath.startsWith("/v1/messages"))
        assertTrue(requestBeta.orEmpty(), requestBeta.orEmpty().contains("server-side-fallback-2026-07-01"))
        assertTrue(requestBody, requestBody.contains("\"fallbacks\":\"default\""))
        assertTrue(requestBody, requestBody.contains("\"model\":\"claude-opus-5-5\""))
        assertTrue(requestBody, requestBody.contains("\"effort\":\"low\""))
        assertTrue(requestBody, requestBody.contains("\"stream\":true"))
        assertTrue(requestBody, requestBody.contains("<selected_text>\\nIch hätte es gewusst.\\n</selected_text>"))
        assertTrue(requestBody, requestBody.contains("Write your whole answer in Ukrainian."))
        assertTrue(requestBody, requestBody.contains("\"role\":\"assistant\""))
        assertTrue(requestBody, requestBody.contains("Why hätte?"))
    }

    @Test
    fun haikuOmitsFallbacksAndDetailedUsesMediumEffort() {
        Claude.stream(settings(Claude.MODEL_HAIKU, detailed = true), listOf(Turn(true, "Haus")), onOpen = {}) {}

        assertFalse(requestBody, requestBody.contains("fallbacks"))
        assertFalse(requestBeta.orEmpty(), requestBeta.orEmpty().contains("server-side-fallback"))
        assertTrue(requestBody, requestBody.contains("\"effort\":\"medium\""))
    }

    @Test
    fun reportsRefusal() {
        stopReason = "refusal"
        val outcome = Claude.stream(settings(Claude.MODEL_SONNET), listOf(Turn(true, "Haus")), onOpen = {}) {}
        assertEquals(Claude.Outcome.Refused, outcome)
    }

    private fun sse(stopReason: String) = listOf(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":5}}""",
        "message_stop" to """{"type":"message_stop"}""",
    ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
}
