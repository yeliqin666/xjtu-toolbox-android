package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** jvm / 桌面端：空实现。 */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    // 桌面端无需处理。
}
