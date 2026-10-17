package com.xjtu.toolbox.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

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

/** Android 侧：全尺寸 ARGB_8888 解码。 */
actual fun decodeImageFull(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

actual fun decodeImageDownToWidth(
    bytes: ByteArray,
    size: IntSize,
    wantWidthPx: Int,
    halveSample: Boolean,
): ImageBitmap? {
    var sample = 1
    if (wantWidthPx > 0) {
        while (size.width / (sample * 2) >= wantWidthPx) sample *= 2
    }
    if (halveSample) sample = (sample / 2).coerceAtLeast(1)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

actual fun imageMemoryCacheBudgetBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt()
