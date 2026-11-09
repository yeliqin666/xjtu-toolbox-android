package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置里的下拉单选行：和 MIUIX `OverlayDropdownPreference` 同一套参数与行样式，
 * 弹出的是全 App 统一的 [AppDropdownMenu]（从右侧当前值处展开，单行按压留白边）。
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
    val muted = MiuixTheme.colorScheme.onSurfaceVariantActions
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = {
            // 菜单挂在当前值这一格上：从这里长出来
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
        },
        onClick = { open = true },
        holdDownState = open,
        enabled = enabled,
    )
}
