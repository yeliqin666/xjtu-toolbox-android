package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 顶栏「问小岱」按钮：点了会跳到小岱并替用户发出一条消息，所以先确认一次。
 * [summary] 说清会发什么，例如「把这项作业的标题和说明发给小岱」。
 */
@Composable
fun AskAgentAction(summary: String, onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    IconButton(onClick = { confirming = true }) {
        Icon(Icons.Default.SmartToy, contentDescription = "问小岱")
    }
    WindowDialog(
        show = confirming,
        title = "问小岱",
        summary = "$summary，并打开小岱对话。",
        onDismissRequest = { confirming = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = { confirming = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = "发送",
                onClick = { confirming = false; onConfirm() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
