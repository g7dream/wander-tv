package tv.wander.browse

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tv.wander.model.PageText
import tv.wander.model.Player

/** The browser: a WebView, the shared cursor and a slim title strip. Driven by the remote and by phones. */
class BrowseLayer(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String, title: String)
        fun onUserClick()
        fun onUserScroll()
        fun onTextFieldFocus(focused: Boolean, label: String?)
    }

    val currentUrl: String get() = ""
    val currentTitle: String get() = ""

    /** Opens a URL, or searches when [input] doesn't look like one. [by] tints the "driving" chip. */
    fun open(input: String, by: Player? = null) {}

    /** WebView history back. False when there is nothing to go back to. */
    fun goBack(): Boolean = false

    /** Remote keys while browsing: D-pad moves the cursor, center taps, rewind/fast-forward page. */
    fun handleKey(event: KeyEvent): Boolean = false

    /** Phone touchpad delta in phone CSS pixels. */
    fun moveCursor(dx: Float, dy: Float, by: Player?) {}

    fun tap(by: Player?) {}

    fun scroll(dx: Float, dy: Float) {}

    /** Types into the focused page field; [submit] presses Enter / submits its form. */
    fun typeText(text: String, submit: Boolean) {}

    fun zoom(delta: Int) {}

    /** Readable page text via Readability.js. Callback on the main thread; null if extraction failed. */
    fun extract(callback: (PageText?) -> Unit) {
        callback(null)
    }

    fun onResume() {}
    fun onPause() {}
    fun destroy() {}
}
