package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.CourseLinkAccess
import com.xjtu.toolbox.schedule.CustomCourseStore
import com.xjtu.toolbox.schedule.HolidaySource
import com.xjtu.toolbox.schedule.JwxtScheduleSource
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCapability
import com.xjtu.toolbox.schedule.ScheduleHost
import com.xjtu.toolbox.schedule.ScheduleLogin
import com.xjtu.toolbox.schedule.ScheduleSession
import com.xjtu.toolbox.schedule.ServedSchedule
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate

/**
 * Web 这一端的日程页宿主面 —— **如实声明这一端有什么**（`:core` 的 `ScheduleHost`）。
 *
 * ## 真接上的（三条缝都是真的）
 *
 * | 槽位 | Web 这一端 |
 * |---|---|
 * | [cache] / [customCourses] | `:core` 的键值缝：**wasm 上是 `localStorage`**（不是内存！见 `core/src/wasmJsMain/.../KeyValueStore.wasmJs.kt`，前缀 `xjtu-toolbox.`）⇒ 课表缓存与自建日程都真落盘，刷新页面还在 |
 * | `login` | 见下面那段：这一端没有账号，三个问题的答案是"由服务端答" |
 * | `session` | [WebScheduleSource]（服务端契约里真有的那四个端点）+ [SettingsStore.SCHEDULE_SOURCE_JWXT] 作为来源键 |
 *
 * [cache] 没有按账号分文件：这一端没有账号（凭据在 campus-api / serve 进程里），
 * `:app` / 桌面那份按账号加后缀是为了多账号互不串数据，本端只有一个"当前"。
 *
 * ## [honored] 只列一条
 *
 * 列进来的只有 [ScheduleCapability.CUSTOM_COURSE_EDIT]（`KeyValueCustomCourseStore` 真落盘 ⇒
 * 工具栏那个「+」与「编辑日程」是真能干的事）。没列的都是这一端**没有**的东西，屏上**一个字都不画**：
 *
 * - `SHARE_FILE` —— 浏览器里没有系统分享面板（`:app` 是 `Intent.ACTION_SEND`、桌面是
 *   `Desktop.browse`）。ICS 生成那一半（`:core` 的 `ScheduleExport`）本来就能用，缺的是"交给谁"
 *   ⇒ 「导出日历 (ICS)」那一行不画（不是画了点了没反应）；
 * - `LMS_LINK` / `ATTENDANCE` —— 课程详情里那两项下钻要会话内核 + `LmsApi` + 考勤记录
 *   （`:data` 的 `CourseLinks`）⇒ [courseLinks] 传 null，两档都不画；
 * - `SEAT_COUNT` —— 教室座位数在空闲教室那套取数里（本端有 `/api/emptyroom/…` 那条路，
 *   但「按教室名查座位表」那个方法还没接）⇒ 详情里「N座」不画；
 * - `DUE_HOMEWORK` —— 思源作业截止那份落盘缓存（`:app` 的 `LmsDueStore`）本端没有
 *   ⇒ 今日栏的「接下来」只有考试；
 * - `WIDGET_REFRESH` / `HOME_SIGNALS` —— 浏览器里没有桌面小组件，本端「首页」那一格也还是
 *   降级页（见 `WebFallbackScreens`）⇒ 课表落地后没有谁要通知；
 * - `ONLINE_CHECK` —— 浏览器没有"校内外"判定 ⇒ 走默认 `true`（真发请求失败自然会被当成失败）；
 * - `SCHEDULE_CHANGE_BUBBLE` —— 调课冒泡要走消息收纳那份宿主（本端没有）；而且**调停补课这个
 *   端点本端就没有**（见 [WebScheduleSource]），变更检测拿到的"变化"只会是被动比对的结果。
 *
 * ## TODO（下一批能接的一格）
 *
 * `SEAT_COUNT`：本端已有 `CampusEmptyRoomApi`（空闲教室那条路由在用它），补一个「按教室名取座位数」
 * 的方法就能把详情里那一格点亮 —— 不必先有会话，因为那条路是 campus-api 自己带的登录。
 *
 * @param client 与整页同一个客户端（serve 模式下它带着 `Authorization` 头）
 * @param mode 这一份 `:web` 在对谁说话（[ApiMode]）—— 决定 [WebScheduleSource] 的信封读法
 */
internal class WebScheduleHost(
    client: HttpClient,
    mode: ApiMode,
) : ScheduleHost {

    override val honored: Set<ScheduleCapability> = setOf(ScheduleCapability.CUSTOM_COURSE_EDIT)

    override fun cache(accountId: String?): ScheduleCacheStore =
        KeyValueScheduleCacheStore(keyValueStore(SCHEDULE_CACHE_STORE))

    override val customCourses: CustomCourseStore =
        KeyValueCustomCourseStore(keyValueStore(CUSTOM_COURSE_STORE))

    /**
     * 这一端没有账号：`accountId` / `studentId` 都给空串（`:app` 那边是登录态里的学号）。
     *
     * 空串会让教材那一条落成「未获取到学号」—— 那正好是事实（本端没有教材端点也没有学号），
     * 屏上写的就是这句话，不是空白。**编一个假的学号更糟**：它会去请求别人的数据。
     */
    override val login: ScheduleLogin = object : ScheduleLogin {
        override val accountId: String = ""
        override val studentId: String = ""
        /**
         * `true`：本端能不能取数**由服务端答**（campus-api 的会话 / serve 的令牌）。
         * 答成 `false` 会让屏永远走「没凭据就不取数」那一支 —— 那是真的坏（页面开了什么都不显示），
         * 而真正的失败（401 / 令牌不对）会以错误页的形式回到屏上，用户看得见。
         */
        override val hasCredentials: Boolean = true
    }

    override val session: ScheduleSession = WebScheduleSession(WebScheduleSource(client, mode))

    override val holidays: HolidaySource = WebHolidaySource

    /** 课程详情里那两项下钻（思源学堂 / 本次考勤）本端没有 ⇒ 不画（见类 KDoc）。 */
    override val courseLinks: CourseLinkAccess? = null

    companion object {
        /** 课表族缓存的键前缀（wasm 上落在 `xjtu-toolbox.schedule_cache.*`）。 */
        const val SCHEDULE_CACHE_STORE = "schedule_cache"

        /** 自建日程那张表（`xjtu-toolbox.custom_courses.*`）。 */
        const val CUSTOM_COURSE_STORE = "custom_courses"
    }
}

/**
 * 教务会话那一格：本端**没有登录这回事**，会话在服务端进程里。
 *
 * 三条回答都按这一条事实来：
 * - [source]：一直是同一个 [WebScheduleSource]（`ScheduleViewModel` 靠对象身份判断"会话换了没有"
 *   ⇒ 每次新建一个会让它反复重载）；
 * - [isLoggedIn]：`true`（"能不能用"由服务端答；答 `false` 会让屏一直等一次永远不会发生的登录）；
 * - [ensureLogin] / [reAuthenticate]：**空操作**返回同一个取数口 —— 会话本就在对面，
 *   重认证的语义只对"页面上持有凭据"的端成立。
 *
 * [fetchSchedule] 不走 `:data` 的 `ScheduleSourceRouter`（那是"按用户选的来源取，非教务源出问题退回教务"）：
 * 本端只有教务这一个来源 ⇒ 直接取，来源键用 [SettingsStore.SCHEDULE_SOURCE_JWXT]
 *（与设置页里那一项的键值同一个，`:app` / 桌面也用它当快照键）。
 */
internal class WebScheduleSession(private val webSource: WebScheduleSource) : ScheduleSession {

    override val source: JwxtScheduleSource get() = webSource

    override val isLoggedIn: Boolean get() = true

    override suspend fun ensureLogin(userInitiated: Boolean): JwxtScheduleSource = webSource

    override suspend fun reAuthenticate(userInitiated: Boolean): JwxtScheduleSource = webSource

    override suspend fun fetchSchedule(
        source: JwxtScheduleSource,
        termCode: String,
        userInitiated: Boolean,
    ): ServedSchedule = ServedSchedule(
        courses = source.schedule(termCode).courses,
        sourceKey = SettingsStore.SCHEDULE_SOURCE_JWXT,
    )
}

/**
 * 节假日那一格：**只读落盘的那一份，不联网**。
 *
 * `:app` 与桌面用的是 `:data` 的 `DataHolidaySource`（okhttp 打两个公开接口，`HolidayApi`）——
 * 那一份挂着 okhttp，进不了浏览器；而且那两个源在浏览器里也没被反代（`shuyz.com` 不给 CORS 头）。
 *
 * 于是本端**如实**只读同一份落盘缓存（键名 `holiday_dates` 与 `:data` 逐字相同，格式也一样，
 * 所以将来服务端把它端出来、或本端接了直连，这个读法不用改）。读不到就是空表 ⇒ 课表里
 * **没有一天会被当成放假**：这是"少画"，不是"画错"（`ScheduleCache.filterByHolidays` 对空表是空操作）。
 */
private object WebHolidaySource : HolidaySource {

    private const val CACHE_KEY = "holiday_dates"

    override fun peekCached(cache: ScheduleCacheStore): Map<LocalDate, String> =
        parseHolidays(cache.readJson(CACHE_KEY, Long.MAX_VALUE))

    /** 见本对象 KDoc：本端不联网取节假日 ⇒ 刷新就是再读一次缓存。 */
    override suspend fun refresh(cache: ScheduleCacheStore, force: Boolean): Map<LocalDate, String> =
        peekCached(cache)

    private fun parseHolidays(json: String?): Map<LocalDate, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = json.safeParseJsonObject()
        return root.entries.mapNotNull { (date, name) ->
            runCatching { LocalDate.parse(date) to name.safeString() }.getOrNull()
        }.toMap()
    }
}
