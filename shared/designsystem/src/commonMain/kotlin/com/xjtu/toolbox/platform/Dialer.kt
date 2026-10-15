package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 平台能力：拨号（把号码交给系统拨号盘）。 */
@Composable
expect fun rememberPhoneDialer(): (String) -> Unit
