package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 页面层级用色，解决"灰卡片踩灰背景"。
 *
 * miuix 的 Scaffold 默认背景是 `surface`（浅 #F7F7F7 / 深 纯黑），所以：
 * - 卡片**不能**用 `surface`——那正是背景色，卡片会整个消失（考勤流水卡就是这么没的）；
 * - 也不该用 `secondaryContainer.copy(alpha=...)`：浅色下 #F0F0F0 半透明压在 #F7F7F7 上
 *   几乎无差，深色下 #434343 半透明又发灰，这是思源学堂"劣质网页感"的来源。
 *
 * 正确的层级是：背景 `surface` → 卡片 [AppCardColor] → 卡内嵌套块 [AppInsetColor]。
 * `surfaceVariant` 在浅色是纯白、深色是 #242424，与背景两端都拉得开。
 */
val AppCardColor: Color
    @Composable get() = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceVariant

/** 卡片内部再嵌一层时用（比卡片略重），例如统计块、内嵌列表行。 */
val AppInsetColor: Color
    @Composable get() = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)

/**
 * 给 [AppCardColor] 卡片补的真实投影（PR W，计划 §14.1）。
 *
 * 卡片层级本来完全靠"卡片色比背景色亮一点"撑着，深色模式下两者差很小，几乎看不出卡片
 * 边界；投影负责把这层级找补回来。浅色模式背景本身够亮，投影调得很淡，避免过了头变成
 * "安卓味"——miuix 的设计语言偏克制，宁可投影浅到看不出，也不要重。
 *
 * 用 [Modifier.dropShadow]（ui 1.12.0 起可用，已用 javap 核对过签名）而不是旧的
 * `Modifier.shadow`，因为后者在部分机型上会带一圈不自然的灰边。
 *
 * 目前只接进了 [ExpressivePanel] 这一个已有的通用卡片组件；应用里大多数卡片是各页面直接
 * 调 `top.yukonga.miuix.kmp.basic.Card(colors = CardDefaults.defaultColors(color =
 * AppCardColor))`（散在十几个非热点文件里，没有统一的卡片组件），逐个接入不属于"组件层
 * 统一加"，按计划 §0.2 第 9/10 条不在本 PR 里做，见收尾报告。
 *
 * @param shape 要和卡片本身一致的形状，否则投影和卡片轮廓对不上。
 * @param strong 长列表里稀疏、重要的大卡片用 true（投影略重一点）；列表项这种密集小卡片
 *   用默认的 false，避免滚动时大量投影拖垮帧率。
 */
@Composable
fun Modifier.appCardShadow(
    shape: Shape = RoundedCornerShape(16.dp),
    strong: Boolean = false,
): Modifier {
    val isDark = LocalIsDarkTheme.current
    val tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
    val radiusDp = if (strong) 16.dp else 10.dp
    val offsetYDp = if (strong) 6.dp else 3.dp
    val alpha = when {
        isDark && strong -> 0.28f
        isDark -> 0.20f
        strong -> 0.10f
        else -> 0.06f
    }
    return this.dropShadow(shape) {
        // ShadowScope 继承自 Density：radius/offset 要的是像素 Float，不是 Dp，这里手动转一下。
        this.radius = radiusDp.toPx()
        this.color = tint
        this.alpha = alpha
        this.offset = androidx.compose.ui.geometry.Offset(0f, offsetYDp.toPx())
    }
}

@Composable
fun ExpressiveIcon(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    iconSize: Dp = 27.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .squircleSurface(
                color = color.copy(alpha = 0.16f),
                cornerRadius = size * 0.31f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 浅色下这团白光融进浅底，只是提亮；深色底上它会变成字形背后一圈灰白光环，所以只在浅色画。
        if (!LocalIsDarkTheme.current) {
            Box(
                Modifier
                    .size(size * 0.7f)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                        ),
                    ),
            )
        }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ExpressivePanel(
    modifier: Modifier = Modifier,
    accent: Color,
    cornerRadius: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .appCardShadow(shape = RoundedCornerShape(cornerRadius))
            .squircleSurface(
                color = accent.copy(alpha = 0.08f),
                cornerRadius = cornerRadius,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = SinkFeedback(),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}
