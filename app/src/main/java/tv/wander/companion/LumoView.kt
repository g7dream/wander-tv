package tv.wander.companion

import android.content.Context
import android.view.View
import tv.wander.model.Mood

/** Lumo the moth, drawn on a Canvas with four moods, idle hover, wing flutter and blinking. */
class LumoView(context: Context) : View(context) {
    var mood: Mood = Mood.CURIOUS

    fun setThinking(on: Boolean) {}

    /** A happy wing-burst in a player's color. */
    fun celebrate(color: Int) {}
}
