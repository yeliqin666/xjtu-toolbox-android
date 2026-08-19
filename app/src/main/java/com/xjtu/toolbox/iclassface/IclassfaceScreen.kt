package com.xjtu.toolbox.iclassface

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate

/**
 * 人脸识别签到查询页。
 *
 * 定位：bkkq（本科考勤）接口完备但上课高峰期常卡死，这里作为独立的快速通道，
 * 只回答一个问题——「今天刷没刷上卡」，不做课程级考勤统计（那是 bkkq 的事）。
 * 与本科/研究生考勤登录流程完全独立，互不影响。
 */
@Composable
fun IclassfaceScreen(
    site: SiteSession,
    onBack: () -> Unit,
) {
    val api = remember(site) { IclassfaceApi(site) }
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<IclassfaceApi.CheckinRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(date: LocalDate, silent: Boolean = false) {
        if (silent) isRefreshing = true else loading = true
        error = null
        scope.launch {
            try {
                records = withContext(Dispatchers.IO) { api.fetchRecords(date) }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.ICLASSFACE, Routes.ICLASSFACE, onBack)
            } catch (e: Exception) {
                error = e.message ?: "查询失败"
            } finally {
                loading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load(selectedDate) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    val isToday = selectedDate == LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "快速考勤流水",
                largeTitle = "快速考勤流水",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val today = LocalDate.now()
        AppDatePickerDialog(
            show = showDatePicker,
            title = "选择日期",
            date = selectedDate,
            minDate = today.minusYears(3),
            maxDate = today,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                selectedDate = it
                showDatePicker = false
                load(it, silent = true)
            }
        )
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { load(selectedDate, silent = true) },
            pullToRefreshState = pullToRefreshState,
            topAppBarScrollBehavior = scrollBehavior,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        when {
            loading -> LazyColumn(Modifier.fillMaxSize()) {
                item { Box(Modifier.fillParentMaxSize()) { LoadingState(message = "查询签到记录...", modifier = Modifier.fillMaxSize()) } }
            }
            error != null && records.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillParentMaxSize()) {
                        ErrorState(
                            message = error!!,
                            onRetry = { load(selectedDate) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
 * 日期切换条。
 *
 * 用 Card 包住，与同页的 StatusHero / RecordCard 保持同一套卡片语言——原来是个裸 Row，
 * 没有任何边界，夹在两张卡之间像是浮在外面的。
 *
 * 左右箭头按天步进；中间日期可点开选择器，一次跳到任意一天。未来日期没有记录，右箭头到今天禁用。
 */
@Composable
private fun DateSwitchRow(
    date: LocalDate,
    isToday: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val relative = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.minusDays(2) -> "前天"
        else -> "${java.time.temporal.ChronoUnit.DAYS.between(date, today)} 天前"
    }
    val weekday = when (date.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDateChange(date.minusDays(1)) }) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "前一天",
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onPickDate),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        date.toString(),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    "$relative · $weekday",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            // 未来日期不会有签到记录，到今天为止
            IconButton(
                enabled = !isToday,
                onClick = { onDateChange(date.plusDays(1)) }
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "后一天",
                    tint = if (isToday) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    }
                )
            }
        }
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
private fun RecordCard(record: IclassfaceApi.CheckinRecord) {
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
