package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebScriptsTest {
    @Test
    fun numbersAreAlwaysPlainJs() {
        assertEquals("0.5", WebScripts.num(0.5f))
        assertEquals("1", WebScripts.num(1f))
        assertEquals("0", WebScripts.num(0f))
        assertEquals("-0.25", WebScripts.num(-0.25f))
        assertEquals("0", WebScripts.num(-0.000001f))
        assertEquals("0", WebScripts.num(Float.NaN))
        assertEquals("0", WebScripts.num(Float.POSITIVE_INFINITY))
        assertEquals("100", WebScripts.num(100f))
    }

    @Test
    fun quotingSurvivesHostileText() {
        assertEquals("\"hi\"", WebScripts.quote("hi"))
        assertEquals("\"a\\\"b\\\\c\"", WebScripts.quote("a\"b\\c"))
        assertEquals("\"line\\nbreak\\r\\t\"", WebScripts.quote("line\nbreak\r\t"))
        assertEquals("\"\\u2028\\u2029\"", WebScripts.quote("\u2028\u2029"))
        assertEquals("\"\\u003c/script>\"", WebScripts.quote("</script>"))
        assertEquals("\"\\u0001\"", WebScripts.quote("\u0001"))
        assertEquals("\"café 🐆\"", WebScripts.quote("café 🐆"))
        val evil = "\");alert(1);(\""
        val q = WebScripts.quote(evil)
        assertFalse(q.substring(1, q.length - 1).contains(Regex("(?<!\\\\)\"")))
    }

    @Test
    fun typeCallIsGuardedAndQuoted() {
        val js = WebScripts.type("cheetah \"speed\"", true)
        assertTrue(js.contains("W.type(\"cheetah \\\"speed\\\"\",true)"))
        assertTrue(js.contains("'__nolib'"))
    }

    @Test
    fun scrollCallsPassFractions() {
        assertTrue(WebScripts.scrollAt(0.5f, 0.25f, 0f, 0.1f, true).contains("W.scrollAt(0.5,0.25,0,0.1,true)"))
        assertTrue(WebScripts.page(0.5f, 0.5f, -3).contains("W.page(0.5,0.5,-1)"))
        assertTrue(WebScripts.hit(0f, 1f).contains("W.hit(0,1)"))
    }

    @Test
    fun readabilityIsWrappedSoItLeavesNoGlobals() {
        val w = WebScripts.wrapReadability("function Readability(){}")
        assertTrue(w.startsWith("(function(){var module;"))
        assertTrue(w.contains("window.__wanderReadability=Readability;"))
    }

    @Test
    fun decodesEvaluateJavascriptResults() {
        assertEquals("plain", WebScripts.decodeString("\"plain\""))
        assertEquals("with \"quotes\" and \n", WebScripts.decodeString("\"with \\\"quotes\\\" and \\n\""))
        assertNull(WebScripts.decodeString("null"))
        assertNull(WebScripts.decodeString(null))
        assertNull(WebScripts.decodeString(""))
        assertNull(WebScripts.decodeString("{not json"))
        assertEquals(2, WebScripts.decodeInt("2"))
        assertEquals(0, WebScripts.decodeInt("null"))
        assertEquals(0, WebScripts.decodeInt("\"__nolib\""))
    }

    @Test
    fun decodesTheExtractObjectFromItsStringLiteral() {
        val inner = """{"url":"https://a.com/","title":"A \"quoted\" title","text":"Hi.","headings":["One"],"siteName":null}"""
        val literal = WebScripts.quote(inner)
        val o = WebScripts.decodeObject(literal)!!
        assertEquals("A \"quoted\" title", o.getString("title"))
        assertEquals("One", o.getJSONArray("headings").getString(0))
        assertTrue(o.isNull("siteName"))
        assertNull(WebScripts.decodeObject("\"__nolib\""))
        assertNull(WebScripts.decodeObject("null"))
    }

    @Test
    fun bridgeAssetExposesTheHelpersTheLayerCalls() {
        val js = java.io.File("src/main/assets/js/wander-bridge.js").takeIf { it.exists() }
            ?: java.io.File("app/src/main/assets/js/wander-bridge.js")
        val src = js.readText()
        for (fn in listOf("W.scrollAt", "W.page", "W.hit", "W.type", "W.media", "W.extract", "W.check", "onField", "__wanderReadability")) {
            assertTrue(fn, src.contains(fn))
        }
        // ES5 only: older Fire OS WebViews must parse it.
        assertFalse(src.contains("=>"))
        assertFalse(Regex("\\b(let|const|class)\\s").containsMatchIn(src))
        assertFalse(src.contains("`"))
        assertFalse(src.contains("?."))
    }
}
