package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 桌面不截窗口：菜单面板保持不透明。 */
@Composable
actual fun rememberWindowSnapshot(capture: Boolean): WindowSnapshot? = null
