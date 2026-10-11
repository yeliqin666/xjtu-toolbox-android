package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryCampus
import com.xjtu.toolbox.library.LibrarySession
import com.xjtu.toolbox.library.SeatResult
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * `/api/library*` —— 四个端点（`docs/api-contract.md` §5 的 P0），全部走 `:data` 的
 * [LibraryApi]（通过 [ServerLibrarySession] 这个缝接到会话内核）：取数/解析一行不重做。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/library/campus` | `LibraryApi.getCurrentCampus()`（`LibraryCampus`） |
 * | `GET /api/library/areas` | `LibraryApi.getFloorAreas()` + `cachedAreaStats`（`AreaStats`） |
 * | `GET /api/library/seats` | `LibraryApi.getSeats()`（`SeatResult`） |
 * | `GET /api/library/my` | `LibraryApi.fetchMyBooking()`（`Result<MyBookingInfo?>`） |
 *
 * ## 逐字段映射（判据是 `CampusLibraryApi` 的 KDoc 表 + `:data` 的实现）
 *
 * - **campus**：`current{code,name}`（`code`= `LibraryCampus.id`，`name`= `displayName`；认不出 =
 *   null，不猜）`+ campuses[]`（三校区表 `LibraryCampus.entries`）`+ queryableFloors[]`
 *   （当前校区的 `floorCodes`；current 为 null 时 null）`+ canBook/hasSeatPlan`（能力开关，§4/§2）；
 * - **areas**：`?campus=&floor=`（`campus` 当前不参与查询：座位系统跟着账号的 `rplace` 走，
 *   与 campus-api 同一条限制；`floor` 缺省 = 当前校区全部楼层，逐层拉一次）。每行
 *   `{code, name, floor, available, total}`；`scount` 只把**有统计**的区域填 total/available
 *   （`:data` 的 `filterScount` 就是这条判据 —— 「统计没到」的区域 available/total 为 null，
 *   不拿 0 冒充）。`totals{}` 与 `areas[]` 同源（同一份 `cachedAreaStats`）；
 * - **seats**：`?area=` 必填 ⇒ 400。`SeatResult.Success` → `{area, total, available, seats[]}`；
 *   `AuthError` / `Error` → 502（上游问题，`FriendlyError` 文案）。`time` 参数**收下但不传给
 *   上游**：`:data` 的 `getSeats` 只有 `area` 一维（`qseat?sp=`），时间维度是 TODO（见返回报告）；
 * - **my**：`{my: MyBookingInfo|null}`。`success(null)` = 页面明确说「没有预约」⇒ `my: null`
 *   （§4：null = 上游明确说没有，≠ 空对象）；`failure` = 502。
 *   ⚠️ `MyBookingInfo.actionUrls` **不投影**：那些 URL 指向上游站点（`rg.lib.xjtu.edu.cn`），
 *   而执行动作的 `/api/library/action` 是 P1 写端点、不实现 —— 投影出来就是给浏览器一个
 *   「点了会失败的按钮」（canBook=true 只声明能力，按 §2 开关语义客户端据此画按钮，P1 再接线）。
 *
 * ## 能力开关
 *
 * `canBook = true`（`:data` 的写路径一行未改，是会真成功的）、`hasSeatPlan = true`
 * （布局/底图端点都在）。**写端点本身（book/swap/action）是 P1，本层不实现** —— 能力开关
 * 如实报，端点如实 404，两件事不互相掩盖。
 *
 * @param session 会话装配；图书馆站点从 `session.sessionManager` 取。
 */
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
                            // success(null) = 页面明确说「没有预约」⇒ null（§4：null ≠ 空对象）
                            my = info?.let { MyBookingInfoDto(it.seatId, it.area, it.statusText) },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载我的预约")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/library/campus` 的 `data`。 */
@Serializable
internal data class LibraryCampusData(
    val current: LibraryCampusDto? = null,
    val campuses: List<LibraryCampusDto>,
    val queryableFloors: List<String>? = null,
    /** 能力开关：本部署能预约/换座/执行动作（写端点是 P1，能力如实报）。 */
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
    /** `null` = 页面明确说「没有预约」（§4）。`actionUrls` 刻意不投影（P1 写端点，见文件头 KDoc）。 */
    val my: MyBookingInfoDto? = null,
)

@Serializable
internal data class MyBookingInfoDto(
    val seatId: String? = null,
    val area: String? = null,
    val statusText: String? = null,
)

// ── 会话缝与常量 ─────────────────────────────────────────────────────

/**
 * 把会话内核的 [SiteSession] 包成 `:data` 的 [LibrarySession] 缝 —— 与桌面端的
 * `SiteBackedLibrarySession` 同一段代码（`:server` 是独立模块，那里的是 private，这里重写一份；
 * 三行转发、每一行的理由都在 [LibrarySession] 的 KDoc）。
 */
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