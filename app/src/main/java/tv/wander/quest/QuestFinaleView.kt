package tv.wander.quest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.cards.Craft
import tv.wander.cards.SteadyLampBackdrop
import tv.wander.cards.sp
import tv.wander.model.Player
import tv.wander.model.QuestBoard
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The finale: confetti in the players' colors, a podium, what we found, and what's next. */
internal class QuestFinaleView(
    context: Context,
    board: QuestBoard,
    onChoice: (QuestOverlay.FinaleChoice) -> Unit,
) : FrameLayout(context) {
    val anotherButton: TextView
    val browseButton: TextView
    private val confetti: ConfettiView

    init {
        background = SteadyLampBackdrop(seed = 23)
        val quest = board.quest

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.text(context, TypeScale.CAPTION, Palette.LAMP, Fonts.bodyBold, if (board.complete) "QUEST COMPLETE" else "QUEST WRAPPED UP").apply {
                letterSpacing = 0.22f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val emoji = Craft.firstGlyph(quest.emoji)
            val title = if (board.complete) "${quest.title}, solved!" else "${quest.title}: good hunting!"
            addView(FitTextView(context, maxSp = TypeScale.DISPLAY, minSp = 30f).apply {
                typeface = Fonts.display
                setTextColor(Palette.CREAM)
                gravity = Gravity.CENTER
                text = if (emoji.isEmpty()) title else "$emoji  $title"
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        }
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dpi(Dimens.SAFE_V + 12f)
            leftMargin = dpi(Dimens.SAFE_H)
            rightMargin = dpi(Dimens.SAFE_H)
        })

        val middle = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        middle.addView(PodiumView(context, board.players), LinearLayout.LayoutParams(0, dpi(MIDDLE_H), 1.05f))
        middle.addView(View(context).apply { setBackgroundColor(Palette.withAlpha(Palette.CREAM, 0.08f)) },
            LinearLayout.LayoutParams(dpi(1f), dpi(MIDDLE_H - 30f)).apply { leftMargin = dpi(28f); rightMargin = dpi(28f); topMargin = dpi(14f) })
        middle.addView(foundColumn(board), LinearLayout.LayoutParams(0, dpi(MIDDLE_H), 1f))
        addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, dpi(MIDDLE_H), Gravity.TOP).apply {
            topMargin = dpi(MIDDLE_TOP)
            leftMargin = dpi(Dimens.SAFE_H + 8f)
            rightMargin = dpi(Dimens.SAFE_H + 8f)
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        anotherButton = pillButton(context, "Another quest", primary = true)
        browseButton = pillButton(context, "Back to browsing", primary = false)
        anotherButton.setOnClickListener { onChoice(QuestOverlay.FinaleChoice.ANOTHER_QUEST) }
        browseButton.setOnClickListener { onChoice(QuestOverlay.FinaleChoice.BACK_TO_BROWSING) }
        anotherButton.id = View.generateViewId()
        browseButton.id = View.generateViewId()
        anotherButton.nextFocusRightId = browseButton.id
        browseButton.nextFocusLeftId = anotherButton.id
        buttons.addView(anotherButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(52f)))
        buttons.addView(browseButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(52f)).apply { leftMargin = dpi(22f) })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dpi(Dimens.SAFE_V + 14f)
        })

        val colors = board.players.map { Palette.player(it.colorIndex) }.distinct().ifEmpty { Palette.PLAYERS.toList() }
        confetti = ConfettiView(context, colors)
        addView(confetti, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun celebrate() = confetti.burst()

    private fun foundColumn(board: QuestBoard): View = FoundList(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.text(context, TypeScale.H2, Palette.CREAM, Fonts.display, "What we found"))
        val found = board.found
        if (found.isEmpty()) {
            addView(Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body, "No finds this time, but a fine evening of wandering."),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
            return@apply
        }
        val shown = found.take(4)
        val quoteLines = if (shown.size <= 3) 2 else 1
        for (f in shown) {
            val finder = board.players.firstOrNull { it.id == f.playerId }
            val color = finder?.let { Palette.player(it.colorIndex) } ?: Palette.CREAM
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(AvatarView(context, finder?.let { displayName(it) } ?: "?", color), LinearLayout.LayoutParams(dpi(26f), dpi(26f)).apply { rightMargin = dpi(12f) })
                addView(Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.bodyBold).apply {
                    text = SpannableStringBuilder(f.item).apply {
                        if (finder != null) {
                            append("  ·  ")
                            val s = length
                            append(displayName(finder))
                            setSpan(ForegroundColorSpan(color), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
            val ev = f.evidence?.trim().orEmpty()
            if (ev.isNotEmpty()) {
                addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "“${ev.trim('"', '“', '”')}”").apply {
                    maxLines = quoteLines
                    ellipsize = TextUtils.TruncateAt.END
                    quotes += this
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dpi(38f)
                    topMargin = dpi(4f)
                })
            }
        }
        if (found.size > shown.size) {
            addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, "+${found.size - shown.size} more"),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f); leftMargin = dpi(38f) })
        }
    }

    companion object {
        private const val MIDDLE_TOP = 142f
        private const val MIDDLE_H = 278f
    }
}

/**
 * "What we found": quotes get two lines while the whole list fits its column, and drop to one line when three
 * long quotes would otherwise run past the bottom and be cut in half. It only ever shrinks, so a layout pass
 * can never flip it back and forth.
 */
private class FoundList(context: Context) : LinearLayout(context) {
    val quotes = ArrayList<TextView>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = MeasureSpec.getSize(heightMeasureSpec)
        val tall = quotes.filter { it.maxLines > 1 }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && limit > 0 && tall.isNotEmpty()) {
            // Measured against the real height, the last children only get what is left and quietly come out
            // short; an unbounded pass shows how tall the list really wants to be.
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (measuredHeight > limit) for (q in tall) q.maxLines = 1
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Top three on blocks (2nd, 1st, 3rd), the rest listed underneath. */
internal class PodiumView(context: Context, players: List<Player>) : View(context) {
    private val ranked = QuestEngine.ranked(players)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bodyBold
        color = Palette.CREAM
        textAlign = Paint.Align.CENTER
        textSize = context.sp(18f)
    }
    private val scorePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display
        color = Palette.INK
        textAlign = Paint.Align.CENTER
        textSize = context.sp(28f)
    }
    private val rankPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bodyBold
        textAlign = Paint.Align.CENTER
        textSize = context.sp(16f)
        letterSpacing = 0.1f
    }
    private val initialPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display
        color = Palette.INK
        textAlign = Paint.Align.CENTER
    }
    private val restPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.body
        color = Palette.MUTED
        textAlign = Paint.Align.CENTER
        textSize = context.sp(16f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h - dp(34f)
        if (ranked.isEmpty()) {
            restPaint.textSize = context.sp(20f)
            canvas.drawText("Everyone on the couch wins tonight.", w / 2f, h / 2f, restPaint)
            return
        }
        val blockW = min(dp(132f), (w - dp(32f)) / 3f)
        val gap = dp(14f)
        // Slots left to right: 2nd, 1st, 3rd.
        val order = listOf(1, 0, 2)
        val heights = floatArrayOf(dp(132f), dp(98f), dp(74f))
        val centerX = w / 2f
        val slotX = floatArrayOf(centerX - blockW - gap, centerX, centerX + blockW + gap)
        paint.color = Palette.withAlpha(Palette.CREAM, 0.12f)
        canvas.drawRect(slotX[0] - blockW / 2f - dp(10f), base, slotX[2] + blockW / 2f + dp(10f), base + dp(2f), paint)
        for ((slot, place) in order.withIndex()) {
            val p = ranked.getOrNull(place) ?: continue
            val color = Palette.player(p.colorIndex)
            val cx = slotX[slot]
            // Ties share a rank, so they stand on blocks of the same height.
            val rank = 1 + ranked.count { it.score > p.score }
            val top = base - heights[(rank - 1).coerceIn(0, heights.size - 1)]
            val r = dp(14f)
            rect.set(cx - blockW / 2f, top, cx + blockW / 2f, base)
            path.reset()
            path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            paint.color = 0xFFFFFFFF.toInt()
            paint.shader = LinearGradient(0f, top, 0f, base, color, Palette.withAlpha(color, 0.55f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.color = Palette.withAlpha(Palette.CREAM, 0.35f)
            canvas.drawRect(cx - blockW / 2f + r, top + dp(3f), cx + blockW / 2f - r, top + dp(5f), paint)
            // Score on the block face, place under it.
            val fmS = scorePaint.fontMetrics
            canvas.drawText(p.score.toString(), cx, top + dp(14f) - fmS.ascent, scorePaint)
            rankPaint.color = Palette.withAlpha(Palette.INK, 0.72f)
            canvas.drawText(ordinal(rank).uppercase(), cx, top + dp(14f) - fmS.ascent + dp(24f), rankPaint)
            // Name and avatar above the block.
            val nameY = top - dp(12f)
            canvas.drawText(TextUtils.ellipsize(displayName(p), namePaint, blockW + gap, TextUtils.TruncateAt.END).toString(), cx, nameY, namePaint)
            val avatarR = if (place == 0) dp(30f) else dp(25f)
            val ay = nameY - dp(24f) - avatarR
            paint.color = Palette.withAlpha(color, 0.25f)
            canvas.drawCircle(cx, ay, avatarR + dp(6f), paint)
            paint.color = color
            canvas.drawCircle(cx, ay, avatarR, paint)
            paint.color = Palette.withAlpha(Palette.CREAM, 0.3f)
            canvas.drawCircle(cx - avatarR * 0.34f, ay - avatarR * 0.4f, avatarR * 0.24f, paint)
            initialPaint.textSize = avatarR
            val fmI = initialPaint.fontMetrics
            canvas.drawText(displayName(p).firstOrNull()?.uppercaseChar()?.toString() ?: "?", cx, ay - (fmI.ascent + fmI.descent) / 2f, initialPaint)
            if (place == 0 && p.score > 0) {
                Craft.drawMoth(canvas, cx + avatarR * 0.95f, ay - avatarR * 0.95f, dp(30f), Palette.LAMP, paint)
            }
        }
        if (ranked.size > 3) {
            restPaint.textSize = context.sp(16f)
            val rest = ranked.drop(3).joinToString("     ") { "${ordinal(1 + ranked.count { o -> o.score > it.score })}  ${displayName(it)} · ${it.score}" }
            canvas.drawText(TextUtils.ellipsize(rest, restPaint, w, TextUtils.TruncateAt.END).toString(), w / 2f, base + dp(26f), restPaint)
        }
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}

/** Canvas confetti: two cannons from the bottom corners plus a gentle top sprinkle, about 2.6 s. */
internal class ConfettiView(context: Context, private val colors: List<Int>) : View(context) {
    private class Bit(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val spin: Float, val w: Float, val h: Float,
        val color: Int, val round: Boolean, val phase: Float,
    )

    private val bits = ArrayList<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null
    private var lastMs = 0f
    private var fade = 1f
    private var pending = false

    init {
        isFocusable = false
        isClickable = false
    }

    fun burst() {
        if (width == 0 || height == 0) {
            pending = true
            return
        }
        pending = false
        seed()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, DURATION_MS).apply {
            duration = DURATION_MS.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { step(it.animatedValue as Float) }
            start()
        }
    }

    /** Test hook: stops the clock and shows the confetti exactly [ms] into the burst. */
    internal fun freezeAt(ms: Float) {
        anim?.cancel()
        anim = null
        pending = false
        if (width == 0 || height == 0) return
        seed()
        step(ms)
    }

    private fun seed() {
        bits.clear()
        lastMs = 0f
        fade = 1f
        val rnd = Random(42)
        val d = resources.displayMetrics.density
        val palette = colors + Palette.CREAM
        fun add(x: Float, y: Float, angleDeg: Float, speed: Float) {
            val a = angleDeg / 180f * PI.toFloat()
            bits += Bit(
                x, y, cos(a) * speed * d, sin(a) * speed * d,
                rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 720f,
                (6f + rnd.nextFloat() * 5f) * d, (10f + rnd.nextFloat() * 9f) * d,
                palette[rnd.nextInt(palette.size)], rnd.nextFloat() < 0.25f, rnd.nextFloat() * 6.28f,
            )
        }
        val w = width.toFloat()
        val h = height.toFloat()
        repeat(66) { add(w * 0.04f, h * 1.02f, -52f - rnd.nextFloat() * 30f, 950f + rnd.nextFloat() * 700f) }
        repeat(66) { add(w * 0.96f, h * 1.02f, -128f + rnd.nextFloat() * 30f, 950f + rnd.nextFloat() * 700f) }
        repeat(36) { add(rnd.nextFloat() * w, -rnd.nextFloat() * h * 0.2f, 80f + rnd.nextFloat() * 20f, 60f + rnd.nextFloat() * 120f) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pending && w > 0 && h > 0) post { burst() }
    }

    /** Fixed 16 ms physics steps, so a stuttering frame rate never changes where the confetti lands. */
    private fun step(nowMs: Float) {
        val d = resources.displayMetrics.density
        val g = 1350f * d
        val flutterCap = 260f * d
        while (lastMs + STEP_MS <= nowMs) {
            lastMs += STEP_MS
            val dt = STEP_MS / 1000f
            val drag = 0.986f.pow(dt * 60f)
            for (b in bits) {
                b.vy += g * dt
                b.vx *= drag
                b.vy *= drag
                if (b.vy > flutterCap) b.vy = flutterCap + (b.vy - flutterCap) * 0.6f // paper flutters, it doesn't drop
                b.x += (b.vx + sin(lastMs / 180f + b.phase) * 40f * d) * dt
                b.y += b.vy * dt
                b.rot += b.spin * dt
            }
        }
        fade = if (nowMs > DURATION_MS - 600f) ((DURATION_MS - nowMs) / 600f).coerceIn(0f, 1f) else 1f
        if (nowMs >= DURATION_MS) bits.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        bits.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (bits.isEmpty()) return
        for (b in bits) {
            if (b.y < -b.h * 4 || b.y > height + b.h * 4) continue
            paint.color = Palette.withAlpha(b.color, fade)
            if (b.round) {
                canvas.drawCircle(b.x, b.y, b.w * 0.55f, paint)
            } else {
                canvas.save()
                canvas.translate(b.x, b.y)
                canvas.rotate(b.rot)
                // Fake the 3D tumble by squashing one axis.
                canvas.scale(1f, 0.35f + 0.65f * kotlin.math.abs(cos(b.rot / 57f)))
                rect.set(-b.w / 2f, -b.h / 2f, b.w / 2f, b.h / 2f)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }
        }
    }

    companion object {
        private const val DURATION_MS = 2600f
        private const val STEP_MS = 16f
    }
}
