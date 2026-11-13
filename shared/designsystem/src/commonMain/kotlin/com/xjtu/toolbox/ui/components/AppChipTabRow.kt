package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一排单选分类胶囊，整行横向滚动、自带左右 16dp 和上下 8dp 留白。
 * Android 是 COUI `ChipTabRow`（玻璃材质、选中过渡、点到的项自动滚进视野）；其它端退回 [AppFilterChip]。
 */
@Composable
fun <K : Any> AppChipTabRow(
    options: List<K>,
    selected: K,
    onSelect: (K) -> Unit,
    label: (K) -> String,
    modifier: Modifier = Modifier,
    /** 胶囊底下的颜色（玻璃材质采它）。默认页面底色；放在卡片里传卡片色。 */
    containerColor: Color = Color.Unspecified,
    /** 所在容器的左右内边距：整行伸到容器边缘，滚动渐隐落在边缘上。 */
    bleed: Dp = 0.dp,
    /** 重复点已选中项是否也回调 [onSelect]（学辅排序靠它翻升 / 降序）。 */
    reselectable: Boolean = false,
) {
    if (options.isEmpty()) return
    PlatformChipTabRow(
        options = options,
        // 选中项不在列表里（数据刷新的间隙）时落到第一项
        selected = if (selected in options) selected else options.first(),
        onSelect = onSelect,
        label = label,
        containerColor = containerColor.takeOrElse { MiuixTheme.colorScheme.surface },
        modifier = modifier.fillMaxWidth().bleedHorizontal(bleed),
        reselectable = reselectable,
    )
}

private fun Modifier.bleedHorizontal(by: Dp): Modifier = if (by == 0.dp) this else layout { measurable, constraints ->
    val extra = by.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
internal expect fun <K : Any> PlatformChipTabRow(
    options: List<K>,
    selected: K,
    onSelect: (K) -> Unit,
    label: (K) -> String,
    containerColor: Color,
    modifier: Modifier,
    reselectable: Boolean,
)

/** 非 Android 端的同款排布。 */
@Composable
internal fun <K : Any> FilterChipRow(
    options: List<K>,
    selected: K,
    onSelect: (K) -> Unit,
    label: (K) -> String,
    modifier: Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            AppFilterChip(selected = option == selected, onClick = { onSelect(option) }, label = label(option))
        }
    }
}
