package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsDue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

/**
 * 日程页里那些**只有某些端才真会照着办**的能力。
 *
 * ## 为什么要有这张清单
 *
 * 同 `SettingsHost.honoredRows` 那条纪律：屏上有一档东西，如果这一端**根本没有**，就不该画
 * （「点了会失败的按钮一个都不画」）。日程页上比较隐蔽的几档是：
 * 「导出日历 (ICS)」（要一个系统分享面板）、「本次考勤」与格子上的考勤角标（要考勤站点会话）、
 * 「N 座」（要空闲教室那套取数）、今日栏的「接下来」里的作业（要思源那份落盘缓存）。
 *
 * 桌面端这几档多数没有 ⇒ 它只把真接上的列进 [ScheduleHost.honored]，其余**一个字都不画**
 * （见 `:desktop` 的 `DesktopScheduleHost` 里那张表与 TODO）。
 */
enum class ScheduleCapability {

    /** 能加/改/删自建日程（决定工具栏的「+」与「编辑日程」入口画不画）。 */
    CUSTOM_COURSE_EDIT,

    /** 能把 ICS 交给系统（决定「导出日历 (ICS)」那一行菜单画不画）。 */
    SHARE_FILE,

    /** 有考勤那一档：课表格子上的角标，以及详情里的「本次考勤」一行。 */
    ATTENDANCE,

    /** 能查教室座位数（决定详情里「N座」那一格画不画）。 */
    SEAT_COUNT,

    /** 能配思源学堂入口（决定详情里「思源学堂」那一行画不画）。 */
    LMS_LINK,

    /** 有「接下来」的作业截止（决定今日栏那一档画不画）。 */
    DUE_HOMEWORK,

    /** 课表落地后要刷新桌面小组件。 */
    WIDGET_REFRESH,

    /** 课表落地后要给首页发信号（首页 Hero 靠它重读课表缓存）。 */
    HOME_SIGNALS,

    /** 有联网判定（没网就不在后台自动登教务）。 */
    ONLINE_CHECK,

    /** 课表变更（调课/停课/换教室）要冒一条气泡。 */
    SCHEDULE_CHANGE_BUBBLE,
}

/**
 * 日程页的**宿主槽位** —— 「这一端有什么」，而不是「这一端是不是 Android」。
 *
 * ## 三类东西走这里（其余一律留在共享屏里）
 *
 * 1. **只有某些端真会照着办的能力**：[honored]（见 [ScheduleCapability]）；
 * 2. **平台专属的动作**：本地落盘（[cache] / [customCourses]）、教务会话与取数（[session]）、
 *    系统分享（[shareFile]）、联网判定（[isOnline]）、课表落地后的通知（[onScheduleSynced]）；
 *    （「会话过期」不在这条缝上 —— 那是 `:core` 既有的 `LocalAuthExpiry`，见 [ScheduleLogin]。）
 * 3. **宿主才知道的数据**：哪些课在思源学堂、这一次课考勤记的是什么（[courseLinks]）、
 *    教室座位数（[roomSeatCount]）。
 *
 * ## 语义债
 *
 * 这些动作的本体与搬迁前逐字相同，只是从「直接摸 `Context` / `AppDatabase`」变成「问宿主」。
 * 每一个成员都**不是**新语义：`shareFile` 就是 `ScheduleExport.shareTextFile`、
 * `onScheduleSynced` 就是 `HomeSignals.scheduleVersion++` 加 `ScheduleWidgetUpdater.requestUpdate`、
 * `dueHomeworks` 就是 `LmsDueStore.load`。
 */
interface ScheduleHost {

    /** 这一端**真会照着办**的那些能力（见 [ScheduleCapability]）。 */
    val honored: Set<ScheduleCapability>

    /**
     * 本地落盘那一族（[cache] / [customCourses]）的调度器。
     *
     * 为什么要有这一格：`ScheduleCacheStore` 是**同步**的（读一段 JSON、写一段 JSON），
     * 而 `:core` 的 commonMain 里没有 `Dispatchers.IO`（wasm 目标更没有）。所以「谁去跑阻塞
     * 文件 IO」只能由端上来说：`:app` 与桌面传 `Dispatchers.IO`（与搬迁前同一个池），
     * 没接的端走 `Dispatchers.Default` —— 与 `DownloadManager` 的 `scope` 同一条口径。
     */
    val io: CoroutineDispatcher get() = Dispatchers.Default

    /**
     * 按账号开一份课表族缓存。[accountId] 为 null 表示「当前账号」。
     *
     * `:app` = `DataCache`（目录名、文件名、账号后缀、原子写入全是原来那一份）；
     * 桌面 = 一份键值文件（没有 `cacheDir`）。
     */
    fun cache(accountId: String?): ScheduleCacheStore

    /** 自建日程那张表（见 [CustomCourseStore]）：`:app` = Room，桌面 = 键值文件。 */
    val customCourses: CustomCourseStore

    /** 登录态：当前账号、学号、有没有存下凭据、会话过期怎么处理。 */
    val login: ScheduleLogin

    /** 教务会话：取数口、登录态、登录/重认证、按来源取整学期课表。 */
    val session: ScheduleSession

    /** 全国节假日（放假的课要隐掉）。 */
    val holidays: HolidaySource

    /** 「接下来」的作业截止：只读别处写好的落盘缓存，日程页**不为作业发任何请求**。 */
    suspend fun dueHomeworks(accountId: String): List<LmsDue> = emptyList()

    /** 课程详情里的下钻（思源学堂、本次考勤）。`null` = 这一端没接。 */
    val courseLinks: CourseLinkAccess?

    /** 教室座位数。`null` = 查不到（这一端没接，或这间教室没有座位表）。 */
    suspend fun roomSeatCount(location: String): Int? = null

    /** 「课表显示考勤角标」这个设置项（`:app` 在 `CredentialStore` 里）。 */
    val attendanceBadgeEnabled: Boolean get() = false

    /** 联网判定。没有这一档的端点走默认值 true（真发请求时失败自然会被当成失败）。 */
    fun isOnline(): Boolean = true

    /** 课表落地之后要通知的事：首页信号 + 小组件刷新。 */
    fun onScheduleSynced() {}

    /**
     * 把一份文件交给系统（ICS 导出）。返回 false = 这一端没接 —— 调用点本来就不该画那一行
     * （见 [ScheduleCapability.SHARE_FILE]）。
     */
    fun shareFile(content: String, fileName: String, mimeType: String): Boolean = false

    /** 课表变更（调课/停课/换教室）冒一条气泡给用户。 */
    fun postScheduleChange(message: String) {}
}

/**
 * 登录态那一格。`:app` 包的是 `AppLoginState`（`LocalAppLoginState` 里那一份）。
 *
 * ⚠️ 「会话过期怎么办」**不**在这一格里：`:core` 早就有那条缝了（`auth/AuthExpiry.kt` 的
 * `LocalAuthExpiry`，`:app` 在 `AppRoot` 顶层注入一次），本屏跟其余共享层屏幕走同一条路。
 * 这里只留三样「屏自己说不出来」的事实。
 */
interface ScheduleLogin {

    /** 当前激活账号 ID（学号/手机号）；空串 = 还没登录。 */
    val accountId: String

    /** 学号（教材接口要它）。`:app` = `activeUsername`。 */
    val studentId: String

    /** 有保存下来的凭据 ⇒ 可以后台自动登录教务。 */
    val hasCredentials: Boolean
}

/**
 * 教务会话与取数那一格。
 *
 * ⚠️ [source] 在**同一个会话期间必须返回同一个实例**：ViewModel 靠对象身份判断「会话换了没有」
 * （与搬迁前的 `site === activeSite` 同一条判据），每次新建一个 `JwxtScheduleApi` 会让
 * 每次重组都当成换了个会话、反复重载。
 */
interface ScheduleSession {

    /** 现在的教务取数口；会话还没建起来时为 null。 */
    val source: JwxtScheduleSource?

    /** 那个会话登上了没有。冷启动时 CAS 登录是异步的：先 false 后 true。 */
    val isLoggedIn: Boolean

    /** 后台静默登录教务。成功返回取数口；失败抛错（调用方自己决定吞不吞）。 */
    suspend fun ensureLogin(userInitiated: Boolean = false): JwxtScheduleSource

    /** 用户点「重试」：强制重新认证一次（可能弹短信二验）。 */
    suspend fun reAuthenticate(userInitiated: Boolean = true): JwxtScheduleSource

    /**
     * 按用户选的来源取整学期课表（`:data` 的 `ScheduleSourceRouter`：历史学期与非教务源
     * 取不到时都退回教务），连同「这一次实际来自哪个系统」一起给出来 ——
     * 课表变更检测按后者分开存快照。
     */
    suspend fun fetchSchedule(source: JwxtScheduleSource, termCode: String, userInitiated: Boolean = false): ServedSchedule
}

/** 一次课表取数的结果：课程 + 它**实际**来自哪个系统（见 [ScheduleDiff.diffAndStore] 的 `servedSourceKey`）。 */
data class ServedSchedule(val courses: List<CourseItem>, val sourceKey: String)

/** 节假日那一格：只读缓存（首屏不能被它拖住）与联网刷新。 */
interface HolidaySource {

    /** 只读内存/磁盘，不访问网络。 */
    fun peekCached(cache: ScheduleCacheStore): Map<LocalDate, String>

    /** 联网取；[force] = true 时忽略内存/磁盘缓存。失败由实现决定抛错还是兜底给缓存。 */
    suspend fun refresh(cache: ScheduleCacheStore, force: Boolean = false): Map<LocalDate, String>
}

/**
 * 课程详情里那两项下钻（`:data` 的 `CourseLinks`：要会话内核、`LmsApi` 与 `AttendanceRecordStore`）。
 *
 * 抠成槽位而不是把 `CourseLinks` 整个塞进 `:core`：它一步都进不了 commonMain
 * （`SessionManager` 在 `:data`）。教材那一半是**纯匹配**，按能力切走后留在 `:core`
 * （[TextbookLinks]），不占这一格。
 */
interface CourseLinkAccess {

    /**
     * 拉一学期考勤并建索引。失败返回 null，界面当作没有角标。
     *
     * [finishedTerms] 是「已结束、不会再变的学期」，由屏按学期自己的起止算出来灌进去 ——
     * 这一层拿不到教务会话，自己判断不了，没灌就当所有学期都还活着。
     */
    suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean = false,
    ): AttendanceMarks?

    /** 这门课在思源学堂对应哪门；按课程号配不上就按课程名精确配，都不中返回 null。 */
    suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary?
}

/** 一学期的考勤流水按课表格子建的索引。`:data` 的 `CourseLinks.AttendanceIndex` 直接实现它。 */
interface AttendanceMarks {

    /** 这一格记的是什么；查无此格（未来的课、或这门课不考勤）返回 null。 */
    fun statusOf(week: Int, dayOfWeek: Int, startSection: Int): WaterType?

    /** 指定周次的那一次考勤。 */
    fun recordOn(course: CourseItem, week: Int): AttendanceWaterRecord?
}
