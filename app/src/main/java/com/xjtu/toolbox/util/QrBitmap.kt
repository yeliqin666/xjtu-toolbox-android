package com.xjtu.toolbox.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码位图。
 *
 * ## 容量与「扫得动」不是一回事
 *
 * 规范上 40 版 + L 级纠错能装 2953 字节，但那是 177×177 个模块。手机屏对手机屏地扫，
 * 取景分析用的是 640×480 那一档，177 个模块只剩不到 3 像素一格，实际扫不出来。
 * 所以这里给的不是规范上限而是 [COMFORTABLE] / [MAX_SCANNABLE] 两个经验值，
 * 调用方据此决定"展示二维码"还是"让用户改用复制粘贴"。
 */
object QrBitmap {

    /** 这个长度以内的码，隔一臂远随手一扫就出。约 25 版。 */
    const val COMFORTABLE = 1200

    /** 再长就得凑近、对准。超过这个值不如别给二维码，省得用户在那对半天。 */
    const val MAX_SCANNABLE = 1800

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

    /**
     * 从一张图里读出二维码，读不到返回 null。
     *
     * 用来支持"从相册选一张截图"：两个人不在同一个地方时，对方把码截图发过来，
     * 这边选一下就行，不必让用户去别的 App 里长按识别再复制一段 base64 回来。
     *
     * 相册里的图动辄四千像素宽，整张丢给 zxing 又慢又容易被噪点带偏，
     * 所以先按 [MAX_DECODE_EDGE] 等比缩一遍。TRY_HARDER 打开：这是一次性的
     * 用户操作，不是每秒三十帧的取景，慢一点换识别率是划算的。
     */
    fun read(source: Bitmap): String? {
        val scaled = downscale(source)
        return try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val binary = BinaryBitmap(
                HybridBinarizer(RGBLuminanceSource(scaled.width, scaled.height, pixels))
            )
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    )
                )
            }
            reader.decodeWithState(binary).text
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** 解码前把长边压到这个像素数以内。再大对识别没有帮助，只是更慢。 */
    private const val MAX_DECODE_EDGE = 1600

    private fun downscale(source: Bitmap): Bitmap {
        val edge = maxOf(source.width, source.height)
        if (edge <= MAX_DECODE_EDGE) return source
        val ratio = MAX_DECODE_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

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
