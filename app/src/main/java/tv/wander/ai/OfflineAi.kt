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
     * appears at least twice in the text, and it is not already found. Generated quests without offline answers
     * get an honest "I can only check this online".
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
            val reason = if (dup != null) {
                "${dup.answer.item} is already on the board! Can you find a different one?"
            } else NO_MATCH_REASON
            return Verdict(false, null, TextKit.clamp(reason, REASON_MAX), null, fromAi = false)
        }

        val evidence = evidenceFor(page, pick.keyword) ?: pick.answer.fact.takeIf { it.isNotBlank() }
        val fact = pick.answer.fact.trim().replaceFirstChar { it.uppercaseChar() }
        val reason = if (fact.isEmpty()) "Yes! ${pick.answer.item} counts!" else "Yes! ${pick.answer.item} counts! $fact"
        return Verdict(
            pass = true,
            item = pick.answer.item,
            reason = TextKit.clamp(reason, REASON_MAX),
            evidence = evidence?.let { TextKit.clamp(it, EVIDENCE_MAX) },
            fromAi = false,
        )
    }

    /** The page sentence that mentions the keyword, trimmed around it to fit on screen. */
    private fun evidenceFor(page: PageText, keyword: String): String? {
        val regex = TextKit.keywordRegex(keyword)
        val sentence = TextKit.sentences(page.text.take(6000), page.headings)
            .firstOrNull { it.text.length >= 20 && regex.containsMatchIn(TextKit.normalize(it.text)) }
            ?.text ?: return null
        if (sentence.length <= EVIDENCE_MAX) return sentence
        val at = TextKit.normalize(sentence).let { regex.find(it)?.range?.first ?: 0 }
        val start = if (at < 50) 0 else sentence.lastIndexOf(' ', at - 40).coerceAtLeast(0)
        val window = sentence.substring(start).trim()
        val clipped = TextKit.clamp(window, if (start > 0) EVIDENCE_MAX - 1 else EVIDENCE_MAX)
        return if (start > 0) TextKit.ELLIPSIS + clipped else clipped
    }
}
