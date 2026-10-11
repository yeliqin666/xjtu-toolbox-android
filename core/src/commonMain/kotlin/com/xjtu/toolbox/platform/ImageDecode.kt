package com.xjtu.toolbox.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

/**
 * 平台能力：**把一张图片的字节解成 [ImageBitmap]**。
 *
 * ## 为什么它是平台缝
 *
 * 图书馆的座位平面图是「一张底图 + 每种座位状态一张整图」（见 `LibrarySource.hasSeatPlan` 的 KDoc）。
 * 组装那张图需要知道三件事：底图的**原始像素尺寸**（坐标是相对它算的）、每个座位从状态图里
 * 裁哪一块、以及把裁下来的块画到哪儿。这三件都是纯算术，与「怎么把 JPEG 变成像素」无关，
 * 所以它们留在共享代码里（`library/LibrarySeatPlan.kt` 的 `decodePlanImages`），
 * 只有「解一张图」留在这里 —— Android 是 `BitmapFactory`，桌面/Web 是 skiko。
 *
 * ## 为什么拆成两个函数
 *
 * [decodeImageSize] 只读文件头（Android 的 `inJustDecodeBounds`），解码前就要用它做两件事：
 *  1. 尺寸认不出来 ⇒ 这张图不可用（底图返回 null 就整张图不画，状态图返回 null 就跳过那一张）；
 *  2. **状态图的宽高比要和底图对得上**，对不上说明不是同一个坐标系，宁可不用。
 * 这两件必须在**解码像素之前**做完。而且 [PlanImages][com.xjtu.toolbox.library.PlanImages] 的
 * `origWidth/origHeight` 是**原始**尺寸：底图是降采样解码的，画的时候要把降采样后的像素
 * 缩放回原始尺寸，所以尺寸不能从解出来的位图上取。
 *
 * 于是 [decodeImage] 把尺寸当参数收下（调用方刚读出来的那个），避免再解析一次文件头 ——
 * Android 侧因此与搬迁前的 `BitmapFactory` 路径**逐字同构**（一次边界解析 + 一次降采样解码）。
 *
 * [maxDim] 是内存上限：`LibrarySeatPlan` 里底图给 2048、状态图给 1024，按 2 的幂降采样、
 * 并且一律用 `RGB_565`（JPEG 没有透明通道，内存砍半；一张 2048 的底图约 8MB）。
 * Android 的 actual 完整实现这三条；桌面/Web 的 actual 没有 `inSampleSize` 这一档，
 * 按原始尺寸解码（见各 actual 的 KDoc）。
 */
expect fun decodeImageSize(bytes: ByteArray): IntSize?

/** 按 [maxDim] 上限降采样解码一张图。[size] 是 [decodeImageSize] 刚读出来的尺寸。 */
expect fun decodeImage(bytes: ByteArray, size: IntSize, maxDim: Int): ImageBitmap?

/**
 * 解出的**原始像素**（ARGB8888、非预乘）—— 滑块验证码识别器要的正是 Android
 * `Bitmap.getPixels` 那批 int 的语义（`0xAARRGGBB`）。JVM 侧用 ImageIO 的
 * `BufferedImage.getRGB`（同为非预乘 ARGB），语义等价；wasm 无识别路径，恒 null。
 */
data class ImagePixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * 全尺寸、ARGB8888 解码一张图并解出原始像素 —— 场馆滑块验证码的自动识别用。
 *
 * 与 [decodeImage] 的区别（两条缝各自服务的路径不同）：
 *  - [decodeImage] 是图书馆座位图的路径：`inSampleSize` 降采样 + `RGB_565`，只求画得对；
 *  - **滑块识别要的是像素本身**（Sobel 边缘 + NCC 匹配），降采样或 565 都会悄悄改掉边缘的
 *    形状 ⇒ 这里全尺寸解出原始 int，与搬进 `:data` 前 `:app` 的 `BitmapFactory.decodeByteArray`
 *    + `getPixels` 路径逐字同构。
 */
expect fun decodeImagePixels(bytes: ByteArray): ImagePixels?

/**
 * 全尺寸解码一张**可绘制**的图 —— 场地滑块验证码的画面用（背景与滑块都带 alpha/原色，
 * 不能走 [decodeImage] 的 `RGB_565` 降采样路径）。Android 实现就是搬迁前
 * `SliderCaptchaView` 里那段 `BitmapFactory.decodeByteArray`（ARGB_8888 全尺寸）。
 */
expect fun decodeImageFull(bytes: ByteArray): ImageBitmap?
