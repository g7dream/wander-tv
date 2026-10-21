package tv.wander.home

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import tv.wander.testing.Snap

@RunWith(RobolectricTestRunner::class)
class LumoActionBarTest {
    private class Recorder : LumoActionBar.Listener {
        val actions = mutableListOf<LumoActionBar.Action>()
        var hidden = 0
        override fun onAction(action: LumoActionBar.Action) { actions += action }
        override fun onActionBarHidden() { hidden++ }
    }

    /** A stand-in for a bright web page, so the bar is judged against what it really sits on. */
    private class FakePage(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(250, 250, 247))
            val u = height / 540f
            p.color = Color.rgb(40, 40, 48)
            c.drawRect(96 * u, 70 * u, 520 * u, 100 * u, p)
            p.color = Color.rgb(150, 150, 160)
            var y = 130f
            while (y < 500f) {
                c.drawRect(96 * u, y * u, (96 + 700 - (y.toInt() * 7 % 160)) * u, (y + 9) * u, p)
                y += 22f
            }
            p.color = Color.rgb(196, 214, 230)
            c.drawRect(560 * u, 130 * u, 860 * u, 300 * u, p)
        }
    }

    private fun key(bar: LumoActionBar, code: Int): Boolean {
        val down = bar.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val up = bar.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
        assertEquals("UP must follow DOWN for keycode $code", down, up)
        return down
    }

    @Test
    fun snapshotWithQuest() {
        val activity = Snap.activity()
        val root = FrameLayout(activity)
        root.addView(FakePage(activity), FrameLayout.LayoutParams(-1, -1))
        val rec = Recorder()
        val bar = LumoActionBar(activity, rec)
        root.addView(bar, FrameLayout.LayoutParams(-1, -1))
        Snap.show(activity, root)
        bar.show(questActive = true)
        Snap.settle(400)
        assertEquals(LumoActionBar.Action.QUEST_SUBMIT, bar.selected)
        Snap.save(activity, "action_bar_quest")
        key(bar, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(bar, KeyEvent.KEYCODE_DPAD_RIGHT)
        Snap.settle(300)
        assertEquals(LumoActionBar.Action.ZOOM_IN, bar.selected)
        Snap.save(activity, "action_bar_zoom")
    }

    @Test
    fun keysMoveTriggerAndClose() {
        val activity = Snap.activity()
        val rec = Recorder()
        val bar = LumoActionBar(activity, rec)
        Snap.show(activity, bar)
        assertFalse(bar.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))

        bar.show(questActive = false)
        assertTrue(bar.isShowing)
        assertEquals(LumoActionBar.Action.CARDS, bar.selected)
        assertTrue(key(bar, KeyEvent.KEYCODE_DPAD_LEFT)) // already at the start: stays
        assertEquals(LumoActionBar.Action.CARDS, bar.selected)
        assertTrue(key(bar, KeyEvent.KEYCODE_DPAD_UP))
        repeat(10) { key(bar, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(LumoActionBar.Action.LUMO_PANEL, bar.selected)
        assertTrue(key(bar, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(LumoActionBar.Action.HOME, bar.selected)
        assertTrue(key(bar, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(LumoActionBar.Action.HOME), rec.actions)
        assertFalse(bar.isShowing)
        assertEquals(1, rec.hidden)

        bar.show(questActive = false)
        assertTrue(key(bar, KeyEvent.KEYCODE_BACK))
        assertFalse(bar.isShowing)
        bar.show(questActive = false)
        assertTrue(key(bar, KeyEvent.KEYCODE_MENU))
        assertFalse(bar.isShowing)

        // Media keys close the bar but are left for the browser to handle.
        bar.show(questActive = false)
        assertFalse(bar.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertFalse(bar.isShowing)
        assertFalse(bar.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertNull(rec.actions.getOrNull(1))
    }

    @Test
    fun zoomKeepsTheBarOpen() {
        val activity = Snap.activity()
        val rec = Recorder()
        val bar = LumoActionBar(activity, rec)
        Snap.show(activity, bar)
        bar.show(questActive = false)
        key(bar, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(LumoActionBar.Action.ZOOM_OUT, bar.selected)
        key(bar, KeyEvent.KEYCODE_DPAD_CENTER)
        key(bar, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(LumoActionBar.Action.ZOOM_OUT, LumoActionBar.Action.ZOOM_OUT), rec.actions)
        assertTrue(bar.isShowing)
        assertEquals(LumoActionBar.Action.ZOOM_OUT, bar.selected)
        assertEquals(0, rec.hidden)
    }

    @Test
    fun hidesAfterIdle() {
        val activity = Snap.activity()
        val rec = Recorder()
        val bar = LumoActionBar(activity, rec)
        Snap.show(activity, bar)
        bar.show(questActive = true)
        Snap.settle(5_000)
        key(bar, KeyEvent.KEYCODE_DPAD_RIGHT) // activity resets the timer
        Snap.settle(5_000)
        assertTrue(bar.isShowing)
        Snap.settle(3_500)
        assertFalse(bar.isShowing)
        assertTrue(rec.actions.isEmpty())
    }
}
