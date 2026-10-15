package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable

/** jvm / 桌面端：空实现。 */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
    // 桌面端无需处理：窗口标题栏/系统栏不受主题影响，UI 也无需降级。
}
