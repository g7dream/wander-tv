package tv.wander.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.random.Random

/**
 * Stand-in for the WebView in screenshots (WebView doesn't render under Robolectric): an article layout
 * with a header bar, a hero image block, a headline and paragraphs of text lines.
 */
class FakePage(context: Context, private val dark: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = h / 540f
        val bg = if (dark) 0xFF101820.toInt() else 0xFFFFFFFF.toInt()
        val bar = if (dark) 0xFF18232E.toInt() else 0xFFF1F3F5.toInt()
        val ink = if (dark) 0xFFB8C4CF.toInt() else 0xFF3C4148.toInt()
        val faint = if (dark) 0xFF41505E.toInt() else 0xFFC9CED4.toInt()
        canvas.drawColor(bg)
        paint.color = bar
        canvas.drawRect(0f, 0f, w, 44 * u, paint)
        paint.color = if (dark) 0xFF2E7D6B.toInt() else 0xFF3366CC.toInt()
        rect.set(60 * u, 14 * u, 170 * u, 30 * u)
        canvas.drawRoundRect(rect, 4 * u, 4 * u, paint)
        paint.color = if (dark) 0xFF7A5A2E.toInt() else 0xFFE9C98F.toInt()
        rect.set(600 * u, 70 * u, 900 * u, 270 * u)
        canvas.drawRoundRect(rect, 6 * u, 6 * u, paint)
        paint.color = ink
        rect.set(60 * u, 72 * u, 470 * u, 96 * u)
        canvas.drawRoundRect(rect, 4 * u, 4 * u, paint)
        val rnd = Random(3)
        var y = 120 * u
        while (y < h - 20 * u) {
            repeat(5) {
                val right = if (y < 280 * u) 560f else 900f
                val len = (0.75f + rnd.nextFloat() * 0.25f) * (right - 60f)
                paint.color = faint
                rect.set(60 * u, y, (60f + len) * u, y + 8 * u)
                canvas.drawRoundRect(rect, 3 * u, 3 * u, paint)
                y += 17 * u
            }
            y += 14 * u
        }
    }
}
