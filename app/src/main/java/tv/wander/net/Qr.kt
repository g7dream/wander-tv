package tv.wander.net

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code bitmap (ZXing) in Wander colors, with a quiet zone. */
object Qr {
    /**
     * Encodes [text] (error correction M, a one-module quiet zone) into a [sizePx] square ARGB bitmap.
     * Modules are whole pixels, so edges stay crisp at any size; the code is centered and any spare
     * pixels become extra [bg] margin. Dark [fg] on light [bg] scans best on every phone camera.
     * Returns null for empty text, a size too small to draw every module, or any encoder failure.
     */
    fun encode(text: String, sizePx: Int, fg: Int, bg: Int): Bitmap? = try {
        val pixels = render(text, sizePx, fg, bg)
        if (pixels == null) {
            null
        } else {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** The QR module grid (including the quiet zone), or null. */
    internal fun matrix(text: String): BitMatrix? {
        if (text.isEmpty()) return null
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** Row-major ARGB pixels for a [sizePx] square, without touching Android graphics. */
    internal fun render(text: String, sizePx: Int, fg: Int, bg: Int): IntArray? {
        if (sizePx <= 0) return null
        val m = matrix(text) ?: return null
        val modules = m.width
        val cell = sizePx / modules
        if (cell < 1) return null
        val offset = (sizePx - cell * modules) / 2
        val pixels = IntArray(sizePx * sizePx)
        pixels.fill(bg)
        for (my in 0 until modules) {
            val top = offset + my * cell
            for (mx in 0 until modules) {
                if (!m.get(mx, my)) continue
                val left = offset + mx * cell
                for (y in top until top + cell) {
                    val row = y * sizePx
                    pixels.fill(fg, row + left, row + left + cell)
                }
            }
        }
        return pixels
    }
}
