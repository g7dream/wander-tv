package tv.wander.ai

import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.RescueOption
import tv.wander.model.SessionStats

/**
 * Lumo's offline reactions to aimless browsing: a line in Lumo's voice plus one better thing to do, either a
 * curated quest (SPEC section 8) or a short wonder on Simple English Wikipedia. Variants rotate with the session
 * so a second nudge never sounds like a recording.
 */
internal object LumoAdvice {
    /** Curated detours: the same list the backend allows, so online and offline suggestions agree. */
    val DETOURS: Map<String, RescueOption> = linkedMapOf(
        "bioluminescence" to RescueOption(RescueOption.Kind.DETOUR, "See creatures that glow",
            "Animals that make their own light, like tiny lamps", url = "https://simple.wikipedia.org/wiki/Bioluminescence"),
        "moth" to RescueOption(RescueOption.Kind.DETOUR, "Meet Lumo's cousins",
            "How moths find their way through the night", url = "https://simple.wikipedia.org/wiki/Moth"),
        "octopus" to RescueOption(RescueOption.Kind.DETOUR, "Visit the octopus",
            "Eight arms, three hearts and blue blood", url = "https://simple.wikipedia.org/wiki/Octopus"),
        "aurora" to RescueOption(RescueOption.Kind.DETOUR, "Watch the night sky glow",
            "A calm one: curtains of light over the poles", url = "https://simple.wikipedia.org/wiki/Aurora"),
        "tardigrade" to RescueOption(RescueOption.Kind.DETOUR, "Meet the water bear",
            "A tiny animal tough enough to survive space", url = "https://simple.wikipedia.org/wiki/Tardigrade"),
    )

    val QUESTS: Map<String, RescueOption> = linkedMapOf(
        "speed-safari" to RescueOption(RescueOption.Kind.QUEST, "Start Speed Safari",
            "Find three animals faster than a car on the highway", questId = "speed-safari"),
        "moon-collectors" to RescueOption(RescueOption.Kind.QUEST, "Start Moon Collectors",
            "Find two planets with more than 20 moons", questId = "moon-collectors"),
        "deep-dive" to RescueOption(RescueOption.Kind.QUEST, "Start Deep Dive",
            "Find three creatures living deeper than 1,000 meters", questId = "deep-dive"),
        "dino-time" to RescueOption(RescueOption.Kind.QUEST, "Start Time Travelers",
            "Find two dinosaurs from the Cretaceous period", questId = "dino-time"),
        "tiny-titans" to RescueOption(RescueOption.Kind.QUEST, "Start Tiny Titans",
            "Find three animals that lift many times their weight", questId = "tiny-titans"),
    )

    private class Variant(val line: (SessionStats) -> String, val suggestion: RescueOption)

    private val VARIANTS: Map<DriftReason, Pair<Mood, List<Variant>>> = mapOf(
        DriftReason.RAPID_HOPPING to (Mood.CONCERNED to listOf(
            Variant({ "So many pages, so fast! My wings are dizzy. Want a quest with a finish line?" }, QUESTS.getValue("speed-safari")),
            Variant({ s -> "${pages(s.pagesLast5Min)} in five minutes! Let's slow down and dive deep instead." }, QUESTS.getValue("deep-dive")),
            Variant({ "We're zooming like moths around a porch light. Let's land on one wonder?" }, DETOURS.getValue("octopus")),
        )),
        DriftReason.ENDLESS_SCROLL to (Mood.SLEEPY to listOf(
            Variant({ "All this scrolling is making my antennae droop. Fancy something that glows?" }, DETOURS.getValue("bioluminescence")),
            Variant({ "Scroll, scroll, scroll... I nearly dozed off! How about some tiny titans?" }, QUESTS.getValue("tiny-titans")),
            Variant({ "My eyes are going swirly. Let's meet the toughest tiny animal ever!" }, DETOURS.getValue("tardigrade")),
        )),
        DriftReason.SITE_LOOP to (Mood.CURIOUS to listOf(
            Variant({ "We keep circling the same light. Trust me, I'm a moth: let's try a new one." }, DETOURS.getValue("moth")),
            Variant({ s -> "Back here ${times(s.sameSiteRepeats)}? Let's go collect some moons instead!" }, QUESTS.getValue("moon-collectors")),
            Variant({ "Round and round we go! Want to time travel to the dinosaurs?" }, QUESTS.getValue("dino-time")),
        )),
        DriftReason.LATE_NIGHT to (Mood.SLEEPY to listOf(
            Variant({ "It's getting late, and even moths need rest. One calm wonder before bed?" }, DETOURS.getValue("aurora")),
            Variant({ "Way past moth o'clock! Let's end with something gentle and glowy." }, DETOURS.getValue("moth")),
        )),
    )

    private fun pages(n: Int): String = if (n > 1) "$n pages" else "So many pages"

    private fun times(n: Int): String = if (n > 2) "$n times" else "again"

    fun advise(stats: SessionStats, signal: DriftSignal): CompanionAdvice {
        val (mood, variants) = VARIANTS[signal.reason] ?: VARIANTS.getValue(DriftReason.RAPID_HOPPING)
        val index = Math.floorMod(stats.pagesTotal + stats.sessionMinutes + stats.localHour, variants.size)
        val v = variants[index]
        val line = TextKit.clamp(v.line(stats), 120)
        return CompanionAdvice(mood, line, v.suggestion)
    }
}
