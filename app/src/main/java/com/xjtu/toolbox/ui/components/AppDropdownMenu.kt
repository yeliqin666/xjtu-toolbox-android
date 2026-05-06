package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * Miuix 风格弹出菜单。
 * 圆角 + scaleIn/fadeIn 动画 + 半透明蒙版 + SinkFeedback 按压质感。
 * @param alignment 菜单弹出位置：TopStart(左上) / TopEnd(右上，默认)
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val popupAlignment = when (alignment) {
        Alignment.TopStart, Alignment.BottomStart -> PopupPositionProvider.Align.Start
        Alignment.TopEnd, Alignment.BottomEnd -> PopupPositionProvider.Align.End
        else -> PopupPositionProvider.Align.End
    }
    OverlayListPopup(
        show = expanded,
        popupModifier = modifier.widthIn(min = 180.dp, max = 300.dp),
        alignment = popupAlignment,
        onDismissRequest = onDismissRequest,
        maxHeight = 400.dp
    ) {
        ListPopupColumn {
            Column(
                modifier = Modifier.padding(vertical = offset.y.coerceAtLeast(0.dp)),
                content = content
            )
        }
    }
}

/**
 * 菜单项 — SinkFeedback 原生按压质感 + 圆角clip。
 */
@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = enabled
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            text()
        }
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            trailingIcon()
        }
    }
}
