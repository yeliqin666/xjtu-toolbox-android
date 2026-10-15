package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** jvm（桌面 / serve 模式 / `:core:jvmTest`）侧的二维码解码：zxing 那条链。 */
actual fun qrDecodeArgb(pixels: IntArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    if (pixels.size < width * height) return null
    return runCatching {
        val luma = ByteArray(width * height)
        for (i in luma.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        val source = PlanarYUVLuminanceSource(luma, width, width, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        QRCodeReader().decode(
            bitmap,
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // 分享码那种上千字的码是 25 版以上，模块密、容错只有 L 级，不开 TRY_HARDER 基本扫不出来
                DecodeHintType.TRY_HARDER to true,
            ),
        ).text
    }.getOrNull()
}
