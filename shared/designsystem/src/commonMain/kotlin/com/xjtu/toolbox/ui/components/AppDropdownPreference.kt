package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置里的下拉单选行：和 MIUIX `OverlayDropdownPreference` 同一套参数与行样式。
 * 点开时右侧的当前值淡出，菜单在这一行右侧展开（见 [MenuMorph.FromRow]）。
 */
@Composable
fun AppDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    // 当前值：菜单展开时淡掉，收起时等菜单缩回去才回来
    val valueAlpha by animateFloatAsState(
        if (open) 0f else 1f,
        if (open) tween(160) else tween(260, delayMillis = 160),
        label = "dropdownValue",
    )
    val muted = MiuixTheme.colorScheme.onSurfaceVariantActions
    // 菜单挂在整行的 Box 上：锚点就是这一行
    Box(modifier) {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = startAction,
            endActions = {
                Row(
                    Modifier.graphicsLayer { alpha = valueAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        items.getOrNull(selectedIndex).orEmpty(),
                        style = MiuixTheme.textStyles.body2,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                }
            },
            onClick = { open = true },
            holdDownState = open,
            enabled = enabled,
        )
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, morph = MenuMorph.FromRow) {
            items.forEachIndexed { index, item ->
                AppDropdownOption(
                    text = item,
                    selected = index == selectedIndex,
                    onClick = {
                        open = false
                        onSelectedIndexChange(index)
                    },
                )
            }
        }
    }
}
