package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable

/**
 * jvm / 桌面端：不做触感（桌面窗口没有振动器）。
 *
 * 刻意**不**返回 null 或抛异常：调用方（8 个屏幕、`HapticsController` 的 4 个语义方法）
 * 一行都不用改，行为退化成「什么都不发生」，UI 也无需降级。
 */
@Composable
actual fun rememberHaptics(): HapticsController = NoopHapticsController

private object NoopHapticsController : HapticsController {
    override fun tick() {}
    override fun lowTick() {}
    override fun success() {}
    override fun error() {}
}
