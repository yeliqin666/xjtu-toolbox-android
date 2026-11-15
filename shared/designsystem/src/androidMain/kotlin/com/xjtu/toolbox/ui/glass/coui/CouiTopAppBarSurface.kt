package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** 顶栏表面录下的那层模糊；顶栏里的搜索、分类胶囊和圆钮都采它。 */
val LocalCouiToolbarBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }
internal val LocalCouiToolbarOverlay = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * 背景从标题栏顶部开始并向下延伸，不改变布局或触摸高度。
 * gradientSpeed 为 null 时保留项目固定渐隐；正值选择全高原厂幂曲线。
 * 只铺模糊，不再盖原厂那层 surface 色的竖向渐隐（顶栏发白就是它）；标题靠模糊后的底承字。
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
    SharedTransitionScope { overlayModifier ->
        Layout(
            modifier = modifier.then(overlayModifier),
            content = {
                Box(Modifier.couiBackdrop(destination)) {
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = backgroundAlpha.coerceIn(0f, 1f) }) {
                        Spacer(Modifier.matchParentSize().then(backgroundModifier))
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
