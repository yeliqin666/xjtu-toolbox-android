package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.lms.LmsCourseSummary

/**
 * 日程页宿主槽位里**能用 `:data` 自己那份实现**的那几格 —— 两端的宿主面共用这一份，
 * 各端只提供「平台专属」的部分（本地落盘、小组件/首页信号、分享面板、座位数）。
 *
 * | 槽位 | 谁给 | 为什么在这里 |
 * |---|---|---|
 * | [DataHolidaySource] | `:data` | 节假日走 okhttp 打两个公开接口（`HolidayApi`），进不了 commonMain |
 * | [DataCourseLinkAccess] | `:data` | 要会话内核 + `LmsApi` + `AttendanceRecordStore`（`CourseLinks`） |
 * | [DataScheduleSession] | `:data` | 会话内核与教务取数都在这一层（`SessionManager` + `JwxtScheduleApi`） |
 */

/**
 * 按用户选的来源取整学期课表，并把「这一次**实际**来自哪个系统」一起给出来。
 *
 * 两条规则都在 [ScheduleSourceRouter] 里（历史学期永远走教务；非教务源出任何问题都退回教务）。
 * 抽成函数是因为两端的宿主面都要写这一句：`:app` 与桌面各传自己的 `SessionManager`。
 */
suspend fun fetchServedSchedule(
    manager: SessionManager?,
    source: JwxtScheduleSource,
    termCode: String,
    userInitiated: Boolean = false,
): ServedSchedule = ServedSchedule(
    courses = ScheduleSourceRouter.getSchedule(
        jwxt = source,
        termCode = termCode,
        manager = manager,
        userInitiated = userInitiated,
    ),
    sourceKey = ScheduleSourceRouter.servedSource().key,
)

/** 节假日那一格：`HolidayApi` 原样包一层（键 `holiday_dates` 与存进去的 JSON 逐字未动）。 */
class DataHolidaySource : HolidaySource {

    override fun peekCached(cache: ScheduleCacheStore): Map<kotlinx.datetime.LocalDate, String> =
        HolidayApi.peekCached(cache)

    override suspend fun refresh(
        cache: ScheduleCacheStore,
        force: Boolean,
    ): Map<kotlinx.datetime.LocalDate, String> = HolidayApi.getHolidayDates(cache, forceRefresh = force)
}

/**
 * 课程详情下钻那一格：`CourseLinks` 原样包一层。
 *
 * 那个 `object` 是进程级单例、缓存按账号整体替换（见它的 KDoc），所以这里**每次进来现造一个**
 * 包装也不会有额外开销 —— 它只把 `manager` 与 `accountType` 这两个「谁在问」的参数补齐。
 */
class DataCourseLinkAccess(
    private val manager: SessionManager?,
    private val accountType: AccountType,
) : CourseLinkAccess {

    override suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean,
    ): AttendanceMarks? {
        // 「宿主落盘接上了没有」那个闸门（它决定考勤记录读不读缓存、取到之后回不回写）。
        // 原来由日程页在首次调用前设好；现在两端都从这里进，位置没变（第一次查询之前）。
        CourseLinks.attachStorage(true, finishedTerms)
        return CourseLinks.attendanceIndex(manager, accountType, termCode, userInitiated)
    }

    override suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary? =
        CourseLinks.lmsCourseFor(manager, course)
}

/**
 * 教务会话那一格：取数口、登录态、登录/重认证、按来源取课表。
 *
 * `manager` / `credentials` 收成 lambda 而不是值：`:app` 的会话内核是 `AppLoginState` 建好之后
 * 才注入的（构造时可能还是 null），而切账号会**原地重配**同一个 `SessionManager`
 * ⇒ 每次都现读一次才对（与搬迁前 `login.sessionManager` 那个属性访问同一条口径）。
 *
 * [source] 的实例在同一会话期间是稳定的：`JwxtScheduleApi` 自己带着学期名/周数缓存，
 * 每次新建一个会让那些缓存白丢，也会让 ViewModel 把「重组」当成「换了会话」。
 */
class DataScheduleSession(
    private val manager: () -> SessionManager?,
    private val credentials: () -> Pair<String, String>?,
    private val siteKey: String = LoginType.JWXT.siteKey(),
) : ScheduleSession {

    private var cachedSite: SiteSession? = null
    private var cachedSource: JwxtScheduleSource? = null

    override val source: JwxtScheduleSource?
        get() {
            val mgr = manager()
            val site = mgr?.getSiteOrNull(siteKey)
            if (site == null) {
                cachedSite = null
                cachedSource = null
                return null
            }
            if (site !== cachedSite || cachedSource == null) {
                cachedSite = site
                cachedSource = JwxtScheduleApi(site)
            }
            return cachedSource
        }

    override val isLoggedIn: Boolean
        get() = manager()?.getSiteOrNull(siteKey)?.hasLogin == true

    override suspend fun ensureLogin(userInitiated: Boolean): JwxtScheduleSource {
        // 后台路径一律 silent：撞上短信验证时直接抛出，不弹窗也不发短信。
        val site = requireNotNull(manager()).ensureSite(siteKey, userInitiated = userInitiated, silent = true)
        return bind(site)
    }

    override suspend fun reAuthenticate(userInitiated: Boolean): JwxtScheduleSource {
        val mgr = requireNotNull(manager())
        val site = mgr.getSiteOrNull(siteKey)
        if (site == null) return ensureLogin(userInitiated = true)
        val creds = credentials()
        if (creds != null) {
            // 与搬迁前 `retry()` 那一支逐字相同：强制重认证一次（force = true）。
            site.ensureLogin(
                creds.first,
                creds.second,
                force = true,
                userInitiated = userInitiated,
                silent = !userInitiated,
            )
        } else {
            mgr.ensureSite(siteKey, userInitiated = true, silent = false)
        }
        return bind(site)
    }

    override suspend fun fetchSchedule(
        source: JwxtScheduleSource,
        termCode: String,
        userInitiated: Boolean,
    ): ServedSchedule = fetchServedSchedule(manager(), source, termCode, userInitiated)

    private fun bind(site: SiteSession): JwxtScheduleSource {
        if (site !== cachedSite || cachedSource == null) {
            cachedSite = site
            cachedSource = JwxtScheduleApi(site)
        }
        return requireNotNull(cachedSource)
    }
}
