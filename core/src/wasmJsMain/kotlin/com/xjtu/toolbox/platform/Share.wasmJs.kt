package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * Web（Kotlin/Wasm）端：空实现。
 *
 * 浏览器有 `navigator.share`（且只在部分浏览器、且要求用户手势），要接的时候只改这一个文件：
 * `window.navigator.asDynamic().share(...)`，调用点不动。现在不接 —— 半可用的分享按钮比
 * 没有按钮更让人困惑，等 :web 真需要再说。
 */
@Composable
actual fun rememberShareLink(): (title: String, url: String) -> Unit = { _, _ -> }
