package tv.wander.companion

import tv.wander.model.DriftReason
import tv.wander.model.Mood
import tv.wander.model.RescueOption
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Lumo's local line bank, used offline and as instant reactions before the AI answers.
 *
 * Voice: warm, short, a little funny, sometimes moth-flavored. Lumo offers, it never scolds. Every
 * function returns a finished sentence (no placeholders left), never repeats the same line twice in a
 * row for a bank, and is safe to call from any thread. Pure Kotlin, so it is unit-testable.
 */
object Lines {
    private val lock = Any()
    private val lastPick = HashMap<String, Int>()

    /** Source of variety. Tests may swap in a seeded Random. */
    @Volatile
    internal var random: Random = Random.Default

    // ---------------------------------------------------------------- the three original contracts

    /** A hello that fits the time of day ([hour] is local 0-23). */
    fun greeting(hour: Int): String {
        val h = ((hour % 24) + 24) % 24
        return when (h) {
            in 5..11 -> pick("greet.morning", MORNING)
            in 12..16 -> pick("greet.afternoon", AFTERNOON)
            in 17..21 -> pick("greet.evening", EVENING)
            else -> pick("greet.late", LATE)
        }
    }

    /** A short line that sounds like [mood]. */
    fun forMood(mood: Mood): String = when (mood) {
        Mood.CURIOUS -> pick("mood.curious", CURIOUS)
        Mood.HAPPY -> pick("mood.happy", HAPPY)
        Mood.SLEEPY -> pick("mood.sleepy", SLEEPY)
        Mood.CONCERNED -> pick("mood.concerned", CONCERNED)
    }

    /** Lumo welcomes a phone that just joined the couch. */
    fun playerJoined(name: String): String = fill(pick("player.joined", JOINED), "name" to cleanName(name))

    // ---------------------------------------------------------------- players and phones

    fun playerLeft(name: String): String = fill(pick("player.left", LEFT), "name" to cleanName(name))

    /** Nudge shown on Home while no phone has joined yet. */
    fun scanHint(): String = pick("scan.hint", SCAN_HINT)

    /** When a fifth phone tries to join. */
    fun couchFull(): String = pick("couch.full", COUCH_FULL)

    // ---------------------------------------------------------------- cards

    fun cardsMaking(): String = pick("cards.making", CARDS_MAKING)

    /** Cards are on screen. [fromAi] = false means the on-device fallback made them. */
    fun cardsReady(count: Int, fromAi: Boolean = true): String {
        val bank = if (fromAi) CARDS_READY else CARDS_READY_OFFLINE
        return fill(pick(if (fromAi) "cards.ready" else "cards.offline", bank), "n" to count.coerceAtLeast(1).toString())
    }

    fun cardsFailed(): String = pick("cards.failed", CARDS_FAILED)

    /** Before reading a card aloud. */
    fun readingAloud(): String = pick("cards.read", READ_ALOUD)

    // ---------------------------------------------------------------- quests

    fun questStart(title: String): String = fill(pick("quest.start", QUEST_START), "title" to title.trim().ifEmpty { "A new quest" })

    /** While the judge looks at a submitted page. [name] is who submitted it, if known. */
    fun questChecking(name: String? = null): String {
        val n = name?.trim().orEmpty()
        return if (n.isEmpty()) pick("quest.checking", QUEST_CHECKING) else fill(pick("quest.checking.name", QUEST_CHECKING_NAME), "name" to cleanName(n))
    }

    fun questPass(name: String, item: String, points: Int): String =
        fill(pick("quest.pass", QUEST_PASS), "name" to cleanName(name), "item" to item.trim().ifEmpty { "That one" }, "points" to points.toString())

    /** A duplicate find. [firstFinder] is the name of whoever found [item] first, if known. */
    fun questDuplicate(item: String, firstFinder: String? = null): String {
        val i = item.trim().ifEmpty { "That one" }
        val f = firstFinder?.trim().orEmpty()
        return if (f.isEmpty()) fill(pick("quest.dup", QUEST_DUPLICATE), "item" to i)
        else fill(pick("quest.dup.name", QUEST_DUPLICATE_NAME), "item" to i, "name" to cleanName(f))
    }

    /** Not accepted. A judge's [reason] wins when present; otherwise a kind "keep looking". */
    fun questFail(reason: String? = null): String {
        val r = reason?.trim().orEmpty()
        return if (r.isNotEmpty()) r else pick("quest.fail", QUEST_FAIL)
    }

    /** Everything found. [bonusTo] is the name of whoever completed it. */
    fun questComplete(bonusTo: String? = null): String {
        val n = bonusTo?.trim().orEmpty()
        return if (n.isEmpty()) pick("quest.done", QUEST_DONE) else fill(pick("quest.done.name", QUEST_DONE_NAME), "name" to cleanName(n))
    }

    /** "Lumo's pick" is being invented from tonight's trail. */
    fun questInventing(): String = pick("quest.inventing", QUEST_INVENTING)

    // ---------------------------------------------------------------- rescue

    /**
     * The headline Lumo says when a drift rule fires. [count] is pages (hopping) or visits (site loop),
     * [seconds] is the measured span (hopping, loop), scroll time, or session length (late night),
     * [hour] is the local hour (late night).
     */
    fun rescueHeadline(reason: DriftReason, count: Int, seconds: Int, hour: Int = 23): String {
        val bank = when (reason) {
            DriftReason.RAPID_HOPPING -> HOPPING
            DriftReason.ENDLESS_SCROLL -> SCROLLING
            DriftReason.SITE_LOOP -> LOOPING
            DriftReason.LATE_NIGHT -> LATE_NIGHT
        }
        val line = fill(
            pick("rescue." + reason.name, bank),
            "n" to count.toString(),
            "dur" to spokenDuration(seconds),
            "hour" to hourWords(hour),
        )
        return capitalize(line)
    }

    /** Lumo reacting to the family picking a rescue option. */
    fun rescueAccepted(kind: RescueOption.Kind): String = when (kind) {
        RescueOption.Kind.QUEST -> pick("rescue.ok.quest", ACCEPT_QUEST)
        RescueOption.Kind.DETOUR -> pick("rescue.ok.detour", ACCEPT_DETOUR)
        RescueOption.Kind.WIND_DOWN -> pick("rescue.ok.wind", ACCEPT_WIND)
    }

    /** "We're fine": Lumo backs off gracefully (and the rule relaxes). */
    fun rescueDismissed(): String = pick("rescue.dismissed", DISMISSED)

    /** A gentle late-night wind-down line, outside of a rescue. */
    fun windDown(): String = pick("winddown", WIND_DOWN)

    /**
     * Two ready-made ways out for [reason], used when the AI has no suggestion. Late night offers a
     * wind-down first; the others mix a family quest and a curious detour.
     */
    fun fallbackOptions(reason: DriftReason): List<RescueOption> {
        val quest = questOption()
        val detour = detourOption()
        return when (reason) {
            DriftReason.RAPID_HOPPING -> listOf(quest, detour)
            DriftReason.ENDLESS_SCROLL, DriftReason.SITE_LOOP -> listOf(detour, quest)
            DriftReason.LATE_NIGHT -> listOf(
                RescueOption(RescueOption.Kind.WIND_DOWN, "Moonlight reading", "One slow page about the night sky, then lamps off.", url = "https://en.wikipedia.org/wiki/Night_sky"),
                detour,
            )
        }
    }

    // ---------------------------------------------------------------- everyday chatter

    /** A feature tip for quiet moments. */
    fun idleTip(): String = pick("tip", TIPS)

    /** While waiting on the AI. */
    fun thinking(): String = pick("thinking", THINKING)

    /** When the backend is unreachable and Lumo switches to on-device smarts. */
    fun offline(): String = pick("offline", OFFLINE)

    /** When the backend answers again. */
    fun online(): String = pick("online", ONLINE)

    /**
     * When the Lumo panel opens. [pages] is the number of stops on tonight's trail (folded titles), so
     * the line says "stops" and never competes with the panel's "pages" stat (the page-load count).
     */
    fun panelIntro(pages: Int): String =
        if (pages <= 0) pick("panel.empty", PANEL_EMPTY) else fill(pick("panel.intro", PANEL_INTRO), "n" to pages.toString(), "s" to if (pages == 1) "" else "s")

    // ---------------------------------------------------------------- formatting helpers

    /** "45 seconds", "a minute", "4 minutes", "over an hour". For sentences Lumo says out loud. */
    fun spokenDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(1)
        if (s < 60) return if (s == 1) "1 second" else "$s seconds"
        val minutes = (s / 60f).roundToInt()
        return when {
            minutes <= 1 -> "a minute"
            minutes < 60 -> "$minutes minutes"
            minutes < 120 -> "over an hour"
            else -> "over ${minutes / 60} hours"
        }
    }

    /** "45 s", "4 min", "1 h 20 min". For the small measured detail line. */
    fun shortDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return "$s s"
        val minutes = (s / 60f).roundToInt()
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h h" else "$h h $m min"
    }

    /** "11 pm", "midnight", "1 am", "noon". */
    fun clockHour(hour: Int): String {
        val h = ((hour % 24) + 24) % 24
        return when (h) {
            0 -> "midnight"
            12 -> "noon"
            in 1..11 -> "$h am"
            else -> "${h - 12} pm"
        }
    }

    // ---------------------------------------------------------------- internals

    private fun questOption(): RescueOption {
        val q = QUEST_IDEAS[pickIndex("quest.idea", QUEST_IDEAS.size)]
        return RescueOption(RescueOption.Kind.QUEST, q.title, q.detail, questId = q.id)
    }

    private fun detourOption(): RescueOption {
        val d = DETOURS[pickIndex("detour", DETOURS.size)]
        return RescueOption(RescueOption.Kind.DETOUR, d.title, d.detail, url = d.url)
    }

    private fun pick(bank: String, lines: Array<String>): String = lines[pickIndex(bank, lines.size)]

    private fun pickIndex(bank: String, size: Int): Int {
        if (size <= 1) return 0
        synchronized(lock) {
            val last = lastPick[bank] ?: -1
            var i = random.nextInt(size)
            if (i == last) i = (i + 1 + random.nextInt(size - 1)) % size
            lastPick[bank] = i
            return i
        }
    }

    private fun fill(template: String, vararg values: Pair<String, String>): String {
        var out = template
        for ((k, v) in values) out = out.replace("{$k}", v)
        return out
    }

    private fun capitalize(s: String): String = if (s.isNotEmpty() && s[0].isLowerCase()) s[0].uppercaseChar() + s.substring(1) else s

    private fun cleanName(name: String): String = name.trim().take(16).ifEmpty { "friend" }

    private fun hourWords(hour: Int): String = when (((hour % 24) + 24) % 24) {
        21 -> "nine"
        22 -> "ten"
        23 -> "eleven"
        0 -> "midnight"
        1 -> "one in the morning"
        2 -> "two in the morning"
        3 -> "three in the morning"
        4 -> "four in the morning"
        else -> "bedtime"
    }

    private class QuestIdea(val id: String, val title: String, val detail: String)
    private class Detour(val title: String, val detail: String, val url: String)

    private val QUEST_IDEAS = arrayOf(
        QuestIdea("speed-safari", "Speed Safari", "Everyone hunts for animals faster than a car on the highway."),
        QuestIdea("moon-collectors", "Moon Collectors", "Race to find two planets with more than 20 moons."),
        QuestIdea("deep-dive", "Deep Dive", "Find three creatures living deeper than 1,000 meters."),
        QuestIdea("dino-time", "Time Travelers", "Track down two dinosaurs from the Cretaceous."),
        QuestIdea("tiny-titans", "Tiny Titans", "Find three tiny animals with giant strength."),
    )

    private val DETOURS = arrayOf(
        Detour("Why moths love lamps", "A small mystery scientists still argue about. I have opinions.", "https://en.wikipedia.org/wiki/Moth"),
        Detour("The deepest place on Earth", "The Mariana Trench is deeper than Everest is tall.", "https://en.wikipedia.org/wiki/Mariana_Trench"),
        Detour("The toughest animal alive", "Tardigrades have survived a trip to outer space.", "https://en.wikipedia.org/wiki/Tardigrade"),
        Detour("Creatures that glow", "Fireflies, jellyfish and mushrooms that make their own light.", "https://en.wikipedia.org/wiki/Bioluminescence"),
        Detour("Octopus superpowers", "Three hearts, blue blood, and arms that can taste.", "https://en.wikipedia.org/wiki/Octopus"),
        Detour("Saturn's rings, up close", "Billions of chunks of ice, some as big as a house.", "https://en.wikipedia.org/wiki/Rings_of_Saturn"),
    )

    // ---------------------------------------------------------------- the bank

    private val MORNING = arrayOf(
        "Morning! I'm Lumo. Daylight makes me a little shy.",
        "Good morning, couch crew. Where shall we wander first?",
        "Morning! The lamp's off, but I'm still fluttering.",
    )
    private val AFTERNOON = arrayOf(
        "Hi, I'm Lumo! Grab a phone and let's wander.",
        "Afternoon! I'm Lumo, your couch moth.",
        "Hello! I'm Lumo. Point me at anything interesting.",
    )
    private val EVENING = arrayOf(
        "Evening! The lamp's on. My favorite time of day.",
        "Hi, I'm Lumo! Lamp's lit, couch is comfy. Where to?",
        "Evening, everyone. I've been circling, waiting for you.",
    )
    private val LATE = arrayOf(
        "Hello, night owls. I'm Lumo, a night moth. We'll get along.",
        "It's late and cozy. Let's find one good thing.",
        "A late-night wander? I'm in. Moths are built for this.",
    )

    private val CURIOUS = arrayOf(
        "Ooh, what's this?",
        "I wonder where this goes.",
        "Tell me more, tell me more.",
        "My antennae are tingling.",
    )
    private val HAPPY = arrayOf(
        "This is my favorite part.",
        "Wings are fluttering!",
        "Oh, that's lovely.",
        "I could do a loop-de-loop.",
    )
    private val SLEEPY = arrayOf(
        "Still here. Just resting my wings.",
        "My antennae are getting droopy.",
        "Is it bedtime somewhere?",
        "Mmm. Cozy lamp, heavy wings.",
    )
    private val CONCERNED = arrayOf(
        "Hmm. Let's slow down a little.",
        "Are we finding what we came for?",
        "My wings feel a bit dizzy.",
        "I'm not sure this is the good stuff.",
    )

    private val JOINED = arrayOf(
        "Hi {name}! Your phone is a remote now.",
        "{name} joined the couch!",
        "Welcome, {name}. Scoot over, everyone.",
        "{name} is here! More eyes, more wonders.",
    )
    private val LEFT = arrayOf(
        "{name} stepped away. Their spot stays warm.",
        "Bye for now, {name}!",
        "{name} left the couch. Come back soon!",
    )
    private val SCAN_HINT = arrayOf(
        "Scan the code with a phone. It turns into a keyboard and a touchpad.",
        "No app needed: point a phone camera at the code and join in.",
        "Phones make the best remotes. Scan the code to join!",
    )
    private val COUCH_FULL = arrayOf(
        "The couch is full! Four phones at a time.",
        "Four players already. Pass a phone around?",
    )

    private val CARDS_MAKING = arrayOf(
        "Folding this page into cards...",
        "Reading this so nobody has to squint...",
        "One moment, I'm making cards.",
    )
    private val CARDS_READY = arrayOf(
        "Here's the page in {n} cards.",
        "{n} cards, ready to read from the couch.",
        "Done! {n} cards. Left and right to flip.",
    )
    private val CARDS_READY_OFFLINE = arrayOf(
        "{n} quick cards, made right here on the TV.",
        "I made {n} cards on my own. No internet needed.",
    )
    private val CARDS_FAILED = arrayOf(
        "I couldn't make cards from this page. Try one with more words?",
        "This page is shy. No cards this time.",
        "Not enough reading here for cards. Let's try another page.",
    )
    private val READ_ALOUD = arrayOf(
        "Ahem. Reading this one out loud.",
        "Gather round, I'll read it.",
    )

    private val QUEST_START = arrayOf(
        "{title} starts now! Find it, then send it from your phone.",
        "Quest time: {title}. Ready, set, wander!",
        "{title}! I'll be the judge. Fair, but fuzzy.",
    )
    private val QUEST_CHECKING = arrayOf(
        "Let me look closely...",
        "Squinting at this page...",
        "Checking the evidence...",
    )
    private val QUEST_CHECKING_NAME = arrayOf(
        "Checking {name}'s page...",
        "Ooh, {name} sent something. Let me look...",
        "Looking at {name}'s find...",
    )
    private val QUEST_PASS = arrayOf(
        "Yes! {item} counts. +{points} for {name}!",
        "{name} found {item}! That's {points} points.",
        "{item}? Confirmed. Nicely done, {name}!",
    )
    private val QUEST_DUPLICATE = arrayOf(
        "We already have {item}. Try another!",
        "{item} is already on the board. Keep hunting!",
    )
    private val QUEST_DUPLICATE_NAME = arrayOf(
        "{item} is already on the board. {name} found it first!",
        "We have {item} already, thanks to {name}. Try another!",
        "So close! {name} spotted {item} first.",
    )
    private val QUEST_FAIL = arrayOf(
        "Not quite this time. Keep looking!",
        "Close, but this page doesn't show it. Try another?",
        "Hmm, I can't find it here. The next page might be the one.",
    )
    private val QUEST_DONE = arrayOf(
        "Quest complete! What a team.",
        "We did it! Every last one found.",
    )
    private val QUEST_DONE_NAME = arrayOf(
        "Quest complete! {name} takes the bonus. What a team.",
        "We did it! {name} found the last one and gets the bonus.",
        "All found! Bonus points to {name}.",
    )
    private val QUEST_INVENTING = arrayOf(
        "Let me invent a quest from tonight's trail...",
        "Hmm, something about where we've been... one moment.",
    )

    private val HOPPING = arrayOf(
        "We've hopped {n} pages in {dur}. I know that dizzy feeling.",
        "{n} pages in {dur}! Even I land somewhere eventually.",
        "Hop, hop, hop: {n} pages in {dur}. Shall we land somewhere good?",
        "{n} pages in {dur}. My wings are tired just watching.",
    )
    private val SCROLLING = arrayOf(
        "{dur} of scrolling and nothing's caught us yet. Shall we find something that does?",
        "{dur} of scrolling. I once circled a porch light for six hours, so I get it.",
        "Down and down for {dur}. I'm starting to think there's no bottom.",
        "{dur} of scrolling, zero clicks. Want to go somewhere on purpose?",
    )
    private val LOOPING = arrayOf(
        "Back to the same site {n} times. I do that with porch lights.",
        "Round and round: same site, {n} visits. I know that circle well.",
        "{n} visits to the same place in {dur}. Lovely light, but there are others.",
        "We keep flying back to the same site. {n} times now! Try a new lamp?",
    )
    private val LATE_NIGHT = arrayOf(
        "It's past {hour} and the lamp's still on. Want a cozy wind-down?",
        "We've been up for {dur} tonight. One good story, then bed?",
        "Past {hour}! Even moths get sleepy. One last gentle thing?",
        "The house is quiet and it's past {hour}. Shall we wind down together?",
    )

    private val ACCEPT_QUEST = arrayOf(
        "Quest mode! Phones at the ready.",
        "Ooh, a quest. I'll get my judging antennae.",
    )
    private val ACCEPT_DETOUR = arrayOf(
        "Detour! Follow me.",
        "This way. I know a good lamp.",
        "Off we flutter!",
    )
    private val ACCEPT_WIND = arrayOf(
        "Cozy mode. Lights low, one good read.",
        "Let's slow down together. Nice and gentle.",
    )
    private val DISMISSED = arrayOf(
        "Okay! I'll hush. Keep going.",
        "Got it, you're on a mission. I'll wait right here.",
        "Fair enough. I'll check in less often.",
    )
    private val WIND_DOWN = arrayOf(
        "It's late. One calm page, then lamps off?",
        "Even moths rest. Let's end with something gentle.",
        "The night's getting long. Shall we find a sleepy story?",
    )

    private val TIPS = arrayOf(
        "Press the menu button and I'll turn this page into cards.",
        "Play/Pause turns any page into big, readable cards.",
        "Scan the code with a phone. It becomes a keyboard and a touchpad.",
        "Try a family quest: everyone hunts, I judge.",
        "Hold the D-pad and the cursor speeds up.",
        "Rewind and fast-forward scroll a whole screen at a time.",
        "Up to four phones can join. Everyone gets their own color.",
        "Your trail stays on this TV. Open my panel to see where we've been.",
    )
    private val THINKING = arrayOf(
        "Hmm, let me think...",
        "Thinking with both antennae...",
        "One moment...",
    )
    private val OFFLINE = arrayOf(
        "The internet's napping, so I'll use my on-device smarts.",
        "I'm offline for now. I can still help, just a bit simpler.",
    )
    private val ONLINE = arrayOf(
        "I'm back online. Full brain again!",
        "Connected again. My antennae are buzzing.",
    )
    // Counts trail stops, never "pages": the panel's stat tile already shows the page-load count.
    private val PANEL_INTRO = arrayOf(
        "Here's where we've wandered tonight: {n} stop{s} so far.",
        "Our little map so far: {n} stop{s}.",
    )
    private val PANEL_EMPTY = arrayOf(
        "A fresh night. Nothing on the map yet.",
        "Fresh night! Where shall we wander first?",
    )
}
