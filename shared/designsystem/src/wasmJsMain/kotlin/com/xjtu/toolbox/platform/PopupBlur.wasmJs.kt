package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** 浏览器里画在同一块画布上，没有跨窗口模糊。 */
@Composable
actual fun rememberPopupBlurBehind(radius: Dp, active: Boolean): Boolean = false
