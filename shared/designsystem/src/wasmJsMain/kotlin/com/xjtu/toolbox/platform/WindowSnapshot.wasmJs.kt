package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/** 浏览器里不截画布：菜单面板保持不透明。 */
@Composable
actual fun rememberWindowSnapshot(capture: Boolean): WindowSnapshot? = null
