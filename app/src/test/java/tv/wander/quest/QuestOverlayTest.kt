package tv.wander.quest

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.cards.FakePage
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestOutcome
import tv.wander.model.Verdict
import tv.wander.testing.Snap

@RunWith(RobolectricTestRunner::class)
class QuestOverlayTest {
    private class Recorder : QuestOverlay.Listener {
        val picked = ArrayList<Quest>()
        var lumo = 0
        var pickerClosed = 0
        var introDismissed = 0
        var finaleDismissed = 0
        override fun onQuestPicked(quest: Quest) { picked += quest }
        override fun onLumoPickRequested() { lumo++ }
        override fun onQuestPickerClosed() { pickerClosed++ }
        override fun onIntroDismissed() { introDismissed++ }
        override fun onFinaleDismissed() { finaleDismissed++ }
    }

    private val speed = QuestCatalog.byId("speed-safari")!!
    private val maya = Player("p1", "Maya", 1)
    private val sam = Player("p2", "Sam", 2)
    private val grandpa = Player("p3", "Grandpa", 0)
    private val leo = Player("p4", "Leo", 3)

    private fun players() = listOf(maya, sam, grandpa)

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, 0)
    }

    /** A real press: the release carries the same down time as the press, like the remote sends. */
    private fun press(overlay: QuestOverlay, code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = overlay.handleKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        val up = overlay.handleKey(KeyEvent(t, t + 60, KeyEvent.ACTION_UP, code, 0))
        return down && up
    }

    private fun stage(activity: Activity, overlay: QuestOverlay, dark: Boolean = true) {
        val root = FrameLayout(activity)
        root.addView(FakePage(activity, dark), FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        Snap.show(activity, root)
    }

    /** Every descendant of type [T] (the overlay's internal views are visible to tests in this module). */
    private inline fun <reified T : View> View.findAll(): List<T> = descendants(this).filterIsInstance<T>()

    private fun descendants(root: View): List<View> {
        val out = ArrayList<View>()
        val stack = ArrayDeque<View>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        return out
    }

    private fun verdict(item: String, evidence: String) = Verdict(true, item, "Yes!", evidence, fromAi = true)

    @Test
    fun pickerSnapshotAndKeys() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = QuestOverlay(activity, rec)
        stage(activity, overlay, dark = false)
        overlay.showPicker(QuestCatalog.all)
        Snap.settle(400)
        assertTrue(overlay.isModal)
        Snap.save(activity, "quest_picker")

        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("moon-collectors"), rec.picked.map { it.id })
        // Down then right to the Lumo card (bottom-right of the 3x2 grid).
        press(overlay, KeyEvent.KEYCODE_DPAD_DOWN)
        press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(overlay, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, rec.lumo)
        Snap.settle(300)
        Snap.save(activity, "quest_picker_lumo_thinking")
        // A second OK while Lumo is thinking doesn't ask twice.
        press(overlay, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, rec.lumo)
        assertTrue(press(overlay, KeyEvent.KEYCODE_BACK))
        assertEquals(1, rec.pickerClosed)
        assertFalse(overlay.isModal)
        assertFalse(overlay.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)))
    }

    @Test
    fun introSnapshotAndDismiss() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = QuestOverlay(activity, rec)
        stage(activity, overlay, dark = false)
        overlay.showIntro(speed)
        Snap.settle(400)
        Snap.save(activity, "quest_intro")
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, rec.introDismissed)
        assertFalse(overlay.isModal)
    }

    @Test
    fun generatedIntroSnapshot() {
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        overlay.showIntro(
            Quest("lumo-volcano", "Volcano Spotters", "Find two volcanoes that erupted in the last 100 years.", 2, "🌋", "Any real volcano with an eruption after 1925.", generated = true),
        )
        Snap.settle(400)
        Snap.save(activity, "quest_intro_lumo")
        assertTrue(press(overlay, KeyEvent.KEYCODE_BACK))
        assertFalse(overlay.isModal)
    }

    @Test
    fun boardWhileCheckingSnapshot() {
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        engine.apply("p2", verdict("Cheetah", "Cheetahs can reach 110 km/h in short bursts."), ps)
        overlay.showBoard(engine.board(ps))
        Snap.settle(300)
        overlay.showChecking(maya)
        Snap.settle(400)
        assertFalse(overlay.isModal)
        assertTrue(overlay.isBoardShowing)
        assertFalse(overlay.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT)))
        Snap.save(activity, "quest_board_checking")
    }

    @Test
    fun acceptedOutcomeSnapshot() {
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        engine.apply("p2", verdict("Cheetah", "Cheetahs can reach 110 km/h in short bursts."), ps)
        overlay.showBoard(engine.board(ps))
        overlay.showChecking(maya)
        Snap.settle(300)
        val outcome = engine.apply("p1", verdict("Peregrine falcon", "The peregrine falcon reaches over 320 km/h during its hunting dive."), ps)
        overlay.showOutcome(outcome, maya)
        Snap.settle(450)
        // Robolectric runs animators on a compressed clock, so hold the burst mid-flight for the picture.
        overlay.findAll<BurstView>().forEach { it.freezeAt(0.3f) }
        Snap.save(activity, "quest_outcome_accepted")
        // The banner leaves on its own after a few seconds.
        Snap.settle(4600)
        Snap.save(activity, "quest_outcome_after")
    }

    @Test
    fun duplicateAndRejectedSnapshots() {
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(speed) }
        val ps = players()
        engine.apply("p2", verdict("Cheetah", "Cheetahs can reach 110 km/h in short bursts."), ps)
        overlay.showBoard(engine.board(ps))
        val dup = engine.apply("p1", verdict("cheetahs", "Cheetahs are fast."), ps)
        assertTrue(dup is QuestOutcome.Duplicate)
        overlay.showOutcome(dup, maya)
        Snap.settle(450)
        Snap.save(activity, "quest_outcome_duplicate")
        val no = engine.apply("p3", Verdict(false, null, "Pronghorns top out around 88 km/h. So close, but not over 100!", null, true), ps)
        overlay.showOutcome(no, grandpa)
        Snap.settle(450)
        Snap.save(activity, "quest_outcome_rejected")
    }

    @Test
    fun finaleSnapshotAndChoice() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = QuestOverlay(activity, rec)
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(speed) }
        val ps = listOf(maya, sam, grandpa, leo)
        engine.apply("p2", verdict("Cheetah", "Cheetahs can reach 110 km/h in short bursts."), ps)
        engine.apply("p1", verdict("Peregrine falcon", "The peregrine falcon reaches over 320 km/h during its hunting dive."), ps)
        val last = engine.apply("p1", verdict("Golden eagle", "Golden eagles can dive at about 240 km/h."), ps)
        assertTrue(last.board.complete)
        overlay.showFinale(last.board)
        Snap.settle(650)
        assertTrue(overlay.isModal)
        val confetti = overlay.findAll<ConfettiView>().single()
        confetti.freezeAt(950f)
        Snap.save(activity, "quest_finale")
        confetti.freezeAt(1900f)
        Snap.save(activity, "quest_finale_late")
        confetti.freezeAt(2700f)
        Snap.save(activity, "quest_finale_settled")
        assertNull(overlay.finaleChoice)
        press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(QuestOverlay.FinaleChoice.BACK_TO_BROWSING, overlay.finaleChoice)
        assertEquals(1, rec.finaleDismissed)
        assertFalse(overlay.isModal)
    }

    @Test
    fun finaleNeverCutsALongQuoteInHalf() {
        // The quotes the offline judge produced on the emulator (Simple English Wikipedia): all three wrap.
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(speed) }
        val ps = listOf(maya, sam, leo)
        engine.apply("p4", verdict("Cheetah", "It is the fastest land animal and can run up to 105 kilometres per hour, and accelerate from 0 to 97 kilometres per hour in less than 3…"), ps)
        engine.apply("p2", verdict("Peregrine falcon", "It can go up to 322 km/h in a dive, which means it is the fastest animal in the world."), ps)
        val last = engine.apply("p1", verdict("Golden eagle", "Golden eagles can dive at about 240 km/h to catch their prey."), ps)
        overlay.showFinale(last.board)
        Snap.settle(650)
        overlay.findAll<ConfettiView>().single().freezeAt(2700f)
        Snap.save(activity, "quest_finale_long_quotes")
        val quotes = overlay.findAll<android.widget.TextView>().filter { it.text.startsWith("“") && it.isShown }
        assertEquals(3, quotes.size)
        for (q in quotes) {
            val column = q.parent as View
            assertTrue("\"${q.text}\" runs past its column", q.bottom <= column.height)
            // A list squeezed into its column hands the last quote whatever height is left: its lines then
            // overflow the view and get cut through the middle.
            val needed = q.layout.getLineBottom(q.layout.lineCount - 1) + q.compoundPaddingTop + q.compoundPaddingBottom
            assertTrue("\"${q.text}\" is cut mid-line ($needed > ${q.height})", needed <= q.height)
        }
    }

    @Test
    fun finaleDefaultsToAnotherQuestAndBackMeansBrowse() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = QuestOverlay(activity, rec)
        stage(activity, overlay)
        val engine = QuestEngine().apply { start(QuestCatalog.byId("moon-collectors")!!) }
        val ps = listOf(maya)
        engine.apply("p1", verdict("Saturn", "Saturn has 274 moons."), ps)
        val done = engine.apply("p1", verdict("Jupiter", "Jupiter has 95 known moons."), ps)
        overlay.showFinale(done.board)
        Snap.settle(300)
        press(overlay, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(QuestOverlay.FinaleChoice.ANOTHER_QUEST, overlay.finaleChoice)
        overlay.showFinale(done.board)
        Snap.settle(300)
        assertNull(overlay.finaleChoice)
        press(overlay, KeyEvent.KEYCODE_BACK)
        assertEquals(QuestOverlay.FinaleChoice.BACK_TO_BROWSING, overlay.finaleChoice)
        assertEquals(2, rec.finaleDismissed)
    }

    @Test
    fun checkingWithoutABoardAndOddInputsNeverCrash() {
        val activity = Snap.activity()
        val overlay = QuestOverlay(activity, Recorder())
        stage(activity, overlay)
        overlay.showChecking(Player("x", "", 7))
        Snap.settle(200)
        assertTrue(overlay.isBoardShowing)
        val engine = QuestEngine()
        val noQuest = engine.apply("x", verdict("Cheetah", "fast"), emptyList())
        overlay.showOutcome(noQuest, Player("x", "", 7))
        overlay.showBoard(null)
        overlay.hideModal()
        overlay.showPicker(emptyList())
        Snap.settle(300)
        press(overlay, KeyEvent.KEYCODE_DPAD_CENTER)
        overlay.showFinale(QuestEngine().apply { start(speed) }.board(emptyList())!!)
        Snap.settle(400)
        Snap.save(activity, "quest_finale_empty")
        overlay.hideModal()
        Snap.settle(13_000)
        assertFalse(overlay.isModal)
    }
}
