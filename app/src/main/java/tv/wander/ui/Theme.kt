package tv.wander.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.roundToInt
import kotlin.random.Random

// Wander's visual language: a lamp-lit living room at night. Deep aubergine ink, warm lamp amber for
// focus and accents, cream text, and four player colors. Every screen builds on these tokens.
// Sizes assume Fire TV's 960x540dp canvas (1080p at xhdpi).

object Palette {
    const val INK = 0xFF120E1C.toInt()        // page background
    const val NIGHT = 0xFF1B1529.toInt()      // surface
    const val DUSK = 0xFF261E3A.toInt()       // raised surface
    const val HAIRLINE = 0xFF3A2F57.toInt()   // borders
    const val CREAM = 0xFFF7EFE2.toInt()      // primary text
    const val MUTED = 0xFFB9AECC.toInt()      // secondary text
    const val LAMP = 0xFFFFB547.toInt()       // accent + focus ring
    const val BERRY = 0xFFFF5D8F.toInt()
    const val MINT = 0xFF3DDC97.toInt()
    const val SKY = 0xFF5AB4FF.toInt()
    const val CORAL = 0xFFFF7A6B.toInt()      // errors / "not quite"
    // Over web pages: 94% so a bright white page (and its logos) only hints through behind overlays.
    const val SCRIM = 0xF00B0812.toInt()

    val PLAYERS = intArrayOf(LAMP, BERRY, MINT, SKY)
    val PLAYER_COLOR_NAMES = arrayOf("Sun", "Berry", "Mint", "Sky")

    fun player(index: Int): Int = PLAYERS[((index % 4) + 4) % 4]

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
}

/** Text sizes in sp. Nothing on screen goes below CAPTION: it has to read from the couch. */
object TypeScale {
    const val DISPLAY = 44f
    const val H1 = 32f
    const val H2 = 24f
    const val BODY = 20f
    const val CAPTION = 16f
}

/** Layout constants in dp. SAFE_* is the Fire TV overscan-safe margin. */
object Dimens {
    const val SAFE_H = 48f
    const val SAFE_V = 27f
    const val RADIUS = 20f
    const val RADIUS_BIG = 28f
    const val FOCUS_STROKE = 3f
}

/** Young Serif for display, Atkinson Hyperlegible for everything else. Loaded once from assets/fonts. */
object Fonts {
    var display: Typeface = Typeface.SERIF
        private set
    var body: Typeface = Typeface.DEFAULT
        private set
    var bodyBold: Typeface = Typeface.DEFAULT_BOLD
        private set
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val assets = context.applicationContext.assets
        display = runCatching { Typeface.createFromAsset(assets, "fonts/YoungSerif-Regular.ttf") }.getOrDefault(Typeface.SERIF)
        body = runCatching { Typeface.createFromAsset(assets, "fonts/AtkinsonHyperlegible-Regular.ttf") }.getOrDefault(Typeface.DEFAULT)
        bodyBold = runCatching { Typeface.createFromAsset(assets, "fonts/AtkinsonHyperlegible-Bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    }
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dpi(value: Float): Int = dp(value).roundToInt()

fun View.dp(value: Float): Float = context.dp(value)

fun View.dpi(value: Float): Int = context.dpi(value)

object Ui {
    /** A TextView in the Wander type system. Pass [typeface] = Fonts.display for headings. */
    fun text(
        context: Context,
        sizeSp: Float,
        color: Int = Palette.CREAM,
        typeface: Typeface = Fonts.body,
        text: CharSequence = "",
    ): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        this.typeface = typeface
        this.text = text
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun roundRect(
        context: Context,
        color: Int,
        radiusDp: Float = Dimens.RADIUS,
        strokeColor: Int = 0,
        strokeDp: Float = 0f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(radiusDp)
        setColor(color)
        if (strokeDp > 0f) setStroke(context.dpi(strokeDp), strokeColor)
    }

    /**
     * Makes [view] a D-pad target with Wander's focus treatment: it grows by [scale], lifts, and gets a
     * lamp-amber ring. [onFocus] runs after the built-in animation on every focus change.
     */
    fun makeFocusable(
        view: View,
        radiusDp: Float = Dimens.RADIUS,
        scale: Float = 1.06f,
        ringColor: Int = Palette.LAMP,
        onFocus: ((Boolean) -> Unit)? = null,
    ) {
        val ctx = view.context
        val ring = roundRect(ctx, 0x00000000, radiusDp, ringColor, Dimens.FOCUS_STROKE)
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .translationZ(if (hasFocus) ctx.dp(12f) else 0f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
            v.foreground = if (hasFocus) ring else null
            if (hasFocus) Sfx.focus()
            onFocus?.invoke(hasFocus)
        }
    }

    /** Gentle looping pulse used for "waiting" states (QR panel, checking...). Returns the animator to cancel. */
    fun pulse(view: View, from: Float = 0.55f, to: Float = 1f, periodMs: Long = 1400): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = periodMs / 2
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
}

/**
 * Full-screen backdrop: ink, a warm lamp glow from the top-left, a faint berry glow bottom-right, and
 * a scatter of dust motes catching the light. Static, cheap to draw on a Fire TV Stick.
 */
class LampBackdrop(seed: Int = 7) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motes = Random(seed).let { r -> List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.8f, 0.08f + r.nextFloat() * 0.22f) } }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawColor(Palette.INK)
        // Reset to opaque: the paint's alpha multiplies the shader, and the motes leave it translucent.
        paint.color = 0xFFFFFFFF.toInt()
        paint.shader = RadialGradient(w * 0.12f, h * 0.05f, w * 0.75f, Palette.withAlpha(Palette.LAMP, 0.16f), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = RadialGradient(w * 0.95f, h * 1.05f, w * 0.55f, Palette.withAlpha(Palette.BERRY, 0.10f), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
        val unit = h / 540f
        for (m in motes) {
            // Motes are brighter near the lamp.
            val nearLamp = 1f - (m[0] * 0.6f + m[1] * 0.4f)
            paint.color = Palette.withAlpha(Palette.CREAM, m[3] * (0.4f + nearLamp))
            canvas.drawCircle(m[0] * w, m[1] * h, m[2] * unit, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
