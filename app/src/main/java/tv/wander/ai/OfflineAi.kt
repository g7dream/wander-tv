package tv.wander.ai

import tv.wander.model.CardDeck
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftSignal
import tv.wander.model.OfflineAnswer
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.SessionStats
import tv.wander.model.Verdict

/** On-device fallbacks so every feature still works with no backend or no network. Pure Kotlin. */
object OfflineAi {
    private const val REASON_MAX = 120
    private const val EVIDENCE_MAX = 140

    /** A number with the kind of unit quests are judged on (speed, depth, moons, weight...). */
    private val MEASUREMENT = Regex(
        "\\d[\\d,.]*\\s*(km/h|kph|kilomet(re|er)s?|mph|miles?|met(re|er)s?|m\\b|ft\\b|feet|moons?|kg\\b|kilograms?|" +
            "tonnes?|tons?|pounds?|lbs?|times|million|percent|%)",
        RegexOption.IGNORE_CASE,
    )

    /** The kind of fact this quest is judged on, read from its prompt and rubric; null when there is none. */
    private fun topicalFact(quest: Quest): Regex? {
        val about = (quest.prompt + " " + quest.rubric).lowercase()
        val parts = buildList {
            if (Regex("km/h|mph|fast|speed").containsMatchIn(about)) {
                add("\\d[\\d,.]*\\s*(km/h|kph|kilomet(re|er)s?\\s+(per|an)\\s+hour|mph|miles\\s+(per|an)\\s+hour)")
            }
            if ("moon" in about) add("\\d[\\d,.]*\\s*(known\\s+|confirmed\\s+)?moons?")
            if (Regex("met(re|er)s|deep|depth").containsMatchIn(about)) add("\\d[\\d,.]*\\s*(m|met(re|er)s?|feet|ft)\\b")
            if ("weight" in about) add("(\\d[\\d,.]*\\s*times|times\\s+(its|their)\\s+(own\\s+)?(body\\s+)?weight)")
            if ("cretaceous" in about) add("cretaceous")
        }
        return if (parts.isEmpty()) null else Regex(parts.joinToString("|", "(", ")"), RegexOption.IGNORE_CASE)
    }

    /** A sentence that carries on about the previous one's subject. */
    private val CONTINUES = Regex("^(It|Its|They|Their|These|This|He|She)\\b")

    const val ONLINE_ONLY_REASON = "I can only check this quest when I'm online. Let's try again in a moment!"
    private const val NO_MATCH_REASON = "I couldn't spot a quest answer on this page yet. Try a page about just one!"
    private const val CONFUSED_REASON = "This page confused my antennae. Let's try another one!"

    /** 3-5 field-guide cards from the page's own text, or one friendly "Not much to read here" card. */
    fun cards(page: PageText): CardDeck = try {
        OfflineCards.build(page)
    } catch (t: Throwable) {
        OfflineCards.notMuch(page)
    }

    /**
     * Judges a quest from [Quest.offline]: an answer matches when one of its keywords is in the page title or
     * appears at least twice in the text. A new answer wins; a page that only proves an answer already found
     * passes with that item, so the quest engine scores it as a duplicate and names who found it first.
     * Generated quests without offline answers get an honest "I can only check this online".
     */
    fun checkQuest(quest: Quest, page: PageText, alreadyFound: List<String>): Verdict = try {
        judge(quest, page, alreadyFound)
    } catch (t: Throwable) {
        Verdict(false, null, CONFUSED_REASON, null, fromAi = false)
    }

    /** "Lumo's pick" from recent page titles: a topic-aware quest the TV can judge on its own. */
    fun newQuest(recentTitles: List<String>): Quest? = try {
        OfflineQuests.pick(recentTitles).toQuest()
    } catch (t: Throwable) {
        OfflineQuests.RECORD_BREAKERS.toQuest()
    }

    /** Lumo's reaction to a drift signal: a mood, one line and a better thing to do. */
    fun advise(stats: SessionStats, signal: DriftSignal): CompanionAdvice = try {
        LumoAdvice.advise(stats, signal)
    } catch (t: Throwable) {
        CompanionAdvice(tv.wander.model.Mood.SLEEPY, signal.headline, LumoAdvice.DETOURS["moth"])
    }

    // ------------------------------------------------------------------ quest judge

    private class Match(val answer: OfflineAnswer, val keyword: String, val inTitle: Boolean, val count: Int) {
        val strength: Int get() = (if (inTitle) 1000 else 0) + count * 10 + keyword.length
    }

    private fun judge(quest: Quest, page: PageText, alreadyFound: List<String>): Verdict {
        if (quest.offline.isEmpty()) return Verdict(false, null, ONLINE_ONLY_REASON, null, fromAi = false)
        val title = TextKit.normalize(page.title)
        val text = TextKit.normalize(page.text.take(6000))
        val found = alreadyFound.map { TextKit.itemKey(it) }.filter { it.isNotEmpty() }.toSet()

        val fresh = ArrayList<Match>()
        val repeats = ArrayList<Match>()
        for (answer in quest.offline) {
            val keywords = (answer.keywords + answer.item).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val already = TextKit.itemKey(answer.item) in found || keywords.any { TextKit.itemKey(it) in found }
            var best: Match? = null
            for (k in keywords) {
                val regex = TextKit.keywordRegex(k)
                val inTitle = regex.containsMatchIn(title)
                val count = regex.findAll(text).count()
                if (inTitle || count >= 2) {
                    val m = Match(answer, k, inTitle, count)
                    if (best == null || m.strength > best.strength) best = m
                }
            }
            if (best != null) (if (already) repeats else fresh).add(best)
        }

        // "Snow leopard" beats "Leopard" when the page is about snow leopards.
        val specific = fresh.filter { m ->
            fresh.none { o ->
                o !== m && o.keyword.length > m.keyword.length &&
                    TextKit.keywordRegex(m.keyword).containsMatchIn(TextKit.normalize(o.keyword))
            }
        }
        val pick = specific.maxByOrNull { it.strength }
        if (pick == null) {
            val dup = repeats.maxByOrNull { it.strength }
                ?: return Verdict(false, null, TextKit.clamp(NO_MATCH_REASON, REASON_MAX), null, fromAi = false)
            // The page does prove an answer, just one that's already on the board. Naming it lets the quest
            // engine score it as a duplicate, so Lumo can say who found it first (SPEC section 8).
            val reason = "${dup.answer.item} is already on the board! Can you find a different one?"
            return Verdict(true, dup.answer.item, TextKit.clamp(reason, REASON_MAX), null, fromAi = false)
        }

        val line = pick.answer.fact.trim().replaceFirstChar { it.uppercaseChar() }
        val evidence = evidenceFor(page, pick.keyword, quest, haveFact = line.isNotEmpty()) ?: line.takeIf { it.isNotEmpty() }
        val reason = if (line.isEmpty()) "Yes! ${pick.answer.item} counts!" else "Yes! ${pick.answer.item} counts! $line"
        return Verdict(
            pass = true,
            item = pick.answer.item,
            reason = TextKit.clamp(reason, REASON_MAX),
            evidence = evidence?.let { TextKit.clamp(it, EVIDENCE_MAX) },
            fromAi = false,
        )
    }

    /**
     * The page sentence that proves the find, trimmed around the keyword to fit on screen. Candidates are the
     * sentences naming the keyword plus the follow-up that carries on about it ("It can run up to 105 km/h").
     * A sentence with the quest's own kind of fact wins (a speed for Speed Safari, moons for Moon Collectors),
     * then any measurement, then the first mention. When the quest is judged on a kind of fact the page never
     * states near the find (Simple English Wikipedia's "Golden eagle" has no speed), null when [haveFact] so the
     * caller quotes Lumo's own fact instead of a sentence that proves nothing.
     */
    private fun evidenceFor(page: PageText, keyword: String, quest: Quest, haveFact: Boolean): String? {
        val regex = TextKit.keywordRegex(keyword)
        val sentences = TextKit.sentences(page.text.take(6000), page.headings).filter { it.text.length >= 20 }
        val mentions = sentences.indices.filter { regex.containsMatchIn(TextKit.normalize(sentences[it].text)) }
        if (mentions.isEmpty()) return null
        val followUps = mentions.map { it + 1 }
            .filter { it < sentences.size && it !in mentions && CONTINUES.containsMatchIn(sentences[it].text) }
        val topical = topicalFact(quest)
        // A measurement, not just any digit: infobox runs like "Temporal range: 1.9 mya" must not win.
        fun score(i: Int): Int {
            val text = sentences[i].text
            if (text.length > 260) return 0
            return when {
                topical?.containsMatchIn(text) == true -> 3
                MEASUREMENT.containsMatchIn(text) -> 1
                else -> 0
            }
        }
        val candidates = (mentions + followUps).sorted()
        val best = candidates.maxByOrNull { score(it) * 10_000 - it } ?: return null
        if (topical != null && haveFact && score(best) < 3) return null
        val pick = if (score(best) > 0 || best in mentions) best else mentions.first()
        val sentence = sentences[pick].text
        if (pick !in mentions) return TextKit.clamp(sentence, EVIDENCE_MAX)
        if (sentence.length <= EVIDENCE_MAX) return sentence
        val at = TextKit.normalize(sentence).let { regex.find(it)?.range?.first ?: 0 }
        val start = if (at < 50) 0 else sentence.lastIndexOf(' ', at - 40).coerceAtLeast(0)
        val window = sentence.substring(start).trim()
        val clipped = TextKit.clamp(window, if (start > 0) EVIDENCE_MAX - 1 else EVIDENCE_MAX)
        return if (start > 0) TextKit.ELLIPSIS + clipped else clipped
    }
}
