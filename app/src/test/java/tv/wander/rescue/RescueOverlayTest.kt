package tv.wander.rescue

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.companion.CompanionLayer
import tv.wander.companion.Lines
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mood
import tv.wander.model.RescueOption
import tv.wander.testing.Snap
import tv.wander.ui.LampBackdrop

@RunWith(RobolectricTestRunner::class)
class RescueOverlayTest {
    private class Spy : RescueOverlay.Listener {
        val events = ArrayList<String>()
        override fun onRescueChoice(option: RescueOption) {
            events += "choice:${option.label}"
        }

        override fun onRescueDismiss() {
            events += "dismiss"
        }
    }

    private val signal = DriftSignal(
        DriftReason.RAPID_HOPPING,
        "We've hopped 7 pages in 4 minutes. I know that dizzy feeling.",
        "7 pages · 4 min · ~12 s each",
    )
    private val options = listOf(
        RescueOption(RescueOption.Kind.QUEST, "Speed Safari", "Everyone hunts for animals faster than a car on the highway.", questId = "speed-safari"),
        RescueOption(RescueOption.Kind.DETOUR, "The deepest place on Earth", "The Mariana Trench is deeper than Everest is tall.", url = "https://en.wikipedia.org/wiki/Mariana_Trench"),
    )

    private fun press(o: RescueOverlay, code: Int): Boolean {
        val down = o.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val up = o.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
        return down && up
    }

    private fun setup(): Triple<android.app.Activity, RescueOverlay, Spy> {
        val a = Snap.activity()
        val spy = Spy()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val overlay = RescueOverlay(a, spy)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        return Triple(a, overlay, spy)
    }

    @Test
    fun snapshotWithLumo() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val overlay = RescueOverlay(a, Spy())
        val layer = CompanionLayer(a)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        layer.setBig(true)
        layer.mood = Mood.SLEEPY
        overlay.show(signal, signal.headline, options)
        Snap.settle(800)
        assertTrue(Snap.save(a, "rescue_overlay").length() > 20_000)
        press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT)
        Snap.settle(400)
        Snap.save(a, "rescue_overlay_right")
    }

    @Test
    fun lateNightSnapshot() {
        val (a, overlay, _) = setup()
        val s = DriftSignal(DriftReason.LATE_NIGHT, "It's past eleven and the lamp's still on. Want a cozy wind-down?", "Past 11 pm · 47 min tonight · 23 pages")
        overlay.show(s, s.headline, Lines.fallbackOptions(DriftReason.LATE_NIGHT))
        Snap.settle(800)
        Snap.save(a, "rescue_overlay_late")
    }

    @Test
    fun centerChoosesTheFocusedOption() {
        val (_, overlay, spy) = setup()
        assertFalse(overlay.isShowing)
        assertFalse(overlay.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        overlay.show(signal, signal.headline, options)
        assertTrue(overlay.isShowing)
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("choice:Speed Safari"), spy.events)
        assertFalse(overlay.isShowing)
        // Resolved once: more keys and phone choices are ignored.
        assertFalse(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        overlay.choose(1)
        assertEquals(1, spy.events.size)
        Snap.settle(400)
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun rightThenEnterChoosesTheSecond() {
        val (_, overlay, spy) = setup()
        overlay.show(signal, signal.headline, options)
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_RIGHT)) // at the end: stays
        assertTrue(press(overlay, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("choice:The deepest place on Earth"), spy.events)
    }

    @Test
    fun backDismissesOnKeyUp() {
        val (_, overlay, spy) = setup()
        overlay.show(signal, signal.headline, options)
        assertTrue(overlay.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue("nothing happens on key-down", overlay.isShowing)
        assertTrue(overlay.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(listOf("dismiss"), spy.events)
        assertFalse(overlay.isShowing)
    }

    @Test
    fun aStrayKeyUpIsIgnored() {
        val (_, overlay, spy) = setup()
        overlay.show(signal, signal.headline, options)
        // The Center press that was already down when the overlay appeared must not pick anything.
        assertTrue(overlay.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(overlay.isShowing)
        assertTrue(spy.events.isEmpty())
        // Every other key is swallowed while showing.
        assertTrue(press(overlay, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(press(overlay, KeyEvent.KEYCODE_MENU))
        assertTrue(spy.events.isEmpty())
    }

    @Test
    fun phoneChoices() {
        val (_, overlay, spy) = setup()
        overlay.choose(0) // not showing: ignored
        overlay.show(signal, signal.headline, options)
        overlay.choose(5) // out of range: ignored
        assertTrue(overlay.isShowing)
        overlay.choose(1)
        overlay.show(signal, signal.headline, options)
        overlay.choose(-1)
        assertEquals(listOf("choice:The deepest place on Earth", "dismiss"), spy.events)
    }

    @Test
    fun noOptionsStillWorks() {
        val (a, overlay, spy) = setup()
        overlay.show(signal, "", emptyList())
        Snap.settle(400)
        Snap.save(a, "rescue_overlay_no_options")
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(press(overlay, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("dismiss"), spy.events)
    }
}
