package tv.wander.browse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The shared cursor, drawn on a Canvas over the page. A soft pool of lamp light (it is a lamp-lit room,
 * and Lumo is a moth: the cursor is the light everyone circles), a crisp ring in the color of whoever
 * moved it last, a center dot that marks exactly where a tap lands, and a small wedge that swings
 * toward wherever the cursor is heading.
 *
 * Over something clickable the ring opens up and fills softly; over a text field the dot becomes a
 * caret. A name chip ("Maya") fades in for 1.5 s when someone new takes over. Taps ripple. After
 * [IDLE_MS] without movement the cursor fades away; any movement brings it straight back.
 *
 * Never focusable or clickable: it only draws.
 */
internal class CursorView(context: Context) : View(context) {
    /** Cursor hotspot in this view's coordinates (px). */
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    /** False while faded out for being idle. */
    val isAwake: Boolean get() = visTarget == 1f

    private var color = Palette.LAMP

    private val ringR = dp(13f)
    private val ringHoverR = dp(17f)
    private val glowR = dp(58f)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, glowR,
            intArrayOf(Palette.withAlpha(Palette.LAMP, 0.30f), Palette.withAlpha(Palette.LAMP, 0.12f), Palette.withAlpha(Palette.LAMP, 0f)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bodyBold
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TypeScale.CAPTION, resources.displayMetrics)
        color = Palette.INK
    }
    private val wedge = Path()
    private val chipRect = RectF()
    private val chipMetrics = Paint.FontMetrics()

    // Visibility (idle fade).
    private var visTarget = 1f
    private var visFrom = 1f
    private var visChangedAt = 0L

    // Hover: 0 plain, 1 clickable, 2 text field.
    private var hover = 0
    private var hoverPrev = 0
    private var hoverChangedAt = 0L

    // Heading of the wedge, in radians. Rests pointing up-left like a classic pointer.
    private var heading = REST_HEADING
    private var headingTarget = REST_HEADING
    private var lastMoveAt = 0L
    private var lastFrameAt = 0L

    // One-shot effects.
    private var pressAt = NEVER
    private var chipAt = NEVER
    private var chipLabel = ""
    private var chipColor = Palette.LAMP
    private val ripples = ArrayList<Ripple>(4)

    private class Ripple(val x: Float, val y: Float, val color: Int, val at: Long)

    private val idleRunnable = Runnable { fadeOut() }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        scheduleIdle()
    }

    /** Moves the hotspot; the wedge turns toward the motion. Wakes the cursor. */
    fun moveTo(x: Float, y: Float) {
        val dx = x - cursorX
        val dy = y - cursorY
        if (hypot(dx, dy) > 0.5f) {
            headingTarget = atan2(dy, dx)
            lastMoveAt = now()
        }
        cursorX = x
        cursorY = y
        wake()
        invalidate()
    }

    /** Places the hotspot without any motion styling (first layout, size changes). */
    fun place(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        invalidate()
    }

    fun setColor(c: Int) {
        if (c == color) return
        color = c
        invalidate()
    }

    /** "Maya" in her color, for 1.5 s, because she just took the cursor. */
    fun showName(label: String, c: Int) {
        chipLabel = label.take(16)
        chipColor = c
        chipAt = now()
        wake()
        invalidate()
    }

    fun setHover(kind: Int) {
        val k = kind.coerceIn(0, 2)
        if (k == hover) return
        hoverPrev = hover
        hover = k
        hoverChangedAt = now()
        invalidate()
    }

    /** A tap landed at the hotspot. */
    fun ripple() {
        val t = now()
        pressAt = t
        if (ripples.size >= 4) ripples.removeAt(0)
        ripples.add(Ripple(cursorX, cursorY, color, t))
        wake()
        invalidate()
    }

    /** Shows the cursor (fading in if it was idle) and restarts the idle timer. */
    fun wake() {
        if (visTarget != 1f) {
            visFrom = currentVisibility(now())
            visTarget = 1f
            visChangedAt = now()
            invalidate()
        }
        scheduleIdle()
    }

    /** Hides straight away (used while a fullscreen video takes over, until someone moves). */
    fun fadeOut() {
        if (visTarget == 0f) return
        visFrom = currentVisibility(now())
        visTarget = 0f
        visChangedAt = now()
        removeCallbacks(idleRunnable)
        invalidate()
    }

    private fun scheduleIdle() {
        removeCallbacks(idleRunnable)
        postDelayed(idleRunnable, IDLE_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idleRunnable)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visTarget == 1f) scheduleIdle()
    }

    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    private fun currentVisibility(t: Long): Float {
        val dur = if (visTarget == 1f) FADE_IN_MS else FADE_OUT_MS
        val p = ((t - visChangedAt).toFloat() / dur).coerceIn(0f, 1f)
        return visFrom + (visTarget - visFrom) * decel(p)
    }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        var animating = false

        val vis = currentVisibility(t)
        if (vis != visTarget) animating = true

        // Ripples live where the tap happened, even if the cursor moves on.
        if (ripples.isNotEmpty()) {
            val it = ripples.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val p = (t - r.at).toFloat() / RIPPLE_MS
                if (p >= 1f) {
                    it.remove()
                    continue
                }
                animating = true
                val e = decel(p)
                ripplePaint.color = Palette.withAlpha(r.color, 0.85f * (1f - p))
                ripplePaint.strokeWidth = dp(3.5f) * (1f - p) + dp(1f)
                canvas.drawCircle(r.x, r.y, ringR + (ringR * 2.6f) * e, ripplePaint)
                if (p < 0.6f) {
                    ripplePaint.color = Palette.withAlpha(r.color, 0.45f * (1f - p / 0.6f))
                    canvas.drawCircle(r.x, r.y, ringR + ringR * 1.3f * decel(p / 0.6f), ripplePaint)
                }
            }
        }

        if (vis <= 0.001f) {
            if (animating) postInvalidateOnAnimation()
            return
        }

        // Wedge heading: follow motion, drift back to rest when still.
        if (t - lastMoveAt > HEADING_REST_DELAY_MS) headingTarget = REST_HEADING
        val dt = if (lastFrameAt == 0L) 16f else (t - lastFrameAt).toFloat().coerceIn(0f, 64f)
        lastFrameAt = t
        val diff = shortestAngle(headingTarget - heading)
        if (kotlin.math.abs(diff) > 0.01f) {
            heading += diff * (1f - kotlin.math.exp(-dt / HEADING_TAU_MS))
            animating = true
        } else {
            heading = headingTarget
        }
        if (headingTarget != REST_HEADING) animating = true // keep checking for the rest timeout

        // Hover morph 0..1 toward the current kind.
        val hp = decel(((t - hoverChangedAt).toFloat() / HOVER_MS).coerceIn(0f, 1f))
        if (hp < 1f) animating = true
        val clickNow = if (hover == 1) 1f else 0f
        val clickPrev = if (hoverPrev == 1) 1f else 0f
        val click = clickPrev + (clickNow - clickPrev) * hp

        // Press dip.
        var press = 1f
        if (pressAt != NEVER) {
            val pp = (t - pressAt).toFloat() / PRESS_MS
            if (pp < 1f) {
                press = 0.74f + 0.26f * decel(pp)
                animating = true
            } else {
                pressAt = NEVER
            }
        }

        val x = cursorX
        val y = cursorY
        val r = (ringR + (ringHoverR - ringR) * click) * press

        canvas.save()
        canvas.translate(x, y)
        // Lamp light pool (brighter over something you can click).
        glowPaint.alpha = (255 * vis * (0.85f + 0.35f * click)).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, glowR, glowPaint)

        // A crisp ink edge around ring and wedge keeps the cursor readable on white pages too.
        val stroke = dp(3f) + dp(0.75f) * click
        buildWedge(r)
        haloPaint.color = Palette.withAlpha(Palette.INK, 0.72f * vis)
        haloPaint.strokeWidth = stroke + dp(3f)
        canvas.drawCircle(0f, 0f, r, haloPaint)
        outlinePaint.color = haloPaint.color
        outlinePaint.strokeWidth = dp(3f)
        canvas.drawPath(wedge, outlinePaint)
        if (click > 0f) {
            fillPaint.color = Palette.withAlpha(color, 0.24f * click * vis)
            canvas.drawCircle(0f, 0f, r - stroke / 2f, fillPaint)
        }
        // The wedge grows out of the ring and points where the cursor is heading.
        fillPaint.color = Palette.withAlpha(color, vis)
        canvas.drawPath(wedge, fillPaint)
        ringPaint.color = fillPaint.color
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(0f, 0f, r, ringPaint)

        if (hover == 2 && hp > 0.5f || hoverPrev == 2 && hp <= 0.5f) drawCaret(canvas, vis) else drawDot(canvas, vis)
        canvas.restore()

        // Name chip.
        if (chipAt != NEVER) {
            val ct = t - chipAt
            if (ct >= CHIP_IN_MS + CHIP_HOLD_MS + CHIP_OUT_MS) {
                chipAt = NEVER
            } else {
                animating = true
                val a = when {
                    ct < CHIP_IN_MS -> decel(ct.toFloat() / CHIP_IN_MS)
                    ct < CHIP_IN_MS + CHIP_HOLD_MS -> 1f
                    else -> 1f - (ct - CHIP_IN_MS - CHIP_HOLD_MS).toFloat() / CHIP_OUT_MS
                }
                val lift = if (ct < CHIP_IN_MS) dp(8f) * (1f - a) else 0f
                drawChip(canvas, x, y, a.coerceIn(0f, 1f) * vis, lift)
            }
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawDot(canvas: Canvas, vis: Float) {
        outlinePaint.color = Palette.withAlpha(Palette.INK, 0.6f * vis)
        outlinePaint.strokeWidth = dp(1.5f)
        fillPaint.color = Palette.withAlpha(Palette.CREAM, vis)
        val dr = dp(3.4f)
        canvas.drawCircle(0f, 0f, dr, fillPaint)
        canvas.drawCircle(0f, 0f, dr, outlinePaint)
    }

    /** An I-beam caret: "you can type here". */
    private fun drawCaret(canvas: Canvas, vis: Float) {
        val h = dp(7f)
        val w = dp(3.5f)
        outlinePaint.color = Palette.withAlpha(Palette.INK, 0.7f * vis)
        outlinePaint.strokeWidth = dp(4.5f)
        drawBeam(canvas, h, w, outlinePaint)
        outlinePaint.color = Palette.withAlpha(Palette.CREAM, vis)
        outlinePaint.strokeWidth = dp(2f)
        drawBeam(canvas, h, w, outlinePaint)
    }

    private fun drawBeam(canvas: Canvas, h: Float, w: Float, p: Paint) {
        canvas.drawLine(0f, -h, 0f, h, p)
        canvas.drawLine(-w, -h, w, -h, p)
        canvas.drawLine(-w, h, w, h, p)
    }

    /** A small pointed tip rising out of the ring at [heading], like a comet's nose. */
    private fun buildWedge(r: Float) {
        val tip = r + dp(10.5f)
        val half = 0.46f
        val a = heading
        wedge.reset()
        wedge.moveTo(cos(a + half) * r, sin(a + half) * r)
        wedge.lineTo(cos(a) * tip, sin(a) * tip)
        wedge.lineTo(cos(a - half) * r, sin(a - half) * r)
        wedge.close()
    }

    private fun drawChip(canvas: Canvas, x: Float, y: Float, alpha: Float, lift: Float) {
        if (alpha <= 0f || chipLabel.isEmpty()) return
        val padH = dp(12f)
        val padV = dp(6.5f)
        val fm = chipMetrics.also { chipText.getFontMetrics(it) }
        val textW = chipText.measureText(chipLabel)
        val w = textW + padH * 2
        val h = (fm.descent - fm.ascent) + padV * 2
        val gap = dp(24f)
        var left = x + gap
        var top = y + gap * 0.8f + lift
        if (left + w > width - dp(8f)) left = x - gap - w
        if (top + h > height - dp(8f)) top = y - gap * 0.8f - h + lift
        left = left.coerceIn(dp(4f), (width - w - dp(4f)).coerceAtLeast(dp(4f)))
        top = top.coerceIn(dp(4f), (height - h - dp(4f)).coerceAtLeast(dp(4f)))
        chipRect.set(left, top, left + w, top + h)
        val rad = h / 2f
        outlinePaint.color = Palette.withAlpha(Palette.INK, 0.45f * alpha)
        outlinePaint.strokeWidth = dp(3f)
        canvas.drawRoundRect(chipRect, rad, rad, outlinePaint)
        chipPaint.color = Palette.withAlpha(chipColor, alpha)
        canvas.drawRoundRect(chipRect, rad, rad, chipPaint)
        chipText.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawText(chipLabel, left + padH, top + padV - fm.ascent, chipText)
    }

    private fun decel(p: Float): Float {
        val c = p.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }

    private fun shortestAngle(a: Float): Float {
        var d = a
        val twoPi = (2 * PI).toFloat()
        while (d > PI) d -= twoPi
        while (d < -PI) d += twoPi
        return d
    }

    companion object {
        const val IDLE_MS = 6000L
        private const val FADE_IN_MS = 120f
        private const val FADE_OUT_MS = 450f
        private const val HOVER_MS = 160f
        private const val PRESS_MS = 220f
        private const val RIPPLE_MS = 460f
        private const val CHIP_IN_MS = 180L
        private const val CHIP_HOLD_MS = 1500L
        private const val CHIP_OUT_MS = 320L
        private const val HEADING_REST_DELAY_MS = 450L
        private const val HEADING_TAU_MS = 70f
        private const val NEVER = Long.MIN_VALUE
        private val REST_HEADING = (-3 * PI / 4).toFloat() // up-left
    }
}
