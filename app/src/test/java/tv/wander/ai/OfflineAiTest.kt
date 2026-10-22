package tv.wander.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.RescueOption
import tv.wander.model.SessionStats

class OfflineAiTest {
    private val specQuestIds = setOf("speed-safari", "moon-collectors", "deep-dive", "dino-time", "tiny-titans")

    // ------------------------------------------------------------------ cards

    @Test
    fun cheetahPageBecomesAGoodDeck() {
        val deck = OfflineAi.cards(SamplePages.CHEETAH)
        assertFalse(deck.fromAi)
        assertEquals(SamplePages.CHEETAH.url, deck.sourceUrl)
        assertEquals(SamplePages.CHEETAH.title, deck.sourceTitle)
        assertTrue("3-5 cards, got ${deck.cards.size}", deck.cards.size in 3..5)
        assertEquals("Cheetah", deck.cards.first().title)
        assertTrue(deck.cards.first().line.startsWith("The cheetah is a large wild cat"))
        for (card in deck.cards) {
            assertTrue("title too long: ${card.title}", card.title.length <= 32)
            assertTrue("line too long: ${card.line}", card.line.length <= 110)
            assertTrue(card.emoji.isNotBlank())
            assertTrue(card.icon.isNotBlank())
            assertFalse("citation left in: ${card.line}", card.line.contains("["))
            assertFalse("pronunciation left in: ${card.line}", card.line.contains("ˈ"))
            assertFalse("boilerplate card: ${card.line}", card.line.contains("ISBN"))
        }
        assertEquals(deck.cards.size, deck.cards.map { it.title.lowercase() }.toSet().size)
        val titles = deck.cards.map { it.title }
        assertTrue("expected a heading or themed title in $titles",
            titles.any { it in setOf("Speed", "Food", "Family life", "Built for speed", "Record breaker", "Size check") })
        // The 110 km/h fact is the best sentence on the page; it must make the deck.
        assertTrue(deck.cards.any { it.line.contains("110 km/h") })
        // Not every card is the page's own emoji.
        assertTrue(deck.cards.map { it.emoji }.toSet().size > 1)
    }

    @Test
    fun tooLittleTextGivesOneFriendlyCard() {
        val deck = OfflineAi.cards(SamplePages.MENU_ONLY)
        assertEquals(1, deck.cards.size)
        assertEquals("Not much to read here", deck.cards[0].title)
        assertFalse(deck.fromAi)
        val empty = OfflineAi.cards(PageText("u", "", ""))
        assertEquals("Not much to read here", empty.cards[0].title)
    }

    @Test
    fun boilerplateIsIgnored() {
        val text = List(12) { "Subscribe to our newsletter and accept all cookies to keep reading this page now." }.joinToString("\n")
        val deck = OfflineAi.cards(PageText("https://x", "News", text))
        assertEquals("Not much to read here", deck.cards[0].title)
    }

    @Test
    fun longSentencesAreShortenedWithAnEllipsis() {
        val long = "The giant Pacific octopus is the largest octopus in the world, and it can grow to about 5 meters " +
            "across from arm tip to arm tip, which is wider than a car, while weighing about 50 kilograms."
        val text = "$long\nOctopuses have three hearts and blue blood that carries oxygen around their bodies.\n" +
            "Each of the eight arms has hundreds of suckers that can taste what they touch."
        val deck = OfflineAi.cards(PageText("https://x", "Giant Pacific octopus", text))
        val first = deck.cards.first()
        assertTrue(first.line.length <= 110)
        assertTrue(first.line.endsWith(TextKit.ELLIPSIS))
        assertEquals("🐙", first.emoji)
    }

    @Test
    fun abbreviationsAndDecimalsDoNotSplitSentences() {
        val parts = TextKit.splitLine("Dr. Smith studied cheetahs in the U.S. for 2.5 years. Then she moved to Kenya.")
        assertEquals(2, parts.size)
        assertTrue(parts[0].endsWith("2.5 years."))
    }

    @Test
    fun clampFitsAndPrefersWordBoundaries() {
        val s = "Cheetahs can sprint faster than a car on the highway for a short while"
        val c = TextKit.clamp(s, 30)
        assertTrue(c.length <= 30)
        assertTrue(c.endsWith(TextKit.ELLIPSIS))
        assertFalse(c.dropLast(1).endsWith(" "))
        assertEquals("short", TextKit.clamp("short", 30))
        assertEquals(10, TextKit.clamp("x".repeat(50), 10).length)
    }

    @Test
    fun emojiMapIsRichAndOrdered() {
        assertTrue(EmojiMap.size >= 60)
        assertEquals("🐙", EmojiMap.find("Octopuses have three hearts")?.emoji)
        assertEquals("🦕", EmojiMap.find("A dinosaur from the Cretaceous")?.emoji)
        assertEquals("🐆", EmojiMap.find("The cheetah sprints")?.emoji) // specific beats generic "speed"
        assertNull(EmojiMap.find("The cards were dealt")) // "car" must not match "cards"
        assertEquals("🐋", EmojiMap.find("A cheetah and a whale", exclude = "🐆")?.emoji)
    }

    // ------------------------------------------------------------------ quest judge

    @Test
    fun answerInTitlePasses() {
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.CHEETAH, emptyList())
        assertTrue(v.pass)
        assertEquals("Cheetah", v.item)
        assertFalse(v.fromAi)
        assertTrue(v.reason.startsWith("Yes! Cheetah counts!"))
        assertTrue(v.reason.length <= 120)
        val evidence = v.evidence
        assertNotNull(evidence)
        assertTrue(evidence!!.length <= 140)
        assertTrue(evidence.lowercase().contains("cheetah"))
    }

    @Test
    fun evidencePrefersTheSentenceWithTheNumber() {
        // The real Simple English Wikipedia opening: the speed is in the follow-up "It ..." sentence.
        val page = PageText(
            "https://simple.wikipedia.org/wiki/Cheetah", "Cheetah",
            "Cheetah Temporal range: Pleistocene–Holocene, 1.9 mya–present Female Southern African cheetah " +
                "KwaZulu Natal, South Africa Duration: 25 seconds. " +
                "A cheetah is a medium large cat which lives in Africa and Asia. It is the fastest land animal and can " +
                "run up to 105 kilometres per hour (65 mph). Most cheetahs live in the savannas of Africa. " +
                "The cheetah stands 70–90 centimetres at the shoulder, and weighs 21–72 kilograms.",
        )
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, page, emptyList())
        assertTrue(v.pass)
        assertTrue(v.evidence!!, v.evidence!!.contains("105"))

        val named = PageText("u", "Cheetah", "The cheetah is a big cat. Cheetahs can reach 112 km/h in short bursts.")
        assertTrue(OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, named, emptyList()).evidence!!.contains("112"))
    }

    @Test
    fun oneMentionInTextIsNotEnoughButTwoAre() {
        val once = PageText("u", "Fast animals", "The peregrine falcon is a bird of prey that lives on every continent but one.")
        assertFalse(OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, once, emptyList()).pass)
        val twice = PageText("u", "Fast animals",
            "The peregrine falcon is a bird of prey. When a peregrine falcon dives, it is the fastest animal.")
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, twice, emptyList())
        assertTrue(v.pass)
        assertEquals("Peregrine falcon", v.item)
    }

    @Test
    fun alreadyFoundItemsAreSkippedWithNormalization() {
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.CHEETAH, listOf("  CHEETAHS "))
        // The page proves an answer that's already on the board: the item comes back so the engine can call it
        // a duplicate and say who found it first.
        assertTrue(v.pass)
        assertEquals("Cheetah", v.item)
        assertTrue(v.reason.contains("already on the board"))
    }

    @Test
    fun aDuplicatePageNamesWhoFoundItFirst() {
        val engine = tv.wander.quest.QuestEngine().apply { start(SamplePages.SPEED_SAFARI) }
        val leo = tv.wander.model.Player("p1", "Leo", 0)
        val ava = tv.wander.model.Player("p2", "Ava", 2)
        val players = listOf(leo, ava)
        val first = engine.apply("p1", OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.CHEETAH, emptyList()), players)
        assertTrue(first is tv.wander.model.QuestOutcome.Accepted)
        val again = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.CHEETAH, engine.found.map { it.item })
        val outcome = engine.apply("p2", again, players)
        assertTrue(outcome is tv.wander.model.QuestOutcome.Duplicate)
        assertEquals(0, ava.score)
        assertTrue(tv.wander.quest.QuestEngine.lineFor(outcome, players).contains("Leo found it first"))
    }

    @Test
    fun aPageWithoutTheQuestsFactQuotesLumosOwnFact() {
        // Simple English Wikipedia's golden eagle article never gives a speed; the first sentence even carries
        // the mobile tagline glued on front.
        val page = PageText(
            "https://simple.wikipedia.org/wiki/Golden_eagle", "Golden eagle",
            "species of bird of prey The golden eagle (Aquila chrysaetos) is one of the best-known birds of prey in " +
                "the northern hemisphere. It is the most widely distributed species of eagle. Golden eagles use their " +
                "agility and speed combined with powerful feet and massive, sharp talons to snatch up prey.",
        )
        val quest = SamplePages.SPEED_SAFARI.copy(
            offline = SamplePages.SPEED_SAFARI.offline +
                tv.wander.model.OfflineAnswer("Golden eagle", listOf("golden eagle", "golden eagles"), "Golden eagles can dive at about 240 km/h."),
        )
        val v = OfflineAi.checkQuest(quest, page, emptyList())
        assertTrue(v.pass)
        assertEquals("Golden eagle", v.item)
        assertEquals("Golden eagles can dive at about 240 km/h.", v.evidence)
    }

    @Test
    fun aDifferentNewItemOnTheSamePageWins() {
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.SAILFISH, listOf("Cheetah"))
        assertTrue(v.pass)
        assertEquals("Sailfish", v.item)
        assertTrue(v.evidence!!.lowercase().contains("sailfish"))
    }

    @Test
    fun specificNamesBeatGenericOnes() {
        val v = OfflineAi.checkQuest(SamplePages.BIG_CATS, SamplePages.SNOW_LEOPARD, emptyList())
        assertTrue(v.pass)
        assertEquals("Snow leopard", v.item)
    }

    @Test
    fun noMatchIsAKindNo() {
        val v = OfflineAi.checkQuest(SamplePages.SPEED_SAFARI, SamplePages.SNOW_LEOPARD, emptyList())
        assertFalse(v.pass)
        assertNull(v.item)
        assertNull(v.evidence)
        assertTrue(v.reason.isNotBlank() && v.reason.length <= 120)
    }

    @Test
    fun aiGeneratedQuestWithoutAnswersIsHonest() {
        val q = Quest("lumo-1234abcd", "Ring Hunters", "Find two planets with rings.", 2, "🔭", "rings", emptyList(), generated = true)
        val v = OfflineAi.checkQuest(q, SamplePages.CHEETAH, emptyList())
        assertFalse(v.pass)
        assertEquals(OfflineAi.ONLINE_ONLY_REASON, v.reason)
        assertTrue(v.reason.contains("online"))
    }

    // ------------------------------------------------------------------ Lumo's pick

    @Test
    fun newQuestFollowsTheFamilysTopic() {
        val space = OfflineAi.newQuest(listOf("Saturn - Wikipedia", "Moons of Jupiter"))!!
        assertEquals("Ring Spotters", space.title)
        assertTrue(space.generated)
        assertTrue(space.id.matches(Regex("lumo-[0-9a-f]{8}")))
        assertTrue(space.goal in 2..3)
        assertTrue(space.rubric.isNotBlank())
        assertTrue(space.offline.isNotEmpty())
        val ocean = OfflineAi.newQuest(listOf("Great Barrier Reef", "Octopus"))!!
        assertEquals("Glow Hunters", ocean.title)
        val dino = OfflineAi.newQuest(listOf("Tyrannosaurus"))!!
        assertEquals("Plant Munchers", dino.title)
        assertEquals("Record Breakers", OfflineAi.newQuest(emptyList())!!.title)
        assertEquals("Record Breakers", OfflineAi.newQuest(listOf("Login", "Settings"))!!.title)
        assertEquals(space.id, OfflineAi.newQuest(listOf("Neptune"))!!.id) // stable ids
    }

    @Test
    fun generatedQuestsCanBeJudgedOffline() {
        val quest = OfflineAi.newQuest(listOf("Saturn"))!!
        val page = PageText("https://simple.wikipedia.org/wiki/Saturn", "Saturn",
            "Saturn is the sixth planet from the Sun. Saturn has the brightest rings of any planet.")
        val v = OfflineAi.checkQuest(quest, page, emptyList())
        assertTrue(v.pass)
        assertEquals("Saturn", v.item)
        assertTrue(v.reason.startsWith("Yes! Saturn counts! Its bright rings"))
    }

    @Test
    fun everyTemplateIsWellFormed() {
        for (t in OfflineQuests.TEMPLATES + OfflineQuests.RECORD_BREAKERS) {
            val q = t.toQuest()
            assertTrue(q.title.length <= 32)
            assertTrue(q.prompt.startsWith("Find") && q.prompt.length <= 140)
            assertTrue(q.goal in 2..3)
            assertTrue("${q.title} needs enough answers", q.offline.size >= q.goal + 1)
            assertTrue(q.rubric.length <= 400)
            for (a in q.offline) {
                assertTrue(a.keywords.isNotEmpty())
                assertTrue("reason too long for ${a.item}", "Yes! ${a.item} counts! ${a.fact}".length <= 120)
            }
        }
        assertEquals(OfflineQuests.TEMPLATES.size + 1,
            (OfflineQuests.TEMPLATES + OfflineQuests.RECORD_BREAKERS).map { it.toQuest().id }.toSet().size)
    }

    // ------------------------------------------------------------------ companion

    private fun stats(pages: Int = 22, minutes: Int = 14, hour: Int = 19) =
        SessionStats(sessionMinutes = minutes, pagesLast5Min = 7, avgDwellSec = 12, scrollSecondsNoClick = 200,
            sameSiteRepeats = 5, localHour = hour, pagesTotal = pages)

    @Test
    fun everyDriftReasonGetsALineAndASuggestion() {
        val moods = mapOf(
            DriftReason.RAPID_HOPPING to Mood.CONCERNED,
            DriftReason.ENDLESS_SCROLL to Mood.SLEEPY,
            DriftReason.SITE_LOOP to Mood.CURIOUS,
            DriftReason.LATE_NIGHT to Mood.SLEEPY,
        )
        for (reason in DriftReason.values()) {
            for (p in 0 until 6) {
                val advice = OfflineAi.advise(stats(pages = p), DriftSignal(reason, "headline", "detail"))
                assertEquals(moods[reason], advice.mood)
                assertTrue(advice.line.isNotBlank() && advice.line.length <= 120)
                val s = advice.suggestion
                assertNotNull(s)
                when (s!!.kind) {
                    RescueOption.Kind.QUEST -> assertTrue(s.questId in specQuestIds)
                    RescueOption.Kind.DETOUR -> assertTrue(s.url!!.startsWith("https://simple.wikipedia.org/wiki/"))
                    RescueOption.Kind.WIND_DOWN -> Unit
                }
                assertTrue(s.label.isNotBlank() && s.detail.isNotBlank())
            }
        }
    }

    @Test
    fun adviceRotatesSoNudgesDoNotRepeat() {
        val lines = (0 until 6).map {
            OfflineAi.advise(stats(pages = it), DriftSignal(DriftReason.RAPID_HOPPING, "h", "d")).line
        }.toSet()
        assertTrue(lines.size >= 2)
    }

    @Test
    fun twoNudgesInARowNeverSayTheSameLine() {
        // On the emulator two hopping nudges a minute apart landed on the same variant (5 pages + 1 minute later).
        val signal = DriftSignal(DriftReason.RAPID_HOPPING, "h", "d")
        val first = OfflineAi.advise(stats(pages = 5), signal).line
        val second = OfflineAi.advise(stats(pages = 5), signal).line
        assertTrue(first != second)
    }

    @Test
    fun lateNightOffersSomethingCalm() {
        for (p in 0 until 4) {
            val s = OfflineAi.advise(stats(pages = p, hour = 23), DriftSignal(DriftReason.LATE_NIGHT, "h", "d")).suggestion!!
            assertEquals(RescueOption.Kind.DETOUR, s.kind)
            assertTrue(s.url!!.endsWith("/Aurora") || s.url!!.endsWith("/Moth"))
        }
    }
}
