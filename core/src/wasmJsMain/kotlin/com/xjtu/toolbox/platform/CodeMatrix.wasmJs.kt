package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**如实返回 null** —— 这一端没有二维码 / 条形码编码器。
 *
 * zxing 只有 JVM/Android 那一份 jar（进不了 wasm），而手写一份 QR 编码器（GF(256) 的 RS 纠错、
 * 掩码评分、版本表）不是这一批要做的事。
 *
 * **TODO（Web 端的付款码）**：Web 那条路由 2026-10-14 起已经点亮（`AppRoute.PaymentCode`
 * 落的是共享屏 `:core` 的 `PaymentCodeScreen`），**但编码器仍然没有** —— 那一屏摇到的两个矩阵都是
 * null ⇒ 屏上只画付款码**数字**（那一档是纯文本，不依赖编码器），两个矩阵一个都不画。
 * 真要画码，这个 actual 要么换一个 wasm 可用的编码器，要么把「编码器」也收成 `:core` 的共享实现
 * （三端同一份）—— 不是画一张假的。
 */
actual fun qrCodeModules(text: String): CodeMatrix? = null

actual fun code128Modules(text: String): CodeMatrix? = null
