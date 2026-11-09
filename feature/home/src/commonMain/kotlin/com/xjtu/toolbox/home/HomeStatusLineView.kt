package com.xjtu.toolbox.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.MenuMorph
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

private val StatusGreen = Color(0xFF2E7D32)
private val StatusBlue = Color(0xFF1E88E5)

/** 问候语下面那行网络状态：点开就地展开各子系统的连接情况。 */
@Composable
internal fun HeroStatusLine(status: HomeStatusLine) {
    val colors = MiuixTheme.colorScheme
    val muted = colors.onSurfaceVariantSummary
    val tint = when (status.tone) {
        HomeTone.ACCENT -> StatusBlue
        HomeTone.ALERT -> colors.error
        HomeTone.NORMAL -> StatusGreen
        HomeTone.MUTED -> muted
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(
                enabled = status.subsystems.isNotEmpty(),
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = { open = true },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(5.dp))
            Text(
                status.label,
                style = MiuixTheme.textStyles.footnote2,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = "查看子系统连接状态", tint = muted, modifier = Modifier.size(13.dp))
        }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, morph = MenuMorph.FromRow) {
            status.subsystems.forEach { subsystem ->
                val rowTint = if (subsystem.ready) StatusGreen else muted
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (subsystem.ready) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                        contentDescription = null,
                        tint = rowTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(subsystem.label, style = MiuixTheme.textStyles.body2, color = colors.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Text(if (subsystem.ready) "已连接" else "未登录", style = MiuixTheme.textStyles.footnote1, color = rowTint, maxLines = 1)
                }
            }
        }
    }
}
