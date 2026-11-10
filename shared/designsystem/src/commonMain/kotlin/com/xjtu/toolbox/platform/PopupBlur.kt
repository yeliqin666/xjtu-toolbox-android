package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 在弹层窗口里调用：[active] 时把弹层后面的页面轻轻糊上 [radius]。
 * 返回这一端是否真的糊得上；糊不上（系统不支持、桌面、Web）时调用方应保持面板不透明，
 * 否则半透明面板后面透出来的是清晰的字。
 */
@Composable
expect fun rememberPopupBlurBehind(radius: Dp, active: Boolean): Boolean
