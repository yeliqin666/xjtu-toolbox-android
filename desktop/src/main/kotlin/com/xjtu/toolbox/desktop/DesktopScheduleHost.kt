package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.schedule.CourseLinkAccess
import com.xjtu.toolbox.schedule.CustomCourseStore
import com.xjtu.toolbox.schedule.DataCourseLinkAccess
import com.xjtu.toolbox.schedule.DataHolidaySource
import com.xjtu.toolbox.schedule.DataScheduleSession
import com.xjtu.toolbox.schedule.HolidaySource
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCapability
import com.xjtu.toolbox.schedule.ScheduleHost
import com.xjtu.toolbox.schedule.ScheduleLogin
import com.xjtu.toolbox.schedule.ScheduleSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 桌面端的日程页宿主面（见 `ScheduleHost` 与 `ScheduleCapability`）。 */
internal class DesktopScheduleHost(private val auth: DesktopAuth) : ScheduleHost {

    override val honored: Set<ScheduleCapability> = setOf(
        ScheduleCapability.CUSTOM_COURSE_EDIT,
        ScheduleCapability.LMS_LINK,
        // 课表落地后给首页发信号：桌面现在有首页了（`AppRoute.Main` → `DesktopHomeScreen`），
        // 它盯的版本号就是 `DesktopHomeSignals.scheduleVersion`（见 onScheduleSynced）。
        // WIDGET_REFRESH 仍旧不列 —— 这一端没有桌面小组件（设计文档 §6 Stage C 说
        // 「小组件用系统通知替代」，那条路还没做，见 `DesktopHomeScreen` 的 TODO #1）。
        ScheduleCapability.HOME_SIGNALS,
    )

    override val io: CoroutineDispatcher = Dispatchers.IO
    /** 课表落地之后要通知的事：桌面只有一件 —— 让首页重读一次缓存（`:app` 那一份还挂着桌面小组件刷新与提醒评估，桌面上没有那两样）。 */
    override fun onScheduleSynced() = DesktopHomeSignals.onScheduleSynced()

    override fun cache(accountId: String?): ScheduleCacheStore =
        KeyValueScheduleCacheStore(
            secureKeyValueStore(accountScopedStoreName(SCHEDULE_CACHE_STORE, accountId)),
        )

    override val customCourses: CustomCourseStore =
        KeyValueCustomCourseStore(secureKeyValueStore(CUSTOM_COURSE_STORE))

    /** 桌面把两个可发现的事实接到 `AccountContext` 与登录状态上。 */
    override val login: ScheduleLogin = object : ScheduleLogin {
        override val accountId: String get() = AccountContext.activeAccountId.orEmpty()
        override val studentId: String get() = AccountContext.activeAccountId.orEmpty()
        override val hasCredentials: Boolean get() = auth.loggedIn
    }

    override val session: ScheduleSession = DataScheduleSession(
        manager = { auth.sessionManager },
        credentials = { auth.sessionManager.credentials },
    )

    override val holidays: HolidaySource = DataHolidaySource()

    override val courseLinks: CourseLinkAccess = DataCourseLinkAccess(
        manager = auth.sessionManager,
        accountType = AccountContext.activeAccountType,
    )

    companion object {
        /** 课表族缓存的落盘文件（`schedule_cache<账号后缀>`）。 */
        const val SCHEDULE_CACHE_STORE = "schedule_cache"

        /** 自建日程的落盘文件（里面只有 `custom_courses` 这一个键）。 */
        const val CUSTOM_COURSE_STORE = "custom_courses"
    }
}
