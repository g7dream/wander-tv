package tv.wander.net

import dev.couchlink.AssetSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.FoundItem
import tv.wander.model.Mode
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.RescueOption
import tv.wander.model.TvState
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives a real PhoneHub over real sockets, the way phones and MainActivity will. */
class PhoneHubTest {
    private val assetFiles = mapOf(
        "phone/index.html" to "<!doctype html><title>Wander remote</title>".toByteArray(),
        "phone/phone.css" to "body{background:#120E1C}".toByteArray(),
        "phone/phone.js" to "connect()".toByteArray(),
        "fonts/YoungSerif-Regular.ttf" to ByteArray(64) { it.toByte() },
    )
    private val assetRequests = Collections.synchronizedList(ArrayList<String>())
    private val assets = AssetSource { p -> assetRequests.add(p); assetFiles[p] }

    private val events = LinkedBlockingQueue<String>()
    private val commands = LinkedBlockingQueue<Pair<String, PhoneCommand>>()
    private val listener = object : PhoneHub.Listener {
        override fun onPlayerJoined(player: Player) {
            events.add("joined ${player.id} ${player.name} ${player.colorIndex}")
        }

        override fun onPlayerLeft(player: Player) {
            events.add("left ${player.id}")
        }

        override fun onCommand(player: Player, command: PhoneCommand) {
            commands.add(player.id to command)
        }
    }

    private val phones = CopyOnWriteArrayList<TestPhone>()
    private val hubs = CopyOnWriteArrayList<PhoneHub>()
    private val port = TestPhone.freePort()

    private fun hub(p: Int = port): PhoneHub = PhoneHub(p, assets, listener) { it() }.also { hubs.add(it) }

    private fun started(): PhoneHub = hub().also { assertTrue(it.start()) }

    private fun phone(): TestPhone = TestPhone(port).also { phones.add(it) }

    private fun event(): String = events.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("no hub event")

    private fun command(): Pair<String, PhoneCommand> = commands.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("no command")

    /** Joins a phone and drains welcome/players/state. Returns the welcome. */
    private fun join(p: TestPhone, name: String?, color: Int?, pid: String? = null): JSONObject {
        p.hello(name, color, pid)
        val welcome = p.expectWelcomeSequence()
        event()
        return welcome
    }

    @After
    fun tearDown() {
        phones.forEach { it.close() }
        hubs.forEach { it.stop() }
    }

    // ------------------------------------------------------------------ joining

    @Test
    fun helloGetsWelcomeThenPlayersThenLatestState() {
        val hub = started()
        hub.publish(TvState(Mode.BROWSE, title = "Cheetah - Wikipedia", url = "https://en.wikipedia.org/wiki/Cheetah", aiOnline = true))
        val p = phone()
        p.hello("Maya", 1)

        val welcome = p.next()
        assertEquals("welcome", welcome.getString("t"))
        assertEquals("p1", welcome.getString("pid"))
        assertEquals("Maya", welcome.getString("name"))
        assertEquals(1, welcome.getInt("color"))

        val players = p.next()
        assertEquals("players", players.getString("t"))
        val list = players.getJSONArray("players")
        assertEquals(1, list.length())
        val me = list.getJSONObject(0)
        assertEquals(setOf("pid", "name", "color", "score"), me.keyNames())
        assertEquals("p1", me.getString("pid"))
        assertEquals(0, me.getInt("score"))

        val state = p.next()
        assertEquals("state", state.getString("t"))
        assertEquals("browse", state.getString("mode"))
        assertEquals("Cheetah - Wikipedia", state.getString("title"))
        assertTrue(state.getBoolean("ai"))

        assertEquals("joined p1 Maya 1", event())
        assertEquals(listOf("p1"), hub.players.map { it.id })
        assertEquals("Maya", hub.player("p1")?.name)
    }

    @Test
    fun newcomerGetsHomeStateWhenNothingWasPublished() {
        started()
        val p = phone()
        p.hello("Leo", 0)
        p.next("welcome")
        val state = p.next("state")
        assertEquals("home", state.getString("mode"))
        assertTrue(state.isNull("quest"))
    }

    @Test
    fun everyoneHearsAboutNewPlayers() {
        started()
        val a = phone()
        join(a, "Maya", 1)
        val b = phone()
        join(b, "Leo", 3)
        val update = a.next("players").getJSONArray("players")
        assertEquals(2, update.length())
        assertEquals("Maya", update.getJSONObject(0).getString("name"))
        assertEquals("Leo", update.getJSONObject(1).getString("name"))
        assertEquals("p2", update.getJSONObject(1).getString("pid"))
    }

    @Test
    fun fifthPhoneIsRejectedUntilASeatFrees() {
        val hub = started()
        val four = List(4) { i -> phone().also { join(it, "Kid$i", i) } }
        val fifth = phone()
        fifth.hello("Nana", 0)
        val reject = fifth.next()
        assertEquals("reject", reject.getString("t"))
        assertEquals("full", reject.getString("reason"))
        assertEquals(4, hub.players.size)
        assertNull("no join for the fifth phone", events.poll(200, TimeUnit.MILLISECONDS))

        // A rejected phone cannot drive the TV.
        fifth.send("""{"t":"tap"}""")
        assertNull(commands.poll(200, TimeUnit.MILLISECONDS))

        // One leaves, the waiting phone tries again on the same socket and gets the free color.
        four[2].close()
        assertEquals("left p3", event())
        fifth.hello("Nana", 0)
        val welcome = fifth.next("welcome")
        assertEquals(2, welcome.getInt("color"))
        assertEquals("joined p5 Nana 2", event())
        assertEquals(4, hub.players.size)
    }

    @Test
    fun takenColorsFallBackToTheFirstFreeOne() {
        started()
        assertEquals(2, join(phone(), "Maya", 2).getInt("color"))
        assertEquals(0, join(phone(), "Leo", 2).getInt("color"))
        assertEquals(1, join(phone(), "Ada", 0).getInt("color"))
        assertEquals(3, join(phone(), "Sam", 7).getInt("color")) // out of range -> first free
    }

    @Test
    fun namesAreCleanedAndDefaultToTheColorName() {
        started()
        assertEquals("Berry", join(phone(), "   ", 1).getString("name"))
        assertEquals("Maximiliana Rosa", join(phone(), "  Maximiliana   Rosalind the Third ", 0).getString("name"))
        assertEquals("Mint", join(phone(), null, 2).getString("name"))
        val emoji = join(phone(), "Grandpa Joe 🦉🦉🦉", 3).getString("name")
        assertTrue("16 chars or fewer: $emoji", emoji.length <= 16)
        assertFalse("no broken surrogate", Character.isHighSurrogate(emoji.last()))
    }

    @Test
    fun badHelloIsRejected() {
        started()
        val p = phone()
        p.send("""{"t":"hello","name":{"first":"Maya"},"color":1}""")
        val r = p.next()
        assertEquals("reject", r.getString("t"))
        assertEquals("bad_hello", r.getString("reason"))
        p.hello("Maya", 1) // and can still join properly
        assertEquals("welcome", p.next().getString("t"))
    }

    @Test
    fun rejoinByPidKeepsScoreAndColor() {
        val hub = started()
        val first = phone()
        val welcome = join(first, "Maya", 1)
        val pid = welcome.getString("pid")
        hub.player(pid)!!.score = 300
        hub.publish(TvState(Mode.BROWSE))
        assertEquals(300, first.next("players").getJSONArray("players").getJSONObject(0).getInt("score"))

        first.close()
        assertEquals("left $pid", event())
        assertTrue(hub.players.isEmpty())

        // Someone else takes Berry meanwhile, so the returning player gets the first free color.
        val other = phone()
        join(other, "Leo", 1)
        val back = phone()
        back.hello("Maya", 1, pid)
        val again = back.next("welcome")
        assertEquals(pid, again.getString("pid"))
        assertEquals(0, again.getInt("color"))
        val players = back.next("players").getJSONArray("players")
        val maya = (0 until players.length()).map { players.getJSONObject(it) }.first { it.getString("pid") == pid }
        assertEquals(300, maya.getInt("score"))
        assertEquals("joined $pid Maya 0", event())
        assertEquals(300, hub.player(pid)!!.score)
    }

    @Test
    fun rejoinWithSameColorFreeKeepsIt() {
        val hub = started()
        val first = phone()
        val pid = join(first, "", 3).getString("pid") // default name "Sky"
        first.close()
        event()
        val back = phone()
        back.hello(null, 0, pid)
        val w = back.next("welcome")
        assertEquals(3, w.getInt("color"))
        assertEquals("Sky", w.getString("name"))
        assertEquals(1, hub.players.size)
    }

    @Test
    fun samePhoneReconnectingTakesOverItsSeat() {
        val hub = started()
        val old = phone()
        val pid = join(old, "Maya", 1).getString("pid")
        val fresh = phone()
        fresh.hello("Maya", 1, pid)
        assertEquals(pid, fresh.expectWelcomeSequence().getString("pid"))
        assertEquals(4000, old.closeCodes.poll(3, TimeUnit.SECONDS))
        assertEquals(1, hub.players.size)
        assertNull("a pure takeover is not a join or leave", events.poll(300, TimeUnit.MILLISECONDS))
        fresh.send("""{"t":"tap"}""")
        assertEquals(pid to PhoneCommand.Tap, command())
    }

    @Test
    fun stalePidWithAnotherNameJoinsAsSomeoneNew() {
        val hub = started()
        join(phone(), "Maya", 1) // p1
        val stranger = phone()
        val w = join(stranger, "Leo", 1, pid = "p1")
        assertEquals("p2", w.getString("pid"))
        assertEquals(2, hub.players.size)
    }

    @Test
    fun stalePidCannotResumeSomeoneElsesDisconnectedSeat() {
        // After a TV restart pids start at p1 again: Ann rejoined first and got p1, then blipped. Ben's
        // phone still remembers "p1" from the earlier session and must not inherit Ann's seat and score.
        val hub = started()
        val ann = phone()
        val annPid = join(ann, "Ann", 2).getString("pid")
        hub.player(annPid)!!.score = 300
        ann.close()
        assertEquals("left $annPid", event())

        val ben = phone()
        val w = join(ben, "Ben", 1, pid = annPid)
        assertNotEquals(annPid, w.getString("pid"))
        assertEquals("Ben", w.getString("name"))
        assertEquals(1, w.getInt("color"))
        assertEquals(0, hub.player(w.getString("pid"))!!.score)

        // Ann comes back with her pid and name: same seat, same score.
        val annAgain = phone()
        annAgain.hello("Ann", 2, annPid)
        val back = annAgain.expectWelcomeSequence()
        assertEquals(annPid, back.getString("pid"))
        assertEquals(300, hub.player(annPid)!!.score)
        assertEquals("Ann", hub.player(annPid)!!.name)
    }

    // ------------------------------------------------------------------ commands

    @Test
    fun everyCommandTypeIsParsed() {
        started()
        val p = phone()
        val pid = join(p, "Maya", 1).getString("pid")
        val cases = listOf(
            """{"t":"move","dx":12.5,"dy":-3}""" to PhoneCommand.Move(12.5f, -3f),
            """{"t":"tap"}""" to PhoneCommand.Tap,
            """{"t":"scroll","dx":0,"dy":120}""" to PhoneCommand.Scroll(0f, 120f),
            """{"t":"go","text":"  cheetah top speed  "}""" to PhoneCommand.Go("cheetah top speed"),
            """{"t":"type","text":"hello","submit":true}""" to PhoneCommand.Type("hello", true),
            """{"t":"type","text":"hel"}""" to PhoneCommand.Type("hel", false),
            """{"t":"key","key":"back"}""" to PhoneCommand.Key("back"),
            """{"t":"key","key":"PlayPause"}""" to PhoneCommand.Key("playpause"),
            """{"t":"cards"}""" to PhoneCommand.MakeCards,
            """{"t":"quest_submit"}""" to PhoneCommand.QuestSubmit,
            """{"t":"quest_start","id":"speed-safari"}""" to PhoneCommand.QuestStart("speed-safari"),
            """{"t":"quest_start","id":"lumo"}""" to PhoneCommand.QuestStart("lumo"),
            """{"t":"read"}""" to PhoneCommand.ReadAloud,
            """{"t":"rescue","choice":0}""" to PhoneCommand.Rescue(0),
            """{"t":"rescue","choice":-1}""" to PhoneCommand.Rescue(-1),
            """{"t":"zoom","delta":1}""" to PhoneCommand.Zoom(1),
            """{"t":"zoom","delta":-3}""" to PhoneCommand.Zoom(-1),
        )
        for ((json, _) in cases) p.send(json)
        for ((json, expected) in cases) assertEquals(json, pid to expected, command())
        for (key in listOf("home", "menu", "up", "down", "left", "right", "enter")) {
            p.send("""{"t":"key","key":"$key"}""")
            assertEquals(PhoneCommand.Key(key), command().second)
        }
    }

    @Test
    fun malformedInputIsIgnoredAndNumbersAreClamped() {
        started()
        val p = phone()
        join(p, "Maya", 1)
        val junk = listOf(
            "not json at all",
            "[1,2,3]",
            "42",
            "{}",
            """{"t":5}""",
            """{"t":"warp","to":"mars"}""",
            """{"t":"move","dx":"NaN","dy":"Infinity"}""",
            """{"t":"move"}""",
            """{"t":"scroll","dx":0,"dy":0}""",
            """{"t":"key","key":"selfdestruct"}""",
            """{"t":"key","key":7}""",
            """{"t":"go","text":"   "}""",
            """{"t":"go","text":["a"]}""",
            """{"t":"type","text":""}""",
            """{"t":"quest_start","id":"../../etc"}""",
            """{"t":"quest_start","id":""}""",
            """{"t":"rescue","choice":"later"}""",
            """{"t":"rescue","choice":42}""",
            """{"t":"zoom","delta":0}""",
            """{"t":"tap",""" + "\"x\":".repeat(3) + "[[[[[[[[[[1]]]]]]]]]]}",
            """{"t":"move","dx":1e9,"dy":-1e9}""",
            """{"t":"scroll","dx":0,"dy":99999}""",
            """{"t":"go","text":"${"z".repeat(800)}"}""",
            """{"t":"type","text":"line1\nline2\u0007"}""",
        )
        for (j in junk) p.send(j)
        p.send("""{"t":"tap"}""")
        assertEquals(PhoneCommand.Move(400f, -400f), command().second)
        assertEquals(PhoneCommand.Scroll(0f, 2400f), command().second)
        val go = command().second as PhoneCommand.Go
        assertEquals(500, go.text.length)
        assertEquals(PhoneCommand.Type("line1 line2", false), command().second)
        assertEquals(PhoneCommand.Tap, command().second)
        assertNull("nothing else got through", commands.poll(200, TimeUnit.MILLISECONDS))
        // The connection survived all of it.
        p.send("""{"t":"ping"}""")
        assertEquals("pong", p.next("pong").getString("t"))
    }

    @Test
    fun commandsBeforeHelloAreIgnored() {
        started()
        val p = phone()
        p.send("""{"t":"go","text":"sneaky"}""")
        p.send("""{"t":"ping"}""")
        assertEquals("pong", p.next().getString("t"))
        assertNull(commands.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun parserUnitCases() {
        val hello = PhoneProtocol.parse("""{"t":"hello","name":"Maya","color":1,"pid":"p3"}""")
        assertEquals(PhoneProtocol.Incoming.Hello("Maya", 1, "p3"), hello)
        assertEquals(PhoneProtocol.Incoming.Hello(null, null, null), PhoneProtocol.parse("""{"t":"hello"}"""))
        assertEquals(PhoneProtocol.Incoming.Hello("7", 2, null), PhoneProtocol.parse("""{"t":"hello","name":7,"color":"2","pid":null}"""))
        assertEquals(PhoneProtocol.Incoming.BadHello, PhoneProtocol.parse("""{"t":"hello","color":[1]}"""))
        assertEquals(PhoneProtocol.Incoming.Ping, PhoneProtocol.parse("""{"t":"ping"}"""))
        assertNull(PhoneProtocol.parse("""{"t":"tap",""" + "\"a\":[".repeat(10) + "1" + "]".repeat(10) + "}"))
        assertEquals("", PhoneProtocol.cleanName("\u0000\u0007  "))
        assertEquals("Ana Maria", PhoneProtocol.cleanName("Ana\n\tMaria"))
    }

    // ------------------------------------------------------------------ TV -> phone

    @Test
    fun publishSerializesStateExactlyAsSpecified() {
        val maya = Player("p3", "Maya", 1, score = 200)
        val quest = Quest(
            id = "speed-safari",
            title = "Speed Safari",
            prompt = "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).",
            goal = 3,
            emoji = "🐆",
            rubric = "Accept animals with a recorded top speed above 100 km/h.",
        )
        val state = TvState(
            mode = Mode.QUEST_INTRO,
            title = "Cheetah - Wikipedia",
            url = "https://en.wikipedia.org/wiki/Cheetah",
            quest = QuestBoard(quest, listOf(FoundItem("Cheetah", "p3", "The cheetah can run at 93 to 104 km/h")), listOf(maya), complete = false),
            cardIndex = 1,
            cardCount = 4,
            cardTitle = "Built for speed",
            rescue = DriftSignal(DriftReason.RAPID_HOPPING, "We've hopped 7 pages in 4 minutes.", "7 pages · 4 min · ~12 s each"),
            rescueOptions = listOf(
                RescueOption(RescueOption.Kind.QUEST, "Start Speed Safari", "Race to find fast animals", questId = "speed-safari"),
                RescueOption(RescueOption.Kind.DETOUR, "Visit the Moon", "A calm detour", url = "https://moon.nasa.gov"),
            ),
            textFieldFocused = true,
            fieldLabel = "Search",
            checking = true,
            aiOnline = true,
            quests = listOf(quest),
        )
        val o = JSONObject(PhoneProtocol.state(state))
        assertEquals(
            setOf("t", "mode", "title", "url", "checking", "ai", "field", "cards", "quest", "rescue", "quests"),
            o.keyNames(),
        )
        assertEquals("state", o.getString("t"))
        assertEquals("quest_intro", o.getString("mode"))
        assertEquals("https://en.wikipedia.org/wiki/Cheetah", o.getString("url"))
        assertTrue(o.getBoolean("checking"))
        assertTrue(o.getBoolean("ai"))

        val field = o.getJSONObject("field")
        assertEquals(setOf("focused", "label"), field.keyNames())
        assertTrue(field.getBoolean("focused"))
        assertEquals("Search", field.getString("label"))

        val cards = o.getJSONObject("cards")
        assertEquals(setOf("index", "count", "title"), cards.keyNames())
        assertEquals(1, cards.getInt("index"))
        assertEquals(4, cards.getInt("count"))
        assertEquals("Built for speed", cards.getString("title"))

        val q = o.getJSONObject("quest")
        assertEquals(setOf("id", "title", "prompt", "emoji", "goal", "found", "players", "complete"), q.keyNames())
        assertEquals("speed-safari", q.getString("id"))
        assertEquals("🐆", q.getString("emoji"))
        assertEquals(3, q.getInt("goal"))
        assertFalse(q.getBoolean("complete"))
        val found = q.getJSONArray("found").getJSONObject(0)
        assertEquals(setOf("item", "pid", "evidence"), found.keyNames())
        assertEquals("Cheetah", found.getString("item"))
        assertEquals("p3", found.getString("pid"))
        // The board's players (the remote's "couch" player included) ride along for names and colors.
        val boardPlayers = q.getJSONArray("players")
        assertEquals(1, boardPlayers.length())
        val bp = boardPlayers.getJSONObject(0)
        assertEquals(setOf("pid", "name", "color", "score"), bp.keyNames())
        assertEquals("p3", bp.getString("pid"))
        assertEquals("Maya", bp.getString("name"))
        assertEquals(1, bp.getInt("color"))
        assertEquals(200, bp.getInt("score"))
        val couch = Player("couch", "The couch", 2, score = 100)
        val withCouch = JSONObject(PhoneProtocol.state(state.copy(quest = state.quest!!.copy(players = listOf(maya, couch)))))
            .getJSONObject("quest").getJSONArray("players")
        assertEquals("couch", withCouch.getJSONObject(1).getString("pid"))
        assertEquals("The couch", withCouch.getJSONObject(1).getString("name"))
        assertEquals(2, withCouch.getJSONObject(1).getInt("color"))
        assertEquals(100, withCouch.getJSONObject(1).getInt("score"))

        val rescue = o.getJSONObject("rescue")
        assertEquals(setOf("headline", "detail", "options"), rescue.keyNames())
        val options = rescue.getJSONArray("options")
        assertEquals(2, options.length())
        assertEquals(setOf("label", "detail", "kind"), options.getJSONObject(0).keyNames())
        assertEquals("Start Speed Safari", options.getJSONObject(0).getString("label"))
        assertEquals("quest", options.getJSONObject(0).getString("kind"))
        assertEquals("detour", options.getJSONObject(1).getString("kind"))
        val windDown = RescueOption(RescueOption.Kind.WIND_DOWN, "Wind down", "Lights low")
        assertEquals(
            "wind_down",
            JSONObject(PhoneProtocol.state(state.copy(rescueOptions = listOf(windDown)))).getJSONObject("rescue")
                .getJSONArray("options").getJSONObject(0).getString("kind"),
        )

        val quests = o.getJSONArray("quests")
        assertEquals(setOf("id", "title", "emoji", "prompt"), quests.getJSONObject(0).keyNames())

        // Absent parts are explicit nulls (field is always an object).
        val bare = JSONObject(PhoneProtocol.state(TvState(Mode.HOME)))
        assertEquals("home", bare.getString("mode"))
        assertTrue(bare.isNull("cards"))
        assertTrue(bare.isNull("quest"))
        assertTrue(bare.isNull("rescue"))
        assertFalse(bare.getJSONObject("field").getBoolean("focused"))
        assertEquals(0, bare.getJSONArray("quests").length())
        assertTrue(bare.has("cards") && bare.has("quest") && bare.has("rescue"))
        for (mode in Mode.values()) {
            assertEquals(mode.name.lowercase(), JSONObject(PhoneProtocol.state(TvState(mode))).getString("mode"))
        }
        assertEquals(
            JSONObject.NULL,
            JSONObject(PhoneProtocol.state(state.copy(quest = state.quest!!.copy(found = listOf(FoundItem("Sailfish", "p3", null))))))
                .getJSONObject("quest").getJSONArray("found").getJSONObject(0).get("evidence"),
        )
    }

    @Test
    fun publishReachesEveryPlayerOnceAndAdoptsQuestScores() {
        val hub = started()
        val a = phone()
        join(a, "Maya", 1)
        val b = phone()
        join(b, "Leo", 2)
        a.next("players")

        val browse = TvState(Mode.BROWSE, title = "Saturn")
        hub.publish(browse)
        hub.publish(browse) // unchanged: not re-sent
        assertEquals("Saturn", a.next("state").getString("title"))
        assertEquals("Saturn", b.next("state").getString("title"))
        a.expectSilence()

        // A quest board with a new score: players goes out again, then the state.
        val quest = Quest("moon-collectors", "Moon Collectors", "Find two planets that have more than 20 moons.", 2, "🪐", "rubric")
        val board = QuestBoard(quest, listOf(FoundItem("Saturn", "p1", "Saturn has 146 moons")), listOf(Player("p1", "Maya", 1, score = 100)), false)
        hub.publish(TvState(Mode.BROWSE, title = "Saturn", quest = board))
        val players = a.next()
        assertEquals("players", players.getString("t"))
        assertEquals(100, players.getJSONArray("players").getJSONObject(0).getInt("score"))
        val state = a.next()
        assertEquals("state", state.getString("t"))
        assertEquals("moon-collectors", state.getJSONObject("quest").getString("id"))
        assertEquals(100, hub.player("p1")!!.score)

        // Newcomers get the latest state.
        val c = phone()
        c.hello("Ada", 3)
        c.next("welcome")
        c.next("players")
        assertEquals("moon-collectors", c.next("state").getJSONObject("quest").getString("id"))
    }

    @Test
    fun toastsAndCelebrations() {
        val hub = started()
        val a = phone()
        join(a, "Maya", 1)
        val b = phone()
        join(b, "Leo", 2)
        a.next("players")

        hub.toast("Lumo is checking your page...")
        for (p in listOf(a, b)) {
            val t = p.next("toast")
            assertEquals("Lumo is checking your page...", t.getString("text"))
            assertEquals("info", t.getString("kind"))
        }
        hub.toast("Not quite!", "bad", playerId = "p2")
        assertEquals("bad", b.next("toast").getString("kind"))
        a.expectSilence()
        hub.toast("Odd kind", "sparkly")
        assertEquals("info", a.next("toast").getString("kind"))
        b.next("toast")

        hub.celebrate("p1", "+100 Cheetah!")
        val c = a.next("celebrate")
        assertEquals("+100 Cheetah!", c.getString("text"))
        assertEquals(1, c.getInt("color"))
        b.expectSilence()

        hub.celebrate("p9", "nobody") // unknown player: no crash, nothing sent
        hub.toast("nobody", playerId = "p9")
        a.expectSilence(150)
    }

    // ------------------------------------------------------------------ HTTP + lifecycle

    @Test
    fun servesThePhonePageFontsAndFiles() {
        started()
        val (s1, h1, b1) = TestPhone.httpGet(port, "/")
        assertEquals(200, s1)
        assertTrue(h1["content-type"]!!.startsWith("text/html"))
        assertArrayEquals(assetFiles["phone/index.html"], b1)

        val (s2, h2, b2) = TestPhone.httpGet(port, "/fonts/YoungSerif-Regular.ttf")
        assertEquals(200, s2)
        assertEquals("font/ttf", h2["content-type"])
        assertArrayEquals(assetFiles["fonts/YoungSerif-Regular.ttf"], b2)

        val (s3, h3, b3) = TestPhone.httpGet(port, "/phone.css")
        assertEquals(200, s3)
        assertEquals("text/css; charset=utf-8", h3["content-type"])
        assertArrayEquals(assetFiles["phone/phone.css"], b3)

        assertEquals(404, TestPhone.httpGet(port, "/missing.js").first)
        assertEquals(404, TestPhone.httpGet(port, "/fonts/").first)
        assertEquals(400, TestPhone.httpGet(port, "/fonts/../../secret").first)
        assertTrue(assetRequests.containsAll(listOf("phone/index.html", "fonts/YoungSerif-Regular.ttf", "phone/phone.css", "phone/missing.js")))
        assertTrue(assetRequests.none { it.contains("..") || it.startsWith("/") })

        assertEquals("phone/index.html", PhoneProtocol.assetPathFor("/"))
        assertEquals("phone/index.html", PhoneProtocol.assetPathFor("/index.html"))
        assertEquals("fonts/AtkinsonHyperlegible-Bold.ttf", PhoneProtocol.assetPathFor("/fonts/AtkinsonHyperlegible-Bold.ttf"))
        assertEquals("phone/phone.js", PhoneProtocol.assetPathFor("/phone.js"))
        assertEquals("phone/img/lumo.svg", PhoneProtocol.assetPathFor("/img/lumo.svg"))
        assertNull(PhoneProtocol.assetPathFor("/fonts"))
    }

    @Test
    fun startFailsCleanlyWhenThePortIsBusy() {
        ServerSocket(port).use {
            val hub = hub()
            assertFalse(hub.start())
            hub.stop()
            hub.stop()
            hub.publish(TvState(Mode.HOME)) // still safe
            hub.toast("nobody listening")
        }
    }

    @Test
    fun stopIsSafeTwiceAndPhonesCanResumeAfterARestart() {
        val hub = started()
        val p = phone()
        val pid = join(p, "Maya", 1).getString("pid")
        hub.player(pid)!!.score = 150
        assertTrue("start twice is fine", hub.start())

        hub.stop()
        assertEquals("left $pid", event())
        hub.stop()
        assertEquals(1001, p.closeCodes.poll(3, TimeUnit.SECONDS))
        assertTrue(hub.players.isEmpty())

        assertTrue(hub.start())
        val again = phone()
        again.hello("Maya", 1, pid)
        assertEquals(pid, again.next("welcome").getString("pid"))
        assertEquals(150, again.next("players").getJSONArray("players").getJSONObject(0).getInt("score"))
        assertEquals("joined $pid Maya 1", event())
    }

    @Test
    fun listenerCallsGoThroughPost() {
        val posted = LinkedBlockingQueue<() -> Unit>()
        val hub = PhoneHub(port, assets, listener) { posted.add(it) }.also { hubs.add(it) }
        assertTrue(hub.start())
        val p = phone()
        p.hello("Maya", 1)
        p.expectWelcomeSequence()
        p.send("""{"t":"tap"}""")
        val joinEvent = posted.poll(3, TimeUnit.SECONDS)
        assertNotNull(joinEvent)
        assertTrue("nothing runs until posted work runs", events.isEmpty())
        joinEvent!!.invoke()
        assertEquals("joined p1 Maya 1", event())
        posted.poll(3, TimeUnit.SECONDS)!!.invoke()
        assertEquals("p1" to PhoneCommand.Tap, command())
    }

    @Test
    fun listenerExceptionsAreContained() {
        val angry = object : PhoneHub.Listener {
            override fun onPlayerJoined(player: Player) = throw IllegalStateException("UI not ready")
            override fun onPlayerLeft(player: Player) = throw IllegalStateException("UI gone")
            override fun onCommand(player: Player, command: PhoneCommand) {
                commands.add(player.id to command)
                throw IllegalStateException("bad command handler")
            }
        }
        val hub = PhoneHub(port, assets, angry) { it() }.also { hubs.add(it) }
        assertTrue(hub.start())
        val p = phone()
        p.hello("Maya", 1)
        p.expectWelcomeSequence()
        p.send("""{"t":"tap"}""")
        p.send("""{"t":"cards"}""")
        assertEquals(PhoneCommand.Tap, command().second)
        assertEquals(PhoneCommand.MakeCards, command().second)
        assertNotEquals(0, hub.players.size)
    }
}

private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
