package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 首页头卡的「森林」背景：useplanes 登录页左侧那张拉丝绿地原图，满幅裁切、始终彩色。
 * 不做任何交互置灰，也不开放颜色或其他定制。
 */
@Composable
expect fun HeroForestBackground(modifier: Modifier = Modifier)

/** 森林背景上承托白字的暗色罩：顶部略压、底部加重，和站点左栏的暗色渐隐同一路子。 */
val HeroForestScrim: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.30f),
    0.45f to Color.Black.copy(alpha = 0.18f),
    1f to Color.Black.copy(alpha = 0.52f),
)
