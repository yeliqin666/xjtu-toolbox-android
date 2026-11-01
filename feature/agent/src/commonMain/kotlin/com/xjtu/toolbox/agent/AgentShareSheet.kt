package com.xjtu.toolbox.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.rememberCopyText
import com.xjtu.toolbox.util.campusDateTimeOf
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
internal fun AgentShareSheet(
    store: AgentShareStore,
    host: AgentHost,
    canSend: Boolean,
    canSendCurrent: Boolean,
    supportsImages: Boolean,
    sendHint: String?,
    onSend: (AgentSharedMaterial, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var entries by remember(store) { mutableStateOf(store.all()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, AgentShareInbox.generation) {
        entries = store.all()
        listState.scrollToItem(0)
    }
    val copy = rememberCopyText()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    OverlayBottomSheet(show = true, title = "分享材料", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("仅存本机 · 保留 7 天，最多 30 条", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                        if (expanded) "收起预览" else "展开预览", modifier = Modifier.size(20.dp))
                }
            }
            sendHint?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = windowHeight * if (expanded) .75f else .45f), state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entries.isEmpty()) item { Text("从其他应用分享或拖入文字、图片，先在这里预览。") }
                items(entries, key = { it.id }) { item ->
                    var text by remember(item.id) { mutableStateOf(item.text) }
                    var confirmed by remember(item.id, text) { mutableStateOf(false) }
                    val hints = remember(text) { sharePrivacyHints(text) }
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(campusDateTimeOf(item.updatedAt).let { "${it.monthNumber}月${it.dayOfMonth}日 ${it.time.toString().take(5)}" },
                                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextField(value = text, onValueChange = { text = it.take(30_000) }, label = "预览，可编辑", maxLines = if (expanded) 12 else 5,
                                modifier = Modifier.fillMaxWidth())
                            if (item.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.images.forEach { AttachmentThumb(it, host, 52.dp) }
                            }
                            if (hints.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(state = if (confirmed) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                    onClick = { confirmed = !confirmed })
                                Text("含${hints.joinToString("、")}，确认可交给当前 AI 服务商", style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            }
                            if (item.images.isNotEmpty() && !supportsImages) Text("当前模型不支持图片，请先切换模型。", style = MiuixTheme.textStyles.footnote2)
                            val allowed = canSend && (text.isNotBlank() || item.images.isNotEmpty()) &&
                                (hints.isEmpty() || confirmed) && (item.images.isEmpty() || supportsImages)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { store.remove(item.id); entries = store.all() }) { Icon(Icons.Default.DeleteOutline, "删除材料") }
                                IconButton(enabled = text.isNotBlank(), onClick = { copy(text); store.touch(item.id, text); entries = store.all(); scope.launch { listState.scrollToItem(0) } }) { Icon(Icons.Default.ContentCopy, "复制材料") }
                                IconButton(enabled = allowed, onClick = { store.touch(item.id, text); onSend(item.copy(text = text), true) }) {
                                    Icon(Icons.Default.AddComment, "新建对话并发送", tint = if (allowed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .25f))
                                }
                                IconButton(enabled = allowed && canSendCurrent, onClick = { store.touch(item.id, text); onSend(item.copy(text = text), false) }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "发送到当前对话", tint = if (allowed && canSendCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .25f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
