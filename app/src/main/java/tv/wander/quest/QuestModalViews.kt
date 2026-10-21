package tv.wander.quest

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.cards.Craft
import tv.wander.cards.MothMarkView
import tv.wander.cards.ScrimGlowDrawable
import tv.wander.cards.SteadyLampBackdrop
import tv.wander.model.Quest
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/** "Pick a family quest": the curated quests as cards plus Lumo's AI-made pick. */
internal class QuestPickerView(
    context: Context,
    quests: List<Quest>,
    private val onPick: (Quest) -> Unit,
    private val onLumo: () -> Unit,
) : FrameLayout(context) {
    val tiles = ArrayList<View>()
    private var lumoTile: LumoPickTile? = null

    init {
        background = SteadyLampBackdrop(seed = 11)
        val shown = quests.distinctBy { it.id }.take(MAX_QUESTS)
        val columns = if (shown.size + 1 > 6) 4 else 3

        val title = Ui.text(context, TypeScale.DISPLAY, Palette.CREAM, Fonts.display, "Pick a family quest")
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V + 6f)
        })
        val sub = Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body, "Everyone hunts on their phones. Prove it on the TV to score.")
        addView(sub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V + 64f)
        })
        val rules = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Craft.chip(context, "+${QuestEngine.POINTS_PER_ITEM} a find", Palette.LAMP, Palette.withAlpha(Palette.LAMP, 0.12f), Palette.withAlpha(Palette.LAMP, 0.4f)))
            addView(Craft.chip(context, "+${QuestEngine.COMPLETION_BONUS} to the finisher", Palette.MINT, Palette.withAlpha(Palette.MINT, 0.12f), Palette.withAlpha(Palette.MINT, 0.4f)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(10f) })
        }
        addView(rules, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V + 16f)
        })

        clipChildren = false
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val items = ArrayList<View>()
        shown.forEachIndexed { i, q -> items += questTile(q, questAccent(q, i)) }
        items += LumoPickTile(context).also { lumo ->
            lumoTile = lumo
            Ui.makeFocusable(lumo, Dimens.RADIUS, 1.05f) { focused -> lumo.setFocusedLook(focused) }
            lumo.setOnClickListener {
                if (!lumo.thinking) {
                    Sfx.select()
                    lumo.setThinking(true)
                    onLumo()
                }
            }
        }
        tiles.addAll(items)
        val gap = dpi(20f)
        items.chunked(columns).forEachIndexed { r, rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            rowItems.forEachIndexed { c, v ->
                row.addView(v, LinearLayout.LayoutParams(0, dpi(TILE_H), 1f).apply { if (c > 0) leftMargin = gap })
            }
            // Keep the last row's tiles the same width as the others.
            repeat(columns - rowItems.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dpi(TILE_H), 1f).apply { leftMargin = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            rightMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(GRID_TOP)
        })

        val hints = Craft.hintRow(
            context,
            listOf(
                Craft.Hint(listOf(Craft.Key.Left, Craft.Key.Right, Craft.Key.Up, Craft.Key.Down), "choose"),
                Craft.Hint(listOf(Craft.Key.Label("OK")), "start"),
                Craft.Hint(listOf(Craft.Key.Label("Back")), "not now"),
            ),
        )
        addView(hints, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(Dimens.SAFE_V + 6f)
        })
    }

    /** Lumo's pick finished (or failed): back to the inviting state. */
    fun resetLumo() {
        lumoTile?.setThinking(false)
    }

    private fun questTile(q: Quest, accent: Int): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(12f))
            background = tileBackground(focused = false, accent = accent)
        }
        val top = tileHeader(
            MedallionView(context, q.emoji, accent),
            q.title,
            Craft.chip(context, "find ${q.goal.coerceAtLeast(1)}", accent, Palette.withAlpha(accent, 0.13f), 0).apply {
                setPadding(dpi(10f), dpi(2f), dpi(10f), dpi(2f))
            },
        )
        tile.addView(top)
        tile.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, q.prompt).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        Ui.makeFocusable(tile, Dimens.RADIUS, 1.05f) { focused -> tile.background = tileBackground(focused, accent) }
        tile.setOnClickListener {
            Sfx.select()
            onPick(q)
        }
        return tile
    }

    private fun tileHeader(medal: View, title: String, tag: View): View = tileHeader(context, medal, title, tag)

    private fun tileBackground(focused: Boolean, accent: Int) =
        if (focused) Ui.roundRect(context, 0xFF31264A.toInt(), Dimens.RADIUS, Palette.withAlpha(accent, 0.55f), 1.5f)
        else Ui.roundRect(context, Palette.withAlpha(Palette.DUSK, 0.92f), Dimens.RADIUS, Palette.HAIRLINE, 1.5f)

    companion object {
        private const val MAX_QUESTS = 7
        private const val TILE_H = 158f
        private const val GRID_TOP = 134f
    }
}

/** The special card: a dashed lamp border, Lumo's moth, and a fresh quest from tonight's trail. */
internal class LumoPickTile(context: Context) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val moth = MothMarkView(context, Palette.LAMP)
    private val line = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.body, IDLE_LINE)
    private val privacy = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Uses tonight's page titles only")
    private var focusedLook = false
    private var gradient: Shader? = null
    private val dash = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    private var timeout: Runnable? = null
    var thinking = false
        private set

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(12f))
        val medal = FrameLayout(context).apply {
            background = Ui.roundRect(context, Palette.withAlpha(Palette.LAMP, 0.16f), 999f, Palette.withAlpha(Palette.LAMP, 0.8f), 1.5f)
            addView(moth, FrameLayout.LayoutParams(dpi(32f), dpi(28f), Gravity.CENTER))
        }
        val tag = Craft.chip(context, "new tonight", Palette.LAMP, Palette.withAlpha(Palette.LAMP, 0.13f), 0).apply {
            setPadding(dpi(10f), dpi(2f), dpi(10f), dpi(2f))
        }
        addView(tileHeader(context, medal, "Lumo's pick ✨", tag))
        line.maxLines = 2
        line.ellipsize = TextUtils.TruncateAt.END
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        privacy.maxLines = 1
        privacy.ellipsize = TextUtils.TruncateAt.END
        addView(privacy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
    }

    fun setFocusedLook(focused: Boolean) {
        focusedLook = focused
        invalidate()
    }

    fun setThinking(on: Boolean) {
        thinking = on
        moth.flutter(on)
        line.text = if (on) "Lumo is dreaming one up..." else IDLE_LINE
        line.setTextColor(if (on) Palette.LAMP else Palette.CREAM)
        timeout?.let { removeCallbacks(it) }
        timeout = null
        if (on) {
            // Never leave the tile stuck if the answer never comes.
            timeout = Runnable { setThinking(false) }.also { postDelayed(it, 15_000) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (thinking) setThinking(true)
    }

    override fun onDetachedFromWindow() {
        timeout?.let { removeCallbacks(it) }
        timeout = null
        moth.flutter(false)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF3A2950.toInt(), 0xFF241B37.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(Dimens.RADIUS)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        paint.shader = gradient
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f)
        paint.color = Palette.withAlpha(Palette.LAMP, if (focusedLook) 0.95f else 0.6f)
        paint.pathEffect = dash
        rect.inset(dp(1f), dp(1f))
        canvas.drawRoundRect(rect, r, r, paint)
        paint.pathEffect = null
        super.onDraw(canvas)
    }

    companion object {
        private const val IDLE_LINE = "A fresh quest from tonight's trail"
    }
}

/** The quest intro: one big quest card with the goal, the rules and a "Let's go". */
internal class QuestIntroView(context: Context, quest: Quest, onGo: () -> Unit) : FrameLayout(context) {
    val goButton: TextView

    init {
        background = ScrimGlowDrawable(glowY = 0.5f, glowAlpha = 0.13f)
        val accent = questAccent(quest)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundRect(context, Palette.DUSK, Dimens.RADIUS_BIG, Palette.withAlpha(0xFF6B5C93.toInt(), 0.6f), 1.5f)
            clipChildren = false
        }
        // Colored band, like the field-guide cards.
        val band = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = BandDrawable(accent, dp(Dimens.RADIUS_BIG))
            setPadding(dpi(30f), 0, dpi(30f), 0)
            addView(Ui.text(context, TypeScale.CAPTION, Palette.withAlpha(Palette.INK, 0.86f), Fonts.bodyBold, if (quest.generated) "LUMO'S PICK · NEW TONIGHT" else "WANDER FAMILY QUEST").apply {
                letterSpacing = 0.16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(context, TypeScale.CAPTION, Palette.INK, Fonts.bodyBold, "+${QuestEngine.POINTS_PER_ITEM} a find  ·  +${QuestEngine.COMPLETION_BONUS} to finish"))
        }
        card.addView(band, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dpi(34f), dpi(26f), dpi(34f), dpi(26f))
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(MedallionView(context, quest.emoji, accent), LinearLayout.LayoutParams(dpi(132f), dpi(132f)))
            val goal = quest.goal.coerceAtLeast(1)
            addView(PipsView(context, pipDp = 20f, gapDp = 9f).apply { set(goal, emptyList()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(18f) })
            addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, "find ${goalWord(goal)}").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        }
        body.addView(left, LinearLayout.LayoutParams(dpi(170f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(28f) })

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        right.addView(FitTextView(context, maxSp = 40f, minSp = 28f).apply {
            typeface = Fonts.display
            setTextColor(Palette.CREAM)
            text = quest.title
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        right.addView(Ui.text(context, 22f, Palette.CREAM, Fonts.body, quest.prompt).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        val how = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundRect(context, Palette.withAlpha(Palette.INK, 0.55f), 16f, Palette.HAIRLINE, 1f)
            setPadding(dpi(14f), dpi(12f), dpi(16f), dpi(12f))
            addView(PhoneGlyphView(context, accent), LinearLayout.LayoutParams(dpi(30f), dpi(44f)).apply { rightMargin = dpi(14f) })
            addView(Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.body,
                "Search on your phones. When the TV shows a page that proves it, tap ‘This page has it!’").apply {
                maxLines = 3
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        right.addView(how, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(18f) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            clipChildren = false
        }
        goButton = pillButton(context, "Let's wander", primary = true)
        goButton.setOnClickListener { onGo() }
        actions.addView(goButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(52f)).apply { rightMargin = dpi(6f) })
        right.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(20f) })
        body.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(body)

        addView(card, LayoutParams(dpi(780f), LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { bottomMargin = dpi(14f) })
        val hints = Craft.hintRow(
            context,
            listOf(
                Craft.Hint(listOf(Craft.Key.Label("OK")), "let's go"),
                Craft.Hint(listOf(Craft.Key.Label("Back")), "close"),
            ),
        )
        addView(hints, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(Dimens.SAFE_V + 6f)
        })
    }
}

/** A rounded pill button with Wander's focus ring. */
internal fun pillButton(context: Context, label: String, primary: Boolean): TextView =
    Ui.text(context, TypeScale.BODY, if (primary) Palette.INK else Palette.CREAM, Fonts.bodyBold, label).apply {
        gravity = Gravity.CENTER
        setPadding(context.dpi(30f), 0, context.dpi(30f), 0)
        background = if (primary) Ui.roundRect(context, Palette.LAMP, 26f)
        else Ui.roundRect(context, Palette.DUSK, 26f, Palette.HAIRLINE, 1.5f)
        Ui.makeFocusable(this, 26f, 1.06f, if (primary) Palette.CREAM else Palette.LAMP)
    }

/** The colored top band of a hero card, with only its top corners rounded. */
internal class BandDrawable(private val color: Int, private val radius: Float) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
        paint.color = color
        canvas.drawPath(path, paint)
        paint.color = Palette.withAlpha(0xFF000000.toInt(), 0.18f)
        canvas.drawRect(rect.left, rect.bottom - radius * 0.11f, rect.right, rect.bottom, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Walks [root] for every focusable descendant (for D-pad traps inside a modal). */
internal fun ViewGroup.focusables(): List<View> {
    val out = ArrayList<View>()
    fun walk(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v.isFocusable) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(this)
    return out
}

/** Medallion on the left; the title and a small tag stacked beside it. */
internal fun tileHeader(context: Context, medal: View, title: String, tag: View): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(medal, LinearLayout.LayoutParams(context.dpi(50f), context.dpi(50f)).apply { rightMargin = context.dpi(12f) })
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(FitTextView(context, maxSp = 22f, minSp = 18f).apply {
            typeface = Fonts.display
            setTextColor(Palette.CREAM)
            text = title
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(tag, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(4f) })
    }
    addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
}
