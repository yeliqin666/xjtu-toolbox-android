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
import top.yukonga.miuix.kmp.basic.TextField
import com.xjtu.toolbox.ui.components.TextLink
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 多选先保存在面板内，确认后只重新加载一次。搜索不受分类限制。来源入口在通知页顶栏右上角，当前来源写在副标题里。 */
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

/** 只编辑当前已选来源的规则；没选的来源规则原样保留。点保存才生效，划走等于取消。 */
@Composable
internal fun NoticeRuleSheet(
    show: Boolean,
    sources: Set<NotificationSource>,
    rules: Map<NotificationSource, NoticeRule>,
    onDismiss: () -> Unit,
    onSave: (Map<NotificationSource, NoticeRule>) -> Unit,
) {
    var draft by remember { mutableStateOf(rules) }
    LaunchedEffect(show) { if (show) draft = rules }
    val colors = MiuixTheme.colorScheme
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "通知规则",
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                "按标题过滤，列表和站内搜索都生效。多个词用空格或逗号隔开。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                sources.sorted().forEach { source ->
                    val rule = draft[source] ?: NoticeRule()
                    fun update(new: NoticeRule) { draft = draft + (source to new) }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(source.displayName, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (!rule.isEmpty) TextLink(text = "清空", onClick = { update(NoticeRule()) })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = rule.only,
                            onValueChange = { update(rule.copy(only = it)) },
                            label = "只看含这些词（留空不限）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = rule.block,
                            onValueChange = { update(rule.copy(block = it)) },
                            label = "屏蔽含这些词",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(draft)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存", color = colors.onPrimary)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
