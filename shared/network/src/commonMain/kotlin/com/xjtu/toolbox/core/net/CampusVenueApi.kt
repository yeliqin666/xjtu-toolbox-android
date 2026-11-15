package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.encodeUrlComponent
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.venue.AreaSlot
import com.xjtu.toolbox.venue.BookingResult
import com.xjtu.toolbox.venue.CaptchaData
import com.xjtu.toolbox.venue.OrderActionResult
import com.xjtu.toolbox.venue.OrderDetail
import com.xjtu.toolbox.venue.OrderInfo
import com.xjtu.toolbox.venue.OrderPage
import com.xjtu.toolbox.venue.PendingOrder
import com.xjtu.toolbox.venue.Venue
import com.xjtu.toolbox.venue.VenueFavorites
import com.xjtu.toolbox.venue.VenueSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 体育场馆的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `AppVenueSource`）。 */
class CampusVenueApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : VenueSource {

    /** 能不能在屏上下单（见 [VenueSource.canBook]）—— 两种形态两种答案。 */
    override val canBook: Boolean get() = mode == ApiMode.SERVE && SERVE_WRITE_ENDPOINTS_READY

    /** 能不能取消订单（见 [VenueSource.canCancel]）。 */
    override val canCancel: Boolean get() = mode == ApiMode.SERVE && SERVE_WRITE_ENDPOINTS_READY

    /** 支付前要先去这个站点的登录页过一遍（`:app` 的内置浏览器带着 App 的统一认证 cookie，一般免输密码）。 */
    override val browserLoginUrl: String get() = ""

    override suspend fun venues(): List<Venue> {
        if (mode == ApiMode.SERVE) return serveVenues()
        val venues = mutableListOf<Venue>()
        var page = 1
        while (page <= MAX_VENUE_PAGES) {
            val data = getData(
                "/api/venue/products",
                "page" to page.toString(),
                "rows" to VENUE_PAGE_SIZE.toString(),
            )
            val rows = data.arr("products").orEmpty()
            if (rows.isEmpty()) break
            rows.forEach { element ->
                val row = element as? JsonObject ?: return@forEach
                // ⚠️ id 上游是字符串；≤ 0 或转不出按 `:app` 的判据跳过这一行
                val id = row["id"].safeString().trim().toIntOrNull() ?: return@forEach
                if (id <= 0) return@forEach
                venues += Venue(
                    id = id,
                    name = row["name"].safeString().trim(),
                    address = row["address"].safeString().trim().takeIf { it.isNotBlank() },
                    iconType = row["icon"].safeString().trim().takeIf { it.isNotBlank() },
                    advanceDay = row["advanceDays"].safeInt().takeIf { it > 0 } ?: 7,
                    advanceNum = row["advanceSlots"].safeInt().takeIf { it > 0 } ?: 8,
                )
            }
            // hasMore 是 campus-api 按「这一页满不满」算的，与 `:app` 的 `array.size < VENUE_PAGE_SIZE` 同一个判据
            if (!data["hasMore"].safeBoolean()) break
            page++
        }
        return venues
    }

    override suspend fun slots(serviceId: Int, date: String): List<AreaSlot> {
        if (mode == ApiMode.SERVE) return serveSlots(serviceId, date)
        val data = getData(
            "/api/venue/slots",
            "serviceid" to serviceId.toString(),
            "date" to date,
        )
        // 可订 + 已占用合并：只拿可订那一半，UI 就分不出「满了」和「没这个时段」（与 `:app` 同一条理由）
        val rows = data.arr("bookable").orEmpty() + data.arr("locked").orEmpty()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val status = row["status"].safeInt()
            val allCount = row["allCount"].safeInt()
            val usingNum = row["usingNum"].safeInt()
            AreaSlot(
                areaDetailId = row["areaDetailId"].safeString().trim().toLongOrNull() ?: 0L,
                // 空白时给「预订」：无细分场地的那一行否则是个没字的格子
                areaName = row["name"].safeString().trim().ifBlank { "预订" },
                stockId = row["stockId"].safeString().trim().toLongOrNull() ?: 0L,
                timeSlot = row["timeSlot"].safeString(),
                price = row["price"].safeDouble(),
                date = row["date"].safeString().takeIf { it.isNotBlank() } ?: date,
                allCount = allCount,
                usingNum = usingNum,
                surplus = if (status == 1) (allCount - usingNum).coerceAtLeast(1) else 0,
                serviceid = serviceId.toString(),
            )
        }.sortedWith(compareBy({ it.timeSlot }, { it.areaName }))
    }

    override suspend fun orders(page: Int, pageSize: Int): OrderPage {
        if (mode == ApiMode.SERVE) return serveOrders(page, pageSize)
        val data = getData(
            "/api/venue/orders",
            "page" to page.toString(),
            "rows" to pageSize.toString(),
        )
        val orders = data.arr("orders").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            OrderInfo(
                orderId = row["orderId"].safeString(),
                status = row["status"].safeInt(),
                createdAt = row["createdDate"].safeString(),
                price = row["price"].safeDouble(),
                details = row.arr("details").orEmpty().mapNotNull { detailElement ->
                    val detail = detailElement as? JsonObject ?: return@mapNotNull null
                    val start = detail["startTime"].safeString()
                    val end = detail["endTime"].safeString()
                    OrderDetail(
                        date = detail["date"].safeString(),
                        timeSlot = when {
                            start.isNotBlank() && end.isNotBlank() -> "$start-$end"
                            start.isNotBlank() -> start
                            else -> end
                        },
                        // 上游没有这两样（见类 KDoc）⇒ 留空，屏上那两段不画
                        areaName = "",
                        price = detail["price"].safeDouble(),
                        serviceId = detail["serviceId"].safeString(),
                        serviceName = "",
                    )
                },
            )
        }
        return OrderPage(
            orders = orders,
            page = data["page"].safeInt(page),
            pageSize = data["rows"].safeInt(pageSize),
            // campus-api 的 count 是这一页的条数，不是总数 ⇒ total 留 null（不冒充）
            total = null,
            hasMore = orders.size >= pageSize,
        )
    }

    // ─── serve 模式（契约 §5.3）────────────────────────────────────────────

    /** serve 模式（契约 §5.3） */
    private suspend fun serveVenues(): List<Venue> {
        val data = getData("/api/venue/products")
        return data.arr("venues").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row["id"].safeInt()
            if (id <= 0) return@mapNotNull null
            Venue(
                id = id,
                name = row["name"].safeString().trim(),
                address = row["address"].safeString().trim().takeIf { it.isNotBlank() },
                iconType = row["iconType"].safeString().trim().takeIf { it.isNotBlank() },
                advanceDay = row["advanceDay"].safeInt().takeIf { it > 0 } ?: 7,
                advanceNum = row["advanceNum"].safeInt().takeIf { it > 0 } ?: 8,
            )
        }
    }

    /** serve 模式（契约 §5.3） */
    private suspend fun serveSlots(serviceId: Int, date: String): List<AreaSlot> {
        val data = getData(
            "/api/venue/slots",
            "serviceId" to serviceId.toString(),
            "date" to date,
        )
        return data.arr("slots").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            AreaSlot(
                areaDetailId = row["areaDetailId"].safeString().trim().toLongOrNull() ?: 0L,
                // 空白时给「预订」（同上）
                areaName = row["areaName"].safeString().trim().ifBlank { "预订" },
                stockId = row["stockId"].safeString().trim().toLongOrNull() ?: 0L,
                timeSlot = row["timeSlot"].safeString(),
                price = row["price"].safeDouble(),
                date = row["date"].safeString().takeIf { it.isNotBlank() } ?: date,
                allCount = row["allCount"].safeInt(),
                usingNum = row["usingNum"].safeInt(),
                surplus = row["surplus"].safeInt(),
                serviceid = row["serviceid"].safeString().ifBlank { serviceId.toString() },
            )
        }.sortedWith(compareBy({ it.timeSlot }, { it.areaName }))
    }

    /** serve 模式（契约 §5.3）：`{page,size,total|null,orders:[…],hasMore}`。 */
    private suspend fun serveOrders(page: Int, pageSize: Int): OrderPage {
        val data = getData(
            "/api/venue/orders",
            "page" to page.toString(),
            "size" to pageSize.toString(),
        )
        val orders = data.arr("orders").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            OrderInfo(
                orderId = row["orderId"].safeString(),
                status = row["status"].safeInt(),
                createdAt = row["createdAt"].safeString(),
                price = row["price"].safeDouble(),
                details = row.arr("details").orEmpty().mapNotNull { detailElement ->
                    val detail = detailElement as? JsonObject ?: return@mapNotNull null
                    OrderDetail(
                        date = detail["date"].safeString(),
                        timeSlot = detail["timeSlot"].safeString(),
                        areaName = detail["areaName"].safeString(),
                        price = detail["price"].safeDouble(),
                        serviceId = detail["serviceId"].safeString(),
                        serviceName = detail["serviceName"].safeString(),
                    )
                },
            )
        }
        return OrderPage(
            orders = orders,
            page = data["page"].safeInt(page),
            pageSize = data["size"].safeInt(pageSize),
            // §5.3：上游给不了总数就是 null（不拿这一页的条数冒充）
            total = if (data["total"].isNull) null else data["total"].safeInt(),
            hasMore = data["hasMore"].safeBoolean(),
        )
    }

    // ─── 写路径（serve 模式；契约 §5.3.2）────────────────────────────

    /** 支付页地址（屏上那个「去支付」把它交给宿主打开，见 `VenueScreen` 的 `pay`）。 */
    override fun paymentUrl(orderId: String): String = if (mode == ApiMode.SERVE) {
        "$baseUrl$VENUE_PAY_PATH?orderId=${encodeUrlComponent(orderId)}"
    } else {
        readOnly()
    }

    /** `GET /api/venue/captcha?serviceId=`（契约 §5.3.2 第 1 条）：把挑战拿回浏览器里画。 */
    override suspend fun captcha(serviceId: Int): CaptchaData {
        if (mode != ApiMode.SERVE) return readOnly()
        val data = getData("/api/venue/captcha", "serviceId" to serviceId.toString())
        return CaptchaData(
            id = data["captchaId"].safeString(),
            backgroundImage = data["backgroundImage"].safeString(),
            sliderImage = data["sliderImage"].safeString(),
            bgWidth = data["bgWidth"].safeInt(),
            bgHeight = data["bgHeight"].safeInt(),
            sliderWidth = data["sliderWidth"].safeInt(),
            sliderHeight = data["sliderHeight"].safeInt(),
        )
    }

    /** serve 模式下 `prepareOrder` 不发请求：上游 `param` 信封由服务端拼。 */
    override fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder {
        if (mode != ApiMode.SERVE) return readOnly()
        return PendingOrder(
            AppJson.encodeToString(selections.map { BookingSelection(it.stockId, it.areaDetailId) }),
        )
    }

    /** `POST /api/venue/book`（契约 §5.3.2） */
    override suspend fun submitBooking(
        serviceId: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String,
    ): BookingResult {
        if (mode != ApiMode.SERVE) return readOnly()
        val selections = runCatching {
            AppJson.decodeFromString<List<BookingSelection>>(pendingOrder.rawParamJson)
        }.getOrElse { error("serve 模式的下单必须先过 prepareOrder（选中项带不过来）") }
        val body = buildJsonObject {
            put("serviceId", serviceId)
            putJsonArray("selections") {
                selections.forEach { selection ->
                    addJsonObject {
                        put("stockId", selection.stockId)
                        put("areaDetailId", selection.areaDetailId)
                    }
                }
            }
            put("captchaId", captchaId)
            put("sliderTrack", sliderTrackJson)
        }.toString()
        val outcome = client.serveAction(baseUrl, VENUE_BOOK_PATH, body)
        return BookingResult(
            success = outcome.ok,
            orderId = outcome.data?.get("orderId").safeString().takeIf { it.isNotBlank() },
            price = outcome.data?.get("price").safeDouble() ?: 0.0,
            message = outcome.message,
        )
    }

    /** `POST /api/venue/cancel`（契约 §5.3.2）：一次普通 POST，不需要滑块。 */
    override suspend fun cancelOrder(orderId: String): OrderActionResult {
        if (mode != ApiMode.SERVE) return readOnly()
        val body = buildJsonObject { put("orderId", orderId) }.toString()
        val outcome = client.serveAction(baseUrl, VENUE_CANCEL_PATH, body)
        return OrderActionResult(outcome.ok, outcome.message)
    }

    // ─── 收藏（走共享落盘）───────────────────────────────────

    /**
     * 已收藏的场馆 id。
     *
     * 落盘交给 [VenueFavorites]（`:shared:model`，`:app` 的 `AppVenueSource` 用的也是它）：同一个 store
     * （`venue_favorites`）、同一个键（`favorite_venue_ids`），值类型统一是 `getStringSet/putStringSet`。
     * 以前这里自己用 `getString/split(',')` 读，同一个键被两种值类型写过 ⇒ 跨端导入档案时
     * `ClassCastException`。
     */
    override suspend fun favorites(): Set<Int> = VenueFavorites.all()

    override suspend fun toggleFavorite(venueId: Int): Boolean = VenueFavorites.toggle(venueId)

    /** campus-api 那条路没有这个能力（写方法被调到就是有人画了不该画的按钮）—— 得吭声。 */
    private fun readOnly(): Nothing = throw RuntimeException("本端只读：campus-api 不提供场馆预订/取消")

    /** 拆 `{code,data}` 信封；`code!=0` 按体测/校历那套报法显式失败（含 `appCode:need-login`）。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("场馆", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 场馆返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login 也照实报错：Web 端没有 CAS 会话可重登，抛 SessionExpiredFailure 只会让屏白白退页。
            error("campus-api 场馆失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 场馆返回缺少 data：${text.take(120)}")
    }

    private companion object {
        /** 与 `:app` 的 `VenueApi.VENUE_PAGE_SIZE` 同一个页大小（请求数也就一样）。 */
        const val VENUE_PAGE_SIZE = 8

        /** 与 `:app` 的 `while (page <= 20)` 同一个上限。 */
        const val MAX_VENUE_PAGES = 20

        /** serve 模式的两个写端点（契约 §5.3.2）都已经落地 ⇒ 两个能力开关随它翻 `true`。 */
        const val SERVE_WRITE_ENDPOINTS_READY = true

        /** 下单（§5.3.2）。 */
        const val VENUE_BOOK_PATH = "/api/venue/book"

        /** 取消订单（§5.3.2）。 */
        const val VENUE_CANCEL_PATH = "/api/venue/cancel"

        /** 支付那一跳（§5.3.2 第 6 条）：serve 的代跳端点（302 → 80 端口那台站点上的支付页地址）。 */
        const val VENUE_PAY_PATH = "/api/venue/pay"
        // 收藏的键名/落盘形态统一由 `:shared:model` 的 `VenueFavorites` 管，这里不再自带一份。
    }
}

/** 一次下单要带上的场地明细（只带服务端用到的两个 id）。 */
@Serializable
internal data class BookingSelection(val stockId: Long, val areaDetailId: Long)
