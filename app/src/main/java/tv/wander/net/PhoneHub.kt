package tv.wander.net

import dev.couchlink.AssetSource
import dev.couchlink.CouchListener
import dev.couchlink.CouchServer
import tv.wander.model.Mode
import tv.wander.model.Player
import tv.wander.model.QuestBoard
import tv.wander.model.TvState
import java.io.IOException

/**
 * Wander's phone protocol on top of couchlink: hello/welcome, up to 4 named players with colors,
 * command parsing, and state broadcast. Listener calls run on the thread [post] uses (main).
 *
 * How it behaves (SPEC section 5):
 * - `GET /` serves `phone/index.html`, `/fonts/x` serves `fonts/x`, anything else `phone/<path>`,
 *   all read through [assets] with asset-relative paths.
 * - A phone becomes a player with its `hello`. At most four players are connected at once; a fifth
 *   gets `{"t":"reject","reason":"full"}` and may try `hello` again later on the same socket.
 * - A `pid` that matches a remembered, disconnected player resumes it with the same score and color.
 *   If the same phone reconnects before its old socket timed out (same pid and same name), the new
 *   socket takes the seat over and the old one is closed.
 * - Taken colors fall back to the first free one; names are trimmed to 16 chars, empty means the
 *   color name.
 * - After `welcome` the newcomer gets `players` (sent to everyone) and then the latest `state`.
 *
 * Every public method is thread-safe and non-blocking: sends are queued by couchlink.
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

    /** A remembered player and the socket it is on (-1 when disconnected). */
    private class Seat(var player: Player, var clientId: Int, var defaultName: Boolean)

    private val lock = Any()
    private val seats = ArrayList<Seat>()
    private val bySocket = HashMap<Int, Seat>()
    private var nextPlayerNumber = 1
    private var generation = 0
    private var server: CouchServer? = null
    private var lastStateJson: String? = null
    private var lastPlayersJson: String? = null

    /** URL path (already sanitized by couchlink) -> asset-relative path -> bytes. */
    private val webAssets = AssetSource { path ->
        val assetPath = PhoneProtocol.assetPathFor(path)
        if (assetPath == null) null else assets.open(assetPath)
    }

    /** Connected players, in join order. */
    val players: List<Player>
        get() = synchronized(lock) { seats.filter { it.clientId >= 0 }.map { it.player } }

    /** Starts serving on [port]. False (and logged) when the port is busy; true if already running. */
    fun start(): Boolean {
        synchronized(lock) {
            if (server != null) return true
            val gen = ++generation
            val s = CouchServer(port, webAssets, Link(gen), ::log)
            return try {
                s.start()
                server = s
                log("phone hub ready on port $port")
                true
            } catch (e: IOException) {
                log("phone hub could not start on port $port: ${e.message}")
                false
            } catch (t: Throwable) {
                log("phone hub failed to start: $t")
                false
            }
        }
    }

    /** Closes every phone connection. Players are remembered, so phones can resume after [start]. */
    fun stop() {
        val s: CouchServer
        val left: List<Player>
        synchronized(lock) {
            s = server ?: return
            server = null
            generation++
            left = seats.filter { it.clientId >= 0 }.map { it.player }
            for (seat in seats) {
                if (seat.clientId >= 0) {
                    seat.clientId = -1
                    seat.player.connected = false
                }
            }
            bySocket.clear()
            lastPlayersJson = null
        }
        try {
            s.stop()
        } catch (t: Throwable) {
            log("phone hub stop failed: $t")
        }
        for (p in left) deliver { listener.onPlayerLeft(p) }
    }

    /**
     * Sends [state] to every player (skipped when nothing changed) and remembers it for newcomers.
     * Scores from [TvState.quest] are adopted, and `players` is re-sent whenever a score changed.
     */
    fun publish(state: TvState) {
        try {
            val json = PhoneProtocol.state(state)
            synchronized(lock) {
                state.quest?.let { adoptScores(it) }
                val srv = server
                val playersJson = PhoneProtocol.players(connectedPlayersLocked())
                if (srv != null && playersJson != lastPlayersJson) {
                    lastPlayersJson = playersJson
                    sendToPlayersLocked(srv, playersJson)
                }
                if (json == lastStateJson) return
                lastStateJson = json
                if (srv != null) sendToPlayersLocked(srv, json)
            }
        } catch (t: Throwable) {
            log("publish failed: $t")
        }
    }

    /** A toast on every phone, or only on [playerId]'s. [kind] is info, good or bad. */
    fun toast(text: String, kind: String = "info", playerId: String? = null) {
        try {
            val json = PhoneProtocol.toast(text, kind)
            synchronized(lock) {
                val srv = server ?: return
                if (playerId == null) {
                    sendToPlayersLocked(srv, json)
                } else {
                    val seat = seats.firstOrNull { it.player.id == playerId && it.clientId >= 0 } ?: return
                    srv.send(seat.clientId, json)
                }
            }
        } catch (t: Throwable) {
            log("toast failed: $t")
        }
    }

    /** Confetti and a buzz on [playerId]'s phone, in that player's color. */
    fun celebrate(playerId: String, text: String) {
        try {
            synchronized(lock) {
                val srv = server ?: return
                val seat = seats.firstOrNull { it.player.id == playerId && it.clientId >= 0 } ?: return
                srv.send(seat.clientId, PhoneProtocol.celebrate(text, seat.player.colorIndex))
            }
        } catch (t: Throwable) {
            log("celebrate failed: $t")
        }
    }

    fun player(id: String): Player? = players.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- socket events

    /** One per started server, so late callbacks from a stopped server are ignored. */
    private inner class Link(private val gen: Int) : CouchListener {
        override fun onOpen(clientId: Int, remoteAddress: String) {
            log("phone socket $clientId opened from $remoteAddress")
        }

        override fun onText(clientId: Int, text: String) {
            try {
                handleText(gen, clientId, text)
            } catch (t: Throwable) {
                log("phone message failed: $t")
            }
        }

        override fun onClose(clientId: Int) {
            try {
                handleClose(gen, clientId)
            } catch (t: Throwable) {
                log("phone close failed: $t")
            }
        }
    }

    private fun handleText(gen: Int, clientId: Int, text: String) {
        when (val incoming = PhoneProtocol.parse(text) ?: return) {
            is PhoneProtocol.Incoming.Hello -> handleHello(gen, clientId, incoming)
            is PhoneProtocol.Incoming.BadHello -> synchronized(lock) {
                if (gen == generation) server?.send(clientId, PhoneProtocol.reject("bad_hello"))
            }
            is PhoneProtocol.Incoming.Ping -> synchronized(lock) {
                if (gen == generation) server?.send(clientId, PhoneProtocol.PONG)
            }
            is PhoneProtocol.Incoming.Command -> {
                val player = synchronized(lock) {
                    if (gen != generation) null else bySocket[clientId]?.player
                } ?: return // commands before a welcome are ignored
                val command = incoming.command
                deliver { listener.onCommand(player, command) }
            }
        }
    }

    private fun handleHello(gen: Int, clientId: Int, hello: PhoneProtocol.Incoming.Hello) {
        var joined: Player? = null
        var replaced: Player? = null
        synchronized(lock) {
            val srv = server ?: return
            if (gen != generation) return
            val requestedName = PhoneProtocol.cleanName(hello.name)

            var seat = bySocket[clientId]
            if (seat == null && hello.pid != null) {
                val remembered = seats.firstOrNull { it.player.id == hello.pid }
                if (remembered != null && remembered.clientId >= 0) {
                    // Same pid on a live seat: only the same phone (same name) may take it over, e.g.
                    // after a Wi-Fi blip that the old socket has not noticed yet. A different name
                    // means a stale pid from an earlier session, so that phone joins as someone new.
                    if (requestedName.isEmpty() || requestedName.equals(remembered.player.name, ignoreCase = true)) {
                        val old = remembered.clientId
                        bySocket.remove(old)
                        srv.close(old, 4000, "Replaced by a newer connection")
                        remembered.clientId = clientId
                        bySocket[clientId] = remembered
                        seat = remembered
                        log("${remembered.player.name} moved to a new connection")
                    }
                } else if (remembered != null) {
                    if (connectedCountLocked() >= PhoneProtocol.MAX_PLAYERS) {
                        rejectFull(srv, clientId)
                        return
                    }
                    remembered.clientId = clientId
                    bySocket[clientId] = remembered
                    seat = remembered
                    val back = applyIdentity(remembered, requestedName, remembered.player.colorIndex)
                    joined = back
                    log("${back.name} is back (${back.id}, score ${back.score})")
                }
            }

            if (seat == null) {
                if (connectedCountLocked() >= PhoneProtocol.MAX_PLAYERS) {
                    rejectFull(srv, clientId)
                    return
                }
                val color = pickColor(hello.color, null)
                val name = requestedName.ifEmpty { PhoneProtocol.COLOR_NAMES[color] }
                val player = Player(id = "p${nextPlayerNumber++}", name = name, colorIndex = color, score = 0, connected = true)
                val fresh = Seat(player, clientId, defaultName = requestedName.isEmpty())
                seats.add(fresh)
                bySocket[clientId] = fresh
                seat = fresh
                joined = player
                forgetOldSeatsLocked()
                log("${player.name} joined as ${player.id} (${PhoneProtocol.COLOR_NAMES[color]})")
            } else if (joined == null) {
                // A repeated hello on a live seat (or a takeover): apply a new name or color if asked.
                val before = seat.player
                val after = applyIdentity(seat, requestedName, hello.color ?: before.colorIndex)
                if (after != before) {
                    replaced = before
                    joined = after
                }
            }

            val player = seat.player
            srv.send(clientId, PhoneProtocol.welcome(player))
            val playersJson = PhoneProtocol.players(connectedPlayersLocked())
            lastPlayersJson = playersJson
            sendToPlayersLocked(srv, playersJson)
            srv.send(clientId, lastStateJson ?: PhoneProtocol.state(TvState(Mode.HOME)))
        }
        replaced?.let { old -> deliver { listener.onPlayerLeft(old) } }
        joined?.let { p -> deliver { listener.onPlayerJoined(p) } }
    }

    /**
     * Gives [seat] its (possibly new) name and color, keeping id and score. An empty [requestedName]
     * keeps the current name, unless that name was only the default color name, which then follows
     * the color. Returns the player now in the seat; a new object only when something visible changed.
     */
    private fun applyIdentity(seat: Seat, requestedName: String, wantedColor: Int): Player {
        val current = seat.player
        val color = pickColor(wantedColor, seat)
        val name = when {
            requestedName.isNotEmpty() -> requestedName
            seat.defaultName -> PhoneProtocol.COLOR_NAMES[color]
            else -> current.name
        }
        if (requestedName.isNotEmpty()) seat.defaultName = false
        val updated = if (name == current.name && color == current.colorIndex) {
            current
        } else {
            current.copy(name = name, colorIndex = color)
        }
        updated.connected = true
        seat.player = updated
        return updated
    }

    private fun handleClose(gen: Int, clientId: Int) {
        val left: Player
        synchronized(lock) {
            if (gen != generation) return
            val seat = bySocket.remove(clientId) ?: return
            seat.clientId = -1
            seat.player.connected = false
            left = seat.player
            server?.let { srv ->
                val playersJson = PhoneProtocol.players(connectedPlayersLocked())
                lastPlayersJson = playersJson
                sendToPlayersLocked(srv, playersJson)
            }
            log("${left.name} left (${left.id})")
        }
        deliver { listener.onPlayerLeft(left) }
    }

    // ---------------------------------------------------------------- helpers (call with lock held)

    private fun rejectFull(srv: CouchServer, clientId: Int) {
        srv.send(clientId, PhoneProtocol.reject("full"))
        log("couch is full, phone socket $clientId was turned away")
    }

    private fun connectedCountLocked(): Int = seats.count { it.clientId >= 0 }

    private fun connectedPlayersLocked(): List<Player> = seats.filter { it.clientId >= 0 }.map { it.player }

    /** The requested color when no other connected player has it, else the first free one. */
    private fun pickColor(requested: Int?, self: Seat?): Int {
        val taken = BooleanArray(PhoneProtocol.MAX_PLAYERS)
        for (s in seats) {
            if (s !== self && s.clientId >= 0) {
                val c = s.player.colorIndex
                if (c in taken.indices) taken[c] = true
            }
        }
        if (requested != null && requested in taken.indices && !taken[requested]) return requested
        val free = taken.indexOfFirst { !it }
        return if (free >= 0) free else (requested ?: 0).coerceIn(0, PhoneProtocol.MAX_PLAYERS - 1)
    }

    private fun adoptScores(board: QuestBoard) {
        for (bp in board.players) {
            val seat = seats.firstOrNull { it.player.id == bp.id } ?: continue
            if (seat.player !== bp && seat.player.score != bp.score) seat.player.score = bp.score
        }
    }

    /** Keeps the memory of departed players bounded (oldest disconnected seats go first). */
    private fun forgetOldSeatsLocked() {
        while (seats.size > MAX_REMEMBERED) {
            val idx = seats.indexOfFirst { it.clientId < 0 }
            if (idx < 0) return
            seats.removeAt(idx)
        }
    }

    private fun sendToPlayersLocked(srv: CouchServer, json: String) {
        for (id in bySocket.keys) srv.send(id, json)
    }

    private fun deliver(block: () -> Unit) {
        try {
            post {
                try {
                    block()
                } catch (t: Throwable) {
                    log("phone listener threw: $t")
                }
            }
        } catch (t: Throwable) {
            log("could not post a phone event: $t")
        }
    }

    private fun log(message: String) {
        if (androidLog) {
            try {
                android.util.Log.i(TAG, message)
                return
            } catch (t: Throwable) {
                // Fall through to stdout.
            }
        }
        println("$TAG: $message")
    }

    private companion object {
        const val TAG = "WanderPhone"
        const val MAX_REMEMBERED = 16

        /** False under plain JVM unit tests, where android.util.Log is a stub that throws. */
        val androidLog: Boolean = try {
            android.util.Log.isLoggable(TAG, android.util.Log.INFO)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
