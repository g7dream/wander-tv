package tv.wander.ai

import tv.wander.model.CardDeck
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.SessionStats
import tv.wander.model.Verdict

/** On-device fallbacks so every feature still works with no backend or no network. Pure Kotlin. */
object OfflineAi {
    fun cards(page: PageText): CardDeck = CardDeck(page.title, page.url, emptyList(), fromAi = false)

    fun checkQuest(quest: Quest, page: PageText, alreadyFound: List<String>): Verdict =
        Verdict(false, null, "", null, fromAi = false)

    fun newQuest(recentTitles: List<String>): Quest? = null

    fun advise(stats: SessionStats, signal: DriftSignal): CompanionAdvice =
        CompanionAdvice(Mood.SLEEPY, signal.headline, null)
}
