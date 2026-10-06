package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * jvm / 桌面端没有系统拨号盘（也不该由共享 UI 去调外部电话程序），空实现。
 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit = {}
