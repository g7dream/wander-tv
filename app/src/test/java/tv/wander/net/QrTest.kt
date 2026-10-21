package tv.wander.net

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks of the QR pixels: they must scan, and every module must be a crisp square. */
class QrTest {
    private val ink = 0xFF120E1C.toInt()
    private val cream = 0xFFF7EFE2.toInt()

    private fun decode(pixels: IntArray, size: Int): String {
        val source = RGBLuminanceSource(size, size, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }

    @Test
    fun scansBackToTheSameUrl() {
        for (url in listOf("http://192.168.1.23:8080", "http://10.0.0.142:8080/", "http://172.16.254.201:8080/?join=1")) {
            val size = 420
            val pixels = Qr.render(url, size, ink, cream)
            assertNotNull(pixels)
            assertEquals(size * size, pixels!!.size)
            assertEquals(url, decode(pixels, size))
        }
    }

    @Test
    fun modulesAreCrispSquaresCenteredInTheBitmap() {
        val url = "http://192.168.1.23:8080"
        val modules = Qr.matrix(url)!!.width
        val size = 333 // deliberately not a multiple of the module count
        val pixels = Qr.render(url, size, ink, cream)!!
        assertTrue("only two colors", pixels.all { it == ink || it == cream })
        val cell = size / modules
        val offset = (size - cell * modules) / 2
        // The one-module quiet zone plus centering slack is all background.
        val quiet = offset + cell
        for (i in 0 until size) {
            for (edge in 0 until quiet) {
                assertEquals(cream, pixels[edge * size + i])
                assertEquals(cream, pixels[i * size + edge])
            }
        }
        // Each module is uniform: sample every pixel of every module against its top-left pixel.
        for (my in 0 until modules) for (mx in 0 until modules) {
            val x0 = offset + mx * cell
            val y0 = offset + my * cell
            val color = pixels[y0 * size + x0]
            for (y in y0 until y0 + cell) for (x in x0 until x0 + cell) assertEquals(color, pixels[y * size + x])
        }
        // The top-left finder pattern starts right after the quiet zone.
        assertEquals(ink, pixels[quiet * size + quiet])
        assertEquals(url, decode(pixels, size))
    }

    @Test
    fun refusesWhatCannotBeDrawn() {
        assertNull(Qr.render("", 300, ink, cream))
        assertNull(Qr.render("http://192.168.1.23:8080", 10, ink, cream))
        assertNull(Qr.render("http://192.168.1.23:8080", 0, ink, cream))
        assertNull(Qr.render("http://192.168.1.23:8080", -5, ink, cream))
    }
}
