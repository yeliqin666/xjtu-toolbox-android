package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable

/** jvm / 桌面端：不做触感（桌面窗口没有振动器）。 */
@Composable
actual fun rememberHaptics(): HapticsController = NoopHapticsController

private object NoopHapticsController : HapticsController {
    override fun tick() {}
    override fun lowTick() {}
    override fun success() {}
    override fun error() {}
}
