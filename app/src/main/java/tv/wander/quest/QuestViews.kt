package tv.wander.quest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import tv.wander.cards.Craft
import tv.wander.cards.emojiPaint
import tv.wander.cards.sp
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Accent color for a quest: fixed for the curated five, cycling the player colors for the rest. */
internal fun questAccent(quest: Quest, fallbackIndex: Int = 0): Int = when (quest.id) {
    "speed-safari" -> Palette.LAMP
    "moon-collectors" -> Palette.BERRY
    "deep-dive" -> Palette.SKY
    "dino-time" -> Palette.MINT
    "tiny-titans" -> Palette.LAMP
    else -> Palette.player(fallbackIndex)
}

/** A player's name, or their color's name ("Berry") if the phone sent none. */
internal fun displayName(p: Player): String =
    p.name.trim().ifEmpty { Palette.PLAYER_COLOR_NAMES[((p.colorIndex % 4) + 4) % 4] }

internal fun goalWord(goal: Int): String = when (goal) {
    1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"; 6 -> "six"
    else -> goal.toString()
}

/** A stamped emoji medallion like the field-guide cards'. Empty emoji draws Lumo's moth. */
internal class MedallionView(context: Context, private var emoji: String, private var accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    private val emojiPaint = emojiPaint()
    var showRing = true

    fun set(emoji: String, accent: Int) {
        this.emoji = emoji
        this.accent = accent
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ringGap = if (showRing) size * 0.07f else 0f
        val r = size / 2f - ringGap - dp(1f)
        paint.style = Paint.Style.FILL
        paint.color = Palette.withAlpha(accent, 0.17f)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (size * 0.022f).coerceAtLeast(dp(1.5f))
        paint.color = Palette.withAlpha(accent, 0.8f)
        canvas.drawCircle(cx, cy, r, paint)
        if (showRing) {
            dashed.strokeWidth = dp(1.2f)
            dashed.color = Palette.withAlpha(accent, 0.4f)
            dashed.pathEffect = dash
            canvas.drawCircle(cx, cy, r + ringGap * 0.8f, dashed)
        }
        paint.style = Paint.Style.FILL
        val glyph = Craft.firstGlyph(emoji)
        if (glyph.isEmpty()) {
            Craft.drawMoth(canvas, cx, cy + r * 0.05f, r * 1.3f, accent, paint)
        } else {
            emojiPaint.textSize = r * 1.05f
            val maxW = r * 1.55f
            val w = emojiPaint.measureText(glyph)
            if (w > maxW && w > 0f) emojiPaint.textSize *= maxW / w
            val fm = emojiPaint.fontMetrics
            canvas.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, emojiPaint)
        }
    }
}

/** Progress pips: one per goal, filled in the finder's color. The newest fill pops in. */
internal class PipsView(context: Context, private val pipDp: Float, private val gapDp: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var goal = 0
    private var colors: List<Int> = emptyList()
    private var popIndex = -1
    private var pop = 1f
    private var anim: ValueAnimator? = null

    /** [filled] = finder colors in order; pops the pip at [animateFrom] and later. */
    fun set(goal: Int, filled: List<Int>, animateFrom: Int = Int.MAX_VALUE) {
        this.goal = goal.coerceIn(1, MAX_PIPS)
        colors = filled
        anim?.cancel()
        if (animateFrom < filled.size) {
            popIndex = animateFrom
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = OvershootInterpolator(2.6f)
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            popIndex = -1
            pop = 1f
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = goal.coerceAtLeast(1)
        val w = (n * dp(pipDp) + (n - 1) * dp(gapDp) + dp(4f)).toInt()
        val h = (dp(pipDp) + dp(4f)).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = dp(pipDp)
        val g = dp(gapDp)
        val cy = height / 2f
        var x = dp(2f) + d / 2f
        for (i in 0 until goal) {
            val c = colors.getOrNull(i)
            if (c == null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = Palette.withAlpha(Palette.CREAM, 0.28f)
                canvas.drawCircle(x, cy, d / 2f - dp(1f), paint)
            } else {
                val scale = if (i >= popIndex && popIndex >= 0) pop.coerceAtLeast(0f) else 1f
                paint.style = Paint.Style.FILL
                paint.color = c
                canvas.drawCircle(x, cy, (d / 2f) * scale, paint)
                paint.color = Palette.withAlpha(Palette.CREAM, 0.45f)
                canvas.drawCircle(x - d * 0.14f * scale, cy - d * 0.14f * scale, d * 0.12f * scale, paint)
            }
            x += d + g
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        pop = 1f
        super.onDetachedFromWindow()
    }

    companion object {
        const val MAX_PIPS = 8
    }
}

/** A small arc spinner in a player's color. Spins only while attached and visible. */
internal class SpinnerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var angle = 0f
    private var anim: ValueAnimator? = null
    var color: Int = Palette.LAMP
        set(value) { field = value; invalidate() }

    private fun startSpin() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startSpin()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown && isAttachedToWindow) startSpin() else stopSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val stroke = s * 0.14f
        paint.strokeWidth = stroke
        oval.set(stroke, stroke, s - stroke, s - stroke)
        oval.offset((width - s) / 2f, (height - s) / 2f)
        paint.color = Palette.withAlpha(color, 0.22f)
        canvas.drawArc(oval, 0f, 360f, false, paint)
        paint.color = color
        canvas.drawArc(oval, angle - 90f, 250f, false, paint)
    }
}

/** A round avatar: the player's color with their initial in Young Serif. */
internal class AvatarView(context: Context, private var name: String, private var color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display
        textAlign = Paint.Align.CENTER
        color = Palette.INK
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        paint.color = color
        canvas.drawCircle(cx, cy, s / 2f, paint)
        paint.color = Palette.withAlpha(Palette.CREAM, 0.3f)
        canvas.drawCircle(cx - s * 0.17f, cy - s * 0.2f, s * 0.12f, paint)
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        text.textSize = s * 0.5f
        val fm = text.fontMetrics
        canvas.drawText(initial, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }
}

/** A tiny phone with a glowing button, for "tap 'This page has it!' on your phone". */
internal class PhoneGlyphView(context: Context, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = h * 0.56f
        val left = (width - w) / 2f
        rect.set(left, 0f, left + w, h)
        paint.style = Paint.Style.FILL
        paint.color = Palette.DUSK
        canvas.drawRoundRect(rect, w * 0.2f, w * 0.2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Palette.CREAM
        canvas.drawRoundRect(rect, w * 0.2f, w * 0.2f, paint)
        paint.style = Paint.Style.FILL
        rect.set(left + w * 0.18f, h * 0.62f, left + w * 0.82f, h * 0.78f)
        paint.color = accent
        canvas.drawRoundRect(rect, h * 0.06f, h * 0.06f, paint)
        paint.color = Palette.withAlpha(Palette.CREAM, 0.3f)
        rect.set(left + w * 0.18f, h * 0.18f, left + w * 0.82f, h * 0.26f)
        canvas.drawRoundRect(rect, h * 0.03f, h * 0.03f, paint)
        rect.set(left + w * 0.18f, h * 0.33f, left + w * 0.62f, h * 0.41f)
        canvas.drawRoundRect(rect, h * 0.03f, h * 0.03f, paint)
    }
}

/** A one-shot burst of rays and sparks in a player's color, behind a badge. */
internal class BurstView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var t = 1f
    private var color = Palette.LAMP
    private var anim: ValueAnimator? = null

    fun fire(color: Int) {
        this.color = color
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Test hook: holds the burst at [progress] (0..1) instead of animating it. */
    internal fun freezeAt(progress: Float) {
        anim?.cancel()
        anim = null
        t = progress.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        t = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (t >= 1f) return
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val ease = 1f - (1f - t) * (1f - t)
        val fade = 1f - t
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        for (i in 0 until 12) {
            val a = (i / 12.0 * 2 * PI + 0.2).toFloat()
            val r0 = maxR * (0.5f + 0.32f * ease)
            val r1 = maxR * (0.62f + 0.38f * ease)
            paint.color = Palette.withAlpha(if (i % 3 == 0) Palette.CREAM else color, fade)
            canvas.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Palette.withAlpha(color, 0.25f * fade)
        canvas.drawCircle(cx, cy, maxR * (0.45f + 0.5f * ease), paint)
    }
}

/**
 * A one-line TextView that steps its size down (to [minSp]) until the text fits, so titles like
 * "Moon Collectors" stay whole in narrow tiles; only then does it ellipsize.
 */
internal class FitTextView(context: Context, private val maxSp: Float, private val minSp: Float) : android.widget.TextView(context) {
    private val probe = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
            val minPx = context.sp(minSp)
            var size = context.sp(maxSp)
            probe.set(paint)
            probe.textSize = size
            val t = text.toString()
            while (size > minPx && probe.measureText(t) > avail) {
                size = (size - context.sp(0.5f)).coerceAtLeast(minPx)
                probe.textSize = size
            }
            if (kotlin.math.abs(textSize - size) > 0.5f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
