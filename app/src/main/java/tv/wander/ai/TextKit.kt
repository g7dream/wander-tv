package tv.wander.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Pure-Kotlin text helpers for the on-device brain: sentence splitting that survives abbreviations and
 * decimals, Wikipedia-style clean-up (citations, pronunciation asides), clamping to TV-sized lines, and
 * normalization for comparing names and quotes.
 */
internal object TextKit {
    const val ELLIPSIS = "…"

    /** A readable sentence from the page with where it came from. */
    data class Sentence(val text: String, val index: Int, val heading: String?)

    private val CITATION = Regex("""\[(?:\d+|[a-z]|citation needed|note \d+|edit|update|clarification needed)]""", RegexOption.IGNORE_CASE)
    private val PAREN = Regex("""\s*\(([^()]{0,200})\)""")
    private val IPA = Regex("""[ˈˌːəɪʊʃʒθðŋɛɔæɑɒʌ]""")
    private val ASIDE_WORDS = Regex("""\b(?:listen|pronounced|pronunciation|born|died|lit\.|from (?:the )?(?:Greek|Latin)|also spelled)\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""[ \t  -   　]+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?])""")
    private val BOILERPLATE = Regex(
        """cookie|subscribe|sign in|sign up|log in|javascript|privacy policy|terms of (?:use|service)|all rights reserved|""" +
            """copyright|advertis|newsletter|click here|this article|may refer to|retrieved|isbn|wikipedia|""" +
            """you are here|skip to|share this|follow us|accept all|download the app""",
        RegexOption.IGNORE_CASE,
    )
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "st", "mt", "vs", "etc", "approx", "no", "jr", "sr", "inc", "ltd", "co", "fig",
        "ca", "est", "gen", "col", "lt", "sgt", "prof", "rev", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec", "eg", "ie", "al", "cf", "dept", "univ", "sq", "ft", "lb", "oz",
    )
    private val BAD_HEADINGS = setOf(
        "contents", "references", "see also", "external links", "notes", "further reading", "gallery", "sources",
        "bibliography", "navigation menu", "related", "share", "comments", "advertisement", "footnotes",
        "citations", "related articles", "more", "menu", "search", "overview", "introduction", "summary",
    )

    /** "(65 mph)", "(33-98 ft)": imperial conversions that only add clutter on a TV card. */
    private val CONVERSION = Regex("""^\s*(?:about |around |up to |over |~)?[\d.,–\-  ]+\s*(?:ft|feet|mi|miles?|mph|lbs?|pounds?|in|inches|oz|yd|sq ?mi|sq ?ft|°F|F|tons?|short tons?)\s*$""", RegexOption.IGNORE_CASE)

    /** "(Acinonyx jubatus)": a scientific name. */
    private val BINOMIAL = Regex("""^\s*[A-Z][a-z]+ [a-z]{3,}\s*$""")

    /** Collapses whitespace, removes citation markers and pronunciation asides, tidies spacing around punctuation. */
    fun cleanSentence(raw: String): String {
        var s = CITATION.replace(raw, "")
        // Two passes so "(about 10 metres (33 ft))" loses the inner conversion, then is judged as a whole.
        repeat(2) {
            s = PAREN.replace(s) { m ->
                val inner = m.groupValues[1]
                val drop = IPA.containsMatchIn(inner) || inner.contains(';') || ASIDE_WORDS.containsMatchIn(inner) ||
                    inner.trimStart().startsWith("/") || inner.length > 60 || inner.isBlank() ||
                    CONVERSION.matches(inner) || BINOMIAL.matches(inner)
                if (drop) "" else m.value
            }
        }
        s = SPACES.replace(s, " ")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        return s.trim()
    }

    fun isBoilerplate(s: String): Boolean = BOILERPLATE.containsMatchIn(s)

    /** A text line that reads like a heading ("Speed", "Family life") rather than a sentence. */
    fun looksLikeHeading(line: String, known: Set<String>): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (known.contains(t.lowercase(Locale.ROOT))) return true
        if (t.length > 50) return false
        if (t.last() in ".!?:;,") return false
        val words = t.split(' ').filter { it.isNotBlank() }
        if (words.size > 7) return false
        return t.first().isUpperCase() || t.first().isDigit()
    }

    fun goodHeading(h: String?): String? {
        if (h == null) return null
        val t = h.trim().replace(Regex("""^\d+(\.\d+)*\s+"""), "").trim()
        if (t.length < 3 || t.length > 32 || t.contains(':')) return null
        if (BAD_HEADINGS.contains(t.lowercase(Locale.ROOT))) return null
        if (isBoilerplate(t)) return null
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** Splits page text into sentences, remembering the nearest heading above each one. */
    fun sentences(text: String, headings: List<String> = emptyList()): List<Sentence> {
        val known = headings.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val out = ArrayList<Sentence>()
        var heading: String? = null
        for (rawLine in text.split('\n')) {
            val line = SPACES.replace(rawLine, " ").trim()
            if (line.isEmpty()) continue
            if (looksLikeHeading(line, known)) {
                heading = line
                continue
            }
            for (piece in splitLine(line)) {
                val cleaned = cleanSentence(piece)
                if (cleaned.isNotEmpty()) out.add(Sentence(cleaned, out.size, heading))
            }
        }
        return out
    }

    /** Sentence boundary scan: ". " followed by a capital/digit/quote, except after abbreviations and initials. */
    fun splitLine(line: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if ((c == '.' || c == '!' || c == '?') && i + 1 < n && line[i + 1] == ' ') {
                var j = i + 1
                while (j < n && line[j] == ' ') j++
                val next = if (j < n) line[j] else ' '
                val startsNew = next.isUpperCase() || next.isDigit() || next == '"' || next == '“' || next == '('
                if (startsNew && !(c == '.' && isAbbreviation(line, i))) {
                    parts.add(line.substring(start, i + 1).trim())
                    start = j
                    i = j
                    continue
                }
            }
            i++
        }
        if (start < n) parts.add(line.substring(start).trim())
        return parts.filter { it.isNotEmpty() }
    }

    private fun isAbbreviation(line: String, dot: Int): Boolean {
        var k = dot - 1
        while (k >= 0 && (line[k].isLetter() || line[k] == '.')) k--
        val raw = line.substring(k + 1, dot)
        if (raw.isEmpty()) return false
        if (raw.contains('.')) return true // "U.S.", "e.g.", "a.m."
        if (raw.length == 1 && raw[0].isUpperCase()) return true // initials: "J. R. Tolkien"
        return raw.lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    /** Cuts at a word boundary so the result, ellipsis included, is at most [max] characters. */
    fun clamp(s: String, max: Int, ellipsis: Boolean = true): String {
        val t = s.trim()
        if (t.length <= max) return t
        val limit = if (ellipsis) max - 1 else max
        var cut = t.substring(0, minOf(t.length, limit + 1))
        val space = cut.lastIndexOf(' ')
        cut = if (space >= maxOf(8, limit / 2)) cut.substring(0, space) else t.substring(0, limit)
        if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
        cut = cut.trimEnd(' ', ',', ';', ':', '-', '–', '—', '(', '"', '\'')
        if (cut.isEmpty()) cut = t.substring(0, limit)
        return if (ellipsis) cut + ELLIPSIS else cut
    }

    /**
     * Shortens a sentence for a card: whole if it fits, else at a clause boundary (", " "; " " - "), else at a
     * word boundary, always ending in an ellipsis when something was cut.
     */
    fun shortLine(s: String, max: Int): String {
        val t = s.trim()
        if (t.length <= max) return t
        val window = t.substring(0, max - 1)
        val clause = listOf(", ", "; ", " — ", " – ", " - ", ": ").maxOf { window.lastIndexOf(it) }
        if (clause >= (max * 7) / 10) return window.substring(0, clause).trimEnd(',', ';', ':', ' ') + ELLIPSIS
        return clamp(t, max)
    }

    /** Lowercase, accents stripped, curly quotes and dashes unified, single spaces. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                ch == '‘' || ch == '’' || ch == '`' -> sb.append('\'')
                ch == '“' || ch == '”' -> sb.append('"')
                ch == '–' || ch == '—' || ch == '−' -> sb.append('-')
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return SPACES.replace(sb.toString(), " ").replace(Regex(" {2,}"), " ").trim()
    }

    /** Key for comparing item names: "Peregrine Falcons" == "peregrine falcon". */
    fun itemKey(s: String): String {
        val n = normalize(s).replace(Regex("""[^a-z0-9 ]+"""), " ")
        return n.split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
            if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
        }
    }

    /** Word-boundary matcher for a keyword in normalized text, tolerating plurals. */
    fun keywordRegex(keyword: String): Regex {
        val k = normalize(keyword)
        return Regex("""(?<![a-z0-9])""" + Regex.escape(k) + """(?:s|es)?(?![a-z0-9])""")
    }

    fun wordSet(s: String): Set<String> =
        Regex("""[a-z0-9]+""").findAll(normalize(s)).map { it.value }.filter { it.length > 2 }.toSet()

    fun similar(a: String, b: String): Boolean {
        val wa = wordSet(a)
        val wb = wordSet(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val inter = wa.intersect(wb).size.toDouble()
        return inter / wa.union(wb).size > 0.5
    }

    /** "Cheetah - Wikipedia" -> "Cheetah", "Mercury (planet) | NASA Space Place" -> "Mercury". */
    fun subjectOf(title: String, siteName: String?): String {
        val parts = title.split(" - ", " | ", " — ", " – ", " :: ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
        val site = siteName?.trim()?.lowercase(Locale.ROOT)
        val pick = parts.firstOrNull { it.lowercase(Locale.ROOT) != site && !it.equals("wikipedia", true) } ?: parts.firstOrNull() ?: ""
        return pick.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()
    }

    /** Stable 8-hex-digit hash (for ids). */
    fun hash8(s: String): String {
        var h = 0x811C9DC5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return String.format(Locale.ROOT, "%08x", h)
    }
}
