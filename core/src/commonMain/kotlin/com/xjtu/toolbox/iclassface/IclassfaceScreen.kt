package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.error.SessionExpiredFailure

import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.FullPageState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.xjtu.toolbox.nav.AppRoute

/**
 * 人脸识别签到查询页。
 *
 * 定位：bkkq（本科考勤）接口完备但上课高峰期常卡死，这里作为独立的快速通道，
 * 只回答一个问题——「今天刷没刷上卡」，不做课程级考勤统计（那是 bkkq 的事）。
 * 与本科/研究生考勤登录流程完全独立，互不影响。
 *
 * 从 `:app` 的 `iclassface/IclassfaceScreen.kt` 搬进 `:core`（桌面端第 20 条真数据路由），
 * 屏上画的东西（日期条 / 状态卡 / 记录列表 / 三档加载态）一个字没改，被换掉的写法只有五处：
 *
 *  1. `site`（`SiteSession`）+ `IclassfaceApi(site)` → 注入的 [source] 端口
 *     （`:data` 的 `AppIclassfaceSource` 包住 `IclassfaceApi`）；
 *  2. `LocalAppLoginState.handleAuthExpired(...)` → `:core` 的 [LocalAuthExpiry]
 *     （见 `auth/AuthExpiry.kt`；`:app` 注入的就是同一个 `handleAuthExpired`，行为不变）；
 *  3. 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app` 的
 *     `AuthExpiredException` 实现了它）—— `catch` 抓不了接口，先抓 `Exception` 再判；
 *  4. `java.time.LocalDate` → `kotlinx.datetime.LocalDate`（`commonMain` 没有前者；
 *     「今天」用 `:core` 既有的 [todayInSystemZone]，与考勤屏同一个口径）；
 *  5. `AppDatePickerDialog` 的边界转换（`toKx()` / `toJavaTime()`）不再需要 ——
 *     那个组件本来收/吐的就是 `kotlinx.datetime.LocalDate`。
 */
@Composable
fun IclassfaceScreen(
    /** 本端的取数（Android / 桌面 = `:data` 的 `AppIclassfaceSource`；Web 还没有这条链）。 */
    source: IclassfaceSource,
    onBack: () -> Unit,
) {
    val authExpiry = LocalAuthExpiry.current
    val scope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(todayInSystemZone()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<CheckinRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(date: LocalDate, silent: Boolean = false) {
        if (silent) isRefreshing = true else loading = true
        error = null
        scope.launch {
            try {
                records = source.fetchRecords(date)
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiry.onAuthExpired(AppRoute.Iclassface, onBack)
                } else {
                    error = com.xjtu.toolbox.error.FriendlyError.of(e, "查询")
                }
            } finally {
                loading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load(selectedDate) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val isToday = selectedDate == todayInSystemZone()
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "快速考勤流水",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        val today = todayInSystemZone()
        AppDatePickerDialog(
            show = showDatePicker,
            title = "选择日期",
            date = selectedDate,
            minDate = today.minus(3, DateTimeUnit.YEAR),
            maxDate = today,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                selectedDate = it
                showDatePicker = false
                load(it, silent = true)
            }
        )
        AppPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { load(selectedDate, silent = true) },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
        when {
            loading -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) { LoadingState(message = "查询签到记录...", modifier = Modifier.fillMaxSize()) }
            error != null && records.isEmpty() -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                ErrorState(
                    message = error!!,
                    onRetry = { load(selectedDate) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    DateSwitchRow(
                        date = selectedDate,
                        isToday = isToday,
                        onDateChange = { selectedDate = it; load(it, silent = true) },
                        onPickDate = { showDatePicker = true }
                    )
                }
                item {
                    StatusHero(
                        checkedIn = records.isNotEmpty(),
                        isToday = isToday,
                        latestTime = records.firstOrNull()?.time
                    )
                }
                if (records.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (isToday) "今天还没有签到/刷卡记录" else "当天没有签到/刷卡记录",
                            subtitle = "数据来自人脸识别签到系统"
                        )
                    }
                } else {
                    item {
                        Text(
                            "共 ${records.size} 条记录",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    items(records) { record -> RecordCard(record) }
                }
            }
        }
        }
    }
}

/**
 * 日期条：最近 14 天一字排开，最右边是今天，点哪天看哪天；再早的日子点末尾的日历图标，用系统月历选。
 *
 * 以前是左右箭头一天一天步进、中间一个日期点开三个下拉框（年 / 月 / 日）：想看上周三要连点好几下，
 * 又难看又难用。查签到基本只看最近几天，一排日期直接点最快。选中的日期在两周以外时，
 * 它自己排在最前面，免得选完了日期条上找不到。
 */
@Composable
private fun DateSwitchRow(
    date: LocalDate,
    @Suppress("UNUSED_PARAMETER") isToday: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val today = remember { todayInSystemZone() }
    val recent = remember(today) { (13 downTo 0).map { today.minus(it, DateTimeUnit.DAY) } }
    val days = if (date in recent) recent else listOf(date) + recent
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 一进来滚到最右边（今天）；选中的日期在屏幕外时把它滚进来
    LaunchedEffect(date) {
        val index = days.indexOf(date).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(index) }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.lazy.LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(days.size) { i ->
                    val d = days[i]
                    DateCell(
                        date = d,
                        selected = d == date,
                        isToday = d == today,
                        onClick = { if (d != date) onDateChange(d) },
                    )
                }
            }
            IconButton(onClick = onPickDate) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "选择更早的日期",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DateCell(date: LocalDate, selected: Boolean, isToday: Boolean, onClick: () -> Unit) {
    val weekday = when (date.dayOfWeek.isoDayNumber) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
    val scheme = MiuixTheme.colorScheme
    Column(
        Modifier
            .width(48.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isToday) "今天" else weekday,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) scheme.onPrimary.copy(alpha = 0.85f) else scheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${date.dayOfMonth}",
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.Bold,
            color = if (selected) scheme.onPrimary else if (isToday) scheme.primary else scheme.onSurface,
        )
        // 每月 1 号标一下月份，跨月时知道是哪个月
        Text(
            if (date.dayOfMonth == 1 || selected) "${date.monthNumber}月" else " ",
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) scheme.onPrimary.copy(alpha = 0.85f) else scheme.onSurfaceVariantSummary,
        )
    }
}
@Composable
private fun StatusHero(checkedIn: Boolean, isToday: Boolean, latestTime: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, tint) = if (checkedIn) {
                Icons.Default.CheckCircle to MiuixTheme.colorScheme.primary
            } else {
                Icons.Default.Warning to MiuixTheme.colorScheme.error
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (checkedIn) {
                        if (isToday) "今天已刷卡/签到" else "当天已刷卡/签到"
                    } else {
                        if (isToday) "今天还没刷卡/签到" else "当天没有刷卡/签到"
                    },
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold
                )
                if (latestTime != null) {
                    Text(
                        "最近一次：$latestTime",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: CheckinRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.time, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        record.location,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        record.type,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
