package tv.wander.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal

class DriftDetectorTest {
    private class MemStore : DriftDetector.Store {
        val map = HashMap<String, Float>()
        override fun getFloat(key: String, def: Float): Float = map[key] ?: def
        override fun putFloat(key: String, value: Float) {
            map[key] = value
        }
    }

    private var now = 1_000_000_000L
    private var hour = 14
    private lateinit var store: MemStore
    private lateinit var d: DriftDetector

    private fun detector(s: MemStore = store) = DriftDetector(s, clock = { now }, hourOf = { hour })

    @Before
    fun setUp() {
        store = MemStore()
        d = detector()
    }

    private fun advance(sec: Double) {
        now += (sec * 1000).toLong()
    }

    /** Loads [n] pages [gapSec] apart, alternating hosts so the site-loop rule stays quiet. */
    private fun hop(n: Int, gapSec: Double, hosts: List<String> = (0 until n).map { "site$it.org" }) {
        for (i in 0 until n) {
            if (i > 0) advance(gapSec)
            d.onPageLoad("https://${hosts[i % hosts.size]}/page$i")
        }
    }

    private fun scrollFor(sec: Int, everySec: Int = 3) {
        d.onScroll()
        var t = 0
        while (t + everySec <= sec) {
            advance(everySec.toDouble())
            t += everySec
            d.onScroll()
        }
        if (t < sec) {
            advance((sec - t).toDouble())
            d.onScroll()
        }
    }

    // ------------------------------------------------------------------ rapid hopping

    @Test
    fun hoppingFiresAtSixLoadsNotFive() {
        hop(5, 10.0)
        advance(2.0)
        assertNull(d.check())
        advance(8.0)
        d.onPageLoad("https://site5.org/x")
        advance(2.0)
        val s = d.check()
        assertNotNull(s)
        assertEquals(DriftReason.RAPID_HOPPING, s!!.reason)
        assertTrue(s.headline, s.headline.contains("6"))
        assertTrue(s.detail, s.detail.startsWith("6 pages · "))
        assertTrue(s.detail, s.detail.endsWith("~10 s each"))
    }

    @Test
    fun hoppingDetailMatchesSpecFormat() {
        // 7 pages, ~12 s apart, measured a couple of minutes in.
        hop(7, 12.0)
        advance(3.0)
        val s = d.check()!!
        assertEquals("7 pages · ${Lines.shortDuration(75)} · ~12 s each", s.detail)
        assertEquals("7 pages · 1 min · ~12 s each", s.detail)
    }

    @Test
    fun hoppingNeedsEachViewUnderTwentySeconds() {
        // Same six loads but one page was read for exactly 20 s: the run breaks there.
        hop(3, 10.0)
        advance(20.0)
        hop(3, 10.0, listOf("x.org", "y.org", "z.org"))
        advance(1.0)
        assertNull(d.check())
    }

    @Test
    fun hoppingIgnoresAFamilyThatSettledDown() {
        hop(6, 10.0)
        advance(20.0) // the sixth page has now been open for 20 s
        assertNull(d.check())
    }

    @Test
    fun hoppingMustFitInTheWindow() {
        // Four loads 19.9 s apart are each "quick", but together they span more than demo's 60 s.
        d.demoMode = true
        hop(4, 19.9)
        advance(1.0)
        assertNull("4 loads spanning ~61 s must not fit a 60 s window", d.check())
    }

    @Test
    fun hoppingDemoThresholdIsFourInSixtySeconds() {
        d.demoMode = true
        hop(3, 5.0)
        advance(1.0)
        assertNull(d.check())
        advance(4.0)
        d.onPageLoad("https://site9.org/")
        advance(1.0)
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)
    }

    @Test
    fun redirectsCountAsOnePage() {
        // Each navigation bounces through a redirect 0.4 s later: still five pages, not ten.
        for (i in 0 until 5) {
            if (i > 0) advance(10.0)
            d.onPageLoad("https://short$i.link/a")
            advance(0.4)
            d.onPageLoad("https://site$i.org/landing")
        }
        advance(1.0)
        assertNull(d.check())
        assertEquals(5, d.stats().pagesTotal)
    }

    @Test
    fun nonWebPagesAreIgnored() {
        d.onPageLoad("about:blank")
        d.onPageLoad("data:text/html,hello")
        d.onPageLoad("javascript:void(0)")
        d.onPageLoad("file:///android_asset/home.html")
        d.onPageLoad("")
        assertEquals(0, d.stats().pagesTotal)
    }

    // ------------------------------------------------------------------ endless scroll

    @Test
    fun scrollFiresAtThreeMinutesNotBelow() {
        d.onPageLoad("https://feed.example/")
        advance(30.0)
        scrollFor(177)
        assertNull(d.check())
        scrollFor(3) // continues the streak: the gap is 3 s
        val s = d.check()
        assertEquals(DriftReason.ENDLESS_SCROLL, s?.reason)
        assertEquals("3 min of scrolling · no clicks", s!!.detail)
    }

    @Test
    fun scrollStreakResetsOnClickPauseAndPageLoad() {
        d.onPageLoad("https://feed.example/")
        scrollFor(120)
        d.onClick()
        scrollFor(120)
        assertNull("a click restarts the streak", d.check())

        advance(16.0) // a reading pause longer than 15 s
        scrollFor(120)
        assertNull("a long pause restarts the streak", d.check())

        d.onPageLoad("https://feed.example/next")
        scrollFor(100)
        assertNull("a page load restarts the streak", d.check())
        scrollFor(80)
        assertEquals(DriftReason.ENDLESS_SCROLL, d.check()?.reason)
    }

    @Test
    fun scrollDemoThresholdIsFortyFiveSeconds() {
        d.demoMode = true
        d.onPageLoad("https://feed.example/")
        advance(25.0)
        scrollFor(44)
        assertNull(d.check())
        scrollFor(1)
        assertEquals(DriftReason.ENDLESS_SCROLL, d.check()?.reason)
    }

    @Test
    fun scrollNeedsScrollingToStillBeHappening() {
        d.onPageLoad("https://feed.example/")
        advance(25.0)
        scrollFor(200)
        advance(16.0)
        assertNull("they stopped scrolling a while ago", d.check())
    }

    // ------------------------------------------------------------------ site loop

    @Test
    fun siteLoopFiresAtFiveEntriesNotFour() {
        // home.site <-> elsewhere, 25 s apart so hopping stays quiet.
        val pattern = listOf("loop.site", "a.org", "loop.site", "b.org", "loop.site", "c.org", "loop.site", "d.org")
        hop(pattern.size, 25.0, pattern)
        advance(25.0)
        assertNull(d.check())
        d.onPageLoad("https://www.loop.site/again")
        advance(1.0)
        val s = d.check()
        assertEquals(DriftReason.SITE_LOOP, s?.reason)
        assertTrue(s!!.headline, s.headline.contains("5"))
        assertTrue(s.detail, s.detail.startsWith("5 visits · same site · "))
    }

    @Test
    fun linksInsideOneSiteAreNotALoop() {
        hop(12, 25.0, listOf("en.wikipedia.org"))
        advance(1.0)
        assertNull(d.check())
        assertEquals(1, d.stats().sameSiteRepeats)
    }

    @Test
    fun siteLoopIgnoresVisitsOutsideTheWindow() {
        val pattern = listOf("loop.site", "a.org")
        hop(8, 130.0, pattern) // loop.site entered 4 times over ~15 min
        advance(130.0)
        d.onPageLoad("https://loop.site/") // the 5th, but the first one is now > 15 min old
        advance(1.0)
        assertNull(d.check())
    }

    @Test
    fun siteLoopDemoThresholdIsThreeInThreeMinutes() {
        d.demoMode = true
        val pattern = listOf("loop.site", "a.org", "loop.site", "b.org")
        hop(4, 25.0, pattern)
        advance(25.0)
        assertNull(d.check())
        d.onPageLoad("https://loop.site/")
        advance(1.0)
        assertEquals(DriftReason.SITE_LOOP, d.check()?.reason)
    }

    // ------------------------------------------------------------------ late night

    @Test
    fun lateNightFiresAfterThirtyMinutesPastEleven() {
        hour = 23
        d.onPageLoad("https://calm.example/")
        advance(29 * 60.0 + 59)
        assertNull(d.check())
        advance(1.0)
        val s = d.check()
        assertEquals(DriftReason.LATE_NIGHT, s?.reason)
        assertTrue(s!!.detail, s.detail.startsWith("Past 11 pm · 30 min tonight · 1 page"))
    }

    @Test
    fun lateNightCoversSmallHoursButNotEvening() {
        hour = 22
        d.onPageLoad("https://calm.example/")
        advance(40 * 60.0)
        assertNull(d.check())
        hour = 3
        assertEquals(DriftReason.LATE_NIGHT, d.check()?.reason)
        hour = 5
        val fresh = detector(MemStore())
        fresh.onPageLoad("https://calm.example/")
        advance(40 * 60.0)
        assertNull(fresh.check())
    }

    @Test
    fun lateNightIsIgnoredInDemoMode() {
        hour = 0
        d.demoMode = true
        d.onPageLoad("https://calm.example/")
        advance(40 * 60.0)
        assertNull(d.check())
    }

    @Test
    fun lateNightReArmsOnlyAfterAnotherFullThreshold() {
        hour = 23
        d.onPageLoad("https://calm.example/")
        advance(30 * 60.0)
        assertNotNull(d.check())
        advance(11 * 60.0) // past the cooldown
        d.onClick()
        assertNull(d.check())
        advance(19 * 60.0) // 30 min since the last nudge
        d.onClick()
        assertEquals(DriftReason.LATE_NIGHT, d.check()?.reason)
    }

    // ------------------------------------------------------------------ cooldown and switches

    @Test
    fun cooldownIsTenMinutes() {
        hop(6, 5.0)
        assertNotNull(d.check())
        // Fresh evidence right away: quiet until 10 min have passed.
        hop(6, 5.0, (0 until 6).map { "fresh$it.org" })
        assertNull(d.check())
        advance(10 * 60.0)
        hop(6, 5.0, (0 until 6).map { "later$it.org" })
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)
    }

    @Test
    fun demoCooldownIsFortyFiveSeconds() {
        d.demoMode = true
        hop(4, 5.0)
        assertNotNull(d.check())
        advance(30.0)
        hop(4, 3.0, (0 until 4).map { "fresh$it.org" })
        assertNull("30 s after the last signal", d.check())
        advance(6.0) // 45 s since the signal
        d.onPageLoad("https://fresh-again.org/")
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)
    }

    @Test
    fun aRuleNeedsNewEvidenceAfterItFires() {
        d.demoMode = true
        hop(4, 5.0)
        assertNotNull(d.check())
        advance(46.0)
        d.onPageLoad("https://one-more.org/")
        advance(1.0)
        assertNull("one new load is not four new loads", d.check())
    }

    @Test
    fun disabledStaysQuietAndForgetsOldEvidence() {
        d.enabled = false
        hop(8, 5.0)
        assertNull(d.check())
        d.enabled = true
        assertNull("evidence gathered while off does not count", d.check())
        advance(5.0)
        hop(6, 5.0, (0 until 6).map { "new$it.org" })
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)
    }

    @Test
    fun nothingHappensWithoutActivity() {
        assertNull(d.check())
        advance(3600.0)
        assertNull(d.check())
    }

    // ------------------------------------------------------------------ adaptation

    private fun fireHopping(): DriftSignal {
        d.demoMode = true
        advance(60.0)
        var s: DriftSignal? = null
        var i = 0
        while (s == null && i < 40) {
            d.onPageLoad("https://adapt${now}.org/")
            advance(2.0)
            s = d.check()
            i++
        }
        return s ?: error("hopping never fired")
    }

    @Test
    fun dismissalRelaxesTheRuleAndPersists() {
        val first = fireHopping()
        d.dismissed(first)
        assertEquals(1.25f, d.multiplier(DriftReason.RAPID_HOPPING), 1e-4f)
        assertEquals(1.25f, store.map[DriftDetector.key(DriftReason.RAPID_HOPPING)]!!, 1e-4f)
        assertEquals("drift.mult.rapid_hopping", DriftDetector.key(DriftReason.RAPID_HOPPING))
        assertEquals(1f, d.multiplier(DriftReason.ENDLESS_SCROLL), 0f)

        // Demo threshold 4 x 1.25 = 5 loads now.
        advance(60.0)
        hop(4, 3.0, (0 until 4).map { "r$it.org" })
        assertNull(d.check())
        advance(3.0)
        d.onPageLoad("https://r4.org/")
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)

        // A new detector on the same store (the next app launch) keeps the relaxed threshold.
        val next = detector()
        assertEquals(1.25f, next.multiplier(DriftReason.RAPID_HOPPING), 1e-4f)
    }

    @Test
    fun normalThresholdScalesToEightLoadsAfterOneDismissal() {
        store.map[DriftDetector.key(DriftReason.RAPID_HOPPING)] = 1.25f
        d = detector()
        hop(7, 5.0)
        assertNull(d.check())
        advance(5.0)
        d.onPageLoad("https://eighth.org/")
        assertEquals(DriftReason.RAPID_HOPPING, d.check()?.reason)
    }

    @Test
    fun relaxationCapsAtThreeTimes() {
        val s = DriftSignal(DriftReason.SITE_LOOP, "h", "d")
        repeat(12) { d.dismissed(s) }
        assertEquals(3f, d.multiplier(DriftReason.SITE_LOOP), 1e-4f)
        assertEquals(3f, store.map[DriftDetector.key(DriftReason.SITE_LOOP)]!!, 1e-4f)
    }

    @Test
    fun acceptanceNudgesBackTowardOne() {
        val s = DriftSignal(DriftReason.ENDLESS_SCROLL, "h", "d")
        d.dismissed(s)
        d.dismissed(s)
        assertEquals(1.5625f, d.multiplier(DriftReason.ENDLESS_SCROLL), 1e-4f)
        d.accepted(s)
        assertEquals(1.28125f, d.multiplier(DriftReason.ENDLESS_SCROLL), 1e-4f)
        assertEquals(1.28125f, store.map[DriftDetector.key(DriftReason.ENDLESS_SCROLL)]!!, 1e-4f)
        repeat(10) { d.accepted(s) }
        assertEquals(1f, d.multiplier(DriftReason.ENDLESS_SCROLL), 0f)
        d.accepted(s)
        assertEquals("never tighter than the defaults", 1f, d.multiplier(DriftReason.ENDLESS_SCROLL), 0f)
    }

    @Test
    fun corruptStoredValuesFallBackSafely() {
        store.map[DriftDetector.key(DriftReason.RAPID_HOPPING)] = Float.NaN
        store.map[DriftDetector.key(DriftReason.SITE_LOOP)] = 99f
        store.map[DriftDetector.key(DriftReason.LATE_NIGHT)] = 0.1f
        d = detector()
        assertEquals(1f, d.multiplier(DriftReason.RAPID_HOPPING), 0f)
        assertEquals(3f, d.multiplier(DriftReason.SITE_LOOP), 0f)
        assertEquals(1f, d.multiplier(DriftReason.LATE_NIGHT), 0f)
    }

    @Test
    fun aThrowingStoreNeverCrashes() {
        val broken = object : DriftDetector.Store {
            override fun getFloat(key: String, def: Float): Float = throw IllegalStateException("disk")
            override fun putFloat(key: String, value: Float) = throw IllegalStateException("disk")
        }
        val b = DriftDetector(broken, clock = { now }, hourOf = { hour })
        b.dismissed(DriftSignal(DriftReason.RAPID_HOPPING, "h", "d"))
        assertEquals(1.25f, b.multiplier(DriftReason.RAPID_HOPPING), 1e-4f)
    }

    @Test
    fun aThrowingHourFunctionNeverCrashes() {
        val b = DriftDetector(store, clock = { now }, hourOf = { throw IllegalStateException("tz") })
        b.onPageLoad("https://a.org/")
        advance(3600.0)
        assertNull(b.check())
        assertEquals(12, b.stats().localHour)
    }

    // ------------------------------------------------------------------ stats

    @Test
    fun statsDescribeTheSession() {
        hour = 21
        d.onPageLoad("https://loop.site/")
        advance(30.0)
        d.onPageLoad("https://a.org/")
        advance(30.0)
        d.onPageLoad("https://loop.site/2")
        advance(60.0)
        d.onScroll()
        advance(10.0)
        d.onScroll()
        val st = d.stats()
        assertEquals(2, st.sessionMinutes)
        assertEquals(3, st.pagesLast5Min)
        assertEquals(3, st.pagesTotal)
        assertEquals((30 + 30 + 70) / 3, st.avgDwellSec)
        assertEquals(10, st.scrollSecondsNoClick)
        assertEquals(2, st.sameSiteRepeats)
        assertEquals(21, st.localHour)
    }

    @Test
    fun statsBeforeAnythingHappens() {
        val st = d.stats()
        assertEquals(0, st.sessionMinutes)
        assertEquals(0, st.pagesTotal)
        assertEquals(0, st.avgDwellSec)
        assertEquals(14, st.localHour)
    }

    @Test
    fun aLongIdleGapStartsANewSession() {
        hop(3, 30.0)
        advance(46 * 60.0)
        d.onPageLoad("https://morning.org/")
        assertEquals(1, d.stats().pagesTotal)
        assertEquals(0, d.stats().sessionMinutes)
    }

    // ------------------------------------------------------------------ hosts

    @Test
    fun hostParsing() {
        assertEquals("example.com", DriftDetector.hostOf("https://www.Example.com/a?b#c"))
        assertEquals("wikipedia.org", DriftDetector.hostOf("http://m.wikipedia.org"))
        assertEquals("en.wikipedia.org", DriftDetector.hostOf("https://en.wikipedia.org/wiki/Moth"))
        assertEquals("example.com", DriftDetector.hostOf("https://user:pw@example.com:8443/x"))
        assertEquals("example.com", DriftDetector.hostOf("example.com/path"))
        assertEquals("localhost", DriftDetector.hostOf("localhost:8080/x"))
        assertEquals("[::1]", DriftDetector.hostOf("http://[::1]:8080/"))
        assertEquals("m.com", DriftDetector.hostOf("https://m.com/"))
        assertNull(DriftDetector.hostOf("about:blank"))
        assertNull(DriftDetector.hostOf("mailto:hi@example.com"))
        assertNull(DriftDetector.hostOf("ftp://files.example.com"))
        assertNull(DriftDetector.hostOf("   "))
    }

    @Test
    fun localHourHelperIsInRange() {
        assertTrue(DriftDetector.localHour(System.currentTimeMillis()) in 0..23)
        assertTrue(DriftDetector.localHour(0L) in 0..23)
    }
}
