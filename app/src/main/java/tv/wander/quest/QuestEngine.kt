package tv.wander.quest

import tv.wander.model.FoundItem
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.QuestOutcome
import tv.wander.model.Verdict

/** Quest state: the active quest, what's been found by whom, and scoring. Pure Kotlin. */
class QuestEngine {
    var active: Quest? = null
        private set

    val found: List<FoundItem> get() = emptyList()

    fun start(quest: Quest) {}

    /** Applies the judge's verdict for [playerId]; awards points to that player in [players]. */
    fun apply(playerId: String, verdict: Verdict, players: List<Player>): QuestOutcome =
        QuestOutcome.Rejected(verdict.reason, QuestBoard(active!!, emptyList(), players, false))

    fun board(players: List<Player>): QuestBoard? = null

    fun end() {}

    companion object {
        const val POINTS_PER_ITEM = 100
        const val COMPLETION_BONUS = 50
    }
}
