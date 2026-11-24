package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** 桌面没有系统分享面板：复制链接并提示。 */
@Composable
actual fun rememberShareLink(): (title: String, url: String) -> Unit {
    val copy = rememberCopyText()
    return remember(copy) { { _, url -> copy(url) } }
}
