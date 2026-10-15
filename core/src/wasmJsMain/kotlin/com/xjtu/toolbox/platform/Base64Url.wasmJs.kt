package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**转调 `:core` commonMain 里那份纯 Kotlin 实现**（[UrlSafeBase64]）。
 *
 * 原先这一端编码给空串、解码给 null（`base64Available = false`），与 deflate 那条缝一起把浏览器的
 * 分享码整条链堵死。2026-10-15（D12 ②）两件一起补进 `commonMain`。
 *
 * 与 Android / jvm 那两份 actual（`java.util.Base64` 的 URL 编解码器）**逐字节同结果**：
 * 编码都不留 `=` 填充，解码都接受带填充与不带填充两种、都忽略末端多余的位
 *（`UrlSafeBase64` 的文件头有对照表，`UrlSafeBase64JvmTest` 逐条钉住）。
 */
actual fun base64UrlEncode(bytes: ByteArray): String = UrlSafeBase64.encode(bytes)

actual fun base64UrlDecode(text: String): ByteArray? = UrlSafeBase64.decode(text)

actual val base64Available: Boolean = true
