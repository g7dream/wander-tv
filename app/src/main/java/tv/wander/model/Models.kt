package tv.wander.model

// Shared value types. Every package depends on these and nothing else in this file depends on Android,
// so pure-logic classes (DriftDetector, QuestEngine, OfflineAi) stay JVM-testable.

/** A phone that joined the couch. [colorIndex] indexes Palette.PLAYERS (0 Sun, 1 Berry, 2 Mint, 3 Sky). */
data class Player(
    val id: String,
    val name: String,
    val colorIndex: Int,
    var score: Int = 0,
    var connected: Boolean = true,
)

/** What the TV is showing; phones adapt their controls to it. */
enum class Mode { HOME, BROWSE, CARDS, QUEST_PICKER, QUEST_INTRO, RESCUE, COMPANION }

enum class Mood { CURIOUS, HAPPY, SLEEPY, CONCERNED }

/** Readable text pulled out of the current page (Readability.js), capped at ~6000 chars. */
data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val headings: List<String> = emptyList(),
    val siteName: String? = null,
)

data class Card(
    val title: String,
    val line: String,
    val emoji: String,
    /** One-word icon keyword ("speed", "planet"...), used when an emoji is missing. */
    val icon: String = "",
)

data class CardDeck(
    val sourceTitle: String,
    val sourceUrl: String,
    val cards: List<Card>,
    /** False when the deck came from the on-device fallback instead of Bedrock. */
    val fromAi: Boolean,
)

/** An item the offline judge accepts for a curated quest, e.g. Cheetah with keywords ["cheetah"]. */
data class OfflineAnswer(val item: String, val keywords: List<String>, val fact: String)

data class Quest(
    val id: String,
    val title: String,
    val prompt: String,
    /** Distinct items needed to finish. */
    val goal: Int,
    val emoji: String,
    /** Plain-language judging rubric sent to the AI judge. */
    val rubric: String,
    val offline: List<OfflineAnswer> = emptyList(),
    /** True for "Lumo's pick" quests generated from the household's recent topics. */
    val generated: Boolean = false,
)

data class Verdict(
    val pass: Boolean,
    /** Canonical name of the thing found ("Peregrine falcon"), used to reject duplicates. */
    val item: String?,
    /** One friendly sentence Lumo says out loud. */
    val reason: String,
    /** A short quote from the page that proves it, shown on screen. */
    val evidence: String?,
    val fromAi: Boolean,
)

data class FoundItem(val item: String, val playerId: String, val evidence: String?)

data class QuestBoard(
    val quest: Quest,
    val found: List<FoundItem>,
    val players: List<Player>,
    val complete: Boolean,
)

sealed class QuestOutcome {
    abstract val board: QuestBoard

    data class Accepted(val found: FoundItem, val points: Int, override val board: QuestBoard) : QuestOutcome()
    data class Duplicate(val item: String, override val board: QuestBoard) : QuestOutcome()
    data class Rejected(val reason: String, override val board: QuestBoard) : QuestOutcome()
}

enum class DriftReason { RAPID_HOPPING, ENDLESS_SCROLL, SITE_LOOP, LATE_NIGHT }

/** Raised by DriftDetector. [headline] is what Lumo says; [detail] is the measured "why", shown small. */
data class DriftSignal(val reason: DriftReason, val headline: String, val detail: String)

/** Timing-only session signals. No URLs or page text: this is all that drift detection ever sees. */
data class SessionStats(
    val sessionMinutes: Int,
    val pagesLast5Min: Int,
    val avgDwellSec: Int,
    val scrollSecondsNoClick: Int,
    val sameSiteRepeats: Int,
    val localHour: Int,
    val pagesTotal: Int,
)

data class RescueOption(
    val kind: Kind,
    val label: String,
    val detail: String,
    val url: String? = null,
    val questId: String? = null,
) {
    enum class Kind { QUEST, DETOUR, WIND_DOWN }
}

/** The companion's reaction to a drift signal: from Bedrock (/companion) or from local lines. */
data class CompanionAdvice(val mood: Mood, val line: String, val suggestion: RescueOption?)

/** Snapshot broadcast to every phone whenever something visible changes. Serialized by PhoneHub. */
data class TvState(
    val mode: Mode,
    val title: String = "",
    val url: String = "",
    val quest: QuestBoard? = null,
    val cardIndex: Int = 0,
    val cardCount: Int = 0,
    val cardTitle: String? = null,
    val rescue: DriftSignal? = null,
    val rescueOptions: List<RescueOption> = emptyList(),
    val textFieldFocused: Boolean = false,
    val fieldLabel: String? = null,
    val checking: Boolean = false,
    val aiOnline: Boolean = false,
    val quests: List<Quest> = emptyList(),
)
