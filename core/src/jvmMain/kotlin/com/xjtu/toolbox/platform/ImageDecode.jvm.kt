package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Image

/** 桌面（jvm）侧真实现：skiko 解码。 */
actual fun decodeImageSize(bytes: ByteArray): IntSize? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    IntSize(image.width, image.height)
}.getOrNull()

actual fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/** JVM 侧真实现：javax.imageio 解出非预乘 ARGB int。 */
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

/** JVM 侧：skiko 没有 `inSampleSize` ⇒ 全尺寸解码（`wantWidthPx` / `halveSample` 不起作用，见 expect）。 */
actual fun decodeImageDownToWidth(
    bytes: ByteArray,
    size: IntSize,
    wantWidthPx: Int,
    halveSample: Boolean,
): ImageBitmap? = decodeImageFull(bytes)

/** JVM 侧：拿不到进程可用内存 ⇒ 固定 32MB。 */
actual fun imageMemoryCacheBudgetBytes(): Int = 32 * 1024 * 1024
