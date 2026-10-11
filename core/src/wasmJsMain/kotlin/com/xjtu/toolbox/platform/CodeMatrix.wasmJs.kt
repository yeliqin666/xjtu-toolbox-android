package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**如实返回 null** —— 这一端没有二维码 / 条形码编码器。
 *
 * zxing 只有 JVM/Android 那一份 jar（进不了 wasm），而手写一份 QR 编码器（GF(256) 的 RS 纠错、
 * 掩码评分、版本表）不是这一批要做的事。
 *
 * **TODO（Web 端的付款码）**：Web 那条路由还没点亮（`AppRoute.PaymentCode` 在 `:web` 仍落
 * `NotPortedScreen`）。真要接的时候，这个 actual 要么换一个 wasm 可用的编码器，要么把
 * 「编码器」也收成 `:core` 的共享实现（三端同一份）；在那之前屏上照常显示付款码**数字**
 * （那一档是纯文本，不依赖编码器），只是不画两个矩阵 —— 不是画一张假的。
 */
actual fun qrCodeModules(text: String): CodeMatrix? = null

actual fun code128Modules(text: String): CodeMatrix? = null
