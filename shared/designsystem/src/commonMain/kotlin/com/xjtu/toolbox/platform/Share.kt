package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 平台能力：系统分享（把「标题 + 链接」交给系统分享面板）。 */
@Composable
expect fun rememberShareLink(): (title: String, url: String) -> Unit
