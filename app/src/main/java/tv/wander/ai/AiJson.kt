package tv.wander.ai

import org.json.JSONArray
import org.json.JSONObject
import tv.wander.model.Card
import tv.wander.model.CardDeck
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.RescueOption
import tv.wander.model.SessionStats
import tv.wander.model.Verdict

/**
 * Request bodies and response parsing for the backend API (SPEC section 6). Parsers return null when a response
 * is malformed, so [AiClient] falls back to [OfflineAi]; every value is re-clamped to the TV's limits.
 */
internal object AiJson {
    const val MAX_TEXT = 6000
    const val MAX_SPEAK = 600

    private const val BLOCKED_REASON = "Lumo can't judge this page. Let's try a different one!"
    private val BLOCKED_CARD = Card(
        "Let's skip this page",
        "Lumo can't make cards from this one. Let's wander somewhere brighter!",
        "🌙",
        "moon",
    )

    /** Page text capped at 6000 chars, never splitting an emoji's surrogate pair. */
    fun trimText(s: String, max: Int = MAX_TEXT): String {
        if (s.length <= max) return s
        var cut = s.substring(0, max)
        if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
        return cut
    }

    fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key, "").trim()

    // ------------------------------------------------------------------ /cards

    fun cardsRequest(page: PageText): JSONObject = JSONObject()
        .put("url", page.url)
        .put("title", page.title)
        .put("text", trimText(page.text))

    fun parseCards(json: JSONObject, page: PageText): CardDeck? {
        val cards = cardList(json.optJSONArray("cards"))
        if (cards.isEmpty()) return null
        return CardDeck(page.title, page.url, cards, fromAi = true)
    }

    /** Guardrail said no: show the server's safe card (never the page's own text). */
    fun blockedCards(json: JSONObject, page: PageText): CardDeck {
        val cards = try {
            cardList(json.optJSONArray("cards"))
        } catch (t: Throwable) {
            emptyList()
        }
        return CardDeck(page.title, page.url, cards.ifEmpty { listOf(BLOCKED_CARD) }, fromAi = true)
    }

    private fun cardList(arr: JSONArray?): List<Card> {
        if (arr == null) return emptyList()
        val out = ArrayList<Card>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = TextKit.clamp(str(o, "title"), OfflineCards.TITLE_MAX, ellipsis = false)
            val line = TextKit.clamp(str(o, "line"), OfflineCards.LINE_MAX)
            if (title.isEmpty() || line.isEmpty()) continue
            val emoji = str(o, "emoji").ifEmpty { EmojiMap.find(line, title)?.emoji ?: EmojiMap.DEFAULT }
            out.add(Card(title, line, emoji, str(o, "icon")))
            if (out.size == 5) break
        }
        return out
    }

    // ------------------------------------------------------------------ /quest/check

    fun checkRequest(quest: Quest, page: PageText, found: List<String>): JSONObject = JSONObject()
        .put("quest", JSONObject()
            .put("id", quest.id)
            .put("title", quest.title)
            .put("prompt", quest.prompt)
            .put("rubric", quest.rubric)
            .put("goal", quest.goal))
        .put("page", JSONObject()
            .put("url", page.url)
            .put("title", page.title)
            .put("text", trimText(page.text)))
        .put("found", JSONArray(found.filter { it.isNotBlank() }.take(20)))

    fun parseVerdict(json: JSONObject): Verdict? {
        if (!json.has("pass")) return null
        val pass = json.optBoolean("pass", false)
        val item = TextKit.clamp(str(json, "item"), 40, ellipsis = false).ifEmpty { null }
        if (pass && item == null) return null
        val reason = TextKit.clamp(str(json, "reason"), 120).ifEmpty {
            if (pass) "Yes! $item counts!" else "Not quite yet. Try another page!"
        }
        val evidence = if (pass) TextKit.clamp(str(json, "evidence"), 140).ifEmpty { null } else null
        return Verdict(pass, if (pass) item else null, reason, evidence, fromAi = true)
    }

    fun blockedVerdict(json: JSONObject): Verdict {
        val reason = try {
            TextKit.clamp(str(json, "reason"), 120)
        } catch (t: Throwable) {
            ""
        }
        return Verdict(false, null, reason.ifEmpty { BLOCKED_REASON }, null, fromAi = true)
    }

    // ------------------------------------------------------------------ /quest/new

    fun newQuestRequest(recentTitles: List<String>): JSONObject = JSONObject()
        .put("recent", JSONArray(recentTitles.map { TextKit.clamp(it, 120, ellipsis = false) }.filter { it.isNotBlank() }.take(12)))

    fun parseQuest(json: JSONObject): Quest? {
        val q = json.optJSONObject("quest") ?: return null
        val title = TextKit.clamp(str(q, "title"), 32, ellipsis = false)
        val prompt = TextKit.clamp(str(q, "prompt"), 140)
        if (title.isEmpty() || prompt.isEmpty()) return null
        val rawId = str(q, "id")
        val id = if (rawId.startsWith("lumo-") && rawId.length in 6..40) rawId else "lumo-" + TextKit.hash8("$title\n$prompt")
        return Quest(
            id = id,
            title = title,
            prompt = prompt,
            goal = q.optInt("goal", 2).coerceIn(2, 3),
            emoji = str(q, "emoji").ifEmpty { "✨" },
            rubric = TextKit.clamp(str(q, "rubric"), 400).ifEmpty { prompt },
            offline = emptyList(),
            generated = true,
        )
    }

    // ------------------------------------------------------------------ /companion

    fun companionRequest(stats: SessionStats, signal: DriftSignal): JSONObject = JSONObject()
        .put("signal", JSONObject()
            .put("reason", signal.reason.name)
            .put("headline", signal.headline)
            .put("detail", signal.detail))
        .put("stats", JSONObject()
            .put("sessionMinutes", stats.sessionMinutes)
            .put("pagesLast5Min", stats.pagesLast5Min)
            .put("avgDwellSec", stats.avgDwellSec)
            .put("scrollSecondsNoClick", stats.scrollSecondsNoClick)
            .put("sameSiteRepeats", stats.sameSiteRepeats)
            .put("localHour", stats.localHour)
            .put("pagesTotal", stats.pagesTotal))

    fun parseAdvice(json: JSONObject, local: CompanionAdvice): CompanionAdvice? {
        val line = TextKit.clamp(str(json, "line"), 120)
        if (line.isEmpty()) return null
        val moodName = str(json, "mood")
        val mood = Mood.values().firstOrNull { it.name.equals(moodName, ignoreCase = true) } ?: local.mood
        val suggestion = suggestion(json.optJSONObject("suggestion")) ?: local.suggestion
        return CompanionAdvice(mood, line, suggestion)
    }

    private fun suggestion(o: JSONObject?): RescueOption? {
        if (o == null) return null
        val label = TextKit.clamp(str(o, "label"), 40, ellipsis = false)
        val detail = TextKit.clamp(str(o, "detail"), 80)
        return when (str(o, "kind").lowercase()) {
            "quest" -> {
                val id = str(o, "questId")
                val known = LumoAdvice.QUESTS[id]
                when {
                    known != null -> RescueOption(RescueOption.Kind.QUEST, label.ifEmpty { known.label },
                        detail.ifEmpty { known.detail }, questId = id)
                    id.isNotEmpty() && label.isNotEmpty() -> RescueOption(RescueOption.Kind.QUEST, label, detail, questId = id)
                    else -> null
                }
            }
            "detour" -> {
                val url = str(o, "url")
                if (url.startsWith("https://") && label.isNotEmpty()) {
                    RescueOption(RescueOption.Kind.DETOUR, label, detail, url = url)
                } else null
            }
            "wind_down" -> if (label.isNotEmpty()) RescueOption(RescueOption.Kind.WIND_DOWN, label, detail) else null
            else -> null
        }
    }

    // ------------------------------------------------------------------ /speak

    fun speakRequest(text: String): JSONObject = JSONObject().put("text", trimText(text.trim(), MAX_SPEAK))
}
