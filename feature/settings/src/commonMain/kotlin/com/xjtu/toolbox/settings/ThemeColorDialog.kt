package com.xjtu.toolbox.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.AppColorPicker
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自选主题色弹窗：浅色、深色两档分开设，按钮只决定「现在在改哪一档」。
 *
 * 两档颜色都按原样落到主题上（见 `XJTUToolBoxTheme` 的 `withExactPrimary`），
 * 所以这里预览的就是最终效果，不再拿种子色去推一档色阶。
 */
@Composable
internal fun ThemeColorDialog(
    initialLightHex: String,
    initialDarkHex: String,
    onDismiss: () -> Unit,
    onConfirm: (lightHex: String, darkHex: String) -> Unit,
) {
    // 先在 Composable 上下文中获取当前系统/App 的主题状态
    val isSystemDark = LocalIsDarkTheme.current
    // 默认先摊开用户此刻正在看的那一档
    var editingDark by remember { mutableStateOf(isSystemDark) }
    var lightHex by remember { mutableStateOf(initialLightHex.ifEmpty { DEFAULT_HEX }) }
    // 深色没单独选过就继承浅色那个，别一进弹窗就变成另一个色
    var darkHex by remember { mutableStateOf(initialDarkHex.ifEmpty { initialLightHex.ifEmpty { DEFAULT_HEX } }) }
    val hex = if (editingDark) darkHex else lightHex
    val color = CourseColors.parseHex(hex) ?: Color(0xFF3482FF)
    val setHex: (String) -> Unit = { value -> if (editingDark) darkHex = value else lightHex = value }
    val valid = CourseColors.parseHex(lightHex) != null && CourseColors.parseHex(darkHex) != null
    WindowDialog(
        show = true,
        title = "自定义主题色",
        summary = "浅色和深色分别设置，选中的颜色直接用作主题色",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilterChip(selected = !editingDark, onClick = { editingDark = false }, label = "浅色")
                AppFilterChip(selected = editingDark, onClick = { editingDark = true }, label = "深色")
            }
            // 色板和输入框只认当前这一档，切按钮就把它们换成那一档的颜色
            AppColorPicker(color = color, onColorChanged = { setHex(CourseColors.toHex(it)) })
            TextField(
                value = hex,
                onValueChange = setHex,
                label = if (editingDark) "深色 Hex 色值（#RRGGBB）" else "浅色 Hex 色值（#RRGGBB）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            XJTUToolBoxTheme(
                darkModeOverride = if (editingDark) "dark" else "light",
                customColorHex = lightHex,
                customDarkColorHex = darkHex,
                applySystemBars = false,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (editingDark) "深色预览" else "浅色预览", color = MiuixTheme.colorScheme.onSurface)
                    Box(
                        Modifier.fillMaxWidth().height(32.dp)
                            .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 主题色上的字只跟明暗走（深色白、浅色黑），挑的色再极端也不会自动换字色，
                        // 所以这一条就是最终效果的预演
                        Text("主题色上的文字", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "应用",
                    enabled = valid,
                    onClick = { onConfirm(lightHex, darkHex) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 没选过色时的起点，和旧弹窗一致。 */
private const val DEFAULT_HEX = "#3482FF"
