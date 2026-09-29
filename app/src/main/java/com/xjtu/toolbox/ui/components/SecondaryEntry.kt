package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 页面里的二级入口卡：不常用、不值得占首页格子的功能挂在相关页面上部。
 * 左边是带色底的图标，中间标题加一行说明，右边是状态（[highlight] 时用主题色胶囊强调）和箭头。
 */
@Composable
fun SecondaryEntry(
    icon: ImageVector,
    color: Color,
    title: String,
    summary: String,
    status: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            status?.let {
                Spacer(Modifier.width(8.dp))
                val accent = MiuixTheme.colorScheme.primary
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = if (highlight) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = if (highlight) Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp) else Modifier,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
}
