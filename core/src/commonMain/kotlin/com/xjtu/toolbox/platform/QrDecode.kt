package com.xjtu.toolbox.platform

/**
 * 平台能力：**把一帧 ARGB 像素里的二维码读成文本**（读不到返回 null，不抛）。
 *
 * ## 这是给谁用的
 *
 * 匹配交友那一屏有两档「怎么拿到对方的码」（见 `:core` 的 `social/MatchScanner`）：
 *  - **相机取景**：CMP 的相机 + 每帧取像素；
 *  - **从相册/文件选一张图**：图 → 像素 → 这里。
 * 两档的差别只在「像素从哪来」，解码是同一件事 —— 所以解码单独留这一条缝，两档共用。
 *
 * `:app` 那一端**没有走这条缝**（它有自己的 `qrlogin/QrFrameDecoder`，含「补定位块」那一档），
 * 这是 C1 红线（Android 行为逐字不变）。这一条缝服务的是 `:web`（与将来的桌面）。
 *
 * ## 参数与口径
 *
 * @param pixels `0xAARRGGBB`、行优先、长度至少 `width * height`（就是 Android `Bitmap.getPixels`
 *   那批 int 的语义，也是 `ImagePixels.pixels` 的语义）
 * @param width 每行像素数
 * @param height 行数
 * @return 码里的文本；图里没有码 / 码坏了 / 这一端解不了那种码（见下）一律 `null`
 *
 * **灰度换算与 `:app` 逐字相同**（`(r*299 + g*587 + b*114) / 1000`）—— 注意**不是** zxing
 * `RGBLuminanceSource` 的 `(r + 2g + b) / 4`。两端都用前者，判据是「同一批像素解出同一段文本」。
 *
 * ## 两端各是谁（别把这条缝读反了）
 *
 * | 源集 | 实现 | 用在哪 |
 * |---|---|---|
 * | jvmMain / androidMain | zxing 那条链（`PlanarYUVLuminanceSource` + `HybridBinarizer` + `QRCodeReader`，`TRY_HARDER` 开着） | 桌面 / serve 模式 / Android 的 JVM 测试 |
 * | wasmJsMain | `:core` commonMain 里那份**纯 Kotlin** 解码链（`platform/qr/QrReader`） | 浏览器 |
 *
 * 于是「浏览器解出来的码与 Android/jvm 是同一段文本」不能靠嘴说：`:core:jvmTest` 的
 * `QrDecodeParityJvmTest` 拿同一批像素同时跑这一份与那份纯 Kotlin 实现，逐字断言相等
 * （包括「图里没有码」时两边都返回 null）。
 *
 * ## 一处**如实**的缺口（见 `QrReader` 的 KDoc 与 `QrCharset` 的边界表）
 *
 * 1. 「补定位块」（`:app` 的 `decodeWithRepairedFinder`，为磨掉一角的码）**没搬** ⇒ 那种码在这一端
 *    可能读不出来；
 * （字符集那一档本来也是缺口：Shift_JIS / GB2312 那几套码表 commonMain 里没有。这一批已补齐 ——
 * KANJI 段、HANZI 段、ECI 20/28/29/30 与全部单字节那一族都解得出来了，剩下 ECI 12 / 16 两档
 * 「连 zxing 自己也不认」的，见 `QrCharset` 的边界表。）
 *
 * 两条都是「本端没这个能力」，不是「读错了」—— 返回 `null`，由调用方给用户一句实话。
 */
expect fun qrDecodeArgb(pixels: IntArray, width: Int, height: Int): String?
