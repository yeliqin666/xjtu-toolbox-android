package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 桌面没有拨号盘：点号码就复制并提示。 */
@Composable
actual fun rememberPhoneDialer(): (String) -> Unit = rememberCopyText()
