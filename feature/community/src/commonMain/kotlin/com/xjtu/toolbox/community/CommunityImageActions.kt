package com.xjtu.toolbox.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 编辑器工具栏里的「图片」：贴一个图片链接，插成 Markdown 图片。 */
@Composable
internal fun CommunityImageActions(enabled: Boolean, onInsert: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { show = true },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(Icons.Outlined.Image, contentDescription = "插入图片",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
    }
    if (!show) return
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    WindowDialog(show = true, title = "插入图片", onDismissRequest = { show = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = url, onValueChange = { url = it; error = null }, label = "https:// 图片地址", singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = { show = false })
                TextButton(
                    text = "插入",
                    enabled = url.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        try { onInsert(communityImageMarkdown(url)); show = false }
                        catch (e: IllegalArgumentException) { error = e.message }
                    },
                )
            }
        }
    }
}
