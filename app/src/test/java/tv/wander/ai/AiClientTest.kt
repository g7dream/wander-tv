package tv.wander.ai

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.PageText
import tv.wander.model.RescueOption
import tv.wander.model.SessionStats
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** AiClient against a fake HTTP layer: success, timeout, 5xx, malformed JSON, guardrail blocks. */
@RunWith(RobolectricTestRunner::class)
class AiClientTest {
    private val base = "https://api.example.test"
    private val key = "wtv_test_key_123456"

    private class FakeTransport(var handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    private fun ok(json: String) = HttpResponse(200, json.toByteArray(Charsets.UTF_8))

    private val posts = AtomicInteger()

    private fun client(
        transport: FakeTransport,
        url: String = base,
        timeouts: AiClient.Timeouts = AiClient.Timeouts.SPEC,
    ) = AiClient(
        url, key,
        post = { block -> posts.incrementAndGet(); block() },
        transport = transport,
        background = { block -> Thread(block, "test-bg").start() },
        timeouts = timeouts,
    )

    /** Runs one async call and waits for its single answer. */
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Any?>()
        start { v -> results.add(v); latch.countDown() }
        assertTrue("callback never fired", latch.await(5, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return results[0] as T
    }

    private val page = SamplePages.CHEETAH

    private val cardsJson = """
        {"cards":[
          {"title":"Meet the cheetah","line":"A big wild cat that lives mostly in Africa.","emoji":"🐆","icon":"animal"},
          {"title":"Built for speed","line":"It sprints about 110 km/h, faster than a car on the highway!","emoji":"⚡","icon":"speed"},
          {"title":"Hide and seek","line":"Cubs have fluffy grey fur that helps them hide in tall grass.","emoji":"","icon":"family"}
        ],"cached":false}
    """.trimIndent()

    // ------------------------------------------------------------------ configuration

    @Test
    fun unsetUrlUsesOfflineWithoutTouchingTheNetwork() {
        val t = FakeTransport { error("must not be called") }
        val c = client(t, url = "")
        assertFalse(c.configured)
        val deck = await<tv.wander.model.CardDeck> { c.cards(page, it) }
        assertFalse(deck.fromAi)
        assertTrue(deck.cards.size in 3..5)
        assertFalse(c.online)
        assertTrue(t.requests.isEmpty())
        assertFalse(await<Boolean> { c.health(it) })
        assertNull(await<ByteArray?> { c.speak("hello", it) })
    }

    @Test
    fun invalidUrlCountsAsUnconfigured() {
        assertFalse(client(FakeTransport { ok("{}") }, url = "not a url").configured)
        assertTrue(client(FakeTransport { ok("{}") }, url = "https://x.test/").configured)
    }

    // ------------------------------------------------------------------ cards

    @Test
    fun cardsSuccess() {
        val t = FakeTransport { ok(cardsJson) }
        val c = client(t)
        val longPage = page.copy(text = page.text + "\n" + "More facts about cheetahs. ".repeat(400))
        val deck = await<tv.wander.model.CardDeck> { c.cards(longPage, it) }
        assertTrue(deck.fromAi)
        assertEquals(3, deck.cards.size)
        assertEquals("Built for speed", deck.cards[1].title)
        assertTrue(deck.cards[2].emoji.isNotBlank()) // empty emoji filled from the keyword map
        assertTrue(c.online)

        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$base/cards", req.url)
        assertEquals(key, req.headers["x-wander-key"])
        assertEquals(8_000, req.timeoutMs)
        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals(page.url, body.getString("url"))
        assertEquals(6000, body.getString("text").length)
    }

    @Test
    fun answersArriveThroughPostExactlyOnce() {
        val t = FakeTransport { ok(cardsJson) }
        val c = client(t)
        val before = posts.get()
        await<tv.wander.model.CardDeck> { c.cards(page, it) }
        Thread.sleep(100)
        assertEquals(before + 1, posts.get())
    }

    @Test
    fun timeoutFallsBackOnceAndIgnoresTheLateAnswer() {
        val calls = AtomicInteger()
        val t = FakeTransport { Thread.sleep(1_200); ok(cardsJson) }
        val c = client(t, timeouts = AiClient.Timeouts(cards = 150, quest = 150, companion = 150, health = 150, speak = 150))
        val latch = CountDownLatch(1)
        var deck: tv.wander.model.CardDeck? = null
        val started = System.nanoTime()
        c.cards(page) { calls.incrementAndGet(); deck = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("fallback took $tookMs ms", tookMs < 1_000)
        assertFalse(deck!!.fromAi)
        assertFalse(c.online)
        Thread.sleep(1_500) // the slow answer lands now and must be dropped
        assertEquals(1, calls.get())
        assertFalse(c.online)
    }

    @Test
    fun networkErrorFallsBack() {
        val c = client(FakeTransport { throw SocketTimeoutException("read timed out") })
        val deck = await<tv.wander.model.CardDeck> { c.cards(page, it) }
        assertFalse(deck.fromAi)
        assertFalse(c.online)
    }

    @Test
    fun serverErrorFallsBackAndOnlineTracksTheLastCall() {
        val t = FakeTransport { HttpResponse(500, """{"error":"internal"}""".toByteArray()) }
        val c = client(t)
        assertFalse(await<tv.wander.model.CardDeck> { c.cards(page, it) }.fromAi)
        assertFalse(c.online)
        t.handler = { ok(cardsJson) }
        assertTrue(await<tv.wander.model.CardDeck> { c.cards(page, it) }.fromAi)
        assertTrue(c.online)
        t.handler = { HttpResponse(503, """{"error":"busy"}""".toByteArray()) }
        await<tv.wander.model.CardDeck> { c.cards(page, it) }
        assertFalse(c.online)
    }

    @Test
    fun malformedJsonFallsBack() {
        val c = client(FakeTransport { ok("<html>gateway hiccup</html>") })
        assertFalse(await<tv.wander.model.CardDeck> { c.cards(page, it) }.fromAi)
        val c2 = client(FakeTransport { ok("""{"cards":[{"title":"","line":""}]}""") })
        assertFalse(await<tv.wander.model.CardDeck> { c2.cards(page, it) }.fromAi)
        val c3 = client(FakeTransport { ok("""{"something":"else"}""") })
        assertFalse(await<tv.wander.model.CardDeck> { c3.cards(page, it) }.fromAi)
    }

    @Test
    fun blockedCardsAreSafeAndNeverEchoThePage() {
        val c = client(FakeTransport {
            ok("""{"blocked":true,"cards":[{"title":"Let's skip this page","line":"Lumo can't make cards from this one.","emoji":"🌙","icon":"moon"}],"cached":false}""")
        })
        val deck = await<tv.wander.model.CardDeck> { c.cards(page, it) }
        assertEquals(1, deck.cards.size)
        assertEquals("Let's skip this page", deck.cards[0].title)
        assertTrue(c.online)
        val c2 = client(FakeTransport { ok("""{"blocked":true,"cards":"nonsense"}""") })
        val safe = await<tv.wander.model.CardDeck> { c2.cards(page, it) }
        assertEquals(1, safe.cards.size)
        assertFalse(safe.cards[0].line.contains("cheetah", ignoreCase = true))
    }

    // ------------------------------------------------------------------ quests

    @Test
    fun questCheckParsesTheVerdict() {
        val t = FakeTransport {
            ok("""{"pass":true,"item":"Cheetah","reason":"Yes! Cheetahs sprint about 110 km/h!","evidence":"A cheetah can sprint at about 110 km/h"}""")
        }
        val c = client(t)
        val v = await<tv.wander.model.Verdict> { c.checkQuest(SamplePages.SPEED_SAFARI, page, listOf("Sailfish"), it) }
        assertTrue(v.pass)
        assertEquals("Cheetah", v.item)
        assertTrue(v.fromAi)
        assertEquals("A cheetah can sprint at about 110 km/h", v.evidence)
        val body = JSONObject(String(t.requests.single().body!!, Charsets.UTF_8))
        assertEquals("speed-safari", body.getJSONObject("quest").getString("id"))
        assertEquals(3, body.getJSONObject("quest").getInt("goal"))
        assertEquals("Sailfish", body.getJSONArray("found").getString(0))
        assertEquals("$base/quest/check", t.requests.single().url)
    }

    @Test
    fun aPassWithoutAnItemIsMalformed() {
        val c = client(FakeTransport { ok("""{"pass":true,"item":null,"reason":"Yes","evidence":null}""") })
        val v = await<tv.wander.model.Verdict> { c.checkQuest(SamplePages.SPEED_SAFARI, page, emptyList(), it) }
        assertFalse(v.fromAi) // offline judge took over
        assertTrue(v.pass)   // and the offline judge finds the cheetah in the title
        assertEquals("Cheetah", v.item)
    }

    @Test
    fun blockedQuestCheckIsANo() {
        val c = client(FakeTransport { ok("""{"blocked":true,"pass":false,"item":null,"reason":"Lumo can't judge this page.","evidence":null}""") })
        val v = await<tv.wander.model.Verdict> { c.checkQuest(SamplePages.SPEED_SAFARI, page, emptyList(), it) }
        assertFalse(v.pass)
        assertNull(v.item)
        assertEquals("Lumo can't judge this page.", v.reason)
    }

    @Test
    fun newQuestFromTheBackend() {
        val t = FakeTransport {
            ok("""{"quest":{"id":"lumo-1a2b3c4d","title":"Ring Hunters","prompt":"Find two planets that have rings.","goal":7,"emoji":"🔭","rubric":"Saturn, Jupiter, Uranus, Neptune."}}""")
        }
        val c = client(t)
        val q = await<tv.wander.model.Quest?> { c.newQuest(listOf("Saturn", "Jupiter"), it) }!!
        assertEquals("lumo-1a2b3c4d", q.id)
        assertEquals(3, q.goal)
        assertTrue(q.generated)
        assertTrue(q.offline.isEmpty())
        val body = JSONObject(String(t.requests.single().body!!, Charsets.UTF_8))
        assertEquals(setOf("recent"), body.keys().asSequence().toSet()) // titles only
    }

    @Test
    fun newQuestFallsBackToATopicAwareOfflineQuest() {
        val c = client(FakeTransport { throw java.io.IOException("offline") })
        val q = await<tv.wander.model.Quest?> { c.newQuest(listOf("Saturn"), it) }!!
        assertEquals("Ring Spotters", q.title)
        assertTrue(q.offline.isNotEmpty())
        val blocked = client(FakeTransport { ok("""{"blocked":true,"quest":{}}""") })
        assertEquals("Record Breakers", await<tv.wander.model.Quest?> { blocked.newQuest(listOf("Saturn"), it) }!!.title)
    }

    // ------------------------------------------------------------------ companion

    private val stats = SessionStats(14, 7, 12, 20, 1, 19, 22)
    private val signal = DriftSignal(DriftReason.RAPID_HOPPING, "We've hopped 7 pages in 4 minutes.", "7 pages · 4 min")

    @Test
    fun companionParsesMoodLineAndSuggestion() {
        val t = FakeTransport {
            ok("""{"mood":"sleepy","line":"My wings are tired!","suggestion":{"kind":"detour","label":"See creatures that glow","detail":"Living light","url":"https://simple.wikipedia.org/wiki/Bioluminescence"}}""")
        }
        val c = client(t)
        val a = await<tv.wander.model.CompanionAdvice> { c.advise(stats, signal, it) }
        assertEquals(Mood.SLEEPY, a.mood)
        assertEquals("My wings are tired!", a.line)
        assertEquals(RescueOption.Kind.DETOUR, a.suggestion!!.kind)
        assertEquals("https://simple.wikipedia.org/wiki/Bioluminescence", a.suggestion!!.url)
        assertEquals(4_000, t.requests.single().timeoutMs)
        val body = JSONObject(String(t.requests.single().body!!, Charsets.UTF_8))
        assertEquals("RAPID_HOPPING", body.getJSONObject("signal").getString("reason"))
        assertEquals(22, body.getJSONObject("stats").getInt("pagesTotal"))
    }

    @Test
    fun companionRepairsBadSuggestionsAndMoods() {
        val c = client(FakeTransport {
            ok("""{"mood":"furious","line":"Whoa, so many pages!","suggestion":{"kind":"detour","label":"Bad","url":"javascript:alert(1)"}}""")
        })
        val a = await<tv.wander.model.CompanionAdvice> { c.advise(stats, signal, it) }
        assertEquals(Mood.CONCERNED, a.mood) // the local mood for hopping
        assertEquals("Whoa, so many pages!", a.line)
        assertNotNull(a.suggestion)
        assertTrue(a.suggestion!!.url == null || a.suggestion!!.url!!.startsWith("https://"))
    }

    @Test
    fun companionQuestSuggestionGetsDefaultLabels() {
        val c = client(FakeTransport { ok("""{"mood":"concerned","line":"Dizzy!","suggestion":{"kind":"quest","questId":"deep-dive","label":"","detail":""}}""") })
        val s = await<tv.wander.model.CompanionAdvice> { c.advise(stats, signal, it) }.suggestion!!
        assertEquals("deep-dive", s.questId)
        assertEquals("Start Deep Dive", s.label)
    }

    // ------------------------------------------------------------------ speech + health

    @Test
    fun speakDecodesBase64Audio() {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x7F, -1, -5)
        val b64 = android.util.Base64.encodeToString(mp3, android.util.Base64.NO_WRAP)
        val t = FakeTransport { ok("""{"audio":"$b64","voice":"Ivy"}""") }
        val c = client(t)
        assertArrayEquals(mp3, await<ByteArray?> { c.speak("Hi, I'm Lumo!", it) })
        assertEquals(10_000, t.requests.single().timeoutMs)
        val long = "word ".repeat(400)
        await<ByteArray?> { c.speak(long, it) }
        val body = JSONObject(String(t.requests[1].body!!, Charsets.UTF_8))
        assertTrue(body.getString("text").length <= 600)
    }

    @Test
    fun speakFailuresAreNull() {
        assertNull(await<ByteArray?> { client(FakeTransport { HttpResponse(502, ByteArray(0)) }).speak("hi", it) })
        assertNull(await<ByteArray?> { client(FakeTransport { ok("""{"audio":"%%%not base64%%%"}""") }).speak("hi", it) })
        val t = FakeTransport { error("must not be called") }
        assertNull(await<ByteArray?> { client(t).speak("   ", it) })
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun healthReflectsTheBackend() {
        val t = FakeTransport { ok("""{"ok":true,"model":"eu.anthropic.claude-haiku-5-5","region":"eu-central-1"}""") }
        val c = client(t)
        assertTrue(await<Boolean> { c.health(it) })
        assertTrue(c.online)
        assertEquals("GET", t.requests.single().method)
        assertNull(t.requests.single().body)
        t.handler = { HttpResponse(401, """{"error":"unauthorized"}""".toByteArray()) }
        assertFalse(await<Boolean> { c.health(it) })
        assertFalse(c.online)
    }

    @Test
    fun aThrowingCallbackDoesNotEscape() {
        val c = client(FakeTransport { ok(cardsJson) })
        val latch = CountDownLatch(1)
        c.cards(page) { latch.countDown(); throw IllegalStateException("caller bug") }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Reaching here without the exception tearing down the worker thread is the assertion; one more call works.
        assertTrue(await<tv.wander.model.CardDeck> { c.cards(page, it) }.fromAi)
    }

    @Test
    fun pageTextIsCappedWithoutSplittingEmoji() {
        val text = "a".repeat(5999) + "🐆" + "tail"
        val trimmed = AiJson.trimText(text)
        assertEquals(5999, trimmed.length)
        val unchanged = AiJson.trimText(PageText("u", "t", "short").text)
        assertEquals("short", unchanged)
    }
}
