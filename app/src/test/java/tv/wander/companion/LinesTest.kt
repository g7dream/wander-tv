package tv.wander.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.wander.model.DriftReason
import tv.wander.model.Mood
import tv.wander.model.RescueOption

class LinesTest {
    private fun assertFinished(line: String) {
        assertTrue("blank line", line.isNotBlank())
        assertFalse("unfilled placeholder in: $line", line.contains('{') || line.contains('}'))
        assertTrue("too long for a bubble: $line", line.length <= 110)
        assertTrue("starts lowercase: $line", !line[0].isLowerCase())
    }

    private fun every(times: Int = 40, f: () -> String): Set<String> {
        val seen = LinkedHashSet<String>()
        var previous: String? = null
        repeat(times) {
            val l = f()
            assertFinished(l)
            assertNotEquals("the same line twice in a row", previous, l)
            previous = l
            seen += l
        }
        return seen
    }

    @Test
    fun greetingsFitEveryHour() {
        for (h in -1..25) assertFinished(Lines.greeting(h))
        val late = every { Lines.greeting(23) }
        val morning = every { Lines.greeting(8) }
        assertTrue(late.size >= 3)
        assertTrue("late greetings differ from morning ones", late.intersect(morning).isEmpty())
    }

    @Test
    fun moodLinesHaveVariety() {
        for (m in Mood.values()) assertTrue(every { Lines.forMood(m) }.size >= 3)
    }

    @Test
    fun playerLinesUseTheName() {
        for (l in every { Lines.playerJoined("Maya") }) assertTrue(l, l.contains("Maya"))
        for (l in every { Lines.playerLeft("Leo") }) assertTrue(l, l.contains("Leo"))
        assertTrue(Lines.playerJoined("   ").contains("friend"))
        assertFalse(Lines.playerJoined("Bartholomew-the-Magnificent").contains("Magnificent"))
    }

    @Test
    fun cardAndQuestLines() {
        for (l in every { Lines.cardsReady(4) }) assertTrue(l, l.contains("4"))
        for (l in every { Lines.cardsReady(3, fromAi = false) }) assertTrue(l, l.contains("3"))
        every { Lines.cardsMaking() }
        every { Lines.cardsFailed() }
        for (l in every { Lines.questStart("Speed Safari") }) assertTrue(l, l.contains("Speed Safari"))
        for (l in every { Lines.questPass("Maya", "Cheetah", 100) }) assertTrue(l, l.contains("Maya") && l.contains("Cheetah"))
        for (l in every { Lines.questDuplicate("Cheetah", "Leo") }) assertTrue(l, l.contains("Leo") && l.contains("Cheetah"))
        for (l in every { Lines.questDuplicate("Cheetah") }) assertTrue(l, l.contains("Cheetah"))
        every { Lines.questFail() }
        assertEquals("Saturn has 146 moons, but it's a planet!", Lines.questFail("Saturn has 146 moons, but it's a planet!"))
        for (l in every { Lines.questComplete("Maya") }) assertTrue(l, l.contains("Maya"))
        every { Lines.questComplete() }
        every { Lines.questChecking() }
        for (l in every { Lines.questChecking("Leo") }) assertTrue(l, l.contains("Leo"))
    }

    @Test
    fun rescueHeadlinesHaveThreePlusVariantsPerReason() {
        for (r in DriftReason.values()) {
            val seen = HashSet<String>()
            repeat(60) {
                val l = Lines.rescueHeadline(r, 7, 240, 23)
                assertTrue("missing placeholder fill: $l", !l.contains('{'))
                assertTrue(l.isNotBlank() && !l[0].isLowerCase())
                seen += l
            }
            assertTrue("$r has only ${seen.size} variants", seen.size >= 3)
        }
        repeat(20) { assertTrue(Lines.rescueHeadline(DriftReason.RAPID_HOPPING, 7, 240).contains("7")) }
        repeat(20) {
            val l = Lines.rescueHeadline(DriftReason.ENDLESS_SCROLL, 0, 45)
            assertTrue(l, l.contains("45 seconds"))
        }
        // The SPEC's example line is in the bank.
        val hopping = HashSet<String>()
        repeat(80) { hopping += Lines.rescueHeadline(DriftReason.RAPID_HOPPING, 7, 240) }
        assertTrue(hopping.contains("We've hopped 7 pages in 4 minutes. I know that dizzy feeling."))
    }

    @Test
    fun lateNightHeadlinesNameTheHour() {
        val seen = HashSet<String>()
        repeat(80) { seen += Lines.rescueHeadline(DriftReason.LATE_NIGHT, 12, 2700, 0) }
        assertTrue(seen.any { it.contains("midnight") })
        assertTrue(seen.any { it.contains("45 minutes") })
    }

    @Test
    fun fallbackOptionsAreUsable() {
        val questIds = setOf("speed-safari", "moon-collectors", "deep-dive", "dino-time", "tiny-titans")
        for (r in DriftReason.values()) {
            repeat(10) {
                val opts = Lines.fallbackOptions(r)
                assertEquals(2, opts.size)
                assertNotEquals(opts[0].kind, opts[1].kind)
                for (o in opts) {
                    assertTrue(o.label.isNotBlank() && o.detail.isNotBlank())
                    assertTrue(o.label.length <= 32)
                    when (o.kind) {
                        RescueOption.Kind.QUEST -> assertTrue(o.questId in questIds)
                        RescueOption.Kind.DETOUR, RescueOption.Kind.WIND_DOWN -> assertTrue(o.url!!.startsWith("https://"))
                    }
                }
            }
        }
        assertEquals(RescueOption.Kind.WIND_DOWN, Lines.fallbackOptions(DriftReason.LATE_NIGHT)[0].kind)
        assertEquals(RescueOption.Kind.QUEST, Lines.fallbackOptions(DriftReason.RAPID_HOPPING)[0].kind)
    }

    @Test
    fun everydayLines() {
        assertTrue(every(60) { Lines.idleTip() }.size >= 6)
        assertTrue(every { Lines.idleTip() }.any { it.contains("menu button") })
        every { Lines.thinking() }
        every { Lines.offline() }
        every { Lines.online() }
        every { Lines.windDown() }
        every { Lines.scanHint() }
        every { Lines.couchFull() }
        every { Lines.cardsMaking() }
        every { Lines.readingAloud() }
        every { Lines.questInventing() }
        every { Lines.rescueDismissed() }
        for (k in RescueOption.Kind.values()) every { Lines.rescueAccepted(k) }
        every { Lines.panelIntro(0) }
        for (l in every { Lines.panelIntro(1) }) assertTrue(l, l.contains("1 page") && !l.contains("1 pages"))
        for (l in every { Lines.panelIntro(9) }) assertTrue(l, l.contains("9 pages"))
    }

    @Test
    fun durations() {
        assertEquals("45 seconds", Lines.spokenDuration(45))
        assertEquals("1 second", Lines.spokenDuration(0))
        assertEquals("a minute", Lines.spokenDuration(70))
        assertEquals("4 minutes", Lines.spokenDuration(240))
        assertEquals("over an hour", Lines.spokenDuration(3700))
        assertEquals("over 2 hours", Lines.spokenDuration(7300))
        assertEquals("45 s", Lines.shortDuration(45))
        assertEquals("4 min", Lines.shortDuration(240))
        assertEquals("1 h 20 min", Lines.shortDuration(4800))
        assertEquals("2 h", Lines.shortDuration(7200))
        assertEquals("11 pm", Lines.clockHour(23))
        assertEquals("midnight", Lines.clockHour(0))
        assertEquals("1 am", Lines.clockHour(1))
        assertEquals("noon", Lines.clockHour(12))
        assertNotNull(Lines.clockHour(-1))
    }
}
