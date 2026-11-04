package com.xjtu.toolbox.notification

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.SelectionTile
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
    var category by remember { mutableStateOf(sources.firstOrNull()?.category ?: SourceCategory.GENERAL) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(show) {
        if (show) {
            selected = sources
            category = sources.firstOrNull()?.category ?: SourceCategory.GENERAL
            query = ""
        }
    }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val searching = query.isNotBlank()
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(show = show, title = "通知来源", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            AppSearchBar(query = query, onQueryChange = { query = it }, label = "搜索学院或部门", modifier = Modifier.padding(top = 2.dp))
            if (!searching) {
                Spacer(Modifier.height(12.dp))
                AppSegmentedTabs(
                    tabs = SourceCategory.entries.map { it.displayName },
                    selectedTabIndex = category.ordinal,
                    onTabSelected = { category = SourceCategory.entries[it] },
                )
            }
            Spacer(Modifier.height(12.dp))
            // 列表区定高：各分类来源数不一样，跟着内容伸缩的话一切分类弹窗就跳一下
            AnimatedContent(
                targetState = if (searching) null else category,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.fillMaxWidth().height(360.dp),
                label = "noticeSources",
            ) { shown ->
                val matching = if (shown != null) NotificationSource.byCategory(shown)
                    else NotificationSource.entries.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    matching.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                SelectionTile(item.displayName, item in selected, Modifier.weight(1f), maxLines = 2) {
                                    selected = if (item in selected) selected - item else selected + item
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (matching.isEmpty()) Text("没有匹配的来源", modifier = Modifier.padding(20.dp), color = summary)
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = if (selected.isEmpty()) "请至少选择一个来源" else "查看 ${selected.size} 个来源",
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
