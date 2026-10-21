package tv.wander.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.model.SessionStats
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/**
 * The Lumo tile's panel: tonight's trail, session stats, AI status, and the nudge / demo toggles.
 *
 * Modal: while showing, [handleKey] consumes every key. Up/down move between the two toggles,
 * Center/OK flips the focused one (and calls the listener), Back or Menu closes (on key-up, so the
 * release never reaches the screen underneath). The panel sits on the left; the bottom-right corner
 * stays free for big Lumo, who "presents" it from the CompanionLayer above.
 *
 * `trail` is page titles in the order they were visited (oldest first); it is shown newest first,
 * with repeated neighbors folded together.
 */
class CompanionPanel(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onToggleNudges(on: Boolean)
        fun onToggleDemo(on: Boolean)
        fun onCompanionPanelClosed()
    }

    val isShowing: Boolean get() = showing

    private var showing = false
    private var downKey = 0
    private var focusIndex = 0

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.roundRect(context, Palette.NIGHT, Dimens.RADIUS_BIG, Palette.HAIRLINE, 1.5f)
        setPadding(dpi(34f), dpi(20f), dpi(34f), dpi(20f))
        clipChildren = false
        clipToPadding = false
    }
    private val subtitle = Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val aiChip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val aiDot = View(context)
    private val aiText = Ui.text(context, TypeScale.CAPTION, Palette.MINT, Fonts.bodyBold)
    private val trailBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statMinutes = StatTile(context)
    private val statPages = StatTile(context)
    private val statDwell = StatTile(context)
    private val nudgesRow = ToggleRow(context, "Gentle nudges", "I'll speak up when we drift.")
    private val demoRow = ToggleRow(context, "Demo mode", "Quick timers for a live demo.")
    private val toggles = listOf(nudgesRow, demoRow)

    init {
        visibility = View.GONE
        isClickable = true
        isFocusable = false
        clipChildren = false
        background = PanelScrim(context)

        // ---- header: title + AI status, then Lumo's line
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(context, TypeScale.DISPLAY, Palette.CREAM, Fonts.display, "Lumo"), LinearLayout.LayoutParams(0, -2, 1f))
        aiChip.addView(aiDot, LinearLayout.LayoutParams(dpi(10f), dpi(10f)).apply { rightMargin = dpi(10f) })
        aiChip.addView(aiText)
        aiChip.setPadding(dpi(14f), dpi(7f), dpi(16f), dpi(8f))
        header.addView(aiChip, LinearLayout.LayoutParams(-2, -2))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        panel.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })

        // ---- body: trail on the left, stats + toggles on the right
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(sectionTitle("Tonight's trail"))
        left.addView(trailBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        body.addView(left, LinearLayout.LayoutParams(dpi(LEFT_W), -2))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        right.addView(sectionTitle("So far"))
        val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statMinutes, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(statPages, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10f) })
        stats.addView(statDwell, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10f) })
        right.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        right.addView(nudgesRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        right.addView(demoRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        body.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(30f) })
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12f) })

        // ---- footer: privacy note on the left, key hints on the right
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "🔒  Your trail stays on this TV"),
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(keyCap("OK"))
        footer.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Switch"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(8f); rightMargin = dpi(20f) })
        footer.addView(keyCap("Back"))
        footer.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Close"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(8f) })
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })

        addView(panel, LayoutParams(dpi(PANEL_W), LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V)
            bottomMargin = dpi(Dimens.SAFE_V)
        })

        nudgesRow.setOnClickListener { flip(0) }
        demoRow.setOnClickListener { flip(1) }
        nudgesRow.id = View.generateViewId()
        demoRow.id = View.generateViewId()
        nudgesRow.nextFocusDownId = demoRow.id
        nudgesRow.nextFocusUpId = nudgesRow.id
        demoRow.nextFocusUpId = nudgesRow.id
        demoRow.nextFocusDownId = demoRow.id
        for (t in toggles) {
            t.nextFocusLeftId = t.id
            t.nextFocusRightId = t.id
        }
    }

    fun show(stats: SessionStats, trail: List<String>, aiOnline: Boolean, nudgesOn: Boolean, demoMode: Boolean) {
        val stops = cleanTrail(trail)
        subtitle.text = Lines.panelIntro(stops.size)
        setAi(aiOnline)
        val minutes = stats.sessionMinutes.coerceAtLeast(0)
        val pages = stats.pagesTotal.coerceAtLeast(0)
        statMinutes.set(if (minutes == 0 && pages > 0) "<1" else minutes.toString(), if (minutes == 1) "minute" else "minutes")
        statPages.set(pages.toString(), if (pages == 1) "page" else "pages")
        statDwell.set(if (pages == 0) "–" else dwell(stats.avgDwellSec), "per page")
        nudgesRow.setOn(nudgesOn, animate = false)
        demoRow.setOn(demoMode, animate = false)
        buildTrail(stops)

        val wasShowing = showing
        showing = true
        downKey = 0
        visibility = View.VISIBLE
        if (!wasShowing) {
            alpha = 0f
            panel.translationY = dp(24f)
            animate().cancel()
            panel.animate().cancel()
            animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            panel.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        focusToggle(0)
    }

    fun hide() {
        if (!showing && visibility != View.VISIBLE) return
        showing = false
        downKey = 0
        animate().cancel()
        panel.animate().cancel()
        animate().alpha(0f).setDuration(160).withEndAction {
            if (!showing) {
                visibility = View.GONE
                toggles.forEach { it.clearFocus() }
            }
        }.start()
        panel.animate().translationY(dp(12f)).setDuration(160).start()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!showing) return false
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> focusToggle(focusIndex - 1)
                KeyEvent.KEYCODE_DPAD_DOWN -> focusToggle(focusIndex + 1)
                else -> if (event.repeatCount == 0 && (isSelect(code) || isClose(code))) downKey = code
            }
            KeyEvent.ACTION_UP -> if (code == downKey) {
                downKey = 0
                if (isSelect(code)) flip(focusIndex) else if (isClose(code)) close()
            }
        }
        return true
    }

    // ------------------------------------------------------------------ internals

    private fun close() {
        if (!showing) return
        Sfx.back()
        hide()
        listener.onCompanionPanelClosed()
    }

    private fun flip(index: Int) {
        if (!showing) return
        val row = toggles.getOrNull(index) ?: return
        focusToggle(index)
        val on = !row.isOn
        row.setOn(on, animate = true)
        Sfx.select()
        if (index == 0) listener.onToggleNudges(on) else listener.onToggleDemo(on)
    }

    private fun focusToggle(index: Int) {
        val i = index.coerceIn(0, toggles.size - 1)
        focusIndex = i
        val row = toggles[i]
        if (!row.requestFocus()) {
            // Not focusable right now (touch mode, detached): still show where OK will land.
            row.onFocusChangeListener?.onFocusChange(row, true)
            toggles.forEachIndexed { j, t -> if (j != i) t.onFocusChangeListener?.onFocusChange(t, false) }
        }
    }

    private fun isSelect(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun isClose(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE ||
        code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_MENU

    private fun setAi(online: Boolean) {
        val color = if (online) Palette.MINT else Palette.MUTED
        aiText.text = if (online) "Amazon Bedrock connected" else "Offline mode: on-device smarts"
        aiText.setTextColor(color)
        aiDot.background = Ui.roundRect(context, color, 5f)
        aiChip.background = Ui.roundRect(context, Palette.withAlpha(color, 0.12f), 18f, Palette.withAlpha(color, 0.4f), 1.2f)
    }

    private fun buildTrail(stops: List<String>) {
        trailBox.removeAllViews()
        if (stops.isEmpty()) {
            for ((i, w) in GHOST_WIDTHS.withIndex()) {
                trailBox.addView(TrailStop(context, null, first = i == 0, last = i == GHOST_WIDTHS.size - 1, ghost = true, ghostWidthDp = w),
                    LinearLayout.LayoutParams(-1, dpi(STOP_H)))
            }
            trailBox.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body,
                "Open a page and I'll keep a little map of where we wander.").apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            return
        }
        val fits = stops.size <= MAX_ROWS
        val shown = if (fits) stops else stops.take(MAX_ROWS - 1)
        val earlier = stops.size - shown.size
        for ((i, title) in shown.withIndex()) {
            val last = i == shown.size - 1 && earlier == 0
            // The newest stop (the one that matters most) may wrap to two lines, so its row grows to fit.
            trailBox.addView(TrailStop(context, title, first = i == 0, last = last, ghost = false),
                LinearLayout.LayoutParams(-1, if (i == 0) LinearLayout.LayoutParams.WRAP_CONTENT else dpi(STOP_H)))
        }
        if (earlier > 0) {
            trailBox.addView(TrailStop(context, if (earlier == 1) "and 1 earlier stop" else "and $earlier earlier stops", first = false, last = true, ghost = true),
                LinearLayout.LayoutParams(-1, dpi(STOP_H)))
        }
    }

    private fun cleanTrail(trail: List<String>): List<String> {
        val out = ArrayList<String>(trail.size)
        var prev: String? = null
        for (t in trail.asReversed()) {
            val title = t.trim().replace(WHITESPACE, " ")
            if (title.isEmpty() || title == prev) continue
            out += title
            prev = title
        }
        return out
    }

    private fun dwell(sec: Int): String = when {
        sec < 60 -> "${sec.coerceAtLeast(0)} s"
        sec < 3600 -> "${(sec + 30) / 60} min"
        else -> "${sec / 3600} h"
    }

    private fun sectionTitle(text: String): TextView = Ui.text(context, TypeScale.H2, Palette.CREAM, Fonts.display, text)

    private fun keyCap(label: String): TextView = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold, label).apply {
        background = Ui.roundRect(context, Palette.DUSK, 10f, Palette.HAIRLINE, 1.5f)
        setPadding(dpi(11f), dpi(4f), dpi(11f), dpi(5f))
    }

    /** One stat: a big lamp-colored number and a small label. */
    private class StatTile(context: Context) : LinearLayout(context) {
        private val value = Ui.text(context, 28f, Palette.LAMP, Fonts.display)
        private val label = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body)

        init {
            orientation = VERTICAL
            background = Ui.roundRect(context, Palette.DUSK, Dimens.RADIUS)
            setPadding(context.dpi(14f), context.dpi(9f), context.dpi(8f), context.dpi(10f))
            addView(value)
            addView(label, LayoutParams(-2, -2).apply { topMargin = context.dpi(2f) })
            value.maxLines = 1
            label.maxLines = 1
        }

        fun set(v: String, l: String) {
            value.text = v
            label.text = l
        }
    }

    /** A focusable row: title and On/Off with a lamp-lit switch, and a one-line explanation. */
    private class ToggleRow(context: Context, title: String, explain: String) : LinearLayout(context) {
        var isOn = false
            private set
        private val knobTravel = context.dp(SWITCH_W - SWITCH_H)
        private val track = FrameLayout(context)
        private val knob = View(context)
        private val state = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold)
        private val rest = Ui.roundRect(context, Palette.DUSK, Dimens.RADIUS)
        private val lit = Ui.roundRect(context, RAISED, Dimens.RADIUS)

        init {
            orientation = VERTICAL
            background = rest
            setPadding(context.dpi(18f), context.dpi(8f), context.dpi(14f), context.dpi(9f))
            val top = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.bodyBold, title).apply { maxLines = 1 }, LayoutParams(0, -2, 1f))
            top.addView(state, LayoutParams(-2, -2).apply { rightMargin = context.dpi(10f) })
            val pad = context.dpi(4f)
            track.setPadding(pad, pad, pad, pad)
            track.addView(knob, FrameLayout.LayoutParams(context.dpi(SWITCH_H - 8f), context.dpi(SWITCH_H - 8f), Gravity.START or Gravity.CENTER_VERTICAL))
            top.addView(track, LayoutParams(context.dpi(SWITCH_W), context.dpi(SWITCH_H)))
            addView(top, LayoutParams(-1, -2))
            addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, explain).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(-1, -2).apply { topMargin = context.dpi(2f) })
            Ui.makeFocusable(this, Dimens.RADIUS, 1.04f) { focused -> background = if (focused) lit else rest }
            isFocusableInTouchMode = true
            setOn(false, animate = false)
        }

        fun setOn(on: Boolean, animate: Boolean) {
            isOn = on
            val ctx = context
            track.background = Ui.roundRect(ctx, if (on) Palette.LAMP else Palette.HAIRLINE, SWITCH_H / 2f)
            knob.background = Ui.roundRect(ctx, if (on) Palette.CREAM else Palette.MUTED, (SWITCH_H - 8f) / 2f)
            state.text = if (on) "On" else "Off"
            state.setTextColor(if (on) Palette.LAMP else Palette.MUTED)
            val x = if (on) knobTravel else 0f
            knob.animate().cancel()
            if (animate) {
                knob.animate().translationX(x).setDuration(180).setInterpolator(OvershootInterpolator(1.4f)).start()
            } else {
                knob.translationX = x
            }
        }
    }

    /** One stop on the trail: a dot on a vertical line and a chip with the page title. */
    private class TrailStop(
        context: Context,
        title: String?,
        private val first: Boolean,
        private val last: Boolean,
        private val ghost: Boolean,
        ghostWidthDp: Float = 0f,
    ) : FrameLayout(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.HAIRLINE
            strokeWidth = context.dp(2f)
            style = Paint.Style.STROKE
            if (ghost) pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(5f)), 0f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.withAlpha(Palette.LAMP, 0.22f) }
        private val lineX = context.dp(10f)

        init {
            setWillNotDraw(false)
            // The newest stop wraps to two lines instead of cutting its title short; older ones stay one line.
            val now = first && !ghost
            val chipH = context.dpi(STOP_H - 8f)
            val chip = Ui.text(context, if (ghost && title != null) TypeScale.CAPTION else TypeScale.BODY, if (ghost) Palette.MUTED else Palette.CREAM, if (now) Fonts.bodyBold else Fonts.body, title ?: "").apply {
                maxLines = if (now) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                val padV = if (now) context.dpi(3f) else 0
                setPadding(context.dpi(if (ghost && title != null) 4f else 14f), padV, context.dpi(14f), padV)
                if (now) {
                    minHeight = chipH
                    setLineSpacing(0f, 1.04f)
                }
                maxWidth = context.dpi(LEFT_W - CHIP_X)
                background = when {
                    title == null -> Ui.roundRect(context, 0x00000000, 14f, Palette.withAlpha(Palette.HAIRLINE, 0.9f), 1.5f)
                    ghost -> null
                    now -> Ui.roundRect(context, RAISED, 14f, Palette.withAlpha(Palette.LAMP, 0.55f), 1.5f)
                    else -> Ui.roundRect(context, Palette.DUSK, 14f)
                }
            }
            val w = if (title == null) context.dpi(ghostWidthDp) else LayoutParams.WRAP_CONTENT
            addView(chip, LayoutParams(w, if (now) LayoutParams.WRAP_CONTENT else chipH, Gravity.CENTER_VERTICAL).apply {
                leftMargin = context.dpi(CHIP_X)
                // Same 4dp breathing room above and below as a fixed STOP_H row, when the row wraps its chip.
                if (now) {
                    topMargin = context.dpi(4f)
                    bottomMargin = context.dpi(4f)
                }
            })
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height / 2f
            val top = if (first) cy else 0f
            val bottom = if (last) cy else height.toFloat()
            if (bottom > top) canvas.drawLine(lineX, top, lineX, bottom, line)
            if (first && !ghost) {
                canvas.drawCircle(lineX, cy, dp(10f), halo)
                dot.color = Palette.LAMP
                canvas.drawCircle(lineX, cy, dp(6f), dot)
            } else {
                dot.color = if (ghost) Palette.HAIRLINE else Palette.MUTED
                canvas.drawCircle(lineX, cy, dp(4.5f), dot)
            }
        }
    }

    /** Dims whatever is behind the panel and lets Lumo's lamp glow from the right. */
    private class PanelScrim(context: Context) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glow: RadialGradient? = null
        private val unit = context.dp(1f)

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            glow = RadialGradient(w - 140f * unit, h - 120f * unit, 420f * unit,
                intArrayOf(Palette.withAlpha(Palette.LAMP, 0.14f), 0x00000000), null, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) {
            paint.shader = null
            paint.color = Palette.withAlpha(Palette.INK, 0.94f)
            canvas.drawRect(bounds, paint)
            glow?.let { paint.shader = it; canvas.drawRect(bounds, paint) }
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PANEL_W = 650f
        const val LEFT_W = 236f
        const val CHIP_X = 28f
        const val STOP_H = 40f
        const val MAX_ROWS = 5
        const val SWITCH_W = 54f
        const val SWITCH_H = 30f
        val RAISED = 0xFF30264A.toInt()
        val GHOST_WIDTHS = floatArrayOf(180f, 140f, 108f)
        val WHITESPACE = Regex("\\s+")
    }
}
