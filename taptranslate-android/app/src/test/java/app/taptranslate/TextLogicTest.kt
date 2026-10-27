package app.taptranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {

    @Test
    fun splitsLanguageTag() {
        val p = MiniMarkdown.parse("[[EN>uk]]\n**погода**, негода\nnoun · /ˈweðə/")
        assertEquals("en", p.source)
        assertEquals("uk", p.target)
        assertEquals("**погода**, негода\nnoun · /ˈweðə/", p.body)
        assertEquals("погода, негода", MiniMarkdown.headline(p.body))
    }

    @Test
    fun hidesTagWhileItIsStreaming() {
        assertEquals("", MiniMarkdown.parse("[[en>").body)
        assertEquals("", MiniMarkdown.parse("[").body)
        assertNull(MiniMarkdown.parse("[[en>").source)
    }

    @Test
    fun keepsAnswerWithoutTag() {
        val p = MiniMarkdown.parse("**hola**\ninterjection")
        assertNull(p.source)
        assertEquals("hola", MiniMarkdown.headline(p.body))
    }

    @Test
    fun shortVersusLongText() {
        assertTrue(Prompt.isShort("under the weather"))
        assertFalse(Prompt.isShort("The meeting was postponed because the manager was ill."))
        assertEquals(8000, Prompt.maxTokens(Prefs.MODEL_REASONER, "word"))
    }

    @Test
    fun userMessageIncludesContextOnlyWhenDifferent() {
        assertEquals("TEXT:\nbank", Prompt.user("bank", null))
        assertEquals("TEXT:\nbank", Prompt.user("bank", "bank"))
        assertTrue(Prompt.user("bank", "We sat on the river bank.").contains("CONTEXT"))
    }

    @Test
    fun systemPromptHandlesDirection() {
        val auto = Prompt.system("Ukrainian", "English", null)
        assertTrue(auto.contains("If TEXT is not in Ukrainian, translate it into Ukrainian."))
        assertTrue(auto.contains("translate it into English"))
        assertTrue(Prompt.system("Ukrainian", "English", "French").contains("Translate TEXT into French"))
    }
}
