package tv.wander.cards

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.ui.Fonts
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import kotlin.math.PI
import kotlin.math.sin

/**
 * Small hand-made pieces shared by the cards and quest overlays: Lumo's moth mark, remote keycaps,
 * chips and the emoji fallbacks. Internal to the app module.
 */
internal object Craft {

    /** Lumo's mark: a fuzzy moth seen from above. [span] is the wingspan in px, centered on (cx, cy). */
    fun drawMoth(canvas: Canvas, cx: Float, cy: Float, span: Float, color: Int, paint: Paint, wingOpen: Float = 1f) {
        val s = span
        val open = wingOpen.coerceIn(0.35f, 1f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        val path = Path()
        val oval = RectF()
        for (side in intArrayOf(-1, 1)) {
            val k = side * open
            // Hindwing: a round lobe tucked under the forewing.
            path.reset()
            path.moveTo(cx + k * 0.05f * s, cy - 0.01f * s)
            path.quadTo(cx + k * 0.33f * s, cy + 0.03f * s, cx + k * 0.30f * s, cy + 0.21f * s)
            path.quadTo(cx + k * 0.21f * s, cy + 0.35f * s, cx + k * 0.05f * s, cy + 0.25f * s)
            path.close()
            paint.color = Palette.withAlpha(color, 0.62f)
            canvas.drawPath(path, paint)
            // Forewing: the broad moth delta, apex out to the side.
            path.reset()
            path.moveTo(cx + k * 0.04f * s, cy - 0.14f * s)
            path.quadTo(cx + k * 0.30f * s, cy - 0.28f * s, cx + k * 0.50f * s, cy - 0.11f * s)
            path.quadTo(cx + k * 0.48f * s, cy + 0.05f * s, cx + k * 0.34f * s, cy + 0.13f * s)
            path.quadTo(cx + k * 0.17f * s, cy + 0.12f * s, cx + k * 0.05f * s, cy + 0.04f * s)
            path.close()
            paint.color = Palette.withAlpha(color, 0.96f)
            canvas.drawPath(path, paint)
            // A pale spot catching the lamp light.
            paint.color = Palette.withAlpha(Palette.CREAM, 0.6f)
            canvas.drawCircle(cx + k * 0.27f * s, cy - 0.06f * s, 0.042f * s, paint)
        }
        // Stout, fuzzy body with banded abdomen.
        paint.color = Palette.withAlpha(Palette.INK, 0.5f)
        oval.set(cx - 0.085f * s, cy - 0.2f * s, cx + 0.085f * s, cy + 0.34f * s)
        canvas.drawOval(oval, paint)
        paint.color = color
        oval.set(cx - 0.065f * s, cy - 0.06f * s, cx + 0.065f * s, cy + 0.32f * s)
        canvas.drawOval(oval, paint)
        canvas.drawCircle(cx, cy - 0.09f * s, 0.075f * s, paint)
        canvas.drawCircle(cx, cy - 0.175f * s, 0.048f * s, paint)
        if (s >= 18f) {
            paint.color = Palette.withAlpha(Palette.INK, 0.32f)
            for (band in floatArrayOf(0.07f, 0.14f, 0.21f)) {
                canvas.drawRect(cx - 0.05f * s, cy + band * s, cx + 0.05f * s, cy + (band + 0.022f) * s, paint)
            }
        }
        // Feathery antennae: little fronds sweeping up and out.
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(cx + side * 0.12f * s, cy - 0.31f * s)
            canvas.rotate(side * 38f)
            paint.color = Palette.withAlpha(color, 0.55f)
            oval.set(-0.04f * s, -0.14f * s, 0.04f * s, 0.14f * s)
            canvas.drawOval(oval, paint)
            paint.color = color
            canvas.drawRect(-0.011f * s, -0.14f * s, 0.011f * s, 0.14f * s, paint)
            canvas.restore()
        }
    }

    /** A pill chip, e.g. the provenance badge. */
    fun chip(context: Context, text: String, textColor: Int, fill: Int, stroke: Int = 0, bold: Boolean = true): TextView =
        Ui.text(context, TypeScale.CAPTION, textColor, if (bold) Fonts.bodyBold else Fonts.body, text).apply {
            background = Ui.roundRect(context, fill, 999f, stroke, if (stroke != 0) 1.5f else 0f)
            setPadding(context.dpi(14f), context.dpi(7f), context.dpi(14f), context.dpi(7f))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    /** One hint: a row of keycaps followed by what they do. */
    class Hint(val keys: List<Key>, val label: String)

    sealed class Key {
        object Left : Key()
        object Right : Key()
        object Up : Key()
        object Down : Key()
        class Label(val text: String) : Key()
    }

    /** "◀ ▶ flip · OK read aloud · Back close" as real-looking remote keycaps. */
    fun hintRow(context: Context, hints: List<Hint>, keycaps: MutableList<KeycapView>? = null): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        hints.forEachIndexed { i, hint ->
            if (i > 0) {
                addView(Ui.text(context, TypeScale.CAPTION, Palette.withAlpha(Palette.MUTED, 0.5f), Fonts.bodyBold, "·").apply {
                    setPadding(context.dpi(14f), 0, context.dpi(14f), 0)
                })
            }
            hint.keys.forEachIndexed { k, key ->
                val cap = KeycapView(context, key)
                keycaps?.add(cap)
                addView(cap, LinearLayout.LayoutParams(-2, context.dpi(30f)).apply {
                    if (k > 0) leftMargin = context.dpi(5f)
                })
            }
            addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, hint.label).apply {
                setPadding(context.dpi(9f), 0, 0, 0)
            })
        }
    }

    /** Emoji for a card icon keyword when the card has none. */
    fun emojiForIcon(icon: String): String? {
        val key = icon.trim().lowercase()
        if (key.isEmpty()) return null
        ICONS[key]?.let { return it }
        return ICONS.entries.firstOrNull { key.startsWith(it.key) || it.key.startsWith(key) && key.length >= 3 }?.value
    }

    /** "https://www.en.wikipedia.org/wiki/x" -> "en.wikipedia.org". Pure string work, never throws. */
    fun domainOf(url: String): String {
        var s = url.trim()
        if (s.isEmpty()) return ""
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        s = s.lowercase().removePrefix("www.").removePrefix("m.")
        return if (s.contains('.') && s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) s else ""
    }

    /** The first user-perceived character (one whole emoji, never half a surrogate pair). */
    fun firstGlyph(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return try {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(t)
            val end = it.next()
            if (end <= 0) t else t.substring(0, end)
        } catch (e: Exception) {
            t.substring(0, t.offsetByCodePoints(0, 1))
        }
    }

    /** Endless 0..1 phase for shimmers and flutters. Remember to cancel it. */
    fun loop(periodMs: Long, onFrame: (Float) -> Unit): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = periodMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onFrame(it.animatedValue as Float) }
    }

    private val ICONS = linkedMapOf(
        "speed" to "⚡", "fast" to "⚡", "planet" to "🪐", "space" to "🚀", "rocket" to "🚀", "moon" to "🌙",
        "sun" to "☀️", "star" to "⭐", "animal" to "🐾", "bird" to "🐦", "fish" to "🐟", "ocean" to "🌊",
        "sea" to "🌊", "water" to "💧", "plant" to "🌱", "tree" to "🌳", "forest" to "🌲", "flower" to "🌸",
        "food" to "🍎", "history" to "🏛️", "dinosaur" to "🦕", "fossil" to "🦴", "music" to "🎵", "art" to "🎨",
        "science" to "🔬", "book" to "📖", "idea" to "💡", "heart" to "❤️", "world" to "🌍", "earth" to "🌍",
        "map" to "🗺️", "time" to "⏳", "money" to "🪙", "sport" to "⚽", "weather" to "⛅", "fire" to "🔥",
        "ice" to "❄️", "cold" to "❄️", "mountain" to "⛰️", "volcano" to "🌋", "city" to "🏙️", "people" to "👥",
        "family" to "👪", "health" to "🩺", "body" to "💪", "strength" to "💪", "tech" to "💻", "computer" to "💻",
        "robot" to "🤖", "size" to "📏", "number" to "🔢", "question" to "❓", "fact" to "✨", "light" to "💡",
        "game" to "🎲", "movie" to "🎬", "home" to "🏠", "car" to "🚗", "train" to "🚆", "plane" to "✈️",
        "bug" to "🐞", "insect" to "🐜", "egg" to "🥚", "danger" to "⚠️", "record" to "🏆", "trophy" to "🏆", "balance" to "⚖️",
        "sound" to "🔊", "eye" to "👀", "brain" to "🧠", "climate" to "🌡️", "energy" to "⚡", "color" to "🎨",
    )
}

/** A remote key drawn as a little keycap: rounded, with a darker lip, holding a label or an arrow. */
internal class KeycapView(context: Context, private val key: Craft.Key) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    var lit: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val label = (key as? Craft.Key.Label)?.text.orEmpty()

    init {
        paint.typeface = Fonts.bodyBold
        paint.textSize = context.sp(TypeScale.CAPTION)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: dpi(30f)
        val w = if (label.isEmpty()) h else (paint.measureText(label) + dp(22f)).toInt().coerceAtLeast(h)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(8f)
        val lip = dp(3f)
        paint.style = Paint.Style.FILL
        paint.color = blend(0xFF1A1428.toInt(), Palette.withAlpha(Palette.LAMP, 0.55f), lit)
        rect.set(0f, lip, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = blend(Palette.DUSK, 0xFF4A3712.toInt(), lit)
        rect.set(0f, 0f, w, h - lip)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = blend(Palette.HAIRLINE, Palette.LAMP, lit)
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        val cx = w / 2f
        val cy = (h - lip) / 2f
        paint.color = blend(Palette.CREAM, Palette.LAMP, lit)
        val a = dp(5.5f)
        path.reset()
        when (key) {
            Craft.Key.Left -> { path.moveTo(cx - a, cy); path.lineTo(cx + a * 0.75f, cy - a); path.lineTo(cx + a * 0.75f, cy + a); path.close() }
            Craft.Key.Right -> { path.moveTo(cx + a, cy); path.lineTo(cx - a * 0.75f, cy - a); path.lineTo(cx - a * 0.75f, cy + a); path.close() }
            Craft.Key.Up -> { path.moveTo(cx, cy - a); path.lineTo(cx - a, cy + a * 0.75f); path.lineTo(cx + a, cy + a * 0.75f); path.close() }
            Craft.Key.Down -> { path.moveTo(cx, cy + a); path.lineTo(cx - a, cy - a * 0.75f); path.lineTo(cx + a, cy - a * 0.75f); path.close() }
            is Craft.Key.Label -> {
                paint.textAlign = Paint.Align.CENTER
                val fm = paint.fontMetrics
                canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
            }
        }
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        val inv = 1f - t
        val ar = (a shr 16 and 0xFF) * inv + (b shr 16 and 0xFF) * t
        val ag = (a shr 8 and 0xFF) * inv + (b shr 8 and 0xFF) * t
        val ab = (a and 0xFF) * inv + (b and 0xFF) * t
        return (0xFF shl 24) or (ar.toInt() shl 16) or (ag.toInt() shl 8) or ab.toInt()
    }
}

/** Lumo's moth mark as a view; [flutter] makes the wings beat softly (cancelled when detached). */
internal class MothMarkView(context: Context, private val color: Int = Palette.LAMP) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wing = 1f
    private var animator: ValueAnimator? = null

    fun flutter(on: Boolean) {
        animator?.cancel()
        animator = null
        wing = 1f
        if (on) {
            animator = Craft.loop(520) { t ->
                wing = 0.62f + 0.38f * ((sin(t * 2 * PI) + 1) / 2).toFloat()
                invalidate()
            }.also { it.start() }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val span = minOf(width.toFloat(), height.toFloat() * 1.1f)
        Craft.drawMoth(canvas, width / 2f, height / 2f + span * 0.04f, span, color, paint, wing)
    }
}

/** Scaled pixels, for text drawn straight onto a Canvas. */
internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

/** Typeface-safe paint for emoji (the body fonts have no emoji glyphs). */
internal fun emojiPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.DEFAULT
    textAlign = Paint.Align.CENTER
}

/** Lumo's moth as a small Drawable, for chips and compound drawables. */
internal class MothDrawable(private val color: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBounds(0, 0, sizePx, sizePx)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        Craft.drawMoth(canvas, b.exactCenterX(), b.exactCenterY(), b.width().toFloat(), color, paint)
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The scrim Wander lays over a web page: deep night ink with a pool of lamp light where the content sits,
 * so overlays feel lit from above instead of grey-boxed.
 */
internal class ScrimGlowDrawable(
    private val glowX: Float = 0.5f,
    private val glowY: Float = 0.48f,
    private val glowAlpha: Float = 0.11f,
    private val base: Int = Palette.SCRIM,
    /** Extra night ink on top of the scrim so busy pages don't fight the content. */
    private val deepen: Float = 0.22f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private var vignette: Shader? = null
    private val shaderFor = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.shader = null
        paint.color = base
        canvas.drawRect(b, paint)
        if (deepen > 0f) {
            paint.color = Palette.withAlpha(Palette.INK, deepen)
            canvas.drawRect(b, paint)
        }
        if (shader == null || shaderFor != b) {
            shaderFor.set(b)
            vignette = RadialGradient(
                b.exactCenterX(), b.exactCenterY(), Math.hypot(b.width().toDouble(), b.height().toDouble()).toFloat() / 2f,
                intArrayOf(0, 0, Palette.withAlpha(Palette.INK, 0.6f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            shader = RadialGradient(
                b.left + b.width() * glowX, b.top + b.height() * glowY, b.width() * 0.46f,
                intArrayOf(Palette.withAlpha(Palette.LAMP, glowAlpha), Palette.withAlpha(Palette.LAMP, glowAlpha * 0.35f), 0),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        paint.color = 0xFFFFFFFF.toInt() // the shaders carry their own alpha
        paint.shader = vignette
        canvas.drawRect(b, paint)
        paint.shader = shader
        canvas.drawRect(b, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The shared [LampBackdrop], drawn from a fresh instance each time. LampBackdrop reuses one Paint whose
 * last mote alpha leaks into its glow gradients on every redraw after the first; a fresh instance per
 * draw keeps the lamp glow steady. Draws are rare (static background), so the allocation is cheap.
 */
internal class SteadyLampBackdrop(private val seed: Int) : Drawable() {
    override fun draw(canvas: Canvas) {
        LampBackdrop(seed).also { it.bounds = bounds }.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Entry point guard for overlay methods MainActivity calls: hops to the main thread if needed and logs
 * instead of crashing, so a bad payload degrades to "nothing happened" rather than a dead app.
 */
internal fun uiSafe(what: String, block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        safely(what, block)
    } else {
        tv.wander.ui.Async.main { safely(what, block) }
    }
}

/**
 * Remembers the key press an overlay acted on (it acts on ACTION_DOWN), so the matching ACTION_UP is
 * swallowed even after the overlay has closed and never leaks into the layer underneath. Matching is by
 * the press's down time, so an unrelated later press is never eaten.
 */
internal class KeySwallow {
    private var keyCode = KeyEvent.KEYCODE_UNKNOWN
    private var downTime = Long.MIN_VALUE

    fun remember(down: KeyEvent) {
        keyCode = down.keyCode
        downTime = down.downTime
    }

    /** True (and forgets it) when [event] is the release of the remembered press. */
    fun swallows(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP || event.keyCode != keyCode || event.downTime != downTime) return false
        keyCode = KeyEvent.KEYCODE_UNKNOWN
        downTime = Long.MIN_VALUE
        return true
    }
}

/** Runs a listener callback without letting a bug on the other side take the overlay down with it. */
internal inline fun safely(what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        android.util.Log.e("Wander", "listener failed: $what", t)
    }
}
