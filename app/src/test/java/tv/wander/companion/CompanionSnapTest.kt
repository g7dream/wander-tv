package tv.wander.companion

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.model.Mood
import tv.wander.model.SessionStats
import tv.wander.testing.Snap
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette

@RunWith(RobolectricTestRunner::class)
class CompanionSnapTest {
    private class PanelSpy : CompanionPanel.Listener {
        val events = ArrayList<String>()
        override fun onToggleNudges(on: Boolean) {
            events += "nudges:$on"
        }

        override fun onToggleDemo(on: Boolean) {
            events += "demo:$on"
        }

        override fun onCompanionPanelClosed() {
            events += "closed"
        }
    }

    private fun press(target: (KeyEvent) -> Boolean, code: Int): Boolean {
        val down = target(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val up = target(KeyEvent(KeyEvent.ACTION_UP, code))
        return down && up
    }

    @Test
    fun layerSmallWithBubble() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val layer = CompanionLayer(a)
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        layer.say("We've hopped 7 pages in 4 minutes. I know that dizzy feeling.", Mood.CONCERNED)
        Snap.settle(1600)
        assertTrue(Snap.save(a, "companion_small_bubble").length() > 20_000)
    }

    @Test
    fun layerBigWithBubbleAndThinking() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val layer = CompanionLayer(a)
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        layer.setBig(true)
        layer.mood = Mood.HAPPY
        layer.say(Lines.greeting(20))
        Snap.settle(1600)
        assertTrue(Snap.save(a, "companion_big_bubble").length() > 20_000)
        layer.thinking(true)
        layer.say("Hmm, let me think...", Mood.CURIOUS)
        Snap.settle(1400)
        Snap.save(a, "companion_big_thinking")
    }

    @Test
    fun layerNeverTakesFocusOrTouches() {
        val a = Snap.activity()
        val layer = CompanionLayer(a)
        Snap.show(a, layer)
        layer.say("Hello")
        assertFalse(layer.isFocusable)
        assertFalse(layer.hasFocusable())
        val ev = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 900f, 900f, 0)
        assertFalse(layer.dispatchTouchEvent(ev))
        ev.recycle()
        assertFalse(layer.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        // Robust to odd calls.
        layer.say("   ")
        layer.hush()
        layer.hush()
        layer.setBig(true)
        layer.setBig(true)
        layer.celebrate(Palette.MINT)
        layer.speaking(true)
        layer.speaking(false)
        Snap.settle(2000)
    }

    @Test
    fun bubbleAutoHidesAfterHold() {
        val a = Snap.activity()
        val layer = CompanionLayer(a)
        Snap.show(a, layer)
        layer.say("Short line", holdMs = 500)
        Snap.settle(300)
        val bubble = (0 until layer.childCount).map { layer.getChildAt(it) }.first { it !is LumoView }
        assertEquals(View.VISIBLE, bubble.visibility)
        Snap.settle(2000)
        assertEquals(View.GONE, bubble.visibility)
        layer.say("Stays up", holdMs = 0)
        Snap.settle(8000)
        assertEquals(View.VISIBLE, bubble.visibility)
        layer.hush()
        Snap.settle(500)
        assertEquals(View.GONE, bubble.visibility)
    }

    @Test
    fun panelWithTrail() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val spy = PanelSpy()
        val panel = CompanionPanel(a, spy)
        val layer = CompanionLayer(a)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        layer.setBig(true)
        val trail = listOf(
            "Cheetah", "Peregrine falcon", "Fastest animals", "Peregrine falcon",
            "Sailfish", "Black marlin", "Golden eagle", "Brazilian free-tailed bat",
        )
        panel.show(SessionStats(23, 6, 42, 0, 1, 21, 14), trail, aiOnline = true, nudgesOn = true, demoMode = false)
        Snap.settle(700)
        assertTrue(Snap.save(a, "companion_panel").length() > 20_000)

        // Keys: down to demo, toggle it, back closes.
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_ENTER))
        Snap.settle(300)
        Snap.save(a, "companion_panel_toggled")
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_BACK))
        assertEquals(listOf("demo:true", "nudges:false", "closed"), spy.events)
        assertFalse(panel.isShowing)
        assertFalse(panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        Snap.settle(400)
        assertEquals(View.GONE, panel.visibility)
    }

    @Test
    fun panelEmptyAndOffline() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val spy = PanelSpy()
        val panel = CompanionPanel(a, spy)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        Snap.show(a, root)
        panel.show(SessionStats(0, 0, 0, 0, 0, 9, 0), emptyList(), aiOnline = false, nudgesOn = false, demoMode = true)
        Snap.settle(700)
        assertTrue(Snap.save(a, "companion_panel_empty").length() > 20_000)
        // A key-up without its key-down (the press that opened the panel) is ignored.
        panel.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        assertTrue(panel.isShowing)
        assertTrue(press(panel::handleKey, KeyEvent.KEYCODE_MENU))
        assertEquals(listOf("closed"), spy.events)
    }
}
