package com.xjtu.toolbox.newattendance

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.attendance.AttendanceStream
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.CourseAttendanceStat
import com.xjtu.toolbox.attendance.TermInfo
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private data class NewAttendanceSnapshot(
    val studentName: String,
    val terms: List<TermInfo>,
    val termBh: String,
    val records: List<AttendanceWaterRecord>,
    val streams: List<AttendanceStream>,
    val stats: List<CourseAttendanceStat>,
    val window: LeaveSemesterWindow?,
    val leaves: List<LeaveRecord>,
)

@Composable
fun NewAttendanceScreen(
    site: SiteSession,
    onBack: () -> Unit,
) {
    val api = remember(site) { NewAttendanceApi(site) }
    val leaveApi = remember(site) { LeaveApi(site) }
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var studentName by remember { mutableStateOf("") }
    var termList by remember { mutableStateOf<List<TermInfo>>(emptyList()) }
    var selectedTermBh by rememberSaveable { mutableStateOf("") }
    var records by remember { mutableStateOf<List<AttendanceWaterRecord>>(emptyList()) }
    var streams by remember { mutableStateOf<List<AttendanceStream>>(emptyList()) }
    var courseStats by remember { mutableStateOf<List<CourseAttendanceStat>>(emptyList()) }
    var leaves by remember { mutableStateOf<List<LeaveRecord>>(emptyList()) }
    var semesterWindow by remember { mutableStateOf<LeaveSemesterWindow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LeaveRecord?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<LeaveAction, LeaveRecord>?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun expired() = appLoginState.handleAuthExpired(LoginType.NEW_ATTENDANCE, Routes.NEW_ATTENDANCE, onBack)

    fun load(fromPull: Boolean = false) {
        loadJob?.cancel()
        if (fromPull) refreshing = true else loading = true
        error = null
        loadJob = scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val name = api.getStudentInfo()["name"] as? String ?: ""
                    val terms = api.getTermList()
                    val bh = selectedTermBh.ifBlank { api.getTermBh() }
                    val term = terms.firstOrNull { it.bh == bh }
                    val fetched = api.getWaterRecords(bh, term?.startDate.orEmpty(), term?.endDate.orEmpty())
                    val stats = try {
                        if (bh == api.getTermBh()) api.getKqtjCurrentWeek() else api.computeCourseStatsFromRecords(fetched)
                    } catch (_: Exception) {
                        api.computeCourseStatsFromRecords(fetched)
                    }
                    // 打卡流水只是原始刷卡数据，拉不到不影响其它 tab，单独兜底成空表。
                    val streamRows = try {
                        api.getStreams(term?.startDate.orEmpty(), term?.endDate.orEmpty())
                    } catch (e: AuthExpiredException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    NewAttendanceSnapshot(
                        studentName = name,
                        terms = terms,
                        termBh = bh,
                        records = fetched,
                        streams = streamRows,
                        stats = stats,
                        window = runCatching { leaveApi.getSemesterWindow() }.getOrNull(),
                        leaves = leaveApi.getLeavePage().records,
                    )
                }
                studentName = snapshot.studentName
                termList = snapshot.terms
                selectedTermBh = snapshot.termBh
                records = snapshot.records
                courseStats = snapshot.stats
                semesterWindow = snapshot.window
                leaves = snapshot.leaves
            } catch (e: AuthExpiredException) {
                expired()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = "新版考勤",
                largeTitle = "新版考勤",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selectedTab == 3) {
                        IconButton(onClick = { showForm = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建请假")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading && records.isEmpty() && leaves.isEmpty() && error == null -> {
                LoadingState(message = "加载新版考勤…", modifier = Modifier.fillMaxSize().padding(padding))
            }
            error != null && records.isEmpty() && leaves.isEmpty() -> {
                ErrorState(message = error!!, onRetry = { load() }, modifier = Modifier.fillMaxSize().padding(padding))
            }
            else -> {
                PullToRefresh(
                    isRefreshing = refreshing,
                    pullToRefreshState = pullToRefreshState,
                    onRefresh = { load(fromPull = true) },
                    topAppBarScrollBehavior = scrollBehavior,
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (studentName.isNotBlank()) {
                            Text(
                                text = studentName + (semesterWindow?.semesterName?.let { " · $it" } ?: ""),
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                            )
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.defaultColors(color = AppCardColor)
                        ) {
                            AppSegmentedTabs(
                                tabs = listOf("流水", "打卡流水", "统计", "请假"),
                                selectedTabIndex = selectedTab,
                                onTabSelected = { selectedTab = it },
                                embedded = true,
                            )
                            if (termList.isNotEmpty() && selectedTab != 3) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                OverlaySpinnerPreference(
                                    title = "学期",
                                    summary = "选择要查询的学期",
                                    items = termList.map { DropdownItem(text = it.name) },
                                    selectedIndex = termList.indexOfFirst { it.bh == selectedTermBh }.coerceAtLeast(0),
                                    onSelectedIndexChange = {
                                        selectedTermBh = termList[it].bh
                                        load()
                                    }
                                )
                            }
                        }
                        when (selectedTab) {
                            0 -> RecordList(records)
                            1 -> StreamList(streams)
                            2 -> StatList(courseStats)
                            else -> LeaveList(
                                leaves = leaves,
                                onOpen = { detail = it },
                                onWithdraw = { pendingAction = LeaveAction.WITHDRAW to it },
                                onCancel = { pendingAction = LeaveAction.CANCEL to it },
                            )
                        }
                    }
                }
            }
        }

        pendingAction?.let { (action, rec) ->
            OverlayDialog(
                show = true,
                title = if (action == LeaveAction.WITHDRAW) "撤回请假" else "申请销假",
                onDismissRequest = { pendingAction = null },
            ) {
                Text(
                    if (action == LeaveAction.WITHDRAW) {
                        "撤回后需要重新提交请假申请。"
                    } else {
                        "销假将提交给审批人确认。"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingAction = null },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确定",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pendingAction = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        when (action) {
                                            LeaveAction.WITHDRAW -> leaveApi.withdrawLeave(rec.leaveId, "学生撤回")
                                            LeaveAction.CANCEL -> leaveApi.cancelLeave(rec.leaveId)
                                        }
                                    }
                                    snackbarHostState.showSnackbar(
                                        if (action == LeaveAction.WITHDRAW) "请假申请已撤回" else "已提交销假申请"
                                    )
                                    load()
                                } catch (e: AuthExpiredException) {
                                    expired()
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(e.message ?: "操作失败")
                                }
                            }
                        },
                    )
                }
            }
        }

        if (showForm) {
            LeaveFormDialog(
                leaveApi = leaveApi,
                window = semesterWindow,
                onDismiss = { showForm = false },
                onExpired = { expired() },
                onSubmitted = {
                    showForm = false
                    scope.launch { snackbarHostState.showSnackbar("请假申请已提交") }
                    load()
                }
            )
        }
        detail?.let { rec ->
            BackHandler { detail = null }
            OverlayDialog(
                show = true,
                title = "请假详情",
                onDismissRequest = { detail = null },
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "${LeaveType.fromCode(rec.leaveType).displayName} · ${leaveStatusLabel(rec.effectiveStatus)}",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${rec.startTime} 至 ${rec.endTime}", style = MiuixTheme.textStyles.body2)
                    if (rec.durationText.isNotBlank()) {
                        Text(rec.durationText, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (rec.reason.isNotBlank()) {
                        Text("事由：${rec.reason}", style = MiuixTheme.textStyles.body2)
                    }
                    rec.approvalNodes.forEach { node ->
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${node.nodeName} · ${leaveStatusLabel(node.status)}",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (node.approverName.isNotBlank()) {
                            Text("审批人 ${node.approverName}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (node.processedTime.isNotBlank()) {
                            Text(node.processedTime, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (node.remark.isNotBlank()) {
                            Text(node.remark, style = MiuixTheme.textStyles.body2)
                        }
                    }
                    rec.evidenceFiles.forEach { file ->
                        Text("附件 ${file.fileName}", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = "关闭", onClick = { detail = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private enum class LeaveAction { WITHDRAW, CANCEL }

@Composable
private fun RecordList(records: List<AttendanceWaterRecord>) {
    if (records.isEmpty()) {
        EmptyState(title = "暂无考勤记录", modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(records, key = { it.sbh }) { record ->
            Card(colors = CardDefaults.defaultColors(color = AppCardColor)) {
                Column(Modifier.padding(16.dp)) {
                    Text(record.courseName, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${record.date} · 第${record.startTime}-${record.endTime}节 · ${record.status.displayName}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    if (record.location.isNotBlank()) {
                        Text(
                            record.location,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 打卡流水：原始刷卡记录，跟"流水"（考勤结果）不是一回事——这里没有课程、
 * 没有考勤状态，只有"什么时候在哪台设备刷了一下、这次刷卡算不算数"。
 */
@Composable
private fun StreamList(streams: List<AttendanceStream>) {
    if (streams.isEmpty()) {
        EmptyState(title = "暂无打卡流水", modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(streams, key = { it.id.ifBlank { it.collectTime + it.location } }) { stream ->
            Card(colors = CardDefaults.defaultColors(color = AppCardColor)) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stream.collectTime.ifBlank { "时间未知" },
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (stream.location.isNotBlank()) {
                            Text(
                                stream.location,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Text(
                        if (stream.effective) "有效" else "无效",
                        style = MiuixTheme.textStyles.body2,
                        color = if (stream.effective) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatList(stats: List<CourseAttendanceStat>) {
    if (stats.isEmpty()) {
        EmptyState(title = "暂无课程考勤统计数据", modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(stats, key = { it.subjectName + it.subjectCode }) { stat ->
            Card(colors = CardDefaults.defaultColors(color = AppCardColor)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stat.subjectName, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(
                        "正常 ${stat.normalCount} · 迟到 ${stat.lateCount} · 缺勤 ${stat.absenceCount} · 请假 ${stat.leaveCount}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaveList(
    leaves: List<LeaveRecord>,
    onOpen: (LeaveRecord) -> Unit,
    onWithdraw: (LeaveRecord) -> Unit,
    onCancel: (LeaveRecord) -> Unit,
) {
    if (leaves.isEmpty()) {
        EmptyState(
            title = "暂无请假记录",
            subtitle = "点右上角加号提交病假或私事假申请",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(leaves, key = { it.leaveId }) { rec ->
            Card(colors = CardDefaults.defaultColors(color = AppCardColor)) {
                Column(
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback(),
                            onClick = { onOpen(rec) },
                        )
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            LeaveType.fromCode(rec.leaveType).displayName,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            leaveStatusLabel(rec.effectiveStatus),
                            style = MiuixTheme.textStyles.body2,
                            color = leaveStatusColor(rec.effectiveStatus),
                        )
                    }
                    Text(
                        "${rec.startTime} 至 ${rec.endTime}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (rec.reason.isNotBlank()) {
                        Text(rec.reason, style = MiuixTheme.textStyles.body2, maxLines = 2)
                    }
                    if (rec.withdrawable || rec.cancellable) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            if (rec.withdrawable) {
                                TextButton(text = "撤回", onClick = { onWithdraw(rec) })
                            }
                            if (rec.cancellable) {
                                TextButton(text = "销假", onClick = { onCancel(rec) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun leaveStatusColor(code: String) = when (code.uppercase()) {
    "APPROVED" -> MiuixTheme.colorScheme.primary
    "REJECTED" -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun LeaveFormDialog(
    leaveApi: LeaveApi,
    window: LeaveSemesterWindow?,
    onDismiss: () -> Unit,
    onExpired: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(LeaveType.PERSONAL) }
    val nextHour = remember {
        LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }
    var startDate by remember { mutableStateOf(nextHour.toLocalDate()) }
    var startHour by remember { mutableIntStateOf(nextHour.hour) }
    var endDate by remember { mutableStateOf(nextHour.toLocalDate()) }
    var endHour by remember { mutableIntStateOf((nextHour.hour + 2).coerceAtMost(23)) }
    var reason by remember { mutableStateOf("") }
    var approvers by remember { mutableStateOf<List<LeaveApprover>>(emptyList()) }
    var approverIndex by remember { mutableIntStateOf(0) }
    var evidence by remember { mutableStateOf<List<Pair<String, ByteArray>>>(emptyList()) }
    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var pickingStartDate by remember { mutableStateOf(false) }
    var pickingEndDate by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val maxDate = remember(window) {
        window?.endDate?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
        } ?: today.plusDays(90)
    }

    LaunchedEffect(type) {
        try {
            val preview = withContext(Dispatchers.IO) { leaveApi.getFlowPreview(type) }
            approvers = preview.nextApprovers
            approverIndex = 0
        } catch (e: AuthExpiredException) {
            onExpired()
        } catch (e: Exception) {
            formError = e.message ?: "审批流程加载失败"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val loaded = uris.mapNotNull { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
            name to bytes
        }
        evidence = evidence + loaded
    }

    BackHandler { onDismiss() }
    OverlayDialog(
        show = true,
        title = "新建请假申请",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "新版考勤系统不支持补假。请假开始时间须为当前之后的整点。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            OverlaySpinnerPreference(
                title = "请假类型",
                items = LeaveType.entries.map { DropdownItem(text = it.displayName) },
                selectedIndex = LeaveType.entries.indexOf(type),
                onSelectedIndexChange = { type = LeaveType.entries[it] }
            )
            ArrowPreference(
                title = "开始日期",
                summary = startDate.toString(),
                onClick = { pickingStartDate = true },
            )
            OverlaySpinnerPreference(
                title = "开始整点",
                items = (0..23).map { DropdownItem(text = "${it}:00") },
                selectedIndex = startHour,
                onSelectedIndexChange = { startHour = it }
            )
            ArrowPreference(
                title = "结束日期",
                summary = endDate.toString(),
                onClick = { pickingEndDate = true },
            )
            OverlaySpinnerPreference(
                title = "结束整点",
                items = (0..23).map { DropdownItem(text = "${it}:00") },
                selectedIndex = endHour,
                onSelectedIndexChange = { endHour = it }
            )
            if (approvers.isNotEmpty()) {
                OverlaySpinnerPreference(
                    title = "下一审批人",
                    items = approvers.map { DropdownItem(text = it.name.ifBlank { it.userId }) },
                    selectedIndex = approverIndex.coerceAtMost(approvers.lastIndex),
                    onSelectedIndexChange = { approverIndex = it }
                )
            }
            TextField(
                value = reason,
                onValueChange = { reason = it },
                label = "请假事由",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            ArrowPreference(
                title = "添加附件",
                summary = if (evidence.isEmpty()) {
                    "病假超过一天需上传书面报告"
                } else {
                    evidence.joinToString { it.first }
                },
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            formError?.let {
                Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = if (submitting) "提交中…" else "提交",
                    enabled = !submitting,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val start = LocalDateTime.of(startDate, LocalTime.of(startHour, 0))
                        val end = LocalDateTime.of(endDate, LocalTime.of(endHour, 0))
                        val now = LocalDateTime.now()
                        val minutes = ChronoUnit.MINUTES.between(start, end)
                        formError = when {
                            !start.isAfter(now) -> "请假时间必须晚于当前时间"
                            start.minute != 0 || end.minute != 0 -> "请假开始和结束时间必须选择整点"
                            !end.isAfter(start) -> "结束时间必须晚于开始时间"
                            reason.isBlank() -> "请填写请假事由"
                            approvers.isEmpty() -> "没有可用审批人"
                            window != null && window.maxStudentLeaveMinutes > 0 && minutes > window.maxStudentLeaveMinutes ->
                                "本次请假超过单次上限"
                            type == LeaveType.SICK && evidence.isEmpty() && minutes >= 24 * 60 ->
                                "病假超过一天必须上传书面请假报告"
                            else -> null
                        }
                        if (formError != null) return@TextButton
                        submitting = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val files = evidence.map { (name, bytes) ->
                                        leaveApi.uploadEvidence(name, guessMime(name), bytes)
                                    }
                                    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                    leaveApi.createLeave(
                                        type = type,
                                        startTime = start.format(fmt),
                                        endTime = end.format(fmt),
                                        reason = reason.trim(),
                                        nextApproverUserId = approvers[approverIndex].userId,
                                        evidenceFiles = files,
                                    )
                                }
                                onSubmitted()
                            } catch (e: AuthExpiredException) {
                                onExpired()
                            } catch (e: Exception) {
                                formError = e.message ?: "请假申请提交失败"
                            } finally {
                                submitting = false
                            }
                        }
                    }
                )
            }
        }
        AppDatePickerDialog(
            show = pickingStartDate,
            title = "开始日期",
            date = startDate,
            minDate = today,
            maxDate = maxOf(maxDate, today),
            onDismiss = { pickingStartDate = false },
            onConfirm = {
                startDate = it
                pickingStartDate = false
            }
        )
        AppDatePickerDialog(
            show = pickingEndDate,
            title = "结束日期",
            date = endDate,
            minDate = today,
            maxDate = maxOf(maxDate, today),
            onDismiss = { pickingEndDate = false },
            onConfirm = {
                endDate = it
                pickingEndDate = false
            }
        )
    }
}

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}
