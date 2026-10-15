package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * MIUI 风格 FilterChip —— 互斥单选用的胶囊。
 *
 * 视觉上对齐 miuix `TabRow` 的语言，而不是自己另造一套：
 * - 选中态用 `tertiaryContainer` / `onTertiaryContainer` 这对语义色（浅色下是淡蓝底 + 主题蓝字，
 *   深色下是深蓝底 + 亮蓝字），由主题保证对比度，不再用 `primary.copy(alpha)` 手工调透明度
 *   ——后者在深色背景上会糊成一团。
 * - 未选中态给一圈 `outline` 描边而非填色块，和 `TabRow` 的 `TabItem` 一致；这样一排胶囊在
 *   任何背景色上都立得住，不依赖父容器恰好是 surfaceVariant。
 * - 圆角走 squircle，尺寸与 miuix 控件同一梯度；按压用 SinkFeedback。
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val bgColor = if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent
    val textColor =
        if (selected) MiuixTheme.colorScheme.onTertiaryContainer
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val outlineColor = MiuixTheme.colorScheme.outline
    val cornerRadius = 11.dp

    Box(
        modifier = modifier
            .squircleSurface(color = bgColor, cornerRadius = cornerRadius)
            // 未选中时用描边勾轮廓，选中时靠底色，二者不叠加
            .squircleBorder(
                width = { if (selected) 0.dp else 1.dp },
                color = { outlineColor },
                cornerRadius = cornerRadius
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback()
            ) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
        }
    }
}

/** MIUI 风格 SuggestionChip — 无选中态，柔和背景 + SinkFeedback 按压 */
@Composable
fun AppSuggestionChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelContent: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 16.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback()
            ) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(4.dp))
            }
            if (labelContent != null) labelContent()
            else if (label != null) Text(label, style = MiuixTheme.textStyles.footnote1)
        }
    }
}
