package tv.wander.quest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestOutcome
import tv.wander.model.Verdict

class QuestEngineTest {
    private val speed = QuestCatalog.byId("speed-safari")!!
    private val dinos = QuestCatalog.byId("dino-time")!!

    private fun players() = listOf(
        Player("p1", "Maya", 1),
        Player("p2", "Sam", 2),
        Player("p3", "Grandpa", 3),
    )

    private fun pass(item: String?, evidence: String? = "It can reach 110 km/h.") =
        Verdict(true, item, "Yes, that counts!", evidence, fromAi = true)

    private fun fail(reason: String) = Verdict(false, null, reason, null, fromAi = true)

    @Test
    fun acceptedItemScoresHundredToSubmitter() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        val outcome = engine.apply("p2", pass("Cheetah"), ps)
        assertTrue(outcome is QuestOutcome.Accepted)
        outcome as QuestOutcome.Accepted
        assertEquals("Cheetah", outcome.found.item)
        assertEquals("p2", outcome.found.playerId)
        assertEquals("It can reach 110 km/h.", outcome.found.evidence)
        assertEquals(QuestEngine.POINTS_PER_ITEM, outcome.points)
        assertEquals(100, ps[1].score)
        assertEquals(0, ps[0].score)
        assertEquals(1, engine.found.size)
        assertFalse(outcome.board.complete)
    }

    @Test
    fun rejectedVerdictKeepsLumosReasonAndScoresNothing() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        val outcome = engine.apply("p1", fail("Pronghorns top out around 88 km/h, so close!"), ps)
        assertTrue(outcome is QuestOutcome.Rejected)
        assertEquals("Pronghorns top out around 88 km/h, so close!", (outcome as QuestOutcome.Rejected).reason)
        assertTrue(ps.all { it.score == 0 })
        assertTrue(engine.found.isEmpty())
    }

    @Test
    fun blankRejectReasonGetsAFriendlyDefault() {
        val engine = QuestEngine().apply { start(speed) }
        val outcome = engine.apply("p1", fail("  "), players()) as QuestOutcome.Rejected
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun passWithoutItemIsRejectedNotCounted() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        assertTrue(engine.apply("p1", pass(null), ps) is QuestOutcome.Rejected)
        assertTrue(engine.apply("p1", pass("  ...  "), ps) is QuestOutcome.Rejected)
        assertTrue(engine.found.isEmpty())
        assertEquals(0, ps[0].score)
    }

    @Test
    fun duplicatesIgnoreCaseArticlesPluralsAndPunctuation() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        engine.apply("p1", pass("Cheetah"), ps)
        for (variant in listOf("cheetah", "The Cheetahs!", "a cheetah.", "CHEETAH (Acinonyx jubatus)", "  the   cheetah  ")) {
            val outcome = engine.apply("p2", pass(variant), ps)
            assertTrue("$variant should be a duplicate", outcome is QuestOutcome.Duplicate)
            assertEquals("Cheetah", (outcome as QuestOutcome.Duplicate).item)
        }
        assertEquals(100, ps[0].score)
        assertEquals(0, ps[1].score)
        assertEquals(1, engine.found.size)
    }

    @Test
    fun aliasesFromTheCatalogAreOneFind() {
        val engine = QuestEngine().apply { start(dinos) }
        val ps = players()
        val first = engine.apply("p1", pass("T. rex"), ps) as QuestOutcome.Accepted
        assertEquals("Tyrannosaurus rex", first.found.item)
        assertTrue(engine.apply("p2", pass("Tyrannosaurus Rex"), ps) is QuestOutcome.Duplicate)
        assertTrue(engine.apply("p2", pass("T-Rex"), ps) is QuestOutcome.Duplicate)
        assertTrue(engine.apply("p2", pass("trex"), ps) is QuestOutcome.Duplicate)
    }

    @Test
    fun aliasMappingWorksForSpeedSafariToo() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        val first = engine.apply("p1", pass("Mexican free-tailed bat"), ps) as QuestOutcome.Accepted
        assertEquals("Brazilian free-tailed bat", first.found.item)
        assertTrue(engine.apply("p3", pass("Brazilian Free Tailed Bats"), ps) is QuestOutcome.Duplicate)
        assertTrue(engine.apply("p3", pass("peregrine"), ps) is QuestOutcome.Accepted)
        assertEquals("Peregrine falcon", engine.found.last().item)
    }

    @Test
    fun unknownItemsAreKeptButCapitalized() {
        val engine = QuestEngine().apply { start(speed) }
        val outcome = engine.apply("p1", pass("frigatebird"), players()) as QuestOutcome.Accepted
        assertEquals("Frigatebird", outcome.found.item)
    }

    @Test
    fun completingTheQuestAddsBonusToTheCompleter() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        engine.apply("p1", pass("Cheetah"), ps)
        engine.apply("p2", pass("Golden eagle"), ps)
        assertFalse(engine.isComplete)
        val last = engine.apply("p2", pass("Peregrine falcon"), ps) as QuestOutcome.Accepted
        assertEquals(QuestEngine.POINTS_PER_ITEM + QuestEngine.COMPLETION_BONUS, last.points)
        assertTrue(last.board.complete)
        assertTrue(engine.isComplete)
        assertEquals(100, ps[0].score)
        assertEquals(250, ps[1].score)
    }

    @Test
    fun submissionsAfterCompletionAreRejectedWithoutPoints() {
        val engine = QuestEngine().apply { start(QuestCatalog.byId("moon-collectors")!!) }
        val ps = players()
        engine.apply("p1", pass("Saturn"), ps)
        engine.apply("p1", pass("Jupiter"), ps)
        val extra = engine.apply("p2", pass("Uranus"), ps)
        assertTrue(extra is QuestOutcome.Rejected)
        assertEquals(0, ps[1].score)
        assertEquals(2, engine.found.size)
    }

    @Test
    fun boardSortsPlayersByScoreAndReflectsFinds() {
        val engine = QuestEngine()
        assertNull(engine.board(players()))
        engine.start(speed)
        val ps = players()
        engine.apply("p3", pass("Cheetah"), ps)
        engine.apply("p2", pass("Sailfish"), ps)
        engine.apply("p3", pass("Golden eagle"), ps)
        val board = engine.board(ps)
        assertNotNull(board)
        board!!
        assertEquals(listOf("p3", "p2", "p1"), board.players.map { it.id })
        assertEquals(listOf("Cheetah", "Sailfish", "Golden eagle"), board.found.map { it.item })
        assertTrue(board.complete)
        assertEquals(speed, board.quest)
    }

    @Test
    fun tiesKeepJoinOrder() {
        val ps = players()
        ps[2].score = 100
        ps[0].score = 100
        assertEquals(listOf("p1", "p3", "p2"), QuestEngine.ranked(ps).map { it.id })
    }

    @Test
    fun unknownPlayerStillRecordsTheFind() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        val outcome = engine.apply("ghost", pass("Cheetah"), ps)
        assertTrue(outcome is QuestOutcome.Accepted)
        assertTrue(ps.all { it.score == 0 })
        assertEquals("ghost", engine.whoFound("cheetahs")?.playerId)
    }

    @Test
    fun applyWithoutAQuestNeverCrashes() {
        val engine = QuestEngine()
        val outcome = engine.apply("p1", pass("Cheetah"), players())
        assertTrue(outcome is QuestOutcome.Rejected)
        assertTrue(engine.found.isEmpty())
    }

    @Test
    fun startResetsAndEndClears() {
        val engine = QuestEngine().apply { start(speed) }
        engine.apply("p1", pass("Cheetah"), players())
        engine.start(dinos)
        assertTrue(engine.found.isEmpty())
        assertEquals(dinos, engine.active)
        engine.end()
        assertNull(engine.active)
        assertNull(engine.board(players()))
        assertNull(engine.whoFound("Cheetah"))
    }

    @Test
    fun emptyEvidenceIsStoredAsNull() {
        val engine = QuestEngine().apply { start(speed) }
        val outcome = engine.apply("p1", pass("Cheetah", evidence = "   "), players()) as QuestOutcome.Accepted
        assertNull(outcome.found.evidence)
    }

    @Test
    fun generatedQuestWithZeroGoalCompletesAfterOneFind() {
        val q = Quest("lumo-1", "Lumo's pick", "Find one volcano.", 0, "🌋", "Any real volcano.", generated = true)
        val engine = QuestEngine().apply { start(q) }
        val outcome = engine.apply("p1", pass("Mount Etna"), players()) as QuestOutcome.Accepted
        assertTrue(outcome.board.complete)
        assertEquals(150, outcome.points)
    }

    @Test
    fun normalizeHandlesTheUsualSuspects() {
        assertEquals(QuestEngine.normalize("Peregrine Falcon"), QuestEngine.normalize("the peregrine falcons"))
        assertEquals(QuestEngine.normalize("Cuvier’s beaked whale"), QuestEngine.normalize("Cuviers Beaked Whale"))
        assertEquals(QuestEngine.normalize("octopus"), QuestEngine.normalize("Octopuses"))
        assertEquals(QuestEngine.normalize("octopus"), QuestEngine.normalize("octopi"))
        assertEquals(QuestEngine.normalize("Butterfly"), QuestEngine.normalize("butterflies"))
        assertEquals(QuestEngine.normalize("fox"), QuestEngine.normalize("Foxes"))
        assertEquals(QuestEngine.normalize("Pokémon"), QuestEngine.normalize("pokemon"))
        assertEquals("pachycephalosaurus", QuestEngine.normalize("Pachycephalosaurus"))
        assertEquals("", QuestEngine.normalize("  !!  "))
        assertNotEquals(QuestEngine.normalize("Golden eagle"), QuestEngine.normalize("Bald eagle"))
    }

    @Test
    fun linesNameWhoFoundItFirst() {
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        val ok = engine.apply("p1", pass("Cheetah"), ps)
        assertTrue(QuestEngine.lineFor(ok, ps).contains("Maya"))
        val dup = engine.apply("p2", pass("cheetahs"), ps)
        val line = QuestEngine.lineFor(dup, ps)
        assertTrue(line, line.contains("Maya found it first"))
        val no = engine.apply("p2", fail("That's a car, not an animal!"), ps)
        assertEquals("That's a car, not an animal!", QuestEngine.lineFor(no, ps))
    }
}
