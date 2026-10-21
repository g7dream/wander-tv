package tv.wander.companion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import tv.wander.model.Mood
import tv.wander.ui.Async
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import kotlin.math.max
import kotlin.math.min

/**
 * Lumo plus its speech bubble, pinned to the bottom-right safe corner, above every other layer.
 *
 * Add it last, MATCH_PARENT, over everything. It never takes focus and never consumes touches or
 * keys. [say] with a mood shows that mood only while the line is on screen, then Lumo eases back
 * to the resting [mood]. Lines reveal with a quick typewriter and hide after `holdMs` (counted from
 * the end of the reveal; `holdMs <= 0` keeps the line up until the next [say] or [hush]).
 *
 * [setPlacement] keeps the bubble out of the way of whatever screen is up: next to Lumo's face while
 * browsing ([Placement.CORNER]), along the bottom of Home ([Placement.FOOTER]), or hidden while a
 * full-screen overlay owns the TV ([Placement.QUIET]).
 */
class CompanionLayer(context: Context) : FrameLayout(context) {
    /** Where the speech bubble goes. */
    enum class Placement {
        /** Next to Lumo's face in the bottom-right corner (browsing). */
        CORNER,

        /** One wide strip along the bottom of Home, left of big Lumo, clear of the tiles and favorites. */
        FOOTER,

        /**
         * No bubble while a full-screen overlay (cards, quests, rescue, panel, action bar) owns the screen.
         * A line said meanwhile still sets Lumo's mood, and it shows once the bubble may come back, if it
         * hasn't run out by then.
         */
        QUIET,
    }

    /** Lumo's resting mood. Setting it shows immediately and ends any per-line mood. */
    var mood: Mood = Mood.CURIOUS
        set(value) {
            field = value
            lineMood = null
            lumo.mood = value
        }

    private val lumo = LumoView(context)
    private val bubble = BubbleView(context)
    private val text: TextView = Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.body)
    private val hidden = ForegroundColorSpan(0x00000000)

    private var lineMood: Mood? = null
    private var reveal: ValueAnimator? = null
    private var sizeAnim: ValueAnimator? = null
    private var hideTask: Runnable? = null
    private var bubbleShowing = false
    private var scaleNow = SMALL_SCALE
    private var big = false

    /** Where the bubble currently goes; see [setPlacement]. */
    var placement: Placement = Placement.CORNER
        private set

    /** The line Lumo is saying (shown, or held back while QUIET); null when Lumo is quiet. */
    private var lineText: String? = null
    /** Uptime when [lineText] runs out; Long.MAX_VALUE keeps it until the next say() or hush(). */
    private var lineEndsAt = 0L

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        isClickable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val boxPx = dpi(BIG_DP)
        lumo.pivotX = boxPx.toFloat()
        lumo.pivotY = boxPx.toFloat()
        addView(lumo, LayoutParams(boxPx, boxPx, Gravity.BOTTOM or Gravity.END).apply {
            // Place the moth itself (not its halo box) just inside the overscan-safe corner.
            rightMargin = dpi(Dimens.SAFE_H + 4f - INSET_X * BIG_DP)
            bottomMargin = dpi(Dimens.SAFE_V + 4f - INSET_BOTTOM * BIG_DP)
        })

        text.maxWidth = dpi(BUBBLE_MAX_DP - BUBBLE_PAD_H * 2 - TAIL_DP)
        text.maxLines = 5
        text.ellipsize = android.text.TextUtils.TruncateAt.END
        text.setLineSpacing(0f, 1.18f)
        bubble.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bubble.setPadding(dpi(BUBBLE_PAD_H), dpi(BUBBLE_PAD_V), dpi(BUBBLE_PAD_H + TAIL_DP), dpi(BUBBLE_PAD_V + 2f))
        bubble.visibility = View.GONE
        bubble.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width.toFloat()
            v.pivotY = v.height - dp(TAIL_FROM_BOTTOM_DP)
        }
        addView(bubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            // Tail tip just left of big Lumo's wing, level with its face.
            rightMargin = dpi(Dimens.SAFE_H + 4f + (1f - 2f * INSET_X) * BIG_DP + 6f)
            bottomMargin = dpi(Dimens.SAFE_V + 4f + (HEAD_FROM_BOTTOM - INSET_BOTTOM) * BIG_DP - TAIL_FROM_BOTTOM_DP)
        })
        applyScale(SMALL_SCALE)
    }

    /**
     * Lumo says [text] in the bubble. Replaces whatever it was saying. [mood] (if given) shows only
     * while this line is visible. [holdMs] counts from the end of the typewriter reveal.
     */
    fun say(text: String, mood: Mood? = null, holdMs: Long = 6000) {
        val line = text.trim()
        if (line.isEmpty()) {
            hush()
            return
        }
        Async.cancel(hideTask)
        hideTask = null
        reveal?.cancel()

        lineMood = mood
        lumo.mood = mood ?: this.mood

        val total = (line.length * CHAR_MS).coerceIn(MIN_REVEAL_MS, MAX_REVEAL_MS)
        lineText = line
        lineEndsAt = if (holdMs > 0) SystemClock.uptimeMillis() + total + holdMs else Long.MAX_VALUE
        if (placement == Placement.QUIET) {
            // Something full-screen is up: keep the line (and its mood) and show it later if still fresh.
            if (bubbleShowing) fadeOutBubble(fast = true)
            scheduleQuietEnd()
            return
        }

        val spannable = SpannableString(line)
        spannable.setSpan(hidden, 0, line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text.setText(spannable, TextView.BufferType.SPANNABLE)
        showBubble()

        reveal = ValueAnimator.ofInt(0, line.length).apply {
            duration = total
            addUpdateListener { a -> revealTo(a.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    revealTo(Int.MAX_VALUE)
                    if (holdMs > 0) hideTask = Async.mainDelayed(holdMs) { endLine(fast = false) }
                }
            })
            start()
        }
        announceForAccessibility(line)
    }

    fun thinking(on: Boolean) {
        lumo.setThinking(on)
    }

    fun celebrate(color: Int) {
        lumo.celebrate(color)
    }

    /** Hides the bubble right away (and forgets a line held back while quiet). Lumo stays. */
    fun hush() {
        Async.cancel(hideTask)
        hideTask = null
        reveal?.cancel()
        endLine(fast = true)
    }

    /**
     * Moves the bubble for the screen that is up. Going [Placement.QUIET] hides the bubble but lets the
     * line run on; coming back shows it again for whatever is left of its hold time.
     */
    fun setPlacement(placement: Placement) {
        if (placement == this.placement) return
        val was = this.placement
        this.placement = placement
        if (placement == Placement.QUIET) {
            reveal?.cancel()
            if (bubbleShowing) fadeOutBubble(fast = true)
            scheduleQuietEnd()
            return
        }
        layoutBubble()
        if (was != Placement.QUIET) return
        val line = lineText ?: return
        Async.cancel(hideTask)
        hideTask = null
        val left = lineEndsAt - SystemClock.uptimeMillis()
        when {
            lineEndsAt == Long.MAX_VALUE -> say(line, lineMood, 0)
            left >= MIN_REPLAY_MS -> say(line, lineMood, (left - MAX_REVEAL_MS).coerceAtLeast(MIN_REPLAY_MS / 2))
            else -> endLine(fast = true)
        }
    }

    /** Large on Home and during a rescue; small in the corner while browsing. */
    fun setBig(big: Boolean) {
        if (this.big == big && sizeAnim == null) return
        this.big = big
        sizeAnim?.cancel()
        val target = if (big) 1f else SMALL_SCALE
        if (!isLaidOut || !isShown) {
            sizeAnim = null
            applyScale(target)
            return
        }
        sizeAnim = ValueAnimator.ofFloat(scaleNow, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyScale(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (sizeAnim === animation) sizeAnim = null
                }
            })
            start()
        }
    }

    /** Lumo's mouth chatters while true. Wire it to Narrator.onSpeaking. */
    fun speaking(on: Boolean) {
        lumo.talking = on
    }

    // ------------------------------------------------------------------ never in the way

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Async.cancel(hideTask)
        hideTask = null
        reveal?.cancel()
        sizeAnim?.cancel()
        sizeAnim = null
    }

    /** While quiet, a line still runs out (and its mood fades) on time even though nobody saw it. */
    private fun scheduleQuietEnd() {
        Async.cancel(hideTask)
        hideTask = null
        if (lineText == null || lineEndsAt == Long.MAX_VALUE) return
        val left = lineEndsAt - SystemClock.uptimeMillis()
        if (left <= 0) {
            endLine(fast = true)
            return
        }
        hideTask = Async.mainDelayed(left) { endLine(fast = true) }
    }

    /** Bubble geometry for the current placement (a QUIET bubble keeps the last one; it is hidden anyway). */
    private fun layoutBubble() {
        val lp = bubble.layoutParams as? LayoutParams ?: return
        val footer = placement == Placement.FOOTER
        val padV = if (footer) FOOTER_PAD_V else BUBBLE_PAD_V
        text.maxWidth = dpi((if (footer) FOOTER_MAX_DP else BUBBLE_MAX_DP) - BUBBLE_PAD_H * 2 - TAIL_DP)
        text.maxLines = if (footer) 2 else 5
        // On Home the strip spans the whole footer row, so it reads as one calm line, not a stray box.
        bubble.minimumWidth = if (footer) dpi(FOOTER_MAX_DP) else 0
        bubble.setPadding(dpi(BUBBLE_PAD_H), dpi(padV), dpi(BUBBLE_PAD_H + TAIL_DP), dpi(padV + 2f))
        // Both keep the big corner bubble's right edge: the tail tip just left of big Lumo's wing.
        lp.bottomMargin = if (footer) {
            dpi(Dimens.SAFE_V)
        } else {
            dpi(Dimens.SAFE_V + 4f + (HEAD_FROM_BOTTOM - INSET_BOTTOM) * BIG_DP - TAIL_FROM_BOTTOM_DP)
        }
        bubble.layoutParams = lp
        applyScale(scaleNow)
    }

    // ------------------------------------------------------------------ internals

    private fun revealTo(count: Int) {
        val s = text.text as? Spannable ?: return
        val n = min(max(count, 0), s.length)
        if (n >= s.length) s.removeSpan(hidden) else s.setSpan(hidden, n, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun showBubble() {
        bubble.animate().cancel()
        if (!bubbleShowing) {
            bubbleShowing = true
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.9f
            bubble.scaleY = 0.9f
            bubble.translationYExtra = dp(8f)
        } else {
            // A new line while one is up: a small pop so the change registers.
            bubble.scaleX = 0.97f
            bubble.scaleY = 0.97f
        }
        bubble.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener { bubble.translationYExtra = dp(8f) * (1f - bubble.alpha) }
            .withEndAction { bubble.translationYExtra = 0f }
            .start()
        applyScale(scaleNow)
    }

    /** The line is over: the bubble goes and Lumo eases back to its resting mood. */
    private fun endLine(fast: Boolean) {
        hideTask = null
        lineText = null
        val restore = lineMood != null
        lineMood = null
        if (restore) lumo.mood = mood
        fadeOutBubble(fast)
    }

    private fun fadeOutBubble(fast: Boolean) {
        if (!bubbleShowing) return
        bubbleShowing = false
        bubble.animate().cancel()
        bubble.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(if (fast) 120L else 180L)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener(null)
            .withEndAction { if (!bubbleShowing) bubble.visibility = View.GONE }
            .start()
    }

    /** Positions Lumo (scaled about its bottom-right) and the bubble for a size between small and big. */
    private fun applyScale(k: Float) {
        scaleNow = k
        val shrink = 1f - k
        lumo.scaleX = k
        lumo.scaleY = k
        // Keep the moth's own corner (not the halo box) anchored as it scales.
        lumo.translationX = -dp(INSET_X * BIG_DP) * shrink
        lumo.translationY = -dp(INSET_BOTTOM * BIG_DP) * shrink
        if (placement == Placement.FOOTER) {
            // Home's strip stays put along the bottom; only the corner bubble follows Lumo's face.
            bubble.translationX = 0f
            bubble.baseTranslationY = 0f
        } else {
            bubble.translationX = dp((1f - 2f * INSET_X) * BIG_DP) * shrink
            bubble.baseTranslationY = dp((HEAD_FROM_BOTTOM - INSET_BOTTOM) * BIG_DP) * shrink
        }
    }

    /** The bubble container: rounded dusk surface with a tail pointing right at Lumo. */
    private class BubbleView(context: Context) : FrameLayout(context) {
        var baseTranslationY = 0f
            set(value) {
                field = value
                translationY = value + translationYExtra
            }
        var translationYExtra = 0f
            set(value) {
                field = value
                translationY = baseTranslationY + value
            }

        init {
            background = BubbleDrawable(context)
            isFocusable = false
            clipToPadding = false
        }
    }

    private class BubbleDrawable(context: Context) : Drawable() {
        private val radius = context.dp(Dimens.RADIUS)
        private val tail = context.dp(TAIL_DP)
        private val tailFromBottom = context.dp(TAIL_FROM_BOTTOM_DP)
        private val stroke = context.dp(1.5f)
        private val shadowDy = context.dp(4f)
        private val path = Path()
        private val tailPath = Path()
        private val rect = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DUSK }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Palette.HAIRLINE
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.withAlpha(0xFF000000.toInt(), 0.28f) }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            path.reset()
            val l = bounds.left + stroke
            val t = bounds.top + stroke
            val r = bounds.right - tail
            val b = bounds.bottom - stroke
            rect.set(l, t, r, b)
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            // A soft comic-style tail curling out toward Lumo's face.
            val tipX = bounds.right - stroke
            val tipY = b - tailFromBottom + tail * 0.15f
            val baseTop = b - tailFromBottom - tail * 1.1f
            val baseBottom = b - tailFromBottom + tail * 0.75f
            tailPath.reset()
            tailPath.moveTo(r - radius * 0.6f, baseTop)
            tailPath.cubicTo(r + tail * 0.1f, baseTop, r + tail * 0.45f, tipY - tail * 0.35f, tipX, tipY)
            tailPath.cubicTo(r + tail * 0.4f, tipY + tail * 0.2f, r + tail * 0.05f, baseBottom, r - radius * 0.6f, baseBottom)
            tailPath.close()
            path.op(tailPath, Path.Op.UNION)
        }

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.translate(0f, shadowDy)
            canvas.drawPath(path, shadow)
            canvas.restore()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            edge.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val BIG_DP = 200f
        const val SMALL_SCALE = 0.6f // 120 dp in the corner
        // Where the moth sits inside LumoView's 240-unit design box, as fractions of the box.
        const val INSET_X = 0.06f
        const val INSET_BOTTOM = 0.135f
        const val HEAD_FROM_BOTTOM = 0.55f
        const val BUBBLE_MAX_DP = 380f
        /** Home's footer bubble: from the left safe edge to just before big Lumo's wing. */
        const val FOOTER_MAX_DP = 678f
        /** Keeps the one-line strip below the favorites row (it ends 68dp above the screen's bottom). */
        const val FOOTER_PAD_V = 7f
        /** A held-back line is only worth showing if this much of it is left. */
        const val MIN_REPLAY_MS = 1800L
        const val BUBBLE_PAD_H = 22f
        const val BUBBLE_PAD_V = 16f
        const val TAIL_DP = 18f
        const val TAIL_FROM_BOTTOM_DP = 26f
        const val CHAR_MS = 24L
        const val MIN_REVEAL_MS = 260L
        const val MAX_REVEAL_MS = 1100L
    }
}
