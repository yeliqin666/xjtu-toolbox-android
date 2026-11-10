package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** 桌面弹层是独立窗口，没有跨窗口模糊。 */
@Composable
actual fun rememberPopupBlurBehind(radius: Dp, active: Boolean): Boolean = false
