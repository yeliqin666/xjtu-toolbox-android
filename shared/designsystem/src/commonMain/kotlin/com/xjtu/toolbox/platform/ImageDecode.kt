package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

/** 平台能力：把一张图片的字节解成 [ImageBitmap]。 */
expect fun decodeImageSize(bytes: ByteArray): IntSize?

/** 按 [maxDim] 上限降采样解码一张图。[size] 是 [decodeImageSize] 刚读出来的尺寸。 */
expect fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap?

/** 解出的原始像素（ARGB8888、非预乘） */
data class ImagePixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/** 全尺寸、ARGB8888 解码一张图并解出原始像素 —— 场馆滑块验证码的自动识别用。 */
expect fun decodeImagePixels(bytes: ByteArray): ImagePixels?

/** 全尺寸解码一张可绘制的图。 */
expect fun decodeImageFull(bytes: ByteArray): ImageBitmap?

/** 按目标宽度降采样解码 —— 教材全文阅读器那一屏的页面走这条（`jiaocai1` 域）。 */
expect fun decodeImageDownToWidth(
    bytes: ByteArray,
    size: IntSize,
    wantWidthPx: Int,
    halveSample: Boolean,
): ImageBitmap?

/**
 * 按原始像素裁出 [left, top, right, bottom) 这一块，长边超过 [maxDim] 才缩，编码成 `data:image/jpeg;base64,…`
 * 给视觉模型看（小字、座位号要原分辨率才看得清）。不支持的端返回 null。
 */
expect fun cropImageToJpegDataUri(bytes: ByteArray, left: Int, top: Int, right: Int, bottom: Int, maxDim: Int): String?

/**
 * 只用来显示的静态大图换成显存里的一份（Android 的硬件位图）：不会因为 GPU 纹理缓存被挤掉而逐帧重新上传。
 * 换出来的这份不能再读像素、不能画到软件画布上；没有这种位图的端原样返回。
 */
expect fun ImageBitmap.toDisplayBitmap(): ImageBitmap

/** 平台能力：解码后位图缓存的内存预算（字节）。 */
expect fun imageMemoryCacheBudgetBytes(): Int
