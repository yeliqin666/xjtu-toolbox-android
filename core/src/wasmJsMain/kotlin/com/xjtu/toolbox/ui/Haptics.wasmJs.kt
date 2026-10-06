package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable

/**
 * Web（Kotlin/Wasm）端：暂不做触感。
 *
 * 浏览器确实有 `navigator.vibrate()`，但只有 Android Chrome 等少数实现，且需要用户手势授权；
 * 为它再引一层 js() 互操作不划算，先退化成空实现。等 Web 端真要上线触感时，
 * 只改这一个 actual，`HapticsController` 的调用点一行不动。
 */
@Composable
actual fun rememberHaptics(): HapticsController = NoopWebHapticsController

private object NoopWebHapticsController : HapticsController {
    override fun tick() {}
    override fun lowTick() {}
    override fun success() {}
    override fun error() {}
}
