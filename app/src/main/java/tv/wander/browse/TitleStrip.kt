package tv.wander.browse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/**
 * The slim strip at the top of the browser: site icon, page title, domain chip, an occasional gentle
 * notice ("That link wants another app"), and who is driving ("● Maya driving"). Its bottom edge fills
 * with lamp light while a page loads. BrowseLayer shows it on navigation and tucks it away after.
 */
internal class TitleStrip(context: Context) : LinearLayout(context) {
    private val iconTile = IconTile(context)
    private val title = Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.display).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1f)
    }
    private val domain = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        background = Ui.roundRect(context, Palette.withAlpha(Palette.INK, 0.55f), 14f, Palette.HAIRLINE, 1f)
        setPadding(dpi(12f), dpi(5f), dpi(12f), dpi(6f))
        maxWidth = dpi(DOMAIN_MAX_DP)
    }
    private val notice = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = Ui.roundRect(context, Palette.withAlpha(Palette.CORAL, 0.20f), 14f, Palette.withAlpha(Palette.CORAL, 0.75f), 1.5f)
        setPadding(dpi(12f), dpi(5f), dpi(14f), dpi(6f))
        maxWidth = dpi(NOTICE_MAX_DP)
        visibility = View.GONE
    }
    private val driverDot = DriverDot(context)
    private val driverName = Ui.text(context, TypeScale.BODY, Palette.LAMP, Fonts.bodyBold).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dpi(DRIVER_MAX_DP)
    }
    private val driverCaption = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "driving").apply { maxLines = 1 }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Palette.HAIRLINE }
    private val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val clip = Path()

    // Progress, eased toward the latest WebChromeClient value and faded out after 100. Time-based, so
    // it looks right however often frames are drawn.
    private var progressFrom = 0f
    private var progressTo = 0f
    private var progressChangedAt = 0L
    private var progressDoneAt = NEVER
    private var progressActive = false

    private var shown = 0f
    private var dim = 0f
    private var showAnim: ValueAnimator? = null
    private var dimAnim: ValueAnimator? = null

    /** True while the strip is on screen (or on its way in). */
    var revealed = false
        private set
    private var dimTarget = 0f
    private var shownPage: Pair<String, String>? = null
    private var shownDriver: Pair<String, Int>? = null
    private var hasDomain = false
    private var compact = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPadding(dpi(14f), 0, dpi(20f), 0)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        addView(iconTile, LayoutParams(dpi(32f), dpi(32f)).apply { rightMargin = dpi(14f) })
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(14f) })
        addView(domain, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(12f) })
        addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(12f) })
        addView(driverDot, LayoutParams(dpi(12f), dpi(12f)).apply { leftMargin = dpi(8f); rightMargin = dpi(9f) })
        addView(driverName, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(6f) })
        addView(driverCaption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        alpha = 0f
        visibility = View.INVISIBLE
        setPage("", "")
        setDriver("Remote", Palette.LAMP)
    }

    // Both setters skip unchanged values: setText re-lays out the whole tree, WebView included, and
    // the driver is re-applied on every D-pad repeat.
    fun setPage(pageTitle: String, url: String) {
        if (shownPage == (pageTitle to url)) return
        shownPage = pageTitle to url
        val web = url.startsWith("http://") || url.startsWith("https://")
        val host = if (web) PageTexts.hostLabel(url) else null
        title.text = pageTitle.ifBlank { host ?: "Wander" }
        domain.text = host ?: ""
        hasDomain = !host.isNullOrEmpty()
        applyDomainVisibility()
        val initialFrom = if (web) PageTexts.siteLabel(url) else pageTitle
        iconTile.setLetter(initialFrom.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: 'W')
    }

    fun setIcon(icon: Bitmap?) = iconTile.setBitmap(icon)

    fun setDriver(name: String, color: Int) {
        if (shownDriver == (name to color)) return
        shownDriver = name to color
        driverName.text = name
        driverName.setTextColor(color)
        driverDot.setColor(color)
    }

    /**
     * Compact mode for a narrower strip (the quest scoreboard holds the top-right corner): chips get
     * smaller caps, and a notice briefly takes the domain chip's place, so the "driving" chip always fits.
     */
    fun setCompact(on: Boolean) {
        if (compact == on) return
        compact = on
        domain.maxWidth = dpi(if (on) DOMAIN_MAX_COMPACT_DP else DOMAIN_MAX_DP)
        notice.maxWidth = dpi(if (on) NOTICE_MAX_COMPACT_DP else NOTICE_MAX_DP)
        driverName.maxWidth = dpi(if (on) DRIVER_MAX_COMPACT_DP else DRIVER_MAX_DP)
        applyDomainVisibility()
    }

    private fun applyDomainVisibility() {
        val show = hasDomain && !(compact && notice.visibility == View.VISIBLE)
        val v = if (show) View.VISIBLE else View.GONE
        if (domain.visibility != v) domain.visibility = v
    }

    /** Shows a short notice chip; null hides it. */
    fun setNotice(text: String?) {
        if (text.isNullOrBlank()) {
            notice.visibility = View.GONE
            applyDomainVisibility()
        } else {
            notice.text = text
            notice.visibility = View.VISIBLE
            notice.alpha = 0f
            notice.translationY = dp(6f)
            notice.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            applyDomainVisibility()
        }
    }

    fun setProgress(percent: Int) {
        val t = AnimationUtils.currentAnimationTimeMillis()
        // A sliver of light straight away, so a click always gets visible feedback.
        val p = (percent.coerceIn(0, 100) / 100f).coerceAtLeast(MIN_PROGRESS)
        // 100 with nothing loading (or a repeat of 100) is not news.
        if (p >= 1f && (!progressActive || progressTo >= 1f)) return
        val restarting = !progressActive || p < progressTo - 0.05f || progressTo >= 1f
        progressFrom = if (restarting) 0f else currentProgress(t)
        progressTo = p
        progressChangedAt = t
        progressActive = true
        progressDoneAt = if (p >= 1f) t + PROGRESS_EASE_MS else NEVER
        invalidate()
    }

    private fun currentProgress(t: Long): Float {
        val k = ((t - progressChangedAt).toFloat() / PROGRESS_EASE_MS.toFloat()).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k)
        return progressFrom + (progressTo - progressFrom) * e
    }

    fun reveal() {
        revealed = true
        animateShown(1f, 220)
    }

    fun conceal() {
        revealed = false
        animateShown(0f, 300)
    }

    /** Fades most of the way out while the cursor is over the strip, so the page under it shows. */
    fun setDimmed(dimmed: Boolean) {
        val target = if (dimmed) 1f else 0f
        if (target == dimTarget) return
        dimTarget = target
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(dim, target).apply {
            duration = 180
            addUpdateListener { dim = it.animatedValue as Float; applyAlpha() }
            start()
        }
    }

    private fun animateShown(target: Float, duration: Long) {
        if (shown == target && showAnim?.isRunning != true) return
        showAnim?.cancel()
        if (target > 0f) visibility = View.VISIBLE
        showAnim = ValueAnimator.ofFloat(shown, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                applyAlpha()
                if (shown == 0f && target == 0f) visibility = View.INVISIBLE
            }
            start()
        }
    }

    private fun applyAlpha() {
        alpha = shown * (1f - 0.75f * dim)
        translationY = -(1f - shown) * dp(14f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = dp(Dimens.RADIUS)
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(shape, r, r, Path.Direction.CW)
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Palette.DUSK, Palette.NIGHT, Shader.TileMode.CLAMP)
        // The lamp sits off to the left: a warm wash fading across the strip.
        warmPaint.shader = RadialGradient(dp(30f), h / 2f, w * 0.45f, Palette.withAlpha(Palette.LAMP, 0.13f), 0, Shader.TileMode.CLAMP)
        progressPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Palette.withAlpha(Palette.LAMP, 0.55f), Palette.LAMP, Shader.TileMode.CLAMP)
        headPaint.shader = RadialGradient(0f, 0f, dp(22f), Palette.withAlpha(Palette.LAMP, 0.55f), 0, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(Dimens.RADIUS)
        canvas.drawRoundRect(shape, r, r, bgPaint)
        canvas.drawRoundRect(shape, r, r, warmPaint)
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(shape.left + inset, shape.top + inset, shape.right - inset, shape.bottom - inset, r, r, borderPaint)
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawProgress(canvas)
    }

    private fun drawProgress(canvas: Canvas) {
        if (!progressActive) return
        val t = AnimationUtils.currentAnimationTimeMillis()
        val shownNow = currentProgress(t)
        var animating = t - progressChangedAt < PROGRESS_EASE_MS
        var progressAlpha = 1f
        if (progressDoneAt != NEVER && t >= progressDoneAt) {
            progressAlpha = 1f - ((t - progressDoneAt).toFloat() / PROGRESS_FADE_MS).coerceIn(0f, 1f)
            if (progressAlpha <= 0f) progressActive = false
        }
        if (progressDoneAt != NEVER && progressActive) animating = true
        if (progressActive && shownNow > 0f) {
            val h = height.toFloat()
            val w = width.toFloat()
            val bar = dp(3f)
            val x = w * shownNow
            canvas.save()
            canvas.clipPath(clip)
            progressPaint.alpha = (255 * progressAlpha).toInt()
            canvas.drawRect(0f, h - bar, x, h, progressPaint)
            // A glowing head where the light is spreading.
            headPaint.alpha = (255 * progressAlpha).toInt()
            canvas.translate(x, h - bar / 2f)
            canvas.drawCircle(0f, 0f, dp(22f), headPaint)
            canvas.restore()
        }
        if (animating) postInvalidateOnAnimation()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val MIN_PROGRESS = 0.04f
        const val PROGRESS_EASE_MS = 260L
        const val PROGRESS_FADE_MS = 360f
        const val DOMAIN_MAX_DP = 260f
        const val DOMAIN_MAX_COMPACT_DP = 180f
        const val NOTICE_MAX_DP = 360f
        const val NOTICE_MAX_COMPACT_DP = 250f
        const val DRIVER_MAX_DP = 220f
        const val DRIVER_MAX_COMPACT_DP = 150f
    }

    /** Rounded tile with the site's favicon on cream, or the domain's initial on lamp amber. */
    private class IconTile(context: Context) : View(context) {
        private var bitmap: Bitmap? = null
        private var letter = 'W'
        private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.display
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TypeScale.CAPTION + 1f, context.resources.displayMetrics)
            color = Palette.INK
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val letterMetrics = Paint.FontMetrics()
        private val src = Rect()
        private val dst = RectF()

        fun setBitmap(b: Bitmap?) {
            bitmap = b?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            invalidate()
        }

        fun setLetter(c: Char) {
            if (c == letter) return
            letter = c
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = dp(9f)
            rect.set(0f, 0f, w, h)
            val b = bitmap
            if (b != null && !b.isRecycled) {
                tile.color = Palette.CREAM
                canvas.drawRoundRect(rect, rad, rad, tile)
                val pad = w * 0.17f
                src.set(0, 0, b.width, b.height)
                dst.set(pad, pad, w - pad, h - pad)
                canvas.drawBitmap(b, src, dst, bmpPaint)
            } else {
                tile.color = Palette.LAMP
                canvas.drawRoundRect(rect, rad, rad, tile)
                val fm = letterMetrics.also { letterPaint.getFontMetrics(it) }
                canvas.drawText(letter.toString(), w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, letterPaint)
            }
        }
    }

    /** The "driving" dot: a solid dot in the driver's color with a soft halo. */
    private class DriverDot(context: Context) : View(context) {
        private var color = Palette.LAMP
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setColor(c: Int) {
            color = c
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            paint.color = Palette.withAlpha(color, 0.28f)
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = color
            canvas.drawCircle(cx, cy, r * 0.62f, paint)
        }
    }
}
