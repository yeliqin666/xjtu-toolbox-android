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

/**
 * JVM 侧真实现：javax.imageio 解出**非预乘 ARGB** int —— 与 Android 的
 * `Bitmap.getPixels` 语义一致（识别器按像素比较，预乘与否会改掉半透明边缘的 RGB）。
 *
 * 用 ImageIO 而不是 skiko：skiko 只能按 skia 的 RGBA8888（预乘）读像素，而
 * `BufferedImage.getRGB` 给的就是 ARGB8888 非预乘，与 `BitmapFactory.getPixels` 逐位同构。
 */
actual fun decodeImagePixels(bytes: ByteArray): ImagePixels? = runCatching {
    val source = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
    val w = source.width
    val h = source.height
    // 统一摊到非预乘 ARGB：源图可能是灰度/索引色，getRGB 要一个这样的画布才能逐像素读。
    val argb = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = argb.createGraphics()
    try {
        g.drawImage(source, 0, 0, null)
    } finally {
        g.dispose()
    }
    val pixels = IntArray(w * h)
    argb.getRGB(0, 0, w, h, pixels, 0, w)
    ImagePixels(w, h, pixels)
}.getOrNull()

/** JVM 侧：skiko 全尺寸解码（与 [decodeImage] 同一解码器，只是不做降采样那一套）。 */
actual fun decodeImageFull(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
