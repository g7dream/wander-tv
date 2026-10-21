package tv.wander.companion

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tv.wander.model.SessionStats

/** The Lumo tile's panel: tonight's trail, session stats, AI status, and the nudge / demo toggles. */
class CompanionPanel(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onToggleNudges(on: Boolean)
        fun onToggleDemo(on: Boolean)
        fun onCompanionPanelClosed()
    }

    val isShowing: Boolean get() = false

    fun show(stats: SessionStats, trail: List<String>, aiOnline: Boolean, nudgesOn: Boolean, demoMode: Boolean) {}

    fun hide() {}

    fun handleKey(event: KeyEvent): Boolean = false
}
