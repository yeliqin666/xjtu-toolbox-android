package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Image

/**
 * Web（Kotlin/Wasm）侧真实现：skiko 解码（Compose for Web 的 `ui-graphics` 变体就带 skiko）。
 *
 * 与桌面端一样的一处**如实差异**：skiko 没有 `BitmapFactory.inSampleSize`，[maxDim] 被忽略，
 * 一次解出全尺寸 —— 不假装做了内存优化。
 *
 * 但这条缝在 Web 上**今天走不到**：campus-api 没有座位布局、也没有平面图图片这两个端点
 * （见 `library/LibrarySource.hasSeatPlan` 的 KDoc），`CampusLibraryApi.hasSeatPlan = false`
 * ⇒ 屏不画平面图那一档、ViewModel 也不去取底图字节 ⇒ 解码函数一次都不会被调到。
 * 留着真实现是为了「以后有端点就白拿」；真取不到时 `decodePlanImages` 返回 null，
 * 屏那边是「平面图加载失败」，不画一张假的底图。
 */
actual fun decodeImageSize(bytes: ByteArray): IntSize? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    IntSize(image.width, image.height)
}.getOrNull()

actual fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
