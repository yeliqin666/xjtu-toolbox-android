package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 页面底部的二级入口卡：不常用、不值得占首页格子的功能挂在相关页面的末尾。 */
@Composable
fun SecondaryEntry(title: String, summary: String, tag: String? = null, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            tag?.let {
                val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
