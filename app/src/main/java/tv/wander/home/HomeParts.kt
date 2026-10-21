package tv.wander.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.model.Player
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import java.util.Calendar

// Small building blocks for the Home screen. Everything is sized in dp for the 960x540dp TV canvas.

internal const val WRAP = -2f
internal const val MATCH = -1f

internal fun Context.size(v: Float): Int = when (v) {
    WRAP -> FrameLayout.LayoutParams.WRAP_CONTENT
    MATCH -> FrameLayout.LayoutParams.MATCH_PARENT
    else -> dpi(v)
}

/**
 * Places [v] at an absolute dp position inside a FrameLayout. [x] is measured from the right edge when
 * [fromRight], [y] from the bottom when [fromBottom].
 */
internal fun FrameLayout.place(
    v: View,
    x: Float,
    y: Float,
    w: Float = WRAP,
    h: Float = WRAP,
    fromRight: Boolean = false,
    fromBottom: Boolean = false,
) {
    val gravity = (if (fromRight) Gravity.END else Gravity.START) or (if (fromBottom) Gravity.BOTTOM else Gravity.TOP)
    val p = FrameLayout.LayoutParams(context.size(w), context.size(h), gravity)
    if (fromRight) p.marginEnd = context.dpi(x) else p.marginStart = context.dpi(x)
    if (fromBottom) p.bottomMargin = context.dpi(y) else p.topMargin = context.dpi(y)
    addView(v, p)
}

/** A rounded surface with a soft radial wash of [tint] from the top-left corner, like lamp light on a card. */
internal fun Context.washedCard(tint: Int, radiusDp: Float, washAlpha: Float = 0.22f, base: Int = Palette.NIGHT): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp)
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(150f)
        setGradientCenter(0.18f, 0.12f)
        colors = intArrayOf(Palette.withAlpha(tint, washAlpha), base)
        setStroke(dpi(1f), Palette.HAIRLINE)
    }

internal fun Context.circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** A Home hero tile: emoji in a tinted disc, a serif title and a short caption. Field-guide card proportions. */
internal class HeroTile(
    context: Context,
    val emoji: String,
    val title: String,
    val subtitle: String,
    val tint: Int,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = context.washedCard(tint, Dimens.RADIUS, 0.28f)
        val pad = dpi(13f)
        setPadding(pad, dpi(14f), pad, dpi(10f))
        val badge = Ui.text(context, 26f, Palette.CREAM, Fonts.body, emoji).apply {
            gravity = Gravity.CENTER
            background = context.circle(Palette.withAlpha(tint, 0.20f))
        }
        addView(badge, LayoutParams(dpi(48f), dpi(48f)))
        addView(
            Ui.text(context, TypeScale.H2, Palette.CREAM, Fonts.display, title).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) },
        )
        addView(
            Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, subtitle).apply {
                setLineSpacing(0f, 1.0f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(5f) },
        )
        contentDescription = "$title. $subtitle"
    }
}

/** A favorite site: a little postcard with an emoji and a two-line title. */
internal class FavoriteTile(context: Context, val favorite: Favorite) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val tint = Palette.player(favorite.colorIndex)
        background = context.washedCard(tint, 16f, 0.30f)
        val pad = dpi(10f)
        setPadding(pad, dpi(10f), pad, dpi(9f))
        addView(Ui.text(context, 26f, Palette.CREAM, Fonts.body, favorite.emoji), LayoutParams(LayoutParams.WRAP_CONTENT, 0, 1f))
        addView(
            Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold, favorite.title).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.0f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        contentDescription = "${favorite.title}. ${favorite.blurb}"
    }
}

/**
 * One of the four seats on the couch. Empty: a dashed outline in the color the next phone would get,
 * breathing softly. Taken: filled in the player's color with their name.
 */
internal class SeatPill(context: Context) : TextView(context) {
    private var pulse: ValueAnimator? = null
    var player: Player? = null
        private set

    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TypeScale.CAPTION)
        typeface = Fonts.bodyBold
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dpi(8f), 0, dpi(8f), 0)
    }

    fun bindEmpty(colorIndex: Int) {
        player = null
        val color = Palette.player(colorIndex)
        text = Palette.PLAYER_COLOR_NAMES[((colorIndex % 4) + 4) % 4]
        setTextColor(Palette.withAlpha(color, 0.75f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(13f)
            setColor(Palette.withAlpha(color, 0.06f))
            setStroke(dpi(1.5f), Palette.withAlpha(color, 0.7f), dp(5f), dp(4f))
        }
        contentDescription = "Empty seat"
        startPulse()
    }

    fun bindPlayer(p: Player) {
        player = p
        stopPulse()
        alpha = 1f
        text = p.name
        setTextColor(Palette.INK)
        background = Ui.roundRect(context, Palette.player(p.colorIndex), 13f)
        contentDescription = "${p.name} is on the couch"
    }

    private fun startPulse() {
        if (pulse != null) return
        if (!isAttachedToWindow) return
        pulse = Ui.pulse(this, 0.45f, 1f, 2200)
    }

    fun stopPulse() {
        pulse?.cancel()
        pulse = null
        alpha = 1f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (player == null && text.isNotEmpty()) startPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) stopPulse() else if (player == null && text.isNotEmpty()) startPulse()
    }
}

/** The time in Young Serif, in the TV's 12/24-hour setting, refreshed on each minute boundary. */
internal class ClockText(context: Context) : TextView(context) {
    private val tick = Runnable { refresh() }

    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TypeScale.H2)
        typeface = Fonts.display
        setTextColor(Palette.CREAM)
        includeFontPadding = false
        maxLines = 1
        refresh(schedule = false)
    }

    private fun refresh(schedule: Boolean = true) {
        val now = Calendar.getInstance()
        text = runCatching { android.text.format.DateFormat.getTimeFormat(context).format(now.time) }
            .getOrElse { String.format(java.util.Locale.US, "%d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)) }
        if (schedule && isAttachedToWindow) {
            removeCallbacks(tick)
            val ms = 60_000L - (now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND)) + 50L
            postDelayed(tick, ms)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
