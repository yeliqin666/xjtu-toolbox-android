package com.xjtu.toolbox.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 顶栏「问小岱」按钮：点了打开小岱新对话，问题填进输入框、这页的内容挂成「附带」标签，
 * 由用户自己改了再发，所以不用先确认。[summary] 说清会带上什么，给读屏用。
 */
@Composable
fun AskAgentAction(summary: String, onConfirm: () -> Unit) {
    IconButton(onClick = onConfirm) {
        Icon(Icons.Default.SmartToy, contentDescription = "问小岱：$summary")
    }
}
