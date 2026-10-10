package com.xjtu.toolbox.server

import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.AreaSlot
import com.xjtu.toolbox.venue.OrderDetail
import com.xjtu.toolbox.venue.OrderInfo
import com.xjtu.toolbox.venue.Venue
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/venue*` —— 四个端点（`docs/api-contract.md` §5 的 P0/P1，本层只做读那半），
 * 走 `:data` 的 [AppVenueSource]（包住 `VenueApi` 的场馆站抓取，解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/venue/products` | `VenueSource.venues()`（`Venue`） |
 * | `GET /api/venue/slots` | `VenueSource.slots(serviceId, date)`（`AreaSlot`） |
 * | `GET /api/venue/orders` | `VenueSource.orders(page, pageSize)`（`OrderPage`） |
 * | `GET /api/venue/status` | 能力开关（只读投影，不取数） |
 *
 * ## ⚠️ `slots` 的 `surplus`：不用旧字段，用 `status == 1` 可订的读法
 *
 * `:data` 的 `VenueApi.fetchAvailableSlots` 已经把「可订 + 已占用」两路合并，并且按 `:app` 的
 * 口径算出 `surplus = if (status == 1) (allCount - usingNum).coerceAtLeast(1) else 0`
 * （`CampusVenueApi` 的 KDoc 点名过：campus-api 的 `surplus` 在 `all == used` 时给 0、
 * 而两端一致的读法是可订 ⇒ 至少 1）。这里只管把 `AreaSlot` 投影出来 —— 那条口径在
 * `:data` 里，不在这一层。
 *
 * ## 能力开关（§2：点了会失败的按钮一个都不画）
 *
 * `canBook = false`：下单要先解滑块（长在 Android 的 `Bitmap`/`Base64` 上的控件），serve 没有
 * 那个宿主 —— `AppVenueSource(site, canBook = false)` 与桌面端同一条口径；`canCancel = true`：
 * 取消订单是一次普通 POST，不需要滑块。**写端点（/book /cancel）是 P1，本层不实现** ——
 * 开关如实报，端点如实 404。
 *
 * @param session 会话装配；场馆站点从 `session.sessionManager` 取。
 */
internal fun Route.venueRoutes(session: ServeSession) {
    route(VENUE_SEGMENT) {

        // ── 场馆列表（分页拉到不足一页为止，`:data` 自己定）──────────────
        get(VENUE_PRODUCTS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val venues = session.venueSource().venues()
                call.respond(ApiEnvelope.ok(VenuesData(venues = venues.map { it.toDto() })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载场馆列表")
            }
        }

        // ── 某场馆某天的时段 ────────────────────────────────────────────
        get(VENUE_SLOTS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val serviceId = params["serviceId"]?.toIntOrNull()
            val date = params["date"]?.trim()
            if (serviceId == null || date.isNullOrEmpty()) {
                return@get call.respondBadRequestMessage("serviceId 与 date 必填（date 形如 2026-10-12）")
            }
            try {
                val slots = session.venueSource().slots(serviceId, date)
                call.respond(
                    ApiEnvelope.ok(
                        SlotsData(
                            serviceId = serviceId,
                            date = date,
                            slots = slots.map { it.toDto() },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载场馆时段")
            }
        }

        // ── 我的订单一页 ────────────────────────────────────────────────
        get(VENUE_ORDERS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val page = params.positiveInt("page", 1) ?: return@get call.respondBadRequestMessage("page 需要是正整数")
            val size = params.positiveInt("size", 20) ?: return@get call.respondBadRequestMessage("size 需要是正整数")
            try {
                val pageResult = session.venueSource().orders(page, size)
                call.respond(
                    ApiEnvelope.ok(
                        OrdersData(
                            page = pageResult.page,
                            size = pageResult.pageSize,
                            total = pageResult.total,
                            orders = pageResult.orders.map { it.toDto() },
                            hasMore = pageResult.hasMore,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载场馆订单")
            }
        }

        // ── 场馆模块状态（能力开关）──────────────────────────────────────
        get(VENUE_STATUS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val source = session.venueSource()
            call.respond(
                ApiEnvelope.ok(
                    VenueStatusData(
                        canBook = source.canBook,
                        canCancel = source.canCancel,
                        browserLoginUrl = source.browserLoginUrl,
                    ),
                ),
            )
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/venue/products` 的 `data`。 */
@Serializable
internal data class VenuesData(val venues: List<VenueDto>)

@Serializable
internal data class VenueDto(
    val id: Int,
    val name: String,
    val address: String? = null,
    val iconType: String? = null,
    val advanceDay: Int,
    val advanceNum: Int,
)

/** `GET /api/venue/slots` 的 `data`。 */
@Serializable
internal data class SlotsData(
    val serviceId: Int,
    val date: String,
    val slots: List<AreaSlotDto>,
)

/** `AreaSlot` 原样投影（`surplus` 已是 `:data` 按「status == 1 可订」算好的，见文件头）。 */
@Serializable
internal data class AreaSlotDto(
    val areaDetailId: Long,
    val areaName: String,
    val stockId: Long,
    val timeSlot: String,
    val price: Double,
    val date: String,
    val allCount: Int,
    val usingNum: Int,
    val surplus: Int,
    val serviceid: String,
    /** `surplus > 0`（模型上的同一个判据）。 */
    val isAvailable: Boolean,
)

/** `GET /api/venue/orders` 的 `data`：分页形状（§4）。 */
@Serializable
internal data class OrdersData(
    val page: Int,
    val size: Int,
    /** 服务端总数；上游给不了时 null（`:data` 的 `OrderPage.total` 本来就是可空的）。 */
    val total: Int? = null,
    val orders: List<OrderInfoDto>,
    val hasMore: Boolean,
)

/** `OrderInfo` 的投影：状态数字 + 状态文案（模型上的两个字段都带）。 */
@Serializable
internal data class OrderInfoDto(
    val orderId: String,
    val status: Int,
    val statusText: String,
    val createdAt: String,
    val price: Double,
    val venueName: String,
    val canPay: Boolean,
    val canCancel: Boolean,
    val details: List<OrderDetailDto>,
)

@Serializable
internal data class OrderDetailDto(
    val date: String,
    val timeSlot: String,
    val areaName: String,
    val price: Double,
    val serviceId: String,
    val serviceName: String,
)

/** `GET /api/venue/status` 的 `data`：能力开关（§2）。 */
@Serializable
internal data class VenueStatusData(
    /** false：serve 没有滑块宿主，下单走不完（P1 写端点也不在）。 */
    val canBook: Boolean,
    /** true：取消不需要滑块（`:data` 直连场馆站）。 */
    val canCancel: Boolean,
    /** 支付页入口站（`:app` 内置浏览器走的那个）；canBook=false 时不会走到。 */
    val browserLoginUrl: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun Venue.toDto() = VenueDto(id, name, address, iconType, advanceDay, advanceNum)

private fun AreaSlot.toDto() = AreaSlotDto(
    areaDetailId = areaDetailId,
    areaName = areaName,
    stockId = stockId,
    timeSlot = timeSlot,
    price = price,
    date = date,
    allCount = allCount,
    usingNum = usingNum,
    surplus = surplus,
    serviceid = serviceid,
    isAvailable = isAvailable,
)

private fun OrderInfo.toDto() = OrderInfoDto(
    orderId = orderId,
    status = status,
    statusText = statusText,
    createdAt = createdAt,
    price = price,
    venueName = venueName,
    canPay = canPay,
    canCancel = canCancel,
    details = details.map { it.toDto() },
)

private fun OrderDetail.toDto() = OrderDetailDto(date, timeSlot, areaName, price, serviceId, serviceName)

/** `?page=&size=` 的正整数读法；不是正整数（或没给）返回 null。 */
private fun io.ktor.http.Parameters.positiveInt(name: String, default: Int): Int? {
    val raw = get(name)?.trim() ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
}

// ── 常量 ─────────────────────────────────────────────────────────────

/**
 * 场馆取数实例：每次请求现建；`canBook = false`（serve 没有滑块宿主 —— 与桌面端同一条口径，
 * 见 [AppVenueSource] 的 KDoc）。
 */
internal fun ServeSession.venueSource(): AppVenueSource =
    AppVenueSource(sessionManager.getSite(ServeSession.VENUE_SITE_KEY), canBook = false)

internal const val VENUE_SEGMENT = "venue"
internal const val VENUE_PRODUCTS = "products"
internal const val VENUE_SLOTS = "slots"
internal const val VENUE_ORDERS = "orders"
internal const val VENUE_STATUS = "status"