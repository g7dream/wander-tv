package tv.wander.rescue

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.model.DriftSignal
import tv.wander.model.RescueOption
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/**
 * The gentle interruption: Lumo's line, the measured "why", and two ways out. Back = "we're fine".
 *
 * Content lives in the left two-thirds; the bottom-right corner stays clear for big Lumo (the
 * CompanionLayer draws above this overlay). While showing, [handleKey] consumes every key: D-pad
 * left/right moves between the options, Center/OK chooses, Back dismisses. Choices and dismissals
 * fire on key-up so the release never leaks to the layer underneath. Each signal resolves exactly
 * once: after a choice or dismissal, further keys and phone choices are ignored until the next show.
 */
class RescueOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onRescueChoice(option: RescueOption)
        fun onRescueDismiss()
    }

    val isShowing: Boolean get() = showing

    private var showing = false
    private var options: List<RescueOption> = emptyList()
    private val cards = ArrayList<View>(2)
    private var focusIndex = 0
    private var downKey = 0

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val eyebrow = Ui.text(context, TypeScale.CAPTION, Palette.LAMP, Fonts.bodyBold, "A NUDGE FROM LUMO").apply {
        letterSpacing = 0.14f
    }
    private val headline = Ui.text(context, TypeScale.H1, Palette.CREAM, Fonts.display).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 0.98f)
    }
    private val detail = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold)
    private val privacy = Ui.text(context, TypeScale.CAPTION, Palette.withAlpha(Palette.MUTED, 0.75f), Fonts.body, "Measured on this TV from timing alone")
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private val hints = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        visibility = View.GONE
        isClickable = true // swallow touches meant for the page underneath
        isFocusable = false
        clipChildren = false
        background = ScrimDrawable(context)

        content.addView(eyebrow, LinearLayout.LayoutParams(-2, -2))
        content.addView(headline, LinearLayout.LayoutParams(dpi(CONTENT_W), -2).apply { topMargin = dpi(14f) })

        val detailRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detailRow.addView(View(context).apply { background = Ui.roundRect(context, Palette.LAMP, 4f) }, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { rightMargin = dpi(10f) })
        detailRow.addView(detail, LinearLayout.LayoutParams(-2, -2))
        detailRow.addView(Ui.text(context, TypeScale.CAPTION, Palette.HAIRLINE, Fonts.bodyBold, "   |   "))
        detailRow.addView(privacy, LinearLayout.LayoutParams(-2, -2))
        content.addView(detailRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(16f) })

        content.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(28f) })

        hints.addView(keyCap("OK"))
        hints.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Choose"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(10f); rightMargin = dpi(28f) })
        hints.addView(keyCap("Back"))
        hints.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "We're fine, keep going"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(10f) })
        content.addView(hints, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(26f) })

        addView(content, LayoutParams(dpi(CONTENT_W + 40f), LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dpi(Dimens.SAFE_H + 28f)
            bottomMargin = dpi(24f)
        })
    }

    /** Shows [line] (Lumo's words, usually the AI's), the signal's measured detail, and up to two [options]. */
    fun show(signal: DriftSignal, line: String, options: List<RescueOption>) {
        this.options = options.take(2)
        headline.text = line.trim().ifEmpty { signal.headline }
        detail.text = signal.detail.trim().ifEmpty { "Lumo noticed a pattern" }
        buildCards()
        focusIndex = 0
        downKey = 0
        val wasShowing = showing
        showing = true
        visibility = View.VISIBLE
        if (!wasShowing) {
            alpha = 0f
            content.translationY = dp(26f)
            animate().cancel()
            content.animate().cancel()
            animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator()).start()
            content.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        focusCard(0)
    }

    fun hide() {
        if (!showing && visibility != View.VISIBLE) return
        showing = false
        downKey = 0
        animate().cancel()
        content.animate().cancel()
        animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!showing) {
                    visibility = View.GONE
                    cards.forEach { it.clearFocus() }
                }
            }.start()
        content.animate().translationY(dp(12f)).setDuration(EXIT_MS).start()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!showing) return false
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(+1)
                    else -> if (event.repeatCount == 0 && (isSelect(code) || isBack(code))) downKey = code
                }
            }
            KeyEvent.ACTION_UP -> {
                if (code == downKey) {
                    downKey = 0
                    if (isSelect(code)) choose(if (options.isEmpty()) -1 else focusIndex)
                    else if (isBack(code)) choose(-1)
                }
            }
        }
        return true
    }

    /** From a phone: 0 or 1 picks an option, -1 dismisses. Anything else is ignored. */
    fun choose(index: Int) {
        if (!showing) return
        if (index == -1) {
            Sfx.back()
            hide()
            listener.onRescueDismiss()
            return
        }
        val option = options.getOrNull(index) ?: return
        Sfx.select()
        cards.getOrNull(index)?.let { card ->
            card.animate().cancel()
            card.animate().scaleX(1.1f).scaleY(1.1f).setDuration(110).withEndAction {
                card.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        }
        hide()
        listener.onRescueChoice(option)
    }

    // ------------------------------------------------------------------ internals

    private fun isSelect(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun isBack(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE ||
        code == KeyEvent.KEYCODE_BUTTON_B

    private fun moveFocus(delta: Int) {
        if (cards.isEmpty()) return
        val next = (focusIndex + delta).coerceIn(0, cards.size - 1)
        if (next != focusIndex) focusCard(next) else nudge(cards[focusIndex], delta)
    }

    private fun focusCard(index: Int) {
        if (cards.isEmpty()) return
        focusIndex = index.coerceIn(0, cards.size - 1)
        val card = cards[focusIndex]
        if (!card.requestFocus()) {
            // Not focusable right now (e.g. touch mode): still show where OK will land.
            card.onFocusChangeListener?.onFocusChange(card, true)
            cards.forEachIndexed { i, c -> if (i != focusIndex) c.onFocusChangeListener?.onFocusChange(c, false) }
        }
    }

    /** A small bump at the end of the row, so the remote never feels dead. */
    private fun nudge(view: View, delta: Int) {
        val d = dp(8f) * delta
        view.animate().translationX(d).setDuration(70).withEndAction {
            view.animate().translationX(0f).setDuration(120).start()
        }.start()
    }

    private fun buildCards() {
        row.removeAllViews()
        cards.clear()
        for ((i, option) in options.withIndex()) {
            val card = optionCard(option)
            card.setOnClickListener { choose(i) }
            // MATCH_PARENT inside a wrap-content row: both cards take the taller one's height.
            row.addView(card, LinearLayout.LayoutParams(dpi(CARD_W), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                if (i > 0) leftMargin = dpi(CARD_GAP)
            })
            cards += card
        }
        // Irregular layouts get explicit D-pad neighbors (used if keys ever reach the views directly).
        for (c in cards) c.id = View.generateViewId()
        for (i in cards.indices) {
            val c = cards[i]
            c.nextFocusLeftId = cards[maxOf(0, i - 1)].id
            c.nextFocusRightId = cards[minOf(cards.size - 1, i + 1)].id
            c.nextFocusUpId = c.id
            c.nextFocusDownId = c.id
        }
        hints.getChildAt(0)?.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
        hints.getChildAt(1)?.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * "Saturn up close 🪐" must never wrap with the emoji alone on the second line: the space before a trailing
     * emoji becomes a no-break space, so the emoji travels with the last word.
     */
    private fun glueTrailingEmoji(label: String): String {
        val t = label.trim()
        val cut = t.lastIndexOf(' ')
        if (cut <= 0) return t
        val tail = t.substring(cut + 1)
        return if (tail.isNotEmpty() && tail.none { it.isLetterOrDigit() }) t.substring(0, cut) + ' ' + tail else t
    }

    private fun optionCard(option: RescueOption): View {
        val ctx = context
        val accent = accentFor(option.kind)
        val rest = Ui.roundRect(ctx, Palette.DUSK, Dimens.RADIUS_BIG, Palette.HAIRLINE, 1.5f)
        val lit = Ui.roundRect(ctx, RAISED, Dimens.RADIUS_BIG, Palette.withAlpha(accent, 0.55f), 1.5f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(20f))
            background = rest
            minimumHeight = dpi(CARD_MIN_H)
        }
        // Header: the emoji badge beside the kind and the label.
        val badge = FrameLayout(ctx).apply { background = Ui.roundRect(ctx, Palette.withAlpha(accent, 0.18f), 16f) }
        badge.addView(Ui.text(ctx, 26f, Palette.CREAM, Fonts.body, emojiFor(option.kind)).apply { gravity = Gravity.CENTER },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(badge, LinearLayout.LayoutParams(dpi(52f), dpi(52f)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(ctx, TypeScale.CAPTION, accent, Fonts.bodyBold, kindLabel(option.kind)).apply {
            letterSpacing = 0.1f
            maxLines = 1
        })
        titles.addView(Ui.text(ctx, TypeScale.H2, Palette.CREAM, Fonts.display, glueTrailingEmoji(option.label)).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 0.92f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(14f) })
        card.addView(top, LinearLayout.LayoutParams(-1, -2))
        card.addView(Ui.text(ctx, TypeScale.BODY, Palette.MUTED, Fonts.body, option.detail).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        Ui.makeFocusable(card, Dimens.RADIUS_BIG, 1.06f) { focused ->
            card.background = if (focused) lit else rest
            if (focused) focusIndex = cards.indexOf(card).coerceAtLeast(0)
        }
        card.isFocusableInTouchMode = true
        return card
    }

    private fun keyCap(label: String): TextView = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold, label).apply {
        background = Ui.roundRect(context, Palette.NIGHT, 10f, Palette.HAIRLINE, 1.5f)
        setPadding(dpi(12f), dpi(5f), dpi(12f), dpi(6f))
    }

    private fun emojiFor(kind: RescueOption.Kind): String = when (kind) {
        RescueOption.Kind.QUEST -> "🧭" // compass
        RescueOption.Kind.DETOUR -> "🔭" // telescope
        RescueOption.Kind.WIND_DOWN -> "🌙" // crescent moon
    }

    private fun kindLabel(kind: RescueOption.Kind): String = when (kind) {
        RescueOption.Kind.QUEST -> "FAMILY QUEST"
        RescueOption.Kind.DETOUR -> "CURIOUS DETOUR"
        RescueOption.Kind.WIND_DOWN -> "WIND DOWN"
    }

    private fun accentFor(kind: RescueOption.Kind): Int = when (kind) {
        RescueOption.Kind.QUEST -> Palette.LAMP
        RescueOption.Kind.DETOUR -> Palette.MINT
        RescueOption.Kind.WIND_DOWN -> Palette.SKY
    }

    /** The scrim: SCRIM over the page, a soft vignette, and Lumo's lamp warming the bottom-right corner. */
    private class ScrimDrawable(context: Context) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var vignette: RadialGradient? = null
        private var lamp: RadialGradient? = null
        private val unit = context.dp(1f)

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            vignette = RadialGradient(w * 0.38f, h * 0.48f, maxOf(w, h) * 0.78f,
                intArrayOf(0x00000000, Palette.withAlpha(0xFF05030A.toInt(), 0.55f)),
                floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
            lamp = RadialGradient(w - 150f * unit, h - 110f * unit, 360f * unit,
                intArrayOf(Palette.withAlpha(Palette.LAMP, 0.16f), 0x00000000),
                null, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.shader = null
            paint.color = Palette.SCRIM
            canvas.drawRect(b, paint)
            vignette?.let { paint.shader = it; canvas.drawRect(b, paint) }
            lamp?.let { paint.shader = it; canvas.drawRect(b, paint) }
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val CONTENT_W = 620f
        const val CARD_W = 300f
        const val CARD_GAP = 20f
        const val CARD_MIN_H = 158f
        const val ENTER_MS = 220L
        const val EXIT_MS = 160L
        val RAISED = 0xFF30264A.toInt()
    }
}
