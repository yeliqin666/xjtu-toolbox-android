package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.lms.LmsCourseSummary

/** 日程页宿主槽位里能用 `:data` 自己那份实现的那几格。 */

/** 按用户选的来源取整学期课表，并把「这一次实际来自哪个系统」一起给出来。 */
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

/** 节假日：`HolidayApi` 包一层（键 `holiday_dates` 与 JSON 格式不能改）。 */
class DataHolidaySource : HolidaySource {

    override fun peekCached(cache: ScheduleCacheStore): Map<kotlinx.datetime.LocalDate, String> =
        HolidayApi.peekCached(cache)

    override suspend fun refresh(
        cache: ScheduleCacheStore,
        force: Boolean,
    ): Map<kotlinx.datetime.LocalDate, String> = HolidayApi.getHolidayDates(cache, forceRefresh = force)
}

/** 课程详情下钻那一格：`CourseLinks` 原样包一层。 */
class DataCourseLinkAccess(
    private val manager: SessionManager?,
    private val accountType: AccountType,
) : CourseLinkAccess {

    override suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean,
    ): AttendanceMarks? {
        CourseLinks.attachStorage(true, finishedTerms)
        return CourseLinks.attendanceIndex(manager, accountType, termCode, userInitiated)
    }

    override suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary? =
        CourseLinks.lmsCourseFor(manager, course)
}

/** 教务会话那一格：取数口、登录态、登录/重认证、按来源取课表。 */
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
