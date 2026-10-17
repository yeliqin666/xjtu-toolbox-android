package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable

/** Web（Kotlin/Wasm）端没有原生系统栏，空实现。 */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
    // 浏览器端无需处理。
}
