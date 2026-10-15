package com.xjtu.toolbox.server

import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.AreaSlot
import com.xjtu.toolbox.venue.CaptchaData
import com.xjtu.toolbox.venue.OrderDetail
import com.xjtu.toolbox.venue.OrderInfo
import com.xjtu.toolbox.venue.Venue
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/venue*`。 */
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

        // ── 滑块挑战：只发图与 captchaId，浏览器自己画、自己出轨迹 ──
        get(VENUE_CAPTCHA) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val serviceId = call.request.queryParameters["serviceId"]?.trim()?.toIntOrNull()
                ?: return@get call.respondBadRequestMessage("serviceId 必填（场馆号，来自 /api/venue/products 的 id）")
            try {
                call.respond(ApiEnvelope.ok(session.venueSource().captcha(serviceId).toDto()))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "获取滑块验证码")
            }
        }

        // ── 写：下单（轨迹带回来，服务端把它交给 `:data`）────────────────
        post(VENUE_BOOK) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<VenueBookRequest>()
                ?: return@post call.respondBadRequestMessage(VENUE_BOOK_BODY_MESSAGE)
            if (body.serviceId <= 0) {
                return@post call.respondBadRequestMessage("serviceId 需要是正整数（场馆号，来自 /api/venue/products 的 id）")
            }
            val selections = body.selections.orEmpty()
            if (selections.isEmpty()) {
                return@post call.respondBadRequestMessage(
                    "selections 至少要一行（每行 {stockId, areaDetailId}，来自 /api/venue/slots）",
                )
            }
            val badRow = selections.firstOrNull { it.stockId <= 0 || it.areaDetailId <= 0 }
            if (badRow != null) {
                return@post call.respondBadRequestMessage(
                    "selections 每一行都要 stockId 与 areaDetailId（正整数），收到：$badRow",
                )
            }
            val captchaId = body.captchaId?.trim().orEmpty()
            if (captchaId.isEmpty()) {
                return@post call.respondBadRequestMessage("captchaId 必填（GET /api/venue/captcha 给的那个）")
            }
            val sliderTrack = body.sliderTrack?.trim().orEmpty()
            if (sliderTrack.isEmpty()) {
                return@post call.respondBadRequestMessage("sliderTrack 必填（滑块轨迹的 JSON 原文，与 Android/桌面同一协议）")
            }
            val source = session.venueSource()
            // `prepareOrder` 是纯计算（`:data` 的 `VenueApi.prepareOrder` = 把选中项拼成服务端那个 param 信封）：
            // 入参形状上面已经验过（非空 + 两个数都是正整数），所以这里不再兜一层 400。
            val pendingOrder = source.prepareOrder(body.serviceId, selections.map { it.toAreaSlot(body.serviceId) })
            val result = try {
                source.submitBooking(body.serviceId, pendingOrder, captchaId, sliderTrack)
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "提交预订")
            }
            if (!result.success) return@post call.respondActionRejected(result.message)
            call.respond(
                ApiEnvelope.ok(
                    VenueBookResult(
                        ok = true,
                        orderId = result.orderId,
                        price = result.price,
                        message = result.message,
                    ),
                ),
            )
        }

        // ── 写：取消订单（不需要滑块，一次普通 POST）────────────────────
        post(VENUE_CANCEL) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<VenueCancelRequest>()
                ?: return@post call.respondBadRequestMessage(VENUE_CANCEL_BODY_MESSAGE)
            val orderId = body.orderId?.trim().orEmpty()
            if (orderId.isEmpty()) {
                return@post call.respondBadRequestMessage("orderId 必填（来自 /api/venue/orders 的 orderId）")
            }
            val result = try {
                session.venueSource().cancelOrder(orderId)
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "取消订单")
            }
            if (!result.success) return@post call.respondActionRejected(result.message)
            call.respond(ApiEnvelope.ok(VenueCancelResult(ok = true, message = result.message)))
        }

        get(VENUE_PAY) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val orderId = call.request.queryParameters["orderId"]?.trim().orEmpty()
            if (orderId.isEmpty()) {
                return@get call.respondBadRequestMessage("orderId 必填（来自 /api/venue/orders 的 orderId）")
            }
            // 地址由 `:data` 拼（`VenueApi.paymentUrl`，站点知识只有那一份）；本层不取数、不发起支付。
            call.respondRedirect(session.venueSource().paymentUrl(orderId))
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

/** `GET /api/venue/captcha` 的 `data`：一张滑块挑战（图按 data URI 原文给，见文件头）。 */
@Serializable
internal data class CaptchaDataDto(
    val captchaId: String,
    /** 背景图 data URI（`data:image/jpeg;base64,…`，原样转发，不重编码）。 */
    val backgroundImage: String,
    /** 滑块图 data URI（带透明通道的 png）。 */
    val sliderImage: String,
    val bgWidth: Int,
    val bgHeight: Int,
    val sliderWidth: Int,
    val sliderHeight: Int,
)

/** `POST /api/venue/book` 的请求体（形状与例子见契约 §5.3.2）。 */
@Serializable
internal data class VenueBookRequest(
    /** 场馆号（`/api/venue/products` 的 `id`）。 */
    val serviceId: Int = 0,
    /** 选中的场地明细（`/api/venue/slots` 的 `stockId` + `areaDetailId`）。 */
    val selections: List<BookingSelectionDto>? = null,
    /** `GET /api/venue/captcha` 给的那个（原样带回）。 */
    val captchaId: String? = null,
    /** 滑块轨迹的 JSON **原文**（`:core` 的 `SliderResult.toJson()`，与 Android/桌面同一协议）。 */
    val sliderTrack: String? = null,
)

/** 一条选中的场地明细：上游 `stockdetail` 的 key（库存 id）与 value（场地明细 id）。 */
@Serializable
internal data class BookingSelectionDto(
    val stockId: Long = 0,
    val areaDetailId: Long = 0,
)

/** `POST /api/venue/book` 成功时的 `data`。 */
@Serializable
internal data class VenueBookResult(
    /** 动作做成了没有。**成功响应里恒为 `true`** —— 失败走 4xx + 信封的 `message`（§4）。 */
    val ok: Boolean,
    /** 上游给的订单号；没给就是 `null`（§4：不编）。 */
    val orderId: String? = null,
    /** 上游给的应付金额（`:data` 的 `BookingResult.price` 原样）。 */
    val price: Double,
    /** `:data` 的 `BookingResult.message` 原样转发（「预订成功」这种，已是中文短句）。 */
    val message: String,
)

/** `POST /api/venue/cancel` 的请求体。 */
@Serializable
internal data class VenueCancelRequest(val orderId: String? = null)

/** `POST /api/venue/cancel` 成功时的 `data`（形状与 `/api/library/action` 同一套）。 */
@Serializable
internal data class VenueCancelResult(val ok: Boolean, val message: String)

/** `GET /api/venue/status` 的 `data`：能力开关（§2）。 */
@Serializable
internal data class VenueStatusData(
    /** true：写端点 `/captcha` `/book` 在本层已落地，滑块由浏览器端的宿主画（见文件头）。 */
    val canBook: Boolean,
    /** true：取消不需要滑块（一次普通 POST）。 */
    val canCancel: Boolean,
    /** 支付页入口站（OAuth 入口，`VenueApi.BROWSER_LOGIN_URL`）。 */
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

/** 一条选中的场地明细 → `:data` 的 `AreaSlot`。 */
private fun BookingSelectionDto.toAreaSlot(serviceId: Int) = AreaSlot(
    areaDetailId = areaDetailId,
    areaName = "",
    stockId = stockId,
    timeSlot = "",
    price = 0.0,
    date = "",
    allCount = 0,
    usingNum = 0,
    surplus = 0,
    serviceid = serviceId.toString(),
)

/** `CaptchaData` → 客户端形状（图与尺寸原样，`id` 改名成 `captchaId`，见 [CaptchaDataDto]）。 */
private fun CaptchaData.toDto() = CaptchaDataDto(
    captchaId = id,
    backgroundImage = backgroundImage,
    sliderImage = sliderImage,
    bgWidth = bgWidth,
    bgHeight = bgHeight,
    sliderWidth = sliderWidth,
    sliderHeight = sliderHeight,
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 场馆取数实例：每次请求现建。 */
internal fun ServeSession.venueSource(): AppVenueSource =
    AppVenueSource(sessionManager.getSite(ServeSession.VENUE_SITE_KEY), canBook = true)

internal const val VENUE_SEGMENT = "venue"
internal const val VENUE_PRODUCTS = "products"
internal const val VENUE_SLOTS = "slots"
internal const val VENUE_ORDERS = "orders"
internal const val VENUE_STATUS = "status"
internal const val VENUE_CAPTCHA = "captcha"
internal const val VENUE_BOOK = "book"
internal const val VENUE_CANCEL = "cancel"
internal const val VENUE_PAY = "pay"

/** 请求体不是端点要的形状时的 400 文案（把合法形状写出来）。 */
private const val VENUE_BOOK_BODY_MESSAGE =
    "请求体需要 JSON：{\"serviceId\":101,\"selections\":[{\"stockId\":9101,\"areaDetailId\":5101}]," +
        "\"captchaId\":\"…\",\"sliderTrack\":\"{…}\"}（见契约 §5.3.2）"
private const val VENUE_CANCEL_BODY_MESSAGE =
    "请求体需要 JSON：{\"orderId\":\"VT20261013001\"}（见契约 §5.3.2）"