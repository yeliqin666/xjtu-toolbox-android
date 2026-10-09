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
