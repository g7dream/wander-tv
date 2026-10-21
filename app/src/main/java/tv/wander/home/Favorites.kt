package tv.wander.home

/** A curated site that reads well on a TV. [colorIndex] picks the tile tint from Palette.PLAYERS. */
data class Favorite(val title: String, val url: String, val emoji: String, val blurb: String, val colorIndex: Int)

object Favorites {
    val all: List<Favorite> = emptyList()
}
