package tv.wander.home

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.cards.MothDrawable
import tv.wander.ui.Async
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/**
 * The Menu-key bar while browsing: a pill of Lumo's tools along the bottom of the page.
 * Selection is driven entirely by [handleKey] (no Android focus), so the WebView underneath never
 * steals the D-pad. Left/right move, OK runs the action (zoom keeps the bar open so it can be pressed
 * again), Back or Menu closes, 8 s of quiet closes too.
 */
class LumoActionBar(context: Context, private val listener: Listener) : FrameLayout(context) {
    enum class Action { CARDS, QUEST_SUBMIT, ZOOM_OUT, ZOOM_IN, HOME, LUMO_PANEL }

    interface Listener {
        fun onAction(action: Action)
        fun onActionBarHidden()
    }

    private class Item(val action: Action, val view: LinearLayout, val icon: TextView, val label: TextView)

    val isShowing: Boolean get() = showing

    /** The highlighted action, for tests and for phones that mirror the bar. */
    val selected: Action? get() = items.getOrNull(index)?.action

    private val bar = LinearLayout(context)
    private val prompt = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold, "What shall we do with this page?")
    private val panel = LinearLayout(context)
    private var items: List<Item> = emptyList()
    private var index = 0
    private var showing = false
    private var idle: Runnable? = null
    /** Key codes whose DOWN we consumed; their UP is swallowed so it can't leak to the layer below. */
    private val swallowUp = HashSet<Int>()

    init {
        visibility = View.GONE
        clipChildren = false
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.clipChildren = false
        panel.clipToPadding = false
        prompt.setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
        // Opaque: over a white page any see-through lets the text underneath ghost through the bar.
        prompt.background = Ui.roundRect(context, Palette.INK, 14f, Palette.HAIRLINE, 1f)
        panel.addView(prompt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(10f) })

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
        bar.background = Ui.roundRect(context, Palette.NIGHT, 34f, Palette.withAlpha(Palette.LAMP, 0.35f), 1.5f)
        panel.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Centered in the strip left of the small Lumo in the bottom-right corner, so the two never touch.
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dpi(Dimens.SAFE_V + 6f)
            marginEnd = dpi(LUMO_CORNER_DP)
            marginStart = dpi(Dimens.SAFE_H)
        })
    }

    /** Shows the bar with "Make it cards" selected; "This page has it!" appears only while a quest runs. */
    fun show(questActive: Boolean) {
        val actions = buildList {
            add(Action.CARDS)
            if (questActive) add(Action.QUEST_SUBMIT)
            add(Action.ZOOM_OUT)
            add(Action.ZOOM_IN)
            add(Action.HOME)
            add(Action.LUMO_PANEL)
        }
        if (items.map { it.action } != actions) rebuild(actions)
        index = if (questActive) 1 else 0
        refresh(animate = false)
        swallowUp.clear()
        if (!showing) {
            showing = true
            visibility = View.VISIBLE
            panel.animate().cancel()
            panel.alpha = 0f
            panel.translationY = dp(28f)
            panel.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            Sfx.pop()
        }
        bumpIdle()
    }

    fun hide() {
        if (!showing) return
        showing = false
        Async.cancel(idle)
        idle = null
        panel.animate().cancel()
        panel.animate().alpha(0f).translationY(dp(18f)).setDuration(160).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!showing) visibility = View.GONE }
            .start()
        listener.onActionBarHidden()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) return swallowUp.remove(code)
        if (event.action != KeyEvent.ACTION_DOWN || !showing) return false
        bumpIdle()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit // the page stays still under the bar
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) trigger()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.repeatCount == 0) {
                    Sfx.back()
                    hide()
                }
            }
            else -> {
                // Any other key (Play/Pause, Rewind...) closes the bar and still does its usual job.
                hide()
                return false
            }
        }
        swallowUp.add(code)
        return true
    }

    /** Runs [action] as if it had been chosen on the bar (phones use this path too). */
    fun perform(action: Action) {
        hide()
        listener.onAction(action)
    }

    override fun onDetachedFromWindow() {
        Async.cancel(idle)
        idle = null
        super.onDetachedFromWindow()
    }

    private fun move(delta: Int) {
        val next = (index + delta).coerceIn(0, items.lastIndex)
        if (next == index) {
            // A tiny nudge says "that's the end".
            val v = items[index].view
            v.animate().translationX(dp(6f) * delta).setDuration(70).withEndAction {
                v.animate().translationX(0f).setDuration(120).start()
            }.start()
            return
        }
        index = next
        refresh(animate = true)
        Sfx.focus()
    }

    private fun trigger() {
        val item = items.getOrNull(index) ?: return
        Sfx.select()
        val rest = if (index == items.indexOf(item)) 1.06f else 1f
        item.view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            item.view.animate().scaleX(rest).scaleY(rest).setDuration(120).start()
        }.start()
        if (item.action == Action.ZOOM_IN || item.action == Action.ZOOM_OUT) {
            // Zoom is something you press a few times: the bar stays open for it.
            listener.onAction(item.action)
        } else {
            perform(item.action)
        }
    }

    private fun bumpIdle() {
        Async.cancel(idle)
        idle = Async.mainDelayed(IDLE_MS) {
            idle = null
            if (showing) hide()
        }
    }

    private fun rebuild(actions: List<Action>) {
        bar.removeAllViews()
        items = actions.mapIndexed { i, a ->
            val (icon, label) = when (a) {
                Action.CARDS -> "🃏" to "Make it cards"
                Action.QUEST_SUBMIT -> "🧭" to "This page has it!"
                Action.ZOOM_OUT -> "A−" to "Smaller"
                Action.ZOOM_IN -> "A+" to "Bigger"
                Action.HOME -> "🏠" to "Home"
                Action.LUMO_PANEL -> "" to "Lumo panel"
            }
            val zoom = a == Action.ZOOM_IN || a == Action.ZOOM_OUT
            val iconView = Ui.text(context, if (zoom) TypeScale.H2 else 26f, Palette.CREAM, if (zoom) Fonts.display else Fonts.body, icon).apply {
                gravity = Gravity.CENTER
                // Lumo's own moth (the same one the cards and quests draw), not a stock butterfly.
                if (a == Action.LUMO_PANEL) setCompoundDrawables(null, MothDrawable(Palette.LAMP, dpi(32f)), null, null)
            }
            val labelView = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, label).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = dpi(if (zoom) 84f else 96f)
                setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(9f))
                addView(iconView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(34f)))
                addView(labelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
                contentDescription = label
            }
            bar.addView(cell, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dpi(4f)
            })
            Item(a, cell, iconView, labelView)
        }
    }

    private fun refresh(animate: Boolean) {
        items.forEachIndexed { i, it ->
            val on = i == index
            it.view.background = if (on) {
                Ui.roundRect(context, Palette.withAlpha(Palette.LAMP, 0.16f), 24f, Palette.LAMP, Dimens.FOCUS_STROKE)
            } else {
                null
            }
            it.label.setTextColor(if (on) Palette.CREAM else Palette.MUTED)
            val s = if (on) 1.06f else 1f
            if (animate) {
                it.view.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(if (on) OvershootInterpolator(1.4f) else DecelerateInterpolator()).start()
            } else {
                it.view.scaleX = s
                it.view.scaleY = s
            }
        }
    }

    private companion object {
        const val IDLE_MS = 8_000L
        /** Room kept free for the small Lumo in the bottom-right corner while browsing. */
        const val LUMO_CORNER_DP = 150f
    }
}
