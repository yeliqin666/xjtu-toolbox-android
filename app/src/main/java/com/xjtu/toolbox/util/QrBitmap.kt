package com.xjtu.toolbox.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 二维码位图。 */
object QrBitmap {

    /**
     * @param ecc 屏对屏扫没有污损和反光，用最低的 L 级把容量让给数据。
     *   印在纸上的码（比如支付码）该用更高的等级。
     * @return 内容装不下时返回 null，而不是抛给调用方一个 WriterException。
     */
    fun generate(
        text: String,
        size: Int,
        ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE,
        margin: Int = 1,
    ): Bitmap? = runCatching {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ecc,
                EncodeHintType.MARGIN to margin,
            ),
        ).toBitmap(foreground, background)
    }.getOrNull()

    private fun BitMatrix.toBitmap(foreground: Int, background: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (this[x, y]) foreground else background
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
