package tv.wander.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestCatalogTest {
    @Test
    fun hasTheFiveSpecQuestsInOrder() {
        assertEquals(
            listOf("speed-safari", "moon-collectors", "deep-dive", "dino-time", "tiny-titans"),
            QuestCatalog.all.map { it.id },
        )
        val expected = mapOf(
            "speed-safari" to Triple("Speed Safari", "🐆", 3),
            "moon-collectors" to Triple("Moon Collectors", "🪐", 2),
            "deep-dive" to Triple("Deep Dive", "🐙", 3),
            "dino-time" to Triple("Time Travelers", "🦕", 2),
            "tiny-titans" to Triple("Tiny Titans", "🐜", 3),
        )
        for (q in QuestCatalog.all) {
            val (title, emoji, goal) = expected.getValue(q.id)
            assertEquals(title, q.title)
            assertEquals(emoji, q.emoji)
            assertEquals(goal, q.goal)
            assertFalse(q.generated)
        }
        assertEquals(
            "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).",
            QuestCatalog.byId("speed-safari")!!.prompt,
        )
    }

    @Test
    fun everyQuestHasARealRubricAndEnoughOfflineAnswers() {
        for (q in QuestCatalog.all) {
            assertTrue(q.id, q.rubric.length > 200)
            assertTrue(q.id, q.rubric.contains("Counts:") && q.rubric.contains("Does not count:"))
            assertTrue(q.id, q.offline.size > q.goal)
            assertTrue(q.id, q.offline.size <= 12)
            val keys = q.offline.map { QuestEngine.normalize(it.item) }
            assertEquals("duplicate offline items in ${q.id}", keys.size, keys.toSet().size)
            for (a in q.offline) {
                assertTrue(a.item, a.keywords.isNotEmpty())
                assertTrue(a.item, a.keywords.all { it == it.lowercase() && it.isNotBlank() })
                assertTrue("fact for ${a.item} needs a number", a.fact.any { it.isDigit() })
                assertTrue("fact for ${a.item} is too long", a.fact.length <= 110)
                assertTrue(a.item.length <= 32)
            }
        }
    }

    @Test
    fun moonFactsMatchTheThreshold() {
        val moons = QuestCatalog.byId("moon-collectors")!!
        assertEquals(setOf("Saturn", "Jupiter", "Uranus"), moons.offline.map { it.item }.toSet())
        assertTrue(moons.rubric.contains("Neptune (16 moons)"))
    }

    @Test
    fun byIdFindsAndMisses() {
        assertNotNull(QuestCatalog.byId("deep-dive"))
        assertNull(QuestCatalog.byId("nope"))
    }

    @Test
    fun offlineMatchUsesWholeWords() {
        val tiny = QuestCatalog.byId("tiny-titans")!!
        assertNull(QuestCatalog.matchOffline(tiny, "Elephants and giants can carry plants."))
        assertEquals("Leafcutter ant", QuestCatalog.matchOffline(tiny, "Leaf-cutter ants carry 8 times their weight")?.item)
        val moons = QuestCatalog.byId("moon-collectors")!!
        assertEquals("Saturn", QuestCatalog.matchOffline(moons, "Saturn’s 274 moons")?.item)
        assertEquals("Jupiter", QuestCatalog.matchOffline(moons, "Saturn and Jupiter", alreadyFound = listOf("saturn"))?.item)
        assertNull(QuestCatalog.matchOffline(moons, ""))
        val dinos = QuestCatalog.byId("dino-time")!!
        assertEquals("Tyrannosaurus rex", QuestCatalog.matchOffline(dinos, "The mighty T. rex lived 66 million years ago")?.item)
    }
}
