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
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/** MIUI 风格 FilterChip — 选中时柔和高亮底色（无硬边框），SinkFeedback 按压 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    unselectedContainerColor: Color = MiuixTheme.colorScheme.surfaceVariant
) {
    val bgColor = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else unselectedContainerColor
    val textColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary

    Box(
        modifier = modifier
            .squircleSurface(color = bgColor, cornerRadius = 20.dp)
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
                style = MiuixTheme.textStyles.footnote1,
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
