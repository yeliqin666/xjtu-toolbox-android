package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.kyant.backdrop.Backdrop
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面级分段选择器：COUI 的胶囊分段控件（Android 上是 COUI `SegmentedControl` 的移植）。
 *
 * 外观是「一枚圆角轨道 + 底下白色滑块」：滑块跟着选中项滑、拖过新项就切，宽度按标签自然宽度分配、
 * 放不下时截断长标签；`compact` 用 32dp / 2dp 的紧凑预设（原版 `small`）。
 * 其它平台没有 AGSL，退回同样式样的普通绘制版本。
 *
 * 名字和参数沿用旧调用点：[embedded]、[backdrop] 是滑块时代的排版 / 采样参数，新控件不需要。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppSegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    backdrop: Backdrop? = null,
    compact: Boolean = false,
    /** 已选中的那一格再点是否也回调 [onTabSelected]（原版不回调；日程「第 N 周」靠它弹选周菜单）。 */
    reselectable: Boolean = false,
) {
    if (tabs.isEmpty()) return
    // 和页面卡片的外边距对齐：轨道左右各让 16dp，正好压在下方卡片的边缘上；
    // embedded（已经在卡片里）收窄到 4dp，compact（壁纸编辑器的表格）由外层自己给边距。
    val inset = when {
        compact -> 0.dp
        embedded -> 4.dp
        else -> 16.dp
    }
    PlatformSegmentedTabs(
        labels = tabs,
        selectedIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex),
        onSelected = onTabSelected,
        modifier = modifier.padding(horizontal = inset).fillMaxWidth(),
        small = compact,
        reselectable = reselectable,
    )
}

@Composable
internal expect fun PlatformSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    small: Boolean,
    reselectable: Boolean,
)

/** 非 Android 端的同款样式：普通填色轨道 + 白色滑块，点击切换。 */
@Composable
internal fun SegmentedTabsFallback(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
    small: Boolean,
    reselectable: Boolean,
) {
    if (labels.isEmpty()) return
    val dark = LocalIsDarkTheme.current
    val height = if (small) 32.dp else 40.dp
    val inset = if (small) 2.dp else 4.dp
    val track = if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .06f)
    val thumb = if (dark) Color.White.copy(alpha = .2f) else Color.White.copy(alpha = .72f)
    val shape = RoundedCornerShape(percent = 50)
    val progress by animateFloatAsState(
        selectedIndex.toFloat(),
        tween(240),
        label = "segmentedFallback",
    )
    BoxWithConstraints(
        modifier.height(height).clip(shape).background(track).drawBehind {
            val px = inset.toPx()
            val cell = (size.width - px * 2f) / labels.size
            drawRoundRect(
                color = thumb,
                topLeft = Offset(px + cell * progress, px),
                size = Size(cell, size.height - px * 2f),
                cornerRadius = CornerRadius((size.height - px * 2f) / 2f),
            )
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = inset)) {
            labels.forEachIndexed { index, label ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = interaction, indication = null) { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = if (index == selectedIndex) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
