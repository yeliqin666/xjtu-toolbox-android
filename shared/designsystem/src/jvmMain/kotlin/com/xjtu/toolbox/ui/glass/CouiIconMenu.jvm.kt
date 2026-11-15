package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.AppDropdownSubMenu
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * JVM 桌面端没有 COUI 材质：菜单退回仓库原有的 `AppDropdownMenu`，版式与 COUI 那边对齐
 * （选中项主题色加粗 + 勾、禁用项变淡、子菜单带 summary）。触发按钮还是 [CouiToolbarActionButton]。
 */
@Composable
actual fun CouiIconMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    content: CouiMenuScope.() -> Unit,
) {
    val entries = rememberCouiMenuEntries(content)
    Box(modifier) {
        CouiToolbarActionButton(onClick = { onExpandedChange(true) }) { icon() }
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            CouiFallbackEntries(entries) { onExpandedChange(false) }
        }
    }
}

/** DSL 条目 → 旧的 `AppDropdownMenu` 内容，行为与 AppIconMenu 时代一致。 */
@Composable
private fun CouiFallbackEntries(entries: List<CouiMenuEntry>, onDismiss: () -> Unit) {
    entries.forEach { entry ->
        when (entry) {
            is CouiMenuEntry.Option -> {
                val colors = MiuixTheme.colorScheme
                val color = if (entry.selected) colors.primary else colors.onSurface
                val leadingIcon: (@Composable () -> Unit)? = entry.icon?.let { imageVector ->
                    {
                        Icon(
                            imageVector = imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = color,
                        )
                    }
                }
                AppDropdownMenuItem(
                    text = {
                        Text(
                            entry.text,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = color,
                        )
                    },
                    onClick = {
                        onDismiss()
                        entry.onClick()
                    },
                    leadingIcon = leadingIcon,
                    // 选中态改用勾：和 COUI 侧 selected 的语义一致
                    trailingIcon = if (entry.selected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "已选",
                                tint = color,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    enabled = entry.enabled,
                )
            }

            is CouiMenuEntry.Submenu -> {
                val leadingIcon: (@Composable () -> Unit)? = entry.icon?.let { imageVector ->
                    {
                        Icon(
                            imageVector = imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                AppDropdownSubMenu(
                    title = entry.title,
                    summary = entry.summary,
                    leadingIcon = leadingIcon,
                ) {
                    CouiFallbackEntries(entry.children, onDismiss)
                }
            }

            CouiMenuEntry.Divider -> Spacer(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            )
        }
    }
}
