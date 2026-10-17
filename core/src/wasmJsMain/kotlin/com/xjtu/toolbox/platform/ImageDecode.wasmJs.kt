package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Image

/** Web（Kotlin/Wasm）侧真实现：skiko 解码（Compose for Web 的 `ui-graphics` 变体就带 skiko）。 */
actual fun decodeImageSize(bytes: ByteArray): IntSize? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    IntSize(image.width, image.height)
}.getOrNull()

actual fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/** Web 没有滑块识别路径（场馆写操作在只读端进不去）⇒ 恒 null，不假装会解。 */
actual fun decodeImagePixels(bytes: ByteArray): ImagePixels? = null

/** Web 侧：skiko 全尺寸解码（与 [decodeImage] 同一解码器）。 */
actual fun decodeImageFull(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/** Web 侧：skiko 没有 `inSampleSize` ⇒ 全尺寸解码（见 expect）。 */
actual fun decodeImageDownToWidth(
    bytes: ByteArray,
    size: IntSize,
    wantWidthPx: Int,
    halveSample: Boolean,
): ImageBitmap? = decodeImageFull(bytes)

/** Web 侧：浏览器不给可用内存 ⇒ 固定 32MB。 */
actual fun imageMemoryCacheBudgetBytes(): Int = 32 * 1024 * 1024
