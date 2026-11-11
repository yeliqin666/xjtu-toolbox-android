package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.pressScale
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 圆钮的视觉直径（和 COUI `TopAppBarButton` 一致）。 */
private val FallbackVisualSize = 40.dp

/** 圆钮的交互直径：视觉是 40dp，触摸区撑到 48dp，和 COUI 的最小交互尺寸一致。 */
private val FallbackTouchTargetSize = 48.dp

/** 禁用时内容的透明度，取自 COUI 原版（内容色乘 0.38）。 */
private const val FallbackDisabledContentAlpha = 0.38f

/**
 * 没有顶栏采样源时的顶栏动作按钮：半透明圆钮。
 *
 * 用于经典风格页面、还没换成 COUI 顶栏的 MIUIX 旧顶栏，以及非 Android 平台：
 * 这些地方拿不到 `LocalCouiToolbarBackdrop`，画不出 AGSL 材质，但按钮的尺寸、
 * 按压反馈和禁用态要和 COUI 版保持一致——
 * - 视觉 40dp 圆、交互区 48dp（外圈接点击，内圈跟手缩放）；
 * - [enabled] 为 false 时点击不触发，内容按 0.38 变淡。
 */
@Composable
internal fun CouiToolbarActionButtonFallback(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(FallbackTouchTargetSize)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(FallbackVisualSize)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) 0.52f else 0.6f))
                .pressScale(interaction, pressed = 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else FallbackDisabledContentAlpha }) {
                content()
            }
        }
    }
}
