package com.aireader.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LogicTest {
    private lateinit var server: MockServer

    @Before
    fun setUp() {
        server = MockServer()
        DeepSeek.baseUrl = server.url
        Ai.retryDelayCap = 0
    }

    @After
    fun tearDown() = server.close()

    private val settings = AppSettings(provider = AiProvider.DEEPSEEK, deepseekKey = "k")
    private fun ok(text: String) = MockServer.Response(200, sse("""{"choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}""", "[DONE]"))
    private val rateLimited = MockServer.Response(429, """{"error":{"message":"slow down"}}""", "application/json")

    @Test
    fun retriesOnceAfterRateLimit() {
        server.queue += rateLimited
        server.queue += ok("hi")
        val text = StringBuilder()
        val result = Ai.stream(settings, "s", listOf(Turn(true, "q")), {}) { text.append(it) }
        assertEquals("hi", text.toString())
        assertEquals(AiResult(AiOutcome.FINISHED, 120, 30), result)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun givesUpAfterSecondFailure() {
        server.queue += rateLimited
        server.queue += rateLimited
        server.queue += ok("never")
        try {
            Ai.stream(settings, "s", listOf(Turn(true, "q")), {}) {}
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(429, e.code)
        }
        assertEquals(2, server.requests.size)
    }

    @Test
    fun doesNotRetryClientErrorsOrAfterTextArrived() {
        server.queue += MockServer.Response(401, """{"error":{"message":"bad key"}}""", "application/json")
        assertTrue(runCatching { Ai.stream(settings, "s", listOf(Turn(true, "q")), {}) {} }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requests.size)

        server.queue += MockServer.Response(200, sse("""{"choices":[{"delta":{"content":"part"},"finish_reason":"insufficient_system_resource"}]}"""))
        server.queue += ok("again")
        val text = StringBuilder()
        assertTrue(runCatching { Ai.stream(settings, "s", listOf(Turn(true, "q")), {}) { text.append(it) } }.exceptionOrNull() is ApiException)
        assertEquals("part", text.toString())
        assertEquals("no second request once text was shown", 2, server.requests.size)
    }

    @Test
    fun doesNotRetryWhenCancelled() {
        server.queue += rateLimited
        server.queue += ok("x")
        assertTrue(runCatching { Ai.stream(settings, "s", listOf(Turn(true, "q")), {}, isCancelled = { true }) {} }.isFailure)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun securityPolicyComesBeforeBookContent() {
        val html = "<!DOCTYPE html><html><head><script>alert(1)</script><link rel=\"stylesheet\" href=\"a.css\"/></head><body><p>x</p></body></html>"
        val out = ChapterRenderer.prepare(html, "<!--READER-->", "NONCE")
        val csp = out.indexOf("Content-Security-Policy")
        assertTrue(out, csp in 0 until out.indexOf("<script>alert"))
        assertTrue(out, out.indexOf("<!--READER-->") > out.indexOf("a.css"))
        val policy = ChapterRenderer.contentSecurityPolicy("NONCE")
        assertTrue(policy.contains("script-src 'nonce-NONCE'"))
        assertTrue(policy.contains("default-src 'none'") && policy.contains("connect-src 'none'"))
        val head = ChapterRenderer.head(AppSettings(), ChapterRenderer.Layout(1, 2, 3), "{}", "NONCE")
        assertEquals(2, Regex("<script nonce=\"NONCE\"").findAll(head).count())
        assertTrue(ChapterRenderer.newNonce() != ChapterRenderer.newNonce())
    }

    @Test
    fun typographySettingsReachTheStylesheet() {
        val head = ChapterRenderer.head(AppSettings(lineHeight = 1.8f, justify = false, font = ReaderFont.PUBLISHER), ChapterRenderer.Layout(1, 2, 30, columns = 2), "{}")
        assertTrue(head.contains("--line-height:1.8;"))
        assertTrue(head.contains("--pad-x:30px;"))
        assertTrue(head.contains("text-align:left !important"))
        assertTrue(head.contains("column-count:2"))
        assertTrue("the book's own fonts are kept", !head.contains("font-family"))
        assertTrue(ChapterRenderer.head(AppSettings(font = ReaderFont.SANS), ChapterRenderer.Layout(1, 2, 3), "{}").contains("font-family:Roboto"))
    }

    @Test
    fun reviewScheduleGrowsAndResets() {
        val now = 1_000_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        var e = VocabEntry(word = "Schuppen")
        e = Vocabulary.review(e, Vocabulary.Grade.GOOD, now)
        assertEquals(1.0, e.intervalDays, 0.0)
        assertEquals(now + day, e.due)
        assertEquals(VocabStatus.LEARNING, e.status)
        e = Vocabulary.review(e, Vocabulary.Grade.GOOD, now)
        assertEquals(3.0, e.intervalDays, 0.0)
        e = Vocabulary.review(e, Vocabulary.Grade.GOOD, now)
        assertEquals(7.5, e.intervalDays, 0.001)
        e = Vocabulary.review(e, Vocabulary.Grade.EASY, now)
        assertTrue(e.intervalDays > 21)
        assertEquals(VocabStatus.KNOWN, e.status)
        val again = Vocabulary.review(e, Vocabulary.Grade.AGAIN, now)
        assertEquals(0.0, again.intervalDays, 0.0)
        assertEquals(now + 10 * 60 * 1000L, again.due)
        assertTrue(again.ease < e.ease)
        assertEquals(5, again.reviews)
    }

    @Test
    fun parsesVocabularyCards() {
        assertEquals("shed", Vocabulary.parseCard("```json\n{\"translation\": \"shed\", \"lemma\": \"Schuppen\"}\n```")!!.getString("translation"))
        assertNull(Vocabulary.parseCard("no json here"))
        assertNull(Vocabulary.parseCard("{broken"))
    }
}
