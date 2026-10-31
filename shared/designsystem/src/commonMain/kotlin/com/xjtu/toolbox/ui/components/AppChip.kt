package com.xjtu.toolbox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 互斥单选用的胶囊，和屁岱卡片里的胶囊同一套样子。
 *
 * 选中是主题色实底（primary + onPrimary，自选色时就是用户挑的那个色）。tertiary 是 Monet
 * 转过色相的「第三色」，不跟主题色，不能用。
 * 未选中是一层浅的前景色，白卡片和灰页面上都看得出，不用描边。
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val cs = MiuixTheme.colorScheme
    val bgColor by animateColorAsState(if (selected) cs.primary else cs.onSurface.copy(alpha = 0.06f), tween(180), label = "chipBg")
    val textColor by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariantActions, tween(180), label = "chipFg")

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback()
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
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
                // 字重不随选中变：变粗会改宽度，一排胶囊跟着跳
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
