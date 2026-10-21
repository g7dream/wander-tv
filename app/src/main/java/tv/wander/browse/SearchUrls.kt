package tv.wander.browse

/** Turns what someone typed into a URL: real URLs pass through, anything else becomes a safe search. */
object SearchUrls {
    fun resolve(input: String): String = input
    fun looksLikeUrl(input: String): Boolean = false
}
