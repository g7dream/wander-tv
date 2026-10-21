package tv.wander.net

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Android Bitmap wrapper around the QR pixels (needs Robolectric for android.graphics). */
@RunWith(RobolectricTestRunner::class)
class QrBitmapTest {
    @Test
    fun encodesIntoAnArgbBitmapThatScans() {
        val ink = 0xFF120E1C.toInt()
        val cream = 0xFFF7EFE2.toInt()
        val url = "http://192.168.1.23:8080"
        val bmp = Qr.encode(url, 400, ink, cream)
        assertNotNull(bmp)
        assertEquals(400, bmp!!.width)
        assertEquals(400, bmp.height)
        assertEquals(cream, bmp.getPixel(0, 0))
        val pixels = IntArray(400 * 400)
        bmp.getPixels(pixels, 0, 400, 0, 0, 400, 400)
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(400, 400, pixels)))).text
        assertEquals(url, text)
        assertNull(Qr.encode("", 400, ink, cream))
        assertNull(Qr.encode(url, 8, ink, cream))
    }
}
