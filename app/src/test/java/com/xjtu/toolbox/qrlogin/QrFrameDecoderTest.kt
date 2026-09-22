package com.xjtu.toolbox.qrlogin

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {

    /** 图书馆 056 号座位桌贴实拍（2026-09-22）：左下定位块下沿磨掉了，只裁了码的附近。 */
    private fun damagedSeat(): Triple<ByteArray, Int, Int> {
        val img = javax.imageio.ImageIO.read(
            requireNotNull(javaClass.classLoader?.getResource("qr/seat_damaged_finder.png"))
        )
        val w = img.width
        val h = img.height
        val y = ByteArray(w * h)
        for (j in 0 until h) for (i in 0 until w) y[j * w + i] = (img.getRGB(i, j) and 0xff).toByte()
        return Triple(y, w, h)
    }

    private val seatUrl = "http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=056&sp=north4southwest"

    @Test
    fun plainZxingCannotReadDamagedFinder() {
        val (y, w, h) = damagedSeat()
        assertNull(QrFrameDecoder.decode(y, w, w, h, repair = false))
    }

    @Test
    fun repairedFinderReadsDamagedSeatCode() {
        val (y, w, h) = damagedSeat()
        assertEquals(seatUrl, QrFrameDecoder.decode(y, w, w, h))
    }

    @Test
    fun respectsRowStridePadding() {
        val (y, w, h) = damagedSeat()
        val stride = w + 48
        val padded = ByteArray(stride * h) { 0x7f }
        for (j in 0 until h) System.arraycopy(y, j * w, padded, j * stride, w)
        assertEquals(seatUrl, QrFrameDecoder.decode(padded, stride, w, h))
    }

    @Test
    fun cleanCodeStillDecodes() {
        val m = QRCodeWriter().encode("hello-xjtu", BarcodeFormat.QR_CODE, 300, 300)
        val y = ByteArray(300 * 300) { i -> if (m[i % 300, i / 300]) 0 else 0xff.toByte() }
        assertEquals("hello-xjtu", QrFrameDecoder.decode(y, 300, 300, 300))
    }

    @Test
    fun noiseYieldsNothing() {
        val rnd = java.util.Random(7)
        val y = ByteArray(640 * 480).also { rnd.nextBytes(it) }
        assertNull(QrFrameDecoder.decode(y, 640, 640, 480))
    }
}
