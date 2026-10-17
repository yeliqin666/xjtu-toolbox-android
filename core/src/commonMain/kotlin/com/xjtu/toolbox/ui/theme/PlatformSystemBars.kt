package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable

/** 平台能力：系统栏外观（状态栏 / 导航栏图标随主题明暗）。 */
@Composable
expect fun PlatformSystemBars(darkTheme: Boolean)
