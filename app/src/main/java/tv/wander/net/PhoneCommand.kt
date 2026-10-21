package tv.wander.net

/** Everything a phone can ask the TV to do. Parsed from the WebSocket JSON in PhoneHub (see SPEC.md). */
sealed class PhoneCommand {
    data class Move(val dx: Float, val dy: Float) : PhoneCommand()
    object Tap : PhoneCommand()
    data class Scroll(val dx: Float, val dy: Float) : PhoneCommand()
    data class Go(val text: String) : PhoneCommand()
    data class Type(val text: String, val submit: Boolean) : PhoneCommand()

    /** back, home, menu, up, down, left, right, enter, playpause */
    data class Key(val key: String) : PhoneCommand()
    object MakeCards : PhoneCommand()
    object QuestSubmit : PhoneCommand()

    /** "lumo" asks for an AI-generated quest. */
    data class QuestStart(val questId: String) : PhoneCommand()
    object ReadAloud : PhoneCommand()

    /** 0 or 1 picks a rescue option, -1 dismisses. */
    data class Rescue(val choice: Int) : PhoneCommand()
    data class Zoom(val delta: Int) : PhoneCommand()
}
