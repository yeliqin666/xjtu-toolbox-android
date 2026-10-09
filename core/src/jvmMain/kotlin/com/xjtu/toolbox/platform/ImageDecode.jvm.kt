package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Image

/**
 * 桌面（jvm）侧真实现：skiko 解码。
 *
 * ⚠️ 与 Android 的一处**如实差异**：skiko 没有 `BitmapFactory.inSampleSize` 那样的「按比例少解一点」
 * 开关 —— 它一次解出全尺寸。所以 [maxDim] 在这一端只被忽略（不假装做了内存优化），
 * 解出来的像素数比 Android 多。绘制口径不受影响：`PlanImages` 记录的是**原始**尺寸，
 * 全尺寸底图按原始尺寸画，与 Android 的「降采样后放大回原始尺寸」落在同一个坐标系里。
 *
 * 这一端没有业务屏会真的加载座位图（桌面只跑 :core 的单测/预览），所以不做缩放也不影响谁。
 */
actual fun decodeImageSize(bytes: ByteArray): IntSize? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    IntSize(image.width, image.height)
}.getOrNull()

actual fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
