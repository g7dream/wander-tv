package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- enforceSearch: DuckDuckGo ----

    @Test
    fun ddgAlreadyStrictReturnsNull() {
        // kp=1 already set → no redirect loop
        assertNull(SearchUrls.enforceSearch("https://duckduckgo.com/?q=cats&kp=1&kae=d"))
        assertNull(SearchUrls.enforceSearch("https://html.duckduckgo.com/?q=dogs&kp=1"))
        assertNull(SearchUrls.enforceSearch("https://lite.duckduckgo.com/?q=birds&kp=1"))
    }

    @Test
    fun ddgMissingKpGetsEnforced() {
        val url = "https://duckduckgo.com/?q=cats"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("kp=1"))
        assertFalse(result, result.contains("kp=-"))
    }

    @Test
    fun ddgModerateKpIsReplaced() {
        // kp=-1 (moderate) replaced with kp=1
        val url = "https://duckduckgo.com/?q=cats&kp=-1"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("kp=1"))
        assertFalse(result, result.contains("kp=-1"))
    }

    @Test
    fun ddgOffKpIsReplaced() {
        // kp=-2 (off) replaced with kp=1
        val url = "https://html.duckduckgo.com/?q=leopard&kp=-2&kae=d"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("kp=1"))
        assertFalse(result, result.contains("kp=-2"))
        // Other params kept
        assertTrue(result!!, result.contains("kae=d"))
        assertTrue(result!!, result.contains("q=leopard"))
    }

    @Test
    fun ddgDirectHomepageGetsEnforced() {
        // Opening duckduckgo.com directly (no query params) still gets kp=1
        val result = SearchUrls.enforceSearch("https://duckduckgo.com/")
        assertNotNull(result)
        assertTrue(result!!, result.contains("kp=1"))
    }

    // ---- enforceSearch: Google ----

    @Test
    fun googleAlreadyStrictReturnsNull() {
        assertNull(SearchUrls.enforceSearch("https://www.google.com/search?q=cats&safe=active"))
        assertNull(SearchUrls.enforceSearch("https://www.google.co.uk/search?q=dogs&safe=active"))
    }

    @Test
    fun googleMissingSafeGetsEnforced() {
        val url = "https://www.google.com/search?q=cats"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("safe=active"))
        assertTrue(result!!, result.contains("q=cats"))
    }

    @Test
    fun googleSafeOffIsReplaced() {
        val url = "https://www.google.com/search?q=leopard&safe=off"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("safe=active"))
        assertFalse(result, result.contains("safe=off"))
        assertTrue(result!!, result.contains("q=leopard"))
    }

    @Test
    fun googleSafeImagesIsReplaced() {
        val url = "https://www.google.co.uk/search?q=cheetah&safe=images"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("safe=active"))
        assertFalse(result, result.contains("safe=images"))
    }

    @Test
    fun googleHomepageWithQueryGetsEnforced() {
        // www.google.com/?q=cats (search from home without /search path)
        val url = "https://www.google.com/?q=cats"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("safe=active"))
    }

    @Test
    fun googleHomepageWithoutQueryIsIgnored() {
        // Plain google.com home with no q= is not a search page
        assertNull(SearchUrls.enforceSearch("https://www.google.com/"))
    }

    // ---- enforceSearch: Bing ----

    @Test
    fun bingAlreadyStrictReturnsNull() {
        assertNull(SearchUrls.enforceSearch("https://www.bing.com/search?q=cats&adlt=strict"))
    }

    @Test
    fun bingMissingAdltGetsEnforced() {
        val url = "https://www.bing.com/search?q=foxes"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("adlt=strict"))
        assertTrue(result!!, result.contains("q=foxes"))
    }

    @Test
    fun bingModeratAdltIsReplaced() {
        val url = "https://www.bing.com/search?q=birds&adlt=moderate"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.contains("adlt=strict"))
        assertFalse(result, result.contains("adlt=moderate"))
        assertTrue(result!!, result.contains("q=birds"))
    }

    // ---- enforceSearch: non-search URLs and malformed input ----

    @Test
    fun nonSearchUrlsReturnNull() {
        assertNull(SearchUrls.enforceSearch("https://en.wikipedia.org/wiki/Cheetah"))
        assertNull(SearchUrls.enforceSearch("https://www.nasa.gov/"))
        assertNull(SearchUrls.enforceSearch("https://www.bbc.co.uk/news"))
        assertNull(SearchUrls.enforceSearch("http://192.168.1.20:8080"))
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(SearchUrls.enforceSearch(""))
        assertNull(SearchUrls.enforceSearch("not a url"))
        assertNull(SearchUrls.enforceSearch("javascript:alert(1)"))
        assertNull(SearchUrls.enforceSearch("file:///etc/hosts"))
    }

    @Test
    fun fragmentIsPreserved() {
        val url = "https://duckduckgo.com/?q=cats&kp=-1#bang"
        val result = SearchUrls.enforceSearch(url)
        assertNotNull(result)
        assertTrue(result!!, result.endsWith("#bang"))
        assertTrue(result!!, result.contains("kp=1"))
    }

    @Test
    fun questionMarkInsideFragmentIsNotTheQuery() {
        assertEquals("https://duckduckgo.com/?kp=1#about?x", SearchUrls.enforceSearch("https://duckduckgo.com/#about?x"))
    }
}