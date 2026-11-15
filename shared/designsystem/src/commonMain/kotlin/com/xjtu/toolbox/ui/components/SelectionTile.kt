package com.xjtu.toolbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/** 弹窗里一组选项的白卡片；弹窗底色要用 `surface`，卡片才分得出来。 */
@Composable
fun AppOptionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppCardColor),
        content = content,
    )
}

/** [AppOptionGroup] 里的一行多选：选中时主题色字和右侧小勾，长短不一的文字也排得齐。 */
@Composable
fun AppCheckRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val content by animateColorAsState(if (selected) primary else MiuixTheme.colorScheme.onSurface, tween(160), label = "rowInk")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = content,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(selected, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Icon(Icons.Filled.Check, contentDescription = "已选", tint = primary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

/**
 * 底部弹窗里的多选块：整块可点。未选是一层很淡的底；选中换成主题色淡底、主题色字和右侧小勾，
 * 颜色渐变过去。空闲教室选楼、通知来源、消息收纳选类别共用。
 */
@Composable
fun SelectionTile(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val primary = MiuixTheme.colorScheme.primary
    val ink = MiuixTheme.colorScheme.onSurface
    val container by animateColorAsState(
        if (selected) primary.copy(alpha = 0.12f) else ink.copy(alpha = 0.045f), tween(160), label = "tileBg",
    )
    val edge by animateColorAsState(
        if (selected) primary.copy(alpha = 0.45f) else Color.Transparent, tween(160), label = "tileEdge",
    )
    val content by animateColorAsState(if (selected) primary else ink, tween(160), label = "tileInk")
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(container)
            .border(0.8.dp, edge, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = content,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Row {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Check, contentDescription = "已选", tint = primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
