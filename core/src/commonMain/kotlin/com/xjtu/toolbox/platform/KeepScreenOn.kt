package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 平台能力：屏幕常亮（把当前窗口标记为 keep-screen-on）。 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
