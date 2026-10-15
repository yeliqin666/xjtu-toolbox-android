package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * jvm（桌面 / serve 模式 / `:core:jvmTest`）侧的二维码解码：**zxing 那条链**。
 *
 * 与 androidMain 那份 actual **逐字同一份实现**（zxing 是纯 Java jar，两端同源），也与
 * `:app` 的 `qrlogin/QrFrameDecoder.decodeArgb` 的**常规那一档**同参数：
 *
 * | | `:app` 的 `QrFrameDecoder` | 这里 |
 * |---|---|---|
 * | 灰度 | `(r*299 + g*587 + b*114) / 1000` | 同一个式子（逐字） |
 * | 位图 | `PlanarYUVLuminanceSource(luma, width, width, 0, 0, width, height, false)` | 同一个 |
 * | 二值化 | `HybridBinarizer` | 同一个 |
 * | 解码 | `QRCodeReader().decode(BinaryBitmap(…), hints)`，hints = `POSSIBLE_FORMATS=[QR_CODE]` + `TRY_HARDER` | 同一个 |
 * | 失败后 | 还有一手 `decodeWithRepairedFinder`（补定位块） | **没有**（那是 `:app` 自己的一档，见 `QrDecode.kt` 的 KDoc） |
 *
 * ⚠️ 那一手「补定位块」**只**留在 `:app`（它要 `FinderCandidates`，也是 `:app` 自己的一份）⇒
 * 磨掉一角的码在**这条缝**上读不出来，而 Android 的匹配交友屏读得出来。这是如实差异，不是遗漏。
 */
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
