package tv.wander.cards

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.model.Card
import tv.wander.model.CardDeck
import tv.wander.testing.Snap

@RunWith(RobolectricTestRunner::class)
class CardsOverlayTest {
    private class Recorder : CardsOverlay.Listener {
        val changes = ArrayList<Pair<Int, Int>>()
        val read = ArrayList<Card>()
        var closed = 0
        override fun onCardsClosed() { closed++ }
        override fun onCardChanged(index: Int, count: Int, card: Card) { changes += index to count }
        override fun onReadAloud(card: Card) { read += card }
    }

    private val deck = CardDeck(
        sourceTitle = "Cheetah - the fastest land animal",
        sourceUrl = "https://en.wikipedia.org/wiki/Cheetah",
        cards = listOf(
            Card("Built for speed", "Cheetahs sprint at up to about 110 km/h, faster than cars on most highways.", "🐆", "speed"),
            Card("Grip like cleats", "Their claws only half retract, so they bite into the ground like running spikes.", "🦶", "body"),
            Card("A tail that steers", "A long, flat tail works like a rudder, keeping them balanced in sharp turns.", "", "balance"),
            Card("Tired after 30 seconds", "A full sprint heats them up fast, so most chases end within about 300 meters.", "⏱️", "time"),
        ),
        fromAi = true,
    )

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, repeat)
    }

    /** A real press: the release carries the same down time as the press, like the remote sends. */
    private fun press(overlay: CardsOverlay, code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = overlay.handleKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        val up = overlay.handleKey(KeyEvent(t, t + 60, KeyEvent.ACTION_UP, code, 0))
        return down && up
    }

    private fun stage(activity: Activity, overlay: CardsOverlay, dark: Boolean = false) {
        val root = FrameLayout(activity)
        root.addView(FakePage(activity, dark), FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        Snap.show(activity, root)
    }

    @Test
    fun loadingSnapshot() {
        val activity = Snap.activity()
        val overlay = CardsOverlay(activity, Recorder())
        stage(activity, overlay)
        overlay.showLoading("Cheetah - the fastest land animal")
        Snap.settle(700)
        assertTrue(overlay.isShowing)
        assertNull(overlay.current)
        assertEquals(0, overlay.count)
        assertTrue(Snap.save(activity, "cards_loading").length() > 10_000)
    }

    @Test
    fun deckOnSecondCardSnapshot() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = CardsOverlay(activity, rec)
        stage(activity, overlay)
        overlay.showLoading(deck.sourceTitle)
        Snap.settle(300)
        overlay.showDeck(deck)
        Snap.settle(400)
        assertEquals(listOf(0 to 4), rec.changes)
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT))
        Snap.settle(600)
        assertEquals(1, overlay.index)
        assertEquals("Grip like cleats", overlay.current?.title)
        assertEquals(listOf(0 to 4, 1 to 4), rec.changes)
        Snap.save(activity, "cards_deck_2of4")
    }

    @Test
    fun offlineDeckWithIconFallbackSnapshot() {
        val activity = Snap.activity()
        val overlay = CardsOverlay(activity, Recorder())
        stage(activity, overlay, dark = true)
        overlay.showDeck(deck.copy(fromAi = false, cards = deck.cards.map { it.copy(emoji = "") }))
        Snap.settle(300)
        press(overlay, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        Snap.settle(200)
        press(overlay, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        Snap.settle(600)
        assertEquals(2, overlay.index)
        Snap.save(activity, "cards_offline_3of4")
    }

    @Test
    fun errorSnapshot() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = CardsOverlay(activity, rec)
        stage(activity, overlay)
        overlay.showLoading("Sign in to continue")
        Snap.settle(200)
        overlay.showError("This page is mostly a sign-in form, so there's nothing for me to read yet.")
        Snap.settle(500)
        assertTrue(overlay.isShowing)
        Snap.save(activity, "cards_error")
        assertTrue(press(overlay, KeyEvent.KEYCODE_BACK))
        assertEquals(1, rec.closed)
        assertFalse(overlay.isShowing)
    }

    @Test
    fun flippingStopsAtTheEndsWithoutWrapping() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = CardsOverlay(activity, rec)
        stage(activity, overlay)
        overlay.showDeck(deck)
        Snap.settle(300)
        overlay.prev()
        Snap.settle(300)
        assertEquals(0, overlay.index)
        repeat(6) { overlay.next(); Snap.settle(300) }
        assertEquals(3, overlay.index)
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4), rec.changes)
        press(overlay, KeyEvent.KEYCODE_MEDIA_REWIND)
        Snap.settle(300)
        assertEquals(2, overlay.index)
    }

    @Test
    fun okReadsTheCurrentCardAndBackCloses() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = CardsOverlay(activity, rec)
        stage(activity, overlay)
        overlay.showDeck(deck)
        Snap.settle(300)
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(press(overlay, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf("Built for speed", "Built for speed"), rec.read.map { it.title })
        // Held keys repeat: only the first press reads aloud.
        overlay.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 3))
        assertEquals(2, rec.read.size)
        // Keys the overlay doesn't use are still consumed, except volume.
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(overlay.handleKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
        // Back closes on the down-press and swallows its own up-press.
        val t = SystemClock.uptimeMillis()
        assertTrue(overlay.handleKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)))
        assertFalse(overlay.isShowing)
        assertEquals(1, rec.closed)
        // An unrelated release is not ours to eat; the release of the press that closed us is.
        assertFalse(overlay.handleKey(KeyEvent(t - 500, t + 10, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)))
        assertTrue(overlay.handleKey(KeyEvent(t, t + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)))
        assertFalse(overlay.handleKey(KeyEvent(t, t + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)))
        assertFalse(overlay.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT)))
        Snap.settle(400)
        assertEquals(View.GONE, overlay.visibility)
        assertNull(overlay.current)
        // close() when already closed doesn't fire again.
        overlay.close()
        assertEquals(1, rec.closed)
    }

    @Test
    fun emptyDeckBecomesAFriendlyError() {
        val activity = Snap.activity()
        val rec = Recorder()
        val overlay = CardsOverlay(activity, rec)
        stage(activity, overlay)
        overlay.showDeck(deck.copy(cards = emptyList()))
        Snap.settle(300)
        assertTrue(overlay.isShowing)
        assertEquals(0, overlay.count)
        assertTrue(rec.changes.isEmpty())
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(overlay.isShowing)
        assertEquals(1, rec.closed)
    }

    @Test
    fun longTextsAndOddInputsNeverCrash() {
        val activity = Snap.activity()
        val overlay = CardsOverlay(activity, Recorder())
        stage(activity, overlay)
        val long = "An extraordinarily long card title that goes on and on"
        val line = "Word ".repeat(80)
        overlay.showDeck(
            CardDeck("", "not a url", listOf(Card(long, line, "👨‍👩‍👧‍👦🐆🐆", ""), Card("", "Only a line", "", "")), fromAi = false),
        )
        Snap.settle(400)
        Snap.save(activity, "cards_stress")
        assertEquals(2, overlay.count)
    }
}
