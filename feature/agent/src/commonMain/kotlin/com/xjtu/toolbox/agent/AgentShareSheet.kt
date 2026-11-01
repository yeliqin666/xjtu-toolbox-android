package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.rememberCopyText
import com.xjtu.toolbox.util.campusDateTimeOf
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import kotlin.time.Clock

@Composable
internal fun AgentShareSheet(
    store: AgentShareStore,
    host: AgentHost,
    canSend: Boolean,
    supportsImages: Boolean,
    sendHint: String?,
    /** 发送时总是新开对话。 */
    onSend: (AgentSharedMaterial) -> Unit,
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
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    OverlayBottomSheet(show = true, title = "分享材料", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sendHint ?: "仅存本机，7 天后自动清理", style = MiuixTheme.textStyles.footnote1, color = summary,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                        if (expanded) "收起预览" else "展开预览", modifier = Modifier.size(18.dp), tint = summary)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = windowHeight * if (expanded) .75f else .5f), state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (entries.isEmpty()) item {
                    Text("从其他应用分享或拖入文字、链接、图片，会先收在这里。", style = MiuixTheme.textStyles.body2, color = summary,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                items(entries, key = { it.id }) { item ->
                    MaterialCard(
                        item = item, host = host, expanded = expanded, canSend = canSend, supportsImages = supportsImages,
                        onRemove = { store.remove(item.id); entries = store.all() },
                        onCopy = { text ->
                            copy(text); store.touch(item.id, text); entries = store.all()
                            scope.launch { listState.scrollToItem(0) }
                        },
                        onSend = { text -> store.touch(item.id, text); onSend(item.copy(text = text)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialCard(
    item: AgentSharedMaterial,
    host: AgentHost,
    expanded: Boolean,
    canSend: Boolean,
    supportsImages: Boolean,
    onRemove: () -> Unit,
    onCopy: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    var editing by remember(item.id) { mutableStateOf(false) }
    var confirmed by remember(item.id, text) { mutableStateOf(false) }
    val hints = remember(text) { sharePrivacyHints(text) }
    val ink = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val lines = remember(text) { text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() } }
    val allowed = canSend && (text.isNotBlank() || item.images.isNotEmpty()) &&
        (hints.isEmpty() || confirmed) && (item.images.isEmpty() || supportsImages)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ink.copy(alpha = 0.045f))
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.Top) {
            Text(lines.firstOrNull() ?: "图片", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(shareTimeLabel(item.updatedAt), style = MiuixTheme.textStyles.footnote2, color = summary)
        }
        if (editing) {
            TextField(value = text, onValueChange = { text = it.take(30_000) }, maxLines = if (expanded) 14 else 8,
                textStyle = MiuixTheme.textStyles.body2, modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
        } else if (lines.size > 1) {
            Text(lines.drop(1).joinToString("\n"), style = MiuixTheme.textStyles.footnote1, color = summary,
                maxLines = if (expanded) 14 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
        }
        if (item.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.images.forEach { AttachmentThumb(it, host, 52.dp) }
        }
        if (item.images.isNotEmpty() && !supportsImages) {
            Text("当前模型不支持图片，请先切换模型", style = MiuixTheme.textStyles.footnote2, color = summary)
        }
        if (hints.isNotEmpty()) Row(
            Modifier.clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Checkbox) { confirmed = !confirmed }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state = if (confirmed) ToggleableState.On else ToggleableState.Off, onClick = { confirmed = !confirmed },
                modifier = Modifier.scale(0.8f))
            Spacer(Modifier.width(6.dp))
            Text("含${hints.joinToString("、")}，确认交给当前 AI 服务商", style = MiuixTheme.textStyles.footnote2,
                color = if (confirmed) summary else MiuixTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.DeleteOutline, "删除材料", onClick = onRemove)
            CardIcon(Icons.Outlined.ContentCopy, "复制材料", enabled = text.isNotBlank()) { onCopy(text) }
            CardIcon(if (editing) Icons.Outlined.EditOff else Icons.Outlined.Edit, if (editing) "完成编辑" else "编辑材料") {
                editing = !editing
            }
            Spacer(Modifier.weight(1f))
            SendChip("发给小岱", enabled = allowed) { onSend(text) }
        }
    }
}

@Composable
private fun CardIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, label, modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable
private fun SendChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    val alpha = if (enabled) 1f else .4f
    Text(
        label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onPrimary.copy(alpha = alpha),
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(primary.copy(alpha = alpha))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun shareTimeLabel(epochMs: Long): String {
    val at = campusDateTimeOf(epochMs)
    val today = campusDateTimeOf(Clock.System.now().toEpochMilliseconds()).date
    val time = at.time.toString().take(5)
    return if (at.date == today) time else "${at.monthNumber}月${at.dayOfMonth}日 $time"
}
