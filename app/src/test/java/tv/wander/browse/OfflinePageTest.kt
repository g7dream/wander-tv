package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePageTest {
    @Test
    fun errorCodesMapToKinds() {
        assertEquals(OfflinePage.Kind.NO_INTERNET, OfflinePage.kindFor(-2, online = false))
        assertEquals(OfflinePage.Kind.NOT_FOUND, OfflinePage.kindFor(-2, online = true))
        assertEquals(OfflinePage.Kind.NOT_FOUND, OfflinePage.kindFor(-6, online = true))
        assertEquals(OfflinePage.Kind.TIMEOUT, OfflinePage.kindFor(-8, online = true))
        assertEquals(OfflinePage.Kind.SECURITY, OfflinePage.kindFor(-11, online = true))
        assertEquals(OfflinePage.Kind.OTHER, OfflinePage.kindFor(-1, online = true))
    }

    @Test
    fun pageUsesOurFontsAndOffersRetry() {
        val html = OfflinePage.html(OfflinePage.Kind.NOT_FOUND, "https://moths.example/wings", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue(html.contains("fonts/YoungSerif-Regular.ttf"))
        assertTrue(html.contains("fonts/AtkinsonHyperlegible-Regular.ttf"))
        assertTrue(html.contains("<b>moths.example</b>"))
        assertTrue(html.contains("href=\"https://moths.example/wings\""))
        assertTrue(html.contains("Try again"))
        assertTrue(html.contains("net::ERR_NAME_NOT_RESOLVED"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun securityProblemsDontOfferRetry() {
        val html = OfflinePage.html(OfflinePage.Kind.SECURITY, "https://bad.example/", null)
        assertFalse(html.contains("Try again"))
        assertTrue(html.contains("certificate"))
    }

    @Test
    fun everythingFromThePageIsEscaped() {
        val url = "https://x.example/?q=\"><script>alert(1)</script>"
        val html = OfflinePage.html(OfflinePage.Kind.OTHER, url, "<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun nonWebUrlsGetNoLinks() {
        val html = OfflinePage.html(OfflinePage.Kind.OTHER, "javascript:alert(1)", null)
        assertFalse(html.contains("href="))
    }

    @Test
    fun everyKindHasWarmCopy() {
        for (k in OfflinePage.Kind.values()) {
            val t = OfflinePage.title(k)
            assertTrue(t.isNotBlank() && t.length < 40)
        }
    }

    /** Writes each variant next to the snapshots so it can be opened in a browser for a design check. */
    @Test
    fun writesPreviewFiles() {
        val dir = java.io.File(System.getProperty("wander.snapDir") ?: "build/snaps", "offline").apply { mkdirs() }
        for (k in OfflinePage.Kind.values()) {
            val html = OfflinePage.html(k, "https://moths.example/wings/luna-moth", if (k == OfflinePage.Kind.OTHER) "net::ERR_CACHE_MISS" else null)
            java.io.File(dir, "offline_${k.name.lowercase()}.html").writeText(html)
        }
        assertTrue(java.io.File(dir, "offline_not_found.html").length() > 2000)
    }
}
