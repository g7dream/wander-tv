package tv.wander.browse

import tv.wander.model.PageText

/**
 * Turns what the page script pulled out (Readability's article, or the fallback) into a [PageText]
 * that is small and clean enough to send to the AI: whitespace collapsed, reference markers like
 * "[12]" dropped, capped at [MAX_CHARS] on a sentence boundary, and at most [MAX_HEADINGS] headings.
 * Pure Kotlin so it is unit tested on the JVM.
 */
internal object PageTexts {
    const val MAX_CHARS = 6000
    const val MAX_HEADINGS = 8
    private const val MAX_HEADING_CHARS = 80
    private const val MAX_TITLE_CHARS = 200

    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F\\u200B\\uFEFF]+")
    private val CITATIONS = Regex("\\[(?:\\d{1,3}|[a-z]|citation needed|clarification needed|edit|note \\d+|nb \\d+)\\]", RegexOption.IGNORE_CASE)
    private val SPACE_BEFORE_PUNCT = Regex(" +([.,;:!?)])")
    private val SENTENCE_END = Regex("[.!?…][\"'”’)\\]]*(?= )")

    /** Boilerplate section names (mostly Wikipedia) that never make a good card. */
    private val BORING_HEADINGS = setOf(
        "contents", "references", "external links", "see also", "notes", "further reading", "sources",
        "bibliography", "citations", "footnotes", "navigation menu", "related articles", "share",
        "comments", "advertisement", "related", "more", "explanatory notes", "works cited",
    )

    fun build(url: String, title: String?, text: String?, headings: List<String>, siteName: String?): PageText? {
        val body = clip(clean(text.orEmpty()), MAX_CHARS)
        if (body.isEmpty()) return null
        val cleanTitle = collapse(title.orEmpty()).take(MAX_TITLE_CHARS).ifEmpty { hostLabel(url) }
        val cleanSite = siteName?.let { collapse(it).take(60) }?.takeIf { it.isNotEmpty() }
        return PageText(url = url, title = cleanTitle, text = body, headings = cleanHeadings(headings, cleanTitle), siteName = cleanSite)
    }

    fun collapse(s: String): String = WHITESPACE.replace(s, " ").trim()

    /** Whitespace collapsed and citation markers removed ("fastest land animal[3] ." -> "fastest land animal."). */
    fun clean(s: String): String {
        val noRefs = CITATIONS.replace(s, "")
        return SPACE_BEFORE_PUNCT.replace(collapse(noRefs), "$1")
    }

    /**
     * Cuts [s] to at most [max] chars, ending on a full sentence when one ends in the last 40% of the
     * window; otherwise on a word boundary with an ellipsis.
     */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val window = s.substring(0, max)
        val minEnd = (max * 0.6).toInt()
        // Look one char past the window so a sentence ending exactly at [max] still counts.
        val probe = s.substring(0, max + 1)
        var cut = -1
        for (m in SENTENCE_END.findAll(probe)) {
            val end = m.range.last + 1
            if (end in minEnd..max) cut = end
        }
        if (cut > 0) return s.substring(0, cut).trim()
        val space = window.lastIndexOf(' ', max - 2)
        val base = if (space >= minEnd) window.substring(0, space) else window.substring(0, max - 1)
        return base.trimEnd(' ', ',', ';', ':', '-') + "…"
    }

    fun cleanHeadings(raw: List<String>, title: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (h in raw) {
            val c = clean(h).removeSuffix("[edit]").trim().trimEnd(':').trim()
            if (c.length < 2) continue
            val key = c.lowercase()
            if (key in BORING_HEADINGS || key == title.lowercase()) continue
            if (!seen.add(key)) continue
            out.add(if (c.length > MAX_HEADING_CHARS) clip(c, MAX_HEADING_CHARS) else c)
            if (out.size == MAX_HEADINGS) break
        }
        return out
    }

    private val TITLE_SEPARATORS = listOf(" - ", " | ", " — ", " – ", " · ", " • ", " :: ", " at ")
    private val GENERIC_HEADS = setOf("home", "homepage", "home page", "welcome", "index", "start", "main page")

    /**
     * The page title without a trailing site name the domain chip already shows:
     * "Cheetah - Wikipedia" on wikipedia.org -> "Cheetah", "moths at DuckDuckGo" -> "moths".
     * Generic heads ("Home | NASA") keep the full title.
     */
    fun displayTitle(title: String?, url: String): String {
        val t = collapse(title.orEmpty())
        if (t.isEmpty()) return hostLabel(url)
        val key = siteLabel(url).lowercase().filter { it.isLetterOrDigit() }
        if (key.length < 2) return t
        for (sep in TITLE_SEPARATORS) {
            val i = t.lastIndexOf(sep)
            if (i <= 0) continue
            val head = t.substring(0, i).trim()
            val tail = t.substring(i + sep.length).lowercase().filter { it.isLetterOrDigit() }
            if (head.length < 2 || head.lowercase() in GENERIC_HEADS || tail.isEmpty() || tail.length > 40) continue
            val names = tail == key || (tail.length >= 3 && key.contains(tail)) || (key.length >= 3 && tail.contains(key))
            if (names) return head
        }
        return t
    }

    /** The name part of a site's address: "en.wikipedia.org" -> "wikipedia", "bbc.co.uk" -> "bbc". */
    fun siteLabel(url: String): String {
        val host = hostLabel(url).substringBefore(':').lowercase()
        val labels = host.split('.').filter { it.isNotEmpty() }
        return when {
            labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2].length <= 3 -> labels[labels.size - 3]
            labels.size >= 2 -> labels[labels.size - 2]
            else -> labels.firstOrNull().orEmpty()
        }
    }

    /** "https://en.wikipedia.org/wiki/Moth" -> "en.wikipedia.org" (used when a page has no title). */
    fun hostLabel(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').removePrefix("www.").ifEmpty { "This page" }
    }
}
