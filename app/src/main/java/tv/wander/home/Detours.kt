package tv.wander.home

import tv.wander.model.Quest
import tv.wander.model.RescueOption

/**
 * Lumo's curated "curious detours": one short, wonder-sized page each, offered when the family is
 * circling the screen. All free, kid-friendly and readable on a TV.
 */
object Detours {
    val all: List<RescueOption> = listOf(
        RescueOption(RescueOption.Kind.DETOUR, "Meet the water bear 🐻", "A tiny animal that survives outer space.", "https://simple.wikipedia.org/wiki/Tardigrade"),
        RescueOption(RescueOption.Kind.DETOUR, "Three hearts, blue blood 🐙", "Octopuses are stranger than aliens.", "https://simple.wikipedia.org/wiki/Octopus"),
        RescueOption(RescueOption.Kind.DETOUR, "The smiling salamander 🦎", "Axolotls can regrow a whole leg.", "https://simple.wikipedia.org/wiki/Axolotl"),
        RescueOption(RescueOption.Kind.DETOUR, "Saturn up close 🪐", "Rings made of ice, and hundreds of moons.", "https://spaceplace.nasa.gov/all-about-saturn/en/"),
        RescueOption(RescueOption.Kind.DETOUR, "Today's picture of the day 🖼️", "One remarkable photo. Guess where it was taken.", "https://commons.wikimedia.org/wiki/Commons:Picture_of_the_day"),
        RescueOption(RescueOption.Kind.DETOUR, "Why rainbows curve 🌈", "Sunlight, raindrops and a little geometry.", "https://simple.wikipedia.org/wiki/Rainbow"),
    )

    private var last = -1

    /** A detour that differs from the last one offered and doesn't point at [currentUrl]. */
    fun next(currentUrl: String?, random: kotlin.random.Random = kotlin.random.Random.Default): RescueOption {
        val candidates = all.indices.filter { it != last && all[it].url != currentUrl }
        val pick = if (candidates.isEmpty()) 0 else candidates[random.nextInt(candidates.size)]
        last = pick
        return all[pick]
    }

    /** A calm way out for late nights. */
    val goodnight: RescueOption = RescueOption(
        RescueOption.Kind.WIND_DOWN,
        "Call it a night 🌙",
        "Lumo dims the lamp and heads home.",
    )

    /** Where a quest begins when it's started from Home: a search the family can dig through together. */
    fun startQuery(quest: Quest): String = when (quest.id) {
        "speed-safari" -> "fastest animals in the world"
        "moon-collectors" -> "which planets have the most moons"
        "deep-dive" -> "deep sea creatures"
        "dino-time" -> "Cretaceous period dinosaurs"
        "tiny-titans" -> "strongest animals for their size"
        else -> quest.title
    }
}
