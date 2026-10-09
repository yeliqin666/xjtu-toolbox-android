package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
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
import com.xjtu.toolbox.venue.VenueSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject

/**
 * 体育场馆的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppVenueSource`）。
 *
 * ## 只读（[canBook] = [canCancel] = false）
 *
 * campus-api 的场馆模块自己写着 `readOnly:true`：抢场要解滑块验证码、而且是写操作，出范围
 *（`/api/venue/status` 的 `note` 就是这么写的）。所以 `:core` 端口里的那四个写方法在这里**一律抛**，
 * 而屏据 [canBook]/[canCancel] 把对应的 UI 整个不画（见 `VenueSource` 的 KDoc：点了会失败的按钮
 * 一个都不画）。抛而不是「静默返回假成功」：真被调到就是有人画了不该画的按钮，得响。
 *
 * ## 逐字段映射（2026-10-08 核对 campus-api 的 `modules/venue.js` + 真机夹具
 * `tests/fixtures/venue-*.json`；左边是 `:core` 的模型，右边是 campus-api 的键）
 *
 * 场馆 `GET /api/venue/products?page=&rows=` → `data{source,page,rows,hasMore,rawCount,products[]}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `Venue.id` | `products[].id` | ⚠️ 上游是**字符串**（`"341"`），`:app` 的 `Venue.id` 是 `Int` ⇒ 转；转不出或 ≤ 0 就跳过该行（`:app` 同一个判据） |
 * | `Venue.name` | `products[].name` | ← 上游 `name` |
 * | `Venue.address` | `products[].address` | ← 上游 `address` |
 * | `Venue.iconType` | `products[].icon` | ← 上游 `icon`（形如 `"icon icon-tennis square x64 text-green"`；屏上没用它画图标，`:app` 一直用同一个 Place 图标） |
 * | `Venue.advanceDay` | `products[].advanceDays` | ← 上游 `advanceday`（可提前几天）。`:app` 的兜底是「≤0 或没有就给 7」 |
 * | `Venue.advanceNum` | `products[].advanceSlots` | ← 上游 `advancenum`（一次能订几段）。`:app` 的兜底是 8 |
 * | —— | `serviceType` `maxPeople` `minPeople` `status` `viewName` | campus-api 投影了但这屏不用（`:app` 的模型里没有这些字段）⇒ 丢掉 |
 *
 * 时段 `GET /api/venue/slots?serviceid=&date=` → `data{source,serviceId,date,bookable[],locked[],...}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `AreaSlot.areaDetailId` | `bookable[]/locked[].areaDetailId` | ⚠️ 字符串（`"4546755"`）⇒ 转 `Long`；`:app` 是 `readInt(item,"id").toLong()` |
 * | `AreaSlot.areaName` | `...name` | ← 上游 `sname`（campus-api 兜过 `name`）。空白时给 `"预订"` —— 与 `:app` 逐字同一个兜底（无细分场地的场馆那一行否则会是个没字的格子） |
 * | `AreaSlot.stockId` | `...stockId` | ⚠️ 字符串 ⇒ 转 `Long` |
 * | `AreaSlot.timeSlot` | `...timeSlot` | ← 上游 `stock.time_no`（`"08:00-09:00"`） |
 * | `AreaSlot.price` | `...price` | ← 上游 `stock.price`。⚠️ 实测**有 0 的**（夹具首行就是 0）⇒ 照实给 0，不拿别的数冒充 |
 * | `AreaSlot.date` | `...date`（campus-api 回显请求里的日期） | 空则用请求的 [date]（`:app` 也是拿请求的日期填的） |
 * | `AreaSlot.allCount` / `usingNum` | `...allCount` / `...usingNum` | ← 上游 `stock.all_count` / `stock.using_num` |
 * | `AreaSlot.surplus` | **不用 `...surplus`** | ⚠️ 口径按 `:app`：上游说可订（`status == 1`）时 `(allCount - usingNum).coerceAtLeast(1)`，否则 0。campus-api 的 `surplus` 是 `max(all-used,0)`，在 `all == used` 时会给 0、而 `:app` 会显示成可订 —— 两端对同一条上游数据的读法必须一致（Web 订不了，但「哪些格子可选」是用户在两端都会看的同一件事）。`bookable` 与 `status == 1` 同义，也不另取 |
 * | `AreaSlot.serviceid` | —— | 就是请求里的 `serviceid`（`:app` 也这么填） |
 * | —— | `status` `isOver` `lockedCount` `lockedError` | 屏不用（`status` 只用来算 `surplus`；`locked` 那一路的失败 campus-api 已经吞掉了，`:app` 也是 runCatching 吞） |
 *
 * 订单 `GET /api/venue/orders?page=&rows=` → `data{source,page,rows,shape,count,orders[]}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `OrderInfo.orderId` | `orders[].orderId` | ← 上游 `orderid` |
 * | `OrderInfo.status` | `orders[].status` | ← 上游 `status`（0 预订中 / 1 预订成功 / 2 预订取消） |
 * | `OrderInfo.createdAt` | `orders[].createdDate` | ← 上游 `createdate` |
 * | `OrderInfo.price` | `orders[].price` | ← 上游 `price` |
 * | `OrderDetail.date` | `orders[].details[].date` | ← 上游 `s_date`。⚠️ **本账号常年是 null** ⇒ 留空串（屏按空白过滤，不画那一段） |
 * | `OrderDetail.timeSlot` | `orders[].details[].startTime` + `endTime` | ← 上游 `starttime` / `endtime`。两个都有就给 `"18:00-19:00"`（与 `AreaSlot.timeSlot` 同一个形状），只有一个就给那一个，都没就留空。⚠️ 实测也常是 null |
 * | `OrderDetail.areaName` | —— | **上游没有**（campus-api 的白名单里没有 `stockdetail.sname`，`:app` 是解析内嵌的 `stockdetail` 拿的）⇒ 留空，屏上那一行不画。不写「场地1」这种编出来的值 |
 * | `OrderDetail.serviceName` | —— | **上游没有**（`:app` 从内嵌的 `service.name` 拿）⇒ 留空。于是订单卡的标题落到 `:app` 本来就有的兜底「体育场馆订单」，详情里「场馆：」那一行不画 |
 * | `OrderDetail.price` | `orders[].details[].price` | ← 上游 `price` |
 * | `OrderDetail.serviceId` | `orders[].details[].serviceId` | ← 上游 `serviceid`（字符串 `"42"`） |
 * | `OrderPage.page` / `pageSize` | `data.page` / `data.rows` | campus-api 把请求参数回显出来 |
 * | `OrderPage.total` | —— | ⚠️ **留 null**：campus-api 的 `count` 是**这一页的条数**（不是服务端总数）⇒ 不拿它冒充总数。`:app` 拿不到总数时也是 null |
 * | `OrderPage.hasMore` | —— | 就按 `:app` 在「没有总数」时的兜底：这一页条数 `>= pageSize` 就算还有下一页（下一页空了自然停） |
 * | —— | `merchant` `successDate` `actualAmount` `detailCount` `details[].status/statusText/count/id` | 屏不用（`:app` 的模型里没有这些字段）⇒ 丢掉。⚠️ 其中 `merchant`（`"体育中心"`）**不要**拿去当 `serviceName` 使：那是商户名，不是场馆名 |
 *
 * ## Web 端的收藏
 *
 * 浏览器里存 `localStorage`（`:core` 的 `keyValueStore("venue_favorites")`，键名沿用 Android 那个
 * `favorite_venue_ids`，值写成逗号分隔的字符串 —— `localStorage` 没有集合这一档）。
 * 与 Android 的 `SharedPreferences` 是两个独立的世界，各自记各自的收藏。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusVenueApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : VenueSource {

    /** campus-api 的场馆模块只读（见类 KDoc）⇒ 下单与取消都不画。 */
    override val canBook: Boolean get() = false

    /** 同上。 */
    override val canCancel: Boolean get() = false

    /** Web 端没有「内置浏览器带着 App 的统一认证 cookie」这回事 ⇒ 空串（而且 canBook=false 时走不到支付）。 */
    override val browserLoginUrl: String get() = ""

    override suspend fun venues(): List<Venue> {
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
                // 空白时给「预订」：与 `:app` 逐字同一个兜底（无细分场地的那一行否则会是个没字的格子）
                areaName = row["name"].safeString().trim().ifBlank { "预订" },
                stockId = row["stockId"].safeString().trim().toLongOrNull() ?: 0L,
                timeSlot = row["timeSlot"].safeString(),
                price = row["price"].safeDouble(),
                date = row["date"].safeString().takeIf { it.isNotBlank() } ?: date,
                allCount = allCount,
                usingNum = usingNum,
                // 见类 KDoc：口径按 `:app`（可订就至少算 1 个剩余），不用 campus-api 的 surplus
                surplus = if (status == 1) (allCount - usingNum).coerceAtLeast(1) else 0,
                serviceid = serviceId.toString(),
            )
        }.sortedWith(compareBy({ it.timeSlot }, { it.areaName }))
    }

    override suspend fun orders(page: Int, pageSize: Int): OrderPage {
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

    /** 只读端不实现支付（`canBook=false` ⇒ 屏上不画支付入口）。真被调到就是有人画了不该画的按钮。 */
    override fun paymentUrl(orderId: String): String = readOnly()

    override suspend fun captcha(serviceId: Int): CaptchaData = readOnly()

    override fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder = readOnly()

    override suspend fun submitBooking(
        serviceId: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String,
    ): BookingResult = readOnly()

    override suspend fun cancelOrder(orderId: String): OrderActionResult = readOnly()

    // ─── 收藏（localStorage）───────────────────────────────────

    override suspend fun favorites(): Set<Int> =
        favoritesStore.getString(KEY_FAVORITES)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    override suspend fun toggleFavorite(venueId: Int): Boolean {
        val current = favorites().toMutableSet()
        val isFavorite = if (current.remove(venueId)) false else { current.add(venueId); true }
        favoritesStore.putString(KEY_FAVORITES, current.joinToString(","))
        return isFavorite
    }

    private val favoritesStore by lazy { keyValueStore("venue_favorites") }

    private fun readOnly(): Nothing = throw RuntimeException("本端只读：campus-api 不提供场馆预订/取消")

    /** 拆 `{code,data}` 信封；`code!=0` 按体测/校历那套报法显式失败（含 `appCode:need-login`）。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 场馆返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // 场馆会话抖动（`appCode:need-login`）也走这一支：Web 端没有 CAS 会话可重登，
            // 照实报错，不抛 SessionExpiredFailure（那会让屏幕白白退页，与体测/通知/校园卡同口径）。
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

        /** 收藏的键名沿用 Android 那个（排查时好认）；值形态不同，见类 KDoc。 */
        const val KEY_FAVORITES = "favorite_venue_ids"
    }
}
