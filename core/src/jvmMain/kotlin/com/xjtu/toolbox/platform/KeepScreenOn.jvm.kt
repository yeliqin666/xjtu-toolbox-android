package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * jvm / 桌面端没有「屏幕常亮」这个概念（窗口由系统电源策略管理），空实现。
 */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    // 桌面端无需处理。
}
