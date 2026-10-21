package tv.wander.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextsTest {
    @Test
    fun collapsesWhitespaceAndDropsCitations() {
        val raw = "The cheetah\n\n  is the fastest land animal[3] , reaching 112 km/h[citation needed].\tIt hunts by day[a]."
        assertEquals("The cheetah is the fastest land animal, reaching 112 km/h. It hunts by day.", PageTexts.clean(raw))
    }

    @Test
    fun shortTextIsUntouched() {
        assertEquals("Moths are great.", PageTexts.clip("Moths are great.", 6000))
    }

    @Test
    fun longTextEndsOnASentence() {
        val sentence = "Moths fly toward the light because they navigate by it. "
        val text = sentence.repeat(200).trim()
        val clipped = PageTexts.clip(text, 6000)
        assertTrue(clipped.length <= 6000)
        assertTrue(clipped.length > 5900)
        assertTrue(clipped.endsWith("navigate by it."))
    }

    @Test
    fun sentenceEndingExactlyAtTheLimitCounts() {
        val text = "a".repeat(5998) + ". More words here."
        val clipped = PageTexts.clip(text, 6000)
        assertEquals(5999, clipped.length)
        assertTrue(clipped.endsWith("."))
    }

    @Test
    fun noSentenceBoundaryFallsBackToAWordAndEllipsis() {
        val text = "word ".repeat(2000).trim()
        val clipped = PageTexts.clip(text, 6000)
        assertTrue(clipped.length <= 6000)
        assertTrue(clipped.endsWith("word…"))
    }

    @Test
    fun decimalsAreNotSentenceEnds() {
        val text = "x".repeat(5990) + " is 3.5 m long and more"
        val clipped = PageTexts.clip(text, 6000)
        assertTrue(clipped, clipped.endsWith("…"))
        assertTrue(!clipped.endsWith("3.…"))
    }

    @Test
    fun headingsAreCleanedDedupedAndCapped() {
        val raw = listOf(
            "Contents", "Etymology[edit]", "Taxonomy", "  Taxonomy ", "References", "Speed and hunting", "Cheetah",
            "Diet", "Habitat", "Range", "Behaviour", "Conservation", "In culture", "Extra one",
        )
        val h = PageTexts.cleanHeadings(raw, "Cheetah")
        assertEquals(listOf("Etymology", "Taxonomy", "Speed and hunting", "Diet", "Habitat", "Range", "Behaviour", "Conservation"), h)
    }

    @Test
    fun buildMakesAPageText() {
        val p = PageTexts.build(
            "https://en.wikipedia.org/wiki/Cheetah",
            "  Cheetah - Wikipedia ",
            "The cheetah is a large cat.\n\nIt is fast.",
            listOf("Speed"),
            "Wikipedia",
        )!!
        assertEquals("https://en.wikipedia.org/wiki/Cheetah", p.url)
        assertEquals("Cheetah - Wikipedia", p.title)
        assertEquals("The cheetah is a large cat. It is fast.", p.text)
        assertEquals(listOf("Speed"), p.headings)
        assertEquals("Wikipedia", p.siteName)
    }

    @Test
    fun buildFallsBackToHostForTitleAndDropsBlankSite() {
        val p = PageTexts.build("https://www.nasa.gov/moon/", "", "Words.", emptyList(), "  ")!!
        assertEquals("nasa.gov", p.title)
        assertNull(p.siteName)
    }

    @Test
    fun emptyTextIsAFailure() {
        assertNull(PageTexts.build("https://a.com", "A", "  \n ", emptyList(), null))
        assertNull(PageTexts.build("https://a.com", "A", null, emptyList(), null))
    }

    @Test
    fun displayTitleDropsTheSiteName() {
        assertEquals("Cheetah", PageTexts.displayTitle("Cheetah - Wikipedia", "https://en.wikipedia.org/wiki/Cheetah"))
        assertEquals("moths that glow", PageTexts.displayTitle("moths that glow at DuckDuckGo", "https://duckduckgo.com/?q=moths"))
        assertEquals("Speed Safari", PageTexts.displayTitle("Speed Safari – National Geographic Kids", "https://kids.nationalgeographic.com/x"))
        assertEquals("Cats", PageTexts.displayTitle("Cats | BBC", "https://www.bbc.co.uk/cats"))
        assertEquals("Home | NASA", PageTexts.displayTitle("Home | NASA", "https://www.nasa.gov/"))
        assertEquals("Tom - Jerry", PageTexts.displayTitle("Tom - Jerry", "https://cartoons.example/"))
        assertEquals("NASA", PageTexts.displayTitle("NASA", "https://www.nasa.gov/"))
        assertEquals("nasa.gov", PageTexts.displayTitle("  ", "https://www.nasa.gov/"))
    }

    @Test
    fun siteLabel() {
        assertEquals("wikipedia", PageTexts.siteLabel("https://en.wikipedia.org/wiki/Moth"))
        assertEquals("bbc", PageTexts.siteLabel("https://www.bbc.co.uk/news"))
        assertEquals("nasa", PageTexts.siteLabel("https://nasa.gov"))
        assertEquals("localhost", PageTexts.siteLabel("http://localhost:8080/"))
    }

    @Test
    fun hostLabel() {
        assertEquals("en.wikipedia.org", PageTexts.hostLabel("https://en.wikipedia.org/wiki/Moth"))
        assertEquals("nasa.gov", PageTexts.hostLabel("https://www.nasa.gov"))
        assertEquals("example.com:8080", PageTexts.hostLabel("http://user@example.com:8080/x?y#z"))
    }
}
