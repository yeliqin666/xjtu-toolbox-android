package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable

/**
 * Web（Kotlin/Wasm）端没有原生系统栏，空实现。
 *
 * 浏览器的地址栏颜色由 `<meta name="theme-color">` 控制，属于宿主页面的职责，
 * 共享 UI 层不管；将来要给 Web 加也能在这一层补，不影响调用方。
 */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
    // 浏览器端无需处理。
}
