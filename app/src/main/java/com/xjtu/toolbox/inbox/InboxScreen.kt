package com.xjtu.toolbox.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import com.xjtu.toolbox.ui.components.SelectionTile
import com.xjtu.toolbox.ui.components.AppTabPager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.activity.compose.BackHandler
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.LocalOnGlassBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InboxScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val loginState = LocalAppLoginState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()

    val data = InboxStore.snapshot()
    val now = remember(data) { System.currentTimeMillis() }
    val todos = remember(data) { InboxRules.todos(data, now) }
    val finished = remember(data) { InboxRules.finished(data, now) }
    val groups = remember(data) { InboxRules.groups(data, now) }
    val unread = groups.count { it.unread }
    // 这次进来之前没看过的待办标个红点；一看到就记成看过，首页红点随之熄掉，这页的红点留到下次进来
    val seenAtOpen = remember { data.seenTodos }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<InboxItem?>(null) }
    var actions by remember { mutableStateOf<RowActions?>(null) }

    val todoIds = todos.map { it.id }
    androidx.compose.runtime.LaunchedEffect(tab, todoIds) {
        if (tab == 0 && todoIds.isNotEmpty()) InboxStore.markTodosSeen(todoIds)
    }

    fun refresh() = scope.launch {
        val manager = loginState.sessionManager ?: return@launch
        refreshing = true
        error = null
        try {
            SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB, userInitiated = true), loginState.accountId.ifEmpty { null })
            // 有座位待办时顺带现查一次，签过到的马上消失
            if (!data.todos[InboxCategories.LIBRARY].isNullOrEmpty()) {
                runCatching {
                    com.xjtu.toolbox.library.LibraryApi(manager.ensureSite(LoginType.LIBRARY, userInitiated = true)).fetchMyBooking().getOrThrow()
                }.onSuccess { com.xjtu.toolbox.library.LibraryStatus.publish(context, it) }
            }
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
                    if (tab == 1 && unread > 0) {
                        IconButton(onClick = { InboxStore.markAllRead() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = "全部已读")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "收纳设置")
                    }
                },
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        AppSegmentedTabs(
                            tabs = listOf(
                                if (todos.isEmpty()) "待办" else "待办 ${todos.size}",
                                if (unread == 0) "消息" else "消息 $unread",
                            ),
                            selectedTabIndex = tab,
                            onTabSelected = { tab = it },
                            modifier = Modifier.readableWidth(),
                        )
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
            // 待办、消息两栏可以左右滑：标签行和页面同步
            AppTabPager(pageCount = 2, selectedTabIndex = tab, onTabSelected = { tab = it }, modifier = Modifier.fillMaxSize()) { page ->
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical(),
                    contentPadding = PaddingValues(top = glassTop, bottom = 24.dp),
                ) {
                    error?.let { msg ->
                        item(key = "error") {
                            Text(
                                msg,
                                color = MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (page == 0) {
                        if (todos.isEmpty()) {
                            item(key = "empty") {
                                EmptyState("没有待办", "学校事务、作业截止、加餐券都会出现在这里", Icons.Outlined.TaskAlt, Modifier.padding(top = 48.dp))
                            }
                        }
                        todos.groupBy { todoSection(it, now) }.forEach { (title, list) ->
                            section(title, list.map {
                                Entry(
                                    it, it.id, unread = it.id !in seenAtOpen, count = 1,
                                    onLongClick = { actions = RowActions(it, listOf("忽略这条待办" to { InboxStore.ignoreTodo(it.id) })) },
                                ) { open(it) }
                            })
                        }
                        // 办完、忽略的灰着留 7 天，看得到最近处理掉了什么
                        if (finished.isNotEmpty()) {
                            section("已完成", finished.map { f ->
                                Entry(
                                    f.item, "done:${f.item.id}", unread = false, count = 1, dimmed = true,
                                    timeText = (if (f.ignored) "已忽略 · " else "已完成 · ") + timeLabel(f.at),
                                ) { open(f.item) }
                            })
                        }
                    } else {
                        if (groups.isEmpty()) {
                            item(key = "empty") {
                                EmptyState("没有消息", "新成绩、调课、学校通知、工具箱公告都会出现在这里", Icons.Outlined.NotificationsNone, Modifier.padding(top = 48.dp))
                            }
                        }
                        groups.groupBy { daySection(it.latest.time) }.forEach { (title, list) ->
                            section(title, list.map { g ->
                                Entry(
                                    g.latest, g.latest.id, g.unread, g.count, dimmed = !g.unread,
                                    onLongClick = if (g.unread) {
                                        { actions = RowActions(g.latest, listOf("标为已读" to { InboxStore.markRead(g.ids) })) }
                                    } else null,
                                ) { open(g.latest, g.ids) }
                            })
                        }
                    }
                }
            }
        }
        // Overlay 系弹窗要挂在 Scaffold 里面才有宿主，放在外面弹不出来
        InboxSettingsSheet(showSettings, data) { showSettings = false }
    }

    detail?.let { d -> InboxDetailDialog(d) { detail = null } }
    actions?.let { a -> RowActionsDialog(a) { actions = null } }
}

private class Entry(
    val item: InboxItem,
    val key: String,
    val unread: Boolean,
    val count: Int,
    /** 读过的消息、办完的待办：整行变灰。 */
    val dimmed: Boolean = false,
    /** 替换右上角的时间，比如「已完成 · 3 小时前」。 */
    val timeText: String? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** 长按一条弹出的操作。 */
private class RowActions(val item: InboxItem, val actions: List<Pair<String, () -> Unit>>)

@Composable
private fun RowActionsDialog(a: RowActions, onDismiss: () -> Unit) {
    WindowDialog(show = true, title = a.item.title, summary = a.item.source, onDismissRequest = onDismiss) {
        Column {
            a.actions.forEach { (label, run) ->
                TextButton(
                    text = label,
                    onClick = { run(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** 一个小标题加一张卡片，卡片里的条目用细线隔开。 */
private fun LazyListScope.section(title: String, entries: List<Entry>) {
    item(key = "h:$title") { SmallTitle(title) }
    item(key = "c:$title") {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
            entries.forEachIndexed { i, e ->
                key(e.key) {
                    InboxRow(e)
                    if (i != entries.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun todoSection(item: InboxItem, now: Long) =
    if (item.expiresAt > 0 && item.expiresAt - now < DAY_MS) "24 小时内截止" else "待办"

private fun daySection(epoch: Long): String {
    val day = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        !day.isBefore(today) -> "今天"
        day == today.minusDays(1) -> "昨天"
        day.isAfter(today.minusDays(7)) -> "本周"
        else -> "更早"
    }
}

/** 每类一个图标和颜色，扫一眼就知道是哪儿来的。 */
private fun categoryStyle(category: String): Pair<ImageVector, Color> = when (category) {
    InboxCategories.SCHOOL_TODO -> Icons.AutoMirrored.Filled.Assignment to Color(0xFF3B82F6)
    InboxCategories.BOOKING -> Icons.Default.DirectionsBus to Color(0xFF14B8A6)
    InboxCategories.LIBRARY -> Icons.Default.EventSeat to Color(0xFF0D9488)
    InboxCategories.LMS -> Icons.AutoMirrored.Filled.MenuBook to Color(0xFF8B5CF6)
    InboxCategories.COUPON -> Icons.Default.Restaurant to Color(0xFFF97316)
    InboxCategories.JUDGE -> Icons.Default.RateReview to Color(0xFFEC4899)
    InboxCategories.GRADE -> Icons.Default.EmojiEvents to Color(0xFFEAB308)
    InboxCategories.SCHEDULE -> Icons.Default.EditCalendar to Color(0xFF6366F1)
    InboxCategories.ATTENDANCE -> Icons.Default.Warning to Color(0xFFEF4444)
    InboxCategories.NOTICE -> Icons.Outlined.Newspaper to Color(0xFF10B981)
    InboxCategories.BULLETIN -> Icons.Default.Campaign to Color(0xFF0EA5E9)
    else -> Icons.Default.School to Color(0xFF64748B)
}

/** 首页顶栏的铃铛：角标 = 未读消息组 + 没看过的待办。角标叠在按钮外面，不会被按钮的圆形裁掉。 */
@Composable
fun InboxBell(onClick: () -> Unit) {
    val data = InboxStore.snapshot()
    val count = remember(data) { InboxRules.badge(data, System.currentTimeMillis()) }
    Box {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = if (count > 0) "消息，$count 条未处理" else "消息",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 4.dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .background(MiuixTheme.colorScheme.error, CircleShape)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 99) "99+" else "$count",
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("M月d日")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** 有截止时间的待办报还剩多久，其余报发生在什么时候。 */
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
    val at = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    val minutes = (System.currentTimeMillis() - epoch) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        at.toLocalDate() == LocalDate.now() -> at.format(CLOCK)
        else -> at.format(DAY)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(e: Entry) {
    val item = e.item
    val unread = e.unread
    val count = e.count
    val (icon, tint) = categoryStyle(item.category)
    val urgent = !e.dimmed && item.expiresAt > 0 && item.expiresAt - System.currentTimeMillis() < DAY_MS
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = e.onClick, onLongClick = e.onLongClick)
            .alpha(if (e.dimmed) 0.5f else 1f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            if (unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(11.dp)
                        .background(MiuixTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(MiuixTheme.colorScheme.error, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.source + if (count > 1) " · $count 条" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    e.timeText ?: timeLabel(item),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (urgent) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontWeight = if (urgent) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // 学校不少消息的标题就是系统名，和上面的来源重复，这时直接拿正文当标题
            val titleIsSource = item.title == item.source && item.body.isNotBlank()
            Text(
                if (titleIsSource) item.body else item.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (item.body.isNotBlank() && !titleIsSource) {
                Text(
                    item.body,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InboxSettingsSheet(show: Boolean, data: InboxData, onDismiss: () -> Unit) {
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(show = show, title = "收纳设置", onDismissRequest = onDismiss) {
        val school = InboxStore.schoolCategories(data).map { InboxCategory(it, InboxCategories.schoolLabel(it)) }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("勾掉的类别不进列表、不计数，屁岱也不再提。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            SettingsGroup("待办", InboxCategories.todos, data)
            SettingsGroup("消息", InboxCategories.messages, data)
            if (school.isNotEmpty()) SettingsGroup("学校消息", school, data)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一组类别，两列勾选块。 */
@Composable
private fun SettingsGroup(title: String, categories: List<InboxCategory>, data: InboxData) {
    Text(title, style = MiuixTheme.textStyles.subtitle, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))
    categories.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { c ->
                val on = c.key !in data.off
                SelectionTile(c.label, on, Modifier.weight(1f), maxLines = 2) { InboxStore.setEnabled(c.key, !on) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
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
