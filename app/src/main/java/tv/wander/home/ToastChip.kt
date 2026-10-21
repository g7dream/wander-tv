package tv.wander.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import tv.wander.ui.Async
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/** A small status chip that drops in at the top center ("Press Back again to leave", "Phones can't join..."). */
class ToastChip(context: Context) : FrameLayout(context) {
    enum class Kind(val color: Int) { INFO(Palette.LAMP), GOOD(Palette.MINT), BAD(Palette.CORAL) }

    private val chip = LinearLayout(context)
    private val dot = View(context)
    private val label = Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.bodyBold).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private var hideTask: Runnable? = null

    val isShowing: Boolean get() = chip.visibility == View.VISIBLE && hideTask != null

    /** The text currently (or last) shown, for tests. */
    val text: CharSequence get() = label.text

    init {
        isClickable = false
        isFocusable = false
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dpi(18f), dpi(12f), dpi(22f), dpi(12f))
        chip.background = Ui.roundRect(context, Palette.NIGHT, 24f, Palette.HAIRLINE, 1f)
        chip.addView(dot, LinearLayout.LayoutParams(dpi(10f), dpi(10f)).apply { marginEnd = dpi(12f) })
        chip.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        chip.visibility = View.GONE
        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpi(Dimens.SAFE_V + 4f)
            marginStart = dpi(Dimens.SAFE_H + 200f)
            marginEnd = dpi(Dimens.SAFE_H + 200f)
        })
    }

    /**
     * Where the chip drops in: centered between [startDp] and [endDp] (margins from the left and right
     * edges), [topDp] from the top. Each screen picks a spot that covers nothing the family needs.
     */
    fun setAnchor(startDp: Float, endDp: Float, topDp: Float) {
        val lp = chip.layoutParams as? LayoutParams ?: return
        // FrameLayout centers a child at (width / 2 + startMargin - endMargin) and measures it within
        // width - startMargin - endMargin. These margins give a chip centered between startDp and endDp
        // that can never grow past either edge.
        val start = dpi((3f * startDp + endDp) / 4f)
        val end = dpi((startDp + 3f * endDp) / 4f)
        val top = dpi(topDp)
        if (lp.marginStart == start && lp.marginEnd == end && lp.topMargin == top) return
        lp.marginStart = start
        lp.marginEnd = end
        lp.topMargin = top
        chip.layoutParams = lp
    }

    fun show(text: String, kind: Kind = Kind.INFO, holdMs: Long = 3200) {
        if (text.isBlank()) return
        label.text = text
        dot.background = context.circle(kind.color)
        chip.background = Ui.roundRect(context, Palette.NIGHT, 24f, Palette.withAlpha(kind.color, 0.6f), 1.5f)
        Async.cancel(hideTask)
        if (chip.visibility != View.VISIBLE) {
            chip.visibility = View.VISIBLE
            chip.alpha = 0f
            chip.translationY = -dp(16f)
        }
        chip.animate().cancel()
        chip.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        hideTask = Async.mainDelayed(holdMs.coerceAtLeast(800)) { dismiss() }
    }

    fun dismiss() {
        Async.cancel(hideTask)
        hideTask = null
        if (chip.visibility != View.VISIBLE) return
        chip.animate().cancel()
        chip.animate().alpha(0f).translationY(-dp(12f)).setDuration(180)
            .withEndAction { if (hideTask == null) chip.visibility = View.GONE }
            .start()
    }

    override fun onDetachedFromWindow() {
        Async.cancel(hideTask)
        hideTask = null
        super.onDetachedFromWindow()
    }
}
