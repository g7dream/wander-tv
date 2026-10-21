package tv.wander.home

import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout
import tv.wander.model.Player

/** Home screen: four big tiles (Search, Quests, Favorites, Lumo), favorites row, and the "join the couch" QR panel. */
class HomeView(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onSearchQuery(query: String)
        fun onOpenQuests()
        fun onOpenFavorite(favorite: Favorite)
        fun onOpenCompanion()
    }

    /** [url] is the phone-join address (null when the TV has no LAN address); [qr] encodes it. */
    fun setJoinInfo(url: String?, qr: Bitmap?) {}
    fun setPlayers(players: List<Player>) {}
    fun setAiOnline(online: Boolean) {}
    /** One line under the title, e.g. "Speed Safari: 2 of 3 found". Null hides it. */
    fun setQuestLine(text: String?) {}
    fun focusDefault() {}
}
