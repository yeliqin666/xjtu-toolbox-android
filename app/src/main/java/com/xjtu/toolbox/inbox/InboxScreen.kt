package com.xjtu.toolbox.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InboxScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val loginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()

    val data = InboxStore.snapshot()
    val now = remember(data) { System.currentTimeMillis() }
    val todos = remember(data) { InboxRules.todos(data, now) }
    val groups = remember(data) { InboxRules.groups(data, now) }

    var showTodos by rememberSaveable { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<InboxItem?>(null) }

    fun refresh() = scope.launch {
        val manager = loginState.sessionManager ?: return@launch
        refreshing = true
        error = null
        try {
            SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB, userInitiated = true), loginState.accountId.ifEmpty { null })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = com.xjtu.toolbox.error.FriendlyError.of(e, "刷新学校消息")
        } finally {
            refreshing = false
        }
    }

    fun open(item: InboxItem, ids: List<String> = listOf(item.id)) {
        InboxStore.markRead(ids)
        item.route?.let(onOpen) ?: run { detail = item }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "消息",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { InboxStore.markAllRead() }) {
                        Icon(Icons.Default.DoneAll, contentDescription = "全部已读")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "收纳设置")
                    }
                },
            )
        },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.padding(padding.withoutTop(glass)).glassSource(glass).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = glassTop, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "tabs") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppFilterChip(selected = showTodos, onClick = { showTodos = true }, label = "待办 ${todos.size}")
                        AppFilterChip(selected = !showTodos, onClick = { showTodos = false }, label = "消息 ${groups.count { it.unread }}")
                    }
                }
                error?.let { msg ->
                    item(key = "error") {
                        Text(msg, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                }
                if (showTodos) {
                    if (todos.isEmpty()) item(key = "empty") { EmptyState("没有待办", "学校事务、作业截止、加餐券都会出现在这里", Icons.Outlined.TaskAlt, Modifier.padding(top = 48.dp)) }
                    items(todos, key = { it.id }) { t -> InboxRow(t, unread = false, count = 1) { open(t) } }
                } else {
                    if (groups.isEmpty()) item(key = "empty") { EmptyState("没有消息", "新成绩、调课、学校通知都会出现在这里", modifier = Modifier.padding(top = 48.dp)) }
                    items(groups, key = { it.latest.id }) { g -> InboxRow(g.latest, g.unread, g.count) { open(g.latest, g.ids) } }
                }
            }
        }
    }

    if (showSettings) InboxSettingsDialog(data) { showSettings = false }
    detail?.let { d -> InboxDetailDialog(d) { detail = null } }
}

/** 首页顶栏的铃铛：角标 = 未读消息组 + 待办。 */
@Composable
fun InboxBell(onClick: () -> Unit) {
    val data = InboxStore.snapshot()
    val count = remember(data) { InboxRules.badge(data, System.currentTimeMillis()) }
    IconButton(onClick = onClick) {
        Box {
            Icon(
                Icons.Default.Notifications,
                contentDescription = if (count > 0) "消息，$count 条未处理" else "消息",
                tint = MiuixTheme.colorScheme.onSurface,
            )
            if (count > 0) {
                Text(
                    if (count > 99) "99+" else "$count",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-6).dp)
                        .background(MiuixTheme.colorScheme.error, RoundedCornerShape(50))
                        .defaultMinSize(minWidth = 16.dp)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("M月d日")

/** 有截止时间的待办报还剩多久，其余报发生在多久以前。 */
private fun timeLabel(item: InboxItem): String {
    if (item.expiresAt <= 0) return timeLabel(item.time)
    val minutes = (item.expiresAt - System.currentTimeMillis()) / 60_000
    return when {
        minutes < 60 -> "$minutes 分钟后截止"
        minutes < 24 * 60 -> "${minutes / 60} 小时后截止"
        else -> Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).format(DAY) + "截止"
    }
}

private fun timeLabel(epoch: Long): String {
    if (epoch <= 0) return ""
    val minutes = (System.currentTimeMillis() - epoch) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DAY)
    }
}

@Composable
private fun InboxRow(item: InboxItem, unread: Boolean, count: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (unread) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.source + if (count > 1) " · 共 $count 条" else "",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(timeLabel(item), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                // 学校不少消息的标题就是系统名，和上面的来源重复，这时直接拿正文当标题
                val titleIsSource = item.title == item.source && item.body.isNotBlank()
                Text(
                    if (titleIsSource) item.body else item.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.body.isNotBlank() && !titleIsSource) {
                    Text(item.body, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InboxSettingsDialog(data: InboxData, onDismiss: () -> Unit) {
    val school = InboxStore.schoolCategories(data).map { InboxCategory(it, InboxCategories.schoolLabel(it)) }
    WindowDialog(show = true, title = "收纳设置", summary = "关掉的类别不进列表、不计数，屁岱也不再提", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            SettingsGroup("待办", InboxCategories.todos, data)
            SettingsGroup("消息", InboxCategories.messages, data)
            if (school.isNotEmpty()) SettingsGroup("学校消息", school, data)
            TextButton(text = "完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun SettingsGroup(title: String, categories: List<InboxCategory>, data: InboxData) {
    Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
    categories.forEach { c ->
        SwitchPreference(
            title = c.label,
            checked = c.key !in data.off,
            onCheckedChange = { InboxStore.setEnabled(c.key, it) },
        )
    }
}

@Composable
private fun InboxDetailDialog(item: InboxItem, onDismiss: () -> Unit) {
    WindowDialog(show = true, title = item.title, summary = "${item.source} · ${timeLabel(item.time)}", onDismissRequest = onDismiss) {
        Column {
            Text(
                item.body.ifBlank { item.title },
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
            TextButton(text = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
