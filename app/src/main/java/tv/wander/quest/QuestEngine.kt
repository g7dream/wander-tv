package tv.wander.quest

import tv.wander.model.FoundItem
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.QuestOutcome
import tv.wander.model.Verdict
import java.text.Normalizer
import java.util.Locale

/** Quest state: the active quest, what's been found by whom, and scoring. Pure Kotlin. */
class QuestEngine {
    private val lock = Any()
    private val items = ArrayList<FoundItem>()

    var active: Quest? = null
        private set

    val found: List<FoundItem> get() = synchronized(lock) { items.toList() }

    /** True once the active quest has as many distinct finds as its goal. */
    val isComplete: Boolean get() = synchronized(lock) { completeLocked() }

    /** Who found [item] (any spelling or alias of it), or null. */
    fun whoFound(item: String): FoundItem? {
        synchronized(lock) {
            val quest = active ?: return null
            val key = normalize(canonicalItem(quest, item))
            return if (key.isEmpty()) null else items.firstOrNull { normalize(it.item) == key }
        }
    }

    fun start(quest: Quest) {
        synchronized(lock) {
            active = quest
            items.clear()
        }
    }

    /** Applies the judge's verdict for [playerId]; awards points to that player in [players]. */
    fun apply(playerId: String, verdict: Verdict, players: List<Player>): QuestOutcome {
        synchronized(lock) {
            return applyLocked(playerId, verdict, players)
        }
    }

    private fun applyLocked(playerId: String, verdict: Verdict, players: List<Player>): QuestOutcome {
        val quest = active
            ?: return QuestOutcome.Rejected(NO_QUEST_REASON, QuestBoard(NO_QUEST, emptyList(), ranked(players), false))
        if (completeLocked()) {
            return QuestOutcome.Rejected(ALREADY_DONE_REASON, boardLocked(quest, players))
        }
        if (!verdict.pass) {
            val reason = verdict.reason.trim().ifEmpty { DEFAULT_REJECT_REASON }
            return QuestOutcome.Rejected(reason, boardLocked(quest, players))
        }
        val raw = verdict.item?.let { cleanItem(it) }.orEmpty()
        if (normalize(raw).isEmpty()) {
            return QuestOutcome.Rejected(NO_ITEM_REASON, boardLocked(quest, players))
        }
        val item = canonicalItem(quest, raw)
        val key = normalize(item)
        val first = items.firstOrNull { normalize(it.item) == key }
        if (first != null) {
            return QuestOutcome.Duplicate(first.item, boardLocked(quest, players))
        }
        val evidence = verdict.evidence?.trim()?.takeIf { it.isNotEmpty() }
        val find = FoundItem(item, playerId, evidence)
        items.add(find)
        var points = POINTS_PER_ITEM
        if (completeLocked()) points += COMPLETION_BONUS
        players.firstOrNull { it.id == playerId }?.let { it.score += points }
        return QuestOutcome.Accepted(find, points, boardLocked(quest, players))
    }

    fun board(players: List<Player>): QuestBoard? {
        synchronized(lock) {
            val quest = active ?: return null
            return boardLocked(quest, players)
        }
    }

    fun end() {
        synchronized(lock) {
            active = null
            items.clear()
        }
    }

    private fun completeLocked(): Boolean {
        val quest = active ?: return false
        return items.size >= quest.goal.coerceAtLeast(1)
    }

    private fun boardLocked(quest: Quest, players: List<Player>): QuestBoard =
        QuestBoard(quest, items.toList(), ranked(players), completeLocked())

    companion object {
        const val POINTS_PER_ITEM = 100
        const val COMPLETION_BONUS = 50

        private const val NO_QUEST_REASON = "No quest is running right now. Pick one first!"
        private const val ALREADY_DONE_REASON = "We already finished this quest! Pick a new one."
        private const val DEFAULT_REJECT_REASON = "Not quite this time. Try another page!"
        private const val NO_ITEM_REASON = "I couldn't tell what this page found. Try one that names it."
        private const val MAX_ITEM_CHARS = 40

        private val NO_QUEST = Quest("none", "No quest", "", 1, "🧭", "")
        private val ARTICLES = setOf("the", "a", "an")
        private val IRREGULAR = mapOf(
            "mice" to "mouse", "geese" to "goose", "teeth" to "tooth", "feet" to "foot",
            "octopi" to "octopus", "octopodes" to "octopus", "cacti" to "cactus", "fungi" to "fungus",
            "wolves" to "wolf", "leaves" to "leaf", "knives" to "knife", "halves" to "half",
            "oxen" to "ox", "children" to "child", "people" to "person", "men" to "man", "women" to "woman",
            "dice" to "die", "lice" to "louse",
        )
        private val MARKS = Regex("\\p{Mn}+")
        private val PARENS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
        private val NON_WORD = Regex("[^a-z0-9]+")
        private val SPACES = Regex("\\s+")

        /** Players best first; ties keep join order. */
        fun ranked(players: List<Player>): List<Player> = players.sortedByDescending { it.score }

        /**
         * Comparison key for duplicate detection: case, accents, punctuation, articles, bracketed notes and
         * plurals are ignored, so "The Cheetahs!", "cheetah" and "Cheetah (Acinonyx)" are the same find.
         */
        fun normalize(item: String): String {
            var s = item.lowercase(Locale.ROOT)
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
            s = s.replace(PARENS, " ")
            s = s.replace("&", " and ")
            s = s.replace("'", "").replace("’", "").replace("‘", "").replace("`", "")
            s = s.replace(NON_WORD, " ").trim()
            if (s.isEmpty()) return ""
            return s.split(' ')
                .filter { it.isNotEmpty() && it !in ARTICLES }
                .joinToString(" ") { singular(it) }
        }

        /**
         * Maps a judge's item name onto the quest's own spelling when it is a known alias (AI says "T. rex",
         * the catalog says "Tyrannosaurus rex"), so the AI judge and the offline judge never double count.
         */
        fun canonicalItem(quest: Quest, raw: String): String {
            val clean = cleanItem(raw)
            val key = normalize(clean)
            if (key.isEmpty()) return clean
            for (answer in quest.offline) {
                if (normalize(answer.item) == key || answer.keywords.any { normalize(it) == key }) return answer.item
            }
            return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        /**
         * One friendly sentence for an outcome, in Lumo's voice: for speech, toasts and the outcome banner.
         * Duplicates name whoever found the item first.
         */
        fun lineFor(outcome: QuestOutcome, players: List<Player>): String = when (outcome) {
            is QuestOutcome.Accepted -> {
                val name = players.firstOrNull { it.id == outcome.found.playerId }?.name
                val who = if (name.isNullOrBlank()) "" else " for $name"
                if (outcome.points > POINTS_PER_ITEM) {
                    "Yes! ${outcome.found.item} counts, and that finishes the quest. +${outcome.points}$who!"
                } else {
                    "Yes! ${outcome.found.item} counts. +${outcome.points}$who!"
                }
            }
            is QuestOutcome.Duplicate -> {
                val key = normalize(outcome.item)
                val first = outcome.board.found.firstOrNull { normalize(it.item) == key }
                val finder = first?.let { f -> players.firstOrNull { it.id == f.playerId }?.name }
                if (finder.isNullOrBlank()) {
                    "${outcome.item} is already on the board. Try a different one!"
                } else {
                    "${outcome.item} is already on the board. $finder found it first!"
                }
            }
            is QuestOutcome.Rejected -> outcome.reason
        }

        private fun cleanItem(raw: String): String {
            val s = raw.replace(SPACES, " ").trim().trim('"', '\'', '“', '”', '.', '!', '?', ',', ';', ':').trim()
            return if (s.length <= MAX_ITEM_CHARS) s else s.take(MAX_ITEM_CHARS).trimEnd()
        }

        private fun singular(word: String): String {
            IRREGULAR[word]?.let { return it }
            if (word.length <= 3) return word
            return when {
                word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
                word.endsWith("sses") -> word.dropLast(2)
                word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("zes") -> word.dropLast(2)
                word.endsWith("uses") -> word.dropLast(2)
                word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") -> word
                word.endsWith("s") -> word.dropLast(1)
                else -> word
            }
        }
    }
}
