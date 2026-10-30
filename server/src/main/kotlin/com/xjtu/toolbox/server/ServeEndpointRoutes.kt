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

/** P0 取数端点组一（免登录 / 轻登录六域） —— `docs/api-contract.md` §5 那批 P0 端点的实现。 */
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


/** 「免登录公开信息」两屏：黄页（机构通讯录）+ 教师检索（院系主页查询）。 */
internal fun Route.infoRoutes(yellowpage: YellowPageSource, faculty: FacultySource) {
    route(INFO_SEGMENT) {
        /** 黄页。 */
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

        /** 教师检索。 */
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

/** `GET /api/info/faculty` 的 `data`。 */
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


/** 体测（轻登录：要有体测站点会话）。 */
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

        /** 通知列表。 */
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


/** 消息收纳（轻登录：要有统一认证会话）。 */
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

/** `GET /api/inbox` 的 `data`：[InboxData] 的投影（时间为带时区的 ISO-8601）。 */
@Serializable
internal data class InboxPayload(
    val messages: List<InboxItemDto>,
    val todos: Map<String, List<InboxItemDto>>,
    val finished: List<FinishedTodoDto>,
    val readAt: Map<String, String>,
    val seenTodos: Set<String>,
    val ignored: Set<String>,
    val off: Set<String>,
    /** 待办 id → 重要程度（`InboxRules.IMPORTANCE_*`）。投影必须带上，否则浏览器那端标的级别存了也读不回来。 */
    val importance: Map<String, Int> = emptyMap(),
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
    importance = importance,
    schoolFetchedAt = isoTime(schoolFetchedAt),
    bubbled = bubbled,
)


/** 空闲教室（CDN 免登录；直查/实时轻登录）。 */
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

    /** 座位数（`:core` 的 `EmptyRoomSource.seatCount`）：按教室名取一个数字。 */
    get(EMPTY_ROOM_SEAT_COUNT_SEGMENT) {
        val room = call.request.queryParameters["room"]?.trim().orEmpty()
        if (room.isEmpty()) return@get call.respondBadRequest("参数 room 必填：教室名，如 主楼A-101")
        val seats = try {
            sourceOf(session).seatCount(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@get call.respondFetchError(e, "查询教室座位数")
        }
        call.respond(ApiEnvelope.ok(SeatCountPayload(room = room, seats = seats)))
    }

    /** 直查 / 实时那一档（`source` 参数二选一，缺省 `direct`）。 */
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

/** 没有数据（CDN 那天没文件 / 直查校区不认识）——照旧 campus-api 的形状：`noData:true` + `note`，`rooms` 空。 */
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

/** `GET /api/emptyroom/seat-count` 的 `data`。 */
@Serializable
internal data class SeatCountPayload(val room: String, val seats: Int?)

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

/** 取数失败 → 信封（契约 §4）：`401` = 需要先登录 / 登录态失效（重发没意义），`502` = 上游或网络的故障（稍后重试可能就成）。 */
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
internal const val EMPTY_ROOM_SEAT_COUNT_SEGMENT = "emptyroom/seat-count"

/** 通知列表 `sources` 缺省时的默认源（与屏的默认来源一致，见 `NotificationViewModel`）。 */
internal const val DEFAULT_NOTIFICATION_SOURCE = "JWC"

/** `all=1` 拉全页的上限：爬虫每页条数不定，加一个硬顶免得上游行为怪异时死循环。 */
internal const val MAX_NOTIFICATION_PAGES = 20