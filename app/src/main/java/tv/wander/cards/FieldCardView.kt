package tv.wander.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import tv.wander.model.Card
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.dp
import java.util.Locale
import kotlin.random.Random

/**
 * One collectible field-guide card, drawn on a Canvas: a dusk body with paper grain, a colored band
 * with the card number, a stamped emoji medallion, the title in Young Serif, one big readable line and
 * a footer with the source and Lumo's moth. [skeleton] draws a shimmering placeholder instead.
 */
internal class FieldCardView(context: Context) : View(context) {
    private var card: Card? = null
    private var number = 1
    private var total = 1
    private var accent = Palette.LAMP
    private var domain = ""

    /** 0 = full color, 1 = pushed back into the dark (neighbours). */
    var dim: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) { field = v; invalidate() }
        }

    var skeleton: Boolean = false
        set(value) { field = value; invalidate() }

    /** Shimmer phase 0..1 for the skeleton. */
    var shimmer: Float = 0f
        set(value) { field = value; if (skeleton) invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint().apply { strokeCap = Paint.Cap.ROUND }
    private val emoji = emojiPaint()
    private val bandText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val numberText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerMatrix = Matrix()

    private val rect = RectF()
    private val bandPath = Path()
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringDash = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    private val ruleDash = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)

    private var bodyShader: Shader? = null
    private var glowShader: Shader? = null
    private var grainLight = FloatArray(0)
    private var grainDark = FloatArray(0)
    private var titleLayout: StaticLayout? = null
    private var lineLayout: StaticLayout? = null
    private var footText = ""
    private var tagText = ""
    private var glyph = ""
    private var glyphKind = Glyph.EMOJI

    private enum class Glyph { EMOJI, LETTER, MOTH }
    private var laidOutW = -1
    private var laidOutH = -1

    init {
        bandText.typeface = Fonts.bodyBold
        bandText.textSize = context.sp(16f)
        bandText.letterSpacing = 0.16f
        numberText.typeface = Fonts.display
        numberText.textSize = context.sp(22f)
        numberText.textAlign = Paint.Align.RIGHT
        titlePaint.typeface = Fonts.display
        titlePaint.color = Palette.CREAM
        linePaint.typeface = Fonts.body
        linePaint.color = Palette.withAlpha(Palette.CREAM, 0.92f)
        footPaint.typeface = Fonts.body
        footPaint.textSize = context.sp(16f)
        footPaint.color = Palette.MUTED
        tagPaint.typeface = Fonts.bodyBold
        tagPaint.textSize = context.sp(16f)
        tagPaint.letterSpacing = 0.14f
        tagPaint.textAlign = Paint.Align.RIGHT
        dashed.style = Paint.Style.STROKE
        letterPaint.typeface = Fonts.display
        letterPaint.textSize = context.sp(44f)
        letterPaint.textAlign = Paint.Align.CENTER
    }

    fun bind(card: Card?, number: Int, total: Int, accent: Int, domain: String) {
        this.card = card
        this.number = number
        this.total = total
        this.accent = accent
        this.domain = domain
        laidOutW = -1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        laidOutW = -1
    }

    private fun layoutContent() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (laidOutW == w && laidOutH == h) return
        laidOutW = w
        laidOutH = h
        bodyShader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2D2445.toInt(), 0xFF1E172E.toInt(), Shader.TileMode.CLAMP)
        glowShader = RadialGradient(w * 0.18f, h * 0.32f, w * 0.85f, Palette.withAlpha(Palette.LAMP, 0.08f), 0, Shader.TileMode.CLAMP)
        val rnd = Random(number * 7919 + total)
        val margin = dp(12f)
        fun scatter(count: Int) = FloatArray(count * 2).also { a ->
            for (i in 0 until count) {
                a[i * 2] = margin + rnd.nextFloat() * (w - 2 * margin)
                a[i * 2 + 1] = dp(BAND) + rnd.nextFloat() * (h - dp(BAND) - margin)
            }
        }
        grainLight = scatter(520)
        grainDark = scatter(420)
        shimmerPaint.shader = LinearGradient(
            0f, 0f, w * 0.45f, 0f,
            intArrayOf(Palette.withAlpha(Palette.HAIRLINE, 1f), Palette.withAlpha(0xFF5A4C80.toInt(), 1f), Palette.withAlpha(Palette.HAIRLINE, 1f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )

        val c = card ?: return
        val pad = dp(PAD)
        val textLeft = pad + dp(MEDAL) + dp(22f)
        val titleWidth = (w - textLeft - pad).toInt().coerceAtLeast(1)
        titleLayout = fit(c.title.trim().ifEmpty { "Did you know?" }, titlePaint, titleWidth, maxLines = 2, sizes = floatArrayOf(34f, 31f, 28f, 26f))
        val lineWidth = (w - 2 * pad).toInt().coerceAtLeast(1)
        lineLayout = fit(c.line.trim(), linePaint, lineWidth, maxLines = 3, sizes = floatArrayOf(22f, 21f, 20f))
        footText = TextUtils.ellipsize(domain.ifEmpty { "this page" }, footPaint, w * 0.52f, TextUtils.TruncateAt.END).toString()
        tagText = c.icon.trim().uppercase().take(14)
        val e = Craft.firstGlyph(c.emoji)
        val mapped = Craft.emojiForIcon(c.icon)
        val iconLetter = c.icon.trim().firstOrNull { it.isLetterOrDigit() }
        when {
            e.isNotEmpty() -> { glyph = e; glyphKind = Glyph.EMOJI }
            mapped != null -> { glyph = mapped; glyphKind = Glyph.EMOJI }
            iconLetter != null -> { glyph = iconLetter.uppercaseChar().toString(); glyphKind = Glyph.LETTER }
            else -> { glyph = ""; glyphKind = Glyph.MOTH }
        }
    }

    /** Largest size in [sizes] (sp) that fits in [maxLines]; the smallest one ellipsizes. */
    private fun fit(text: String, paint: TextPaint, width: Int, maxLines: Int, sizes: FloatArray): StaticLayout {
        for ((i, size) in sizes.withIndex()) {
            paint.textSize = context.sp(size)
            val last = i == sizes.lastIndex
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.08f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val overflow = layout.lineCount > maxLines || (layout.lineCount == maxLines && layout.getEllipsisCount(maxLines - 1) > 0)
            if (!overflow || last) return layout
        }
        throw IllegalStateException("unreachable")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        layoutContent()
        val r = dp(Dimens.RADIUS_BIG)
        val band = dp(BAND)
        val pad = dp(PAD)

        // Body.
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = OPAQUE // a shader is modulated by the paint's alpha; never inherit last frame's
        paint.shader = bodyShader
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = glowShader
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        // Paper grain.
        grainPaint.strokeWidth = dp(1.1f)
        grainPaint.color = Palette.withAlpha(Palette.CREAM, 0.045f)
        canvas.drawPoints(grainLight, grainPaint)
        grainPaint.color = Palette.withAlpha(0xFF000000.toInt(), 0.12f)
        canvas.drawPoints(grainDark, grainPaint)

        // Colored band with rounded top corners.
        val bandColor = if (skeleton) Palette.HAIRLINE else accent
        bandPath.reset()
        rect.set(0f, 0f, w, band)
        bandPath.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        paint.color = bandColor
        canvas.drawPath(bandPath, paint)
        paint.color = Palette.withAlpha(0xFF000000.toInt(), 0.18f)
        canvas.drawRect(0f, band - dp(3f), w, band, paint)

        if (skeleton) {
            drawSkeleton(canvas, w, h, band, pad)
        } else {
            drawContent(canvas, w, h, band, pad)
        }

        // Inner hairline frame and outer edge, like a printed trading card.
        val inset = dp(7f)
        rect.set(inset, inset, w - inset, h - inset)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Palette.withAlpha(Palette.CREAM, 0.13f)
        canvas.drawRoundRect(rect, r - inset, r - inset, paint)
        rect.set(dp(0.75f), dp(0.75f), w - dp(0.75f), h - dp(0.75f))
        paint.strokeWidth = dp(1.5f)
        paint.color = Palette.withAlpha(0xFF6B5C93.toInt(), 0.55f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL

        if (dim > 0f) {
            rect.set(0f, 0f, w, h)
            paint.color = Palette.withAlpha(Palette.INK, dim * 0.62f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    private fun drawContent(canvas: Canvas, w: Float, h: Float, band: Float, pad: Float) {
        // Band labels: the guide's name and the card number.
        val ink = Palette.withAlpha(Palette.INK, 0.86f)
        bandText.color = ink
        val bandMid = band / 2f - dp(1.5f)
        val fmB = bandText.fontMetrics
        canvas.drawText("LUMO'S FIELD GUIDE", pad, bandMid - (fmB.ascent + fmB.descent) / 2f, bandText)
        numberText.color = Palette.INK
        val fmN = numberText.fontMetrics
        canvas.drawText(String.format(Locale.ROOT, "%02d / %02d", number, total), w - pad, bandMid - (fmN.ascent + fmN.descent) / 2f, numberText)

        // Stamped medallion with the emoji.
        val medal = dp(MEDAL)
        val cx = pad + medal / 2f
        val cy = band + dp(18f) + medal / 2f
        paint.style = Paint.Style.FILL
        paint.color = Palette.withAlpha(accent, 0.17f)
        canvas.drawCircle(cx, cy, medal / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Palette.withAlpha(accent, 0.8f)
        canvas.drawCircle(cx, cy, medal / 2f, paint)
        dashed.strokeWidth = dp(1.2f)
        dashed.color = Palette.withAlpha(accent, 0.4f)
        dashed.pathEffect = ringDash
        canvas.drawCircle(cx, cy, medal / 2f + dp(6f), dashed)
        paint.style = Paint.Style.FILL
        if (glyphKind == Glyph.MOTH) {
            Craft.drawMoth(canvas, cx, cy + medal * 0.03f, medal * 0.62f, accent, paint)
        } else if (glyphKind == Glyph.LETTER) {
            letterPaint.color = accent
            val fm = letterPaint.fontMetrics
            canvas.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, letterPaint)
        } else {
            emoji.textSize = context.sp(48f)
            val maxW = medal * 0.78f
            val measured = emoji.measureText(glyph)
            if (measured > maxW && measured > 0f) emoji.textSize *= maxW / measured
            val fm = emoji.fontMetrics
            canvas.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, emoji)
        }

        // Title, vertically centered on the medallion.
        val textLeft = pad + medal + dp(22f)
        titleLayout?.let { tl ->
            canvas.save()
            canvas.translate(textLeft, cy - tl.height / 2f)
            tl.draw(canvas)
            canvas.restore()
        }

        // The line.
        val lineTop = cy + medal / 2f + dp(16f)
        lineLayout?.let { ll ->
            canvas.save()
            canvas.translate(pad, lineTop)
            ll.draw(canvas)
            canvas.restore()
        }

        // Footer: dotted rule, moth + source, icon tag.
        val footBase = h - dp(22f)
        dashed.strokeWidth = dp(1.2f)
        dashed.color = Palette.withAlpha(Palette.CREAM, 0.14f)
        dashed.pathEffect = ruleDash
        val ruleY = h - dp(50f)
        canvas.drawLine(pad, ruleY, w - pad, ruleY, dashed)
        val fmF = footPaint.fontMetrics
        val footMid = footBase + (fmF.ascent + fmF.descent) / 2f
        Craft.drawMoth(canvas, pad + dp(10f), footMid, dp(22f), Palette.withAlpha(Palette.LAMP, 0.9f), paint)
        canvas.drawText(footText, pad + dp(28f), footBase, footPaint)
        if (tagText.isNotEmpty()) {
            tagPaint.color = Palette.withAlpha(accent, 0.95f)
            canvas.drawText(tagText, w - pad, footBase, tagPaint)
        }
    }

    private fun drawSkeleton(canvas: Canvas, w: Float, h: Float, band: Float, pad: Float) {
        // A shimmer band sweeping left to right across the placeholder bars.
        val travel = w * 1.9f
        shimmerMatrix.setTranslate(-w * 0.45f + shimmer * travel - w * 0.2f, 0f)
        shimmerPaint.shader?.setLocalMatrix(shimmerMatrix)
        val medal = dp(MEDAL)
        val cx = pad + medal / 2f
        val cy = band + dp(18f) + medal / 2f
        canvas.drawCircle(cx, cy, medal / 2f, shimmerPaint)
        val textLeft = pad + medal + dp(22f)
        val barR = dp(8f)
        fun bar(x: Float, y: Float, width: Float, height: Float) {
            rect.set(x, y, x + width, y + height)
            canvas.drawRoundRect(rect, barR, barR, shimmerPaint)
        }
        bar(textLeft, cy - dp(26f), (w - textLeft - pad) * 0.82f, dp(22f))
        bar(textLeft, cy + dp(6f), (w - textLeft - pad) * 0.5f, dp(22f))
        val lineTop = cy + medal / 2f + dp(22f)
        bar(pad, lineTop, w - 2 * pad, dp(14f))
        bar(pad, lineTop + dp(26f), (w - 2 * pad) * 0.9f, dp(14f))
        bar(pad, lineTop + dp(52f), (w - 2 * pad) * 0.62f, dp(14f))
        bar(pad, h - dp(36f), dp(150f), dp(12f))
        rect.set(pad, band / 2f - dp(7f), pad + dp(170f), band / 2f + dp(7f))
        paint.color = Palette.withAlpha(Palette.CREAM, 0.08f)
        canvas.drawRoundRect(rect, barR, barR, paint)
    }

    companion object {
        /** Natural size in dp. */
        const val WIDTH = 520f
        const val HEIGHT = 320f
        private const val BAND = 48f
        private const val PAD = 30f
        private const val MEDAL = 88f
        private const val OPAQUE = 0xFFFFFFFF.toInt()
    }
}
