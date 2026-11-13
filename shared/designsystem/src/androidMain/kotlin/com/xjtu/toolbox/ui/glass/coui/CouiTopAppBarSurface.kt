package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** 顶栏表面录下的那层模糊/渐隐；顶栏里的搜索、分类胶囊和圆钮都采它。 */
val LocalCouiToolbarBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }
internal val LocalCouiToolbarOverlay = staticCompositionLocalOf<SharedTransitionScope?> { null }

private const val FadeStopCount = 65

/**
 * 背景从标题栏顶部开始并向下延伸，不改变布局或触摸高度。
 * gradientSpeed 为 null 时保留项目固定渐隐；正值选择全高原厂幂曲线。
 * gradientColor 未指定时使用浅色白色、深色黑色渐变；Transparent 关闭遮罩，显式 alpha 与预设透明度相乘。
 * topExtension 在测量时读取，背景向上延伸并平移，不改变内容占位或触摸范围。
 */
@Composable
fun CouiTopAppBarSurface(
    modifier: Modifier = Modifier,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiBackdrop.current) {
        "顶栏需要显式 backdrop 或 ProvideCouiBackdrop 作用域"
    },
    fadeHeight: androidx.compose.ui.unit.Dp = CouiTopAppBarDefaults.fadeHeight,
    referenceBlurRadius: androidx.compose.ui.unit.Dp = CouiTopAppBarDefaults.referenceBlurRadius,
    bottomExtension: androidx.compose.ui.unit.Dp = CouiTopAppBarDefaults.bottomExtension,
    backgroundAlpha: Float = 1f,
    gradientSpeed: Float? = null,
    gradientColor: Color = Color.Unspecified,
    dark: Boolean = CouiTheme.isDark,
    topExtension: () -> androidx.compose.ui.unit.Dp = { 0.dp },
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val destination = rememberCouiBackdrop(parent = backdrop)
    val backgroundModifier = Modifier.drawCouiGradientBackdrop(
        backdrop = backdrop,
        fadeHeightPx = fadeHeight.value * density,
        referenceBlurRadiusPx = referenceBlurRadius.value * density,
        gradientSpeed = gradientSpeed,
    )
    val resolvedGradientColor = gradientColor.takeOrElse { if (dark) Color.Black else Color.White }
    val gradient = remember(resolvedGradientColor, dark) {
        // 扩展色彩编码保留渐变透明度精度，避免每个节点先量化为 8 位。
        val color = resolvedGradientColor.convert(ColorSpaces.ExtendedSrgb)
        Brush.verticalGradient(List(FadeStopCount) { index ->
            color.copy(alpha = color.alpha * topBarGradientAlpha(index / (FadeStopCount - 1f), dark))
        })
    }
    SharedTransitionScope { overlayModifier ->
        Layout(
            modifier = modifier.then(overlayModifier),
            content = {
                Box(Modifier.couiBackdrop(destination)) {
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = backgroundAlpha.coerceIn(0f, 1f) }) {
                        Spacer(Modifier.matchParentSize().then(backgroundModifier))
                        if (resolvedGradientColor.alpha > 0f) Spacer(Modifier.matchParentSize().background(gradient))
                    }
                }
                Box {
                    CompositionLocalProvider(
                        LocalCouiToolbarBackdrop provides destination,
                        LocalCouiToolbarOverlay provides this@SharedTransitionScope,
                        content = content,
                    )
                }
            },
        ) { measurables, constraints ->
            val topBar = measurables[1].measure(constraints.copy(minHeight = 0))
            val topExtensionPx = topExtension().roundToPx()
            val background = measurables[0].measure(
                Constraints.fixed(
                    width = topBar.width,
                    height = topBar.height + topExtensionPx + bottomExtension.roundToPx(),
                )
            )
            // 折叠时固定高度的背景随顶栏上移，渐进曲线不随当前栏高重新拉伸。
            layout(topBar.width, topBar.height) {
                background.placeRelative(0, -topExtensionPx)
                topBar.placeRelative(0, 0)
            }
        }
    }
}

private fun topBarGradientAlpha(position: Float, dark: Boolean): Float {
    if (dark) return .4f * (1f - position * position * (3f - 2f * position))
    // 保留浅色 .6/.7 节点；两段 Hermite 共用连接斜率，底端以零斜率淡出。
    val first = position <= .6f
    val span = if (first) .6f else .4f
    val t = if (first) position / span else (position - .6f) / span
    val start = if (first) 1f else .7f
    val end = if (first) .7f else 0f
    val startSlope = if (first) -.5f else -.8f
    val endSlope = if (first) -.8f else 0f
    val smooth = t * t * (3f - 2f * t)
    return (start * (1f - smooth) + end * smooth +
        span * t * (1f - t) * (startSlope * (1f - t) - endSlope * t)).coerceIn(0f, 1f)
}
