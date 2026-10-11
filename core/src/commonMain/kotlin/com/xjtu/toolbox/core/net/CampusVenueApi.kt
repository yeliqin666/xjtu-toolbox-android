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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 体育场馆的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppVenueSource`）。
 *
 * ## 两种形态：campus-api 只读，serve 能下单（契约 §5.3.2）
 *
 * - **campus-api（旧路，默认）**：那个模块自己写着 `readOnly:true`：抢场要解滑块验证码、而且是写操作，
 *   出范围（`/api/venue/status` 的 `note` 就是这么写的）。所以端口里的写方法在这条路上**一律抛**，
 *   而屏据 [canBook]/[canCancel] 把对应的 UI 整个不画（见 `VenueSource` 的 KDoc：点了会失败的按钮
 *   一个都不画）。抛而不是「静默返回假成功」：真被调到就是有人画了不该画的按钮，得响。
 * - **serve（契约 §5.3.2）**：`:server` 把滑块挑战**按值**端出来（`GET /api/venue/captcha`），
 *   浏览器端自己画（`:web` 的 `WebSlideCaptchaHost` —— `:data` 没有 wasm 目标，那份求解器进不了浏览器）、
 *   自己出轨迹；下单 `POST /api/venue/book` 把 `captchaId` + 轨迹原文带回去，取消 `POST /api/venue/cancel`
 *   是一次普通 POST。两个开关（[canBook]/[canCancel]）随两条路径已落地而翻 `true`。
 *   逐字段形状（请求/响应/错误码）在契约 §5.3.2，本类只负责翻译。
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
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : VenueSource {

    /**
     * 能不能在屏上下单（见 [VenueSource.canBook]）—— **两种形态两种答案**。
     *
     * campus-api（旧路）：那个模块自己写着 `readOnly:true`（抢场要解滑块且属写操作）⇒ `false`。
     *
     * serve（契约 §5.3.2）：`/api/venue/captcha` + `/api/venue/book` 已落地，滑块由本端的
     * `WebSlideCaptchaHost` 在浏览器里画（`:data` 没有 wasm 目标，求解器进不了浏览器）⇒ 下单这条路真能
     * 走完，如实翻 `true`（[SERVE_WRITE_ENDPOINTS_READY]）。
     */
    override val canBook: Boolean get() = mode == ApiMode.SERVE && SERVE_WRITE_ENDPOINTS_READY

    /**
     * 能不能取消订单（见 [VenueSource.canCancel]）。
     *
     * serve：`POST /api/venue/cancel` 已落地（一次普通 POST，不需要滑块）⇒ 与 `/api/venue/status`
     * 的 `canCancel:true` 一致；campus-api 的旧路仍然 `false`（那条路没有取消端点）。
     */
    override val canCancel: Boolean get() = mode == ApiMode.SERVE && SERVE_WRITE_ENDPOINTS_READY

    /**
     * 支付页入口站（OAuth 入口）。
     *
     * 两种形态都是空串：浏览器里没有「内置浏览器带着 App 的统一认证 cookie」这回事，而这份地址
     * （`VenueApi.BROWSER_LOGIN_URL`）也只有 `:data` 知道 —— `:core` 不复制一份站点知识。
     * serve 模式下屏上能下成单，但「去支付」那一跳在浏览器里做不到（支付页在 80 端口那台站点上，
     * 会话在 serve 进程里）⇒ 点了不会有动作，见契约 §5.3.2 末尾的缺口说明与 [paymentUrl]。
     */
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

    /**
     * serve 模式（契约 §5.3）：`{venues:[{id,name,address?,iconType?,advanceDay,advanceNum}]}`。
     *
     * `:server` 自己已经把页拉到不足为止了（`VenueSource.venues()` 就是那份数据）⇒ **一次请求**，
     * 不再照旧路那样按 `hasMore` 翻页（旧路的翻页是 campus-api 的投影只有一页大小造成的）。
     * `id` 在这里就是**数字**（旧路是字符串 `"341"`）；≤ 0 跳过该行 —— 与旧路同一个判据。
     */
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

    /**
     * serve 模式（契约 §5.3）：`{serviceId,date,slots:[{areaDetailId,areaName,stockId,timeSlot,price,
     * date,allCount,usingNum,surplus,serviceid,isAvailable}]}`。
     *
     * 四处与旧路不同（都是契约写明的）：参数名是 `serviceId`（旧路是 `serviceid`）、
     * 可订与已占用已经合成一个 `slots[]`（旧路要自己拼 `bookable + locked`）、`areaName` 直接给
     * （旧路读的是 `name`）、以及 **`surplus` 直接用服务端那个值** —— §5.3 把「可订就至少算 1」
     * 那条口径放在 `:data` 里（旧路是拿到 campus-api 的 `surplus` 后自己在客户端重算一遍，
     * 同一件事两处算就会分叉）。
     */
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
                // 与旧路逐字同一个兜底（无细分场地的那一行否则会是个没字的格子）
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

    /**
     * serve 模式（契约 §5.3）：`{page,size,total|null,orders:[…],hasMore}`。
     *
     * 与旧路的三处差别：行里叫 `createdAt`（旧路 `createdDate`）、分页参数是 `size`（旧路 `rows`）、
     * 以及 **`details[]` 里的 `areaName` / `serviceName` 现在有值了**（旧路上游没投影这两项，
     * 屏上那两段是空着不画的）；`total` 仍然可空（上游给不了就是 `null`，不拿这一页的条数冒充）。
     */
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

    /**
     * 支付页 URL。
     *
     * campus-api（旧路）：与从前一样抛 —— 那条路 `canBook = false`，屏上不画支付入口（真被调到就是有人
     * 画了不该画的按钮）。
     *
     * serve：**空串**（不是抛，也不是编一个地址）：支付页在 `:data` 的 `VenueApi.paymentUrl` 里拼
     * （`:core` 不复制那份站点知识），而浏览器里也没有场馆站的会话（会话在 serve 进程里）⇒ 屏上那个
     * 「去支付」确实打不开。这是**已知缺口**，写在契约 §5.3.2 末尾；哪天契约给了支付入口、或把状态里的
     * `browserLoginUrl` 接给客户端，再把它补上。空串而不是抛：那条路是**能到达**的（下单能成），
     * 抛会在点击时炸掉页面。
     */
    override fun paymentUrl(orderId: String): String =
        if (mode == ApiMode.SERVE) "" else readOnly()

    /**
     * `GET /api/venue/captcha?serviceId=`（契约 §5.3.2 第 1 条）：把挑战拿回浏览器里画。
     *
     * 服务端给的是**上游那张图与上游发的验证码号**（`captchaId ← CaptchaData.id`）：`captchaId`
     * 是要连同轨迹一起回传的句柄，本端只负责原样带着。
     */
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

    /**
     * serve 模式下 `prepareOrder` **不发请求**：上游那个 `param` 信封由**服务端**用它那份 `:data`
     * （`VenueApi.prepareOrder`）拼，`:core` 不复制那份站点知识。这里只把「选中了哪几个场地明细」
     * 编码进 [PendingOrder.rawParamJson]（`stockId` + `areaDetailId`，上游 `stockdetail` 那格只用到
     * 这两个数），[submitBooking] 再解回来 —— 「先备好订单、再要验证码」那个两段式流程与两端一致。
     */
    override fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder {
        if (mode != ApiMode.SERVE) return readOnly()
        return PendingOrder(
            AppJson.encodeToString(selections.map { BookingSelection(it.stockId, it.areaDetailId) }),
        )
    }

    /**
     * `POST /api/venue/book`（契约 §5.3.2）：把 [prepareOrder] 编码的选中项、`captchaId` 与**轨迹原文**
     * 一起发出去。轨迹是 [SliderResult.toJson] 的字符串，服务端把它原样交给 `:data`（`yzm` 的拼接、
     * 「验证码有误」时的重试、结果解析都不在客户端也不在服务端重做）。
     *
     * 结果：[BookingResult.success] 只认服务端的 `ok`（`code == 0`）；业务拒绝（409 —— 验证码有误、场地
     * 被抢）与 `:app` 那条路同形：`BookingResult(false, 服务端的中文短句)`，屏上的横幅只认它。
     */
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

    /**
     * `POST /api/venue/cancel`（契约 §5.3.2）：一次普通 POST，不需要滑块。
     *
     * api（旧路）与从前一样抛；serve 把 `ok` 与那句中文短句原样带回 [OrderActionResult]（业务拒绝
     * 也是**结果**，屏上要把它显示出来）。
     */
    override suspend fun cancelOrder(orderId: String): OrderActionResult {
        if (mode != ApiMode.SERVE) return readOnly()
        val body = buildJsonObject { put("orderId", orderId) }.toString()
        val outcome = client.serveAction(baseUrl, VENUE_CANCEL_PATH, body)
        return OrderActionResult(outcome.ok, outcome.message)
    }

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

    /**
     * campus-api 那条路没有这个能力（写方法被调到就是有人画了不该画的按钮）—— 得吭声。
     * serve 模式下这些方法都是真实现，不再走这里。
     */
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

        /**
         * serve 模式的两个写端点（契约 §5.3.2）都已经落地 ⇒ 两个能力开关随它翻 `true`。
         * 留着这个常量是为了让「开关 = 实现」这件事在一处可见：哪天端点被拿回去，翻这一个数就行。
         * （与 `CampusLibraryApi.SERVE_WRITE_ENDPOINTS_READY` 同一个名字、同一个意思。）
         */
        const val SERVE_WRITE_ENDPOINTS_READY = true

        /** 下单（§5.3.2）。 */
        const val VENUE_BOOK_PATH = "/api/venue/book"

        /** 取消订单（§5.3.2）。 */
        const val VENUE_CANCEL_PATH = "/api/venue/cancel"

        /** 收藏的键名沿用 Android 那个（排查时好认）；值形态不同，见类 KDoc。 */
        const val KEY_FAVORITES = "favorite_venue_ids"
    }
}

/**
 * 一次下单要带上的场地明细（serve 模式，[CampusVenueApi.prepareOrder] → [CampusVenueApi.submitBooking]
 * 之间的携带物）：只说「哪一个库存下的哪一条场地明细」—— 上游 `stockdetail` 那个信封只用到这两个数
 * （`:data` 的 `VenueApi.buildBookingParam`），名字/时段/价格…服务端不看，带上只会多一个「两端各说
 * 一遍」的地方。形状与 `POST /api/venue/book` 的 `selections[]` 逐字段相同（契约 §5.3.2）。
 */
@Serializable
internal data class BookingSelection(val stockId: Long, val areaDetailId: Long)
