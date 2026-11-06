package com.xjtu.toolbox.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface

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

/** 设置行的前置图标：32dp 淡色圆底 + 18dp 图标 + 12dp 间隔。 */
@Composable
fun SettingsIcon(icon: ImageVector, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f), modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}
