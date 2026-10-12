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

/**
 * 桌面端的日程页宿主面 —— **如实声明这一端有什么**（见 `:core` 的 `ScheduleHost` 与
 * `ScheduleCapability`）。
 *
 * | 那一档 | 桌面这一端 |
 * |---|---|
 * | 本地落盘 | 键值文件（0600 的 Properties，与凭据 / 设置同一套落盘口径）—— 课表族缓存在 `schedule_cache<账号后缀>`，自建日程在 `custom_courses` |
 * | 教务会话与取数 | `:data` 的 [DataScheduleSession]（`DesktopAuth` 的 `SessionManager` + `JwxtScheduleApi`），与「全校课表 / 成绩 / 评教」**同一个教务站点** |
 * | 节假日 | `:data` 的 [DataHolidaySource]（两个公开接口，无需登录） |
 * | 思源学堂入口 | [DataCourseLinkAccess]（桌面本来就有思源学堂那条路由，借它的站点） |
 * | 加/改/删自建日程 | [KeyValueCustomCourseStore] 真落盘 |
 *
 * 这一端**没有**的那几档（都不列进 [honored]，屏上也就**一个字都不画**）：
 *  - `SHARE_FILE` —— 桌面没有系统分享面板。ICS 生成那一半（`:core` 的 `ScheduleExport`）已经能用，
 *    缺的是「把文件交给谁」，所以「导出日历 (ICS)」那一行菜单**不画**（不是画出来点了没反应）；
 *  - `SEAT_COUNT` —— 教室座位数在 `:data` 的 `EmptyRoomApi` 里，它要空闲教室那套站点会话
 *    （三档数据源各要不同的登录），这一端还没接 ⇒ 详情里「N座」那一格不画；
 *  - `DUE_HOMEWORK` —— 思源作业截止那份落盘缓存（`:app` 的 `LmsDueStore`，读 `DataCache`）
 *    桌面还没有对应实现 ⇒ 今日栏的「接下来」只有考试，没有作业那一条；
 *  - `ATTENDANCE` —— 考勤那一档要单独登一次考勤站点（`:data` 的 `CourseLinks.attendanceIndex`）。
 *    取数本身在这一端跑得通，但「课表显示考勤角标」这个开关还只有 Android 在读
 *    （桌面设置页没画那一行，见 `DesktopSettingsHost` 的 TODO #4）⇒ 这一档整档不画，
 *    免得出现「开关关不掉、角标自己冒出来」；
 *  - `WIDGET_REFRESH` —— 桌面没有桌面小组件（设计文档 §6 Stage C 说「小组件用系统通知替代」，
 *    那条路还没做，见 `DesktopHomeScreen` 的 TODO #1）⇒ 课表落地后没有小组件要刷；
 *    `HOME_SIGNALS` **列了**（这一端现在有首页了：`AppRoute.Main` → `DesktopHomeScreen`，
 *    它盯的就是 `DesktopHomeSignals.scheduleVersion`）；
 *  - `SCHEDULE_CHANGE_BUBBLE` —— 调课/停课的气泡走 `InboxStore`，这一端还没有那份消息收纳的宿主；
 *  - `ONLINE_CHECK` —— 桌面只按直连走（`DesktopAuth` 的已知缺口），没有校内外判定 ⇒
 *    `isOnline()` 走默认 true（真发请求失败时自然会被当成失败）。
 *
 * **TODO（下一批要接的那几档）**：
 *  1. `SEAT_COUNT`：桌面已经装配了「空闲教室」那条路由（`AppRoute.EmptyRoom`，源自己 ensure 三档
 *     数据源），把 `EmptyRoomApi` 那一步接过来就能画「N座」；
 *  2. `SHARE_FILE`：桌面的对应动作是「写进一个用户看得见的目录 + 打开它」（`java.awt.Desktop`），
 *     不是系统分享面板 —— 定下来写哪儿再画那一行；
 *  3. `ATTENDANCE`：把「课表显示考勤角标」那一行接进设置页（`SettingsStore.scheduleAttendanceBadge`
 *     这个键两头同名，`:core` 的设置屏本来就会写），这一档就能亮。
 *
 * @param auth 会话 + 凭据（`Main.kt` 与离屏证据各建一个）
 */
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

    /** 与搬迁前同一个池（`:app` 的日程页一直是 `Dispatchers.IO`）。 */
    override val io: CoroutineDispatcher = Dispatchers.IO
    /**
     * 课表落地之后要通知的事：桌面只有一件 —— 让首页重读一次缓存
     *（`:app` 那一份还挂着桌面小组件刷新与提醒评估，桌面上没有那两样）。
     */
    override fun onScheduleSynced() = DesktopHomeSignals.onScheduleSynced()

    override fun cache(accountId: String?): ScheduleCacheStore =
        KeyValueScheduleCacheStore(
            secureKeyValueStore(SCHEDULE_CACHE_STORE + AccountContext.suffixFor(accountId)),
        )

    override val customCourses: CustomCourseStore =
        KeyValueCustomCourseStore(secureKeyValueStore(CUSTOM_COURSE_STORE))

    /** 桌面把两个可发现的事实接到 `AccountContext` 与登录状态上（与 `DesktopAuth` 同一口径）。 */
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
