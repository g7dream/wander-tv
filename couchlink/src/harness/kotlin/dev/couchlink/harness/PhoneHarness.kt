package dev.couchlink.harness

import dev.couchlink.AssetSource
import dev.couchlink.CouchListener
import dev.couchlink.CouchServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Desktop stand-in for the Fire TV: serves the phone page from app/src/main/assets exactly like
 * PhoneHub does (`/` -> phone/index.html, `/fonts/x` -> fonts/x, `/x` -> phone/x) and plays the TV
 * side of the SPEC section 5 protocol, so the phone page can be built and tested in a browser.
 *
 *   tools/gw.sh :couchlink:runPhoneHarness            (port 8080)
 *   tools/gw.sh :couchlink:runPhoneHarness -Pport=8090
 *
 * Extras for development: `/harness` is a protocol console (join, press every button, watch the
 * traffic), `GET /harness/tv/<mode>` switches the simulated TV to any mode from outside, and
 * `GET /harness/couch` has the TV remote ("The couch") find the next quest item.
 */
fun main(args: Array<String>) {
    // Log in UTF-8 so quest emoji and curly quotes survive Windows consoles and redirected output.
    try {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    } catch (e: Exception) {
        // Keep the platform stream.
    }
    val assetsDir = File(args.getOrNull(0) ?: "app/src/main/assets").absoluteFile
    val port = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    if (!assetsDir.isDirectory) {
        System.err.println("Assets folder not found: $assetsDir")
        exitProcess(2)
    }
    val tv = SimulatedTv(assetsDir, port)
    try {
        tv.start()
    } catch (e: IOException) {
        System.err.println("Port $port is busy (${e.message}). Try -Pport=8090.")
        exitProcess(1)
    }
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { tv.stop(); done.countDown() })
    done.await()
}

private const val MAX_PLAYERS = 4
private const val COUCH_PID = "couch"
private val COLOR_NAMES = arrayOf("Sun", "Berry", "Mint", "Sky")
private val KEYS = setOf("back", "home", "menu", "up", "down", "left", "right", "enter", "playpause")
private val MODES = setOf("home", "browse", "cards", "quest_picker", "quest_intro", "rescue", "companion")

private class SampleItem(val item: String, val evidence: String)

private class SimQuest(
    val id: String,
    val emoji: String,
    val title: String,
    val prompt: String,
    val goal: Int,
    val notQuite: String,
    val answers: List<SampleItem>,
)

/** The five curated quests from SPEC section 8, each with answers the simulated judge accepts. */
private val QUESTS = listOf(
    SimQuest(
        "speed-safari", "🐆", "Speed Safari",
        "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).", 3,
        "Close! I couldn't find a speed over 100 km/h on this page.",
        listOf(
            SampleItem("Cheetah", "The cheetah can reach 93 to 104 km/h in short bursts."),
            SampleItem("Peregrine falcon", "In its hunting dive the peregrine falcon tops 320 km/h."),
            SampleItem("Sailfish", "Sailfish have been clocked at around 110 km/h."),
            SampleItem("Golden eagle", "Golden eagles dive at up to 240 km/h."),
        ),
    ),
    SimQuest(
        "moon-collectors", "🪐", "Moon Collectors",
        "Find two planets that have more than 20 moons.", 2,
        "Hmm, I don't see a planet with more than 20 moons here.",
        listOf(
            SampleItem("Saturn", "Saturn has 146 moons in its orbit."),
            SampleItem("Jupiter", "Jupiter has 95 moons officially recognized."),
            SampleItem("Uranus", "Uranus has 28 known moons."),
        ),
    ),
    SimQuest(
        "deep-dive", "🐙", "Deep Dive",
        "Find three creatures that live deeper than 1,000 meters in the ocean.", 3,
        "Not deep enough yet. I need something living below 1,000 meters.",
        listOf(
            SampleItem("Anglerfish", "Deep-sea anglerfish live as deep as 2,000 meters."),
            SampleItem("Dumbo octopus", "Dumbo octopuses live 3,000 to 4,000 meters down."),
            SampleItem("Mariana snailfish", "The Mariana snailfish was filmed at about 8,000 meters."),
            SampleItem("Gulper eel", "Gulper eels are found as deep as 3,000 meters."),
        ),
    ),
    SimQuest(
        "dino-time", "🦕", "Time Travelers",
        "Find two dinosaurs that lived in the Cretaceous period.", 2,
        "I couldn't spot a Cretaceous dinosaur on this page.",
        listOf(
            SampleItem("Tyrannosaurus", "Tyrannosaurus lived 68 to 66 million years ago, in the Late Cretaceous."),
            SampleItem("Triceratops", "Triceratops lived at the very end of the Cretaceous period."),
            SampleItem("Velociraptor", "Velociraptor lived 75 to 71 million years ago, in the Cretaceous."),
        ),
    ),
    SimQuest(
        "tiny-titans", "🐜", "Tiny Titans",
        "Find three animals that can lift or pull many times their own body weight.", 3,
        "I didn't find a creature hauling many times its own weight here.",
        listOf(
            SampleItem("Dung beetle", "A dung beetle can pull 1,141 times its own body weight."),
            SampleItem("Leafcutter ant", "Leafcutter ants carry leaves 50 times their body weight."),
            SampleItem("Rhinoceros beetle", "Rhinoceros beetles can lift 850 times their own weight."),
        ),
    ),
)

/** "Lumo's pick": what the AI would generate. Lumo is a moth, so of course it picks night creatures. */
private val LUMO_PICK = SimQuest(
    "lumo-night-shift", "🦉", "Night Shift",
    "Lumo's pick: find two animals that are wide awake while you're asleep.", 2,
    "That one sleeps at night like us. I need a night owl.",
    listOf(
        SampleItem("Barn owl", "Barn owls hunt at night, finding mice by sound alone."),
        SampleItem("Fennec fox", "Fennec foxes are nocturnal and rest through the hot desert day."),
        SampleItem("Aye-aye", "The aye-aye is a nocturnal lemur that taps trees to find grubs."),
    ),
)

private class SampleCard(val title: String, val line: String)

private val CARDS = listOf(
    SampleCard("Built for speed", "A cheetah goes from 0 to 100 km/h in about three seconds."),
    SampleCard("Spots that hide", "Its spots break up its outline in tall, dry grass."),
    SampleCard("A sprinter, not a marathoner", "It can only keep top speed for about 30 seconds."),
    SampleCard("Chirps, not roars", "Cheetahs can't roar. They chirp like birds and purr."),
)

private class Seat(
    val pid: String,
    var name: String,
    var color: Int,
    var score: Int = 0,
    var clientId: Int = -1,
    var defaultName: Boolean = false,
)

private class Found(val item: String, val pid: String, val evidence: String)

/** Every field lives on the single "tv" thread; socket callbacks hop onto it. */
private class SimulatedTv(private val assetsDir: File, private val port: Int) : CouchListener {
    private val tv: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "simulated-tv").apply { isDaemon = true }
    }
    private val server = CouchServer(port, DirAssets(), this, log = { println("[couch] $it") })

    private val seats = ArrayList<Seat>()
    private val bySocket = HashMap<Int, Seat>()
    private var nextPlayer = 1

    private var mode = "home"
    private val history = ArrayDeque<String>()
    private var title = ""
    private var url = ""
    private var checking = false
    private var fieldFocused = false
    private var cardIndex = 0
    private var quest: SimQuest? = null
    private val found = ArrayList<Found>()
    private var complete = false
    private var acceptNext = true
    private var couchScore = 0 // the TV remote plays as "The couch" (pid "couch"), like MainActivity

    fun start() {
        server.start()
        val lan = lanIp()
        println()
        println("  Wander TV phone harness: a simulated TV is listening.")
        println("  Phone page       http://localhost:$port/")
        if (lan != null) println("  On a real phone  http://$lan:$port/   (same Wi-Fi)")
        println("  Protocol console http://localhost:$port/harness")
        println("  Serving          $assetsDir")
        if (!File(assetsDir, "phone/index.html").isFile) {
            println("  Note: phone/index.html is not there yet, so / shows the protocol console.")
        }
        println("  Ctrl+C stops it.")
        println()
    }

    fun stop() {
        server.stop()
        tv.shutdownNow()
    }

    // ---------------------------------------------------------------- socket callbacks

    override fun onOpen(clientId: Int, remoteAddress: String) {
        println("[tv] socket $clientId opened from $remoteAddress")
    }

    override fun onText(clientId: Int, text: String) {
        tv.execute { guarded { handle(clientId, text) } }
    }

    override fun onClose(clientId: Int) {
        tv.execute {
            guarded {
                val seat = bySocket.remove(clientId) ?: return@guarded
                seat.clientId = -1
                println("[tv] ${seat.name} (${seat.pid}) left")
                broadcastPlayers()
            }
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            println("[tv] error: $t")
            t.printStackTrace()
        }
    }

    // ---------------------------------------------------------------- incoming

    private fun handle(clientId: Int, text: String) {
        val seat = bySocket[clientId]
        println("<- [${seat?.let { "${it.name} ${it.pid}" } ?: "socket $clientId"}] $text")
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            println("[tv] ignored: not a JSON object")
            return
        }
        val type = o.opt("t") as? String ?: return
        if (type == "hello") return hello(clientId, o)
        if (type == "ping") return send(clientId, JSONObject().put("t", "pong"))
        if (seat == null) {
            println("[tv] ignored: socket $clientId has not said hello")
            return
        }
        when (type) {
            "move", "scroll", "tap" -> Unit // the cursor lives on the real TV; logging is enough here
            "go" -> {
                val q = (o.opt("text") as? String)?.trim().orEmpty().take(500)
                if (q.isEmpty()) return
                setMode("browse")
                title = "Results for $q"
                url = "https://en.wikipedia.org/w/index.php?search=" + URLEncoder.encode(q, "UTF-8")
                fieldFocused = q.contains("form", ignoreCase = true)
                publish()
            }
            "type" -> {
                val typed = (o.opt("text") as? String).orEmpty().take(500)
                val submit = o.opt("submit") == true
                toast("Typed into page", "info", seat)
                if (submit && fieldFocused) {
                    fieldFocused = false
                    if (typed.isNotBlank()) title = "Results for ${typed.trim()}"
                    publish()
                }
            }
            "key" -> key(seat, (o.opt("key") as? String)?.lowercase().orEmpty())
            "cards" -> openCards()
            "read" -> {
                if (mode == "cards") toast("Lumo is reading “${CARDS[cardIndex].title}”", "info", seat)
                else toast("Make it cards first, then I can read them out.", "info", seat)
            }
            "quest_start" -> startQuest(seat, (o.opt("id") as? String).orEmpty())
            "quest_submit" -> submit(seat)
            "rescue" -> rescue(seat, (o.opt("choice") as? Number)?.toInt() ?: return)
            "zoom" -> {
                val d = (o.opt("delta") as? Number)?.toDouble() ?: return
                toast(if (d > 0) "Text a little bigger" else "Text a little smaller", "info", seat)
            }
            else -> println("[tv] ignored: unknown type \"$type\"")
        }
    }

    private fun hello(clientId: Int, o: JSONObject) {
        val rawName = o.opt("name")
        val rawColor = o.opt("color")
        val rawPid = o.opt("pid")
        val typesOk = (rawName == null || rawName == JSONObject.NULL || rawName is String || rawName is Number) &&
            (rawColor == null || rawColor == JSONObject.NULL || rawColor is Number) &&
            (rawPid == null || rawPid == JSONObject.NULL || rawPid is String)
        if (!typesOk) return send(clientId, JSONObject().put("t", "reject").put("reason", "bad_hello"))
        val name = cleanName(if (rawName == null || rawName == JSONObject.NULL) "" else rawName.toString())
        val wanted = (rawColor as? Number)?.toInt()?.takeIf { it in 0 until MAX_PLAYERS }
        val pid = rawPid as? String

        var seat = bySocket[clientId]
        if (seat == null && pid != null) {
            // Like PhoneHub: a pid only claims a seat together with the same name (pids restart every session).
            val known = seats.firstOrNull { it.pid == pid }?.takeIf { name.isEmpty() || name.equals(it.name, ignoreCase = true) }
            if (known != null && known.clientId >= 0) {
                // Same phone on a new socket: take the seat over and retire the stale socket.
                bySocket.remove(known.clientId)
                server.close(known.clientId, 4000, "Replaced by a newer connection")
                known.clientId = clientId
                bySocket[clientId] = known
                seat = known
                println("[tv] ${known.name} moved to socket $clientId")
            } else if (known != null && known.clientId < 0) {
                if (connected() >= MAX_PLAYERS) return rejectFull(clientId)
                known.clientId = clientId
                known.color = pickColor(known.color, known)
                if (name.isNotEmpty()) {
                    known.name = name
                    known.defaultName = false
                } else if (known.defaultName) {
                    known.name = COLOR_NAMES[known.color]
                }
                bySocket[clientId] = known
                seat = known
                println("[tv] ${known.name} is back as ${known.pid} with ${known.score} points")
            }
        } else if (seat != null) {
            // A repeated hello on a live socket: apply a new name or color.
            seat.color = pickColor(wanted ?: seat.color, seat)
            if (name.isNotEmpty()) {
                seat.name = name
                seat.defaultName = false
            } else if (seat.defaultName) {
                seat.name = COLOR_NAMES[seat.color]
            }
        }
        if (seat == null) {
            if (connected() >= MAX_PLAYERS) return rejectFull(clientId)
            val color = pickColor(wanted, null)
            val fresh = Seat("p${nextPlayer++}", name.ifEmpty { COLOR_NAMES[color] }, color, clientId = clientId, defaultName = name.isEmpty())
            seats.add(fresh)
            bySocket[clientId] = fresh
            seat = fresh
            println("[tv] ${fresh.name} joined as ${fresh.pid} (${COLOR_NAMES[color]})")
        }
        send(clientId, JSONObject().put("t", "welcome").put("pid", seat.pid).put("name", seat.name).put("color", seat.color))
        broadcastPlayers()
        send(clientId, stateJson())
    }

    private fun rejectFull(clientId: Int) {
        send(clientId, JSONObject().put("t", "reject").put("reason", "full"))
        println("[tv] the couch is full; socket $clientId may say hello again later")
    }

    private fun key(seat: Seat, key: String) {
        if (key !in KEYS) return println("[tv] ignored: unknown key \"$key\"")
        when (key) {
            "back" -> back()
            "home" -> {
                setMode("home")
                title = ""
                url = ""
                fieldFocused = false
                publish()
            }
            "menu" -> {
                setMode("rescue")
                publish()
            }
            "left", "right" -> if (mode == "cards") {
                val next = (cardIndex + if (key == "right") 1 else -1).coerceIn(0, CARDS.lastIndex)
                if (next != cardIndex) {
                    cardIndex = next
                    publish()
                }
            }
            "playpause" -> when (mode) {
                "browse" -> openCards()
                "cards" -> toast("Lumo is reading “${CARDS[cardIndex].title}”", "info", seat)
            }
            "enter" -> if (mode == "cards") toast("Lumo is reading “${CARDS[cardIndex].title}”", "info", seat)
            else -> Unit // up/down move focus or the cursor on the real TV
        }
    }

    private fun back() {
        mode = history.removeLastOrNull() ?: "home"
        if (mode == "home") {
            title = ""
            url = ""
        }
        fieldFocused = false
        publish()
    }

    private fun openCards() {
        setMode("cards")
        cardIndex = 0
        if (title.isEmpty()) {
            title = "Cheetah - Wikipedia"
            url = "https://en.wikipedia.org/wiki/Cheetah"
        }
        publish()
    }

    private fun startQuest(seat: Seat, id: String) {
        val q = if (id == "lumo") LUMO_PICK else QUESTS.firstOrNull { it.id == id }
        if (q == null) return toast("I don't know that quest yet.", "bad", seat)
        quest = q
        found.clear()
        complete = false
        checking = false
        acceptNext = true
        couchScore = 0
        setMode("quest_intro")
        publish()
        toAll(JSONObject().put("t", "toast").put("text", "${seat.name} started ${q.title} ${q.emoji}").put("kind", "info"))
        tv.schedule({
            guarded {
                if (mode == "quest_intro" && quest === q) {
                    setMode("browse")
                    publish()
                }
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun submit(seat: Seat) {
        val q = quest ?: return toast("Start a quest first, then send me a page.", "bad", seat)
        if (complete) return toast("${q.title} is done! Pick another quest.", "info", seat)
        if (checking) return toast("Hang on, I'm still reading the last page.", "info", seat)
        checking = true
        publish()
        toAll(JSONObject().put("t", "toast").put("text", "Lumo is checking your page...").put("kind", "info"))
        val accept = acceptNext
        acceptNext = !acceptNext
        tv.schedule({ guarded { verdict(seat, q, accept) } }, 1200, TimeUnit.MILLISECONDS)
    }

    private fun verdict(seat: Seat, q: SimQuest, accept: Boolean) {
        if (quest !== q) return
        checking = false
        val next = q.answers.firstOrNull { a -> found.none { it.item == a.item } }
        if (!accept || next == null) {
            toast(q.notQuite, "bad", seat)
            publish()
            return
        }
        found.add(Found(next.item, seat.pid, next.evidence))
        seat.score += 100
        var points = 100
        if (found.size >= q.goal) {
            complete = true
            seat.score += 50
            points += 50
        }
        broadcastPlayers()
        publish()
        if (seat.clientId >= 0) {
            val text = if (complete) "+$points ${next.item}! Quest complete!" else "+100 ${next.item}!"
            send(seat.clientId, JSONObject().put("t", "celebrate").put("text", text).put("color", seat.color))
        }
        val line = if (complete) "${seat.name} finished ${q.title}! +50 bonus" else "${seat.name} found ${next.item}!"
        toAll(JSONObject().put("t", "toast").put("text", line).put("kind", "good"))
    }

    private fun rescue(seat: Seat, choice: Int) {
        if (mode != "rescue") return println("[tv] ignored: no rescue on screen")
        when (choice) {
            0 -> {
                setMode("browse")
                title = "How moths find their way by moonlight"
                url = "https://en.wikipedia.org/wiki/Moth"
                publish()
                toast("Good pick. Detour time!", "good", seat)
            }
            1 -> {
                setMode("home")
                title = ""
                url = ""
                publish()
                toAll(JSONObject().put("t", "toast").put("text", "Lights low. The lamp stays on for next time.").put("kind", "info"))
            }
            -1 -> {
                back()
                toast("Okay! I'll keep quiet for a while.", "info", seat)
            }
            else -> println("[tv] ignored: rescue choice $choice")
        }
    }

    /** Called from the /harness/tv/<mode> endpoint. */
    fun forceMode(target: String) {
        tv.execute {
            guarded {
                if (target == "cards") return@guarded openCards()
                setMode(target)
                if (target == "browse" && title.isEmpty()) {
                    title = "Cheetah - Wikipedia"
                    url = "https://en.wikipedia.org/wiki/Cheetah"
                }
                if (target == "home") {
                    title = ""
                    url = ""
                }
                publish()
            }
        }
    }

    /** Called from GET /harness/couch: the TV remote ("The couch") submits a page and it is accepted. */
    fun couchFind() {
        tv.execute {
            guarded {
                val q = quest ?: return@guarded println("[tv] ignored: no quest running")
                if (complete) return@guarded println("[tv] ignored: ${q.title} is already complete")
                val next = q.answers.firstOrNull { a -> found.none { it.item == a.item } } ?: return@guarded
                found.add(Found(next.item, COUCH_PID, next.evidence))
                couchScore += 100
                if (found.size >= q.goal) {
                    complete = true
                    couchScore += 50
                }
                publish()
                toAll(JSONObject().put("t", "toast").put("text", "The couch found ${next.item}!").put("kind", "good"))
            }
        }
    }

    // ---------------------------------------------------------------- outgoing

    private fun setMode(next: String) {
        if (next == mode) return
        history.addLast(mode)
        while (history.size > 20) history.removeFirst()
        mode = next
    }

    private fun stateJson(): JSONObject {
        val q = quest
        return JSONObject()
            .put("t", "state")
            .put("mode", mode)
            .put("title", title)
            .put("url", url)
            .put("checking", checking)
            .put("ai", true)
            .put("field", JSONObject().put("focused", fieldFocused).put("label", if (fieldFocused) "Search" else ""))
            .put(
                "cards",
                if (mode == "cards") JSONObject().put("index", cardIndex).put("count", CARDS.size).put("title", CARDS[cardIndex].title)
                else JSONObject.NULL,
            )
            .put(
                "quest",
                if (q == null) JSONObject.NULL else JSONObject()
                    .put("id", q.id).put("title", q.title).put("prompt", q.prompt).put("emoji", q.emoji).put("goal", q.goal)
                    .put("found", JSONArray().also { arr -> found.forEach { arr.put(JSONObject().put("item", it.item).put("pid", it.pid).put("evidence", it.evidence)) } })
                    .put("players", boardPlayers())
                    .put("complete", complete),
            )
            .put(
                "rescue",
                if (mode == "rescue") JSONObject()
                    .put("headline", "We've hopped 7 pages in 4 minutes. I know that dizzy feeling.")
                    .put("detail", "7 pages · 4 min · ~12 s each")
                    .put(
                        "options",
                        JSONArray()
                            .put(JSONObject().put("label", "Take a detour").put("detail", "How moths find their way by moonlight").put("kind", "detour"))
                            .put(JSONObject().put("label", "Wind down").put("detail", "Lights low, back to Home").put("kind", "wind_down")),
                    )
                else JSONObject.NULL,
            )
            .put("quests", JSONArray().also { arr -> QUESTS.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("emoji", it.emoji).put("prompt", it.prompt)) } })
    }

    private fun publish() = toAll(stateJson())

    /** The scoreboard as the TV builds it: connected phones, plus "The couch" once the remote has points. */
    private fun boardPlayers(): JSONArray {
        val list = JSONArray()
        for (s in seats) {
            if (s.clientId >= 0) list.put(JSONObject().put("pid", s.pid).put("name", s.name).put("color", s.color).put("score", s.score))
        }
        if (couchScore > 0) {
            val used = seats.filter { it.clientId >= 0 }.map { it.color }.toSet()
            val color = (0 until MAX_PLAYERS).firstOrNull { it !in used } ?: 0
            list.put(JSONObject().put("pid", COUCH_PID).put("name", "The couch").put("color", color).put("score", couchScore))
        }
        return list
    }

    private fun broadcastPlayers() {
        val list = JSONArray()
        for (s in seats) {
            if (s.clientId >= 0) list.put(JSONObject().put("pid", s.pid).put("name", s.name).put("color", s.color).put("score", s.score))
        }
        toAll(JSONObject().put("t", "players").put("players", list))
    }

    private fun toast(text: String, kind: String, seat: Seat) {
        if (seat.clientId >= 0) send(seat.clientId, JSONObject().put("t", "toast").put("text", text).put("kind", kind))
    }

    private fun send(clientId: Int, o: JSONObject) {
        val text = o.toString()
        println("-> [${bySocket[clientId]?.name ?: "socket $clientId"}] $text")
        server.send(clientId, text)
    }

    private fun toAll(o: JSONObject) {
        if (bySocket.isEmpty()) return
        val text = o.toString()
        println("-> [everyone] $text")
        for (id in bySocket.keys) server.send(id, text)
    }

    private fun connected(): Int = seats.count { it.clientId >= 0 }

    private fun pickColor(requested: Int?, self: Seat?): Int {
        val taken = seats.filter { it !== self && it.clientId >= 0 }.map { it.color }.toSet()
        if (requested != null && requested in 0 until MAX_PLAYERS && requested !in taken) return requested
        return (0 until MAX_PLAYERS).firstOrNull { it !in taken } ?: 0
    }

    private fun cleanName(raw: String): String {
        val flat = raw.filter { it >= ' ' && it != '\u007f' }.trim().replace(Regex("\\s+"), " ")
        if (flat.length <= 16) return flat
        val end = if (Character.isHighSurrogate(flat[15])) 15 else 16
        return flat.substring(0, end).trim()
    }

    // ---------------------------------------------------------------- assets

    /** The same URL mapping as PhoneHub, read fresh from disk on every request so edits show up on reload. */
    private inner class DirAssets : AssetSource {
        private val root = assetsDir.canonicalFile

        override fun open(path: String): ByteArray? {
            if (path == "/harness") return CONSOLE_HTML.toByteArray(Charsets.UTF_8)
            if (path == "/harness/couch") {
                couchFind()
                return "<!doctype html><p>The remote submitted a page</p>".toByteArray(Charsets.UTF_8)
            }
            if (path.startsWith("/harness/tv/")) {
                val target = path.removePrefix("/harness/tv/")
                if (target !in MODES) return null
                forceMode(target)
                return "<!doctype html><p>TV switched to $target</p>".toByteArray(Charsets.UTF_8)
            }
            val rel = when {
                path == "/" || path == "/index.html" -> "phone/index.html"
                path.startsWith("/fonts/") -> "fonts/" + path.removePrefix("/fonts/")
                else -> "phone/" + path.removePrefix("/")
            }
            val file = File(root, rel).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
                return if (rel == "phone/index.html") CONSOLE_HTML.toByteArray(Charsets.UTF_8) else null
            }
            return file.readBytes()
        }
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("en")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }
}

/**
 * A plain protocol console: join as a player, press every phone command, switch the TV's mode and
 * watch both directions of traffic. Not the real phone page, just a test bench for the protocol.
 */
private val CONSOLE_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Wander harness console</title>
<style>
  :root { --ink:#120E1C; --night:#1B1529; --dusk:#261E3A; --line:#3A2F57; --cream:#F7EFE2; --muted:#B9AECC; --lamp:#FFB547; --coral:#FF7A6B; --mint:#3DDC97; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--ink); color:var(--cream); font:15px/1.45 system-ui,-apple-system,sans-serif; }
  header { padding:18px 16px 10px; }
  h1 { margin:0; font:400 24px/1.2 Georgia,serif; color:var(--lamp); }
  header p { margin:4px 0 0; color:var(--muted); font-size:13px; }
  main { display:grid; gap:12px; padding:0 16px 24px; grid-template-columns:minmax(0,1fr); }
  @media (min-width: 860px) { main { grid-template-columns: 380px minmax(0,1fr); } }
  section { background:var(--night); border:1px solid var(--line); border-radius:16px; padding:14px; }
  h2 { margin:0 0 10px; font-size:12px; letter-spacing:.14em; text-transform:uppercase; color:var(--muted); }
  .row { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:10px; }
  input, select { background:var(--dusk); color:var(--cream); border:1px solid var(--line); border-radius:10px; padding:9px 10px; font:inherit; min-width:0; }
  input[type=text] { flex:1 1 140px; }
  button { background:var(--dusk); color:var(--cream); border:1px solid var(--line); border-radius:999px; padding:8px 13px; font:inherit; cursor:pointer; }
  button:hover { border-color:var(--lamp); }
  button.primary { background:var(--lamp); color:var(--ink); border-color:var(--lamp); font-weight:700; }
  #status { font-weight:700; }
  #status.on { color:var(--mint); } #status.off { color:var(--coral); }
  #state { white-space:pre-wrap; word-break:break-word; font:12px/1.4 ui-monospace,Consolas,monospace; color:var(--muted); max-height:280px; overflow:auto; margin:0; }
  #log { font:12px/1.5 ui-monospace,Consolas,monospace; max-height:60vh; overflow:auto; margin:0; padding:0; list-style:none; }
  #log li { padding:3px 0; border-bottom:1px solid rgba(58,47,87,.5); word-break:break-word; }
  #log .in { color:var(--cream); } #log .out { color:var(--lamp); } #log .sys { color:var(--muted); }
</style>
</head>
<body>
<header>
  <h1>Wander harness console</h1>
  <p>A test bench for the phone protocol against the simulated TV. Status: <span id="status" class="off">offline</span></p>
</header>
<main>
  <div>
    <section>
      <h2>Join</h2>
      <div class="row">
        <input id="name" type="text" placeholder="Name" value="Maya" maxlength="24">
        <select id="color"><option value="0">Sun</option><option value="1" selected>Berry</option><option value="2">Mint</option><option value="3">Sky</option></select>
        <button class="primary" id="join">Say hello</button>
      </div>
      <div class="row"><button id="rejoin">Reconnect with my pid</button><button id="drop">Disconnect</button></div>
    </section>
    <section>
      <h2>Phone commands</h2>
      <div class="row"><input id="text" type="text" placeholder="Search, URL or text" value="cheetah top speed"></div>
      <div class="row">
        <button data-send='{"t":"go"}' data-text="1">Go</button>
        <button data-send='{"t":"type","submit":false}' data-text="1">Type</button>
        <button data-send='{"t":"type","submit":true}' data-text="1">Type + enter</button>
      </div>
      <div class="row">
        <button data-send='{"t":"move","dx":24,"dy":0}'>Move right</button>
        <button data-send='{"t":"move","dx":0,"dy":24}'>Move down</button>
        <button data-send='{"t":"tap"}'>Tap</button>
        <button data-send='{"t":"scroll","dx":0,"dy":240}'>Scroll down</button>
      </div>
      <div class="row">
        <button data-send='{"t":"key","key":"back"}'>Back</button>
        <button data-send='{"t":"key","key":"home"}'>Home</button>
        <button data-send='{"t":"key","key":"menu"}'>Menu</button>
        <button data-send='{"t":"key","key":"left"}'>Left</button>
        <button data-send='{"t":"key","key":"right"}'>Right</button>
        <button data-send='{"t":"key","key":"enter"}'>Enter</button>
        <button data-send='{"t":"key","key":"playpause"}'>Play/Pause</button>
      </div>
      <div class="row">
        <button data-send='{"t":"cards"}'>Make it cards</button>
        <button data-send='{"t":"read"}'>Read aloud</button>
        <button data-send='{"t":"zoom","delta":1}'>Zoom +</button>
        <button data-send='{"t":"zoom","delta":-1}'>Zoom -</button>
      </div>
      <div class="row">
        <select id="quest">
          <option value="speed-safari">Speed Safari</option><option value="moon-collectors">Moon Collectors</option>
          <option value="deep-dive">Deep Dive</option><option value="dino-time">Time Travelers</option>
          <option value="tiny-titans">Tiny Titans</option><option value="lumo">Lumo's pick</option>
        </select>
        <button id="questStart">Start quest</button>
        <button data-send='{"t":"quest_submit"}'>Submit page</button>
      </div>
      <div class="row">
        <button data-send='{"t":"rescue","choice":0}'>Rescue: option 1</button>
        <button data-send='{"t":"rescue","choice":1}'>Rescue: option 2</button>
        <button data-send='{"t":"rescue","choice":-1}'>We're fine</button>
        <button data-send='{"t":"ping"}'>Ping</button>
      </div>
    </section>
    <section>
      <h2>Switch the TV</h2>
      <div class="row" id="modes"></div>
    </section>
    <section>
      <h2>Latest state</h2>
      <pre id="state">No state yet.</pre>
    </section>
  </div>
  <section>
    <h2>Traffic</h2>
    <ul id="log"></ul>
  </section>
</main>
<script>
(function () {
  var ws = null, pid = null;
  var statusEl = document.getElementById('status');
  function el(id) { return document.getElementById(id); }
  function log(kind, text) {
    var li = document.createElement('li');
    li.className = kind;
    li.textContent = (kind === 'in' ? '<- ' : kind === 'out' ? '-> ' : '') + text;
    var list = el('log');
    list.insertBefore(li, list.firstChild);
    while (list.children.length > 300) list.removeChild(list.lastChild);
  }
  function setStatus(on, text) { statusEl.textContent = text; statusEl.className = on ? 'on' : 'off'; }
  function send(obj) {
    if (!ws || ws.readyState !== 1) { log('sys', 'not connected'); return; }
    var text = JSON.stringify(obj);
    ws.send(text);
    log('out', text);
  }
  function connect(withPid) {
    if (ws) { try { ws.close(); } catch (e) {} }
    ws = new WebSocket('ws://' + location.host + '/ws');
    setStatus(false, 'connecting');
    ws.onopen = function () {
      setStatus(true, 'connected');
      send({ t: 'hello', name: el('name').value, color: Number(el('color').value), pid: withPid ? pid : null });
    };
    ws.onmessage = function (ev) {
      log('in', ev.data);
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.t === 'welcome') { pid = m.pid; setStatus(true, 'joined as ' + m.name + ' (' + m.pid + ')'); }
      if (m.t === 'reject') setStatus(false, 'rejected: ' + m.reason);
      if (m.t === 'state') el('state').textContent = JSON.stringify(m, null, 2);
    };
    ws.onclose = function (ev) { setStatus(false, 'offline (' + ev.code + ')'); log('sys', 'closed ' + ev.code + ' ' + ev.reason); };
  }
  el('join').onclick = function () { connect(false); };
  el('rejoin').onclick = function () { connect(true); };
  el('drop').onclick = function () { if (ws) ws.close(1000, 'bye'); };
  el('questStart').onclick = function () { send({ t: 'quest_start', id: el('quest').value }); };
  Array.prototype.forEach.call(document.querySelectorAll('[data-send]'), function (b) {
    b.onclick = function () {
      var msg = JSON.parse(b.getAttribute('data-send'));
      if (b.getAttribute('data-text')) msg.text = el('text').value;
      send(msg);
    };
  });
  ['home', 'browse', 'cards', 'quest_picker', 'quest_intro', 'rescue', 'companion'].forEach(function (m) {
    var b = document.createElement('button');
    b.textContent = m;
    b.onclick = function () { fetch('/harness/tv/' + m).then(function () { log('sys', 'TV switched to ' + m); }); };
    el('modes').appendChild(b);
  });
  setInterval(function () { if (ws && ws.readyState === 1) ws.send('{"t":"ping"}'); }, 20000);
})();
</script>
</body>
</html>
""".trimIndent()
