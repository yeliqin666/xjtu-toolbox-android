package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * App 式图标：分类色的对角渐变铺满超椭圆，上半截一层柔和高光，字形用白色。
 * 不加投影：一屏二十几个图标，每个一层 dropShadow，滚动时帧率会掉。
 *
 * 从 :app 的 `home/HomeTab.kt` 搬进 commonMain：首页与游戏合集页都要用同一枚图标，
 * 搬屏之后不能只留 :app 那一份（跨模块同名同包会静默遮蔽）。
 */
@Composable
fun GradientAppIcon(
    icon: ImageVector,
    color: Color,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    val radius = size * 0.3f
    val top = lerp(color, Color.White, 0.22f)
    val bottom = lerp(color, Color.Black, 0.10f)
    Box(
        Modifier
            .size(size)
            .squircleClip(radius)
            .background(Brush.linearGradient(listOf(top, color, bottom)))
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                        endY = this.size.height * 0.55f,
                    )
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}
