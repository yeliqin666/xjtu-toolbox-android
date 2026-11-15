package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 嵌在标题行、列表底、卡片里的次要动作。
 *
 * miuix 的 [top.yukonga.miuix.kmp.basic.TextButton] 默认是一块 `secondaryVariant` 灰胶囊
 * （最小约 58×46、圆角 16）。放在「最近交易 / 全部」这种标题旁边，或紧贴一行说明，
 * 会像一块没设计过的砖。这里只留主色文字，需要时带一个小箭头；点按区域靠内边距撑开，不画底。
 *
 * 整页唯一的主操作仍用 `Button` + `buttonColorsPrimary()`。和主按钮并排的次要按钮用
 * [appTonalTextButtonColors]，不要退回默认灰胶囊。
 */
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chevron: Boolean = false,
    color: Color = MiuixTheme.colorScheme.primary,
) {
    val tint = if (enabled) color else color.copy(alpha = 0.38f)
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
        if (chevron) {
            Spacer(Modifier.width(1.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
