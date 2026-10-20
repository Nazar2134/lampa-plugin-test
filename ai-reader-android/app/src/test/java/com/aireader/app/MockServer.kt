package com.aireader.app

import java.io.Closeable
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A tiny HTTP/1.1 server for client tests: records each request and replies with queued (or default) responses. */
class MockServer : Closeable {
    class Request(val line: String, val headers: Map<String, String>, val body: String)
    class Response(val status: Int, val body: String, val contentType: String = "text/event-stream")

    private val socket = ServerSocket(0)
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests = ArrayList<Request>()
    val queue = ArrayDeque<Response>()
    var fallback = Response(200, "")

    val last get() = requests.last()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { handle(it.getInputStream().buffered(), it.getOutputStream()) }
            }
        }
    }

    private fun handle(input: java.io.InputStream, output: java.io.OutputStream) {
        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }
        val requestLine = readLine()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) read += input.read(bytes, read, length - read)
        synchronized(this) { requests += Request(requestLine, headers, bytes.decodeToString()) }

        val response = synchronized(this) { queue.removeFirstOrNull() } ?: fallback
        val body = response.body.toByteArray()
        output.write("HTTP/1.1 ${response.status} X\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    override fun close() = socket.close()
}

fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" }
