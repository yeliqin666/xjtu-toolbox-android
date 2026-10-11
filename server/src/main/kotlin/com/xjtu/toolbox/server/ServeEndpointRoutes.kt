package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.calendar.CalendarEvent
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarSource
import com.xjtu.toolbox.calendar.SchoolTerm
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS
import com.xjtu.toolbox.emptyroom.EmptyRoomSource
import com.xjtu.toolbox.emptyroom.LiveRoom
import com.xjtu.toolbox.emptyroom.LiveSnapshot
import com.xjtu.toolbox.emptyroom.NoDataException
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.emptyroom.RoomSource
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyMember
import com.xjtu.toolbox.faculty.FacultySearchPage
import com.xjtu.toolbox.faculty.FacultySearchQuery
import com.xjtu.toolbox.faculty.FacultySource
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.FitnessItem
import com.xjtu.toolbox.fitness.FitnessScore
import com.xjtu.toolbox.fitness.FitnessSource
import com.xjtu.toolbox.fitness.FitnessYear
import com.xjtu.toolbox.inbox.AppInboxSource
import com.xjtu.toolbox.inbox.FinishedTodo
import com.xjtu.toolbox.inbox.InboxData
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxSource
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.MergedNotificationPage
import com.xjtu.toolbox.notification.NoticeSource
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.notification.NotificationSource
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageCategory
import com.xjtu.toolbox.yellowpage.YellowPageData
import com.xjtu.toolbox.yellowpage.YellowPageDepartment
import com.xjtu.toolbox.yellowpage.YellowPageSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **P0 取数端点组一（免登录 / 轻登录六域）** —— `docs/api-contract.md` §5 那批 P0 端点的实现。
 *
 * 十个端点，全部是 `:data` 对应 Source/端口的**薄包装**（契约 §1：解析与口径在 `:data`，
 * 本文件只做「把已解析的模型投影成线上形状」，不做二次解析）：
 *
 * | 端点 | 数据源（`:data` / `:core` 端口） |
 * |---|---|
 * | `GET /api/calendar/school` | [SchoolCalendarApi]（免登录） |
 * | `GET /api/info/yellowpage` | [YellowPageApi]（免登录，收 Ktor 客户端） |
 * | `GET /api/info/faculty` | [FacultyApiSource]（免登录，收 OkHttp 客户端） |
 * | `GET /api/fitness/years` `/api/fitness/score` | [FitnessApi]（轻登录：要有体测站点会话） |
 * | `GET /api/notification/sources` `/api/notification/list` | [AppNoticeSource]（免登录） |
 * | `GET /api/inbox` | [AppInboxSource]（轻登录：要有统一认证会话） |
 * | `GET /api/emptyroom/cdn` `/api/emptyroom/rooms` | [AppEmptyRoomSource]（CDN 免登录；直查/实时轻登录） |
 *
 * ## 三个共同口径（与 [SessionRoutes] 同一套）
 *
 * 1. **挂载点**：这些扩展挂在 `route("/api")` 里 ⇒ 自动被 [accessTokenGate] 罩住（`/api/status`
 *    是闸门里那条**唯一**的豁免，这里没有第二条）；
 * 2. **形状**：一律 [ApiEnvelope] 信封（`code == HTTP 状态码`，成功 `code == 0` + HTTP 200），
 *    时间字段一律 ISO-8601 带时区的字符串（契约 §4，不用 epoch 秒/毫秒）；
 * 3. **红线**：响应体**绝不出现**登录用户本人相关的身份（学号 / 姓名 / 手机号 / 令牌）。
 *    —— `/api/fitness/score` 是新契约（§5「要改」）里**明确要带**本人姓名/学号的那一条，
 *    它是「本人的数据」：投影自 `:data` 的 [FitnessScore]（身份字段来自体测系统、不是本端拼的）。
 *
 * ## 失败码（契约 §4）
 *
 * - `401` 需要先登录 / 登录态失效（重发同样的请求没有意义），或闸门没收令牌；
 * - `502` 上游或网络的故障（稍后重试可能就成）；
 * - `400` 请求参数不对（该端点要的查询参数缺失 / 认不得的来源代码）；
 * - `404` 端点不存在（闸门里的兜底 handle，见 [ServeModule]）。
 * 文案一律是 [FriendlyError] 给的中文短句。
 *
 * ## 为什么取数实现写在**默认参数**里而不是文件里 new
 *
 * `:data` 那几份源大多收一个可注入的客户端（[YellowPageApi] 收 Ktor `HttpClient`、
 * [FacultyApi] 收 `OkHttpClient`）或本身就是无参构造 —— 用**构造参数 + 默认值**的形态，
 * 生产装配一行不写（默认就是真上游），`:server:test` 的夹具契约测试把假客户端从参数塞进来
 * （MockEngine / okhttp 拦截器 / 假上游代理，见 `:testkit`）。这也让「合并由主对话做」的那一步
 * 变成一行：`serveEndpointRoutes(session)`。
 *
 * @param session serve 进程的会话装配（[ServeSession]）：fitness / inbox / emptyroom 三族要
 *   它的 `SessionManager`（登录过的会话、站点注册）；calendar / yellowpage / faculty / notification
 *   不用会话，是纯免登录的公开接口。
 */
internal fun Route.serveEndpointRoutes(
    session: ServeSession,
    calendar: SchoolCalendarSource = SchoolCalendarApi(),
    yellowpage: YellowPageSource = YellowPageApi(createToolboxClient(), cache = null),
    faculty: FacultySource = FacultyApiSource(),
    notification: NoticeSource = AppNoticeSource(),
) {
    calendarRoutes(calendar)
    infoRoutes(yellowpage, faculty)
    fitnessRoutes(session)
    notificationRoutes(notification)
    inboxRoutes(session)
    emptyRoomRoutes(session)
}

// ─────────────────────────────────────────────────────────────────────────────
// 校历：GET /api/calendar/school
// ─────────────────────────────────────────────────────────────────────────────

/** 校历。全部学期一起回（[SchoolCalendarSource.terms] 就是这份数据），顺序 = 开学日期升序。 */
internal fun Route.calendarRoutes(source: SchoolCalendarSource = SchoolCalendarApi()) {
    get(CALENDAR_SCHOOL_SEGMENT) {
        val terms = try {
            source.terms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondFetchError(e, "加载校历")
        }
        call.respond(ApiEnvelope.ok(CalendarPayload(terms.map { it.toDto() })))
    }
}

/** `GET /api/calendar/school` 的 `data`（校历模型在 `:core`，这里只投影）。 */
@Serializable
internal data class CalendarPayload(val terms: List<SchoolTermDto>)

/** [SchoolTerm] 的线上形状。日期是 ISO-8601 日期字符串（契约 §4：时间一律 ISO 带时区）。 */
@Serializable
internal data class SchoolTermDto(
    val id: String,
    val startDate: String,
    val endDate: String,
    val termName: String,
    val yearName: String,
    val totalWeeks: Int,
    val workDays: Int,
    val events: List<CalendarEventDto>,
)

/** [CalendarEvent] 的线上形状。 */
@Serializable
internal data class CalendarEventDto(
    val id: String,
    val startDate: String,
    val endDate: String,
    val name: String,
    val remark: String,
    val days: Int,
    val colorHex: String,
)

private fun SchoolTerm.toDto() = SchoolTermDto(
    id = id,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    termName = termName,
    yearName = yearName,
    totalWeeks = totalWeeks,
    workDays = workDays,
    events = events.map { it.toDto() },
)

private fun CalendarEvent.toDto() = CalendarEventDto(
    id = id,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    name = name,
    remark = remark,
    days = days,
    colorHex = colorHex,
)

// ─────────────────────────────────────────────────────────────────────────────
// 黄页 + 教师检索：GET /api/info/yellowpage · /api/info/faculty
// ─────────────────────────────────────────────────────────────────────────────

/** 「免登录公开信息」两屏：黄页（机构通讯录）+ 教师检索（院系主页查询）。 */
internal fun Route.infoRoutes(yellowpage: YellowPageSource, faculty: FacultySource) {
    route(INFO_SEGMENT) {
        /**
         * 黄页。免登录（`:core` 的 [YellowPageApi] 一条凭据都不带）。
         *
         * `updateTime` 是 `:core` 解析出的「2026年08月01日」展示串（与 App 端同一份），
         * 不是本端算的。
         */
        get(INFO_YELLOWPAGE_SEGMENT) {
            val data = try {
                yellowpage.getData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@get call.respondFetchError(e, "加载黄页")
            }
            call.respond(ApiEnvelope.ok(dtoOf(data)))
        }

        /**
         * 教师检索。
         *
         * 查询参数：`q`（姓名，模糊）、`college`（学院 id）、`discipline`（学科 id）、
         * `page`（1-based，默认 1）。职称/导师过滤是**客户端**的活（`:core` 的 `matches`，
         * 服务端没有那两张表），与 campus-api 的检索端点同一套参数面。
         *
         * **能力开关**：`contactsAvailable` —— serve 模式默认**不投影任何联系方式**
         * （email / 电话 / 手机 / 办公室 / 住址那几个字段一律不出现），所以永远报 `false`。
         * 屏据此不画「联系方式」那一块；将来要开，先改契约（见本文件 TODO）。
         */
        get(INFO_FACULTY_SEGMENT) {
            val query = FacultySearchQuery(
                name = call.request.queryParameters["q"].orEmpty().trim(),
                collegeId = call.request.queryParameters["college"]?.toIntOrNull() ?: 0,
                disciplineId = call.request.queryParameters["discipline"]?.toIntOrNull() ?: 0,
            )
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val result = try {
                faculty.search(query, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@get call.respondFetchError(e, "检索教师")
            }
            call.respond(ApiEnvelope.ok(FacultyPayload.from(result)))
        }
    }
}

/** `GET /api/info/yellowpage` 的 `data`。 */
@Serializable
internal data class YellowPagePayload(
    val updateTime: String,
    val categories: List<YellowPageCategoryDto>,
    val departments: List<YellowPageDepartmentDto>,
)

/** [YellowPageCategory] 的线上形状（status/sort 已由 `:core` 的解析滤掉，不投影）。 */
@Serializable
internal data class YellowPageCategoryDto(val id: Int, val name: String)

/** [YellowPageDepartment] 的线上形状。`phone` 是部门的办公电话（黄页这屏的数据主体）。 */
@Serializable
internal data class YellowPageDepartmentDto(
    val id: Int,
    val categoryId: Int,
    val name: String,
    val phone: String,
)

/**
 * `GET /api/info/faculty` 的 `data`。
 *
 * [contactsAvailable]：serve 模式**只读不拉联系方式**（不传 `?contacts=1`，与旧 campus-api
 * 同一条隐私口径）——所以永远 `false`，且 members 里没有 email/电话/手机/办公地点/住址这几个字段
 * （契约 §4：字段缺失 = 本端不投影）。
 */
@Serializable
internal data class FacultyPayload(
    val contactsAvailable: Boolean,
    val total: Int,
    val totalPage: Int,
    val pageIndex: Int,
    val members: List<FacultyMemberDto>,
) {
    companion object {
        fun from(page: FacultySearchPage): FacultyPayload = FacultyPayload(
            contactsAvailable = false,
            total = page.total,
            totalPage = page.totalPage,
            pageIndex = page.pageIndex,
            members = page.members.map { it.toDto() },
        )
    }
}

/** [FacultyMember] 的线上形状 —— **不含联系方式那几项**（见 [FacultyPayload]）。 */
@Serializable
internal data class FacultyMemberDto(
    val teacherId: Long,
    val name: String,
    val englishName: String,
    val pinyin: String,
    val homepageUrl: String,
    val collegeName: String,
    val proRank: String,
    val job: String,
    val discipline: String,
    val degree: String,
    val education: String,
    val graduatedUniversity: String,
    val isDoctoralTutor: Boolean,
    val isMasterTutor: Boolean,
    val profile: String,
    val researchDirections: List<String>,
    val picUrl: String,
    val entryTime: String,
    val lastUpdate: String,
    val clickTimes: Long,
)

private fun FacultyMember.toDto() = FacultyMemberDto(
    teacherId = teacherId,
    name = name,
    englishName = englishName,
    pinyin = pinyin,
    homepageUrl = homepageUrl,
    collegeName = collegeName,
    proRank = proRank,
    job = job,
    discipline = discipline,
    degree = degree,
    education = education,
    graduatedUniversity = graduatedUniversity,
    isDoctoralTutor = isDoctoralTutor,
    isMasterTutor = isMasterTutor,
    profile = profile,
    researchDirections = researchDirections,
    picUrl = picUrl,
    entryTime = entryTime,
    lastUpdate = lastUpdate,
    clickTimes = clickTimes,
)

/** [YellowPageData] → 线上形状。 */
private fun dtoOf(data: YellowPageData) = YellowPagePayload(
    updateTime = data.updateTime,
    categories = data.categories.map { YellowPageCategoryDto(it.id, it.name) },
    departments = data.departments.map { YellowPageDepartmentDto(it.id, it.categoryId, it.name, it.phone) },
)

// ─────────────────────────────────────────────────────────────────────────────
// 体测：GET /api/fitness/years · /api/fitness/score
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 体测（轻登录：要有体测站点会话）。
 *
 * 取数实现**每个请求 new 一个** [FitnessApi]（构造是纯本地的：只拿 `SiteSession`；
 * 取数时才联网）。站点先例：`ServeSession` 注册的 `FITNESS_SITE_KEY`。
 *
 * ⚠️ 新契约（§5「要改」）里这是**本人的数据**：score 响应带学号/姓名（投影自 `:data` 的
 * [FitnessScore]，不是本端拼的）。没登录（体测会话未初始化）→ 401。
 */
internal fun Route.fitnessRoutes(session: ServeSession) {
    val source: (ServeSession) -> FitnessSource = { s -> FitnessApi(s.sessionManager.getSite(ServeSession.FITNESS_SITE_KEY)) }

    get(FITNESS_YEARS_SEGMENT) {
        val years = try {
            source(session).years()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondFetchError(e, "加载体测学年")
        }
        call.respond(ApiEnvelope.ok(FitnessYearsPayload(years.map { it.toDto() })))
    }

    get(FITNESS_SCORE_SEGMENT) {
        val year = call.request.queryParameters["year"]?.trim()
        if (year.isNullOrEmpty()) {
            return@get call.respondBadRequest("参数 year 必填：要查询的体测学年，如 2026")
        }
        val score = try {
            source(session).score(year)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondFetchError(e, "查询体测成绩")
        }
        call.respond(ApiEnvelope.ok(score.toDto()))
    }
}

/** `GET /api/fitness/years` 的 `data`。 */
@Serializable
internal data class FitnessYearsPayload(val years: List<FitnessYearDto>)

/** [FitnessYear] 的线上形状。 */
@Serializable
internal data class FitnessYearDto(val yearNum: String, val name: String, val checked: Boolean)

/** [FitnessScore] 的线上形状（七个分项照 `:core` 的 `ITEM_DEFS` 顺序）。 */
@Serializable
internal data class FitnessScoreDto(
    val studentNumber: String,
    val studentName: String,
    val totalScore: String,
    val totalGrade: String,
    val reportType: String,
    val reportStatus: String,
    val sex: String,
    val grade: String,
    val items: List<FitnessItemDto>,
)

/** [FitnessItem] 的线上形状。 */
@Serializable
internal data class FitnessItemDto(val name: String, val value: String, val grade: String, val tone: String)

private fun FitnessYear.toDto() = FitnessYearDto(yearNum, name, checked)

private fun FitnessScore.toDto() = FitnessScoreDto(
    studentNumber = studentNumber,
    studentName = studentName,
    totalScore = totalScore,
    totalGrade = totalGrade,
    reportType = reportType,
    reportStatus = reportStatus,
    sex = sex,
    grade = grade,
    items = items.map { FitnessItemDto(it.name, it.value, it.grade, it.tone) },
)

// ─────────────────────────────────────────────────────────────────────────────
// 通知公告：GET /api/notification/sources · /api/notification/list
// ─────────────────────────────────────────────────────────────────────────────

/** 通知公告（免登录：29 个公开公告源，`AppNoticeSource` 不碰任何站点会话）。 */
internal fun Route.notificationRoutes(source: NoticeSource = AppNoticeSource()) {
    route(NOTIFICATION_SEGMENT) {

        /** 29 个源的清单 —— 形状照 `:core` 的 [NotificationSource] 枚举（code = 枚举名）。 */
        get(NOTIFICATION_SOURCES_SEGMENT) {
            val entries = NotificationSource.entries
            call.respond(
                ApiEnvelope.ok(
                    NotificationSourcesPayload(
                        total = entries.size,
                        sources = entries.map { s ->
                            NotificationSourceDto(
                                code = s.name,
                                displayName = s.displayName,
                                category = s.category.displayName,
                            )
                        },
                    ),
                ),
            )
        }

        /**
         * 通知列表。
         *
         * 参数：`sources` = 逗号分隔的来源代码（缺省 = `JWC`，与屏的默认来源一致；认不得的代码 400）、
         * `page` = 上游页号（1-based，默认 1；**页大小由各站分页决定**，`:core` 端口没有页大小概念）、
         * `all=1` = 从第 1 页开始把 `hasMore` 的页全拉完（上限 [MAX_NOTIFICATION_PAGES]）。
         *
         * 分页形状照契约 §4：`{page, size, total}`。通知的 `total` 是「本次响应带了多少条」
         * （上游不报总数）：单页 = `size`，`all=1` = 全部拉到的条数。
         *
         * `skipped` = 这次没拉到的源代码（域名级失败/该源抓取抛错），界面据此显示「以下来源暂不可用」。
         */
        get(NOTIFICATION_LIST_SEGMENT) {
            val codes = call.request.queryParameters["sources"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .ifEmpty { listOf(DEFAULT_NOTIFICATION_SOURCE) }
            val sources = codes.map { code ->
                NotificationSource.entries.firstOrNull { it.name == code.uppercase() }
                    ?: return@get call.respondBadRequest("未知的通知来源：$code")
            }
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val all = call.request.queryParameters["all"] == "1"

            val result = try {
                if (all) fetchAll(source, sources, page) else source.merged(sources, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@get call.respondFetchError(e, "加载通知")
            }
            call.respond(
                ApiEnvelope.ok(
                    NotificationListPayload(
                        page = if (all) 1 else page,
                        size = result.items.size,
                        total = result.items.size,
                        items = result.items.map { it.toDto() },
                        skipped = result.skipped.map { it.name },
                        hasMore = result.hasMore,
                    ),
                ),
            )
        }
    }
}

/** `GET /api/notification/sources` 的 `data`。 */
@Serializable
internal data class NotificationSourcesPayload(
    val total: Int,
    val sources: List<NotificationSourceDto>,
)

/** 一个源（[NotificationSource] 枚举的投影；`category` 是中文分类名，如「综合」「工学」）。 */
@Serializable
internal data class NotificationSourceDto(val code: String, val displayName: String, val category: String)

/** `GET /api/notification/list` 的 `data`（分页形状照契约 §4，见 [NotificationListPayload] KDoc）。 */
@Serializable
internal data class NotificationListPayload(
    val page: Int,
    val size: Int,
    val total: Int,
    val items: List<NotificationDto>,
    val skipped: List<String>,
    val hasMore: Boolean,
)

/** [Notification] 的线上形状；`date` 是 ISO 日期（契约 §4），`source` 是枚举名。 */
@Serializable
internal data class NotificationDto(
    val title: String,
    val link: String,
    val source: String,
    val description: String,
    val tags: List<String>,
    val date: String,
)

private fun Notification.toDto() = NotificationDto(
    title = title,
    link = link,
    source = source.name,
    description = description,
    tags = tags,
    date = date.toString(),
)

/** `all=1`：从 [startPage] 起把 `hasMore` 的页全拉完，最多 [MAX_NOTIFICATION_PAGES] 页。 */
private suspend fun fetchAll(source: NoticeSource, sources: List<NotificationSource>, startPage: Int): MergedNotificationPage {
    var page = startPage
    val items = mutableListOf<Notification>()
    val skipped = LinkedHashSet<NotificationSource>()
    var hasMore = true
    while (hasMore && page <= MAX_NOTIFICATION_PAGES) {
        val result = source.merged(sources, page)
        items += result.items
        skipped += result.skipped
        hasMore = result.hasMore
        page++
    }
    return MergedNotificationPage(items.distinctBy { it.link to it.title }, skipped, hasMore)
}

// ─────────────────────────────────────────────────────────────────────────────
// 消息收纳：GET /api/inbox
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 消息收纳（轻登录：要有统一认证会话）。
 *
 * 四路聚合的形状照 [InboxData]（`:core` 的收纳模型）：消息 / 待办（按分类整块）／已读等
 * 用户态。时间全部投影成 ISO-8601 带时区（契约 §4），`0` / 缺省（没读过 / 没过期）→ `null`
 * （= 本端明确说没有，不是字段缺失）。
 *
 * 触发：`?force=1` 强制刷新（绕过 30 分钟 TTL）；否则按 [InboxSource.isDue] 的 TTL 决定要不要
 * 真去拉（与桌面端进门那一枪同一口径）。账号 = 会话里的当前账号（`AccountContext`），
 * 响应里**不出现**学号。
 */
internal fun Route.inboxRoutes(session: ServeSession) {
    get(INBOX_SEGMENT) {
        val source = AppInboxSource(session.sessionManager)
        val account = AccountContext.activeAccountId
        val force = call.request.queryParameters["force"] == "1"
        val data = try {
            if (force || source.isDue(account, System.currentTimeMillis())) {
                source.refresh(account)
            }
            InboxStore.load(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondFetchError(e, "刷新消息收纳")
        }
        call.respond(ApiEnvelope.ok(data.toDto()))
    }
}

/** `GET /api/inbox` 的 `data` —— [InboxData] 的逐字段投影（时间为 ISO-8601 带时区）。 */
@Serializable
internal data class InboxPayload(
    val messages: List<InboxItemDto>,
    val todos: Map<String, List<InboxItemDto>>,
    val finished: List<FinishedTodoDto>,
    val readAt: Map<String, String>,
    val seenTodos: Set<String>,
    val ignored: Set<String>,
    val off: Set<String>,
    val schoolFetchedAt: String?,
    val bubbled: Set<String>,
)

/** [InboxItem] 的线上形状；`time`/`expiresAt` 为 0 时是 `null`。 */
@Serializable
internal data class InboxItemDto(
    val id: String,
    val category: String,
    val source: String,
    val title: String,
    val body: String,
    val time: String?,
    val route: String?,
    val expiresAt: String?,
)

/** [FinishedTodo] 的线上形状。 */
@Serializable
internal data class FinishedTodoDto(val item: InboxItemDto, val at: String, val ignored: Boolean)

private fun InboxItem.toDto() = InboxItemDto(
    id = id,
    category = category,
    source = source,
    title = title,
    body = body,
    time = isoTime(time),
    route = route,
    expiresAt = isoTime(expiresAt),
)

private fun FinishedTodo.toDto() = FinishedTodoDto(item.toDto(), isoTime(at) ?: "", ignored)

private fun InboxData.toDto() = InboxPayload(
    messages = messages.map { it.toDto() },
    todos = todos.mapValues { (_, items) -> items.map { it.toDto() } },
    finished = finished.map { it.toDto() },
    readAt = readAt.mapValues { (_, at) -> isoTime(at) ?: "" },
    seenTodos = seenTodos,
    ignored = ignored,
    off = off,
    schoolFetchedAt = isoTime(schoolFetchedAt),
    bubbled = bubbled,
)

// ─────────────────────────────────────────────────────────────────────────────
// 空闲教室：GET /api/emptyroom/cdn · /api/emptyroom/rooms
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 空闲教室（CDN 免登录；直查/实时轻登录）。
 *
 * 两个端点都带 **能力开关**（契约 §5「要改」）：`availableSources` 如实报**这一发请求时**
 * serve 能真取数的那几档 —— 会话没登录过只有 `[{key:cdn}]`；登录过就是 `[live, cdn, direct]`
 * 三档（顺序照屏上的菜单：默认第一档是实时）。开关随请求变，不硬编。
 *
 * 语义（照 `:core` 的 [EmptyRoomSource] KDoc）：
 * - `cdn` —— 预生成的课表快照，免登录，可看今天/明天；
 * - `direct` —— 登录教务实时查课表，结果和 CDN 同源（每楼 11+1 次请求）；
 * - `live` —— 智慧教室平台的此刻状态（含上课/有人，带人数），只覆盖兴庆/雁塔/创新港。
 *
 * 没有数据的日期（CDN 404 / 校区不认识）→ **不是错误**：`data.noData = true` + `note`
 * （照旧 campus-api 的 cdn 形状，沿用那一档的口径），`rooms` 为空。
 */
internal fun Route.emptyRoomRoutes(session: ServeSession) {
    val sourceOf: (ServeSession) -> EmptyRoomSource = { s -> AppEmptyRoomSource(s.sessionManager) }

    /** CDN 档：某校区某天（可选楼）的逐节占用表，一天一次请求。 */
    get(EMPTY_ROOM_CDN_SEGMENT) {
        val source = sourceOf(session)
        val campus = call.request.queryParameters["campus"]?.trim().orEmpty()
        if (campus.isEmpty()) return@get call.respondBadRequest("参数 campus 必填：校区名，如 兴庆校区")
        val buildings = queriedBuildings(campus, call.request.queryParameters["building"])
        if (buildings.isEmpty()) return@get call.respondBadRequest("不认识的校区或教学楼：$campus")
        val date = call.request.queryParameters["date"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: source.availableDates().first()
        val force = call.request.queryParameters["force"] == "1"
        call.respondRoomRows(source, session, campus, buildings, date, direct = false, force)
    }

    /**
     * 直查 / 实时那一档（`source` 参数二选一，缺省 `direct`）。
     *
     * `direct`：登录教务查课表（每楼 11+1 次请求），`rooms[]` 与 CDN 同一形状（`status[11]`）。
     * `live`：智慧教室平台此刻快照，`rooms[]` 是 {name, building, status, people, seats, course, teacher}，
     * `fetchedAt` 是这份快照的抓取时刻。两档都要先登录（未登录 → 401）。
     */
    get(EMPTY_ROOM_ROOMS_SEGMENT) {
        val source = sourceOf(session)
        val tier = call.request.queryParameters["source"]?.trim()?.lowercase() ?: "direct"
        if (tier != "direct" && tier != "live") {
            return@get call.respondBadRequest("参数 source 只认 direct / live，实际：$tier")
        }
        val campus = call.request.queryParameters["campus"]?.trim().orEmpty()
        if (campus.isEmpty()) return@get call.respondBadRequest("参数 campus 必填：校区名，如 兴庆校区")
        val buildings = queriedBuildings(campus, call.request.queryParameters["building"])
        if (tier == "direct" && buildings.isEmpty()) {
            return@get call.respondBadRequest("不认识的校区或教学楼：$campus")
        }
        val date = call.request.queryParameters["date"]?.trim()?.takeIf { it.isNotEmpty() }
        val force = call.request.queryParameters["force"] == "1"

        if (tier == "live") {
            val snapshot = try {
                source.liveSnapshot(campus, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoDataException) {
                return@get call.respondRoomNoData(session, campus, emptyList(), source.availableDates().first(), direct = false, e.message)
            } catch (e: Exception) {
                return@get call.respondFetchError(e, "查询实时状态")
            }
            call.respond(
                ApiEnvelope.ok(
                    LiveRoomsPayload(
                        availableSources = availableSources(session),
                        source = "live",
                        campus = campus,
                        date = source.availableDates().first(),
                        noData = false,
                        note = null,
                        fetchedAt = isoTime(snapshot.fetchedAt),
                        buildings = snapshot.buildings,
                        rooms = snapshot.rooms.map { it.toDto() },
                    ),
                ),
            )
        } else {
            val day = date ?: source.availableDates().first()
            call.respondRoomRows(source, session, campus, buildings, day, direct = true, force)
        }
    }
}

/** 一楼一查再拼（每个楼才能标上 `building`），按教室名排序 —— 与直接拼 [RoomInfo] 同一份数据。 */
private suspend fun ApplicationCall.respondRoomRows(
    source: EmptyRoomSource,
    session: ServeSession,
    campus: String,
    buildings: List<String>,
    date: String,
    direct: Boolean,
    force: Boolean,
) {
    val rows = try {
        val merged = mutableListOf<RoomRowDto>()
        for (building in buildings) {
            source.rooms(campus, setOf(building), date, direct = direct, force = force, onProgress = { _, _ -> })
                .forEach { merged.add(RoomRowDto(campus, building, it.name, it.size, it.status)) }
        }
        merged.sortedBy { it.room }
    } catch (e: CancellationException) {
        throw e
    } catch (e: NoDataException) {
        return respondRoomNoData(session, campus, buildings, date, direct, e.message)
    } catch (e: Exception) {
        return respondFetchError(e, if (direct) "直查教务" else "加载课表数据")
    }
    respond(
        ApiEnvelope.ok(
            RoomRowsPayload(
                availableSources = availableSources(session),
                source = if (direct) "direct" else "cdn",
                campus = campus,
                buildings = buildings,
                date = date,
                noData = false,
                note = null,
                rooms = rows,
            ),
        ),
    )
}

/**
 * 没有数据（CDN 那天没文件 / 直查校区不认识）——照旧 campus-api 的形状：`noData:true` + `note`，
 * `rooms` 空。这是「这一天/这个校区没有数据」，**不是**请求失败（失败走信封错误码）。
 */
private suspend fun ApplicationCall.respondRoomNoData(
    session: ServeSession,
    campus: String,
    buildings: List<String>,
    date: String,
    direct: Boolean,
    note: String?,
) {
    respond(
        ApiEnvelope.ok(
            RoomRowsPayload(
                availableSources = availableSources(session),
                source = if (direct) "direct" else "cdn",
                campus = campus,
                buildings = buildings,
                date = date,
                noData = true,
                note = note?.takeIf { it.isNotBlank() } ?: "当天暂无空闲教室数据，请稍后再试",
                rooms = emptyList(),
            ),
        ),
    )
}

/** `GET /api/emptyroom/cdn` 与 `/rooms`（direct 档）的 `data`。 */
@Serializable
internal data class RoomRowsPayload(
    val availableSources: List<RoomSourceDto>,
    val source: String,
    val campus: String,
    val buildings: List<String>,
    val date: String,
    val noData: Boolean,
    val note: String?,
    val rooms: List<RoomRowDto>,
)

/** 一行教室：[RoomInfo] 的投影 + 本端回填的校区/楼（`:data` 的 [RoomInfo] 不带这两项）。 */
@Serializable
internal data class RoomRowDto(
    val campus: String,
    val building: String,
    val room: String,
    val seats: Int,
    val status: List<Int>,
)

/** `GET /api/emptyroom/rooms?source=live` 的 `data`。 */
@Serializable
internal data class LiveRoomsPayload(
    val availableSources: List<RoomSourceDto>,
    val source: String,
    val campus: String,
    val date: String,
    val noData: Boolean,
    val note: String?,
    val fetchedAt: String?,
    val buildings: List<String>,
    val rooms: List<LiveRoomDto>,
)

/** [LiveRoom] 的线上形状。`teacher` 是这间教室**当前这堂课**的教师名（公开的课堂信息，不是登录用户）。 */
@Serializable
internal data class LiveRoomDto(
    val name: String,
    val building: String,
    val status: Int,
    val people: Int,
    val seats: Int,
    val course: String?,
    val teacher: String?,
)

/** 能力开关里的一个档位（key = [RoomSource.key]，name 照屏上的叫法）。 */
@Serializable
internal data class RoomSourceDto(val key: String, val name: String)

private fun LiveRoom.toDto() = LiveRoomDto(name, building, status, people, seats, course, teacher)

/** 本端真能取数的档位；没登录只有 CDN（见 [emptyRoomRoutes] 的 KDoc）。 */
private fun availableSources(session: ServeSession): List<RoomSourceDto> {
    val tiers = if (session.authenticated) {
        listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT)
    } else {
        listOf(RoomSource.CDN)
    }
    return tiers.map { RoomSourceDto(it.key, displayName(it)) }
}


private fun displayName(source: RoomSource): String = when (source) {
    RoomSource.LIVE -> "实时状态"
    RoomSource.CDN -> "CDN 课表"
    RoomSource.DIRECT -> "直查教务"
}

/** `building` 参数（逗号分隔多个楼）→ 楼名单；留空 = 该校区全部楼（[CAMPUS_BUILDINGS]）。 */
private fun queriedBuildings(campus: String, raw: String?): List<String> {
    val all = CAMPUS_BUILDINGS[campus].orEmpty()
    if (all.isEmpty()) return emptyList()
    val wanted = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return all
    val unknown = wanted.filterNot { it in all }
    if (unknown.isNotEmpty()) return emptyList()
    return wanted
}

// ─────────────────────────────────────────────────────────────────────────────
// 小工具：失败码 / ISO 时间
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 取数失败 → 信封（契约 §4）：`401` = 需要先登录 / 登录态失效（重发没意义），
 * `502` = 上游或网络的故障（稍后重试可能就成）。文案是 [FriendlyError] 的中文短句。
 */
private suspend fun ApplicationCall.respondFetchError(e: Throwable, action: String) {
    if (e is CancellationException) throw e
    val message = FriendlyError.of(e, action)
    val needsLogin = e is SessionExpiredFailure || "登录" in message
    respond(
        if (needsLogin) HttpStatusCode.Unauthorized else HttpStatusCode.BadGateway,
        ApiErrors.loginFailed(message, retryable = !needsLogin),
    )
}

/** 400 + 信封（[ApiErrors.badRequest]）。 */
private suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ApiErrors.badRequest(message))

/** epoch 毫秒 → ISO-8601 带时区字符串（契约 §4）；`<= 0`（没读过 / 没过期）→ null。 */
private fun isoTime(epochMs: Long): String? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER)
}

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

// ── 路径段（相对 route("/api")；对外路径 = API_PREFIX + "/" + 段）────────────────

internal const val CALENDAR_SCHOOL_SEGMENT = "calendar/school"
internal const val INFO_SEGMENT = "info"
internal const val INFO_YELLOWPAGE_SEGMENT = "yellowpage"
internal const val INFO_FACULTY_SEGMENT = "faculty"
internal const val FITNESS_YEARS_SEGMENT = "fitness/years"
internal const val FITNESS_SCORE_SEGMENT = "fitness/score"
internal const val NOTIFICATION_SEGMENT = "notification"
internal const val NOTIFICATION_SOURCES_SEGMENT = "sources"
internal const val NOTIFICATION_LIST_SEGMENT = "list"
internal const val INBOX_SEGMENT = "inbox"
internal const val EMPTY_ROOM_CDN_SEGMENT = "emptyroom/cdn"
internal const val EMPTY_ROOM_ROOMS_SEGMENT = "emptyroom/rooms"

/** 通知列表 `sources` 缺省时的默认源（与屏的默认来源一致，见 `NotificationViewModel`）。 */
internal const val DEFAULT_NOTIFICATION_SOURCE = "JWC"

/** `all=1` 拉全页的上限：爬虫每页条数不定，加一个硬顶免得上游行为怪异时死循环。 */
internal const val MAX_NOTIFICATION_PAGES = 20