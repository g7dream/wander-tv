package tv.wander.quest

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.QuestOutcome

/** Quest UI: picker, intro card, the scoreboard panel that rides along while browsing, results, finale. */
class QuestOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onQuestPicked(quest: Quest)
        fun onLumoPickRequested()
        fun onQuestPickerClosed()
        fun onIntroDismissed()
        fun onFinaleDismissed()
    }

    /** True while the picker, intro or finale covers the screen (it then owns the keys). */
    val isModal: Boolean get() = false

    fun showPicker(quests: List<Quest>) {}

    fun showIntro(quest: Quest) {}

    /** The scoreboard panel; null hides it. */
    fun showBoard(board: QuestBoard?) {}

    fun showChecking(player: Player) {}

    fun showOutcome(outcome: QuestOutcome, player: Player) {}

    fun showFinale(board: QuestBoard) {}

    fun hideModal() {}

    fun handleKey(event: KeyEvent): Boolean = false
}
