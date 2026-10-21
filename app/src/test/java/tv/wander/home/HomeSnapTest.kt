package tv.wander.home

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.model.Player
import tv.wander.testing.Snap
import tv.wander.ui.Palette

@RunWith(RobolectricTestRunner::class)
class HomeSnapTest {
    private class Recorder : HomeView.Listener {
        val events = mutableListOf<String>()
        override fun onSearchQuery(query: String) { events += "search:$query" }
        override fun onOpenQuests() { events += "quests" }
        override fun onOpenFavorite(favorite: Favorite) { events += "fav:${favorite.title}" }
        override fun onOpenCompanion() { events += "lumo" }
    }

    @Test
    fun homeEmpty() {
        val activity = tvActivity()
        val home = HomeView(activity, Recorder())
        Snap.show(activity, home)
        home.setJoinInfo(null, null)
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(600)
        val file = Snap.save(activity, "home_no_wifi")
        assertTrue(file.length() > 10_000)
    }

    @Test
    fun homeWithPlayers() {
        val activity = tvActivity()
        val home = HomeView(activity, Recorder())
        Snap.show(activity, home)
        val url = "http://192.168.1.42:8080"
        home.setJoinInfo(url, qr(url, home.qrSizePx))
        home.setPlayers(listOf(Player("p1", "Maya", 1, 200), Player("p2", "Grandpa Joe", 2)))
        home.setAiOnline(true)
        home.setQuestLine("🐆 Speed Safari: 2 of 3 found")
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(600)
        Snap.save(activity, "home_players")
    }

    @Test
    fun homeFullCouchShowsEveryName() {
        val activity = tvActivity()
        val home = HomeView(activity, Recorder())
        Snap.show(activity, home)
        val url = "http://192.168.1.42:8080"
        home.setJoinInfo(url, qr(url, home.qrSizePx))
        home.setPlayers(listOf(Player("p1", "Maya", 1, 200), Player("p2", "Grandpa", 2), Player("p3", "Sophie", 3), Player("p4", "Leo", 0)))
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(600)
        Snap.save(activity, "home_full_couch")
    }

    @Test
    fun homeWhilePhonesCantJoinYet() {
        val activity = tvActivity()
        val home = HomeView(activity, Recorder())
        Snap.show(activity, home)
        val url = "http://192.168.1.42:8080"
        home.setJoinInfo(url, qr(url, home.qrSizePx))
        home.setPhonesReady(false)
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(600)
        Snap.save(activity, "home_phones_not_ready")
        // Once the hub is serving, the code comes back.
        home.setPhonesReady(true)
        Snap.settle(100)
    }

    @Test
    fun homeNoPhonesYet() {
        val activity = tvActivity()
        val home = HomeView(activity, Recorder())
        Snap.show(activity, home)
        val url = "http://192.168.100.123:8080"
        home.setJoinInfo(url, qr(url, home.qrSizePx))
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(600)
        Snap.save(activity, "home_waiting")
    }

    @Test
    fun searchOpen() {
        val activity = tvActivity()
        val rec = Recorder()
        val home = HomeView(activity, rec)
        Snap.show(activity, home)
        var fieldOpen = false
        home.onSearchFieldChanged = { fieldOpen = it }
        val url = "http://192.168.1.42:8080"
        home.setJoinInfo(url, qr(url, home.qrSizePx))
        home.setPlayers(listOf(Player("p1", "Maya", 1)))
        home.openSearch("cheetah top speed")
        Snap.settle(600)
        assertTrue(home.isSearchOpen)
        assertTrue(fieldOpen)
        Snap.save(activity, "home_search")
        assertTrue(home.handleBack())
        assertFalse(home.isSearchOpen)
        assertFalse(fieldOpen)
        assertFalse(home.handleBack())
    }

    @Test
    fun dpadPathAndActions() {
        val activity = tvActivity()
        val rec = Recorder()
        val home = HomeView(activity, rec)
        Snap.show(activity, home)
        leaveTouchMode(home)
        home.focusDefault()
        Snap.settle(100)
        // Mirrors ViewRootImpl: dispatch the key, and if nobody handled it, run focus navigation.
        fun press(code: Int) {
            val decor = activity.window.decorView
            val handled = decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> 0
            }
            if (!handled && dir != 0) decor.findFocus()?.focusSearch(dir)?.requestFocus(dir)
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            Snap.settle(50)
        }
        val focusedText = { (activity.window.decorView.findFocus()?.contentDescription ?: "").toString() }
        assertTrue(focusedText().startsWith("Search"))
        press(KeyEvent.KEYCODE_DPAD_UP) // nothing above the tiles: focus stays
        assertTrue(focusedText().startsWith("Search"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(focusedText().startsWith("Quests"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("quests", rec.events.last())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT) // the join panel is not a focus trap
        assertTrue(focusedText().startsWith("Lumo"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focusedText().startsWith(Favorites.all[5].title))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focusedText().startsWith(Favorites.all[5].title))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("fav:${Favorites.all[5].title}", rec.events.last())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(focusedText().startsWith("Lumo"))
        // Favorites tile jumps down to the favorites row.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(focusedText().startsWith(Favorites.all[0].title))
        Snap.save(activity, "home_focus_favorite")
    }

    @Test
    fun favoritesAreCurated() {
        assertEquals(6, Favorites.all.size)
        Favorites.all.forEach {
            assertTrue(it.url, it.url.startsWith("https://"))
            assertTrue(it.title.length <= 18)
            assertTrue(it.blurb.isNotBlank())
            assertTrue(it.colorIndex in 0..3)
        }
    }

    private fun tvActivity() = Snap.activity()

    /** A remote-driven TV is never in touch mode; Robolectric starts in it, so leave it like a key press would. */
    private fun leaveTouchMode(home: HomeView) {
        home.requestFocusFromTouch()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Palette.INK else Palette.CREAM)
        return bmp
    }
}
