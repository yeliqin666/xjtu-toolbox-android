package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**如实返回 null** —— 这一端没有 deflate。
 *
 * 浏览器有 `CompressionStream('deflate')`，但它是流式异步的，而分享码这条路在共享层是同步链
 * （见 commonMain 的 KDoc）。把同步换成 suspend 会一路改到屏与调用点，而 `AppRoute.Match`
 * 在 `:web` 还没点亮 —— 不值得先付这笔账。
 *
 * **TODO（Web 端的匹配交友）**：见 commonMain `Deflate.kt` 文件头那两条路。在那之前
 * [deflateAvailable] 是 false，`:core` 的匹配屏据它**如实说这一端生成不了分享码**，
 * 而不是画一张空的 / 假的码。
 */
actual fun deflateBytes(raw: ByteArray): ByteArray? = null

actual fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray? = null

actual val deflateAvailable: Boolean = false
