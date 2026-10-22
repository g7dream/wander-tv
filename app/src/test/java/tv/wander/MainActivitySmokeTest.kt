package tv.wander

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import tv.wander.companion.CompanionLayer
import tv.wander.home.HomeView
import tv.wander.home.LumoActionBar
import tv.wander.home.ToastChip
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mode
import tv.wander.model.Mood
import tv.wander.model.Player
import tv.wander.model.QuestOutcome
import tv.wander.model.Verdict
import tv.wander.net.PhoneCommand
import tv.wander.net.PhoneHub
import tv.wander.quest.QuestEngine
import tv.wander.testing.Snap
import java.net.ServerSocket

/** Launches the real activity (WebView is shadowed) and drives it like a remote and a phone would. */
@RunWith(RobolectricTestRunner::class)
class MainActivitySmokeTest {
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity

    private fun launch() {
        // Phones get a free port (8080 may be busy on this machine) and the AI stays offline ("off" is
        // not an http(s) address), so nothing here depends on the network or on timing.
        Config.portOverride = ServerSocket(0).use { it.localPort }
        val intent = Intent().putExtra("api_url", "off").putExtra("api_key", "test")
        val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        controller = c
        activity = c.get()
        Snap.settle(1200)
        // A remote-driven TV is never in touch mode.
        activity.window.decorView.findViewById<View>(android.R.id.content).requestFocusFromTouch()
        field<HomeView>("home").focusDefault()
        Snap.settle(100)
    }

    /** Always tear the activity down (frees the phone port, the AI pools and the voice), then check the log. */
    @After
    fun tearDown() {
        try {
            controller?.pause()?.stop()?.destroy()
            Snap.settle(100)
        } finally {
            Config.portOverride = null
        }
        // guard() logs instead of crashing; in tests a swallowed exception is still a failure.
        val errors = ShadowLog.getLogsForTag("Wander").filter { it.type >= android.util.Log.ERROR }
        assertTrue(errors.joinToString(" | ") { "${it.msg}: ${it.throwable}" }, errors.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T {
        val f = MainActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(name: String, vararg args: Pair<Class<*>, Any?>): T {
        val m = MainActivity::class.java.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
        m.isAccessible = true
        return m.invoke(activity, *args.map { it.second }.toTypedArray()) as T
    }

    private val mode: Mode get() = field("mode")
    private val lumo: CompanionLayer get() = field("lumo")

    /**
     * Like ViewRootImpl: a real press (down then up sharing one downTime, as the remote sends them),
     * running focus navigation if nobody handled the down.
     */
    private fun press(code: Int) {
        val t = SystemClock.uptimeMillis()
        val handled = activity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        if (!handled && dir != 0) activity.window.decorView.findFocus()?.focusSearch(dir)?.requestFocus(dir)
        Snap.settle(40)
        activity.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
        Snap.settle(60)
    }

    /** Waits for something that lands from a background thread (offline AI answers). */
    private fun settleUntil(what: String, done: () -> Boolean) {
        repeat(100) {
            if (done()) return
            Thread.sleep(20)
            Snap.settle(30)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private fun focused(): String = (activity.window.decorView.findFocus()?.contentDescription ?: "").toString()

    private fun openFirstFavorite() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(focused().startsWith("Favorites"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(300)
        assertEquals(Mode.BROWSE, mode)
    }

    @Test
    fun launchesHomeAndRendersEveryLayer() {
        launch()
        assertEquals(Mode.HOME, mode)
        assertTrue(focused().startsWith("Search"))
        // Home's bubble runs along the bottom, never over the tiles; the hub is serving, so the code shows.
        assertEquals(CompanionLayer.Placement.FOOTER, lumo.placement)
        assertTrue(field<Boolean>("hubReady"))
        Snap.save(activity, "main_home")
    }

    @Test
    fun remoteFlowsThroughHomeBrowseAndBack() {
        launch()
        val home = field<HomeView>("home")

        // Favorites tile jumps to the row; OK on a favorite opens it in the browser.
        openFirstFavorite()
        assertEquals(View.INVISIBLE, home.visibility)
        assertEquals(CompanionLayer.Placement.CORNER, lumo.placement)

        // D-pad belongs to the cursor while browsing: always consumed, never reaches the page.
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER)) {
            val t = SystemClock.uptimeMillis()
            assertTrue(activity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0)))
        }

        // Menu opens Lumo's bar (Lumo keeps quiet meanwhile); Back closes it without leaving the page.
        val bar = field<LumoActionBar>("actionBar")
        press(KeyEvent.KEYCODE_MENU)
        assertTrue(bar.isShowing)
        assertEquals(CompanionLayer.Placement.QUIET, lumo.placement)
        Snap.settle(300)
        Snap.save(activity, "main_browse_bar")
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(bar.isShowing)
        assertEquals(Mode.BROWSE, mode)
        assertEquals(CompanionLayer.Placement.CORNER, lumo.placement)

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

        // Back on Home asks twice before leaving (the toast sits between the tagline and the tiles).
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(activity.isFinishing)
        assertTrue(field<ToastChip>("toast").text.toString().contains("Back again"))
        Snap.settle(300)
        Snap.save(activity, "main_home_toast")
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

        // Typing without a page opens the Home search, prefilled; an empty Send presses Enter in it.
        val home = field<HomeView>("home")
        listener.onCommand(maya, PhoneCommand.Type("cheetah", false))
        Snap.settle(100)
        assertTrue(home.isSearchOpen)
        listener.onCommand(maya, PhoneCommand.Key("enter"))
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

        // Typing and submitting from Home browses.
        listener.onCommand(maya, PhoneCommand.Type("cheetah top speed", true))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)

        // Go opens straight into the browser; commands with nothing to act on never crash.
        listener.onCommand(maya, PhoneCommand.Go("saturn moons"))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)
        for (c in listOf(PhoneCommand.MakeCards, PhoneCommand.ReadAloud, PhoneCommand.Rescue(0), PhoneCommand.Zoom(1),
            PhoneCommand.Scroll(0f, 120f), PhoneCommand.Tap, PhoneCommand.QuestStart("nope"))) {
            listener.onCommand(maya, c)
            Snap.settle(50)
        }
        // Menu closes the cards that MakeCards opened; nothing is left loading or "thinking".
        assertEquals(Mode.CARDS, mode)
        assertEquals(CompanionLayer.Placement.QUIET, lumo.placement)
        Snap.save(activity, "main_cards_loading")
        listener.onCommand(maya, PhoneCommand.Key("menu"))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)
        assertFalse(field<Boolean>("cardsLoading"))

        // Phone Home while cards are still loading cleans them up too.
        listener.onCommand(maya, PhoneCommand.MakeCards)
        Snap.settle(50)
        assertTrue(field<Boolean>("cardsLoading"))
        listener.onCommand(maya, PhoneCommand.Key("home"))
        Snap.settle(100)
        assertEquals(Mode.HOME, mode)
        assertFalse(field<Boolean>("cardsLoading"))

        // Lumo's pick (offline here) lands on the quest intro, where Lumo steps aside.
        listener.onCommand(maya, PhoneCommand.Go("saturn moons"))
        Snap.settle(100)
        listener.onCommand(maya, PhoneCommand.QuestSubmit)
        listener.onCommand(maya, PhoneCommand.QuestStart("lumo"))
        settleUntil("Lumo's pick") { mode == Mode.QUEST_INTRO }
        assertFalse(field<Boolean>("pickPending"))
        Snap.settle(300)
        assertEquals(View.INVISIBLE, lumo.visibility)
        assertEquals(CompanionLayer.Placement.QUIET, lumo.placement)

        listener.onPlayerLeft(maya)
        Snap.settle(100)
    }

    @Test
    fun phoneCommandsWaitWhileWanderIsInTheBackground() {
        launch()
        val listener = field<PhoneHub.Listener>("hubListener")
        val maya = Player("p1", "Maya", 1)
        listener.onPlayerJoined(maya)
        controller!!.pause().stop()
        listener.onCommand(maya, PhoneCommand.Go("saturn moons"))
        Snap.settle(100)
        assertEquals(Mode.HOME, mode)
        controller!!.start().resume()
        Snap.settle(100)
        listener.onCommand(maya, PhoneCommand.Go("saturn moons"))
        Snap.settle(100)
        assertEquals(Mode.BROWSE, mode)
    }

    @Test
    fun questFinaleAnotherQuestOpensThePicker() {
        launch()
        // Home -> Quests -> the first quest -> its intro -> hunting in the browser.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(focused().startsWith("Quests"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Mode.QUEST_PICKER, mode)
        Snap.settle(400)
        // The picker has its own moth: Lumo steps out instead of sitting on the "Lumo's pick" card.
        assertEquals(View.INVISIBLE, lumo.visibility)
        Snap.save(activity, "main_quest_picker")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Mode.QUEST_INTRO, mode)
        Snap.settle(400)
        Snap.save(activity, "main_quest_intro")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Mode.BROWSE, mode)
        Snap.settle(1500)
        assertEquals(View.VISIBLE, lumo.visibility)
        // Lumo announces the hunt once the family can see it, in the corner, clear of the board.
        Snap.save(activity, "main_quest_hunt")

        // Three accepted finds (judged here by the real engine), as the AI judge would send them.
        val engine = field<QuestEngine>("quests")
        val quest = assertNotNullAndGet(engine.active)
        val couch: Player = call("couchPlayer")
        quest.offline.take(quest.goal).forEach { answer ->
            val players: List<Player> = call("questPlayers", Player::class.java to couch)
            val outcome = engine.apply(couch.id, Verdict(true, answer.item, "Yes!", answer.fact, fromAi = false), players)
            assertTrue(outcome is QuestOutcome.Accepted)
            call<Unit>("finishCheck", QuestOutcome::class.java to outcome, Player::class.java to couch)
            Snap.settle(200)
        }
        Snap.settle(3000)
        assertTrue(field<Boolean>("finaleShowing"))
        assertEquals(Mode.QUEST_INTRO, mode)
        Snap.save(activity, "main_quest_finale")

        // "Another quest" has focus: OK opens the picker again, and the finished quest is gone.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(300)
        assertEquals(Mode.QUEST_PICKER, mode)
        assertNull(engine.active)
        assertFalse(field<Boolean>("finaleShowing"))

        // Back on the picker returns to the page.
        press(KeyEvent.KEYCODE_BACK)
        Snap.settle(200)
        assertEquals(Mode.BROWSE, mode)
    }

    @Test
    fun panelAndRescueAnswerRealKeyPairs() {
        launch()
        // Menu on Home opens the Lumo panel; Back (acted on at key-up) closes it, exactly once.
        press(KeyEvent.KEYCODE_MENU)
        assertEquals(Mode.COMPANION, mode)
        Snap.settle(300)
        // Toggling nudges answers with a toast under the panel header, not a bubble over the toggles.
        field<tv.wander.companion.CompanionPanel.Listener>("panelListener").onToggleNudges(true)
        Snap.settle(400)
        assertEquals(CompanionLayer.Placement.QUIET, lumo.placement)
        Snap.save(activity, "main_panel_toast")
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Mode.HOME, mode)
        Snap.settle(200)
        assertTrue(focused().isNotEmpty())

        openFirstFavorite()
        val signal = DriftSignal(DriftReason.RAPID_HOPPING, "We've hopped 5 pages in a minute.", "5 pages · 1 min · ~9 s each")
        val advice = CompanionAdvice(Mood.SLEEPY, "", null)
        call<Unit>("showRescue", DriftSignal::class.java to signal, CompanionAdvice::class.java to advice)
        assertEquals(Mode.RESCUE, mode)
        assertEquals(Mood.SLEEPY, lumo.mood)
        Snap.settle(300)
        Snap.save(activity, "main_rescue")
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Mode.BROWSE, mode)
        assertEquals(Mood.CURIOUS, lumo.mood)
        Snap.settle(1500)
        field<ToastChip>("toast").show("Type on your phone: it goes straight into \"Search Wikipedia\".", ToastChip.Kind.INFO)
        Snap.settle(400)
        Snap.save(activity, "main_browse_line")

        // Choosing the quest option wakes Lumo up and starts that quest.
        Snap.settle(1000)
        call<Unit>("showRescue", DriftSignal::class.java to signal, CompanionAdvice::class.java to advice)
        assertEquals(Mode.RESCUE, mode)
        Snap.settle(300)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(100)
        assertEquals(Mode.QUEST_INTRO, mode)
        assertEquals(Mood.CURIOUS, lumo.mood)
    }

    @Test
    fun aFinderWhosePhoneDropsOffStaysOnTheBoard() {
        launch()
        // On the emulator a phone that left mid-quest vanished from the scoreboard and its find lost its name.
        val engine = field<QuestEngine>("quests")
        engine.start(tv.wander.quest.QuestCatalog.byId("speed-safari")!!)
        val ava = Player("p7", "Ava", 2)
        val before: List<Player> = call("questPlayers", Player::class.java to ava)
        assertTrue(engine.apply(ava.id, Verdict(true, "Cheetah", "Yes!", "fast", fromAi = false), before) is QuestOutcome.Accepted)
        val hubListener = field<PhoneHub.Listener>("hubListener")
        hubListener.onPlayerLeft(ava)
        Snap.settle(100)
        val after: List<Player> = call("questPlayers", Player::class.java to null)
        val kept = after.firstOrNull { it.id == ava.id }
        assertNotNull("Ava's find keeps her on the board", kept)
        assertEquals(100, kept!!.score)
        assertEquals("Ava", call<String>("nameOf", String::class.java to ava.id))
        // Back on the couch: no ghost copy of her stays behind.
        hubListener.onPlayerJoined(ava)
        Snap.settle(100)
        assertTrue(field<Map<String, Player>>("awayPlayers").isEmpty())
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
