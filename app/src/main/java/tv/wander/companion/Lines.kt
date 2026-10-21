package tv.wander.companion

import tv.wander.model.Mood

/** Lumo's local line bank, used offline and as instant reactions before the AI answers. */
object Lines {
    fun greeting(hour: Int): String = "Hi! I'm Lumo."
    fun forMood(mood: Mood): String = ""
    fun playerJoined(name: String): String = "Hi $name!"
}
