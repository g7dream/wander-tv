package tv.wander.ai

import tv.wander.model.CardDeck
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftSignal
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.SessionStats
import tv.wander.model.Verdict

/**
 * Client for the Wander backend (API Gateway -> Lambda -> Bedrock / Polly / DynamoDB).
 * Every call answers exactly once, on the main thread via [post], and falls back to [OfflineAi]
 * when the backend is unset, slow or failing. Page text is only sent from cards/checkQuest.
 */
class AiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val post: (() -> Unit) -> Unit,
) {
    val configured: Boolean get() = baseUrl.isNotBlank()

    /** True after the last call reached the backend successfully. */
    val online: Boolean get() = false

    fun health(cb: (Boolean) -> Unit) {
        post { cb(false) }
    }

    fun cards(page: PageText, cb: (CardDeck) -> Unit) {
        post { cb(OfflineAi.cards(page)) }
    }

    fun checkQuest(quest: Quest, page: PageText, alreadyFound: List<String>, cb: (Verdict) -> Unit) {
        post { cb(OfflineAi.checkQuest(quest, page, alreadyFound)) }
    }

    fun newQuest(recentTitles: List<String>, cb: (Quest?) -> Unit) {
        post { cb(OfflineAi.newQuest(recentTitles)) }
    }

    fun advise(stats: SessionStats, signal: DriftSignal, cb: (CompanionAdvice) -> Unit) {
        post { cb(OfflineAi.advise(stats, signal)) }
    }

    /** MP3 bytes from Amazon Polly, or null (the caller then uses on-device TTS). */
    fun speak(text: String, cb: (ByteArray?) -> Unit) {
        post { cb(null) }
    }
}
