package tv.wander

import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import tv.wander.home.HomeView
import tv.wander.home.LumoActionBar
import tv.wander.home.ToastChip
import tv.wander.model.Mode
import tv.wander.model.Player
import tv.wander.net.PhoneCommand
import tv.wander.net.PhoneHub
import tv.wander.testing.Snap

/** Launches the real activity (WebView is shadowed) and drives it like a remote and a phone would. */
@RunWith(RobolectricTestRunner::class)
class MainActivitySmokeTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        Snap.settle(1200)
        // A remote-driven TV is never in touch mode.
        activity.window.decorView.findViewById<View>(android.R.id.content).requestFocusFromTouch()
        field<HomeView>("home").focusDefault()
        Snap.settle(100)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T {
        val f = MainActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity) as T
    }

    private val mode: Mode get() = field("mode")

    /** Like ViewRootImpl: dispatch through the activity, then run focus navigation if nobody handled it. */
    private fun press(code: Int) {
        val handled = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        if (!handled && dir != 0) activity.window.decorView.findFocus()?.focusSearch(dir)?.requestFocus(dir)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        Snap.settle(60)
    }

    /** guard() logs instead of crashing; in tests a swallowed exception is still a failure. */
    @org.junit.After
    fun noSwallowedErrors() {
        val errors = org.robolectric.shadows.ShadowLog.getLogsForTag("Wander").filter { it.type >= android.util.Log.ERROR }
        assertTrue(errors.joinToString(" | ") { "${it.msg}: ${it.throwable}" }, errors.isEmpty())
    }

    private fun focused(): String = (activity.window.decorView.findFocus()?.contentDescription ?: "").toString()

    @Test
    fun launchesHomeAndRendersEveryLayer() {
        launch()
        assertEquals(Mode.HOME, mode)
        assertTrue(focused().startsWith("Search"))
        Snap.save(activity, "main_home")
        controller.pause().stop().destroy()
    }

    @Test
    fun remoteFlowsThroughHomeBrowseAndBack() {
        launch()
        val home = field<HomeView>("home")

        // Favorites tile jumps to the row; OK on a favorite opens it in the browser.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(focused().startsWith("Favorites"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(300)
        assertEquals(Mode.BROWSE, mode)
        assertEquals(View.INVISIBLE, home.visibility)

        // D-pad belongs to the cursor while browsing: always consumed, never reaches the page.
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
        }

        // Menu opens Lumo's bar; Back closes it without leaving the page.
        val bar = field<LumoActionBar>("actionBar")
        press(KeyEvent.KEYCODE_MENU)
        assertTrue(bar.isShowing)
        Snap.settle(300)
        Snap.save(activity, "main_browse_bar")
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(bar.isShowing)
        assertEquals(Mode.BROWSE, mode)

        // The bar's Home action goes Home.
        press(KeyEvent.KEYCODE_MENU)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(LumoActionBar.Action.HOME, bar.selected)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Mode.HOME, mode)
        assertEquals(View.VISIBLE, home.visibility)

        // Focus comes back to the favorite we left from; Back on the first page returns Home too.
        Snap.settle(100)
        assertTrue(focused().startsWith(tv.wander.home.Favorites.all[0].title))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Mode.BROWSE, mode)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Mode.HOME, mode)
        Snap.settle(100)

        // Back on Home asks twice before leaving.
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(activity.isFinishing)
        assertTrue(field<ToastChip>("toast").text.toString().contains("Back again"))
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun phoneCommandsAreRoutedLikeTheRemote() {
        launch()
        val listener = field<PhoneHub.Listener>("hubListener")
        val maya = Player("p1", "Maya", 1)
        listener.onPlayerJoined(maya)
        Snap.settle(100)

        // Phone arrows move Home focus through the same routing as the remote.
        listener.onCommand(maya, PhoneCommand.Key("right"))
        Snap.settle(60)
        assertTrue(focused().startsWith("Quests"))
        listener.onCommand(maya, PhoneCommand.Key("left"))
        Snap.settle(60)
        assertTrue(focused().startsWith("Search"))

        // A swipe on the touchpad works as a D-pad on Home.
        listener.onCommand(maya, PhoneCommand.Move(120f, 4f))
        Snap.settle(60)
        assertTrue(focused().startsWith("Quests"))

        // Typing without a page opens the Home search, prefilled; submitting browses.
        val home = field<HomeView>("home")
        listener.onCommand(maya, PhoneCommand.Type("cheetah", false))
        Snap.settle(100)
        assertTrue(home.isSearchOpen)
        listener.onCommand(maya, PhoneCommand.Type("cheetah top speed", true))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)

        // Phone Back can't close the app from Home.
        listener.onCommand(maya, PhoneCommand.Key("home"))
        Snap.settle(100)
        assertEquals(Mode.HOME, mode)
        listener.onCommand(maya, PhoneCommand.Key("back"))
        listener.onCommand(maya, PhoneCommand.Key("back"))
        Snap.settle(100)
        assertFalse(activity.isFinishing)

        // Go opens straight into the browser; commands with nothing to act on never crash.
        listener.onCommand(maya, PhoneCommand.Go("saturn moons"))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)
        for (c in listOf(PhoneCommand.MakeCards, PhoneCommand.QuestSubmit, PhoneCommand.ReadAloud, PhoneCommand.Rescue(0), PhoneCommand.Zoom(1),
            PhoneCommand.Scroll(0f, 120f), PhoneCommand.Tap, PhoneCommand.QuestStart("nope"), PhoneCommand.QuestStart("lumo"), PhoneCommand.Key("menu"))) {
            listener.onCommand(maya, c)
            Snap.settle(50)
        }
        listener.onPlayerLeft(maya)
        Snap.settle(100)
        controller.pause().stop().destroy()
    }
}
