package com.xjtu.toolbox.platform

import com.xjtu.toolbox.platform.qr.QrReader

/**
 * Web（wasmJs）侧的二维码解码：**转调 `:core` commonMain 里那份纯 Kotlin 解码链**。
 *
 * zxing（`HybridBinarizer` / `QRCodeReader`）只有 JVM/Android 那一份 jar，进不了 wasmJs；而浏览器
 * 这一端要「从选的一张图 / 相机的一帧里认出对方的分享码」⇒ 解码链按 D12 ① 编码器同一套做法搬进了
 * commonMain（`platform/qr/`，见 [QrReader] 的 KDoc：整条链的八步与三处如实缺口）。
 *
 * | | 这一端 |
 * |---|---|
 * | 灰度 | `(r*299 + g*587 + b*114) / 1000`（与 jvm/android 两份 actual 逐字相同） |
 * | 二值化 / 定位 / 透视 / 纠错 | `platform/qr/` 那一族（逐行对照 zxing） |
 * | 与 Android 的关系 | **同一批像素解出同一段文本**：`:core:jvmTest` 的 `QrDecodeParityJvmTest` 拿同一批 ARGB 同时跑这一份与 jvm 那份 actual 逐字断言；jvmMain / androidMain 两份 actual 用的仍是 zxing（红线：Android 行为逐字不变） |
 *
 * ⚠️ 两处如实缺口见 `QrDecode.kt` 的 KDoc（「补定位块」没搬、Shift_JIS/GB2312 没有码表）——
 * 都是返回 `null`，不是读错。
 */
actual fun qrDecodeArgb(pixels: IntArray, width: Int, height: Int): String? =
    QrReader.decodeArgb(pixels, width, height)
