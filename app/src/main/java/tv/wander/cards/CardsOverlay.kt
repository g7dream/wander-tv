package tv.wander.cards

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tv.wander.model.Card
import tv.wander.model.CardDeck

/** "Make it a card": a long page becomes 3-5 big cards the whole room can read. Left/right flips. */
class CardsOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onCardsClosed()
        fun onCardChanged(index: Int, count: Int, card: Card)
        fun onReadAloud(card: Card)
    }

    val isShowing: Boolean get() = false
    val index: Int get() = 0
    val count: Int get() = 0
    val current: Card? get() = null

    fun showLoading(pageTitle: String) {}

    fun showDeck(deck: CardDeck) {}

    fun showError(message: String) {}

    fun next() {}

    fun prev() {}

    fun handleKey(event: KeyEvent): Boolean = false

    fun close() {}
}
