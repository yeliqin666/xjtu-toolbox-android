package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * jvm / 桌面端没有系统分享面板，空实现（与 [rememberPhoneDialer] 同一条降级策略）。
 */
@Composable
actual fun rememberShareLink(): (title: String, url: String) -> Unit = { _, _ -> }
