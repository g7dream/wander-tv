package tv.wander.companion

import android.content.Context
import android.widget.FrameLayout
import tv.wander.model.Mood

/** Lumo plus its speech bubble, pinned to the bottom-right safe corner, above every other layer. */
class CompanionLayer(context: Context) : FrameLayout(context) {
    var mood: Mood = Mood.CURIOUS

    fun say(text: String, mood: Mood? = null, holdMs: Long = 6000) {}

    fun thinking(on: Boolean) {}

    fun celebrate(color: Int) {}

    fun hush() {}

    /** Large on Home and during a rescue; small in the corner while browsing. */
    fun setBig(big: Boolean) {}
}
