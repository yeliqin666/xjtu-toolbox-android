package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable

/** Web（Kotlin/Wasm）端：暂不做触感。 */
@Composable
actual fun rememberHaptics(): HapticsController = NoopWebHapticsController

private object NoopWebHapticsController : HapticsController {
    override fun tick() {}
    override fun lowTick() {}
    override fun success() {}
    override fun error() {}
}
