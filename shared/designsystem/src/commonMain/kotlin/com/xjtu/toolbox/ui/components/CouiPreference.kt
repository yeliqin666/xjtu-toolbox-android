package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 设置项的规格，照 COUI demo 的 `CouiPageDefaults` 抄（见 COUI-Kit 的
 * `coui/.../layout/CouiPageComponents.kt`）：
 *
 * - 卡片：24dp 平滑圆角，卡内条目之间 1px 分割线、两端各缩进 16dp；
 * - 行：最小高度 48dp，左右 16dp / 上下 10dp 内边距；
 * - 左侧图标区 24dp，与文字留 16dp；
 * - 标题 16sp / Medium(500)，摘要 13sp / Normal，右侧值 14sp；
 * - 分割线 1px，颜色 `onSurface` 12%（COUI 用 outlineVariant，这里跟着 Miuix 主题取同一条灰）。
 *
 * 之前用的是 MIUIX 的 `ArrowPreference` / `SwitchPreference`（行高 52dp、图标 32dp、
 * 字号也更大），这里换成同一套尺寸。
 */
object CouiPreferenceSpec {
    val cardCorner = 24.dp
    val cardHorizontalMargin = 16.dp
    val cardVerticalMargin = 4.dp
    val rowMinHeight = 60.dp
    val rowPaddingHorizontal = 16.dp
    val rowPaddingVertical = 10.dp
    val iconSize = 24.dp
    val iconSpacing = 16.dp
    val summarySpacing = 2.dp
    val titleSize = 16.sp
    val summarySize = 13.sp
    val valueSize = 14.sp
    /** 分割线从文字起点开始：左边距 + 图标 + 图标与文字的间距，不贯穿到图标下方。 */
    val dividerStartInset = rowPaddingHorizontal + iconSize + iconSpacing
    val dividerEndInset = rowPaddingHorizontal
    val dividerThickness = 1f
    val valueMaxWidth = 162.dp
}

/**
 * 一组设置：圆角卡片，条目之间自动画 1px 分割线（第一项之前不画）。
 *
 * 分割线由自定义 [Layout] 在测量后按每条的实际高度落位，所以不管里面是哪种行
 * （下拉、开关、跳转、滑条、自定义内容）都对得齐，不需要调用方手动插 Divider。
 */
@Composable
fun CouiSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dividerColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val dividers = remember { mutableListOf<Float>() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = CouiPreferenceSpec.cardHorizontalMargin,
                vertical = CouiPreferenceSpec.cardVerticalMargin,
            )
            .clip(RoundedCornerShape(CouiPreferenceSpec.cardCorner))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .drawBehind {
                val start = CouiPreferenceSpec.dividerStartInset.toPx()
                val end = size.width - CouiPreferenceSpec.dividerEndInset.toPx()
                dividers.forEach { y ->
                    drawLine(
                        color = dividerColor,
                        start = Offset(start, y),
                        end = Offset(end, y),
                        strokeWidth = CouiPreferenceSpec.dividerThickness,
                    )
                }
            },
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        dividers.clear()
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            if (index > 0) dividers.add(y.toFloat())
            y += placeable.height
        }
        layout(constraints.maxWidth, y) {
            var top = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, top)
                top += placeable.height
            }
        }
    }
}

/**
 * 一条设置项。标题 + 可选摘要 + 可选左侧图标 + 右侧内容（值 / 箭头 / 开关 / 自定义）。
 * 给了 [onClick] 才有整行点击与按压反馈。
 */
@Composable
fun CouiPreferenceItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: (@Composable () -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CouiPreferenceSpec.rowMinHeight)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = SinkFeedback(),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = CouiPreferenceSpec.rowPaddingHorizontal,
                vertical = CouiPreferenceSpec.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) icon()
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CouiPreferenceSpec.summarySpacing),
        ) {
            Text(
                text = title,
                fontSize = CouiPreferenceSpec.titleSize,
                fontWeight = FontWeight.Medium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    fontSize = CouiPreferenceSpec.summarySize,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                fontSize = CouiPreferenceSpec.valueSize,
                color = colors.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(start = CouiPreferenceSpec.iconSpacing)
                    .widthIn(max = CouiPreferenceSpec.valueMaxWidth),
            )
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
            )
        }
        if (trailing != null) {
            Box(Modifier.padding(start = CouiPreferenceSpec.iconSpacing)) { trailing() }
        }
    }
}

/** 跳转行：标题 + 可选摘要 + 可选图标 + 右侧箭头。 */
@Composable
fun ArrowPreference(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    CouiPreferenceItem(
        title = title,
        modifier = modifier,
        summary = summary,
        icon = startAction,
        showChevron = true,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 开关行：标题 + 可选摘要 + 可选图标 + 右侧 MIUIX 开关。 */
@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    CouiPreferenceItem(
        title = title,
        modifier = modifier,
        summary = summary,
        icon = startAction,
        enabled = enabled,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
            )
        },
    )
}

/** 设置行的前置图标：纯图标（不带圆底），24dp、直接填色，右侧留 16dp 到文字。 */
@Composable
fun CouiSettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(CouiPreferenceSpec.iconSize))
    Box(Modifier.size(CouiPreferenceSpec.iconSpacing))
}

/** 分组内的一条细分隔线：1px、从文字起点开始、右端与卡片内边距对齐（设置页和「我的」页共用）。 */
@Composable
fun CouiSectionDivider(
    startInset: androidx.compose.ui.unit.Dp = CouiPreferenceSpec.dividerStartInset,
    endInset: androidx.compose.ui.unit.Dp = CouiPreferenceSpec.dividerEndInset,
) {
    val color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    // 1 物理像素：和卡片里用 drawLine(strokeWidth = 1f) 画出来的那条一样粗。
    val thickness = with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset, end = endInset)
            .height(thickness)
            .background(color),
    )
}
/** 分组标题：12sp / Medium，左右 32dp、上下 8dp（对齐 COUI 的 `GroupTitle`）。 */
@Composable
fun SmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
