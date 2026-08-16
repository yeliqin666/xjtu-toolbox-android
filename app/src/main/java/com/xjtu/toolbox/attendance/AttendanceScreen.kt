package com.xjtu.toolbox.attendance

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppInsetColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendanceScreen(
    site: SiteSession,
    onBack: () -> Unit
) {
    val isPostgraduate = site.siteKey == "pg_attendance"
    val appLoginState = LocalAppLoginState.current
    val api = remember(site) { AttendanceApi(site) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var records by remember { mutableStateOf<List<AttendanceWaterRecord>>(emptyList()) }
    var courseStats by remember { mutableStateOf<List<CourseAttendanceStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var studentName by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 学期选择
    var termList by remember { mutableStateOf<List<TermInfo>>(emptyList()) }
    var currentTermBh by remember { mutableStateOf("") }
    var selectedTermBh by rememberSaveable { mutableStateOf("") }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var loadGeneration by remember { mutableIntStateOf(0) }

    // 周次筛选（null = 全部）
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
    // 状态筛选
    var selectedStatus by rememberSaveable { mutableStateOf<WaterType?>(null) }
    // 课程搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 从概览卡片点下钻时锁定到某门课：流水 Tab 自动用此作为过滤
    var drilldownSubject by rememberSaveable { mutableStateOf<String?>(null) }
    val effectiveSubjectFilter = drilldownSubject ?: ""  // 下钻 vs 搜索同时只一个生效

    fun loadData(termBh: String? = null) {
        loadJob?.cancel()
        val myGeneration = ++loadGeneration
        fun ensureLatest() {
            if (myGeneration != loadGeneration) {
                throw kotlinx.coroutines.CancellationException("superseded by newer attendance load")
            }
        }
        isLoading = records.isEmpty()
        errorMessage = null
        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 学校端较脆弱，元数据按顺序取，避免进入页面时瞬间打出三路请求。
                    val info = api.getStudentInfo()
                    ensureLatest()
                    studentName = info["name"] as? String ?: ""

                    val fetchedTerms = api.getTermList()
                    ensureLatest()
                    termList = fetchedTerms

                    if (currentTermBh.isEmpty()) {
                        try {
                            val fetchedCurrentTerm = api.getTermBh()
                            ensureLatest()
                            currentTermBh = fetchedCurrentTerm
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: AuthExpiredException) { throw e }
                        catch (_: Exception) { currentTermBh = "" }
                    }

                    // 确定要查询的学期
                    val bh = termBh ?: currentTermBh
                    if (bh.isNotEmpty()) {
                        selectedTermBh = bh
                    }

                    // 加载考勤记录（历史学期需要日期范围）
                    val isCurrentTerm = termBh == null || bh == currentTermBh
                    val termInfo = termList.firstOrNull { it.bh == bh }
                    val cachedSnapshot = AttendanceCache.load(context, isPostgraduate)
                    val canAppendCurrent = isCurrentTerm &&
                        cachedSnapshot?.selectedTermBh == bh &&
                        cachedSnapshot.records.isNotEmpty()
                    val fetchedRecords = if (canAppendCurrent) {
                        val cachedRecords = cachedSnapshot.records
                        val latestCachedDate = cachedRecords
                            .mapNotNull { runCatching { java.time.LocalDate.parse(it.date) }.getOrNull() }
                            .maxOrNull()
                        val refreshStart = latestCachedDate?.minusDays(2)?.toString().orEmpty()
                        val fresh = api.getWaterRecords(
                            bh.ifEmpty { null },
                            startDate = refreshStart,
                            endDate = java.time.LocalDate.now().toString()
                        )
                        (fresh + cachedRecords)
                            .distinctBy { "${it.sbh}|${it.date}|${it.courseName}|${it.startTime}|${it.location}" }
                            .sortedByDescending { it.date }
                    } else if (!isCurrentTerm && termInfo != null && termInfo.startDate.isNotEmpty()) {
                        api.getWaterRecords(bh, startDate = termInfo.startDate, endDate = termInfo.endDate)
                    } else {
                        api.getWaterRecords(bh.ifEmpty { null })
                    }
                    ensureLatest()
                    records = fetchedRecords

                    // 课程统计：当前学期用 getKqtjCurrentWeek，历史学期用 getKqtjByTime + 回退
                    val fetchedStats = if (isCurrentTerm) {
                        try {
                            api.getKqtjCurrentWeek()
                        } catch (e: AuthExpiredException) {
                            throw e
                        } catch (_: Exception) {
                            api.computeCourseStatsFromRecords(fetchedRecords)
                        }
                    } else {
                        val statsFromApi = try {
                            if (termInfo != null && termInfo.startDate.isNotEmpty() && termInfo.endDate.isNotEmpty()) {
                                api.getKqtjByTime(termInfo.startDate, termInfo.endDate)
                            } else if (fetchedRecords.isNotEmpty()) {
                                val minDate = fetchedRecords.minOf { it.date }
                                val maxDate = fetchedRecords.maxOf { it.date }
                                api.getKqtjByTime(minDate, maxDate)
                            } else {
                                emptyList()
                            }
                        } catch (e: AuthExpiredException) {
                            throw e
                        } catch (_: Exception) { emptyList() }
                        // 3) API 为空则从 records 直接聚合
                        statsFromApi.ifEmpty { api.computeCourseStatsFromRecords(fetchedRecords) }
                    }
                    ensureLatest()
                    courseStats = fetchedStats
                    AttendanceCache.save(
                        context,
                        isPostgraduate,
                        AttendanceSnapshot(
                            studentName = studentName,
                            termList = termList,
                            currentTermBh = currentTermBh,
                            selectedTermBh = selectedTermBh,
                            records = fetchedRecords,
                            courseStats = fetchedStats,
                            savedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.ATTENDANCE, Routes.ATTENDANCE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
                if (records.isNotEmpty()) {
                    snackbarHostState.showSnackbar(
                        "考勤更新失败，当前显示上次缓存的数据",
                        duration = SnackbarDuration.Long
                    )
                }
            } finally {
                if (myGeneration == loadGeneration) {
                    isLoading = false
                }
            }
        }
    }

    fun switchTerm(bh: String) {
        if (bh == selectedTermBh) return
        selectedTermBh = bh
        selectedWeek = null
        selectedStatus = null
        loadData(bh)
    }

    LaunchedEffect(Unit) {
        AttendanceCache.load(context, isPostgraduate)?.let { cached ->
            studentName = cached.studentName
            termList = cached.termList
            currentTermBh = cached.currentTermBh
            selectedTermBh = cached.selectedTermBh
            records = cached.records
            courseStats = cached.courseStats
            isLoading = false
        }
        loadData(selectedTermBh.ifEmpty { null })
    }

    // 派生数据
    val maxWeek = remember(records) { records.maxOfOrNull { it.week } ?: 0 }

    val filteredRecords = remember(records, selectedWeek, selectedStatus, searchQuery, drilldownSubject) {
        records.asSequence()
            .let { seq -> if (selectedWeek != null) seq.filter { it.week == selectedWeek } else seq }
            .let { seq -> if (selectedStatus != null) seq.filter { it.status == selectedStatus } else seq }
            .let { seq ->
                if (drilldownSubject != null) seq.filter { it.courseName == drilldownSubject }
                else if (searchQuery.isNotBlank()) seq.filter {
                    searchQuery.lowercase() in it.courseName.lowercase() ||
                            searchQuery.lowercase() in it.location.lowercase() ||
                            searchQuery.lowercase() in it.teacher.lowercase()
                } else seq
            }
            .sortedByDescending { it.date }
            .toList()
    }

    // 全局统计
    val displayRecords = if (selectedWeek != null) records.filter { it.week == selectedWeek } else records
    val totalNormal = displayRecords.count { it.status == WaterType.NORMAL }
    val totalLate = displayRecords.count { it.status == WaterType.LATE }
    val totalAbsence = displayRecords.count { it.status == WaterType.ABSENCE }
    val totalLeave = displayRecords.count { it.status == WaterType.LEAVE }
    val attendanceRate = if (displayRecords.isNotEmpty())
        (totalNormal + totalLeave) * 100 / displayRecords.size else 100
    val termItems = remember(termList) { termList.map { DropdownItem(text = it.name) } }
    val selectedTermIndex = termList.indexOfFirst { it.bh == selectedTermBh }.coerceAtLeast(0)
    val weekItems = remember(maxWeek) {
        listOf(DropdownItem(text = "全部周次")) +
                (1..maxWeek).map { DropdownItem(text = "第${it}周") }
    }
    val selectedWeekIndex = if (maxWeek > 0) selectedWeek?.coerceIn(1, maxWeek) ?: 0 else 0

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = "考勤查询",
                color = MiuixTheme.colorScheme.surface,
                largeTitle = "考勤查询",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadData(selectedTermBh.ifEmpty { null }) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            LoadingState(message = "加载考勤数据...", modifier = Modifier.fillMaxSize().padding(padding))
        } else if (errorMessage != null && records.isEmpty()) {
            ErrorState(
                message = errorMessage!!,
                onRetry = { loadData() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MiuixTheme.colorScheme.surface)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.defaultColors(color = AppCardColor)
                ) {
                    if (termItems.isNotEmpty()) {
                        OverlaySpinnerPreference(
                            title = "学期",
                            summary = "选择要查询的学期",
                            items = termItems,
                            selectedIndex = selectedTermIndex,
                            onSelectedIndexChange = { index ->
                                termList.getOrNull(index)?.let { switchTerm(it.bh) }
                            }
                        )
                    }
                    if (maxWeek > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        OverlaySpinnerPreference(
                            title = "周次",
                            summary = if (selectedWeek == null) {
                                "查看整个学期"
                            } else {
                                "${displayRecords.size} 条记录"
                            },
                            items = weekItems,
                            selectedIndex = selectedWeekIndex,
                            onSelectedIndexChange = { index ->
                                selectedWeek = index.takeIf { it > 0 }
                            }
                        )
                    }
                }

                TabRowWithContour(
                    tabs = listOf("概览", "流水"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    // 默认轨道色是 surface，和页面背景（同为 surface）同色，轨道等于隐形。
                    // 上一版换成 AppInsetColor（surfaceContainerHigh 半透明）在浅色下够用，
                    // 但深色主题页面背景是纯黑，0xFF242424 半透明叠在纯黑上还是几乎看不出来。
                    // 换成 onBackground.copy(alpha=...)——这是 miuix BreadcrumbBarDefaults 自己
                    // 用的技巧：onBackground 在浅色是黑、深色是近白，同一份 alpha 在两个主题下
                    // 都能相对页面背景做出稳定的浅灰对比，不需要为每个主题单独调值。
                    colors = top.yukonga.miuix.kmp.basic.TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )

                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { direction * it / 4 } + fadeIn(
                            androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)
                        )) togetherWith (slideOutHorizontally { -direction * it / 4 } + fadeOut(
                            androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)
                        ))
                    },
                    label = "attendanceTab"
                ) { tab ->
                    when (tab) {
                        0 -> OverviewTab(courseStats, totalNormal, totalLate,
                            totalAbsence, totalLeave, attendanceRate,
                            searchQuery, { searchQuery = it },
                            { drilldownSubject = it },
                            { searchQuery = "" },
                            { selectedTab = 1 })
                        1 -> RecordFlowTab(filteredRecords, selectedStatus, displayRecords.size,
                            searchQuery, { searchQuery = it }, { selectedStatus = it },
                            drilldownSubject, { drilldownSubject = null })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    courseStats: List<CourseAttendanceStat>,
    totalNormal: Int, totalLate: Int, totalAbsence: Int, totalLeave: Int,
    attendanceRate: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onDrilldownSubject: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSwitchToRecordTab: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 出勤率环形已删：miuix CircularProgressIndicator 的 Canvas 内部按 size 参数
        // （默认 30dp）画图，外层 Modifier.fillMaxSize() 会被内部 .size(size) 覆盖，
        // 手动传 size 才能对齐，试了几次都在文字和圆环之间错位。既然只有百分比数字本身
        // 有用，直接用大号文字表达，不需要绘制一个图形元素来表达一个数字。
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = AppCardColor)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rateColor = when {
                        attendanceRate >= 90 -> MiuixTheme.colorScheme.primary
                        attendanceRate >= 70 -> MiuixTheme.colorScheme.primaryVariant
                        else -> MiuixTheme.colorScheme.error
                    }
                    Column {
                        Text(
                            "${attendanceRate}%",
                            style = MiuixTheme.textStyles.title1,
                            fontWeight = FontWeight.Bold,
                            color = rateColor
                        )
                        Text(
                            "出勤率",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.width(20.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatValue("正常", totalNormal, MiuixTheme.colorScheme.primary, Modifier.weight(1f))
                        StatValue("迟到", totalLate, MiuixTheme.colorScheme.primaryVariant, Modifier.weight(1f))
                        StatValue("缺勤", totalAbsence, MiuixTheme.colorScheme.error, Modifier.weight(1f))
                        StatValue(
                            "请假",
                            totalLeave,
                            MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 按课程统计
        if (courseStats.isNotEmpty()) {
            item {
                Text("按课程统计", style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }

            // 搜索
            item {
                com.xjtu.toolbox.ui.components.AppSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    label = "搜索课程...",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val filtered = courseStats
                .filter { searchQuery.isBlank() || searchQuery.lowercase() in it.subjectName.lowercase() }
                .sortedByDescending { it.abnormalCount }

            items(filtered) { stat ->
                CourseStatCard(stat) {
                    // 下钻：切到流水 Tab，锁定该课程，清掉搜索状态避免混淆
                    onDrilldownSubject(stat.subjectName)
                    onClearSearch()
                    onSwitchToRecordTab()
                }
            }

            if (filtered.all { it.abnormalCount == 0 }) {
                item {
                    top.yukonga.miuix.kmp.basic.Card(
                        Modifier.fillMaxWidth(),
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("所有课程出勤良好", style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        } else {
            item {
                EmptyState(
                    title = "暂无课程考勤统计数据",
                    subtitle = "尝试切换学期或检查网络连接"
                )
            }
        }
    }
}

@Composable
private fun CourseStatCard(stat: CourseAttendanceStat, onClick: () -> Unit = {}) {
    val hasIssue = stat.abnormalCount > 0
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            ),
        cornerRadius = 12.dp,
        // 默认 defaultColors() 用的是 surfaceContainer，深色主题下与页面背景同为 #242424，
        // 卡片同样会糊掉，所以统一走 AppCardColor
        colors = if (hasIssue) top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ) else top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stat.subjectName, style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val rate = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
                val rateColor = when {
                    rate >= 90 -> MiuixTheme.colorScheme.primary
                    rate >= 70 -> MiuixTheme.colorScheme.primaryVariant
                    else -> MiuixTheme.colorScheme.error
                }
                Surface(shape = RoundedCornerShape(6.dp), color = rateColor.copy(alpha = 0.12f)) {
                    Text("${rate}%", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MiuixTheme.textStyles.body2, color = rateColor, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 出勤率进度条
            val rateForBar = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
            val animatedRate by animateFloatAsState(
                targetValue = (rateForBar / 100f).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                label = "attendanceBar"
            )
            val barColor = when {
                rateForBar >= 90 -> MiuixTheme.colorScheme.primary
                rateForBar >= 70 -> MiuixTheme.colorScheme.primaryVariant
                else -> MiuixTheme.colorScheme.error
            }
            LinearProgressIndicator(
                progress = animatedRate,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                // 轨道色不能用 surfaceVariant——那已经是卡片自身的颜色，轨道会看不见
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = barColor, backgroundColor = AppInsetColor)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("正常", stat.normalCount, MiuixTheme.colorScheme.primary)
                StatChip("迟到", stat.lateCount, MiuixTheme.colorScheme.primaryVariant)
                StatChip("缺勤", stat.absenceCount, MiuixTheme.colorScheme.error)
                StatChip("请假", stat.leaveCount, MiuixTheme.colorScheme.onSurfaceVariantSummary)
                StatChip("总计", stat.total, MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else MiuixTheme.colorScheme.outline)
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordFlowTab(
    filteredRecords: List<AttendanceWaterRecord>,
    selectedStatus: WaterType?,
    totalCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onStatusChange: (WaterType?) -> Unit,
    drilldownSubject: String? = null,
    onClearDrilldown: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 下钻来源提示：概览卡片点过来的，醒目地告诉用户为什么只看到一门课
        if (drilldownSubject != null) {
            item {
                Surface(
                    color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.FilterAlt,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已筛选「$drilldownSubject」",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .clickable { onClearDrilldown() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            // 与 Jiaoxiaozhi 复制按钮统一：可点击元素用 primary 突出。
                            Text("清除", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        // 搜索框
        item {
            com.xjtu.toolbox.ui.components.AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange,
                label = "搜索课程、教室、教师...",
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 状态筛选
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = AppCardColor)
            ) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppFilterChip(
                        selected = selectedStatus == null,
                        onClick = { onStatusChange(null) },
                        label = "全部 ($totalCount)",
                        leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(16.dp)) }
                    )
                    WaterType.entries.forEach { type ->
                        AppFilterChip(
                            selected = selectedStatus == type,
                            onClick = { onStatusChange(if (selectedStatus == type) null else type) },
                            label = type.displayName
                        )
                    }
                }
            }
        }

        item {
            Text("${filteredRecords.size} 条记录",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }

        // 按日期分组
        val groupedByDate = filteredRecords.groupBy { it.date }
        groupedByDate.forEach { (date, dayRecords) ->
            item(key = "header_$date") {
                Text(date, style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            itemsIndexed(dayRecords, key = { idx, it -> "${it.date}_${it.startTime}_${it.courseName}_${it.sbh}_$idx" }) { _, record ->
                AttendanceRecordCard(record)
            }
        }

        if (filteredRecords.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无符合条件的记录",
                    subtitle = "请尝试更换周次筛选"
                )
            }
        }
    }
}

@Composable
private fun StatValue(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value.toString(),
            style = MiuixTheme.textStyles.title3,
            color = if (value == 0) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun AttendanceRecordCard(record: AttendanceWaterRecord) {
    val statusColor = when (record.status) {
        WaterType.NORMAL -> MiuixTheme.colorScheme.primary
        WaterType.LATE -> MiuixTheme.colorScheme.primaryVariant
        WaterType.ABSENCE -> MiuixTheme.colorScheme.error
        WaterType.LEAVE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        // 这里原本用 surface —— 而 Scaffold 背景正是 surface，所以这张卡整个没有背景。
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppCardColor)
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(statusColor)
            )
            Row(Modifier.weight(1f).padding(start = 11.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.courseName.ifEmpty { record.location },
                        style = MiuixTheme.textStyles.body1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.courseName.isNotEmpty()) {
                            Text(record.location, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Text("第${record.week}周 · 第${record.startTime}-${record.endTime}节",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (record.teacher.isNotEmpty()) {
                        Text(record.teacher, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                    Text(record.status.displayName, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
