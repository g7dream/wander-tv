package tv.wander.speech

import android.content.Context
import tv.wander.ai.AiClient

/** Reads cards and Lumo's lines aloud: Amazon Polly through the backend, Android TextToSpeech as fallback. */
class Narrator(context: Context, private val ai: AiClient) {
    var onSpeaking: ((Boolean) -> Unit)? = null

    fun speak(text: String) {}

    fun stop() {}

    fun shutdown() {}
}
