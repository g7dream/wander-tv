package tv.wander.quest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import tv.wander.cards.Craft
import tv.wander.model.Player
import tv.wander.model.QuestBoard
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dpi

/**
 * The scoreboard that rides along in the top-right corner while everyone browses: the quest, progress
 * pips in the finders' colors, what has been found by whom, the players by score, and a live
 * "Checking Maya's page..." row while the judge thinks.
 */
internal class QuestBoardView(context: Context) : LinearLayout(context) {
    private val emoji = Ui.text(context, 22f, Palette.CREAM, Fonts.body)
    private val title = Ui.text(context, 21f, Palette.CREAM, Fonts.display)
    private val head = LinearLayout(context)
    private val progress = LinearLayout(context)
    private val pips = PipsView(context, pipDp = 16f, gapDp = 7f)
    private val count = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold)
    private val foundList = LinearLayout(context)
    private val divider = View(context)
    private val playerList = LinearLayout(context)
    private val checkingRow = LinearLayout(context)
    private val spinner = SpinnerView(context)
    private val checkingText = Ui.text(context, TypeScale.CAPTION, Palette.CREAM, Fonts.bodyBold)

    private var lastQuestId: String? = null
    private var lastFoundCount = 0

    init {
        orientation = VERTICAL
        background = Ui.roundRect(context, Palette.NIGHT, Dimens.RADIUS, 0xFF4E4172.toInt(), 1.5f)
        setPadding(dpi(18f), dpi(16f), dpi(18f), dpi(16f))

        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(emoji, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(10f) })
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        progress.orientation = HORIZONTAL
        progress.gravity = Gravity.CENTER_VERTICAL
        progress.addView(pips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        progress.addView(View(context), LayoutParams(0, 1, 1f))
        progress.addView(count)
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })

        foundList.orientation = VERTICAL
        addView(foundList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })

        divider.setBackgroundColor(Palette.withAlpha(Palette.CREAM, 0.1f))
        addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dpi(1f)).apply { topMargin = dpi(12f); bottomMargin = dpi(10f) })

        playerList.orientation = VERTICAL
        addView(playerList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        checkingRow.orientation = HORIZONTAL
        checkingRow.gravity = Gravity.CENTER_VERTICAL
        checkingRow.setPadding(dpi(12f), dpi(9f), dpi(12f), dpi(9f))
        checkingRow.addView(spinner, LayoutParams(dpi(22f), dpi(22f)).apply { rightMargin = dpi(12f) })
        checkingText.maxLines = 2
        checkingText.ellipsize = TextUtils.TruncateAt.END
        checkingRow.addView(checkingText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        checkingRow.visibility = View.GONE
        addView(checkingRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
    }

    /** Fills the panel from [board]; pops the pips of anything new since the last call. */
    fun bind(board: QuestBoard) {
        setBare(false)
        val quest = board.quest
        val sameQuest = quest.id == lastQuestId
        emoji.text = Craft.firstGlyph(quest.emoji).ifEmpty { "✨" }
        title.text = quest.title
        val goal = quest.goal.coerceAtLeast(1)
        val found = board.found
        val colors = found.take(PipsView.MAX_PIPS).map { f -> colorOf(f.playerId, board.players) }
        val from = if (sameQuest && found.size > lastFoundCount) lastFoundCount else Int.MAX_VALUE
        pips.set(goal, colors, from)
        count.text = if (board.complete) "done!" else "${found.size.coerceAtMost(goal)} of $goal"
        count.setTextColor(if (board.complete) Palette.MINT else Palette.MUTED)
        lastQuestId = quest.id
        lastFoundCount = found.size

        foundList.removeAllViews()
        if (found.isEmpty()) {
            foundList.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Nothing found yet. The hunt is on!").apply {
                maxLines = 2
            })
        } else {
            // Newest at the bottom; only the last few fit in the corner.
            val maxRows = if (board.players.size >= 3) 3 else 4
            val shown = found.takeLast(maxRows)
            if (found.size > shown.size) {
                foundList.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "+${found.size - shown.size} earlier"))
            }
            for (f in shown) {
                val finder = board.players.firstOrNull { it.id == f.playerId }
                foundList.addView(row(colorOf(f.playerId, board.players), f.item, finder?.let { displayName(it) }.orEmpty()))
            }
        }

        playerList.removeAllViews()
        if (board.players.isEmpty()) {
            playerList.addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Scan the QR code to join in"))
        } else {
            // "lead" only for a clear leader, never for a tie at the top.
            val leader = board.players.first()
            val clear = leader.score > 0 && board.players.none { it !== leader && it.score >= leader.score }
            for (p in board.players.take(4)) {
                playerList.addView(playerRow(p, isLeader = clear && p === leader))
            }
        }
    }

    fun showChecking(name: String, color: Int, more: Int) {
        spinner.color = color
        val whose = when {
            more > 0 -> "${more + 1} pages..."
            name.isBlank() -> "the page..."
            else -> "$name's page..."
        }
        checkingText.text = SpannableStringBuilder("Checking\n").apply {
            val start = length
            append(whose)
            setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        checkingRow.background = Ui.roundRect(context, Palette.withAlpha(color, 0.14f), 14f, Palette.withAlpha(color, 0.5f), 1.5f)
        checkingRow.visibility = View.VISIBLE
    }

    fun hideChecking() {
        checkingRow.visibility = View.GONE
    }

    /** Before any quest board exists (a submit with no quest on screen yet) only the checking row shows. */
    fun showBareChecking() {
        setBare(true)
        lastQuestId = null
        lastFoundCount = 0
    }

    fun reset() {
        lastQuestId = null
        lastFoundCount = 0
        hideChecking()
    }

    private fun setBare(bare: Boolean) {
        val v = if (bare) View.GONE else View.VISIBLE
        head.visibility = v
        progress.visibility = v
        foundList.visibility = v
        divider.visibility = v
        playerList.visibility = v
        (checkingRow.layoutParams as LayoutParams).topMargin = if (bare) 0 else dpi(12f)
        checkingRow.requestLayout()
    }

    private fun row(color: Int, label: String, trailing: String): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(3f), 0, dpi(3f))
        addView(Dot(context, color), LayoutParams(dpi(10f), dpi(10f)).apply { rightMargin = dpi(10f) })
        addView(Ui.text(context, 18f, Palette.CREAM, Fonts.bodyBold, label).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (trailing.isNotBlank()) {
            addView(Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body, trailing).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dpi(90f)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(8f) })
        }
    }

    private fun playerRow(p: Player, isLeader: Boolean): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(3f), 0, dpi(3f))
        val color = Palette.player(p.colorIndex)
        val name = displayName(p)
        addView(AvatarView(context, name, color), LayoutParams(dpi(24f), dpi(24f)).apply { rightMargin = dpi(10f) })
        addView(Ui.text(context, 18f, if (p.connected) Palette.CREAM else Palette.MUTED, Fonts.body, name).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (isLeader) {
            addView(Ui.text(context, TypeScale.CAPTION, Palette.LAMP, Fonts.bodyBold, "lead").apply {
                background = Ui.roundRect(context, Palette.withAlpha(Palette.LAMP, 0.14f), 999f)
                setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(8f) })
        }
        addView(Ui.text(context, 20f, color, Fonts.display, p.score.toString()))
    }

    private class Dot(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            paint.color = color
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
        }
    }

    companion object {
        const val WIDTH_DP = 260f

        fun colorOf(playerId: String, players: List<Player>): Int =
            players.firstOrNull { it.id == playerId }?.let { Palette.player(it.colorIndex) } ?: Palette.CREAM
    }
}
