package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * Web（Kotlin/Wasm）端：空实现。
 *
 * 浏览器有 Screen Wake Lock API（需用户手势与页面可见性配合），要做时只改这一个 actual，
 * 调用点不动。当前退化成「不常亮」，不影响功能。
 */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    // 浏览器端暂不处理。
}
