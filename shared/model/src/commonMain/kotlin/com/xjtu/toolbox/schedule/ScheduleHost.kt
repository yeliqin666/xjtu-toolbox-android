package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsDue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

/** 日程页里那些只有某些端才真会照着办的能力。 */
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

/** 日程页的宿主槽位 —— 「这一端有什么」，而不是「这一端是不是 Android」。 */
interface ScheduleHost {

    /** 这一端**真会照着办**的那些能力（见 [ScheduleCapability]）。 */
    val honored: Set<ScheduleCapability>

    /**
     * 缺的那几档能力各自一句说明（键 = [ScheduleCapability]）。
     *
     * 为空 = 本端没什么要交代的（三端都没有的、或显而易见的不写）——这是默认值，所以不填这一格的端
     * 行为与本槽位出现之前**一模一样**：屏上不会多画一行字。
     *
     * 只对「这一端不干这件事」的能力有意义：不用 [honored] 里有的东西去配说明，屏上也只在
     * `capability !in honored` 的分支里找它。文案要同时说清三件事：这一端没有什么 / 为什么 / 去哪儿能用。
     */
    val gapNotes: Map<ScheduleCapability, String> get() = emptyMap()

    /** [capability] 的缺口说明；null = 本端不交代（有这一档，或没什么好说的）。 */
    fun gapNote(capability: ScheduleCapability): String? = gapNotes[capability]

    /** 本地落盘那一族（[cache] / [customCourses]）的调度器。 */
    val io: CoroutineDispatcher get() = Dispatchers.Default

    /** 按账号开一份课表族缓存。 */
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

    /** 把一份文件交给系统（ICS 导出）。 */
    fun shareFile(content: String, fileName: String, mimeType: String): Boolean = false

    /** 课表变更（调课/停课/换教室）冒一条气泡给用户。 */
    fun postScheduleChange(message: String) {}
}

/** 登录态那一格。 */
interface ScheduleLogin {

    /** 当前激活账号 ID（学号/手机号）；空串 = 还没登录。 */
    val accountId: String

    /** 学号（教材接口要它）。`:app` = `activeUsername`。 */
    val studentId: String

    /** 有保存下来的凭据 ⇒ 可以后台自动登录教务。 */
    val hasCredentials: Boolean
}

/** 教务会话与取数那一格。 */
interface ScheduleSession {

    /** 现在的教务取数口；会话还没建起来时为 null。 */
    val source: JwxtScheduleSource?

    /** 那个会话登上了没有。冷启动时 CAS 登录是异步的：先 false 后 true。 */
    val isLoggedIn: Boolean

    /** 后台静默登录教务。成功返回取数口；失败抛错（调用方自己决定吞不吞）。 */
    suspend fun ensureLogin(userInitiated: Boolean = false): JwxtScheduleSource

    /** 用户点「重试」：强制重新认证一次（可能弹短信二验）。 */
    suspend fun reAuthenticate(userInitiated: Boolean = true): JwxtScheduleSource

    /** 按用户选的来源取整学期课表（`:data` 的 `ScheduleSourceRouter`）。 */
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
 * 课程详情里那两项下钻。
 *
 * 判据（配对 / 建索引）在共享层的 `CourseLinkMatching.kt`，各端只需要把数据取来：
 * Android 与桌面用 `:shared:data` 的 `CourseLinks`（要会话内核、`LmsApi` 与 `AttendanceRecordStore`），
 * web 等 serve 端点接上 `CourseLinkSource` 即可（driver 用 `SourceBackedCourseLinkAccess`）。
 */
interface CourseLinkAccess {

    /** 拉一学期考勤并建索引。 */
    suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean = false,
    ): AttendanceMarks?

    /** 这门课在思源学堂对应哪门；按课程号配不上就按课程名精确配，都不中返回 null。 */
    suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary?
}

/** 一学期的考勤流水按课表格子建的索引。共享层的 [SlotAttendanceMarks] 直接实现它（wasm 也能编）。 */
interface AttendanceMarks {

    /** 这一格记的是什么；查无此格（未来的课、或这门课不考勤）返回 null。 */
    fun statusOf(week: Int, dayOfWeek: Int, startSection: Int): WaterType?

    /** 指定周次的那一次考勤。 */
    fun recordOn(course: CourseItem, week: Int): AttendanceWaterRecord?
}
