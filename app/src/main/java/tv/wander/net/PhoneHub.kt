package tv.wander.net

import dev.couchlink.AssetSource
import tv.wander.model.Player
import tv.wander.model.TvState

/**
 * Wander's phone protocol on top of couchlink: hello/welcome, up to 4 named players with colors,
 * command parsing, and state broadcast. Listener calls run on the thread [post] uses (main).
 */
class PhoneHub(
    private val port: Int,
    private val assets: AssetSource,
    private val listener: Listener,
    private val post: (() -> Unit) -> Unit,
) {
    interface Listener {
        fun onPlayerJoined(player: Player)
        fun onPlayerLeft(player: Player)
        fun onCommand(player: Player, command: PhoneCommand)
    }

    /** Connected players, in join order. */
    val players: List<Player> get() = emptyList()

    fun start(): Boolean = false

    fun stop() {}

    fun publish(state: TvState) {}

    fun toast(text: String, kind: String = "info", playerId: String? = null) {}

    fun celebrate(playerId: String, text: String) {}

    fun player(id: String): Player? = players.firstOrNull { it.id == id }
}
