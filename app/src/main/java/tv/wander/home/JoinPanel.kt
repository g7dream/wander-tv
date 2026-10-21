package tv.wander.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import tv.wander.model.Player
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi

/**
 * "Join the couch": the QR code phones scan (dark modules on a cream tile so any camera reads it),
 * the address for anyone who'd rather type it, and four seats that fill in each player's color.
 */
internal class JoinPanel(context: Context) : FrameLayout(context) {
    private val qrTile = FrameLayout(context)
    private val qrImage = ImageView(context)
    private val noCode = Ui.text(context, 40f, Palette.CREAM, Fonts.body, "📶").apply {
        gravity = Gravity.CENTER
        alpha = 0.45f
    }
    private val address = Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.bodyBold).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val hint = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body).apply { maxLines = 3 }
    private val seats = List(4) { SeatPill(context) }
    private var players: List<Player> = emptyList()

    init {
        background = context.washedCard(Palette.LAMP, Dimens.RADIUS_BIG, 0.13f)
        val pad = 16f
        place(Ui.text(context, TypeScale.H2, Palette.CREAM, Fonts.display, "Join the couch").apply { maxLines = 1 }, pad, 16f)

        qrTile.addView(qrImage, LayoutParams(dpi(QR_DP), dpi(QR_DP), Gravity.CENTER))
        qrTile.addView(noCode, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        qrImage.scaleType = ImageView.ScaleType.FIT_CENTER
        place(qrTile, pad, 56f, TILE_DP, TILE_DP)

        val seatColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        seats.forEachIndexed { i, seat ->
            seatColumn.addView(seat, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(SEAT_H)).apply {
                if (i > 0) topMargin = dpi((TILE_DP - 4 * SEAT_H) / 3f)
            })
        }
        place(seatColumn, pad + TILE_DP + 8f, 56f, WIDTH - pad * 2 - TILE_DP - 8f, TILE_DP)

        place(address, pad, 56f + TILE_DP + 12f, WIDTH - pad * 2)
        place(hint, pad, 56f + TILE_DP + 42f, WIDTH - pad * 2)
        setJoinInfo(null, null)
        setPlayers(emptyList())
    }

    fun setJoinInfo(url: String?, qr: Bitmap?) {
        if (url.isNullOrBlank()) {
            showCode(null, "📶")
            address.setTextSize(TypedValue.COMPLEX_UNIT_SP, TypeScale.BODY)
            address.text = "No Wi-Fi yet"
            hint.text = "Connect the TV to Wi-Fi to pair phones."
            contentDescription = "Connect the TV to Wi-Fi to pair phones."
            return
        }
        val shown = url.removePrefix("http://").removeSuffix("/")
        val code = qr?.takeIf { !it.isRecycled }
        showCode(code, "📱")
        address.text = shown
        fitAddress()
        hint.text = if (code != null) "Scan with your phone camera. No app needed." else "Type this address into your phone's browser."
        contentDescription = "Join the couch: scan the code or open $shown on your phone."
    }

    /** Cream tile with the code when there is one; otherwise a quiet dashed placeholder with [glyph]. */
    private fun showCode(code: Bitmap?, glyph: String) {
        if (code != null) {
            qrTile.background = Ui.roundRect(context, Palette.CREAM, 16f)
            // Nearest-neighbour scaling keeps every module crisp, which is what phone cameras need.
            qrImage.setImageDrawable(BitmapDrawable(resources, code).apply {
                isFilterBitmap = false
                setAntiAlias(false)
                paint.flags = paint.flags and Paint.FILTER_BITMAP_FLAG.inv()
            })
            qrImage.visibility = View.VISIBLE
            noCode.visibility = View.GONE
        } else {
            qrTile.background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(Palette.withAlpha(Palette.DUSK, 0.7f))
                setStroke(dpi(1.5f), Palette.withAlpha(Palette.MUTED, 0.45f), dp(6f), dp(5f))
            }
            qrImage.setImageDrawable(null)
            qrImage.visibility = View.INVISIBLE
            noCode.text = glyph
            noCode.visibility = View.VISIBLE
        }
    }

    fun setPlayers(list: List<Player>) {
        players = list.filter { it.connected }.take(4)
        val taken = players.map { ((it.colorIndex % 4) + 4) % 4 }.toSet()
        val free = (0 until 4).filter { it !in taken }.toMutableList()
        seats.forEachIndexed { i, seat ->
            val p = players.getOrNull(i)
            if (p != null) seat.bindPlayer(p) else seat.bindEmpty(free.removeFirstOrNull() ?: i)
        }
    }

    /** Long addresses (192.168.100.123:8080) step down to caption size instead of being cut off. */
    private fun fitAddress() {
        val avail = resources.displayMetrics.density * (WIDTH - 32f)
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, TypeScale.BODY)
        if (address.paint.measureText(address.text.toString()) > avail) {
            address.setTextSize(TypedValue.COMPLEX_UNIT_SP, TypeScale.CAPTION)
        }
    }

    companion object {
        const val WIDTH = 220f
        const val HEIGHT = 270f
        const val TILE_DP = 116f
        const val QR_DP = 106f
        const val SEAT_H = 26f
    }
}
