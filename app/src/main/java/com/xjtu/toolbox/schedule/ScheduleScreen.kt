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
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.draw.alpha
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
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventAvailable
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.AppTopBar
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
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
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import top.yukonga.miuix.kmp.basic.VerticalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class ScheduleDiskSnapshot(
    val termList: List<String> = emptyList(),
    val termCode: String = "",
    val courses: List<CourseItem> = emptyList(),
    val exams: List<ExamItem> = emptyList(),
    val startDate: LocalDate? = null,
)

private fun readScheduleDiskSnapshot(
    dataCache: com.xjtu.toolbox.util.DataCache,
    gson: com.google.gson.Gson,
): ScheduleDiskSnapshot {
    val termList = dataCache.get("schedule_term_list", Long.MAX_VALUE)?.let { json ->
        try { gson.fromJson(json, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
    }.orEmpty()
    val termCode = dataCache.get("schedule_last_term", Long.MAX_VALUE)
        ?.trim('"')
        .orEmpty()
        .ifEmpty { termList.firstOrNull().orEmpty() }
    if (termCode.isEmpty()) return ScheduleDiskSnapshot(termList = termList)
    val courses = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode)
        ?: dataCache.get("schedule_$termCode", Long.MAX_VALUE)?.let { json ->
            try { gson.fromJson(json, Array<CourseItem>::class.java).toList().map { it.sanitized() } } catch (_: Exception) { null }
        }
        ?: emptyList()
    val exams = dataCache.get("exams_$termCode", Long.MAX_VALUE)?.let { json ->
        try { gson.fromJson(json, Array<ExamItem>::class.java).toList().map { it.sanitized() } } catch (_: Exception) { emptyList() }
    }.orEmpty()
    val startDate = dataCache.get("start_date_$termCode", Long.MAX_VALUE)?.let { json ->
        try { LocalDate.parse(json.trim('"')) } catch (_: Exception) { null }
    }
    return ScheduleDiskSnapshot(termList, termCode, courses, exams, startDate)
}

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
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 顶栏盖在内容上面时（首页日程 tab 的玻璃顶栏，plan2 Y2）顶栏的总高度。
     * 各栏的滚动内容把它当作顶部留白，从顶栏下面穿过；0 = 顶栏不盖内容，和原来一样。
     */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 首页日程 tab 的顶栏折叠行为（顶栏在 MainScreen 里），交给各栏的下拉刷新协调。 */
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
    /** 课程详情面板里的下钻入口（教材全文 / 课程回放 / 考勤）要能跳到别的功能页。 */
    onNavigate: (String) -> Unit = {},
) {
    // 大屏适配由屏内 Composable 自己根据 currentWindowSize() 判断，调用方不再透传
    val isWideLayout = com.xjtu.toolbox.ui.isWideLayout()
    val appLoginState = LocalAppLoginState.current
    var activeSite by remember(site) { mutableStateOf(site) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // PR T（计划 §11）：切周、课表加载完成的触感反馈，见下面 haptics.tick()/success() 调用处。
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    // DataCache 构造时绑定账号，切账号后必须换新实例，见 DataCache 类注释
    val dataCache = remember(appLoginState.accountId) {
        com.xjtu.toolbox.util.DataCache(context, appLoginState.accountId.ifEmpty { null })
    }
    val gson = remember { com.google.gson.Gson() }
    val api = remember(activeSite) { activeSite?.let { ScheduleApi(it) } }
    fun termLabel(code: String): String = ScheduleTermStore.display(code, dataCache, gson, api)

    // 课表走用户选的来源；历史学期、以及非教务源取不到时都退回教务，见 ScheduleSourceRouter。
    // 考试、教材、学期表这些只有教务有，照旧直接用 api。
    suspend fun fetchSchedule(
        scheduleApi: ScheduleApi,
        term: String,
        userInitiated: Boolean = false,
    ): List<CourseItem> = ScheduleSourceRouter.getSchedule(
        context = context,
        jwxt = scheduleApi,
        termCode = term,
        manager = appLoginState.sessionManager,
        accountType = appLoginState.accountType,
        userInitiated = userInitiated,
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val disk = remember { readScheduleDiskSnapshot(dataCache, gson) }

    // Room 数据库 - 自定义课程
    val db = remember { com.xjtu.toolbox.util.AppDatabase.getInstance(context) }
    val customCourseDao = remember { db.customCourseDao() }
    var customCourses by remember { mutableStateOf<List<CustomCourseEntity>>(emptyList()) }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CustomCourseEntity?>(null) }
    var addScheduleDraft by remember { mutableStateOf(CustomCourseDraft()) }

    var courses by remember { mutableStateOf(disk.courses) }
    var exams by remember { mutableStateOf(disk.exams) }
    // 「接下来」用的作业截止数据。只读别处已经写好的落盘缓存，日程页不为此发任何请求，见 LmsDueStore。
    var homeworkDue by remember { mutableStateOf<List<com.xjtu.toolbox.lms.LmsDue>>(emptyList()) }
    var textbooks by remember { mutableStateOf<List<TextbookItem>>(emptyList()) }
    var textbooksLoading by remember { mutableStateOf(false) }
    var textbooksError by remember { mutableStateOf<String?>(null) }
    var textbooksLoaded by remember { mutableStateOf(false) }
    var textbooksRefreshing by remember { mutableStateOf(false) }
    // 后台加载（课程详情顺带取教材）的失败原因。跟 textbooksError 分开放：
    // 那个是教材页自己的提示条，不该被一次后台请求改写，反过来也一样。
    var textbooksBackgroundError by remember { mutableStateOf<String?>(null) }
    var examsRefreshing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(disk.courses.isEmpty()) }
    var isSwitching by remember { mutableStateOf(false) }  // 学期切换中（保留旧日程显示）
    var isRefreshingFromNetwork by remember { mutableStateOf(false) } // 缓存已显示，后台刷新中
    var loadJob by remember { mutableStateOf<Job?>(null) }
    val loadGen = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var lastLoadedAccount by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val initialWeek = remember(disk.startDate) {
        val startDate = disk.startDate ?: return@remember 0
        try {
            val w = TermWeeks.weekOf(startDate)
            // 这里还拿不到 totalWeeks（依赖 courses），先用磁盘快照估一次。
            val diskWeeks = disk.courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 } ?: 30
            if (w in 1..diskWeeks) w else 0
        } catch (_: Exception) { 0 }
    }
    var currentWeek by rememberSaveable { mutableIntStateOf(if (initialWeek > 0) initialWeek else 1) }
    var realCurrentWeek by remember { mutableIntStateOf(initialWeek) }  // 实际当前周（0=未知），用于时间线显示判断
    /**
     * 学期周数。取 `weekBits` 的长度——那是教务下发的周次位串，长度就是周数。
     * 原来写死 20，于是暑假、短学期也能滑到第 20 周，后面全是空网格。
     * 为 0 表示这学期没课，由 [ScheduleTabContent] 落空状态。
     */
    val totalWeeks = remember(courses) {
        courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 } ?: 0
    }

    /**
     * 现算一遍学期周数，不走 [totalWeeks] 那个 remember。
     *
     * 加载流程里"先赋值 courses，紧接着算周次"是同一帧内的事，而 `totalWeeks` 是本次
     * 组合期间算好的 val，那一刻还是旧值（冷启动时就是 0）。0 会被当成"学期只有 0 周"，
     * 于是任何周次都 > 0，页面报"学期已结束"。这里直接读 state，拿到的永远是最新的。
     */
    fun knownTotalWeeks(): Int = courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 } ?: 0

    /**
     * 添加/编辑日程弹窗用的周数。教务课表为空时 [totalWeeks] 是 0，弹窗里一格周都
     * 选不了、「添加」按钮永远灰着——表现就是「点加号没反应」。空的时候退到
     * 自定义日程里最长的周数，再退到学期默认周数。
     */
    fun editableWeeks(): Int = totalWeeks.takeIf { it > 0 }
        ?: customCourses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
        ?: TermWeeks.DEFAULT_TOTAL_WEEKS
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    /** 今日 / 学期两级点课后要弹的详情。周视图有自己那份，见 ScheduleTabContent。 */
    var unifiedSelectedCourse by remember { mutableStateOf<CourseItem?>(null) }

    /** 今日那一级点课时带上今天；学期那一级说不出是哪一次，保持 null。 */
    var unifiedOccurrence by remember { mutableStateOf<Occurrence?>(null) }

    /**
     * tab 序号 → 这一页放什么。固定今日 / 日程 / 学期三格（plan2 §1.5），
     * 页面里凡是要问"现在是不是在课表页"的地方都走这个函数，别再去比 selectedTab == 0。
     */
    fun contentOf(tab: Int): String = when (tab) { 0 -> "today"; 1 -> "week"; else -> "semester" }
    val currentContent = contentOf(selectedTab)
    var weekNote by remember { mutableStateOf<String?>(null) } // "距开学X周" / "学期已结束"

    // 学期相关
    var termList by remember { mutableStateOf(disk.termList) }
    var selectedTermCode by remember { mutableStateOf(disk.termCode) }
    var currentTermCode by remember { mutableStateOf(disk.termCode) }  // 当前学期，用于判断是否缓存考试

    /**
     * 本次会话里用户有没有主动切过学期。
     *
     * 切过就不该再被"当前学期"拽回去。用 rememberSaveable 是为了跨越
     * 导航到子页面再返回（那会让本 composable 被销毁重建）；
     * 进程重启后回到 false，于是新会话仍然从当前学期开始——
     * 不然开学后会永远停在上学期。
     */
    var userPickedTerm by rememberSaveable { mutableStateOf(false) }
    /** 当前学期课表为空、但按日期推算的学期有课时，要自动切过去的学期代码。见 loadInitialData。 */
    var autoTermSuggestion by remember { mutableStateOf<String?>(null) }
    var termDropdownExpanded by remember { mutableStateOf(false) }

    // 周视图 vs 总览（每次启动默认周视图，不保存状态）
    var showAllWeeks by remember { mutableStateOf(false) }

    // 周选择器弹窗（§1.4）：标签行下面的胶囊点开
    var showWeekPicker by remember { mutableStateOf(false) }

    // 是否正在显示缓存数据（网络失败时提示）
    var showingStaleData by remember { mutableStateOf(disk.courses.isNotEmpty()) }

    // 开学日期（导出 ICS 用）
    var startOfTerm by remember { mutableStateOf(disk.startDate) }
    
    // 法定节假日
    var holidayDates by remember { mutableStateOf(HolidayApi.peekCached(context)) }

    // 导出菜单
    var showExportMenu by remember { mutableStateOf(false) }

    // 通知外层（MainScreen TopAppBar）当前学期。第几周已经挪到标签行下面的周选择胶囊里，
    // 副标题不用再重复一遍。
    LaunchedEffect(selectedTermCode, termList) {
        onSubtitleChange(termLabel(selectedTermCode))
    }

    fun readCachedTerms(): List<String> {
        val json = dataCache.get("schedule_term_list", Long.MAX_VALUE) ?: return emptyList()
        return try { gson.fromJson(json, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
    }

    fun applyTermStart(startDate: LocalDate) {
        startOfTerm = startDate
        try {
            // totalWeeks 取自 courses，这一帧里 courses 可能刚被赋值而它还是旧的；
            // 直接现算一遍，别让"课表已经到了但周数还是 0"的中间态判成学期结束。
            val weeks = knownTotalWeeks()
            val status = TermWeeks.statusOf(
                startOfTerm = startDate,
                totalWeeks = weeks,
                firstTeachWeek = TermWeeks.firstTeachWeekOf(courses),
            )
            if (status is TermWeeks.Status.InTerm) realCurrentWeek = status.week
            if (status is TermWeeks.Status.AfterTerm) showAllWeeks = true
            currentWeek = TermWeeks.displayWeekOf(status)
            weekNote = TermWeeks.noteOf(status)
        } catch (_: Exception) {
            currentWeek = 1
            weekNote = null
        }
    }

    /** 磁盘缓存立刻上屏，不碰网络。 */
    fun paintCache(termCode: String): Int {
        if (termCode.isEmpty()) return -1
        selectedTermCode = termCode
        currentTermCode = termCode
        val optimized = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode)
        if (optimized != null) {
            courses = optimized
        } else {
            val cached = dataCache.get("schedule_$termCode", Long.MAX_VALUE)
            if (cached != null) {
                try { courses = gson.fromJson(cached, Array<CourseItem>::class.java).toList().map { it.sanitized() } } catch (_: Exception) {}
            }
        }
        dataCache.get("exams_$termCode", Long.MAX_VALUE)?.let { json ->
            try { exams = gson.fromJson(json, Array<ExamItem>::class.java).toList().map { it.sanitized() } } catch (_: Exception) {}
        }
        dataCache.get("start_date_$termCode", Long.MAX_VALUE)?.let { json ->
            try { applyTermStart(LocalDate.parse(json.trim('"'))) } catch (_: Exception) { currentWeek = 1 }
        }
        return courses.size
    }

    // 初始加载：有缓存先上屏，课表接口一到就停转圈；考试/学期列表后台补。
    fun loadInitialData() {
        loadJob?.cancel()
        errorMessage = null
        val keepShowing = courses.isNotEmpty()
        if (!keepShowing) isLoading = true
        isRefreshingFromNetwork = false
        showingStaleData = false
        val gen = loadGen.incrementAndGet()
        // 本次加载属于哪个账号。切账号时 SessionManager 是原地重配、api 背后的站点会被
        // 换成新账号的会话；本任务虽会被 LaunchedEffect(accountId) 取消，但取消只在挂起点
        // 生效，恰好在那之前拿到的结果可能已是新账号的数据。每次联网结果落地前核对一次，
        // 账号变了就按取消处理，既不刷界面也不写缓存（dataCache 绑定的是本任务的账号）。
        val jobAccount = AccountContext.activeAccountId
        fun ensureSameAccount() {
            if (AccountContext.activeAccountId != jobAccount) {
                throw kotlinx.coroutines.CancellationException("account switched during schedule load")
            }
        }
        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cachedTerms = readCachedTerms()
                    if (cachedTerms.isNotEmpty()) termList = cachedTerms
                    val lastTerm = dataCache.get("schedule_last_term", Long.MAX_VALUE)
                        ?.trim('"')
                        .orEmpty()
                        .ifEmpty { cachedTerms.firstOrNull().orEmpty() }
                    if (lastTerm.isNotEmpty() && paintCache(lastTerm) > 0) {
                        isLoading = false
                        isRefreshingFromNetwork = api != null
                        showingStaleData = true
                    }

                    if (api == null) {
                        if (courses.isEmpty()) throw RuntimeException("暂无缓存日程")
                        showingStaleData = true
                        return@withContext
                    }

                    // ── 在线模式 ──
                    // 学期接口和课表并行：有上次学期时先按缓存学期拉课表并立刻上屏，学期代码回来再对一下。
                    try {
                        supervisorScope {
                            val termDeferred = async {
                                try {
                                    api.getCurrentTerm()
                                } catch (e: AuthExpiredException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.w("ScheduleUI", "getCurrentTerm failed, trying cache", e)
                                    // 没缓存学期也别直接报错：按日期推一个学期代码去拉课表。
                                    // 教务的「当前学期」接口偶发返回空行或超时，不该把整页拖成错误页。
                                    lastTerm.ifEmpty {
                                        com.xjtu.toolbox.util.XjtuTime.expectedTermCode()
                                            ?: throw RuntimeException("网络不可用且无缓存学期数据，请连网后重试")
                                    }
                                }
                            }
                            val schedulePrefetch = lastTerm.takeIf { it.isNotEmpty() }?.let { cached ->
                                async {
                                    try {
                                        fetchSchedule(api, cached)
                                    } catch (e: AuthExpiredException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                            val examsDeferred = async {
                                val termForExam = lastTerm.ifEmpty { termDeferred.await() }
                                try {
                                    api.getExamSchedule(termForExam)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: AuthExpiredException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.w("ScheduleUI", "getExamSchedule failed; keeping cached/empty exams", e)
                                    exams
                                }
                            }
                            val startDateDeferred = async {
                                val termForStart = lastTerm.ifEmpty { termDeferred.await() }
                                try { api.getStartOfTerm(termForStart) } catch (_: Exception) { null }
                            }
                            val termListDeferred = async {
                                try { api.getTermList() } catch (_: Exception) { emptyList() }
                            }

                            fun paintCourses(termCode: String, freshCourses: List<CourseItem>, startDate: LocalDate?) {
                                ensureSameAccount()
                                val holidays = holidayDates.ifEmpty { HolidayApi.peekCached(context) }
                                if (holidays.isNotEmpty()) holidayDates = holidays
                                val optimized = ScheduleCache.filterByHolidays(freshCourses, startDate, holidays)
                                val optimizedJson = gson.toJson(optimized)
                                val cachedOptimizedJson =
                                    dataCache.get(ScheduleCache.optimizedScheduleKey(termCode), Long.MAX_VALUE)
                                val contentChanged = cachedOptimizedJson == null || optimizedJson != cachedOptimizedJson
                                if (courses.isEmpty() || contentChanged) {
                                    courses = optimized
                                }
                                showingStaleData = false
                                isLoading = false
                                isRefreshingFromNetwork = false
                                // PR T：paintCourses 是网络课表落地的唯一入口（见上面的注释），课表加载完成在这里报一次成功触感。
                                haptics.success()
                                try { dataCache.put("schedule_$termCode", gson.toJson(freshCourses)) } catch (_: Exception) {}
                                try { dataCache.put(ScheduleCache.optimizedScheduleKey(termCode), optimizedJson) } catch (_: Exception) {}
                                if (contentChanged && cachedOptimizedJson != null) {
                                    scope.launch { snackbarHostState.showSnackbar("日程有更新", duration = SnackbarDuration.Short) }
                                }
                                // 变更检测放在这个漏斗里：paintCourses 是网络课表落地的唯一入口，
                                // 读缓存的路径不经过它。缓存和快照本来就是同一份，比了也永远无变化。
                                // 用未过滤节假日的 freshCourses 比，否则放假会被误判成"课被取消了"。
                                val changes = ScheduleDiff.diffAndStore(context, termCode, freshCourses)
                                ScheduleDiff.summarize(changes)?.let { msg ->
                                    ScheduleDiff.setPending(context, msg)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
                                    }
                                }
                            }

                            val prefetched = schedulePrefetch?.await()
                            if (prefetched != null) {
                                paintCourses(lastTerm, prefetched, startOfTerm)
                            }

                            val termCode = termDeferred.await()
                            ensureSameAccount()
                            currentTermCode = termCode
                            ScheduleCache.writeCurrentTerm(dataCache, gson, termCode)
                            // 用户本次进来主动切过学期时，不要再把视图拽回"当前学期"。
                            // 这段以前是无条件执行的：从课程详情跳去思源学堂再返回，
                            // ScheduleScreen 重新组合 → loadInitialData 重跑 →
                            // selectedTermCode 被覆盖成当前学期，连 schedule_last_term
                            // 也被一起改写，用户刚翻到的历史学期就这么没了。
                            val keepUserTerm = userPickedTerm && lastTerm.isNotEmpty() && lastTerm != termCode
                            if (!keepUserTerm) {
                                selectedTermCode = termCode
                                try { dataCache.put("schedule_last_term", gson.toJson(termCode)) } catch (_: Exception) {}
                            }
                            if (!keepUserTerm && termCode != lastTerm && lastTerm.isNotEmpty()) {
                                if (paintCache(termCode) > 0) {
                                    isLoading = false
                                    isRefreshingFromNetwork = true
                                    showingStaleData = true
                                }
                                schedulePrefetch?.cancel()
                                examsDeferred.cancel()
                                startDateDeferred.cancel()
                                val freshCourses = fetchSchedule(api, termCode)
                                val startDate = try { api.getStartOfTerm(termCode) } catch (_: Exception) { startOfTerm }
                                paintCourses(termCode, freshCourses, startDate)
                                if (startDate != null) {
                                    applyTermStart(startDate)
                                    try { dataCache.put("start_date_$termCode", gson.toJson(startDate.toString())) } catch (_: Exception) {}
                                }
                                val freshExams = try { api.getExamSchedule(termCode) } catch (_: Exception) { exams }
                                ensureSameAccount()
                                exams = freshExams
                                if (freshExams.isNotEmpty()) {
                                    try { dataCache.put("exams_$termCode", gson.toJson(freshExams)) } catch (_: Exception) {}
                                }
                            } else {
                                // 留在用户选的学期时，这一支要认那一个学期——
                                // examsDeferred / startDateDeferred / prefetched 本来就是按
                                // lastTerm 发的，只有这里的标签之前写成了 termCode。
                                val viewTerm = if (keepUserTerm) lastTerm else termCode
                                val freshCourses = prefetched ?: fetchSchedule(api, viewTerm)
                                if (prefetched == null) {
                                    paintCourses(viewTerm, freshCourses, startOfTerm)
                                }
                                val startDate = startDateDeferred.await() ?: startOfTerm
                                ensureSameAccount()
                                if (startDate != null) {
                                    applyTermStart(startDate)
                                    try { dataCache.put("start_date_$viewTerm", gson.toJson(startDate.toString())) } catch (_: Exception) {}
                                    if (holidayDates.isNotEmpty()) {
                                        courses = ScheduleCache.filterByHolidays(freshCourses, startDate, holidayDates)
                                    }
                                }
                                val freshExams = examsDeferred.await()
                                ensureSameAccount()
                                exams = freshExams
                                if (freshExams.isNotEmpty()) {
                                    try { dataCache.put("exams_$viewTerm", gson.toJson(freshExams)) } catch (_: Exception) {}
                                }
                            }
                            // 换季那几周教务的「当前学期」常常还指着短学期/暑假，课表是空的，
                            // 而新学期的课其实已经能查到。此时页面只剩一句「本学期没有课程」，
                            // 用户并不知道要去切学期。按日期推一个该在的学期探一下，有课就切过去。
                            if (!keepUserTerm && courses.isEmpty()) {
                                val expected = com.xjtu.toolbox.util.XjtuTime.expectedTermCode()
                                if (expected != null && expected != termCode) {
                                    val probe = try { fetchSchedule(api, expected) } catch (_: Exception) { emptyList() }
                                    if (probe.isNotEmpty()) autoTermSuggestion = expected
                                }
                            }
                            val availableTerms = (termListDeferred.await() + readCachedTerms()).distinct()
                            ensureSameAccount()
                            try { ScheduleTermStore.merge(dataCache, gson, api.termNames()) } catch (_: Exception) {}
                            if (availableTerms.isNotEmpty()) {
                                termList = availableTerms
                                try { dataCache.put("schedule_term_list", gson.toJson(availableTerms)) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: AuthExpiredException) {
                        throw e
                    } catch (e: Exception) {
                        if (courses.isNotEmpty()) {
                            showingStaleData = true
                            isRefreshingFromNetwork = false
                            if (termList.isEmpty()) {
                                val t = readCachedTerms()
                                if (t.isNotEmpty()) termList = t
                            }
                            scope.launch { snackbarHostState.showSnackbar("网络异常，显示的可能不是最新数据", duration = SnackbarDuration.Long) }
                            android.util.Log.w("ScheduleUI", "Network failed, showing cached data", e)
                        } else {
                            android.util.Log.w("ScheduleUI", "Online failed, falling back to cache", e)
                            val t = readCachedTerms()
                            if (t.isNotEmpty()) termList = t
                            val fallbackTerm = selectedTermCode.ifEmpty { t.firstOrNull().orEmpty() }
                            if (fallbackTerm.isNotEmpty()) paintCache(fallbackTerm)
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
                errorMessage = com.xjtu.toolbox.util.FriendlyError.of(e, "加载课表")
            } finally {
                if (gen == loadGen.get()) {
                    isLoading = false
                    isRefreshingFromNetwork = false
                    ScheduleWidgetUpdater.requestUpdate(context)
                }
            }
        }
    }

    /**
     * 手动刷新：刷新**正在看的学期**，不要去拉「当前学期」再把视图切回去。
     * 人已经翻到历史学期了，下拉却弹回本学期，等于白切。
     */
    fun refreshSchedule(force: Boolean = true) {
        if (api == null) return
        if (isRefreshingFromNetwork) return
        isRefreshingFromNetwork = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val viewing = selectedTermCode
                    val actualCurrent = try {
                        api.getCurrentTerm()
                    } catch (e: Exception) {
                        viewing.ifEmpty {
                            val cachedTermList = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                            val cachedTerms = if (cachedTermList != null) {
                                try { gson.fromJson(cachedTermList, Array<String>::class.java).toList() } catch (_: Exception) { emptyList() }
                            } else emptyList()
                            cachedTerms.firstOrNull() ?: throw e
                        }
                    }
                    if (actualCurrent.isNotEmpty()) {
                        currentTermCode = actualCurrent
                        ScheduleCache.writeCurrentTerm(dataCache, gson, actualCurrent)
                    }
                    try { ScheduleTermStore.merge(dataCache, gson, api.termNames()) } catch (_: Exception) {}
                    val termCode = viewing.ifEmpty { actualCurrent }
                    if (viewing.isEmpty() && termCode.isNotEmpty()) selectedTermCode = termCode
                    val apiCourses = try {
                        fetchSchedule(api, termCode, userInitiated = true)
                    } catch (e: Exception) {
                        android.util.Log.w("ScheduleUI", "refreshSchedule getSchedule failed", e)
                        return@withContext
                    }
                    // courses 只放教务结果；自定义课由 mergedCourses 再拼，避免刷新后重复
                    courses = apiCourses
                    showingStaleData = false
                    dataCache.put("schedule_$termCode", gson.toJson(apiCourses))
                }
            } catch (e: Exception) {
                android.util.Log.w("ScheduleUI", "refreshSchedule failed", e)
                errorMessage = com.xjtu.toolbox.util.FriendlyError.of(e, "刷新课表")
            } finally {
                isRefreshingFromNetwork = false
            }
        }
    }

    fun refreshExams() {
        val scheduleApi = api
        if (scheduleApi == null || selectedTermCode.isEmpty() || examsRefreshing) return
        examsRefreshing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val fresh = scheduleApi.getExamSchedule(selectedTermCode)
                    exams = fresh
                    dataCache.put("exams_$selectedTermCode", gson.toJson(fresh))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCHEDULE, onBack)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(com.xjtu.toolbox.util.FriendlyError.of(e, "刷新考试"))
            } finally {
                examsRefreshing = false
            }
        }
    }

    /**
     * 加载教材。
     *
     * [background] 说的是**谁在等这个结果**，而不是"用哪种转圈"：
     * true 表示没人在等——课程详情面板点开一门课时顺带取的，于是不占页面的
     * 加载态、不写教材页的提示条、失败也不抢导航。用户自己点刷新时传 false，
     * 首屏还是空的就铺加载态、已经有内容就走下拉刷新的那一个，由
     * [textbooksLoaded] 自己决定，调用方不必操心。
     *
     * 这两件事以前挤在一个 `silent` 里，于是同时错了两头：课程详情那条路
     * **跳过了缓存**（缓存是本地的，读它既不慢也不打扰谁），失败又只写进
     * 教材页才看得到的 [textbooksError]——"教务这次没请求成功"在课程详情里的
     * 表现就是教材那一行整个不见，不给任何解释；而用户手动刷新一旦变成
     * `silent = textbooksLoaded`，又反过来被当成了没人在等。最要命的是
     * AuthExpired 会走 handleAuthExpired → onBack()：点开一门课，人就被弹出
     * 日程页去重登一次。
     */
    fun loadTextbooks(termCode: String, background: Boolean = false) {
        android.util.Log.d("ScheduleUI", "loadTextbooks called: studentId='$studentId', termCode='$termCode' background=$background")
        val jw = api
        val blocked = when {
            jw == null -> "尚未登录教务系统"
            studentId.isBlank() -> "未获取到学号"
            else -> null
        }
        if (jw == null || blocked != null) {
            if (background) textbooksBackgroundError = blocked else textbooksError = blocked
            return
        }
        if (background) {
            // 后台那条路要防重入：面板反复开合会一直打这个请求。
            // 用户自己点的刷新不拦——切学期时上一发还没回来，新的那一发必须跑。
            if (textbooksRefreshing) return
            textbooksRefreshing = true
            textbooksBackgroundError = null
        } else {
            // 已经有内容了就别把它换成一屏加载态——下拉刷新自己有指示器。
            if (textbooksLoaded) textbooksRefreshing = true else textbooksLoading = true
            textbooksError = null
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 先上缓存，两条路径一视同仁：网络那一下失败时，手里有什么先给什么。
                    ScheduleCache.readTextbooks(dataCache, gson, termCode, Long.MAX_VALUE)?.let { cached ->
                        textbooks = cached.sortedBy { item -> if (item.hasSubstantiveTextbook) 0 else 1 }
                        textbooksLoaded = true
                    }
                    val raw = jw.getTextbooks(studentId, termCode)
                    // 排序：有教材的在前，无教材的在后
                    textbooks = raw.sortedBy { item ->
                        if (item.hasSubstantiveTextbook) 0 else 1
                    }
                    ScheduleCache.writeTextbooks(dataCache, gson, termCode, textbooks)
                }
                android.util.Log.d("ScheduleUI", "loadTextbooks done: ${textbooks.size} items")
                textbooksLoaded = true
                if (background) textbooksBackgroundError = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                android.util.Log.w("ScheduleUI", "loadTextbooks 登录过期 background=$background")
                // 静默路径不抢导航：用户只是点开了一门课，不该因此被弹回登录。
                // 教务真过期了，页面上任何一个正经操作都会撞到，由那一次去处理。
                if (background) textbooksBackgroundError = "教务登录已过期，去教材页刷新一次"
                else appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCHEDULE, onBack)
            } catch (e: Exception) {
                android.util.Log.e("ScheduleUI", "loadTextbooks failed background=$background", e)
                val msg = com.xjtu.toolbox.util.FriendlyError.of(e, "查询教材")
                if (background) textbooksBackgroundError = msg else textbooksError = msg
            } finally {
                textbooksLoading = false
                textbooksRefreshing = false
            }
        }
    }

    fun refreshActiveTab() {
        when (contentOf(selectedTab)) {
            // 学期一级同时放着考试和教材，两份都刷。
            "semester" -> {
                refreshExams()
                if (selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode)
            }
            else -> refreshSchedule(true)
        }
    }

    LaunchedEffect(appLoginState.accountId) {
        val id = appLoginState.accountId
        if (lastLoadedAccount != null && lastLoadedAccount != id) {
            courses = emptyList()
            exams = emptyList()
            customCourses = emptyList()
        }
        lastLoadedAccount = id
        loadInitialData()
        try { holidayDates = HolidayApi.getHolidayDates(context) } catch (_: Exception) {}
        // 「接下来」的作业数据，纯读缓存（plan2 §5.2）。
        homeworkDue = withContext(Dispatchers.IO) {
            runCatching { com.xjtu.toolbox.lms.LmsDueStore.load(context, id) }.getOrDefault(emptyList())
        }
    }

    // 先进来时还没登录、稍后 JWXT 会话才就绪：补一次在线刷新。入页时已经有 site 就不要再打一遍。
    var hadSite by remember { mutableStateOf(site != null) }
    LaunchedEffect(activeSite) {
        val now = activeSite != null
        val appeared = now && !hadSite
        hadSite = now
        if (!appeared) return@LaunchedEffect
        if (isLoading || isSwitching || isRefreshingFromNetwork) return@LaunchedEffect
        loadInitialData()
    }

    // 设置里换了「当前学期课表来源」以后回到这里：按新来源重新加载一遍。
    // 日程是首页的一个 tab，组合一次就一直留着，从设置页返回不会重新加载；而学期列表只在
    // 教务系统那条路径上拉取。默认来源是移动教务，那时教务可能一直没登录、学期列表是空的，
    // 切到教务系统回来右上角就没有「切换学期」，要重启才出现。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var lastSource by remember { mutableStateOf(com.xjtu.toolbox.util.CredentialStore(context).scheduleSource) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val now = com.xjtu.toolbox.util.CredentialStore(context).scheduleSource
            if (now == lastSource) return@LifecycleEventObserver
            lastSource = now
            if (!isLoading && !isSwitching && !isRefreshingFromNetwork) loadInitialData()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            customCourses = customCourseDao.getByTerm(AccountContext.activeAccountId ?: "", selectedTermCode)
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

    // 「接下来」：今日两处 TodayTimeline（窄屏 tab、宽屏常驻栏）共用同一份，见 plan2 §5.3。
    val upcomingItems = remember(exams, homeworkDue) { buildUpcoming(exams, homeworkDue) }

    // 自定义课程操作
    //
    // 冲突不再自动删旧的：以前只比星期和节次、不比周次，第 4 周和第 8 周同一时段的实验
    // 会被判成冲突，旧的被静默删掉且无法恢复。现在只有周次、星期、时间都重叠才算冲突，
    // 而且交给用户选：替换 / 都保留 / 取消。
    var pendingSave by remember { mutableStateOf<Pair<CustomCourseEntity, List<CustomCourseEntity>>?>(null) }

    fun commitCustomCourse(entity: CustomCourseEntity, replacing: List<CustomCourseEntity>) {
        scope.launch {
            val accountId = entity.accountId
            replacing.forEach { customCourseDao.delete(it) }
            if (entity.id == 0L) {
                customCourseDao.insert(entity)
                addScheduleDraft = CustomCourseDraft()
            } else {
                customCourseDao.update(entity)
            }
            customCourses = customCourseDao.getByTerm(accountId, selectedTermCode)
            ScheduleWidgetUpdater.requestUpdate(context)
            val verb = if (entity.id == 0L) "已添加日程" else "已更新日程"
            val msg = if (replacing.isEmpty()) verb
                else "$verb，并替换了「${replacing.joinToString("、") { it.courseName }}」"
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    fun saveCustomCourse(entity: CustomCourseEntity) {
        scope.launch {
            val accountId = AccountContext.activeAccountId ?: ""
            val withAccount = if (entity.accountId.isBlank()) entity.copy(accountId = accountId) else entity
            // DAO 只按星期和节次粗筛，周次与分钟级时间在这里精判。
            val conflicts = customCourseDao
                .getConflicts(accountId, withAccount.termCode, withAccount.dayOfWeek, withAccount.startSection, withAccount.endSection)
                .filter { it.id != withAccount.id && CustomCourseConflicts.conflicts(withAccount, it) }
            if (conflicts.isEmpty()) {
                commitCustomCourse(withAccount, emptyList())
            } else {
                pendingSave = withAccount to conflicts
            }
        }
    }

    pendingSave?.let { (entity, conflicts) ->
        val lines = conflicts.joinToString("\n") { other ->
            val weeks = CustomCourseConflicts.sharedWeeks(entity.weekBits, other.weekBits)
            "「${other.courseName}」：${CustomCourseConflicts.describeWeeks(weeks)}"
        }
        // Window* 自带窗口，不依赖外层 Scaffold 宿主（同 CustomCourseDialog 的删除确认）。
        BackHandler { pendingSave = null }
        WindowDialog(
            show = true,
            title = "时间冲突",
            summary = "「${entity.courseName}」与以下日程在同一时段重叠：\n$lines",
            onDismissRequest = { pendingSave = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "都保留",
                    onClick = {
                        pendingSave = null
                        commitCustomCourse(entity, emptyList())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "替换原有日程",
                    onClick = {
                        pendingSave = null
                        commitCustomCourse(entity, conflicts)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "取消",
                    onClick = { pendingSave = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    fun deleteCustomCourse(entity: CustomCourseEntity) {
        scope.launch {
            val accountId = AccountContext.activeAccountId ?: ""
            customCourseDao.delete(entity)
            customCourses = customCourseDao.getByTerm(accountId, selectedTermCode)
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
            totalWeeks = editableWeeks(),
            draft = addScheduleDraft,
            onAutoSave = { addScheduleDraft = it },
            // 草稿等真正写库后再清（commitCustomCourse）：撞上冲突选「取消」时，
            // 重新打开添加弹窗还能看到刚才填的内容。
            onSave = { saveCustomCourse(it) },
            onDismiss = { showAddCourseDialog = false }
        )
    }
    editingCourse?.let { entity ->
        val showEditCourseState = remember { mutableStateOf(true) }
        CustomCourseDialog(
            show = showEditCourseState,
            existing = entity,
            termCode = selectedTermCode,
            totalWeeks = editableWeeks(),
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
        val wantsTextbooks = contentOf(selectedTab) in setOf("book", "semester")
        if (wantsTextbooks && api != null && !textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) {
            android.util.Log.d("ScheduleUI", "LaunchedEffect auto-loading textbooks: term=$selectedTermCode (api just became ready)")
            // api 刚变非空时之前可能设了「尚未登录」错误，要清掉再加载
            textbooksError = null
            loadTextbooks(selectedTermCode)
        }
    }

    // 切换学期
    fun switchTerm(newTermCode: String) {
        if (newTermCode == selectedTermCode) return
        userPickedTerm = true
        selectedTermCode = newTermCode
        try { dataCache.put("schedule_last_term", gson.toJson(newTermCode)) } catch (_: Exception) {}
        textbooksLoaded = false
        textbooks = emptyList()
        // 考试也要清。不清的话，新学期没有缓存考试时，屏幕上留着的是**上一个学期**的
        // 考试安排——比空着更糟，用户会照着一个早就过去的日期去考试。
        exams = emptyList()
        showingStaleData = false
        scope.launch {
            isSwitching = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    val isOldTerm = newTermCode != currentTermCode

                    // 先尝试从缓存加载
                    val cachedOptimizedCourses = ScheduleCache.readOptimizedCourses(dataCache, gson, newTermCode)
                    val cachedExams = dataCache.get("exams_$newTermCode", com.xjtu.toolbox.util.DataCache.TERM_TTL_MS)
                    if (cachedOptimizedCourses != null) {
                        courses = cachedOptimizedCourses
                        if (cachedExams != null) {
                            try { exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList().map { it.sanitized() } } catch (_: Exception) {}
                        }
                        android.util.Log.d("ScheduleUI", "Optimized term from cache: $newTermCode")
                    } else {
                        val cachedCourses = dataCache.get("schedule_$newTermCode", Long.MAX_VALUE)
                        if (cachedCourses != null) {
                        try {
                            courses = gson.fromJson(cachedCourses, Array<CourseItem>::class.java).toList().map { it.sanitized() }
                            if (cachedExams != null) exams = gson.fromJson(cachedExams, Array<ExamItem>::class.java).toList().map { it.sanitized() }
                            android.util.Log.d("ScheduleUI", "Term from cache: $newTermCode")
                        } catch (_: Exception) {}
                        }
                    }

                    // 已结束且本地是全的：一个请求都不发，想强制重拉走下拉刷新。
                    val sealed = ScheduleCache.isSealed(dataCache, gson, newTermCode)
                    if (sealed) {
                        android.util.Log.d("ScheduleUI", "学期 $newTermCode 已封存，直接用缓存")
                    }

                    // 在线时更新
                    if (api != null && !sealed) {
                        try {
                            val freshCourses = fetchSchedule(api, newTermCode, userInitiated = true)
                            exams = api.getExamSchedule(newTermCode)
                            val freshStartDate = try { api.getStartOfTerm(newTermCode) } catch (_: Exception) { null }
                            val freshHolidays = try { HolidayApi.getHolidayDates(context, forceRefresh = true) } catch (_: Exception) { emptyMap() }
                            holidayDates = freshHolidays
                            courses = ScheduleCache.filterByHolidays(freshCourses, freshStartDate, freshHolidays)
                            // 缓存。
                            //
                            // 考试**不分当前/历史**一律落盘：以前跟着 isOldTerm 一起只在
                            // 历史学期写，于是这学期看过的考试从来没进过缓存；等它变成历史学期，
                            // 课表封存（sealed）后连网络都不再请求 —— 翻回去就永远没有考试。
                            // 这就是「历史学期没有考试」的来源。
                            try {
                                dataCache.put("exams_$newTermCode", gson.toJson(exams))
                            } catch (_: Exception) {}
                            if (isOldTerm) {
                                try {
                                    dataCache.put("schedule_$newTermCode", gson.toJson(freshCourses))
                                    ScheduleCache.writeOptimizedCourses(dataCache, gson, newTermCode, courses)
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
                    } else if (api == null) {
                        showingStaleData = true
                    }

                    // 计算当前周
                    try {
                        val startDate = if (api != null && !sealed) {
                            try { api.getStartOfTerm(newTermCode) } catch (_: Exception) { null }
                        } else {
                            val cs = dataCache.get("start_date_$newTermCode", Long.MAX_VALUE)
                            if (cs != null) try { LocalDate.parse(cs.trim('"')) } catch (_: Exception) { null } else null
                        }
                        if (startDate != null) {
                            startOfTerm = startDate
                            if (api != null) try { dataCache.put("start_date_$newTermCode", gson.toJson(startDate.toString())) } catch (_: Exception) {}
                            val status = TermWeeks.statusOf(
                                startOfTerm = startDate,
                                totalWeeks = knownTotalWeeks(),
                                firstTeachWeek = TermWeeks.firstTeachWeekOf(courses),
                            )
                            if (status is TermWeeks.Status.AfterTerm) showAllWeeks = true
                            currentWeek = TermWeeks.displayWeekOf(status)
                            weekNote = TermWeeks.noteOf(status)
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
                errorMessage = com.xjtu.toolbox.util.FriendlyError.of(e, "切换学期")
            } finally {
                isSwitching = false
                ScheduleWidgetUpdater.requestUpdate(context)
            }
        }
    }

    LaunchedEffect(autoTermSuggestion) {
        val target = autoTermSuggestion ?: return@LaunchedEffect
        autoTermSuggestion = null
        if (target == selectedTermCode) return@LaunchedEffect
        switchTerm(target)
        snackbarHostState.showSnackbar(
            "教务的当前学期还没有课表，已切到${termLabel(target)}",
            duration = SnackbarDuration.Long,
        )
    }

    // 注入 TopAppBar actions：[+] [⋮] 两个独立按钮
    val headerActionsContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = {
        // 添加日程（独立按钮）
        if (currentContent == "week") {
            // 一直可点。学期代码还没拿到时（教务接口失败、首装无缓存）别灰掉——用户
            // 只看到一个按不动的加号，不知道为什么；先按日期推一个学期，实在推不出再说明。
            IconButton(
                onClick = {
                    if (selectedTermCode.isEmpty()) {
                        val fallback = currentTermCode.ifEmpty {
                            termList.firstOrNull()
                                ?: com.xjtu.toolbox.util.XjtuTime.expectedTermCode().orEmpty()
                        }
                        if (fallback.isEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("还没拿到学期信息，下拉刷新后再添加", duration = SnackbarDuration.Short)
                            }
                            return@IconButton
                        }
                        selectedTermCode = fallback
                    }
                    showAddCourseDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加日程")
            }
        }
        // 周视图/全部周叠加的切换已经挪到标签行下面的周选择胶囊里（§1.4），
        // 这里不再放一个意思含糊的按钮。
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
                    // 视图切换已经提到工具栏上了，这里不再重复一份——
                    // 同一个开关两个入口，用户按了哪个都得再确认一次状态。
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
                                android.widget.Toast.makeText(context, "无法获取开学日期，ICS 导出不可用", android.widget.Toast.LENGTH_SHORT).show()
                                return@ScheduleMenuRow
                            }
                            scope.launch {
                                android.widget.Toast.makeText(context, "正在导出日历…", android.widget.Toast.LENGTH_SHORT).show()
                                try {
                                    val holidays = HolidayApi.getHolidayDates(context).keys
                                    val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, selectedTermCode, holidays)
                                    ScheduleExport.shareTextFile(context, ics, "${selectedTermCode}_日程.ics", "text/calendar")
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "节假日获取失败，已按普通课表导出",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
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
                            text = termLabel(term),
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
    // 周选择器那一行：只在「日程」栏出现，替掉原来那个只在"每周/全部周叠加"之间
    // 二选一、看不出按了会怎样的按钮（§1.4）。
    //
    // 窄屏挂在顶栏标签下面；宽屏挂在左栏周视图的顶上。宽屏顶栏横跨左右两栏，
    // 这一行放进顶栏的话，「回本周」被推到屏幕另一头、和周胶囊隔着半个屏，
    // 顶栏还因此高了一截，把只管课程详情的右栏也往下挤。
    // 宽屏只占左栏宽度，周标题和「回本周」离得近，不隔半个屏幕。
    val weekPillRow: @Composable (Modifier) -> Unit = { rowModifier ->
        val weekPillDateRange = remember(startOfTerm, currentWeek) {
            val st = startOfTerm
            if (st != null && currentWeek > 0) {
                val monday = st.plusWeeks((currentWeek - 1).toLong())
                val sunday = monday.plusDays(6)
                "${monday.monthValue}/${monday.dayOfMonth}–${sunday.monthValue}/${sunday.dayOfMonth}"
            } else null
        }
        // 一条整宽的周标题栏，不再是两颗小胶囊：小胶囊是 footnote 字号、30dp 高，
        // 和日程页的大标题、大卡片不在一个量级，摆在那里像临时加的控件。
        // 左边是「现在看的是哪一周」这个标题本身，大字、整块可点开周选择；
        // 右边「回本周」是同一行里的一个文字动作，不另起一个按钮的形状。
        Row(
            rowModifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showWeekPicker = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showAllWeeks) "全学期总览" else "第 $currentWeek 周",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (!showAllWeeks && weekPillDateRange != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        weekPillDateRange,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择周",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 2.dp).size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // 当前看的不是本周（或处于全学期总览）时，给个回本周的近道。
            // 只在看本学期时出现：历史学期里没有「本周」，按了也回不去
            val viewingCurrentTerm = selectedTermCode.isEmpty() || selectedTermCode == currentTermCode
            if (viewingCurrentTerm && (showAllWeeks || (realCurrentWeek > 0 && currentWeek != realCurrentWeek))) {
                Text(
                    "回本周",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAllWeeks = false
                            if (realCurrentWeek > 0) currentWeek = realCurrentWeek
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
    val headerBottomContent: (@Composable () -> Unit) = {
        Column {
            AppSegmentedTabs(
                // 固定今日 / 日程 / 学期三格，不再随布局变化，见 plan2 §1.5。
                tabs = listOf("今日", "日程", "学期"),
                selectedTabIndex = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    // 学期栏第一次打开时才加载教材，别的栏用不上。
                    if (tab == 2 && !textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) {
                        loadTextbooks(selectedTermCode)
                    }
                },
            )
            if (currentContent == "week" && !isWideLayout) weekPillRow(Modifier)
        }
    }

    // 周选择弹窗（§1.4③）：网格选任意一周，或者切到「全学期总览」。
    if (showWeekPicker) {
        val pageWeeksForPicker = totalWeeks.takeIf { it > 0 }
            ?: filteredMergedCourses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
            ?: TermWeeks.DEFAULT_TOTAL_WEEKS
        val pickerShow = remember { mutableStateOf(true) }
        BackHandler(enabled = pickerShow.value) { pickerShow.value = false; showWeekPicker = false }
        OverlayBottomSheet(
            show = pickerShow.value,
            title = "选择周",
            onDismissRequest = { pickerShow.value = false; showWeekPicker = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                (1..pageWeeksForPicker).chunked(5).forEach { rowWeeks ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowWeeks.forEach { weekN ->
                            val hasCourse = filteredMergedCourses.any { it.isInWeek(weekN) }
                            val isSelected = !showAllWeeks && weekN == currentWeek
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        haptics.tick()
                                        showAllWeeks = false
                                        currentWeek = weekN
                                        showWeekPicker = false
                                    }
                                    .alpha(if (hasCourse) 1f else com.xjtu.toolbox.ui.components.ExpiredStyle.CONTENT_ALPHA),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MiuixTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(
                                    Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "$weekN",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                    )
                                    if (weekN == realCurrentWeek) {
                                        Text(
                                            "本周",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        // 不满 5 个时补空位，保持网格对齐。
                        repeat(5 - rowWeeks.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        showAllWeeks = true
                        showWeekPicker = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("全学期总览")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "把整学期的课叠到一张表上，看每门课占了哪些格子",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                // 弹层铺到屏幕底边，最后这行小字会压在系统导航条上；和 AppDialogs 里的弹层一样补一段导航条高度
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
    // 顶栏按钮是一段 lambda 交给外层 Scaffold 拿着的，闭包里 currentContent / api 这类
    // 普通 val 是发布那一刻的值：只发布一次的话，切到考试页加号还在、登录晚到 api 仍是 null。
    // 这两个变了就重发一份，别用 SideEffect 每帧发——外层重组会再重组这里，转起来没头。
    DisposableEffect(showTopBar, currentContent, api) {
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
        snackbarHost = {
            Box(Modifier.padding(bottom = contentBottomPadding)) {
                SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            if (showTopBar) {
                // 第几周已经在标签行下面的周选择胶囊里，副标题不用再重复一遍，只留学期名。
                SmallTopAppBar(
                    title = "日程",
                    subtitle = termLabel(selectedTermCode),
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
                                        // 快捷回当前学期：用户从历史学期切回去时不用翻列表
                                        if (termList.size > 1 && currentTermCode.isNotEmpty() &&
                                            termList.indexOf(currentTermCode) >= 0 &&
                                            currentTermCode != selectedTermCode) {
                                            DropdownImpl(
                                                text = "📍 当前学期 · ${termLabel(currentTermCode)}",
                                                optionSize = termList.size + 1,
                                                isSelected = false,
                                                onSelectedIndexChange = {
                                                    termDropdownExpanded = false
                                                    switchTerm(currentTermCode)
                                                },
                                                index = -1,
                                            )
                                        }
                                        termList.forEachIndexed { idx, term ->
                                            DropdownImpl(
                                                text = termLabel(term),
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
                        // 模式切换按钮已经挪到标签行下面的周选择胶囊里（§1.4）。
                        // 导出快捷按钮：最常用操作（日历订阅）从菜单里提升到顶栏
                        IconButton(
                            onClick = {
                                val st = startOfTerm
                                if (st == null) {
                                    android.widget.Toast.makeText(context, "无法获取开学日期，ICS 导出不可用", android.widget.Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, selectedTermCode)
                                ScheduleExport.shareTextFile(context, ics, "${selectedTermCode}_日程.ics", "text/calendar")
                            },
                            enabled = filteredMergedCourses.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "导出日历")
                        }
                        // 更多（CSV/图片/学期切换）
                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
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
        // 宽屏：课表区右侧挂一栏常驻详情。窄屏时 Row 里只剩一个 weight(1f) 的孩子，
        // 等价于改造前那一个 Column。
        Row(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(MiuixTheme.colorScheme.surface)
        ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 嵌入式 header 已迁移到 MainScreen TopAppBar.actions / bottomContent slot

            // 顶栏是玻璃、盖在内容上面时（contentTopPadding > 0）：
            // 下面几条不滚动的横幅（缓存提示、切周进度条、考试倒计时）出现时，横幅本身先让出顶栏高度，
            // 列表就不用再留；没有横幅时把留白交给各栏的滚动内容，内容才会从顶栏下面滚过去。
            val staticHeaderShown = (showingStaleData && !isLoading) ||
                (!isLoading && errorMessage == null &&
                    ((currentContent == "week" && isSwitching) || ExamCountdown.next(exams) != null))
            if (staticHeaderShown && contentTopPadding > 0.dp) Spacer(Modifier.height(contentTopPadding))
            val listTopPadding = if (staticHeaderShown) 0.dp else contentTopPadding

            // 缓存数据提示：刷新失败但有缓存时，顶部一条小 banner 告知用户「这可能是旧数据」
            if (showingStaleData && !isLoading) {
                Surface(
                    color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "网络异常，正在展示缓存数据",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .height(20.dp)
                                .clickable { if (!isRefreshingFromNetwork && api != null) refreshSchedule(true) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text("重试", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
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
                if (currentContent == "week" && isSwitching) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        height = 2.dp
                    )
                }
                // 没有独立的「考试」tab，改成常驻横幅——功能不能因为改版就消失。
                // 点开是完整考试列表。
                var showExamSheet by remember { mutableStateOf(false) }
                val nextExam = remember(exams) { ExamCountdown.next(exams) }
                nextExam?.let { n ->
                    ExamCountdownBanner(
                        n,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable { showExamSheet = true },
                    )
                }
                if (showExamSheet) {
                    val examSheetShow = remember { mutableStateOf(true) }
                    ExamListSheet(
                        show = examSheetShow,
                        exams = exams,
                        onDismiss = { showExamSheet = false },
                    )
                }
                // 今日 / 日程 / 学期三栏左右滑动切换（和其他分段标签页一样用 AppTabPager）。
                // 周视图里原来那个切周的翻页器因此关掉了手滑（见 ScheduleTabContent），
                // 两个横向手势叠在一起会互相抢；切周走周选择胶囊。
                com.xjtu.toolbox.ui.components.AppTabPager(
                    pageCount = 3,
                    selectedTabIndex = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        // 学期栏第一次打开时才加载教材，别的栏用不上（和点标签行的逻辑一致）
                        if (tab == 2 && !textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) {
                            loadTextbooks(selectedTermCode)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { tab ->
                    when (contentOf(tab)) {
                        "week" -> Column(Modifier.fillMaxSize()) {
                            // 宽屏的周胶囊行长在左栏这里，不进顶栏（见 weekPillRow）。它不随课表滚动，
                            // 所以由它自己让出玻璃顶栏的高度，下面的课表就不用再留。
                            val weekTopPadding = if (isWideLayout) {
                                weekPillRow(Modifier.padding(top = listTopPadding))
                                0.dp
                            } else {
                                listTopPadding
                            }
                            val schedulePull = rememberPullToRefreshState()
                            PullToRefresh(
                                refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                                // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
                                topAppBarScrollBehavior = topAppBarScrollBehavior,
                                isRefreshing = isRefreshingFromNetwork,
                                // 考试倒计时横幅在每个 tab 上都常驻，下拉刷新时顺带把它也刷了。
                                onRefresh = { if (api != null) { refreshSchedule(true); refreshExams() } },
                                pullToRefreshState = schedulePull,
                                // 内容铺到玻璃顶栏下面时，指示器也要从顶栏下面出来，而不是屏幕顶边
                                contentPadding = PaddingValues(top = weekTopPadding),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                ScheduleTabContent(
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
                                    onEditCustomCourse = { editingCourse = it },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = weekTopPadding,
                                    textbooks = textbooks,
                                    textbooksProblem = textbooksBackgroundError,
                                    onRequestTextbooks = {
                                        if (!textbooksLoaded && selectedTermCode.isNotEmpty()) {
                                            loadTextbooks(selectedTermCode, background = true)
                                        }
                                    },
                                    onNavigate = onNavigate,
                                    // 宽屏：周视图点课不再弹窗，写进与今日 / 学期两级同一份选中状态，
                                    // 由右栏展示。三个入口一个面板。
                                    onCourseSelected = if (isWideLayout) {
                                        { course, occurrence ->
                                            unifiedOccurrence = occurrence
                                            unifiedSelectedCourse = course
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        "today" -> {
                            val todayPull = rememberPullToRefreshState()
                            PullToRefresh(
                                refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                                // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
                                topAppBarScrollBehavior = topAppBarScrollBehavior,
                                isRefreshing = isRefreshingFromNetwork,
                                onRefresh = { if (api != null) { refreshSchedule(true); refreshExams() } },
                                pullToRefreshState = todayPull,
                                // 内容铺到玻璃顶栏下面时，指示器也要从顶栏下面出来，而不是屏幕顶边
                                contentPadding = PaddingValues(top = listTopPadding),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                TodayTimeline(
                                    courses = remember(filteredMergedCourses, realCurrentWeek) {
                                        filteredMergedCourses.filter { it.isInWeek(realCurrentWeek) }
                                    },
                                    exams = exams,
                                    today = java.time.LocalDate.now(),
                                    allCourseNames = remember(filteredMergedCourses) {
                                        filteredMergedCourses.map { it.courseName }.distinct().sorted()
                                    },
                                    onCourseClick = {
                                        unifiedOccurrence = Occurrence(
                                            java.time.LocalDate.now(), realCurrentWeek,
                                        )
                                        unifiedSelectedCourse = it
                                    },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = listTopPadding,
                                    upcoming = upcomingItems,
                                    todayHomework = homeworkDue,
                                )
                            }
                        }
                        "semester" -> {
                            val semPull = rememberPullToRefreshState()
                            PullToRefresh(
                                refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                                // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
                                topAppBarScrollBehavior = topAppBarScrollBehavior,
                                isRefreshing = textbooksRefreshing,
                                onRefresh = {
                                    refreshExams()
                                    if (selectedTermCode.isNotEmpty()) {
                                        loadTextbooks(selectedTermCode)
                                    }
                                },
                                pullToRefreshState = semPull,
                                // 内容铺到玻璃顶栏下面时，指示器也要从顶栏下面出来，而不是屏幕顶边
                                contentPadding = PaddingValues(top = listTopPadding),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                SemesterCourseList(
                                    courses = filteredMergedCourses,
                                    textbooks = textbooks,
                                    // 分级布局没有独立的「考试」页，整学期的考试就落在这一级——
                                    // 「这学期还有哪些考试」本来就是学期尺度的问题。
                                    exams = exams,
                                    // 学期一级一行代表整学期，说不出是哪一次课，
                                    // 所以不给回放也不给本次考勤。
                                    onCourseClick = {
                                        unifiedOccurrence = null
                                        unifiedSelectedCourse = it
                                    },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = listTopPadding,
                                )
                            }
                        }
                    }
                }
            }
        }

            // 宽屏常驻详情栏。选中的课来自周视图 / 今日 / 学期三个入口的**同一份**状态，
            // 没选中时展示「今天的课」，点一下就是选中。
            if (isWideLayout) {
                VerticalDivider()
                Column(
                    Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(top = contentTopPadding),
                ) {
                    // 「今日」栏左边已经是今天的时间轴，右栏再放一遍「今天的课」就是纯重复。
                    // 这时右栏自动选中现在或下一节课、直接给出它的详情（教材、考勤、回放入口）；
                    // 今天的课都上完了或者今天没课，就给本周概览。「日程」「学期」两栏照旧：右栏放今天的课是补充。
                    val weekCourses = remember(filteredMergedCourses, realCurrentWeek) {
                        filteredMergedCourses.filter { it.isInWeek(realCurrentWeek) }
                    }
                    val onTodayTab = contentOf(selectedTab) == "today"
                    val todayFocus = if (unifiedSelectedCourse == null && onTodayTab) {
                        remember(weekCourses) { focusCourseOf(weekCourses, java.time.LocalDate.now(), java.time.LocalTime.now()) }
                    } else {
                        null
                    }
                    val picked = unifiedSelectedCourse ?: todayFocus?.course
                    if (picked != null) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .overScrollVertical()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (todayFocus != null) {
                                Text(
                                    if (todayFocus.ongoing) "正在上" else "下一节",
                                    style = MiuixTheme.textStyles.footnote1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            CourseDetailContent(
                                course = picked,
                                textbooks = textbooks,
                                textbooksProblem = textbooksBackgroundError,
                                termCode = selectedTermCode,
                                occurrence = if (todayFocus != null) {
                                    Occurrence(java.time.LocalDate.now(), realCurrentWeek)
                                } else {
                                    unifiedOccurrence
                                },
                                onRequestTextbooks = {
                                    if (!textbooksLoaded && selectedTermCode.isNotEmpty()) {
                                        loadTextbooks(selectedTermCode, background = true)
                                    }
                                },
                                onNavigate = onNavigate,
                                // 常驻栏没有「关掉」这回事，不要底部那颗「知道了」。
                                onCloseAction = null,
                            )
                        }
                    } else if (onTodayTab) {
                        WeekGlance(courses = weekCourses, today = java.time.LocalDate.now())
                    } else {
                        Text(
                            "今天的课",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        TodayTimeline(
                            courses = weekCourses,
                            exams = exams,
                            today = java.time.LocalDate.now(),
                            allCourseNames = remember(filteredMergedCourses) {
                                filteredMergedCourses.map { it.courseName }.distinct().sorted()
                            },
                            onCourseClick = {
                                unifiedOccurrence = Occurrence(
                                    java.time.LocalDate.now(), realCurrentWeek,
                                )
                                unifiedSelectedCourse = it
                            },
                            bottomPadding = contentBottomPadding,
                            upcoming = upcomingItems,
                            todayHomework = homeworkDue,
                        )
                    }
                }
            }
        }
    }

    // 宽屏选中的课长在常驻右栏里，不算一层页面：返回直接走页面自己的返回逻辑，
    // 不先清选中（和教师主页同一个约定）。

    // 今日 / 学期两级点课打开的详情，用的是跟周视图完全同一个弹窗——
    // 下钻能力不能因为从哪一级点进来而不同。
    // 宽屏不走这一支：右栏已经在展示同一份选中，再弹一次就是两份。
    if (!isWideLayout) unifiedSelectedCourse?.let { course ->
        val showDetail = remember(course) { mutableStateOf(true) }
        CourseDetailDialog(
            show = showDetail,
            course = course,
            onDismiss = { unifiedSelectedCourse = null },
            textbooks = textbooks,
            textbooksProblem = textbooksBackgroundError,
            termCode = selectedTermCode,
            occurrence = unifiedOccurrence,
            onRequestTextbooks = {
                if (!textbooksLoaded && selectedTermCode.isNotEmpty()) {
                    loadTextbooks(selectedTermCode, background = true)
                }
            },
            onNavigate = onNavigate,
        )
    }
}

/** 没有独立的「考试」tab，全列表从倒计时横幅点开。用的是与学期栏同一套卡片和排序（ExamList.kt）。 */
@Composable
private fun ExamListSheet(
    show: MutableState<Boolean>,
    exams: List<ExamItem>,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = show.value) { show.value = false; onDismiss() }
    val examData = remember(exams) { sortExamsForList(exams) }
    var endedExpanded by rememberSaveable { mutableStateOf(false) }
    OverlayBottomSheet(
        show = show.value,
        title = "考试安排",
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            if (examData.total == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.EventAvailable, contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "本学期暂无考试安排",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                ) {
                    examListItems(examData, endedExpanded, { endedExpanded = !endedExpanded })
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
    onEditCustomCourse: (CustomCourseEntity) -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。见 CourseLinkSections。 */
    textbooksProblem: String? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    /**
     * 宽屏：点课不再弹详情，而是把选中送给课表右侧的常驻详情栏（由调用方持有）。
     * 窄屏传 null，走下面的本地选中 + 弹窗，行为与改造前一致。
     */
    onCourseSelected: ((CourseItem, Occurrence?) -> Unit)? = null,
) {
    val allNames = remember(courses) { courses.map { it.courseName }.distinct().sorted() }
    var selectedCourse by remember { mutableStateOf<CourseItem?>(null) }
    var selectedOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    // PR T：这是独立于 ScheduleScreen 的私有 Composable，haptics 不能从外层直接闭包过来，本地再取一份。
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    /** 两个点击入口共用：宽屏交给右栏，窄屏落回本地状态。 */
    fun selectCourse(course: CourseItem, occurrence: Occurrence?) {
        if (onCourseSelected != null) {
            onCourseSelected(course, occurrence)
        } else {
            selectedOccurrence = occurrence
            selectedCourse = course
        }
    }

    // 考勤角标。三条约束都是"别因为考勤把课表拖坏"：默认关、旁路加载、失败即无角标。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loginStateForBadge = LocalAppLoginState.current
    val badgeEnabled = remember { com.xjtu.toolbox.util.CredentialStore(ctx).scheduleAttendanceBadge }
    var attendanceIndex by remember { mutableStateOf<CourseLinks.AttendanceIndex?>(null) }
    LaunchedEffect(badgeEnabled, selectedTermCode, startOfTerm, totalWeeks) {
        if (!badgeEnabled) { attendanceIndex = null; return@LaunchedEffect }
        // 告诉 CourseLinks 这个学期结没结束——那一层没有教务会话，自己判断不了。
        // 用学期自己的起止判断而不是跟 currentTermCode 比：后者在冷启动阶段会被
        // paintCache 覆盖成正在画的学期。
        CourseLinks.attachContext(
            ctx,
            listOfNotNull(
                selectedTermCode.takeIf {
                    ScheduleCache.isFinishedByDate(startOfTerm, totalWeeks)
                },
            ),
        )
        attendanceIndex = CourseLinks.attendanceIndex(
            loginStateForBadge.sessionManager,
            loginStateForBadge.accountType,
            selectedTermCode,
        )
    }
    val absenceColor = Color(0xFFE5484D)
    val lateColor = Color(0xFFF5A524)
    val leaveColor = Color(0xFF9BA1A6)
    fun badgeOf(slot: com.xjtu.toolbox.ui.ScheduleSlot, week: Int): com.xjtu.toolbox.ui.SlotMark? {
        val idx = attendanceIndex ?: return null
        return when (idx.statusOf(week, slot.slotDayOfWeek, slot.slotStartSection)) {
            com.xjtu.toolbox.attendance.WaterType.ABSENCE -> com.xjtu.toolbox.ui.SlotMark(absenceColor)
            com.xjtu.toolbox.attendance.WaterType.LATE -> com.xjtu.toolbox.ui.SlotMark(lateColor)
            com.xjtu.toolbox.attendance.WaterType.LEAVE -> com.xjtu.toolbox.ui.SlotMark(leaveColor)
            // 正常出勤也标，用中性色。只标异常的话，全勤的人整学期一个点都看不到；
            // 而这个点本身有信息——这节课已经上过且记了考勤，没点的就是还没上。
            com.xjtu.toolbox.attendance.WaterType.NORMAL -> com.xjtu.toolbox.ui.SlotMark()
            // 未识别状态：标出来但不判定好坏，用中性色提示"有记录但看不懂"。
            com.xjtu.toolbox.attendance.WaterType.UNKNOWN -> com.xjtu.toolbox.ui.SlotMark(leaveColor)
            // 查无此格（未来的课、或没有考勤的课）不标。
            null -> null
        }
    }

    // 顶栏是玻璃、盖在内容上时：有「学期已结束 / 距开学」这条提示，就由提示先让出顶栏高度，
    // 网格不再重复留；没有提示时，留白交给网格的纵向滚动，网格才能从顶栏下面滚过去。
    // 以前漏了这一条，提示整条被压在玻璃顶栏后面。
    val gridTopPadding = if (weekNote != null) 0.dp else topPadding
    Column(Modifier.fillMaxSize().overScrollVertical()) {
        if (weekNote != null && topPadding > 0.dp) Spacer(Modifier.height(topPadding))
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

        // 一节课都没有（暑假、还没选课）。0 页 Pager 会崩，20 页空网格是原来的毛病。
        // 判的是 courses（含自定义日程）而不是 totalWeeks：后者只数教务课表，
        // 教务为空时用户自己加的日程也会被这一句挡住，加了等于没加。
        if (courses.isEmpty()) {
            EmptyState(
                title = "本学期没有课程",
                subtitle = "教务还没排课或还没选课。可以下拉刷新、在右上角「更多」里切换学期，或点 + 添加自己的日程",
                modifier = Modifier.fillMaxSize().padding(top = gridTopPadding, bottom = bottomPadding)
            )
            return@Column
        }
        // 教务课表为空、只有自定义日程时 totalWeeks 是 0，翻页器得有页可翻。
        val pageWeeks = totalWeeks.takeIf { it > 0 }
            ?: courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
            ?: TermWeeks.DEFAULT_TOTAL_WEEKS

        // 主体：每周用 Pager 横滑切周；总览单页
        if (!showAllWeeks) {
            // realCurrentWeek 加载完后重建 Pager，让 initialPage 正确停在当前周
            key(realCurrentWeek) {
            val pagerState = rememberPagerState(initialPage = (currentWeek - 1).coerceIn(0, pageWeeks - 1), pageCount = { pageWeeks })
            // currentWeek -> pager（仅在用户没正在拖拽时同步）
            LaunchedEffect(currentWeek) {
                val target = (currentWeek - 1).coerceIn(0, pageWeeks - 1)
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
                        if (w != currentWeek) {
                            haptics.tick()
                            onWeekChange(w)
                        }
                    }
            }
            // userScrollEnabled = false：左右滑动交给外层的三栏切换，切周走周选择胶囊
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) { page ->
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
                        modifier = Modifier.fillMaxSize().padding(top = gridTopPadding, bottom = bottomPadding)
                    )
                } else {
                    ScheduleGrid(
                        weekCourses, allNames,
                        isCurrentWeek = (weekN == realCurrentWeek && selectedTermCode == currentTermCode),
                        weekDates = weekDates,
                        holidayNames = holidayDates,
                        enableCompression = true,
                        weekKey = weekN,
                        bottomPadding = bottomPadding,
                        topPadding = gridTopPadding,
                        slotBadge = { badgeOf(it, weekN) },
                        onSlotClick = { item ->
                            val course = item as? CourseItem ?: return@ScheduleGrid
                            val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                            if (customEntity != null) onEditCustomCourse(customEntity)
                            else {
                                // 周视图知道是第几周、哪一天，详情面板据此只给这一次的数据。
                                selectCourse(
                                    course,
                                    weekDates?.getOrNull(course.dayOfWeek - 1)
                                        ?.let { Occurrence(it, weekN) },
                                )
                            }
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
                bottomPadding = bottomPadding,
                        topPadding = gridTopPadding,
                onSlotClick = { item ->
                    val course = item as? CourseItem ?: return@ScheduleGrid
                    val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                    if (customEntity != null) onEditCustomCourse(customEntity)
                    else {
                        // 全部周叠加一格代表很多周，说不出是哪一次。
                        selectCourse(course, null)
                    }
                }
            )
        }

    }

    // 课程详情弹窗（仅普通课程；自定义课程已在点击时分流到编辑弹窗）
    selectedCourse?.let { course ->
        val showCourseDetail = remember(course) { mutableStateOf(true) }
        CourseDetailDialog(
            show = showCourseDetail,
            course = course,
            onDismiss = { selectedCourse = null },
            textbooks = textbooks,
            textbooksProblem = textbooksProblem,
            termCode = selectedTermCode,
            occurrence = selectedOccurrence,
            onRequestTextbooks = onRequestTextbooks,
            onNavigate = onNavigate,
        )
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

/**
 * 课程详情的**内容**，不含容器。
 *
 * 手机上被 [CourseDetailDialog] 包在 OverlayBottomSheet 里（行为与抽出前一致），
 * 宽屏下直接长在课表右侧的常驻详情栏里。
 *
 * @param onCloseAction 弹窗版底部的「知道了」。分屏右栏不需要这个按钮，传 null。
 */
@Composable
private fun CourseDetailContent(
    course: CourseItem,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。 */
    textbooksProblem: String? = null,
    termCode: String = "",
    /** 这一次课是哪天、第几周；学期总览给不出，传 null。 */
    occurrence: Occurrence? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onCloseAction: (() -> Unit)? = null,
) {
    val isAgenda = course.courseType == "日程"
        // 异步获取教室座位数
        var seatCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(course.location) {
            if (course.location.isNotEmpty()) {
                seatCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { com.xjtu.toolbox.emptyroom.EmptyRoomApi().getRoomSeatCount(course.location) } catch (_: Exception) { null }
                }
            }
        }
        // 标题一段、信息一块、下钻一块。原来是所有行平铺、统一 8dp，
        // 标题、地点、时间、教材、回放一视同仁地排下来，看不出哪些是一组的。
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    course.courseName,
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                )
                course.courseType.takeIf { it.isNotBlank() && !isAgenda }?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            // 压成两行：谁在哪、什么时候。原来五行图标各占一行，一加下钻区就爆。
            val dayName = when (course.dayOfWeek) {
                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
                5 -> "五"; 6 -> "六"; 7 -> "日"; else -> "?"
            }
            val timeText = if (isAgenda) {
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
                val endHourRaw = endMinutes / 60
                val endLabel = if (endHourRaw >= 24) "次日00:00"
                else "%02d:%02d".format(endHourRaw, endMinutes % 60)
                "星期$dayName %02d:%02d-$endLabel".format(
                    (startMinutes / 60).coerceIn(0, 23), (startMinutes % 60).coerceIn(0, 59),
                )
            } else {
                "星期$dayName 第${course.startSection}-${course.endSection}节"
            }
            // 具体到某一次时直接报日期，比让人自己数第几周有用。
            val dateText = occurrence?.let {
                "${it.date.monthValue}/${it.date.dayOfMonth} · 第${it.week}周"
            } ?: course.getWeeks().takeIf { it.isNotEmpty() }?.let { "${formatWeeks(it)}周" }

            // 两行元信息合进一张卡：它们回答的是同一个问题（这门课在哪、什么时候），
            // 裸排在弹窗底色上时和下面的下钻入口分不开。
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
            ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
            if (course.teacher.isNotEmpty() || course.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (course.location.isNotEmpty()) Icons.Default.Place else Icons.Default.Person,
                        null, Modifier.size(17.dp), tint = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(
                            listOfNotNull(
                                course.location.takeIf { it.isNotEmpty() },
                                course.teacher.takeIf { it.isNotEmpty() },
                            ).joinToString("  ·  "),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    if (seatCount != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "${seatCount}座",
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CalendarMonth, null, Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primaryVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(timeText, dateText).joinToString("  ·  "),
                    style = MiuixTheme.textStyles.body2,
                )
            }
            }
            }
            // 下钻区：教材 → 全文、思源学堂、课程回放、本课考勤。见 CourseLinkSections。
            CourseLinkSections(
                course = course,
                textbooks = textbooks,
                textbooksProblem = textbooksProblem,
                termCode = termCode,
                occurrence = occurrence,
                onRequestTextbooks = onRequestTextbooks,
                onNavigate = onNavigate,
            )
        }
        if (onCloseAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCloseAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("知道了")
            }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
}

@Composable
private fun CourseDetailDialog(
    show: MutableState<Boolean>,
    course: CourseItem,
    onDismiss: () -> Unit,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。 */
    textbooksProblem: String? = null,
    termCode: String = "",
    /** 这一次课是哪天、第几周；学期总览给不出，传 null。 */
    occurrence: Occurrence? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
) {
    val close = { show.value = false; onDismiss() }
    BackHandler(enabled = show.value) { close() }
    OverlayBottomSheet(
        show = show.value,
        onDismissRequest = close,
    ) {
        CourseDetailContent(
            course = course,
            textbooks = textbooks,
            textbooksProblem = textbooksProblem,
            termCode = termCode,
            occurrence = occurrence,
            onRequestTextbooks = onRequestTextbooks,
            // 下钻跳转前先关弹窗，行为与抽出前一致。
            onNavigate = { route -> close(); onNavigate(route) },
            onCloseAction = close,
        )
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

/**
 * 下一场考试的倒计时条。常驻在页面顶部，点开是完整考试列表（ExamListSheet）。
 */
@Composable
fun ExamCountdownBanner(next: ExamCountdown.Next, modifier: Modifier = Modifier) {
    // 三天以内才转成警示色。整学期都红着，红色就不再是信号了。
    val urgent = next.daysLeft <= 3
    val accent = if (urgent) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Schedule, null, Modifier.size(18.dp), tint = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${next.label} · ${next.exam.courseName}",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    next.exam.examDate.takeIf { it.isNotBlank() },
                    next.exam.examTime.takeIf { it.isNotBlank() },
                    next.exam.location.takeIf { it.isNotBlank() },
                ).joinToString("  ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

