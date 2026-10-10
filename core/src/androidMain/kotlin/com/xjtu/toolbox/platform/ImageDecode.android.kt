package com.xjtu.toolbox.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * Android 侧真实现 —— 与搬迁前 `:app/library/LibrarySeatPlan.kt` 里那两段**逐字同构**
 * （`inJustDecodeBounds` 读原始尺寸；按 [maxDim] 取 2 的幂 `inSampleSize`；`RGB_565`）。
 *
 * 这里只差一处：原来尺寸是在 `decodePlanImages` 里读的，现在由那个纯逻辑函数调 [decodeImageSize]。
 * 每个文件被解析一次的次数、解码参数、返回的位图完全没变。
 */
actual fun decodeImageSize(bytes: ByteArray): IntSize? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return IntSize(bounds.outWidth, bounds.outHeight)
}

actual fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap? {
    var sample = 1
    while (max(size.width, size.height) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * Android 侧真实现 —— 与搬迁前 `:app/venue/VenueCaptchaSolver.kt` 里那段
 * `BitmapFactory.decodeByteArray` + `getPixels` 逐字同构：全尺寸、ARGB_8888、非预乘。
 * 位图在函数内即时回收（识别器只拿像素，不画）—— 与搬迁前 `solve` 的 finally 同一件事。
 */
actual fun decodeImagePixels(bytes: ByteArray): ImagePixels? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ImagePixels(bitmap.width, bitmap.height, pixels)
    } finally {
        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}

/** Android 侧：全尺寸 ARGB_8888 解码（默认配置）—— `SliderCaptchaView` 搬迁前的原样路径。 */
actual fun decodeImageFull(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
