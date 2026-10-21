package tv.wander.home

/** A curated site that reads well on a TV. [colorIndex] picks the tile tint from Palette.PLAYERS. */
data class Favorite(val title: String, val url: String, val emoji: String, val blurb: String, val colorIndex: Int)

object Favorites {
    /**
     * Six calm, kid-friendly places to start: free, text-first pages that render well in a WebView at
     * couch distance, and that line up with the family quests (animals, space, the deep sea, dinosaurs).
     * Titles name what's inside; the blurb credits where it comes from.
     */
    val all: List<Favorite> = listOf(
        Favorite(
            title = "Animals",
            url = "https://simple.wikipedia.org/wiki/Animal",
            emoji = "🐾",
            blurb = "Simple English Wikipedia: every creature, in plain words",
            colorIndex = 2,
        ),
        Favorite(
            title = "Space",
            url = "https://spaceplace.nasa.gov/menu/solar-system/",
            emoji = "🪐",
            blurb = "NASA Space Place: planets, moons and how rockets work",
            colorIndex = 3,
        ),
        Favorite(
            title = "Photo of the day",
            url = "https://commons.wikimedia.org/wiki/Commons:Picture_of_the_day",
            emoji = "🖼️",
            blurb = "Wikimedia Commons: one remarkable free photo, every day",
            colorIndex = 0,
        ),
        Favorite(
            title = "Deep sea",
            url = "https://oceanexplorer.noaa.gov/",
            emoji = "🐙",
            blurb = "NOAA Ocean Exploration: real dives into the deep sea",
            colorIndex = 3,
        ),
        Favorite(
            title = "Classic stories",
            url = "https://www.gutenberg.org/ebooks/bookshelf/20",
            emoji = "📖",
            blurb = "Project Gutenberg: free classic children's books to read together",
            colorIndex = 1,
        ),
        Favorite(
            title = "Dinosaurs",
            url = "https://www.nhm.ac.uk/discover/dino-directory.html",
            emoji = "🦕",
            blurb = "Natural History Museum Dino Directory: hundreds of dinosaurs, A to Z",
            colorIndex = 2,
        ),
    )
}
