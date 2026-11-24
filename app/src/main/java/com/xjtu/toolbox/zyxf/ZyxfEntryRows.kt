package com.xjtu.toolbox.zyxf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一项的点击：轻点打开，长按出操作菜单（#200）。 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.entryClicks(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** 分组卡片里一行的形状：首行圆上角、末行圆下角，连起来是一整张卡。 */
private fun groupShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val r = 18.dp
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
}

@Composable
private fun GroupDivider(start: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.dividerLine))
}

/** 列表视图（#199）的文件夹行：一行一个，名字前一个按名字取色的文件夹图标。 */
@Composable
internal fun FolderLine(entry: ZyxfApi.Entry, first: Boolean, last: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(groupShape(first, last))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .entryClicks(onClick, onLongClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = folderTone(entry.name), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.timeText.isNotBlank()) {
                Text(entry.timeText, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (!last) GroupDivider(50.dp)
    }
}

/** 列表视图的文件行，照官网：类型标、文件名、彩色大小标签、日期、下载。 */
@Composable
internal fun FileLine(
    entry: ZyxfApi.Entry,
    state: String?,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val (tone, label) = extTone(entry.ext)
    val color = Color(tone)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(groupShape(first, last))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
            .entryClicks(onClick, onLongClick),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1,
                modifier = Modifier.width(30.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                entry.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.sizeText.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                val pill = sizeTone(entry.sizeBytes)
                Text(
                    entry.sizeText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = pill,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(pill.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            entry.shortDate()?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                when {
                    state == DOWNLOADING -> CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp, progress = null)
                    else -> IconButton(onClick = onDownload, enabled = state == null, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = state ?: "下载",
                            tint = if (state == null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (!last) GroupDivider(50.dp)
    }
}

/** 大小标签的颜色：按量级分档，扫一眼就分得出大文件。 */
private fun sizeTone(bytes: Long): Color = Color(
    when {
        bytes < 1L shl 20 -> 0xFF2E9E6B
        bytes < 5L shl 20 -> 0xFF3B82C4
        bytes < 20L shl 20 -> 0xFF8B5CC7
        else -> 0xFFD9534F
    },
)

private fun ZyxfApi.Entry.shortDate(): String? =
    createdAt.takeIf { it > 0 }?.let { SimpleDateFormat("yy-MM-dd", Locale.CHINA).format(Date(it)) }

/** 长按一项弹出的操作菜单（#200）：上面是能拿到的全部信息，下面是操作。 */
@Composable
internal fun ZyxfEntrySheet(
    entry: ZyxfApi.Entry?,
    downloadState: String?,
    location: String,
    onTodo: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = entry != null, title = entry?.name.orEmpty(), onDismissRequest = onDismiss) {
        val e = entry ?: return@OverlayBottomSheet
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            val facts = listOfNotNull(
                (if (e.isFolder) "文件夹" else extTone(e.ext).second.ifBlank { "文件" }),
                e.sizeText.takeIf { it.isNotBlank() },
                e.createdAt.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it)) + " 上传" },
            ).joinToString(" · ")
            Text(facts, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            (e.path.takeIf { it.isNotBlank() } ?: location.takeIf { it.isNotBlank() })?.let {
                Text("位置：$it", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(10.dp))
            SheetAction(Icons.AutoMirrored.Filled.EventNote, "加入待办", onTodo)
            if (!e.isFolder) {
                SheetAction(Icons.Filled.Download, downloadState ?: "下载", onDownload, enabled = downloadState == null)
                SheetAction(Icons.Filled.Share, "分享", onShare, enabled = downloadState != DOWNLOADING)
            }
            SheetAction(Icons.AutoMirrored.Filled.Chat, "小岱解读", onAsk)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MiuixTheme.textStyles.body1, color = tint)
    }
}
