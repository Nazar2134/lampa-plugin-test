package app.taptranslate

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class DeepSeekClientTest {

    private lateinit var server: ServerSocket
    @Volatile private var status = 200
    @Volatile private var response = ""
    @Volatile private var requestBody = ""
    @Volatile private var authHeader: String? = null

    /** Tiny HTTP/1.1 server: reads one request per connection and replies with [status] and [response]. */
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var length = 0
                    var auth: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val (name, value) = line.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
                        if (name.equals("Content-Length", true)) length = value.toInt()
                        if (name.equals("Authorization", true)) auth = value
                    }
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    requestBody = String(body)
                    authHeader = auth
                    val bytes = response.toByteArray()
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun client() = DeepSeekClient("http://127.0.0.1:${server.localPort}/", "sk-test")

    private fun chunk(field: String, text: String) =
        "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put(field, text)))) + "\n\n"

    @Test
    fun streamsContentAndIgnoresKeepAlives() {
        response = ": keep-alive\n\n" +
            chunk("reasoning_content", "hmm") +
            chunk("content", "[[en>uk]]\n") +
            chunk("content", "**привіт**") +
            "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        val deltas = mutableListOf<String>()
        var thinking = 0

        val full = client().streamChat("deepseek-chat", "SYS", "TEXT:\nhello", 800, { thinking++ }, { deltas += it })

        assertEquals("[[en>uk]]\n**привіт**", full)
        assertEquals(listOf("[[en>uk]]\n", "**привіт**"), deltas)
        assertEquals(1, thinking)
        assertEquals("Bearer sk-test", authHeader)
        val body = JSONObject(requestBody)
        assertEquals("deepseek-chat", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(800, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("TEXT:\nhello", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun mapsAuthErrorToFriendlyMessage() {
        status = 401
        response = """{"error":{"message":"Authentication Fails","type":"authentication_error"}}"""
        try {
            client().streamChat("deepseek-chat", "s", "u", 10, {}, {})
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("API key"))
        }
    }

    @Test
    fun reportsUnknownErrorsWithDetail() {
        status = 400
        response = """{"error":{"message":"Model Not Exist"}}"""
        try {
            client().streamChat("nope", "s", "u", 10, {}, {})
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("DeepSeek error 400: Model Not Exist", e.message)
        }
    }

    @Test
    fun readsBalance() {
        response = """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"4.20"}]}"""
        assertEquals("balance 4.20 USD", client().balance())
    }
}
