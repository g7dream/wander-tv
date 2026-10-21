package tv.wander.quest

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import tv.wander.model.Player
import tv.wander.model.QuestOutcome
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dpi

/** The result banner that slides in at the top: a burst for a find, a nudge for a duplicate or a miss. */
internal class QuestBannerView(context: Context) : FrameLayout(context) {
    private val row = LinearLayout(context)
    private val badgeHolder = FrameLayout(context)
    private val burst = BurstView(context)
    private val headline = Ui.text(context, 30f, Palette.CREAM, Fonts.display)
    private val quote = Ui.text(context, 18f, Palette.withAlpha(Palette.CREAM, 0.88f), Fonts.body)
    private val caption = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold)
    private var badge: View? = null

    init {
        clipChildren = false
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dpi(18f), dpi(16f), dpi(28f), dpi(16f))
        badgeHolder.clipChildren = false
        badgeHolder.addView(burst, LayoutParams(dpi(104f), dpi(104f), Gravity.CENTER))
        row.addView(badgeHolder, LinearLayout.LayoutParams(dpi(92f), dpi(92f)).apply { rightMargin = dpi(16f) })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            headline.maxLines = 1
            headline.ellipsize = TextUtils.TruncateAt.END
            addView(headline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            quote.maxLines = 2
            quote.ellipsize = TextUtils.TruncateAt.END
            addView(quote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
            caption.maxLines = 1
            caption.ellipsize = TextUtils.TruncateAt.END
            addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        }
        row.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Caps the text column so the banner never grows past [maxWidthPx]. */
    fun setMaxWidth(maxWidthPx: Int) {
        val textMax = (maxWidthPx - dpi(92f + 16f + 18f + 28f)).coerceAtLeast(dpi(200f))
        headline.maxWidth = textMax
        quote.maxWidth = textMax
        caption.maxWidth = textMax
        headline.minWidth = (textMax * 0.72f).toInt().coerceAtMost(dpi(340f))
    }

    fun bind(outcome: QuestOutcome, player: Player) {
        val me = Palette.player(player.colorIndex)
        val name = displayName(player)
        when (outcome) {
            is QuestOutcome.Accepted -> {
                setBadge(AvatarView(context, name, me))
                frame(me)
                headline.text = SpannableStringBuilder().apply {
                    append("+${QuestEngine.POINTS_PER_ITEM} ", ForegroundColorSpan(me))
                    append("${outcome.found.item}!")
                }
                val evidence = outcome.found.evidence?.trim().orEmpty()
                quote.text = if (evidence.isNotEmpty()) "“${evidence.trim('"', '“', '”')}”" else "Lumo read the page, and it counts."
                quote.visibility = View.VISIBLE
                val goal = outcome.board.quest.goal.coerceAtLeast(1)
                caption.text = SpannableStringBuilder().apply {
                    append(name, ForegroundColorSpan(me))
                    append(" found it")
                    if (outcome.points > QuestEngine.POINTS_PER_ITEM) {
                        append("  ·  ")
                        append("+${outcome.points - QuestEngine.POINTS_PER_ITEM} for finishing the quest!", ForegroundColorSpan(Palette.LAMP))
                    } else {
                        append("  ·  ${outcome.board.found.size.coerceAtMost(goal)} of $goal found")
                    }
                }
                burst.fire(me)
            }
            is QuestOutcome.Duplicate -> {
                val key = QuestEngine.normalize(outcome.item)
                val first = outcome.board.found.firstOrNull { QuestEngine.normalize(it.item) == key }
                val finder = first?.let { f -> outcome.board.players.firstOrNull { it.id == f.playerId } }
                val finderColor = finder?.let { Palette.player(it.colorIndex) } ?: Palette.SKY
                val finderName = when {
                    finder == null -> "someone"
                    finder.id == player.id -> "you"
                    else -> displayName(finder)
                }
                setBadge(AvatarView(context, finder?.let { displayName(it) } ?: "?", finderColor))
                frame(finderColor)
                headline.text = SpannableStringBuilder().apply {
                    append("Already found by ")
                    append(finderName, ForegroundColorSpan(finderColor))
                }
                quote.text = "${outcome.item} is already on the board."
                quote.visibility = View.VISIBLE
                caption.text = SpannableStringBuilder().apply {
                    append(name, ForegroundColorSpan(me))
                    append(", try a different one!")
                }
            }
            is QuestOutcome.Rejected -> {
                setBadge(MedallionView(context, "", Palette.CORAL).apply { showRing = false })
                frame(Palette.CORAL)
                headline.text = SpannableStringBuilder().apply {
                    append("Not quite, ", ForegroundColorSpan(Palette.CORAL))
                    append(name, ForegroundColorSpan(me))
                }
                val reason = outcome.reason.trim()
                quote.text = if (reason.isNotEmpty()) "“${reason.trim('"', '“', '”')}”" else "That page doesn't prove it yet."
                quote.visibility = View.VISIBLE
                caption.text = "Lumo checked it. Try another page!"
            }
        }
    }

    private fun frame(accent: Int) {
        row.background = Ui.roundRect(context, Palette.NIGHT, 26f, Palette.withAlpha(accent, 0.7f), 2f)
    }

    private fun setBadge(view: View) {
        badge?.let { badgeHolder.removeView(it) }
        badge = view
        badgeHolder.addView(view, LayoutParams(dpi(64f), dpi(64f), Gravity.CENTER))
    }
}

private fun SpannableStringBuilder.append(text: CharSequence, span: Any): SpannableStringBuilder {
    val start = length
    append(text)
    setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return this
}
