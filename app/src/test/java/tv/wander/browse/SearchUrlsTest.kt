package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUrlsTest {
    private fun search(q: String) = "https://duckduckgo.com/?q=$q&kp=1&kae=d"

    @Test
    fun explicitSchemesAreUrls() {
        for (s in listOf("http://example.com", "https://en.wikipedia.org/wiki/Moth", "HTTPS://NASA.GOV", "https://localhost:8080/x", "http://cheetah", "https://192.168.1.4")) {
            assertTrue(s, SearchUrls.looksLikeUrl(s))
        }
    }

    @Test
    fun bareDomainsAreUrls() {
        for (s in listOf(
            "nasa.gov", "wikipedia.org", "en.wikipedia.org/wiki/Cheetah", "www.bbc.co.uk", "socket.io", "kids.nationalgeographic.com",
            "example.com:8080", "example.com/path?q=1#top", "Example.COM", "nasa.gov.", "a-b.dev", "museum.museum", "xn--80ak6aa92e.xn--p1ai",
        )) {
            assertTrue(s, SearchUrls.looksLikeUrl(s))
        }
    }

    @Test
    fun localAddressesAreUrls() {
        for (s in listOf("localhost", "localhost:3000", "localhost/admin", "192.168.0.10", "10.0.0.2:8080", "127.0.0.1:8080/ws")) {
            assertTrue(s, SearchUrls.looksLikeUrl(s))
        }
    }

    @Test
    fun searchesAreNotUrls() {
        for (s in listOf(
            "", "   ", "cheetah top speed", "cheetah", "node.js", "e.g.", "3.14", "U.S.", "a.m.", "file.txt", "Mr.Smith",
            "how fast is a cheetah?", "what is nasa.gov", "user@example.com", "ftp://files.example.com", "file:///etc/hosts",
            "javascript:alert(1)", "data:text/html,hi", "about:blank", "999.1.1.1", "example.com:99999", "example.com:abc",
            "-bad.com", "bad-.com", "exa_mple.com", "http://", "..", ".com",
        )) {
            assertFalse(s, SearchUrls.looksLikeUrl(s))
        }
    }

    @Test
    fun resolveAddsHttpsToDomains() {
        assertEquals("https://nasa.gov", SearchUrls.resolve("nasa.gov"))
        assertEquals("https://en.wikipedia.org/wiki/Moth", SearchUrls.resolve("  en.wikipedia.org/wiki/Moth  "))
        assertEquals("https://example.com:8080/a", SearchUrls.resolve("example.com:8080/a"))
    }

    @Test
    fun resolveUsesHttpForLocalDevices() {
        assertEquals("http://192.168.1.20:8080", SearchUrls.resolve("192.168.1.20:8080"))
        assertEquals("http://localhost:3000/", SearchUrls.resolve("localhost:3000/"))
    }

    @Test
    fun resolveKeepsExplicitUrls() {
        assertEquals("https://en.wikipedia.org/wiki/Moth", SearchUrls.resolve("https://en.wikipedia.org/wiki/Moth"))
        assertEquals("http://example.com/A?b=C", SearchUrls.resolve("http://example.com/A?b=C"))
        assertEquals("https://NASA.GOV", SearchUrls.resolve("HTTPS://NASA.GOV"))
    }

    @Test
    fun resolveSearchesEverythingElseSafely() {
        assertEquals(search("cheetah+top+speed"), SearchUrls.resolve("cheetah top speed"))
        assertEquals(search("node.js"), SearchUrls.resolve("node.js"))
        assertEquals(search("javascript%3Aalert%281%29"), SearchUrls.resolve("javascript:alert(1)"))
        assertEquals(search("caf%C3%A9+%26+cr%C3%AApes"), SearchUrls.resolve("café & crêpes"))
        assertEquals(search("100%25+fast%3F"), SearchUrls.resolve(" 100% fast? "))
        assertEquals(search("file%3A%2F%2F%2Fetc%2Fhosts"), SearchUrls.resolve("file:///etc/hosts"))
    }

    @Test
    fun blankOpensSafeSearchHome() {
        assertEquals("https://duckduckgo.com/?kp=1&kae=d", SearchUrls.resolve(""))
        assertEquals("https://duckduckgo.com/?kp=1&kae=d", SearchUrls.resolve(" \n\t "))
    }

    @Test
    fun everySearchKeepsStrictSafeSearchAndDarkTheme() {
        for (q in listOf("dinosaurs", "what moon has volcanoes", "!g cats")) {
            val u = SearchUrls.resolve(q)
            assertTrue(u, u.startsWith("https://duckduckgo.com/?q="))
            assertTrue(u, u.endsWith("&kp=1&kae=d"))
        }
    }
}
