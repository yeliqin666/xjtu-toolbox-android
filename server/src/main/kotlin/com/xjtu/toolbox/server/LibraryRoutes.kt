package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.library.BookResult
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryCampus
import com.xjtu.toolbox.library.LibrarySession
import com.xjtu.toolbox.library.SeatResult
import io.ktor.server.response.respond
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** `/api/library*`。 */
internal fun Route.libraryRoutes(session: ServeSession) {
    route(LIBRARY_SEGMENT) {

        // ── 校区 ───────────────────────────────────────────────────────
        get(LIBRARY_CAMPUS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val api = session.libraryApi()
                val current = api.getCurrentCampus()
                val floors = current?.floorCodes
                call.respond(
                    ApiEnvelope.ok(
                        LibraryCampusData(
                            current = current?.let { LibraryCampusDto(it.id, it.displayName) },
                            campuses = LibraryCampus.entries.map { LibraryCampusDto(it.id, it.displayName) },
                            queryableFloors = floors,
                            canBook = true,
                            hasSeatPlan = true,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载图书馆校区")
            }
        }

        // ── 区域（某层；不给 floor = 当前校区的全部楼层）─────────────────
        get(LIBRARY_AREAS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val floor = call.request.queryParameters["floor"]?.trim()?.takeIf { it.isNotEmpty() }
            // `?campus=` 收了但不参与查询：座位系统跟着账号的 rplace 走，切校区是写操作（P1 的 switch）
            // —— 与 campus-api 同一条限制（见文件头 KDoc）
            try {
                val api = session.libraryApi()
                val codeToName = if (floor != null) {
                    api.getFloorAreas(floor)
                } else {
                    // 缺省 = 当前校区全部楼层（与 LibrarySource.warmCampusAreas 同一个形状：逐层拉一次）
                    val campus = api.getCurrentCampus() ?: LibraryCampus.XINGQING
                    linkedMapOf<String, String>().apply {
                        campus.floorCodes.forEach { f -> api.getFloorAreas(f).forEach { (code, name) -> putIfAbsent(code, name) } }
                    }
                }
                val stats = api.cachedAreaStats
                val areas = codeToName.map { (code, name) ->
                    AreaRow(
                        code = code,
                        name = name,
                        floor = api.floorOfArea(code),
                        available = stats[code]?.available,
                        total = stats[code]?.total,
                    )
                }
                call.respond(
                    ApiEnvelope.ok(
                        LibraryAreasData(
                            floor = floor,
                            areaCount = areas.size,
                            areas = areas,
                            totals = stats.mapValues { (_, s) -> AreaTotals(s.available, s.total) },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载图书馆区域")
            }
        }

        // ── 某区域的座位实时状态 ────────────────────────────────────────
        get(LIBRARY_SEATS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val area = call.request.queryParameters["area"]?.trim()
            if (area.isNullOrEmpty()) {
                return@get call.respondBadRequestMessage("area 参数必填")
            }
            try {
                when (val result = session.libraryApi().getSeats(area)) {
                    is SeatResult.Success -> {
                        val seats = result.seats.map { SeatRow(it.seatId, it.available) }
                        call.respond(
                            ApiEnvelope.ok(
                                LibrarySeatsData(
                                    area = area,
                                    total = seats.size,
                                    available = seats.count { it.available },
                                    seats = seats,
                                ),
                            ),
                        )
                    }
                    is SeatResult.AuthError -> call.respondUpstreamFailure(
                        RuntimeException("图书馆会话已失效，请重新登录"), "加载座位",
                    )
                    is SeatResult.Error -> call.respondUpstreamFailure(RuntimeException(result.message), "加载座位")
                }
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载座位")
            }
        }

        // ── 我的预约 ────────────────────────────────────────────────────
        get(LIBRARY_MY) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val outcome = session.libraryApi().fetchMyBooking()
                if (outcome.isFailure) {
                    val error = outcome.exceptionOrNull() ?: IllegalStateException("预约页面认不出来")
                    call.respondUpstreamFailure(error, "加载我的预约")
                    return@get
                }
                val info = outcome.getOrNull()
                call.respond(
                    ApiEnvelope.ok(
                        MyBookingData(
                            my = info?.let {
                                MyBookingInfoDto(
                                    it.seatId,
                                    it.area,
                                    it.statusText,
                                    it.actionUrls,
                                )
                            },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载我的预约")
            }
        }

        // ── 写：约座 / 换座 / 签到 / 中途离开 / 中途返回 / 退座 / 切校区，一个端点按 `data.action` 分（契约 §5.3.1）──
        post(LIBRARY_ACTION) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<LibraryActionRequest>()
                ?: return@post call.respondBadRequestMessage(LIBRARY_ACTION_BODY_MESSAGE)
            val api = session.libraryApi()
            when (val action = body.action.trim()) {
                LIBRARY_ACTION_SEAT, LIBRARY_ACTION_CHANGE -> {
                    val seatId = body.seatId?.trim().orEmpty()
                    val area = body.area?.trim().orEmpty()
                    if (seatId.isEmpty() || area.isEmpty()) {
                        return@post call.respondBadRequestMessage(
                            "action=$action 要带 seatId 与 area（座位号与区域码）",
                        )
                    }
                    call.respondLibraryAction(if (action == LIBRARY_ACTION_SEAT) "预约座位" else "换座") {
                        if (action == LIBRARY_ACTION_SEAT) api.bookSeat(seatId, area, body.autoSwap)
                        else api.swapSeat(seatId, area)
                    }
                }

                // 四个 `/my/` 页面动作：「打向哪个地址」不由客户端给 —— 从**当前**预约页上取那条按钮的
                // 真实地址（`:data` 的 `executeAction` 正是按地址再复核一次「这一枪生效了没有」）。
                LIBRARY_ACTION_SIGNIN, LIBRARY_ACTION_LEAVE,
                LIBRARY_ACTION_BACK, LIBRARY_ACTION_CANCEL,
                -> {
                    val label = LIBRARY_PAGE_ACTION_LABELS.getValue(action)
                    val booking = try {
                        api.fetchMyBooking()
                    } catch (e: Exception) {
                        return@post call.respondUpstreamFailure(e, "读取我的预约")
                    }
                    if (booking.isFailure) {
                        return@post call.respondUpstreamFailure(
                            booking.exceptionOrNull() ?: IllegalStateException("预约页面认不出来"),
                            "读取我的预约",
                        )
                    }
                    val url = booking.getOrNull()?.actionUrls?.get(label)
                        ?: return@post call.respondActionRejected("当前预约上没有「$label」这个操作")
                    call.respondLibraryAction(label) { api.executeAction(url) }
                }

                LIBRARY_ACTION_SWITCH_CAMPUS -> {
                    val campus = LibraryCampus.byId(body.campus)
                        ?: return@post call.respondBadRequestMessage(
                            "campus 只认 ${LibraryCampus.entries.joinToString(" / ") { it.id }}",
                        )
                    val switched = try {
                        api.switchCampus(campus)
                    } catch (e: Exception) {
                        return@post call.respondUpstreamFailure(e, "切换校区")
                    }
                    if (!switched) {
                        // `:data` 的 switchCampus 把「没提交上 / 上游没确认」都吞成 false ⇒ 只能算上游那一层
                        // 出了问题（502 的含义正是「稍后重试可能就成」）。
                        return@post call.respondUpstreamFailure(
                            IllegalStateException("图书馆没有确认这次校区切换"),
                            "切换校区",
                        )
                    }
                    call.respond(
                        ApiEnvelope.ok(LibraryActionResult(ok = true, message = "✓ 已切换到${campus.displayName}校区")),
                    )
                }

                else -> call.respondBadRequestMessage(
                    "未知的 action：${body.action}（只认 ${LIBRARY_ACTIONS.joinToString(" / ")}）",
                )
            }
        }
    }
}


// ── 写路径的数据模型（客户端形状）───────────────────────────────────────────

/** `POST /api/library/action` 的请求体。 */
@Serializable
internal data class LibraryActionRequest(
    /** 动作码：`seat` / `change` / `signin` / `leave` / `back` / `cancel` / `switch_campus`。 */
    val action: String = "",
    /** `seat` / `change`：座位号（如 `A01`）。 */
    val seatId: String? = null,
    /** `seat` / `change`：区域码（如 `north2east`）。 */
    val area: String? = null,
    /** `seat`：已有预约时自动改走换座。默认 `true` —— 与 `:data` 的 `bookSeat` 那个默认值一致。 */
    val autoSwap: Boolean = true,
    /** `switch_campus`：校区码 —— `east`（兴庆）/ `west`（雁塔）/ `inno`（创新港）。 */
    val campus: String? = null,
)

/** `POST /api/library/action` 成功时的 `data`。 */
@Serializable
internal data class LibraryActionResult(
    /** 动作做成了没有。 */
    val ok: Boolean,
    /** `:data` 的 `BookResult.message` 原样转发（已是中文短句）。 */
    val message: String,
)

// ── 写路径的小工具 ─────────────────────────────────────────────────────

/** 一次写动作的结果 → 信封（契约 §5.3.1）。 */
private suspend fun ApplicationCall.respondBookResult(result: BookResult) {
    if (result.success) {
        return respond(ApiEnvelope.ok(LibraryActionResult(ok = true, message = result.message)))
    }
    // `:data` 的 BookResult 只有 `success` + `message`（业务拒绝与网络故障不分档），所以这里只分一处：
    // 会话失效（那边把话吞进 message 里了）⇒ 401 指路先登录；其余一律 409 + 原文（§5.3.1）。
    if (LIBRARY_SESSION_EXPIRED_MARK in result.message) return respondLoginRequired()
    respondActionRejected(result.message)
}

/** 一次写动作：抛出来的算上游/网络故障（502），返回的 [BookResult] 交给 [respondBookResult]。 */
private suspend fun ApplicationCall.respondLibraryAction(what: String, action: suspend () -> BookResult) {
    val result = try {
        action()
    } catch (e: Exception) {
        return respondUpstreamFailure(e, what)
    }
    respondBookResult(result)
}


// ── 动作码（契约 §5.3.1）────────────────────────────────────────────────

/** 四个 `/my/` 页面动作 → 「我的预约」页上那个按钮的文案。 */
private val LIBRARY_PAGE_ACTION_LABELS = mapOf(
    LIBRARY_ACTION_SIGNIN to "入馆签到",
    LIBRARY_ACTION_LEAVE to "中途离开",
    LIBRARY_ACTION_BACK to "中途返回",
    LIBRARY_ACTION_CANCEL to "取消预约",
)

private const val LIBRARY_ACTION_SEAT = "seat"
private const val LIBRARY_ACTION_CHANGE = "change"
private const val LIBRARY_ACTION_SIGNIN = "signin"
private const val LIBRARY_ACTION_LEAVE = "leave"
private const val LIBRARY_ACTION_BACK = "back"
private const val LIBRARY_ACTION_CANCEL = "cancel"
private const val LIBRARY_ACTION_SWITCH_CAMPUS = "switch_campus"

/** 合法动作码（400 的文案里逐个列出来，客户端照着改）。 */
private val LIBRARY_ACTIONS = listOf(
    LIBRARY_ACTION_SEAT,
    LIBRARY_ACTION_CHANGE,
    LIBRARY_ACTION_SIGNIN,
    LIBRARY_ACTION_LEAVE,
    LIBRARY_ACTION_BACK,
    LIBRARY_ACTION_CANCEL,
    LIBRARY_ACTION_SWITCH_CAMPUS,
)

private const val LIBRARY_ACTION_BODY_MESSAGE =
    "请求体需要 JSON：{\"action\":\"seat\",\"seatId\":\"A01\",\"area\":\"north2east\"}（动作表见契约 §5.3.1）"

/** 会话失效的判据 —— `:data` 把它吞进 `BookResult.message`（见 [respondBookResult]）。 */
private const val LIBRARY_SESSION_EXPIRED_MARK = "登录"

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/library/campus` 的 `data`。 */
@Serializable
internal data class LibraryCampusData(
    val current: LibraryCampusDto? = null,
    val campuses: List<LibraryCampusDto>,
    val queryableFloors: List<String>? = null,
    /** 能力开关：本部署能预约/换座/执行动作。 */
    val canBook: Boolean,
    /** 能力开关：布局与平面图端点都在（P0 不投影，P1 接）。 */
    val hasSeatPlan: Boolean,
)

@Serializable
internal data class LibraryCampusDto(val code: String, val name: String)

/** `GET /api/library/areas` 的 `data`。 */
@Serializable
internal data class LibraryAreasData(
    val floor: String? = null,
    val areaCount: Int,
    val areas: List<AreaRow>,
    /** 区域码 → 空座/总数；只有 `scount` 里**有统计**的区域才在（`:data` 同判据）。 */
    val totals: Map<String, AreaTotals>,
)

@Serializable
internal data class AreaRow(
    val code: String,
    val name: String,
    val floor: String? = null,
    /** 统计没到 = null（不拿 0 冒充「满了」）。 */
    val available: Int? = null,
    val total: Int? = null,
)

@Serializable
internal data class AreaTotals(val available: Int, val total: Int)

/** `GET /api/library/seats` 的 `data`。 */
@Serializable
internal data class LibrarySeatsData(
    val area: String,
    val total: Int,
    val available: Int,
    val seats: List<SeatRow>,
)

@Serializable
internal data class SeatRow(val seatId: String, val available: Boolean)

/** `GET /api/library/my` 的 `data`。 */
@Serializable
internal data class MyBookingData(
    /** `null` = 页面明确说「没有预约」（§4）；`actionUrls` 是页面上那几个动作 → 上游地址（见文件头）。 */
    val my: MyBookingInfoDto? = null,
)

@Serializable
internal data class MyBookingInfoDto(
    val seatId: String? = null,
    val area: String? = null,
    val statusText: String? = null,
    /** 「取消预约」「入馆签到」… → 上游那条动作地址（见文件头）。 */
    val actionUrls: Map<String, String> = emptyMap(),
)

// ── 会话缝与常量 ─────────────────────────────────────────────────────

/** 把会话内核的 [SiteSession] 包成 `:data` 的 [LibrarySession] 缝。 */
private class ServerLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = SessionExpiredException(siteName)
}

/** 图书馆取数实例：每次请求现建（类是轻的；站点会话由 [ServeSession] 持有）。 */
internal fun ServeSession.libraryApi(): LibraryApi = LibraryApi(ServerLibrarySession(sessionManager.getSite(ServeSession.LIBRARY_SITE_KEY)))

internal const val LIBRARY_SEGMENT = "library"
internal const val LIBRARY_CAMPUS = "campus"
internal const val LIBRARY_AREAS = "areas"
internal const val LIBRARY_SEATS = "seats"
internal const val LIBRARY_MY = "my"
internal const val LIBRARY_ACTION = "action"