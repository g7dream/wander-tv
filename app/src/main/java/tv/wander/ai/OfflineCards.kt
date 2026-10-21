package tv.wander.ai

import tv.wander.model.Card
import tv.wander.model.CardDeck
import tv.wander.model.PageText

/**
 * "Make it cards" on the TV, without the cloud: picks the most informative sentences of the page (numbers,
 * superlatives, "known for"), titles them with the page's own headings where it can, and dresses each one as
 * a field-guide card.
 */
internal object OfflineCards {
    const val TITLE_MAX = 32
    const val LINE_MAX = 110
    private const val MIN_TEXT = 160

    private val UNIT_NUMBER = Regex(
        """\d[\d,.]*\s?(?:km/h|kph|mph|km|kilomet|miles?|met(?:er|re)s?|m\b|cm|mm|kg|kilograms?|grams?|tons?|tonnes|""" +
            """pounds?|lbs?|feet|ft|inch|years?|days?|hours?|minutes?|seconds?|°|degrees|percent|%|times|million|billion|species)""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER = Regex("""\b\d[\d,.]*\b""")
    private val SUPERLATIVE = Regex(
        """\b(?:fastest|largest|biggest|smallest|tallest|longest|oldest|deepest|heaviest|strongest|loudest|hottest|""" +
            """coldest|highest|rarest|brightest|smartest|world record|record|the most(?! recent| common| likely)|the only|the first|one of the few)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_WORD = Regex("""\b[A-Za-z]{11,}\b""")
    private val KNOWN_FOR = Regex("""\b(?:known for|famous for|nicknamed|called the|can reach|can run|can grow|can live|is able to|are able to)\b""", RegexOption.IGNORE_CASE)
    private val DEFINITION = Regex("""\b(?:is|are|was|were) (?:a|an|the|one of)\b""", RegexOption.IGNORE_CASE)

    private class Theme(val title: String, val emoji: String, val icon: String, val pattern: Regex)

    private val THEMES = listOf(
        Theme("Built for speed", "⚡", "speed", Regex("""\b(?:fast|faster|fastest|speed|sprint|km/h|mph|run|runs|swim at|dive)\b""", RegexOption.IGNORE_CASE)),
        Theme("Record breaker", "🏆", "record", SUPERLATIVE),
        Theme("On the menu", "🍽", "food", Regex("""\b(?:eats?|ate|prey|hunts?|hunting|diet|feeds?|food|graze|munch)\b""", RegexOption.IGNORE_CASE)),
        Theme("Home sweet home", "🏡", "home", Regex("""\b(?:lives? in|lived in|found in|habitat|native to|range|nests?|burrows?)\b""", RegexOption.IGNORE_CASE)),
        Theme("Size check", "📏", "size", Regex("""\b(?:weighs?|weight|tall|long|length|wide|size|kg|tons?|tonnes|meters|metres|feet)\b""", RegexOption.IGNORE_CASE)),
        Theme("Growing up", "🐣", "family", Regex("""\b(?:babies|baby|cubs?|chicks?|eggs?|young|calves|born|hatch\w*)\b""", RegexOption.IGNORE_CASE)),
        Theme("Super senses", "👀", "senses", Regex("""\b(?:eyes?|eyesight|see|sees|hear|hearing|smell|senses?|sight)\b""", RegexOption.IGNORE_CASE)),
        Theme("Out of this world", "🌌", "space", Regex("""\b(?:planet|moons?|stars?|orbit|galaxy|sun|space|telescope)\b""", RegexOption.IGNORE_CASE)),
        Theme("A bit of history", "📜", "history", Regex("""\b(?:century|centuries|ancient|years ago|in \d{3,4}|discovered|invented|founded|built in|history)\b""", RegexOption.IGNORE_CASE)),
        Theme("Famous for", "⭐", "star", KNOWN_FOR),
    )
    private val FALLBACK_TITLES = listOf("Did you know?" to "💡", "Here's a fun one" to "✨", "One more thing" to "🔍", "Lumo noticed" to "🦋")

    private class Candidate(val s: TextKit.Sentence, val score: Double)

    fun notMuch(page: PageText): CardDeck = CardDeck(
        sourceTitle = page.title,
        sourceUrl = page.url,
        cards = listOf(Card("Not much to read here", "This page is mostly links or pictures. Try a longer article for cards!", "🔍", "search")),
        fromAi = false,
    )

    fun build(page: PageText): CardDeck {
        val text = page.text.take(6000)
        val subject = TextKit.subjectOf(page.title, page.siteName)
        val sentences = TextKit.sentences(text, page.headings).filter { usable(it.text) }
        if (text.trim().length < MIN_TEXT || sentences.size < 2) return notMuch(page)

        val candidates = sentences.map { Candidate(it, score(it)) }
        val lead = candidates.firstOrNull { DEFINITION.containsMatchIn(it.s.text) && it.s.index < 4 } ?: candidates.first()
        val picked = mutableListOf(lead)
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.s.index })) {
            if (picked.size >= 5) break
            if (c === lead) continue
            if (picked.size >= 3 && c.score < 2.5) break // 4th and 5th cards must be genuinely interesting
            if (picked.any { TextKit.similar(it.s.text, c.s.text) }) continue
            picked.add(c)
        }
        picked.sortBy { it.s.index }

        val subjectHit = EmojiMap.find(subject, lead.s.text)
        val usedTitles = HashSet<String>()
        val cards = ArrayList<Card>()
        for (c in picked) {
            val isLead = c === lead
            val line = TextKit.shortLine(c.s.text, LINE_MAX)
            val title = titleFor(c, isLead, subject, usedTitles)
            usedTitles.add(title.lowercase())
            val emoji: String
            val icon: String
            if (isLead) {
                emoji = subjectHit?.emoji ?: EmojiMap.DEFAULT
                icon = subjectHit?.icon ?: "spark"
            } else {
                // A themed title keeps its emoji; otherwise show what this sentence is about (a cloud, a brain),
                // and only then a theme or the page's own emoji, so a deck is not five cheetahs in a row.
                val titled = THEMES.firstOrNull { it.title.equals(title, ignoreCase = true) }
                val other = EmojiMap.find(c.s.text, title, exclude = subjectHit?.emoji)
                val themed = THEMES.firstOrNull { it.pattern.containsMatchIn(c.s.text) }
                val fallback = FALLBACK_TITLES.firstOrNull { it.first == title }?.second
                emoji = titled?.emoji ?: other?.emoji ?: themed?.emoji ?: fallback ?: subjectHit?.emoji ?: EmojiMap.DEFAULT
                icon = titled?.icon ?: other?.icon ?: themed?.icon ?: subjectHit?.icon ?: "spark"
            }
            cards.add(Card(title, line, emoji, icon))
        }
        return CardDeck(sourceTitle = page.title, sourceUrl = page.url, cards = cards, fromAi = false)
    }

    private fun usable(s: String): Boolean {
        if (s.length < 40 || s.length > 320) return false
        if (s.split(' ').count { it.isNotBlank() } < 6) return false
        if (TextKit.isBoilerplate(s)) return false
        if (!s.first().isUpperCase() && !s.first().isDigit() && s.first() != '"' && s.first() != '“') return false
        if (s.endsWith(":") || s.endsWith("?")) return false
        val letters = s.count { it.isLetter() }
        val visible = s.count { !it.isWhitespace() }
        if (visible == 0 || letters.toDouble() / visible < 0.6) return false
        if (NUMBER.findAll(s).count() > 6) return false // tables and stat dumps
        return true
    }

    private fun score(s: TextKit.Sentence): Double {
        val t = s.text
        var score = 0.0
        score += when {
            s.index == 0 -> 3.0
            s.index <= 2 -> 1.5
            else -> 1.0 / (1.0 + s.index * 0.05)
        }
        if (UNIT_NUMBER.containsMatchIn(t)) score += 2.5 else if (NUMBER.containsMatchIn(t)) score += 1.0
        if (SUPERLATIVE.containsMatchIn(t)) score += 2.0
        if (KNOWN_FOR.containsMatchIn(t)) score += 1.5
        if (DEFINITION.containsMatchIn(t)) score += 0.5
        // Sentences that fit a card whole read best on a TV; long or jargon-heavy ones read worst.
        score += when {
            t.length in 50..LINE_MAX -> 1.2
            t.length <= 160 -> 0.4
            t.length > 220 -> -1.0
            else -> 0.0
        }
        score -= 0.7 * LONG_WORD.findAll(t).count()
        if (t.contains('(') || t.contains('[')) score -= 0.3
        return score
    }

    private fun titleFor(c: Candidate, isLead: Boolean, subject: String, used: Set<String>): String {
        if (isLead) {
            val t = TextKit.clamp(subject, TITLE_MAX, ellipsis = false).ifBlank { "What's this page?" }
            return t.replaceFirstChar { it.uppercaseChar() }
        }
        TextKit.goodHeading(c.s.heading)?.let { h ->
            if (h.lowercase() !in used && !h.equals(subject, ignoreCase = true)) return h
        }
        for (theme in THEMES) {
            if (theme.title.lowercase() !in used && theme.pattern.containsMatchIn(c.s.text)) return theme.title
        }
        for ((title, _) in FALLBACK_TITLES) {
            if (title.lowercase() !in used) return title
        }
        return "Fun fact"
    }
}
