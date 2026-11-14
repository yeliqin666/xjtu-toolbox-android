package com.xjtu.toolbox.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon

/** 设置页图标的低饱和多彩调色板；各端各页共用，不跟主题色走。 */
object SettingsColors {
    val Purple = Color(0xFF7E57C2)
    val Blue = Color(0xFF42A5F5)
    val Teal = Color(0xFF26A69A)
    val Indigo = Color(0xFF5C6BC0)
    val BlueGray = Color(0xFF78909C)
    val Green = Color(0xFF66BB6A)
    val Orange = Color(0xFFFFA726)
    val Pink = Color(0xFFEC407A)
    val Lime = Color(0xFF9CCC65)
}

/**
 * 设置行的前置图标：**纯图标**，不带圆底，直接填调色板里的颜色。
 *
 * 尺寸对齐 COUI 设置项的 24dp 图标槽（图标本身 24dp，右侧留 16dp 到文字）。
 */
@Composable
fun SettingsIcon(icon: ImageVector, color: Color) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
    Spacer(Modifier.width(16.dp))
}
