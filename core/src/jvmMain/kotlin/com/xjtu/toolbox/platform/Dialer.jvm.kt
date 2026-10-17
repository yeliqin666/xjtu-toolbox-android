package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** jvm / 桌面端没有系统拨号盘，空实现。 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit = {}
