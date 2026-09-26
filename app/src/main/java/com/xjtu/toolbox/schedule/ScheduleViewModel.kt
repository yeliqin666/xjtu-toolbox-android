package com.xjtu.toolbox.schedule

import android.content.Context
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.home.HomeSignals
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.LmsDueStore
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal sealed interface ScheduleEvent {
    data object AuthExpired : ScheduleEvent
    /** 网络课表落地，报一次成功触感。 */
    data object Loaded : ScheduleEvent
    data class Message(val text: String, val long: Boolean = false) : ScheduleEvent
}

private data class ScheduleDiskSnapshot(
    val termList: List<String> = emptyList(),
    val termCode: String = "",
    val courses: List<CourseItem> = emptyList(),
    val exams: List<ExamItem> = emptyList(),
    val startDate: LocalDate? = null,
)

private fun readScheduleDiskSnapshot(dataCache: DataCache): ScheduleDiskSnapshot {
    val termList = ScheduleCache.readTermList(dataCache)
    val termCode = ScheduleCache.readLastTerm(dataCache) ?: termList.firstOrNull().orEmpty()
    if (termCode.isEmpty()) return ScheduleDiskSnapshot(termList = termList)
    return ScheduleDiskSnapshot(
        termList = termList,
        termCode = termCode,
        courses = ScheduleCache.readCourses(dataCache, termCode).orEmpty(),
        exams = ScheduleCache.readExams(dataCache, termCode).orEmpty(),
        startDate = ScheduleCache.readStartDate(dataCache, termCode),
    )
}

/**
 * 日程 tab：课表、考试、教材、自定义日程。挂在主页面上，切 tab 不丢；同一账号只自动加载一次，
 * 用户翻到的历史学期不会被再次加载拽回当前学期。
 */
internal class ScheduleViewModel(context: Context, private val login: AppLoginState) : ViewModel() {
    private val context = context.applicationContext
    private val customCourseDao = AppDatabase.getInstance(this.context).customCourseDao()
    private val eventChannel = Channel<ScheduleEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var accountId by mutableStateOf(login.accountId)
    // DataCache 构造时绑定账号，切账号后换新实例
    private var dataCache = DataCache(this.context, accountId.ifEmpty { null })
    private val disk = readScheduleDiskSnapshot(dataCache)

    var activeSite by mutableStateOf<SiteSession?>(null); private set
    var api by mutableStateOf<ScheduleApi?>(null); private set
    private var studentId = ""

    var courses by mutableStateOf(disk.courses); private set
    var exams by mutableStateOf(disk.exams); private set
    /** 「接下来」的作业截止：只读别处写好的落盘缓存，这里不发请求。 */
    var homeworkDue by mutableStateOf<List<LmsDue>>(emptyList()); private set
    var textbooks by mutableStateOf<List<TextbookItem>>(emptyList()); private set
    var textbooksLoading by mutableStateOf(false); private set
    var textbooksError by mutableStateOf<String?>(null); private set
    var textbooksLoaded by mutableStateOf(false); private set
    var textbooksRefreshing by mutableStateOf(false); private set
    /** 后台加载（课程详情顺带取教材）的失败原因，和教材页自己的 [textbooksError] 分开。 */
    var textbooksBackgroundError by mutableStateOf<String?>(null); private set
    private var examsRefreshing = false
    var isLoading by mutableStateOf(disk.courses.isEmpty()); private set
    /** 学期切换中：保留旧日程显示。 */
    var isSwitching by mutableStateOf(false); private set
    /** 缓存已显示，后台刷新中。 */
    var isRefreshingFromNetwork by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** 网络失败、正显示缓存。 */
    var showingStaleData by mutableStateOf(disk.courses.isNotEmpty()); private set

    private val initialWeek = disk.startDate?.let { start ->
        runCatching {
            val w = TermWeeks.weekOf(start)
            val diskWeeks = disk.courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 } ?: 30
            if (w in 1..diskWeeks) w else 0
        }.getOrDefault(0)
    } ?: 0
    var currentWeek by mutableIntStateOf(if (initialWeek > 0) initialWeek else 1)
    /** 实际当前周（0 = 未知），时间线用。 */
    var realCurrentWeek by mutableIntStateOf(initialWeek); private set
    /** 「距开学 X 周」/「学期已结束」。 */
    var weekNote by mutableStateOf<String?>(null); private set
    /** 周视图 vs 全学期总览；学期已结束时自动切到总览。 */
    var showAllWeeks by mutableStateOf(false)

    var termList by mutableStateOf(disk.termList); private set
    var selectedTermCode by mutableStateOf(disk.termCode); private set
    /** 教务的当前学期，用来判断是不是在看历史学期。 */
    var currentTermCode by mutableStateOf(disk.termCode); private set
    /** 本次会话里用户主动切过学期：之后的加载不再把视图拽回当前学期。 */
    private var userPickedTerm = false

    var startOfTerm by mutableStateOf(disk.startDate); private set
    var holidayDates by mutableStateOf(HolidayApi.peekCached(this.context)); private set

    var customCourses by mutableStateOf<List<CustomCourseEntity>>(emptyList()); private set
    var addScheduleDraft by mutableStateOf(CustomCourseDraft())
    /** 待确认的冲突：(要保存的, 与之冲突的)。 */
    var pendingSave by mutableStateOf<Pair<CustomCourseEntity, List<CustomCourseEntity>>?>(null)

    /**
     * 学期周数，取教务下发的 weekBits 长度；0 表示这学期没课。
     * 在协程里直接读 [courses] 拿到的是最新值，不存在「课表刚到、周数还是旧的」的中间态。
     */
    val totalWeeks by derivedStateOf { weeksOf(courses) }

    private var loadJob: Job? = null
    private var loadGen = 0
    private var loadedAccount: String? = null
    private var loadedAt = 0L
    private var loginWatch: Job? = null
    private var lastSource = CredentialStore(this.context).scheduleSource
    private var attemptingAutoLogin = false

    init {
        // 自定义日程订阅：学期或账号一变就换一条订阅
        viewModelScope.launch {
            snapshotFlow { selectedTermCode to accountId }.distinctUntilChanged().collectLatest { (term, _) ->
                if (term.isEmpty()) return@collectLatest
                customCourseDao.observeByTerm(AccountContext.activeAccountId ?: "", term).collect { customCourses = it }
            }
        }
    }

    private fun weeksOf(list: List<CourseItem>) = list.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 } ?: 0

    /** 添加 / 编辑日程弹窗用的周数：教务课表为空时退到自定义日程里最长的，再退到默认周数。 */
    fun editableWeeks(): Int = totalWeeks.takeIf { it > 0 }
        ?: customCourses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
        ?: TermWeeks.DEFAULT_TOTAL_WEEKS

    fun termLabel(code: String): String = ScheduleTermStore.display(code, dataCache, api)

    private fun send(event: ScheduleEvent) { viewModelScope.launch { eventChannel.send(event) } }

    // ── 绑定：账号、会话、学号 ──

    fun bind(account: String, site: SiteSession?, studentId: String) {
        this.studentId = studentId
        bindSite(site)
        if (account == loadedAccount) {
            // 隔了一阵子再切回这个 tab：静默刷新一次（用户切过的学期保留）
            if (System.currentTimeMillis() - loadedAt > STALE_MS && !isLoading && !isSwitching && !isRefreshingFromNetwork) loadInitialData()
            return
        }
        if (loadedAccount != null) {
            courses = emptyList()
            exams = emptyList()
            customCourses = emptyList()
            dataCache = DataCache(context, account.ifEmpty { null })
        }
        loadedAccount = account
        accountId = account
        loadInitialData()
        viewModelScope.launch {
            try { holidayDates = HolidayApi.getHolidayDates(context) } catch (_: Exception) {}
            homeworkDue = withContext(Dispatchers.IO) { runCatching { LmsDueStore.load(context, account) }.getOrDefault(emptyList()) }
        }
    }

    private fun bindSite(site: SiteSession?) {
        if (site === activeSite) return
        val appeared = site != null && activeSite == null && loadedAccount != null
        setSite(site)
        // 先进来时还没登录、稍后教务会话才就绪：补一次在线刷新
        if (appeared && !isLoading && !isSwitching && !isRefreshingFromNetwork) loadInitialData()
    }

    private fun setSite(site: SiteSession?) {
        activeSite = site
        api = site?.let { ScheduleApi(it) }
        watchLogin(site)
    }

    /**
     * site 存在 ≠ 已登录：冷启动时 CAS 登录是异步的，首屏请求常被登录页顶包、落成「本学期没有课程」。
     * 轮询 hasLogin，登录一完成就补一次加载；最多等 5 分钟。
     */
    private fun watchLogin(site: SiteSession?) {
        loginWatch?.cancel()
        if (site == null || site.hasLogin) return
        loginWatch = viewModelScope.launch {
            repeat(300) {
                if (site.hasLogin) {
                    if (!isSwitching && !isRefreshingFromNetwork) loadInitialData()
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    /** 没会话但有凭据、网络在线：后台登一次教务。 */
    fun autoLoginIfNeeded() {
        if (activeSite != null || !login.hasCredentials || attemptingAutoLogin) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val online = cm?.activeNetwork != null &&
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) return
        attemptingAutoLogin = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { login.sessionManager?.ensureSite(LoginType.JWXT) }?.let { bindSite(it) }
            } catch (_: Exception) {}
            attemptingAutoLogin = false
        }
    }

    /** 设置里换了「当前学期课表来源」以后回到这里：按新来源重新加载。 */
    fun onResume() {
        val now = CredentialStore(context).scheduleSource
        if (now == lastSource) return
        lastSource = now
        if (!isLoading && !isSwitching && !isRefreshingFromNetwork) loadInitialData()
    }

    /** 错误页的重试：没会话先登录，有会话强制重新认证一次。 */
    fun retry() {
        val site = activeSite
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            if (site == null && login.hasCredentials) {
                attemptingAutoLogin = true
                try {
                    withContext(Dispatchers.IO) { login.sessionManager?.ensureSite(LoginType.JWXT) }?.let { setSite(it) }
                } catch (_: Exception) {}
                attemptingAutoLogin = false
            } else if (site != null) {
                try {
                    withContext(Dispatchers.IO) {
                        login.sessionManager?.credentials?.let { (user, password) -> site.ensureLogin(user, password, force = true) }
                    }
                } catch (_: Exception) {}
            }
            loadInitialData()
        }
    }

    // ── 课表 ──

    /** 课表走用户选的来源；历史学期和非教务源取不到时退回教务，见 ScheduleSourceRouter。 */
    private suspend fun fetchSchedule(scheduleApi: ScheduleApi, term: String, userInitiated: Boolean = false): List<CourseItem> =
        ScheduleSourceRouter.getSchedule(
            context = context,
            jwxt = scheduleApi,
            termCode = term,
            manager = login.sessionManager,
            accountType = login.accountType,
            userInitiated = userInitiated,
        )

    private fun readCachedTerms(): List<String> = ScheduleCache.readTermList(dataCache)

    private fun applyTermStart(startDate: LocalDate) {
        startOfTerm = startDate
        try {
            val status = TermWeeks.statusOf(
                startOfTerm = startDate,
                totalWeeks = weeksOf(courses),
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
    private fun paintCache(termCode: String): Int {
        if (termCode.isEmpty()) return -1
        selectedTermCode = termCode
        currentTermCode = termCode
        ScheduleCache.readCourses(dataCache, termCode)?.let { courses = it }
        ScheduleCache.readExams(dataCache, termCode)?.let { exams = it }
        ScheduleCache.readStartDate(dataCache, termCode)?.let { applyTermStart(it) }
        return courses.size
    }

    /** 初始加载：有缓存先上屏，课表接口一到就停转圈；考试 / 学期列表后台补。 */
    fun loadInitialData() {
        loadJob?.cancel()
        errorMessage = null
        if (courses.isEmpty()) isLoading = true
        isRefreshingFromNetwork = false
        showingStaleData = false
        val gen = ++loadGen
        loadedAt = System.currentTimeMillis()
        val api = api
        // 切账号时 SessionManager 原地重配，api 背后的站点会换成新账号的会话；
        // 每次联网结果落地前核对账号，变了就按取消处理，既不刷界面也不写缓存
        val jobAccount = AccountContext.activeAccountId
        fun ensureSameAccount() {
            if (AccountContext.activeAccountId != jobAccount) throw CancellationException("account switched during schedule load")
        }
        var suggestion: String? = null
        loadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cachedTerms = readCachedTerms()
                    if (cachedTerms.isNotEmpty()) termList = cachedTerms
                    val lastTerm = ScheduleCache.readLastTerm(dataCache) ?: cachedTerms.firstOrNull().orEmpty()
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
                    try {
                        suggestion = loadOnline(api, lastTerm, ::ensureSameAccount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AuthExpiredException) {
                        throw e
                    } catch (e: Exception) {
                        fallBackToCache(e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                eventChannel.send(ScheduleEvent.AuthExpired)
            } catch (e: Exception) {
                errorMessage = FriendlyError.of(e, "加载课表")
            } finally {
                if (gen == loadGen) {
                    isLoading = false
                    isRefreshingFromNetwork = false
                    ScheduleWidgetUpdater.requestUpdate(context)
                }
            }
            suggestion?.takeIf { it != selectedTermCode }?.let { target ->
                switchTerm(target, byUser = false)
                eventChannel.send(ScheduleEvent.Message("教务的当前学期还没有课表，已切到${termLabel(target)}", long = true))
            }
        }
    }

    /**
     * 学期接口和课表并行：有上次学期时先按缓存学期拉课表立刻上屏，学期代码回来再对一下。
     * 返回要自动切过去的学期（当前学期没课、按日期推算的学期有课时），否则 null。
     */
    private suspend fun loadOnline(api: ScheduleApi, lastTerm: String, ensureSameAccount: () -> Unit): String? = supervisorScope {
        val termDeferred = async {
            try {
                api.getCurrentTerm()
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentTerm failed, trying cache", e)
                // 教务的「当前学期」接口偶发返回空行或超时：按日期推一个，别把整页拖成错误页
                lastTerm.ifEmpty { XjtuTime.expectedTermCode() ?: throw RuntimeException("网络不可用且无缓存学期数据，请连网后重试") }
            }
        }
        val schedulePrefetch = lastTerm.takeIf { it.isNotEmpty() }?.let { cached ->
            async {
                try { fetchSchedule(api, cached) } catch (e: AuthExpiredException) { throw e } catch (_: Exception) { null }
            }
        }
        val examsDeferred = async {
            val termForExam = lastTerm.ifEmpty { termDeferred.await() }
            try {
                api.getExamSchedule(termForExam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getExamSchedule failed; keeping cached/empty exams", e)
                exams
            }
        }
        val startDateDeferred = async {
            val termForStart = lastTerm.ifEmpty { termDeferred.await() }
            try { api.getStartOfTerm(termForStart) } catch (_: Exception) { null }
        }
        val termListDeferred = async { try { api.getTermList() } catch (_: Exception) { emptyList() } }

        /** 网络课表落地的唯一入口：缓存、首页信号、变更检测都在这里。 */
        fun paintCourses(termCode: String, freshCourses: List<CourseItem>, startDate: LocalDate?) {
            ensureSameAccount()
            val holidays = holidayDates.ifEmpty { HolidayApi.peekCached(context) }
            if (holidays.isNotEmpty()) holidayDates = holidays
            val optimized = ScheduleCache.filterByHolidays(freshCourses, startDate, holidays)
            // 比对象不比 JSON 文本：新旧版本写出的格式不同，逐字比较会误报「日程有更新」
            val cachedOptimized = ScheduleCache.readOptimizedCourses(dataCache, termCode, Long.MAX_VALUE)
            val contentChanged = cachedOptimized == null || cachedOptimized != optimized.map { it.normalized() }
            if (courses.isEmpty() || contentChanged) courses = optimized
            showingStaleData = false
            isLoading = false
            isRefreshingFromNetwork = false
            send(ScheduleEvent.Loaded)
            ScheduleCache.writeRawCourses(dataCache, termCode, freshCourses)
            ScheduleCache.writeOptimizedCourses(dataCache, termCode, optimized)
            // 叫醒首页：Hero 的「下一项安排」认 HomeSignals.scheduleVersion
            HomeSignals.scheduleVersion++
            if (contentChanged && cachedOptimized != null) send(ScheduleEvent.Message("日程有更新"))
            // 用未过滤节假日的课表比，否则放假会被误判成「课被取消了」
            ScheduleDiff.summarize(ScheduleDiff.diffAndStore(context, termCode, freshCourses))?.let { msg ->
                ScheduleDiff.setPending(context, msg)
                send(ScheduleEvent.Message(msg, long = true))
            }
        }

        val prefetched = schedulePrefetch?.await()
        if (prefetched != null) paintCourses(lastTerm, prefetched, startOfTerm)

        val termCode = termDeferred.await()
        ensureSameAccount()
        currentTermCode = termCode
        ScheduleCache.writeCurrentTerm(dataCache, termCode)
        // 用户本次主动切过学期时，不要再把视图拽回当前学期
        val keepUserTerm = userPickedTerm && lastTerm.isNotEmpty() && lastTerm != termCode
        if (!keepUserTerm) {
            selectedTermCode = termCode
            ScheduleCache.writeLastTerm(dataCache, termCode)
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
                ScheduleCache.writeStartDate(dataCache, termCode, startDate)
            }
            val freshExams = try { api.getExamSchedule(termCode) } catch (_: Exception) { exams }
            ensureSameAccount()
            exams = freshExams
            if (freshExams.isNotEmpty()) ScheduleCache.writeExams(dataCache, termCode, freshExams)
        } else {
            // 留在用户选的学期时认那一个学期：prefetch / 考试 / 开学日期本来就是按 lastTerm 发的
            val viewTerm = if (keepUserTerm) lastTerm else termCode
            val freshCourses = prefetched ?: fetchSchedule(api, viewTerm)
            if (prefetched == null) paintCourses(viewTerm, freshCourses, startOfTerm)
            val startDate = startDateDeferred.await() ?: startOfTerm
            ensureSameAccount()
            if (startDate != null) {
                applyTermStart(startDate)
                ScheduleCache.writeStartDate(dataCache, viewTerm, startDate)
                if (holidayDates.isNotEmpty()) courses = ScheduleCache.filterByHolidays(freshCourses, startDate, holidayDates)
            }
            val freshExams = examsDeferred.await()
            ensureSameAccount()
            exams = freshExams
            if (freshExams.isNotEmpty()) ScheduleCache.writeExams(dataCache, viewTerm, freshExams)
        }
        // 换季那几周教务的「当前学期」常还指着短学期 / 暑假，课表是空的：按日期推一个学期探一下
        var suggestion: String? = null
        if (!keepUserTerm && courses.isEmpty()) {
            val expected = XjtuTime.expectedTermCode()
            if (expected != null && expected != termCode) {
                val probe = try { fetchSchedule(api, expected) } catch (_: Exception) { emptyList() }
                if (probe.isNotEmpty()) suggestion = expected
            }
        }
        val availableTerms = (termListDeferred.await() + readCachedTerms()).distinct()
        ensureSameAccount()
        try { ScheduleTermStore.merge(dataCache, api.termNames()) } catch (_: Exception) {}
        if (availableTerms.isNotEmpty()) {
            termList = availableTerms
            ScheduleCache.writeTermList(dataCache, availableTerms)
        }
        suggestion
    }

    private fun fallBackToCache(e: Exception) {
        if (courses.isNotEmpty()) {
            showingStaleData = true
            isRefreshingFromNetwork = false
            if (termList.isEmpty()) readCachedTerms().takeIf { it.isNotEmpty() }?.let { termList = it }
            send(ScheduleEvent.Message("网络异常，显示的可能不是最新数据", long = true))
            Log.w(TAG, "Network failed, showing cached data", e)
            return
        }
        Log.w(TAG, "Online failed, falling back to cache", e)
        val terms = readCachedTerms()
        if (terms.isNotEmpty()) termList = terms
        val fallbackTerm = selectedTermCode.ifEmpty { terms.firstOrNull().orEmpty() }
        if (fallbackTerm.isNotEmpty()) paintCache(fallbackTerm)
        if (courses.isEmpty()) throw RuntimeException("网络不可用且无缓存数据，请连网后重试")
        showingStaleData = true
        isRefreshingFromNetwork = false
        send(ScheduleEvent.Message("网络异常 · 显示缓存日程", long = true))
    }

    /** 下拉刷新：刷新正在看的学期，不去拉当前学期再把视图切回去。 */
    fun refreshSchedule() {
        val api = api ?: return
        if (isRefreshingFromNetwork) return
        isRefreshingFromNetwork = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val viewing = selectedTermCode
                    val actualCurrent = try {
                        api.getCurrentTerm()
                    } catch (e: Exception) {
                        viewing.ifEmpty { readCachedTerms().firstOrNull() ?: throw e }
                    }
                    if (actualCurrent.isNotEmpty()) {
                        currentTermCode = actualCurrent
                        ScheduleCache.writeCurrentTerm(dataCache, actualCurrent)
                    }
                    try { ScheduleTermStore.merge(dataCache, api.termNames()) } catch (_: Exception) {}
                    val termCode = viewing.ifEmpty { actualCurrent }
                    if (viewing.isEmpty() && termCode.isNotEmpty()) selectedTermCode = termCode
                    val apiCourses = try {
                        fetchSchedule(api, termCode, userInitiated = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "refreshSchedule getSchedule failed", e)
                        return@withContext
                    }
                    // courses 只放教务结果，自定义日程另外拼，免得刷新后重复
                    courses = apiCourses
                    showingStaleData = false
                    ScheduleCache.writeRawCourses(dataCache, termCode, apiCourses)
                    // optimized 键也要跟上：首页 Hero 只认它
                    ScheduleCache.writeOptimizedCourses(dataCache, termCode, ScheduleCache.filterByHolidays(apiCourses, startOfTerm, holidayDates))
                    HomeSignals.scheduleVersion++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refreshSchedule failed", e)
                errorMessage = FriendlyError.of(e, "刷新课表")
            } finally {
                isRefreshingFromNetwork = false
            }
        }
    }

    fun refreshExams() {
        val api = api ?: return
        val term = selectedTermCode
        if (term.isEmpty() || examsRefreshing) return
        examsRefreshing = true
        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    api.getExamSchedule(term).also { ScheduleCache.writeExams(dataCache, term, it) }
                }
                exams = fresh
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                eventChannel.send(ScheduleEvent.AuthExpired)
            } catch (e: Exception) {
                eventChannel.send(ScheduleEvent.Message(FriendlyError.of(e, "刷新考试")))
            } finally {
                examsRefreshing = false
            }
        }
    }

    // ── 教材 ──

    /**
     * 学期栏需要教材、还没加载过时拉一次。[requireApi] 为 true 时等教务会话就绪再拉（会话晚到会随 [api] 变化补上）；
     * 用户点进学期栏时传 false，没登录就直接给出「尚未登录」的提示。
     */
    fun ensureTextbooks(requireApi: Boolean = true) {
        if (requireApi && api == null) return
        if (textbooksLoaded || textbooksLoading || selectedTermCode.isEmpty()) return
        textbooksError = null
        loadTextbooks(selectedTermCode)
    }

    /** 课程详情面板顺带取教材：没人在等，失败不抢导航。 */
    fun requestTextbooksInBackground() {
        if (!textbooksLoaded && selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode, background = true)
    }

    fun reloadTextbooks() {
        if (selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode)
    }

    /**
     * [background] 说的是有没有人在等结果：true 时不占页面加载态、不写教材页的提示条，
     * 登录过期也不弹回登录——用户只是点开了一门课。两条路径都先上缓存。
     */
    private fun loadTextbooks(termCode: String, background: Boolean = false) {
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
            // 面板反复开合会一直打这个请求，后台这条防重入
            if (textbooksRefreshing) return
            textbooksRefreshing = true
            textbooksBackgroundError = null
        } else {
            if (textbooksLoaded) textbooksRefreshing = true else textbooksLoading = true
            textbooksError = null
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ScheduleCache.readTextbooks(dataCache, termCode, Long.MAX_VALUE)?.let { cached ->
                        textbooks = cached.sortedBy { if (it.hasSubstantiveTextbook) 0 else 1 }
                        textbooksLoaded = true
                    }
                    // 有教材的在前
                    textbooks = jw.getTextbooks(studentId, termCode).sortedBy { if (it.hasSubstantiveTextbook) 0 else 1 }
                    ScheduleCache.writeTextbooks(dataCache, termCode, textbooks)
                }
                textbooksLoaded = true
                if (background) textbooksBackgroundError = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                if (background) textbooksBackgroundError = "教务登录已过期，去教材页刷新一次"
                else eventChannel.send(ScheduleEvent.AuthExpired)
            } catch (e: Exception) {
                Log.e(TAG, "loadTextbooks failed background=$background", e)
                val msg = FriendlyError.of(e, "查询教材")
                if (background) textbooksBackgroundError = msg else textbooksError = msg
            } finally {
                textbooksLoading = false
                textbooksRefreshing = false
            }
        }
    }

    // ── 学期 ──

    /** 添加日程前要有学期：还没拿到时按当前学期 / 学期表 / 日期推一个，推不出返回 false。 */
    fun ensureTermForAdd(): Boolean {
        if (selectedTermCode.isNotEmpty()) return true
        val fallback = currentTermCode.ifEmpty { termList.firstOrNull() ?: XjtuTime.expectedTermCode().orEmpty() }
        if (fallback.isEmpty()) return false
        selectedTermCode = fallback
        return true
    }

    fun switchTerm(newTermCode: String, byUser: Boolean = true) {
        if (newTermCode == selectedTermCode) return
        if (byUser) userPickedTerm = true
        selectedTermCode = newTermCode
        ScheduleCache.writeLastTerm(dataCache, newTermCode)
        textbooksLoaded = false
        textbooks = emptyList()
        // 考试也要清：新学期没缓存时留着上学期的考试，比空着更糟
        exams = emptyList()
        showingStaleData = false
        val api = api
        viewModelScope.launch {
            isSwitching = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) { loadTerm(api, newTermCode) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                eventChannel.send(ScheduleEvent.AuthExpired)
            } catch (e: Exception) {
                errorMessage = FriendlyError.of(e, "切换学期")
            } finally {
                isSwitching = false
                ScheduleWidgetUpdater.requestUpdate(context)
            }
        }
    }

    private suspend fun loadTerm(api: ScheduleApi?, term: String) {
        val isOldTerm = term != currentTermCode
        ScheduleCache.readCourses(dataCache, term)?.let { cached ->
            courses = cached
            ScheduleCache.readExams(dataCache, term, DataCache.TERM_TTL_MS)?.let { exams = it }
        }
        // 已结束且本地是全的：一个请求都不发，想强制重拉走下拉刷新
        val sealed = ScheduleCache.isSealed(dataCache, term)
        if (api != null && !sealed) {
            try {
                val freshCourses = fetchSchedule(api, term, userInitiated = true)
                exams = api.getExamSchedule(term)
                val freshStartDate = try { api.getStartOfTerm(term) } catch (_: Exception) { null }
                val freshHolidays = try { HolidayApi.getHolidayDates(context, forceRefresh = true) } catch (_: Exception) { emptyMap() }
                holidayDates = freshHolidays
                courses = ScheduleCache.filterByHolidays(freshCourses, freshStartDate, freshHolidays)
                // 考试不分当前 / 历史一律落盘：否则这学期看过的考试等它封存后就再也拿不到
                ScheduleCache.writeExams(dataCache, term, exams)
                if (isOldTerm) {
                    ScheduleCache.writeRawCourses(dataCache, term, freshCourses)
                    ScheduleCache.writeOptimizedCourses(dataCache, term, courses)
                    if (freshStartDate != null) ScheduleCache.writeStartDate(dataCache, term, freshStartDate)
                }
                if (freshStartDate != null) startOfTerm = freshStartDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (courses.isEmpty()) throw e
                showingStaleData = true
                send(ScheduleEvent.Message("网络异常，显示缓存数据"))
            }
        } else if (api == null) {
            showingStaleData = true
        }
        // 当前周
        try {
            val startDate = if (api != null && !sealed) {
                try { api.getStartOfTerm(term) } catch (_: Exception) { null }
            } else {
                ScheduleCache.readStartDate(dataCache, term)
            }
            if (startDate != null) {
                startOfTerm = startDate
                if (api != null) ScheduleCache.writeStartDate(dataCache, term, startDate)
                val status = TermWeeks.statusOf(startOfTerm = startDate, totalWeeks = weeksOf(courses), firstTeachWeek = TermWeeks.firstTeachWeekOf(courses))
                if (status is TermWeeks.Status.AfterTerm) showAllWeeks = true
                currentWeek = TermWeeks.displayWeekOf(status)
                weekNote = TermWeeks.noteOf(status)
            } else {
                currentWeek = 1; weekNote = null
            }
        } catch (_: Exception) {
            currentWeek = 1; weekNote = null
        }
    }

    // ── 自定义日程 ──
    //
    // 冲突只在周次、星期、时间都重叠时才算，交给用户选：替换 / 都保留 / 取消

    fun saveCustomCourse(entity: CustomCourseEntity) {
        viewModelScope.launch {
            val accountId = AccountContext.activeAccountId ?: ""
            val withAccount = if (entity.accountId.isBlank()) entity.copy(accountId = accountId) else entity
            // DAO 只按星期和节次粗筛，周次与分钟级时间在这里精判
            val conflicts = customCourseDao
                .getConflicts(accountId, withAccount.termCode, withAccount.dayOfWeek, withAccount.startSection, withAccount.endSection)
                .filter { it.id != withAccount.id && CustomCourseConflicts.conflicts(withAccount, it) }
            if (conflicts.isEmpty()) commitCustomCourse(withAccount, emptyList()) else pendingSave = withAccount to conflicts
        }
    }

    fun commitCustomCourse(entity: CustomCourseEntity, replacing: List<CustomCourseEntity>) {
        pendingSave = null
        viewModelScope.launch {
            replacing.forEach { customCourseDao.delete(it) }
            if (entity.id == 0L) {
                customCourseDao.insert(entity)
                // 草稿等真正写库后再清：撞上冲突选「取消」时重新打开还能看到
                addScheduleDraft = CustomCourseDraft()
            } else {
                customCourseDao.update(entity)
            }
            customCourses = customCourseDao.getByTerm(entity.accountId, selectedTermCode)
            ScheduleWidgetUpdater.requestUpdate(context)
            val verb = if (entity.id == 0L) "已添加日程" else "已更新日程"
            eventChannel.send(ScheduleEvent.Message(
                if (replacing.isEmpty()) verb else "$verb，并替换了「${replacing.joinToString("、") { it.courseName }}」"
            ))
        }
    }

    fun deleteCustomCourse(entity: CustomCourseEntity) {
        viewModelScope.launch {
            customCourseDao.delete(entity)
            customCourses = customCourseDao.getByTerm(AccountContext.activeAccountId ?: "", selectedTermCode)
            ScheduleWidgetUpdater.requestUpdate(context)
            eventChannel.send(ScheduleEvent.Message("已删除「${entity.courseName}」"))
        }
    }

    private companion object {
        const val TAG = "ScheduleViewModel"
        const val STALE_MS = 30 * 60_000L
    }
}
