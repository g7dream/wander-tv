package tv.wander.rescue

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tv.wander.model.DriftSignal
import tv.wander.model.RescueOption

/** The gentle interruption: Lumo's line, the measured "why", and two ways out. Back = "we're fine". */
class RescueOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onRescueChoice(option: RescueOption)
        fun onRescueDismiss()
    }

    val isShowing: Boolean get() = false

    fun show(signal: DriftSignal, line: String, options: List<RescueOption>) {}

    fun hide() {}

    fun handleKey(event: KeyEvent): Boolean = false

    /** From a phone: 0 or 1 picks an option, -1 dismisses. */
    fun choose(index: Int) {}
}
