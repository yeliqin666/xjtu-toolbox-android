package com.xjtu.toolbox.schedule

import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.filled.KeyboardArrowDown

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Event
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.AppTopBar
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import androidx.compose.foundation.text.selection.SelectionContainer
import com.xjtu.toolbox.ui.DAY_START_HOUR
import com.xjtu.toolbox.ui.ScheduleGrid
import com.xjtu.toolbox.ui.WeekSelector
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun ScheduleScreen(
    site: SiteSession? = null,
    studentId: String = "",
    onBack: () -> Unit = {},  // 用于 catch AuthExpired 时退出
    showTopBar: Boolean = true,
    showBackButton: Boolean = true,
    onSubtitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?) -> Unit = {},
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {},
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val appLoginState = LocalAppLoginState.current
    var activeSite by remember(site) { mutableStateOf(site) }
    val api = remember(activeSite) { activeSite?.let { ScheduleApi(it) } }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataCache = remember { com.xjtu.toolbox.util.DataCache(context) }
    val gson = remember { com.google.gson.Gson() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Room 数据库 - 自定义课程
    val db = remember { com.xjtu.toolbox.util.AppDatabase.getInstance(context) }
    val customCourseDao = remember { db.customCourseDao() }
    var customCourses by remember { mutableStateOf<List<CustomCourseEntity>>(emptyList()) }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CustomCourseEntity?>(null) }
    var addScheduleDraft by remember { mutableStateOf(CustomCourseDraft()) }

    var courses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var exams by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var textbooks by remember { mutableStateOf<List<TextbookItem>>(emptyList()) }
    var textbooksLoading by remember { mutableStateOf(false) }
    var textbooksError by remember { mutableStateOf<String?>(null) }
    var textbooksLoaded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isSwitching by remember { mutableStateOf(false) }  // 学期切换中（保留旧日程显示）
    var isRefreshingFromNetwork by remember { mutableStateOf(false) } // 缓存已显示，后台刷新中
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 立即从缓存计算当前周（避免打开闪第一周）
    val initialWeek = remember {
        try {
            val cachedTerm = dataCache.get("schedule_last_term", Long.MAX_VALUE)?.trim('"').orEmpty()
            if (cachedTerm.isEmpty()) return@remember 0
            val cachedStart = dataCache.get("start_date_$cachedTerm", Long.MAX_VALUE)?.trim('"').orEmpty()
            if (cachedStart.isEmpty()) return@remember 0
            val startDate = LocalDate.parse(cachedStart)
            val daysBetween = ChronoUnit.DAYS.between(startDate, LocalDate.now())
            val w = ((daysBetween / 7) + 1).toInt()
            if (w in 1..20) w else 0
        } catch (_: Exception) { 0 }
    }
    var currentWeek by rememberSaveable { mutableIntStateOf(if (initialWeek > 0) initialWeek else 1) }
    var realCurrentWeek by remember { mutableIntStateOf(initialWeek) }  // 实际当前周（0=未知），用于时间线显示判断
    val totalWeeks = 20
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var weekNote by remember { mutableStateOf<String?>(null) } // "距开学X周" / "学期已结束"

    // 学期相关
    var termList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTermCode by remember { mutableStateOf("") }
    var currentTermCode by remember { mutableStateOf("") }  // 当前学期，用于判断是否缓存考试
    var termDropdownExpanded by remember { mutableStateOf(false) }

    // 周视图 vs 总览（每次启动默认周视图，不保存状态）
    var showAllWeeks by remember { mutableStateOf(false) }

    // 是否正在显示缓存数据（网络失败时提示）
    var showingStaleData by remember { mutableStateOf(false) }

    // 开学日期（导出 ICS 用）
    var startOfTerm by remember { mutableStateOf<LocalDate?>(null) }
    
    // 法定节假日
    var holidayDates by remember { mutableStateOf<Map<LocalDate, String>>(emptyMap()) }

    // 导出菜单
    var showExportMenu by remember { mutableStateOf(false) }

    // 通知外层（MainScreen TopAppBar）当前学期 / 周次（不含日期范围）
    LaunchedEffect(selectedTab, selectedTermCode, currentWeek, showAllWeeks, weekNote) {
        val subtitle = when {
            selectedTab != 0 -> selectedTermCode
            weekNote != null -> selectedTermCode
            showAllWeeks -> selectedTermCode.takeIf { it.isNotEmpty() }?.let { "$it · 全学期" } ?: "全学期"
            selectedTermCode.isNotEmpty() -> "$selectedTermCode · 第 $currentWeek 周"
            else -> "第 $currentWeek 周"
        }
        onSubtitleChange(subtitle)
    }

    // 初始加载
    fun loadInitialData() {
        isLoading = true
        isRefreshingFromNetwork = false
        errorMessage = null
        showingStaleData = false
        scope.launch {
            try {
                // 未登录态：仅展示缓存，不打断流程
                if (api == null) {
                    withContext(Dispatchers.IO) {
                        // 尝试从缓存中找到最近一个学期的数据
                        val cachedTermList = dataCache.get("schedule_term_list")
                        val cachedTerms = if (cachedTermList != null) {
                            try { gson.fromJson(cachedTermList, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
                        } else emptyList()
                        if (cachedTerms.isNotEmpty()) termList = cachedTerms

                        val termCode = cachedTerms.firstOrNull() ?: ""
                        selectedTermCode = termCode
                        currentTermCode = termCode
                        if (termCode.isNotEmpty()) {
                            try { dataCache.put("schedule_last_term", gson.toJson(termCode)) } catch (_: Exception) {}
                        }

                        if (termCode.isNotEmpty()) {
                            val cached = dataCache.get("schedule_$termCode", Long.MAX_VALUE)  // 离线不限 TTL
                            if (cached != null) {
                                try {
                                    courses = gson.fromJson(cached, Array<CourseItem>::class.java).toList()
                                    android.util.Log.d("ScheduleUI", "Offline: loaded ${courses.size} courses from cache")
                                } catch (_: Exception) {}
                            }
                            val cachedExams = dataCache.get("exams_$termCode", Long.MAX_VALUE)
                            if (cachedExams != null) {
                                try { exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList() } catch (_: Exception) {}
                            }
                            // 从缓存的开学日期计算当前周
                            val cachedStart = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                            if (cachedStart != null) {
                                try {
                                    val startDate = LocalDate.parse(cachedStart.trim('"'))
                                    startOfTerm = startDate
                                    val today = LocalDate.now()
                                    val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                                    val rawWeek = ((daysBetween / 7) + 1).toInt()
                                    if (rawWeek in 1..totalWeeks) realCurrentWeek = rawWeek
                                    currentWeek = rawWeek.coerceIn(1, totalWeeks)
                                } catch (_: Exception) { currentWeek = 1 }
                            }
                            val cachedHolidays = try { HolidayApi.getHolidayDates(context) } catch (_: Exception) { emptyMap() }
                            holidayDates = cachedHolidays
                            val optimizedCourses = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                            if (optimizedCourses != null) {
                                courses = optimizedCourses
                                android.util.Log.d("ScheduleUI", "Offline: loaded ${optimizedCourses.size} optimized courses from cache")
                            } else if (courses.isNotEmpty()) {
                                val filtered = ScheduleCache.filterByHolidays(courses, startOfTerm, cachedHolidays)
                                courses = filtered
                                ScheduleCache.writeOptimizedCourses(dataCache, gson, termCode, filtered)
                            }
                        }

                        if (courses.isEmpty()) {
                            // 无缓存：抛出空状态，由 UI 决定如何提示
                            throw RuntimeException("暂无缓存日程")
                        }
                        showingStaleData = true
                    }
                    return@launch
                }

                // ── 在线模式 ──
                withContext(Dispatchers.IO) {
                    // getCurrentTerm 可能网络失败 → 尝试用缓存学期
                    val termCode = try {
                        api.getCurrentTerm()
                    } catch (e: Exception) {
                        android.util.Log.w("ScheduleUI", "getCurrentTerm failed, trying cache", e)
                        val cachedTermList = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                        val cachedTerms = if (cachedTermList != null) {
                            try { gson.fromJson(cachedTermList, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
                        } else emptyList()
                        cachedTerms.firstOrNull() ?: throw RuntimeException("网络不可用且无缓存学期数据，请连网后重试")
                    }
                    selectedTermCode = termCode
                    currentTermCode = termCode
                    try { dataCache.put("schedule_last_term", gson.toJson(termCode)) } catch (_: Exception) {}

                    val cachedOptimizedCoursesJson = dataCache.get(ScheduleCache.optimizedScheduleKey(termCode), Long.MAX_VALUE)
                    var cachedOptimizedCoursesSize = -1
                    if (cachedOptimizedCoursesJson != null) {
                        try {
                            val parsed = gson.fromJson(cachedOptimizedCoursesJson, Array<CourseItem>::class.java).toList()
                            courses = parsed
                            cachedOptimizedCoursesSize = parsed.size
                            if (parsed.isNotEmpty()) {
                                // 先展示缓存，随后后台轻量刷新
                                isLoading = false
                                isRefreshingFromNetwork = true
                                showingStaleData = true
                            }
                            android.util.Log.d("ScheduleUI", "Loaded optimized courses from cache: ${parsed.size}")
                        } catch (_: Exception) { cachedOptimizedCoursesSize = -1 }
                    } else {
                        val cachedCourses = dataCache.get("schedule_$termCode")
                        if (cachedCourses != null) {
                            try {
                                val parsed = gson.fromJson(cachedCourses, Array<CourseItem>::class.java).toList()
                                courses = parsed
                                if (parsed.isNotEmpty()) {
                                    isLoading = false
                                    isRefreshingFromNetwork = true
                                    showingStaleData = true
                                }
                                android.util.Log.d("ScheduleUI", "Loaded raw courses from cache: ${parsed.size}")
                            } catch (_: Exception) {}
                        }
                    }

                    try {
                        coroutineScope {
                            val coursesDeferred = async { api.getSchedule(termCode) }
                            val examsDeferred = async { api.getExamSchedule(termCode) }
                            val startDateDeferred = async {
                                try { api.getStartOfTerm(termCode) } catch (_: Exception) { null }
                            }
                            val termListDeferred = async {
                                try { api.getTermList() } catch (_: Exception) { emptyList() }
                            }
                            val holidaysDeferred = async {
                                try { HolidayApi.getHolidayDates(context, forceRefresh = true) } catch (_: Exception) { emptyMap() }
                            }

                            val freshCourses = coursesDeferred.await()
                            val freshExams = examsDeferred.await()
                            val startDate = startDateDeferred.await()
                            val fetchedTermList = termListDeferred.await()
                            val freshHolidays = holidaysDeferred.await()
                            val optimizedFreshCourses = ScheduleCache.filterByHolidays(freshCourses, startDate, freshHolidays)

                            val hasUpdate = cachedOptimizedCoursesSize >= 0 && optimizedFreshCourses.size != cachedOptimizedCoursesSize
                            val optimizedFreshCoursesJson = gson.toJson(optimizedFreshCourses)
                            val contentChanged = if (!hasUpdate && cachedOptimizedCoursesSize >= 0) {
                                optimizedFreshCoursesJson != cachedOptimizedCoursesJson
                            } else hasUpdate

                            if (cachedOptimizedCoursesSize < 0 || contentChanged) {
                                courses = optimizedFreshCourses
                            }
                            exams = freshExams
                            holidayDates = freshHolidays
                            showingStaleData = false
                            isRefreshingFromNetwork = false
                            if (fetchedTermList.isNotEmpty()) {
                                termList = fetchedTermList
                                // 缓存学期列表（离线时使用）
                                try { dataCache.put("schedule_term_list", gson.toJson(fetchedTermList)) } catch (_: Exception) {}
                            }

                            try { dataCache.put("schedule_$termCode", gson.toJson(freshCourses)) } catch (_: Exception) {}
                            try { dataCache.put(ScheduleCache.optimizedScheduleKey(termCode), optimizedFreshCoursesJson) } catch (_: Exception) {}
                            // 缓存考试数据（离线时使用）
                            if (freshExams.isNotEmpty()) {
                                try { dataCache.put("exams_$termCode", gson.toJson(freshExams)) } catch (_: Exception) {}
                            }

                            // 缓存开学日期（离线时计算当前周用）
                            if (startDate != null) {
                                startOfTerm = startDate
                                try { dataCache.put("start_date_$termCode", gson.toJson(startDate.toString())) } catch (_: Exception) {}
                            }

                            if (contentChanged) {
                                scope.launch { snackbarHostState.showSnackbar("日程有更新", duration = SnackbarDuration.Short) }
                            }

                            if (startDate != null) {
                                try {
                                    val today = LocalDate.now()
                                    val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                                    val rawWeek = ((daysBetween / 7) + 1).toInt()
                                    if (rawWeek in 1..totalWeeks) realCurrentWeek = rawWeek
                                    val firstTeachWeek = courses.flatMap { c ->
                                        c.weekBits.indices.filter { c.weekBits[it] == '1' }.map { it + 1 }
                                    }.minOrNull()
                                    val notStarted = rawWeek in 1..totalWeeks &&
                                            firstTeachWeek != null && rawWeek < firstTeachWeek
                                    currentWeek = when {
                                        rawWeek <= 0 -> 1
                                        rawWeek > totalWeeks -> { showAllWeeks = true; 1 }
                                        notStarted -> firstTeachWeek
                                        else -> rawWeek
                                    }
                                    weekNote = when {
                                        rawWeek <= 0 -> "距开学还有 ${1 - rawWeek} 周"
                                        rawWeek > totalWeeks -> "学期已结束"
                                        notStarted -> "尚未开课 · 第${firstTeachWeek}周开始上课"
                                        else -> null
                                    }
                                } catch (_: Exception) { currentWeek = 1; weekNote = null }
                            } else {
                                currentWeek = 1; weekNote = null
                            }
                        }
                    } catch (e: Exception) {
                        if (courses.isNotEmpty()) {
                            showingStaleData = true
                            isRefreshingFromNetwork = false
                            // 离线时也尝试加载缓存的学期列表
                            if (termList.isEmpty()) {
                                val cachedTermList = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                                if (cachedTermList != null) {
                                    try { termList = gson.fromJson(cachedTermList, Array<String>::class.java).toList() } catch (_: Exception) {}
                                }
                            }
                            scope.launch { snackbarHostState.showSnackbar("网络异常，显示的可能不是最新数据", duration = SnackbarDuration.Long) }
                            android.util.Log.w("ScheduleUI", "Network failed, showing cached data", e)
                        } else {
                            // ── 在线路径失败且无已加载数据 → 尝试缓存回退 ──
                            android.util.Log.w("ScheduleUI", "Online failed, falling back to cache", e)
                            val cachedTermList = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                            val cachedTerms = if (cachedTermList != null) {
                                try { gson.fromJson(cachedTermList, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
                            } else emptyList()
                            if (cachedTerms.isNotEmpty()) termList = cachedTerms

                            val termCode = if (selectedTermCode.isNotEmpty()) selectedTermCode else cachedTerms.firstOrNull() ?: ""
                            if (termCode.isNotEmpty()) {
                                selectedTermCode = termCode
                                currentTermCode = termCode
                                val optimizedCourses = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                                if (optimizedCourses != null) {
                                    courses = optimizedCourses
                                } else {
                                    val cached = dataCache.get("schedule_$termCode", Long.MAX_VALUE)
                                    if (cached != null) {
                                        try { courses = gson.fromJson(cached, Array<CourseItem>::class.java).toList() } catch (_: Exception) {}
                                    }
                                }
                                val cachedExams = dataCache.get("exams_$termCode", Long.MAX_VALUE)
                                if (cachedExams != null) {
                                    try { exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList() } catch (_: Exception) {}
                                }
                                val cachedStart = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                                if (cachedStart != null) {
                                    try {
                                        val startDate = LocalDate.parse(cachedStart.trim('"'))
                                        startOfTerm = startDate
                                        val today = LocalDate.now()
                                        val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                                        val rawWeek = ((daysBetween / 7) + 1).toInt()
                                        if (rawWeek in 1..totalWeeks) realCurrentWeek = rawWeek
                                        currentWeek = rawWeek.coerceIn(1, totalWeeks)
                                    } catch (_: Exception) { currentWeek = 1 }
                                }
                            }
                            if (courses.isNotEmpty()) {
                                showingStaleData = true
                                isRefreshingFromNetwork = false
                                scope.launch { snackbarHostState.showSnackbar("网络异常 · 显示缓存日程", duration = SnackbarDuration.Long) }
                            } else {
                                throw RuntimeException("网络不可用且无缓存数据，请连网后重试")
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCHEDULE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isRefreshingFromNetwork = false
                ScheduleWidgetUpdater.requestUpdate(context)
            }
        }
    }

    // 懒加载教材（切换到教材 tab 时才加载）
    fun loadTextbooks(termCode: String) {
        if (api == null) { textbooksError = "尚未登录教务系统"; return }
        android.util.Log.d("ScheduleUI", "loadTextbooks called: studentId='$studentId', termCode='$termCode'")
        if (studentId.isBlank()) { textbooksError = "未获取到学号"; return }
        textbooksLoading = true
        textbooksError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ScheduleCache.readTextbooks(dataCache, gson, termCode, Long.MAX_VALUE)?.let { cached ->
                        textbooks = cached.sortedBy { item -> if (item.hasSubstantiveTextbook) 0 else 1 }
                        textbooksLoaded = true
                    }
                    val raw = api.getTextbooks(studentId, termCode)
                    // 排序：有教材的在前，无教材的在后
                    textbooks = raw.sortedBy { item ->
                        if (item.hasSubstantiveTextbook) 0 else 1
                    }
                    ScheduleCache.writeTextbooks(dataCache, gson, termCode, textbooks)
                }
                android.util.Log.d("ScheduleUI", "loadTextbooks done: ${textbooks.size} items")
                textbooksLoaded = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCHEDULE, onBack)
            } catch (e: Exception) {
                android.util.Log.e("ScheduleUI", "loadTextbooks failed", e)
                textbooksError = "教材查询失败: ${e.message}"
            } finally { textbooksLoading = false }
        }
    }

    LaunchedEffect(Unit) {
        android.util.Log.d("ScheduleUI", "ScheduleScreen entered: studentId='\$studentId', online=\${api != null}")
        loadInitialData()
        launch {
            try { holidayDates = HolidayApi.getHolidayDates(context) } catch (_: Exception) {}
        }
    }

    // 小组件/首页进入日程页时，可能先以离线缓存态渲染；
    // 当 JWXT 登录稍后恢复成功后，自动切换为在线刷新，避免长期停留离线视图。
    LaunchedEffect(activeSite) {
        if (activeSite == null) return@LaunchedEffect
        if (isLoading || isSwitching) return@LaunchedEffect
        if (!showingStaleData && errorMessage == null && courses.isNotEmpty()) return@LaunchedEffect

        android.util.Log.d("ScheduleUI", "Login became available, refreshing schedule online")
        loadInitialData()
    }

    var attemptingAutoLogin by remember { mutableStateOf(false) }
    LaunchedEffect(activeSite, appLoginState.hasCredentials) {
        if (activeSite != null) return@LaunchedEffect
        if (!appLoginState.hasCredentials) return@LaunchedEffect
        if (attemptingAutoLogin) return@LaunchedEffect
        // 网络检查
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val online = cm?.activeNetwork != null &&
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) return@LaunchedEffect
        attemptingAutoLogin = true
        try {
            android.util.Log.d("ScheduleUI", "site==null + online + has credentials -> background ensureSite(JWXT)")
            activeSite = withContext(Dispatchers.IO) { appLoginState.sessionManager?.ensureSite(LoginType.JWXT) }
        } catch (_: Exception) {}
        attemptingAutoLogin = false
    }

    // 加载自定义课程（学期变更时刷新）
    LaunchedEffect(selectedTermCode) {
        if (selectedTermCode.isNotEmpty()) {
            customCourses = customCourseDao.getByTerm(selectedTermCode)
        }
    }

    // 合并 API 课程 + 自定义课程
    val mergedCourses = remember(courses, customCourses) {
        courses + customCourses.map { it.toCourseItem() }
    }

    // 剔除命中法定节假日的周次
    val filteredMergedCourses = remember(mergedCourses, startOfTerm, holidayDates) {
        ScheduleCache.filterByHolidays(mergedCourses, startOfTerm, holidayDates)
    }

    // 自定义课程操作
    fun saveCustomCourse(entity: CustomCourseEntity) {
        scope.launch {
            // ★ 修改前排查冲突排查
            val conflicts = customCourseDao.getConflicts(entity.termCode, entity.dayOfWeek, entity.startSection, entity.endSection)
            val realConflicts = conflicts.filter { it.id != entity.id } // 排除自己自身
            if (realConflicts.isNotEmpty()) {
                val conflictNames = realConflicts.joinToString("、") { it.courseName }
                // 为了简单展示，我们这里直接先将碰撞的旧课程删除
                android.util.Log.d("ScheduleUI", "Deleting conflicts: \$conflictNames")
                realConflicts.forEach {
                    customCourseDao.delete(it)
                }
                snackbarHostState.showSnackbar("已自动清除时间冲突的课程(\$conflictNames)", duration = SnackbarDuration.Short)
            }
            
            if (entity.id == 0L) customCourseDao.insert(entity) else customCourseDao.update(entity)
            customCourses = customCourseDao.getByTerm(selectedTermCode)
            ScheduleWidgetUpdater.requestUpdate(context)
            snackbarHostState.showSnackbar(if (entity.id == 0L) "已添加日程" else "已更新日程", duration = SnackbarDuration.Short)
        }
    }
    fun deleteCustomCourse(entity: CustomCourseEntity) {
        scope.launch {
            customCourseDao.delete(entity)
            customCourses = customCourseDao.getByTerm(selectedTermCode)
            ScheduleWidgetUpdater.requestUpdate(context)
            snackbarHostState.showSnackbar("已删除「${entity.courseName}」", duration = SnackbarDuration.Short)
        }
    }

    // 自定义课程弹窗
    val showAddCourseState = remember { mutableStateOf(false) }
    LaunchedEffect(showAddCourseDialog) { showAddCourseState.value = showAddCourseDialog }
    if (showAddCourseDialog) {
        CustomCourseDialog(
            show = showAddCourseState,
            termCode = selectedTermCode,
            totalWeeks = totalWeeks,
            draft = addScheduleDraft,
            onAutoSave = { addScheduleDraft = it },
            onSave = {
                saveCustomCourse(it)
                addScheduleDraft = CustomCourseDraft()
            },
            onDismiss = { showAddCourseDialog = false }
        )
    }
    editingCourse?.let { entity ->
        val showEditCourseState = remember { mutableStateOf(true) }
        CustomCourseDialog(
            show = showEditCourseState,
            existing = entity,
            termCode = selectedTermCode,
            totalWeeks = totalWeeks,
            onSave = ::saveCustomCourse,
            onDelete = ::deleteCustomCourse,
            onDismiss = { editingCourse = null }
        )
    }

    // 安全触发: 当 Tab 已在教材且数据未加载时自动加载
    // 关键修复：把 api 也加入 key。
    // 之前只用 selectedTab/selectedTermCode/textbooksLoaded —— 当用户首次切到「教材」tab 时
    // jwxtLogin == null → api == null → loadTextbooks 立即报错；之后即使 jwxtLogin 异步登好
    // 让 api 从 null 变 non-null，LaunchedEffect 因 key 没变也不会重启 → 教材永远不刷新。
    // 只有关闭 App 重开（jwxtLogin 启动时已就绪）才能首次成功——这就是「关掉重开就好」的根因。
    LaunchedEffect(selectedTab, selectedTermCode, textbooksLoaded, api) {
        if (selectedTab == 2 && api != null && !textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) {
            android.util.Log.d("ScheduleUI", "LaunchedEffect auto-loading textbooks: term=$selectedTermCode (api just became ready)")
            // api 刚变非空时之前可能设了「尚未登录」错误，要清掉再加载
            textbooksError = null
            loadTextbooks(selectedTermCode)
        }
    }

    // 切换学期
    fun switchTerm(newTermCode: String) {
        if (newTermCode == selectedTermCode) return
        selectedTermCode = newTermCode
        try { dataCache.put("schedule_last_term", gson.toJson(newTermCode)) } catch (_: Exception) {}
        textbooksLoaded = false
        textbooks = emptyList()
        showingStaleData = false
        scope.launch {
            isSwitching = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    val isOldTerm = newTermCode != currentTermCode

                    // 先尝试从缓存加载
                    val cachedOptimizedCourses = ScheduleCache.readOptimizedCourses(dataCache, gson, newTermCode, Long.MAX_VALUE)
                    val cachedExams = dataCache.get("exams_$newTermCode", Long.MAX_VALUE)
                    if (cachedOptimizedCourses != null) {
                        courses = cachedOptimizedCourses
                        if (cachedExams != null) {
                            try { exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList() } catch (_: Exception) {}
                        }
                        android.util.Log.d("ScheduleUI", "Optimized term from cache: $newTermCode")
                    } else {
                        val cachedCourses = dataCache.get("schedule_$newTermCode", Long.MAX_VALUE)
                        if (cachedCourses != null) {
                        try {
                            courses = gson.fromJson(cachedCourses, Array<CourseItem>::class.java).toList()
                            if (cachedExams != null) exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList()
                            android.util.Log.d("ScheduleUI", "Term from cache: $newTermCode")
                        } catch (_: Exception) {}
                        }
                    }

                    // 在线时更新
                    if (api != null) {
                        try {
                            val freshCourses = api.getSchedule(newTermCode)
                            exams = api.getExamSchedule(newTermCode)
                            val freshStartDate = try { api.getStartOfTerm(newTermCode) } catch (_: Exception) { null }
                            val freshHolidays = try { HolidayApi.getHolidayDates(context, forceRefresh = true) } catch (_: Exception) { emptyMap() }
                            holidayDates = freshHolidays
                            courses = ScheduleCache.filterByHolidays(freshCourses, freshStartDate, freshHolidays)
                            // 缓存
                            if (isOldTerm) {
                                try {
                                    dataCache.put("schedule_$newTermCode", gson.toJson(freshCourses))
                                    ScheduleCache.writeOptimizedCourses(dataCache, gson, newTermCode, courses)
                                    dataCache.put("exams_$newTermCode", gson.toJson(exams))
                                    if (freshStartDate != null) {
                                        dataCache.put("start_date_$newTermCode", gson.toJson(freshStartDate.toString()))
                                    }
                                } catch (_: Exception) {}
                            }
                            if (freshStartDate != null) startOfTerm = freshStartDate
                        } catch (e: Exception) {
                            if (courses.isEmpty()) throw e
                            showingStaleData = true
                            scope.launch { snackbarHostState.showSnackbar("网络异常，显示缓存数据", duration = SnackbarDuration.Short) }
                        }
                    } else {
                        showingStaleData = true
                    }

                    // 计算当前周
                    try {
                        val startDate = if (api != null) {
                            try { api.getStartOfTerm(newTermCode) } catch (_: Exception) { null }
                        } else {
                            val cs = dataCache.get("start_date_$newTermCode", Long.MAX_VALUE)
                            if (cs != null) try { LocalDate.parse(cs.trim('"')) } catch (_: Exception) { null } else null
                        }
                        if (startDate != null) {
                            startOfTerm = startDate
                            if (api != null) try { dataCache.put("start_date_$newTermCode", gson.toJson(startDate.toString())) } catch (_: Exception) {}
                            val today = LocalDate.now()
                            val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                            val rawWeek = ((daysBetween / 7) + 1).toInt()
                            val firstTeachWeek = courses.flatMap { c ->
                                c.weekBits.indices.filter { c.weekBits[it] == '1' }.map { it + 1 }
                            }.minOrNull()
                            val notStarted = rawWeek in 1..totalWeeks &&
                                    firstTeachWeek != null && rawWeek < firstTeachWeek
                            currentWeek = when {
                                rawWeek <= 0 -> 1
                                rawWeek > totalWeeks -> { showAllWeeks = true; 1 }
                                notStarted -> firstTeachWeek
                                else -> rawWeek
                            }
                            weekNote = when {
                                rawWeek <= 0 -> "距开学还有 ${1 - rawWeek} 周"
                                rawWeek > totalWeeks -> "学期已结束"
                                notStarted -> "尚未开课 · 第${firstTeachWeek}周开始上课"
                                else -> null
                            }
                        } else {
                            currentWeek = 1; weekNote = null
                        }
                    } catch (_: Exception) { currentWeek = 1; weekNote = null }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCHEDULE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isSwitching = false
                ScheduleWidgetUpdater.requestUpdate(context)
            }
        }
    }

    // 注入 TopAppBar actions：[+] [⋮] 两个独立按钮
    val headerActionsContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = {
        // 添加日程（独立按钮）
        if (selectedTab == 0) {
            IconButton(
                onClick = { showAddCourseDialog = true },
                enabled = selectedTermCode.isNotEmpty()
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加日程")
            }
        }
        Box {
            IconButton(onClick = { showExportMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            // 主菜单
            OverlayListPopup(
                show = showExportMenu,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { showExportMenu = false }
            ) {
                ListPopupColumn {
                    if (selectedTab == 0) {
                        ScheduleMenuRow(
                            icon = if (showAllWeeks) Icons.Default.DateRange else Icons.Default.CalendarMonth,
                            text = if (showAllWeeks) "切到每周视图" else "切到全学期总览",
                            onClick = { showExportMenu = false; showAllWeeks = !showAllWeeks }
                        )
                    }
                    if (termList.isNotEmpty()) {
                        ScheduleMenuRow(
                            icon = Icons.Default.SwapHoriz,
                            text = "切换学期",
                            onClick = { showExportMenu = false; termDropdownExpanded = true }
                        )
                    }
                    ScheduleMenuRow(
                        icon = Icons.Default.Event,
                        text = "导出日历 (ICS)",
                        onClick = {
                            showExportMenu = false
                            val st = startOfTerm
                            if (st == null) {
                                scope.launch { snackbarHostState.showSnackbar("无法获取开学日期，ICS 导出不可用") }
                                return@ScheduleMenuRow
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar("正在获取法定节假日信息并导出...", duration = SnackbarDuration.Short)
                                try {
                                    val holidays = HolidayApi.getHolidayDates(context).keys
                                    val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, selectedTermCode, holidays)
                                    ScheduleExport.shareTextFile(context, ics, "${selectedTermCode}_日程.ics", "text/calendar")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("节假日获取失败，退回普通导出: ${e.message}", duration = SnackbarDuration.Short)
                                    val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, selectedTermCode, emptySet())
                                    ScheduleExport.shareTextFile(context, ics, "${selectedTermCode}_日程.ics", "text/calendar")
                                }
                            }
                        }
                    )
                }
            }
            // 学期切换 popup（独立，由"切换学期"菜单项触发）
            val termSelectedIdxTb = termList.indexOf(selectedTermCode).coerceAtLeast(0)
            OverlayListPopup(
                show = termDropdownExpanded,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { termDropdownExpanded = false }
            ) {
                ListPopupColumn {
                    termList.forEachIndexed { idx, term ->
                        DropdownImpl(
                            text = term,
                            optionSize = termList.size,
                            isSelected = idx == termSelectedIdxTb,
                            onSelectedIndexChange = {
                                termDropdownExpanded = false
                                switchTerm(term)
                            },
                            index = idx
                        )
                    }
                }
            }
        }
    }
    val headerBottomContent: (@Composable () -> Unit) = {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = MiuixTheme.colorScheme.surfaceContainer
        ) {
            TabRowWithContour(
                tabs = listOf("日程", "考试", "教材"),
                selectedTabIndex = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    if (tab == 2) {
                        if (!textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
    DisposableEffect(showTopBar) {
        if (!showTopBar) {
            onActionsChange(headerActionsContent)
            onBottomContentChange(headerBottomContent)
        }
        onDispose {
            onActionsChange(null)
            onBottomContentChange(null)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showTopBar) {
                val weekDateLabel = remember(startOfTerm, currentWeek) {
                    val st = startOfTerm
                    if (st != null && currentWeek > 0 && !showAllWeeks) {
                        val monday = st.plusWeeks((currentWeek - 1).toLong())
                        val sunday = monday.plusDays(6)
                        "${monday.monthValue}/${monday.dayOfMonth}-${sunday.monthValue}/${sunday.dayOfMonth}"
                    } else null
                }
                val computedSubtitle = when {
                    selectedTab != 0 -> selectedTermCode.takeIf { it.isNotEmpty() } ?: ""
                    weekNote != null -> selectedTermCode.takeIf { it.isNotEmpty() } ?: ""
                    showAllWeeks -> "${selectedTermCode} · 全学期"
                    weekDateLabel != null -> "${selectedTermCode} · 第 $currentWeek 周 · $weekDateLabel"
                    selectedTermCode.isNotEmpty() -> "${selectedTermCode} · 第 $currentWeek 周"
                    else -> "第 $currentWeek 周"
                }
                SmallTopAppBar(
                    title = "日程",
                    subtitle = computedSubtitle,
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                        }
                    },
                    actions = {
                        // 学期切换（仅多个学期时显示）
                        if (termList.size > 1) {
                            Box {
                                IconButton(onClick = { termDropdownExpanded = true }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "切换学期")
                                }
                                val termSelectedIdx = termList.indexOf(selectedTermCode).coerceAtLeast(0)
                                OverlayListPopup(
                                    show = termDropdownExpanded,
                                    alignment = PopupPositionProvider.Align.End,
                                    onDismissRequest = { termDropdownExpanded = false }
                                ) {
                                    ListPopupColumn {
                                        termList.forEachIndexed { idx, term ->
                                            DropdownImpl(
                                                text = term,
                                                optionSize = termList.size,
                                                isSelected = idx == termSelectedIdx,
                                                onSelectedIndexChange = {
                                                    termDropdownExpanded = false
                                                    switchTerm(term)
                                                },
                                                index = idx
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 模式切换（仅日程 tab）
                        if (selectedTab == 0) {
                            IconButton(onClick = { showAllWeeks = !showAllWeeks }) {
                                Icon(
                                    if (showAllWeeks) Icons.Default.DateRange else Icons.Default.CalendarMonth,
                                    contentDescription = if (showAllWeeks) "切到每周" else "切到总览"
                                )
                            }
                        }
                        // 导出菜单
                        Box {
                            IconButton(onClick = { showExportMenu = true }, enabled = filteredMergedCourses.isNotEmpty()) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            AppDropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                                if (termList.size > 1) {
                                    AppDropdownMenuItem(
                                        text = { Text("切换学期") },
                                        leadingIcon = { Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp)) },
                                        onClick = {
                                            showExportMenu = false
                                            termDropdownExpanded = true
                                        }
                                    )
                                }
                                AppDropdownMenuItem(
                                    text = { Text("导出日历 (ICS)") },
                                    leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(20.dp)) },
                                    onClick = {
                                        showExportMenu = false
                                        val st = startOfTerm
                                        if (st == null) {
                                            scope.launch { snackbarHostState.showSnackbar("无法获取开学日期，ICS 导出不可用") }
                                            return@AppDropdownMenuItem
                                        }
                                        val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, selectedTermCode)
                                        ScheduleExport.shareTextFile(context, ics, "${selectedTermCode}_日程.ics", "text/calendar")
                                    }
                                )
                                AppDropdownMenuItem(
                                    text = { Text("导出表格 (CSV)") },
                                    leadingIcon = { Icon(Icons.Default.TableChart, null, Modifier.size(20.dp)) },
                                    onClick = {
                                        showExportMenu = false
                                        val csv = ScheduleExport.generateCsv(filteredMergedCourses)
                                        ScheduleExport.shareTextFile(context, csv, "${selectedTermCode}_日程.csv", "text/csv")
                                    }
                                )
                                AppDropdownMenuItem(
                                    text = { Text("导出图片") },
                                    leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(20.dp)) },
                                    onClick = {
                                        showExportMenu = false
                                        scope.launch {
                                            try {
                                                val bitmap = ScheduleExport.renderScheduleBitmap(
                                                    filteredMergedCourses, currentWeek, selectedTermCode, showAllWeeks
                                                )
                                                ScheduleExport.shareBitmap(context, bitmap, "${selectedTermCode}_第${currentWeek}周日程.png")
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar("图片导出失败: ${e.message}")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        val contentPadding = if (showTopBar) padding else PaddingValues(0.dp)
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(MiuixTheme.colorScheme.surface)
        ) {
            // 嵌入式 header 已迁移到 MainScreen TopAppBar.actions / bottomContent slot

            if (isLoading) {
                LoadingState(message = "\u52a0\u8f7d\u65e5\u7a0b...", modifier = Modifier.fillMaxSize())
            } else if (errorMessage != null) {
                ErrorState(
                    message = errorMessage!!,
                    onRetry = {
                        // 用户主动重试：interactive=true 让 MFA 弹窗能正常工作（不被背景策略跳过）
                        if (activeSite == null && appLoginState.hasCredentials) {
                            scope.launch {
                                attemptingAutoLogin = true
                                errorMessage = null
                                isLoading = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        activeSite = appLoginState.sessionManager?.ensureSite(LoginType.JWXT)
                                    }
                                } catch (_: Exception) {}
                                attemptingAutoLogin = false
                                if (activeSite == null) loadInitialData()
                            }
                        } else if (activeSite != null) {
                            scope.launch {
                                errorMessage = null
                                isLoading = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        appLoginState.sessionManager?.credentials?.let { creds ->
                                            activeSite?.ensureLogin(creds.first, creds.second, force = true)
                                        }
                                    }
                                } catch (_: Exception) {}
                                loadInitialData()
                            }
                        } else {
                            loadInitialData()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                if (isSwitching || isRefreshingFromNetwork) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        height = 2.dp
                    )
                }
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn() + slideInHorizontally { if (targetState > initialState) it else -it } togetherWith
                                fadeOut() + slideOutHorizontally { if (targetState > initialState) -it else it }
                    }, label = "tabSwitch"
                ) { tab ->
                    when (tab) {
                        0 -> ScheduleTabContent(
                            courses = filteredMergedCourses,
                            currentWeek = currentWeek,
                            totalWeeks = totalWeeks,
                            showAllWeeks = showAllWeeks,
                            weekNote = weekNote,
                            realCurrentWeek = realCurrentWeek,
                            selectedTermCode = selectedTermCode,
                            startOfTerm = startOfTerm,
                            currentTermCode = currentTermCode,
                            onWeekChange = { currentWeek = it },
                            onToggleMode = { showAllWeeks = !showAllWeeks },
                            holidayDates = holidayDates,
                            customCourses = customCourses,
                            onEditCustomCourse = { editingCourse = it }
                        )
                        1 -> ExamTabContent(exams, contentBottomPadding)
                        2 -> TextbookTabContent(
                            textbooks = textbooks,
                            isLoading = textbooksLoading,
                            error = textbooksError,
                            onRetry = {
                                if (api == null && appLoginState.hasCredentials) {
                                    scope.launch {
                                        textbooksError = null
                                        attemptingAutoLogin = true
                                        try {
                                            withContext(Dispatchers.IO) {
                                                activeSite = appLoginState.sessionManager?.ensureSite(LoginType.JWXT)
                                            }
                                        } catch (_: Exception) {}
                                        attemptingAutoLogin = false
                                        if (selectedTermCode.isNotEmpty() && activeSite != null) {
                                            loadTextbooks(selectedTermCode)
                                        }
                                    }
                                } else if (selectedTermCode.isNotEmpty()) {
                                    loadTextbooks(selectedTermCode)
                                }
                            },
                            bottomPadding = contentBottomPadding
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleTabContent(
    courses: List<CourseItem>, currentWeek: Int, totalWeeks: Int,
    showAllWeeks: Boolean, weekNote: String? = null,
    realCurrentWeek: Int = 0, selectedTermCode: String = "", startOfTerm: java.time.LocalDate? = null, currentTermCode: String = "",
    onWeekChange: (Int) -> Unit, onToggleMode: () -> Unit, onAddSchedule: () -> Unit = {},
    customCourses: List<CustomCourseEntity> = emptyList(),
    holidayDates: Map<java.time.LocalDate, String> = emptyMap(),
    onEditCustomCourse: (CustomCourseEntity) -> Unit = {}
) {
    val allNames = remember(courses) { courses.map { it.courseName }.distinct().sorted() }
    var selectedCourse by remember { mutableStateOf<CourseItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 学期状态提示（未开学/已结束）
        if (weekNote != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    weekNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        // 主体：每周用 Pager 横滑切周；总览单页
        if (!showAllWeeks) {
            // realCurrentWeek 加载完后重建 Pager，让 initialPage 正确停在当前周
            key(realCurrentWeek) {
            val pagerState = rememberPagerState(initialPage = (currentWeek - 1).coerceAtLeast(0), pageCount = { totalWeeks })
            // currentWeek -> pager（仅在用户没正在拖拽时同步）
            LaunchedEffect(currentWeek) {
                val target = (currentWeek - 1).coerceIn(0, totalWeeks - 1)
                if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                    pagerState.scrollToPage(target)
                }
            }
            // pager -> currentWeek（仅在用户拖拽稳定后触发，避免初始化 emit 覆盖）
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .drop(1)
                    .collect { page ->
                        val w = page + 1
                        if (w != currentWeek) onWeekChange(w)
                    }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val weekN = page + 1
                val weekCourses = remember(courses, weekN) { courses.filter { it.isInWeek(weekN) } }
                val weekDates = remember(startOfTerm, weekN) {
                    if (startOfTerm != null && weekN > 0) {
                        val monday = startOfTerm.plusWeeks((weekN - 1).toLong())
                        (0..6).map { monday.plusDays(it.toLong()) }
                    } else null
                }
                if (weekCourses.isEmpty() && courses.isNotEmpty()) {
                    EmptyState(
                        title = "本周无日程",
                        subtitle = "第${weekN}周还没有安排",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ScheduleGrid(
                        weekCourses, allNames,
                        isCurrentWeek = (weekN == realCurrentWeek && selectedTermCode == currentTermCode),
                        weekDates = weekDates,
                        holidayNames = holidayDates,
                        enableCompression = true,
                        weekKey = weekN,
                        onSlotClick = { item ->
                            val course = item as? CourseItem ?: return@ScheduleGrid
                            val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                            if (customEntity != null) onEditCustomCourse(customEntity)
                            else selectedCourse = course
                        }
                    )
                }
            }
            } // close key(realCurrentWeek)
        } else {
            ScheduleGrid(
                courses, allNames,
                showWeeks = true,
                enableCompression = true,
                onSlotClick = { item ->
                    val course = item as? CourseItem ?: return@ScheduleGrid
                    val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                    if (customEntity != null) onEditCustomCourse(customEntity)
                    else selectedCourse = course
                }
            )
        }

    }

    // 课程详情弹窗（仅普通课程；自定义课程已在点击时分流到编辑弹窗）
    selectedCourse?.let { course ->
        val showCourseDetail = remember(course) { mutableStateOf(true) }
        CourseDetailDialog(show = showCourseDetail, course = course, onDismiss = { selectedCourse = null })
    }
}

@Composable
private fun ScheduleMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceContainer)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceContainer)
    }
}

@Composable
private fun CourseDetailDialog(show: MutableState<Boolean>, course: CourseItem, onDismiss: () -> Unit) {
    BackHandler(enabled = show.value) { show.value = false; onDismiss() }
    val isAgenda = course.courseType == "日程"
    OverlayBottomSheet(
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() }
    ) {
        // 异步获取教室座位数
        var seatCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(course.location) {
            if (course.location.isNotEmpty()) {
                seatCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { com.xjtu.toolbox.emptyroom.EmptyRoomApi().getRoomSeatCount(course.location) } catch (_: Exception) { null }
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                course.courseName,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (course.teacher.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isAgenda) "参与人: ${course.teacher}" else "教师: ${course.teacher}", style = MiuixTheme.textStyles.body2)
                }
            }
            if (course.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(if (isAgenda) "地点: ${course.location}" else "教室: ${course.location}", style = MiuixTheme.textStyles.body2)
                    }
                    if (seatCount != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "${seatCount}座",
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primaryVariant)
                Spacer(Modifier.width(8.dp))
                val dayName = when (course.dayOfWeek) {
                    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
                    5 -> "五"; 6 -> "六"; 7 -> "日"; else -> "?"
                }
                if (isAgenda) {
                    val startMinutes = if (course.startMinuteOfDay >= DAY_START_HOUR * 60) {
                        course.startMinuteOfDay
                    } else {
                        (DAY_START_HOUR + course.startSection - 1) * 60
                    }
                    val endMinutes = if (course.endMinuteOfDay > startMinutes) {
                        course.endMinuteOfDay
                    } else {
                        (DAY_START_HOUR + course.endSection) * 60
                    }
                    val startHour = (startMinutes / 60).coerceIn(0, 23)
                    val startMinute = (startMinutes % 60).coerceIn(0, 59)
                    val endHourRaw = endMinutes / 60
                    val endMinuteRaw = endMinutes % 60
                    val endHour = if (endHourRaw >= 24) 0 else endHourRaw
                    val endMinute = if (endHourRaw >= 24) 0 else endMinuteRaw
                    val endLabel = if (endHourRaw >= 24) "次日00:00" else "%02d:%02d".format(endHour, endMinute)
                    Text(
                        "星期$dayName  %02d:%02d-$endLabel".format(startHour, startMinute),
                        style = MiuixTheme.textStyles.body2
                    )
                } else {
                    Text("星期$dayName  第${course.startSection}-${course.endSection}节", style = MiuixTheme.textStyles.body2)
                }
            }
            val weeks = course.getWeeks()
            if (weeks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.DateRange, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("周次: ${formatWeeks(weeks)}", style = MiuixTheme.textStyles.body2)
                }
            }
            if (course.courseType.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(8.dp))
                    Text("类型: ${course.courseType}", style = MiuixTheme.textStyles.body2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { show.value = false; onDismiss() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("知道了")
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 格式化周次：[1,2,3,5,7,8,9] → "1-3, 5, 7-9" */
private fun formatWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.sorted()
    val ranges = mutableListOf<String>()
    var start = sorted[0]; var end = sorted[0]
    for (i in 1 until sorted.size) {
        if (sorted[i] == end + 1) { end = sorted[i] }
        else {
            ranges.add(if (start == end) "$start" else "$start-$end")
            start = sorted[i]; end = sorted[i]
        }
    }
    ranges.add(if (start == end) "$start" else "$start-$end")
    return ranges.joinToString(", ")
}

@Composable
private fun ExamTabContent(exams: List<ExamItem>, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    // 去重（同一门课+同一天只显示一次）
    val uniqueExams = exams.distinctBy { "${it.courseName}_${it.examDate}" }
    if (uniqueExams.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无考试安排", style = MiuixTheme.textStyles.body1)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + bottomPadding)) {
        items(uniqueExams) { exam -> ExamCard(exam) }
    }
}

@Composable
private fun ExamCard(exam: ExamItem) {
    // 判断已考/今天/未考
    val today = java.time.LocalDate.now()
    val examLocalDate = try {
        val dateStr = exam.examDate.replace("年", "-").replace("月", "-").replace("日", "").trim()
        java.time.LocalDate.parse(dateStr)
    } catch (_: Exception) { null }
    val isPast = examLocalDate != null && examLocalDate.isBefore(today)
    val isToday = examLocalDate != null && examLocalDate.isEqual(today)
    val daysUntil = examLocalDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }

    val accentColor = when {
        isToday -> MiuixTheme.colorScheme.error
        isPast -> MiuixTheme.colorScheme.outline
        else -> MiuixTheme.colorScheme.primary
    }

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = if (isPast) MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MiuixTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.fillMaxWidth()) {
            // ── 左侧：日历日期 ──
            if (examLocalDate != null) {
                Surface(
                    modifier = Modifier.width(72.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = accentColor.copy(alpha = if (isPast) 0.12f else 0.18f)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "${examLocalDate.monthValue}月",
                            style = MiuixTheme.textStyles.footnote1,
                            color = accentColor,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${examLocalDate.dayOfMonth}",
                            style = MiuixTheme.textStyles.title4,
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                        val dayOfWeek = when (examLocalDate.dayOfWeek.value) {
                            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
                            5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> ""
                        }
                        Text(
                            dayOfWeek,
                            style = MiuixTheme.textStyles.footnote1,
                            color = accentColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── 右侧：考试信息 ──
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 14.dp)) {
                // 课程名 + 状态
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        exam.courseName.ifEmpty { "未知课程" },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                        color = if (isPast) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    // 状态标签
                    val (label, badgeColor) = when {
                        isToday -> "今天" to MiuixTheme.colorScheme.error
                        daysUntil != null && daysUntil in 1..7 -> "${daysUntil}天后" to MiuixTheme.colorScheme.primaryVariant
                        isPast -> "已结束" to MiuixTheme.colorScheme.outline
                        daysUntil != null && daysUntil > 7 -> "${daysUntil}天后" to MiuixTheme.colorScheme.primary
                        else -> "" to MiuixTheme.colorScheme.outline
                    }
                    if (label.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 时间 + 地点 紧凑排列
                val infoColor = if (isPast) MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary

                if (exam.examTime.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(14.dp), tint = infoColor)
                        Spacer(Modifier.width(4.dp))
                        Text(exam.examTime, style = MiuixTheme.textStyles.footnote1, color = infoColor)
                    }
                    Spacer(Modifier.height(3.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, Modifier.size(14.dp), tint = infoColor)
                    Spacer(Modifier.width(4.dp))
                    Text(exam.location.ifEmpty { "待定" }, style = MiuixTheme.textStyles.footnote1, color = infoColor)
                    if (exam.seatNumber.isNotEmpty()) {
                        Text("  ·  座位 ${exam.seatNumber}", style = MiuixTheme.textStyles.footnote1, color = infoColor, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ═══════════════════════ 教材 Tab ═══════════════════════

@Composable
private fun TextbookTabContent(
    textbooks: List<TextbookItem>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    when {
        isLoading -> LoadingState(message = "查询教材信息...", modifier = Modifier.fillMaxSize())
        error != null -> ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize())
        textbooks.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("暂无教材信息", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    Text("本学期可能未录入教材数据", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.outline)
                }
            }
        }
        else -> {
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + bottomPadding)
            ) {
                items(textbooks) { item -> TextbookCard(item) }
            }
        }
    }
}

@Composable
private fun TextbookCard(item: TextbookItem) {
    val hasTextbook = item.hasSubstantiveTextbook
    val scheme = MiuixTheme.colorScheme

    // 清洗占位符数据
    val author = item.author.trim().takeIf { it.length >= 2 } ?: ""
    val isbn = item.isbn.trim().takeIf { !it.startsWith("978000000000") } ?: ""
    val bookName = item.textbookName.trim().takeIf { it.length >= 2 } ?: ""
    val pubParts = buildList {
        if (item.publisher.isNotBlank()) add(item.publisher)
        if (item.edition.isNotBlank()) add(item.edition)
    }

    val accentColor = if (hasTextbook) scheme.primary else scheme.outline

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = scheme.surfaceVariant)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 左侧 4dp 色条
                    drawRect(
                        color = accentColor.copy(alpha = if (hasTextbook) 0.6f else 0.2f),
                        size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)
                    )
                }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
        ) {
            // ── 课程名行 ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (hasTextbook) scheme.primary else scheme.outline
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    item.courseName.ifEmpty { "未知课程" },
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    color = if (hasTextbook) scheme.onSurface else scheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.price.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = scheme.tertiaryContainer
                    ) {
                        Text(
                            "¥${item.price}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (!hasTextbook) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "暂无教材信息",
                    style = MiuixTheme.textStyles.footnote1,
                    color = scheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 30.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
                // ── 教材信息区 ──
                Column(Modifier.padding(start = 30.dp)) {
                    // 书名
                    if (bookName.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                "《${bookName}》",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface
                            )
                        }
                    }

                    // 作者 · 出版信息 合并为一行
                    val metaInfo = buildList {
                        if (author.isNotEmpty()) add(author)
                        addAll(pubParts)
                    }.joinToString(" · ")
                    if (metaInfo.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            metaInfo,
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // ISBN（胶囊标签）
                    if (isbn.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.secondaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    "ISBN $isbn",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.8f),
                                    letterSpacing = 0.4.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
