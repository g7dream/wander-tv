package tv.wander.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import tv.wander.model.Mood
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lumo the moth, drawn on a Canvas with four moods, idle hover, wing flutter and blinking.
 *
 * Everything is vector: a plump, fuzzy cream body, big glossy eyes, feathery antennae and four
 * lamp-to-berry wings with eye-spots, in front of a warm lamp halo. Moods blend over 300 ms by
 * interpolating face and body parameters (never a hard cut). The view draws at any size: the art
 * lives in a fixed 240x240 design box that is scaled to fit and centered.
 *
 * Performance: one Choreographer loop runs only while the view is attached and visible, at ~30 fps
 * when idle and 60 fps during blends and celebrations. onDraw does not allocate.
 *
 * Under Robolectric the loop ticks through a main-thread Handler instead: Robolectric's default
 * Choreographer re-runs re-posted frame callbacks forever inside `idleFor`, which would hang any
 * snapshot test that contains Lumo.
 */
class LumoView(context: Context) : View(context) {
    /** Lumo's resting mood. Changes blend smoothly over 300 ms. */
    var mood: Mood = Mood.CURIOUS
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** While true the mouth chatters (wire it to the narrator's onSpeaking). */
    var talking: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun setThinking(on: Boolean) {
        thinkTarget = if (on) 1f else 0f
        invalidate()
    }

    /** A happy wing-burst in a player's color. */
    fun celebrate(color: Int) {
        val now = SystemClock.uptimeMillis()
        celebrateAt = now
        happyUntil = now + CELEBRATE_HAPPY_MS
        burstColor = color
        for (i in 0 until SPARKS) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = 90f + rnd.nextFloat() * 120f
            val ring = 44f + rnd.nextFloat() * 16f
            sx[i] = ORIGIN_X + cos(a) * ring
            sy[i] = ORIGIN_Y - 14f + sin(a) * ring * 0.85f
            svx[i] = cos(a) * speed
            svy[i] = sin(a) * speed - 45f
            sage[i] = 0f
            slife[i] = 0.75f + rnd.nextFloat() * 0.55f
            ssize[i] = 3f + rnd.nextFloat() * 3.8f
            srot[i] = rnd.nextFloat() * 90f
            svrot[i] = (rnd.nextFloat() - 0.5f) * 540f
            val pickColor = rnd.nextFloat()
            scolor[i] = when {
                pickColor < 0.62f -> color
                pickColor < 0.85f -> Palette.LAMP
                else -> Palette.CREAM
            }
        }
        sparksAlive = true
        invalidate()
    }

    // ================================================================== animation state

    private val rnd = Random(SystemClock.uptimeMillis())
    private var lastTick = 0L
    private var hasTicked = false
    private var running = false
    private var frameCount = 0

    private val cur = FloatArray(P_COUNT)
    private val from = FloatArray(P_COUNT)
    private var blendTarget: Mood? = null
    private var blendStart = 0L

    private var flutterPhase = 0f
    private var bobPhase = 0f
    private var lifePhase = 0f // free-running seconds for small wobbles

    private var nextBlinkAt = 0L
    private var blinkStart = -1L
    private var blinkDur = 150L
    private var doubleBlink = false

    private var thinkTarget = 0f
    private var think = 0f
    private var talk = 0f

    private var celebrateAt = -1L
    private var happyUntil = -1L
    private var burstColor = Palette.LAMP

    private val sx = FloatArray(SPARKS)
    private val sy = FloatArray(SPARKS)
    private val svx = FloatArray(SPARKS)
    private val svy = FloatArray(SPARKS)
    private val sage = FloatArray(SPARKS)
    private val slife = FloatArray(SPARKS)
    private val ssize = FloatArray(SPARKS)
    private val srot = FloatArray(SPARKS)
    private val svrot = FloatArray(SPARKS)
    private val scolor = IntArray(SPARKS)
    private var sparksAlive = false

    private val frame = Choreographer.FrameCallback { onFrame() }
    private val tickRunnable = Runnable { onFrame() }
    private val tickHandler = Handler(Looper.getMainLooper())

    // ================================================================== geometry (built once)

    private val upperWing = Path()
    private val lowerWing = Path()
    private val upperBand = Path()
    private val upperVeins = Path()
    private val lowerVeins = Path()
    private val ruff = Path()
    private val head = Path()
    private val furHead: FloatArray
    private val furBody: FloatArray
    private val tuft = Path()
    private val abdomenBands = Path()
    private val happyEye = Path()
    private val smile = Path()
    private val tongue = Path()
    private val sleepyMouth = Path()
    private val wavyMouth = Path()
    private val brow = Path()
    private val spark = Path()

    // per-frame scratch (reused, never reallocated)
    private val antenna = Path()
    private val antennaBarbs = Path()
    private val eyeClip = Path()
    private val oval = RectF()
    private val antTipX = FloatArray(2)
    private val antTipY = FloatArray(2)

    // ================================================================== paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val upperWingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lowerWingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wingRootPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ruffPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val abdomenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Palette.CREAM
    }

    init {
        contentDescription = "Lumo"
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        System.arraycopy(PARAMS[Mood.CURIOUS.ordinal], 0, cur, 0, P_COUNT)
        blendTarget = Mood.CURIOUS
        scheduleBlink(SystemClock.uptimeMillis(), first = true)

        buildWings()
        buildBody()
        buildFace()
        furHead = furRing(0f, HEAD_Y, HEAD_R - 3.5f, HEAD_R + 0.5f, 52, 0.3f, 7)
        furBody = furRing(0f, 38f, 13.5f, 18f, 18, 0.25f, 11)

        haloPaint.shader = RadialGradient(
            0f, -14f, 118f,
            intArrayOf(Palette.withAlpha(Palette.LAMP, 0.62f), Palette.withAlpha(Palette.LAMP, 0.26f), Palette.withAlpha(Palette.LAMP, 0.08f), 0),
            floatArrayOf(0f, 0.36f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        upperWingPaint.shader = LinearGradient(
            -8f, -4f, -98f, -52f,
            intArrayOf(0xFFFFC266.toInt(), 0xFFFF9466.toInt(), Palette.BERRY, 0xFFE2457F.toInt()),
            floatArrayOf(0f, 0.42f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        lowerWingPaint.shader = LinearGradient(
            -6f, 8f, -62f, 64f,
            intArrayOf(0xFFFFAA5E.toInt(), 0xFFFF6F8C.toInt(), 0xFFD9437E.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        wingRootPaint.shader = RadialGradient(
            -4f, 2f, 34f,
            intArrayOf(Palette.withAlpha(BODY_LIGHT, 0.85f), Palette.withAlpha(BODY, 0.35f), 0),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        headPaint.shader = RadialGradient(
            -12f, HEAD_Y - 16f, 56f,
            intArrayOf(BODY_LIGHT, BODY, BODY_SHADE),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        ruffPaint.shader = RadialGradient(
            -6f, -4f, 34f,
            intArrayOf(0xFFFFFBF2.toInt(), BODY_LIGHT, BODY),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        abdomenPaint.shader = LinearGradient(
            -16f, 18f, 18f, 58f,
            intArrayOf(BODY_LIGHT, BODY, BODY_SHADE),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        cheekPaint.shader = RadialGradient(
            0f, 0f, 9f,
            intArrayOf(Palette.withAlpha(Palette.BERRY, 0.78f), Palette.withAlpha(Palette.BERRY, 0.34f), 0),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        eyePaint.shader = RadialGradient(
            0f, -3f, 13f,
            intArrayOf(0xFF2E2346.toInt(), EYE, EYE),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        glowPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Palette.withAlpha(Palette.LAMP, 0.95f), Palette.withAlpha(Palette.LAMP, 0.35f), 0),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
    }

    // ================================================================== lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastTick = 0L
        scheduleBlink(SystemClock.uptimeMillis(), first = true)
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (shouldRun && !running) {
            running = true
            lastTick = 0L
            postNextFrame()
        } else if (!shouldRun && running) {
            stopLoop()
        }
    }

    private fun postNextFrame() {
        try {
            if (USE_CHOREOGRAPHER) Choreographer.getInstance().postFrameCallback(frame)
            else tickHandler.postDelayed(tickRunnable, 16L)
        } catch (_: RuntimeException) {
            running = false
        }
    }

    private fun stopLoop() {
        running = false
        try {
            if (USE_CHOREOGRAPHER) Choreographer.getInstance().removeFrameCallback(frame)
            tickHandler.removeCallbacks(tickRunnable)
        } catch (_: RuntimeException) {
        }
    }

    private fun onFrame() {
        if (!running) return
        frameCount++
        val now = SystemClock.uptimeMillis()
        tick(now)
        val busy = sparksAlive || now - blendStart < BLEND_MS || (celebrateAt >= 0 && now - celebrateAt < LOOP_MS) ||
            talking || think > 0.01f || cur[P_FLUTTER_HZ] > 2.5f
        // Gentle idle life reads fine at 30 fps; blends, celebrations, chatter and fast flutter get every frame.
        if (busy || frameCount % 2 == 0) invalidate()
        postNextFrame()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val def = (resources.displayMetrics.density * 160f).toInt()
        val w = resolveSize(def, widthMeasureSpec)
        val h = resolveSize(def, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ================================================================== per-frame update

    private fun tick(now: Long) {
        if (now == lastTick) return
        val dt = if (lastTick == 0L) 0.016f else ((now - lastTick).coerceIn(0L, 50L)) / 1000f
        val firstTick = !hasTicked
        hasTicked = true
        lastTick = now

        val target = if (happyUntil > now) Mood.HAPPY else mood
        if (target != blendTarget) {
            System.arraycopy(if (firstTick) PARAMS[target.ordinal] else cur, 0, from, 0, P_COUNT)
            blendTarget = target
            blendStart = now
        }
        val to = PARAMS[target.ordinal]
        val k = ease(((now - blendStart).toFloat() / BLEND_MS).coerceIn(0f, 1f))
        for (i in 0 until P_COUNT) cur[i] = from[i] + (to[i] - from[i]) * k
        if (k >= 1f) System.arraycopy(to, 0, from, 0, P_COUNT)

        flutterPhase += 2f * PI.toFloat() * cur[P_FLUTTER_HZ] * dt
        bobPhase += 2f * PI.toFloat() * cur[P_BOB_HZ] * dt
        lifePhase += dt
        if (flutterPhase > 1000f) flutterPhase -= (2f * PI.toFloat()) * 150f
        if (bobPhase > 1000f) bobPhase -= (2f * PI.toFloat()) * 150f
        if (lifePhase > 10_000f) lifePhase = 0f

        think = approach(think, thinkTarget, dt / 0.25f)
        talk = approach(talk, if (talking) 1f else 0f, dt / 0.15f)

        if (blinkStart < 0 && now >= nextBlinkAt) {
            blinkStart = now
            blinkDur = (150 + (1f - cur[P_EYE_OPEN]) * 220).toLong()
        }
        if (blinkStart >= 0 && now - blinkStart > blinkDur) {
            blinkStart = -1
            if (doubleBlink) {
                doubleBlink = false
                nextBlinkAt = now + 90
            } else {
                scheduleBlink(now, first = false)
            }
        }

        if (sparksAlive) {
            var alive = false
            for (i in 0 until SPARKS) {
                if (sage[i] >= slife[i]) continue
                sage[i] += dt
                svy[i] += 150f * dt
                svx[i] *= (1f - 0.9f * dt)
                sx[i] += svx[i] * dt
                sy[i] += svy[i] * dt
                srot[i] += svrot[i] * dt
                if (sage[i] < slife[i]) alive = true
            }
            sparksAlive = alive
        }
    }

    private fun scheduleBlink(now: Long, first: Boolean) {
        nextBlinkAt = now + (if (first) 2400 else 3000) + rnd.nextInt(3000)
        doubleBlink = !first && rnd.nextFloat() < 0.18f
    }

    private fun blinkAmount(now: Long): Float {
        if (blinkStart < 0) return 0f
        val t = (now - blinkStart).toFloat() / blinkDur
        return when {
            t < 0.4f -> t / 0.4f
            t < 0.55f -> 1f
            t < 1f -> 1f - (t - 0.55f) / 0.45f
            else -> 0f
        }
    }

    // ================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        tick(now)

        val unit = min(w, h) / BOX
        canvas.save()
        canvas.translate((w - BOX * unit) / 2f, (h - BOX * unit) / 2f)
        canvas.scale(unit, unit)

        // Body motion: hover bob, happy bounce, and the celebration loop-de-loop.
        val bounce = cur[P_BOUNCE]
        val bob = (1f - bounce) * sin(bobPhase) * cur[P_BOB_AMP] - bounce * abs(sin(bobPhase)) * 8f
        var loopX = 0f
        var loopY = 0f
        var loopRot = 0f
        var burst = -1f
        if (celebrateAt >= 0) {
            val p = (now - celebrateAt).toFloat() / LOOP_MS
            if (p < 1f) {
                val e = easeInOut(p)
                val th = 2f * PI.toFloat() * e
                loopX = LOOP_R * sin(th)
                loopY = -LOOP_R * (1f - cos(th))
                loopRot = -360f * e
            }
            val bp = (now - celebrateAt).toFloat() / BURST_MS
            if (bp < 1f) burst = bp
            if (p >= 1f && bp >= 1f && !sparksAlive) celebrateAt = -1
        }

        val ox = ORIGIN_X + loopX
        val oy = ORIGIN_Y + bob + loopY

        // Lamp halo (follows Lumo, never rotates with it).
        val haloPulse = 0.93f + 0.07f * sin(lifePhase * 1.7f)
        canvas.save()
        canvas.translate(ox, oy)
        haloPaint.alpha = (255 * (cur[P_HALO] * haloPulse).coerceIn(0f, 1f)).toInt()
        canvas.drawCircle(0f, -14f, 118f, haloPaint)
        if (burst >= 0f) drawBurstRing(canvas, burst)
        canvas.restore()

        canvas.save()
        canvas.translate(ox, oy)
        canvas.rotate(loopRot, 0f, -10f)
        drawWings(canvas)
        drawBody(canvas)
        canvas.save()
        val tilt = cur[P_TILT] + sin(bobPhase * 0.5f + 0.7f) * 1.6f
        canvas.rotate(tilt, 0f, 4f)
        drawAntennae(canvas)
        drawHead(canvas, now)
        if (think > 0.01f) drawAntennaGlow(canvas)
        canvas.restore()
        canvas.restore()

        canvas.save()
        canvas.translate(ox, oy)
        if (think > 0.01f) drawThinkingDots(canvas)
        if (cur[P_Z] > 0.01f) drawZs(canvas)
        canvas.restore()

        if (sparksAlive) drawSparks(canvas)
        canvas.restore()
    }

    private fun drawWings(canvas: Canvas) {
        val spread = cur[P_WING_SPREAD]
        val amp = cur[P_FLUTTER_AMP]
        val f = sin(flutterPhase)
        val fLow = sin(flutterPhase - 0.55f)
        // Seen from the front, a wing beat reads as the wing narrowing and widening.
        val upperScale = spread * (1f - 0.11f * amp * (0.5f + 0.5f * f))
        val lowerScale = spread * (1f - 0.08f * amp * (0.5f + 0.5f * fLow))
        val upperRot = (1f - spread) * 16f + f * 3.2f * amp
        val lowerRot = -(1f - spread) * 10f + fLow * 2.2f * amp
        for (side in 0..1) {
            canvas.save()
            if (side == 1) canvas.scale(-1f, 1f)
            // lower wing (behind)
            canvas.save()
            canvas.rotate(lowerRot, -4f, 8f)
            canvas.scale(lowerScale, 1f, -4f, 8f)
            canvas.drawPath(lowerWing, lowerWingPaint)
            stroke.strokeWidth = 1.1f
            stroke.color = Palette.withAlpha(0xFF8A1F4E.toInt(), 0.28f)
            canvas.drawPath(lowerVeins, stroke)
            drawEyeSpot(canvas, -46f, 44f, 6.4f, Palette.SKY)
            stroke.strokeWidth = 2f
            stroke.color = WING_RIM
            canvas.drawPath(lowerWing, stroke)
            canvas.restore()
            // upper wing
            canvas.save()
            canvas.rotate(upperRot, -6f, -2f)
            canvas.scale(upperScale, 1f, -6f, -2f)
            canvas.drawPath(upperWing, upperWingPaint)
            stroke.strokeWidth = 1.2f
            stroke.color = Palette.withAlpha(0xFF8A1F4E.toInt(), 0.24f)
            canvas.drawPath(upperVeins, stroke)
            stroke.strokeWidth = 1.8f
            stroke.color = Palette.withAlpha(0xFFFFF1DC.toInt(), 0.34f)
            canvas.drawPath(upperBand, stroke)
            drawEyeSpot(canvas, -64f, -34f, 9.5f, Palette.MINT)
            stroke.strokeWidth = 2.2f
            stroke.color = WING_RIM
            canvas.drawPath(upperWing, stroke)
            canvas.restore()
            // fuzz where the wings meet the body
            canvas.drawCircle(-4f, 2f, 34f, wingRootPaint)
            canvas.restore()
        }
    }

    /** A glossy wing spot: a cream ring, the color, and a soft sheen. Decoration, not a second face. */
    private fun drawEyeSpot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = Palette.withAlpha(0xFFFFF1DC.toInt(), 0.75f)
        canvas.drawCircle(x, y, r + 1.8f, fill)
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
        fill.color = Palette.withAlpha(0xFFFFFFFF.toInt(), 0.32f)
        canvas.drawCircle(x - r * 0.18f, y - r * 0.2f, r * 0.55f, fill)
        fill.color = Palette.withAlpha(0xFFFFFFFF.toInt(), 0.85f)
        canvas.drawCircle(x - r * 0.32f, y - r * 0.34f, r * 0.17f, fill)
    }

    private fun drawBody(canvas: Canvas) {
        // abdomen
        oval.set(-17.5f, 13f, 17.5f, 60f)
        canvas.drawOval(oval, abdomenPaint)
        stroke.strokeWidth = 1.6f
        stroke.color = Palette.withAlpha(BODY_DEEP, 0.55f)
        canvas.drawPath(abdomenBands, stroke)
        fill.color = Palette.withAlpha(0xFFFFFBF2.toInt(), 0.32f)
        oval.set(-9f, 24f, 3f, 52f)
        canvas.drawOval(oval, fill)
        stroke.strokeWidth = 1.3f
        stroke.color = Palette.withAlpha(BODY_DEEP, 0.6f)
        canvas.drawLines(furBody, stroke)
        // fluffy collar
        canvas.drawPath(ruff, ruffPaint)
        stroke.strokeWidth = 1.4f
        stroke.color = Palette.withAlpha(BODY_SHADE, 0.7f)
        canvas.drawPath(ruff, stroke)
    }

    private fun drawAntennae(canvas: Canvas) {
        val sway = sin(lifePhase * 1.3f) * 2.2f + sin(flutterPhase * 0.5f) * 1.2f
        for (side in 0..1) {
            val s = if (side == 0) -1f else 1f
            val perk = if (side == 0) cur[P_ANT_L] else cur[P_ANT_R]
            buildAntenna(s, perk, sway * s)
            stroke.color = ANTENNA
            stroke.strokeWidth = 1.5f
            canvas.drawPath(antennaBarbs, stroke)
            stroke.strokeWidth = 2.6f
            canvas.drawPath(antenna, stroke)
        }
    }

    /** Builds one feathery antenna into [antenna]/[antennaBarbs] (s = -1 left, +1 right). */
    private fun buildAntenna(s: Float, perk: Float, swayDeg: Float) {
        val bx = s * 10f
        val by = HEAD_Y - HEAD_R + 6f
        val len = 50f
        val droop = max(0f, -perk)
        val up = max(0f, perk)
        val angle = Math.toRadians((26.0 - up * 16.0 + droop * 64.0 + swayDeg)).toFloat()
        val tipX = bx + s * sin(angle) * len
        val tipY = by - cos(angle) * len + droop * 10f
        val ca = angle * (0.35f + droop * 0.1f)
        val cx = bx + s * sin(ca) * len * 0.62f
        val cy = by - cos(ca) * len * 0.62f - droop * 9f
        antTipX[if (s < 0) 0 else 1] = tipX
        antTipY[if (s < 0) 0 else 1] = tipY

        antenna.reset()
        antenna.moveTo(bx, by)
        antenna.quadTo(cx, cy, tipX, tipY)

        antennaBarbs.reset()
        val n = 10
        for (i in 1..n) {
            val t = 0.2f + 0.78f * i / n
            val u = 1f - t
            val px = u * u * bx + 2f * u * t * cx + t * t * tipX
            val py = u * u * by + 2f * u * t * cy + t * t * tipY
            var tx = 2f * u * (cx - bx) + 2f * t * (tipX - cx)
            var ty = 2f * u * (cy - by) + 2f * t * (tipY - cy)
            val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
            tx /= tl
            ty /= tl
            val nx = -ty
            val ny = tx
            val barb = 2.2f + 5.6f * sin(PI.toFloat() * (0.15f + 0.85f * t)) * (1f - 0.35f * t)
            for (k in 0..1) {
                val sign = if (k == 0) 1f else -1f
                val dx = (nx * sign * 0.8f + tx * 0.6f) * barb
                val dy = (ny * sign * 0.8f + ty * 0.6f) * barb
                antennaBarbs.moveTo(px, py)
                antennaBarbs.lineTo(px + dx, py + dy)
            }
        }
    }

    private fun drawHead(canvas: Canvas, now: Long) {
        canvas.drawPath(tuft, headPaint)
        canvas.drawPath(head, headPaint)
        stroke.strokeWidth = 1.2f
        stroke.color = Palette.withAlpha(BODY_SHADE, 0.55f)
        canvas.drawLines(furHead, stroke)

        // cheeks
        val cheek = cur[P_CHEEK]
        if (cheek > 0.01f) {
            cheekPaint.alpha = (255 * cheek.coerceIn(0f, 1f)).toInt()
            for (side in 0..1) {
                val s = if (side == 0) -1f else 1f
                canvas.save()
                canvas.translate(s * 24.5f, EYE_Y + 14.5f)
                canvas.scale(1.1f, 0.68f)
                canvas.drawCircle(0f, 0f, 9f, cheekPaint)
                canvas.restore()
            }
        }

        drawEyes(canvas, now)
        drawMouth(canvas)

        if (cur[P_BROW] > 0.01f) {
            stroke.strokeWidth = 2.8f
            stroke.color = Palette.withAlpha(BROW, cur[P_BROW])
            canvas.drawPath(brow, stroke)
            canvas.save()
            canvas.scale(-1f, 1f)
            canvas.drawPath(brow, stroke)
            canvas.restore()
        }
    }

    private fun drawEyes(canvas: Canvas, now: Long) {
        val happy = cur[P_HAPPY_EYES].coerceIn(0f, 1f)
        val open = cur[P_EYE_OPEN] * (1f - blinkAmount(now))
        val sc = cur[P_EYE_SCALE]
        val rx = 10.4f * sc
        // Round eyes squeeze shut into the happy ^ ^ arcs instead of just fading.
        val ry = 12.4f * sc * (1f - 0.8f * happy)
        val roundAlpha = (1f - happy * 1.4f).coerceIn(0f, 1f)
        for (side in 0..1) {
            val s = if (side == 0) -1f else 1f
            val ex = s * EYE_X
            if (roundAlpha > 0.01f) {
                canvas.save()
                canvas.translate(ex, EYE_Y)
                oval.set(-rx, -ry, rx, ry)
                eyePaint.alpha = (255 * roundAlpha).toInt()
                canvas.drawOval(oval, eyePaint)
                // glossy rim light at the bottom
                stroke.strokeWidth = 1.6f
                stroke.color = Palette.withAlpha(EYE_SHINE, 0.9f * roundAlpha)
                oval.set(-rx + 2.2f, -ry + 2.2f, rx - 2.2f, ry - 2.2f)
                canvas.drawArc(oval, 35f, 110f, false, stroke)
                // highlights
                fill.color = Palette.withAlpha(0xFFFFFFFF.toInt(), roundAlpha)
                canvas.drawCircle(-rx * 0.34f, -ry * 0.38f, 3.9f * sc, fill)
                canvas.drawCircle(rx * 0.36f, ry * 0.34f, 1.8f * sc, fill)
                // eyelid: sleepy, concerned and blinking
                val cover = 1f - open
                if (cover > 0.02f) {
                    eyeClip.reset()
                    oval.set(-rx - 0.6f, -ry - 0.6f, rx + 0.6f, ry + 0.6f)
                    eyeClip.addOval(oval, Path.Direction.CW)
                    canvas.save()
                    canvas.clipPath(eyeClip)
                    val lidY = -ry + 2f * ry * cover.coerceAtMost(1f) + (if (cover >= 0.98f) 2f else 0f)
                    oval.set(-rx - 4f, lidY - 2f * ry - 6f, rx + 4f, lidY)
                    fill.color = Palette.withAlpha(LID, roundAlpha)
                    canvas.drawOval(oval, fill)
                    stroke.strokeWidth = 1.8f
                    stroke.color = Palette.withAlpha(LASH, roundAlpha)
                    canvas.drawArc(oval, 15f, 150f, false, stroke)
                    canvas.restore()
                }
                canvas.restore()
            }
            if (happy > 0.01f) {
                canvas.save()
                canvas.translate(ex, EYE_Y + 1f)
                stroke.strokeWidth = 3.8f
                stroke.color = Palette.withAlpha(EYE, happy)
                canvas.drawPath(happyEye, stroke)
                canvas.restore()
            }
        }
    }

    private fun drawMouth(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, MOUTH_Y)
        val keep = 1f - talk
        val o = cur[P_MOUTH_O] * keep
        val smileW = cur[P_MOUTH_SMILE] * keep
        val sleepy = cur[P_MOUTH_SLEEPY] * keep
        val wavy = cur[P_MOUTH_WAVY] * keep
        if (o > 0.01f) {
            fill.color = Palette.withAlpha(MOUTH, o)
            oval.set(-3.1f, -3.4f, 3.1f, 3.9f)
            canvas.drawOval(oval, fill)
            fill.color = Palette.withAlpha(Palette.BERRY, 0.7f * o)
            oval.set(-1.8f, 0.8f, 1.8f, 3.2f)
            canvas.drawOval(oval, fill)
        }
        if (smileW > 0.01f) {
            fill.color = Palette.withAlpha(MOUTH, smileW)
            canvas.drawPath(smile, fill)
            fill.color = Palette.withAlpha(TONGUE, smileW)
            canvas.drawPath(tongue, fill)
        }
        if (sleepy > 0.01f) {
            stroke.strokeWidth = 2f
            stroke.color = Palette.withAlpha(MOUTH, sleepy)
            canvas.drawPath(sleepyMouth, stroke)
        }
        if (wavy > 0.01f) {
            stroke.strokeWidth = 2.1f
            stroke.color = Palette.withAlpha(MOUTH, wavy)
            canvas.drawPath(wavyMouth, stroke)
        }
        if (talk > 0.01f) {
            val chatter = 0.5f + 0.5f * sin(lifePhase * 21f) * (0.6f + 0.4f * sin(lifePhase * 7.3f))
            val oh = 1.4f + 3.6f * chatter
            fill.color = Palette.withAlpha(MOUTH, talk)
            oval.set(-3.6f, -oh * 0.6f, 3.6f, oh)
            canvas.drawOval(oval, fill)
        }
        canvas.restore()
    }

    private fun drawAntennaGlow(canvas: Canvas) {
        val pulse = 0.55f + 0.45f * sin(lifePhase * 6f)
        for (side in 0..1) {
            canvas.save()
            canvas.translate(antTipX[side], antTipY[side])
            val r = 7f + 2f * pulse
            canvas.scale(r, r)
            glowPaint.alpha = (255 * think * (0.55f + 0.45f * pulse)).toInt()
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restore()
        }
    }

    /**
     * Three little lamp sparks circling the crown of the head like a thought; the ones passing in
     * front are bigger and brighter.
     */
    private fun drawThinkingDots(canvas: Canvas) {
        val base = lifePhase * 2.4f
        for (i in 0..2) {
            val th = base + i * (2f * PI.toFloat() / 3f)
            val x = cos(th) * 48f
            val y = HEAD_Y - HEAD_R - 4f + sin(th) * 11f
            val depth = 0.5f + 0.5f * sin(th)
            val r = 10f + 6f * depth
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(r, r)
            glowPaint.alpha = (255 * think * (0.5f + 0.5f * depth)).toInt()
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restore()
            fill.color = Palette.withAlpha(0xFFFFE6B5.toInt(), think * (0.75f + 0.25f * depth))
            canvas.drawCircle(x, y, 3.4f + 2.4f * depth, fill)
        }
    }

    private fun drawZs(canvas: Canvas) {
        val z = cur[P_Z]
        zPaint.typeface = Fonts.display
        for (i in 0..2) {
            var p = (lifePhase / Z_CYCLE + i / 3f) % 1f
            if (p < 0f) p += 1f
            val alpha = when {
                p < 0.2f -> p / 0.2f
                p > 0.6f -> (1f - p) / 0.4f
                else -> 1f
            } * z * 0.9f
            if (alpha <= 0.01f) continue
            zPaint.textSize = 12f + 12f * p
            zPaint.alpha = (255 * alpha).toInt()
            val x = 30f + 24f * p + sin(p * 9f + i) * 3f
            val y = HEAD_Y - 30f - 52f * p
            canvas.drawText(Z, x, y, zPaint)
        }
    }

    private fun drawBurstRing(canvas: Canvas, p: Float) {
        val e = 1f - (1f - p) * (1f - p)
        val fade = (1f - p) * (1f - p) * (1f - p)
        stroke.strokeWidth = 9f * (1f - p) + 1f
        stroke.color = Palette.withAlpha(burstColor, 0.7f * fade)
        canvas.drawCircle(0f, -14f, 40f + 72f * e, stroke)
        stroke.strokeWidth = 3f * (1f - p) + 0.5f
        stroke.color = Palette.withAlpha(Palette.CREAM, 0.6f * fade)
        canvas.drawCircle(0f, -14f, 36f + 66f * e, stroke)
    }

    private fun drawSparks(canvas: Canvas) {
        for (i in 0 until SPARKS) {
            if (sage[i] >= slife[i]) continue
            val life = sage[i] / slife[i]
            val alpha = if (life < 0.7f) 1f else (1f - life) / 0.3f
            fill.color = Palette.withAlpha(scolor[i], alpha)
            canvas.save()
            canvas.translate(sx[i], sy[i])
            canvas.rotate(srot[i])
            val sz = ssize[i] * (1f - 0.4f * life)
            canvas.scale(sz, sz)
            canvas.drawPath(spark, fill)
            canvas.restore()
        }
    }

    // ================================================================== geometry builders

    private fun buildWings() {
        // Upper wing (left side; the right side is mirrored at draw time).
        val upperOuter = Path().apply {
            moveTo(-84f, -70f)
            cubicTo(-100f, -70f, -108f, -52f, -102f, -30f)
            cubicTo(-97f, -12f, -78f, 10f, -48f, 15f)
        }
        upperWing.moveTo(-6f, -8f)
        upperWing.cubicTo(-20f, -40f, -52f, -70f, -84f, -70f)
        appendScalloped(upperWing, upperOuter, bumps = 7, depth = 3.4f, cx = -60f, cy = -30f)
        upperWing.cubicTo(-30f, 18f, -14f, 10f, -6f, 4f)
        upperWing.close()

        upperBand.moveTo(-70f, -62f)
        upperBand.cubicTo(-84f, -46f, -84f, -16f, -60f, 4f)
        upperVeins.moveTo(-8f, -3f)
        upperVeins.quadTo(-40f, -32f, -78f, -58f)
        upperVeins.moveTo(-8f, -1f)
        upperVeins.quadTo(-50f, -18f, -96f, -32f)
        upperVeins.moveTo(-8f, 1f)
        upperVeins.quadTo(-46f, 0f, -80f, -2f)

        val lowerOuter = Path().apply {
            moveTo(-62f, 26f)
            cubicTo(-78f, 38f, -72f, 64f, -52f, 67f)
            cubicTo(-36f, 70f, -22f, 58f, -13f, 42f)
        }
        lowerWing.moveTo(-4f, 6f)
        lowerWing.cubicTo(-20f, 8f, -46f, 14f, -62f, 26f)
        appendScalloped(lowerWing, lowerOuter, bumps = 6, depth = 2.8f, cx = -40f, cy = 36f)
        lowerWing.cubicTo(-7f, 31f, -3f, 18f, -4f, 6f)
        lowerWing.close()

        lowerVeins.moveTo(-6f, 9f)
        lowerVeins.quadTo(-34f, 20f, -58f, 40f)
        lowerVeins.moveTo(-6f, 11f)
        lowerVeins.quadTo(-26f, 34f, -38f, 60f)
    }

    private fun buildBody() {
        val ruffBase = Path().apply { addCircle(0f, 8f, 25.5f, Path.Direction.CW) }
        appendScalloped(ruff, ruffBase, bumps = 14, depth = 3.2f, cx = 0f, cy = 8f, closed = true)
        ruff.close()

        val headBase = Path().apply { addCircle(0f, HEAD_Y, HEAD_R, Path.Direction.CW) }
        appendScalloped(head, headBase, bumps = 30, depth = 1.6f, cx = 0f, cy = HEAD_Y, closed = true)
        head.close()

        // a soft forehead tuft
        tuft.moveTo(-6f, HEAD_Y - HEAD_R + 3f)
        tuft.quadTo(-4f, HEAD_Y - HEAD_R - 5f, 1f, HEAD_Y - HEAD_R - 4f)
        tuft.quadTo(-1f, HEAD_Y - HEAD_R, 3f, HEAD_Y - HEAD_R + 2f)
        tuft.quadTo(5f, HEAD_Y - HEAD_R - 3f, 9f, HEAD_Y - HEAD_R - 1f)
        tuft.quadTo(8f, HEAD_Y - HEAD_R + 3f, 6f, HEAD_Y - HEAD_R + 5f)
        tuft.close()

        abdomenBands.moveTo(-15f, 33f)
        abdomenBands.quadTo(0f, 38f, 15f, 33f)
        abdomenBands.moveTo(-14f, 43f)
        abdomenBands.quadTo(0f, 48f, 14f, 43f)
        abdomenBands.moveTo(-10f, 52f)
        abdomenBands.quadTo(0f, 56f, 10f, 52f)

        spark.moveTo(0f, -1f)
        spark.quadTo(0.18f, -0.18f, 1f, 0f)
        spark.quadTo(0.18f, 0.18f, 0f, 1f)
        spark.quadTo(-0.18f, 0.18f, -1f, 0f)
        spark.quadTo(-0.18f, -0.18f, 0f, -1f)
        spark.close()
    }

    private fun buildFace() {
        happyEye.moveTo(-8f, 2.5f)
        happyEye.quadTo(0f, -10f, 8f, 2.5f)

        smile.moveTo(-7.5f, -2.2f)
        smile.quadTo(0f, -0.6f, 7.5f, -2.2f)
        smile.quadTo(6.2f, 7.4f, 0f, 7.6f)
        smile.quadTo(-6.2f, 7.4f, -7.5f, -2.2f)
        smile.close()
        tongue.moveTo(-3.6f, 5.4f)
        tongue.quadTo(0f, 1.4f, 3.6f, 5.4f)
        tongue.quadTo(2f, 7.5f, 0f, 7.5f)
        tongue.quadTo(-2f, 7.5f, -3.6f, 5.4f)
        tongue.close()

        sleepyMouth.moveTo(-3.8f, 0f)
        sleepyMouth.quadTo(0f, 2.4f, 3.8f, 0f)

        wavyMouth.moveTo(-7.5f, 0.6f)
        wavyMouth.quadTo(-5.6f, -2.4f, -3.75f, 0.2f)
        wavyMouth.quadTo(-1.9f, 2.6f, 0f, 0.2f)
        wavyMouth.quadTo(1.9f, -2.2f, 3.75f, 0.2f)
        wavyMouth.quadTo(5.6f, 2.6f, 7.5f, 0f)

        // Left worried brow: outer end low, inner end lifted. Mirrored for the right eye.
        brow.moveTo(-EYE_X - 8f, EYE_Y - 14.5f)
        brow.quadTo(-EYE_X - 1f, EYE_Y - 15.5f, -EYE_X + 6.5f, EYE_Y - 20.5f)
    }

    /**
     * Samples [base] and appends it to [target] with rounded scallops pushed outward (away from
     * cx, cy). Open edges fade the scallops in and out at their ends so they join smoothly.
     */
    private fun appendScalloped(
        target: Path,
        base: Path,
        bumps: Int,
        depth: Float,
        cx: Float,
        cy: Float,
        closed: Boolean = false,
    ) {
        val pm = PathMeasure(base, false)
        val len = pm.length
        val samples = bumps * 12
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0..samples) {
            val u = i.toFloat() / samples
            pm.getPosTan(len * u, pos, tan)
            var nx = tan[1]
            var ny = -tan[0]
            if (nx * (pos[0] - cx) + ny * (pos[1] - cy) < 0f) {
                nx = -nx
                ny = -ny
            }
            val window = if (closed) 1f else min(1f, min(u / 0.08f, (1f - u) / 0.08f))
            val bump = sqrt(abs(sin(PI.toFloat() * bumps * u))) * depth * window
            val x = pos[0] + nx * bump
            val y = pos[1] + ny * bump
            if (closed && i == 0) target.moveTo(x, y) else target.lineTo(x, y)
        }
    }

    /** Short fur strokes around a ring, as [x0,y0,x1,y1] quads for drawLines. */
    private fun furRing(cx: Float, cy: Float, r0: Float, r1: Float, count: Int, slant: Float, seed: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray(count * 4)
        for (i in 0 until count) {
            val a = (2.0 * PI * i / count + r.nextDouble(-0.06, 0.06)).toFloat()
            val inner = r0 + r.nextFloat() * 1.5f
            val outer = r1 - r.nextFloat() * 2.2f
            val ca = cos(a)
            val sa = sin(a)
            out[i * 4] = cx + ca * inner
            out[i * 4 + 1] = cy + sa * inner
            out[i * 4 + 2] = cx + (ca - sa * slant) * outer
            out[i * 4 + 3] = cy + (sa + ca * slant) * outer
        }
        return out
    }

    private fun approach(v: Float, target: Float, step: Float): Float =
        if (v < target) min(target, v + step) else max(target, v - step)

    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun easeInOut(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    companion object {
        private val USE_CHOREOGRAPHER = Build.FINGERPRINT?.contains("robolectric") != true
        private const val BOX = 240f
        private const val ORIGIN_X = 120f
        private const val ORIGIN_Y = 134f
        private const val HEAD_Y = -26f
        private const val HEAD_R = 34f
        private const val EYE_X = 14.5f
        private const val EYE_Y = -23f
        private const val MOUTH_Y = -6f
        private const val BLEND_MS = 300L
        private const val LOOP_MS = 1100L
        private const val BURST_MS = 650L
        private const val CELEBRATE_HAPPY_MS = 1900L
        private const val LOOP_R = 15f
        private const val Z_CYCLE = 2.8f
        private const val SPARKS = 30
        private const val Z = "z"

        private const val BODY = 0xFFF3E3C3.toInt()
        private const val BODY_LIGHT = 0xFFFFF6E6.toInt()
        private const val BODY_SHADE = 0xFFDDBF8F.toInt()
        private const val BODY_DEEP = 0xFFC9A574.toInt()
        private const val EYE = 0xFF1A1326.toInt()
        private const val EYE_SHINE = 0xFF5A4A86.toInt()
        private const val LID = 0xFFEBD3A8.toInt()
        private const val LASH = 0xFF7A5A3E.toInt()
        private const val MOUTH = 0xFF4A1F3A.toInt()
        private const val TONGUE = 0xFFFF7BA3.toInt()
        private const val ANTENNA = 0xFFE6CC9C.toInt()
        private const val BROW = 0xFF9C744A.toInt()
        private const val WING_RIM = 0xFFFFC7AE.toInt()

        // Mood parameters, blended over 300 ms.
        private const val P_EYE_OPEN = 0
        private const val P_EYE_SCALE = 1
        private const val P_HAPPY_EYES = 2
        private const val P_BROW = 3
        private const val P_MOUTH_O = 4
        private const val P_MOUTH_SMILE = 5
        private const val P_MOUTH_SLEEPY = 6
        private const val P_MOUTH_WAVY = 7
        private const val P_TILT = 8
        private const val P_ANT_L = 9
        private const val P_ANT_R = 10
        private const val P_WING_SPREAD = 11
        private const val P_FLUTTER_HZ = 12
        private const val P_FLUTTER_AMP = 13
        private const val P_BOB_AMP = 14
        private const val P_BOB_HZ = 15
        private const val P_BOUNCE = 16
        private const val P_HALO = 17
        private const val P_Z = 18
        private const val P_CHEEK = 19
        private const val P_COUNT = 20

        private fun params(
            eyeOpen: Float, eyeScale: Float, happyEyes: Float, brow: Float,
            o: Float, smile: Float, sleepyMouth: Float, wavy: Float,
            tilt: Float, antL: Float, antR: Float, wings: Float, flutterHz: Float, flutterAmp: Float,
            bobAmp: Float, bobPeriodS: Float, bounce: Float, halo: Float, z: Float, cheek: Float,
        ) = floatArrayOf(
            eyeOpen, eyeScale, happyEyes, brow, o, smile, sleepyMouth, wavy, tilt, antL, antR,
            wings, flutterHz, flutterAmp, bobAmp, 1f / bobPeriodS, bounce, halo, z, cheek,
        )

        /** Indexed by Mood.ordinal: CURIOUS, HAPPY, SLEEPY, CONCERNED. */
        private val PARAMS = arrayOf(
            params(1f, 1.1f, 0f, 0f, 1f, 0f, 0f, 0f, -12f, 0.6f, -0.05f, 1f, 1.6f, 1f, 4f, 2.4f, 0f, 0.9f, 0f, 0.55f),
            params(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0.35f, 0.35f, 1.04f, 3.4f, 1.5f, 3f, 1.25f, 1f, 1f, 0f, 0.95f),
            params(0.4f, 0.97f, 0f, 0f, 0f, 0f, 1f, 0f, 7f, -0.85f, -0.7f, 0.93f, 0.55f, 0.6f, 3.2f, 3.6f, 0f, 0.45f, 1f, 0.4f),
            params(0.88f, 1f, 0f, 1f, 0f, 0f, 0f, 1f, -3f, -0.3f, -0.3f, 0.74f, 1.1f, 0.45f, 2.4f, 2.4f, 0f, 0.7f, 0f, 0.35f),
        )
    }
}
