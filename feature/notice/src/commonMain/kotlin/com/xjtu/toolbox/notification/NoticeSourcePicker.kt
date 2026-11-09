package com.xjtu.toolbox.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppCheckRow
import com.xjtu.toolbox.ui.components.AppOptionGroup
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NoticeSourceBar(sources: Set<NotificationSource>, onOpen: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp)).background(AppCardColor)
            .semantics { contentDescription = "选择通知来源" }.clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Newspaper, null, Modifier.size(20.dp), tint = primary)
        }
        Text(sources.joinToString("、") { it.displayName }, modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${sources.size} 个来源", style = MiuixTheme.textStyles.footnote1, color = primary)
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = primary)
    }
}

/** 多选先保存在面板内，确认后只重新加载一次。搜索不受分类限制。 */
@Composable
internal fun NoticeSourceSheet(
    show: Boolean,
    sources: Set<NotificationSource>,
    onDismiss: () -> Unit,
    onConfirm: (Set<NotificationSource>) -> Unit,
) {
    var selected by remember { mutableStateOf(sources) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(show) {
        if (show) {
            selected = sources
            query = ""
        }
    }
    val colors = MiuixTheme.colorScheme
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "通知来源",
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            AppSearchBar(query = query, onQueryChange = { query = it }, label = "搜索学院或部门", modifier = Modifier.padding(top = 2.dp))
            Text(
                if (selected.isEmpty()) "还没选来源" else "已选：" + selected.joinToString("、") { it.displayName },
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
            )
            // 定高：搜索时条目变少，弹窗不跟着跳
            Column(Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState())) {
                val keyword = query.trim()
                var anyMatch = false
                SourceCategory.entries.forEach { category ->
                    val matching = NotificationSource.byCategory(category)
                        .filter { keyword.isEmpty() || it.displayName.contains(keyword, ignoreCase = true) }
                    if (matching.isNotEmpty()) {
                        anyMatch = true
                        val allOn = selected.containsAll(matching)
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(category.displayName, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                            Text(
                                if (allOn) "清空" else "全选",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colors.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selected = if (allOn) selected - matching.toSet() else selected + matching }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        AppOptionGroup {
                            matching.forEach { item ->
                                AppCheckRow(item.displayName, item in selected) {
                                    selected = if (item in selected) selected - item else selected + item
                                }
                            }
                        }
                    }
                }
                if (!anyMatch) Text("没有匹配的来源", modifier = Modifier.padding(20.dp), color = colors.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (selected.isEmpty()) "请至少选择一个来源" else "查看 ${selected.size} 个来源",
                    color = colors.onPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
