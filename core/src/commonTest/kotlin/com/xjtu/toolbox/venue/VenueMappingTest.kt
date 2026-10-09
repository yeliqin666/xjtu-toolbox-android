package com.xjtu.toolbox.venue

import com.xjtu.toolbox.core.net.CampusVenueApi
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.platform.keyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `CampusVenueApi`（Web 端的场馆取数）的**字段映射测试**。
 *
 * 为什么值得写：campus-api 是**白名单投影**，字段名既不是 `:app` 解析的那套、也有几样干脆没有。
 * 光在浏览器里看一眼「有场馆、有时段」看不出这些差别：
 *  - `id`/`areaDetailId`/`stockId` 上游是**字符串**，而 `:app` 的模型是 `Int`/`Long`；
 *  - `advanceday` 变成 `advanceDays`、`advancenum` 变成 `advanceSlots`（`:app` 的兜底 7/8 得跟着走）；
 *  - 订单明细的 `areaName`（上游 `stockdetail.sname`）与 `serviceName`（上游 `service.name`）
 *    **白名单里没有** ⇒ 必须留空、屏上不画那两段（`merchant:"体育中心"` 是商户名，不是场馆名，不能顶替）；
 *  - 订单的 `date`/`startTime`/`endTime` **实测常年是 null** ⇒ 留空；
 *  - campus-api 的 `count` 是**这一页的条数**而不是总数 ⇒ `OrderPage.total` 必须留 null。
 *
 * 键名与形状照抄真机 `tests/fixtures/venue-{products,slots-ok,orders}.json` 的**投影后**形状，
 * 值自己编（不抄任何真实姓名/订单号/手机号）。
 */
class VenueMappingTest {

    /** `/api/venue/products` 第 1 页：两行正常 + 一行没有 id（`:app` 会跳过它）。 */
    private val productsPage1 = """
        {"code":0,"error":null,"data":{"source":"http://202.117.17.144:8080/web/product/productData.html",
          "page":1,"rows":8,"hasMore":true,"rawCount":2,
          "products":[
            {"id":"341","name":"博物馆负一层匹克球场","address":"创新港校区","icon":"icon icon-tennis square x64 text-green",
             "serviceType":1,"advanceDays":3,"advanceSlots":2,"maxPeople":1,"minPeople":1,"status":1,"viewName":null},
            {"id":"42","name":"文体中心羽毛球馆","address":null,"icon":null,
             "serviceType":1,"advanceDays":null,"advanceSlots":null,"maxPeople":4,"minPeople":1,"status":1,"viewName":null},
            {"id":null,"name":"没有 id 的一行","address":null,"icon":null,
             "serviceType":null,"advanceDays":null,"advanceSlots":null,"maxPeople":null,"minPeople":null,"status":null,"viewName":null}
          ]}}
    """.trimIndent()

    /** 第 2 页：一行、`hasMore:false` ⇒ 到此为止（与 `:app` 的「不足一页就停」同义）。 */
    private val productsPage2 = """
        {"code":0,"error":null,"data":{"page":2,"rows":8,"hasMore":false,"rawCount":1,
          "products":[{"id":"43","name":"游泳馆","address":"文体中心东侧","icon":null,
            "serviceType":1,"advanceDays":5,"advanceSlots":1,"maxPeople":8,"minPeople":1,"status":1,"viewName":null}]}}
    """.trimIndent()

    /**
     * `/api/venue/slots`：两行可订 + 一行已占用。
     *
     * 第二行（`allCount=1, usingNum=1`）是**故意**的：campus-api 的 `surplus` 是 `max(all-used,0)=0`，
     * 而 `:app` 的口径是「上游说可订（status==1）就至少算 1 个剩余」⇒ 这里钉住 `:app` 那个口径。
     */
    private val slotsPayload = """
        {"code":0,"error":null,"data":{"source":"http://202.117.17.144:8080/web/product/findOkArea.html",
          "serviceId":42,"date":"2026-10-09",
          "bookable":[
            {"areaDetailId":"4546755","name":"场地1","stockId":"464285","date":"2026-10-09","timeSlot":"18:00-19:00",
             "price":9.999,"status":1,"bookable":true,"allCount":2,"usingNum":0,"surplus":2,"isOver":null},
            {"areaDetailId":"4546756","name":"场地2","stockId":"464285","date":"2026-10-09","timeSlot":"08:00-09:00",
             "price":9.999,"status":1,"bookable":true,"allCount":1,"usingNum":1,"surplus":0,"isOver":null}
          ],
          "locked":[
            {"areaDetailId":"4546757","name":"场地3","stockId":"464286","date":"2026-10-09","timeSlot":"08:00-09:00",
             "price":9.999,"status":0,"bookable":false,"allCount":1,"usingNum":1,"surplus":0,"isOver":1}
          ],
          "bookableCount":2,"lockedCount":1,"lockedError":null}}
    """.trimIndent()

    /**
     * `/api/venue/orders`：一条明细全 null（本账号真实形态）、一条两个时刻都在、一条只有起点。
     *
     * `page`/`rows` 按请求回显 —— campus-api 就是把请求参数原样回显的（它的 `count` 则是**这一页的条数**）。
     */
    private fun ordersPayload(page: String, rows: String) = """
        {"code":0,"error":null,"data":{"source":"http://202.117.17.144:8080/web/yyuser/searchorder.html",
          "page":$page,"rows":$rows,"shape":"array","count":2,
          "orders":[
            {"orderId":"1000000000000001","merchant":"体育中心","createdDate":"2026-05-23 09:11:36",
             "successDate":"2026-05-23 09:13:26","price":9.999,"actualAmount":null,"status":1,"detailCount":1,
             "details":[{"id":"3433373305370492","date":null,"startTime":null,"endTime":null,
               "price":9.999,"status":1,"statusText":null,"serviceId":"42","count":1}]},
            {"orderId":"1000000000000002","merchant":"体育中心","createdDate":"2026-05-22 20:43:00",
             "successDate":null,"price":29.997,"actualAmount":null,"status":0,"detailCount":2,
             "details":[
               {"id":"1","date":"2026-05-24","startTime":"18:00","endTime":"19:00",
                "price":9.999,"status":1,"statusText":"预订成功","serviceId":"42","count":1},
               {"id":"2","date":"2026-05-24","startTime":"19:00","endTime":null,
                "price":9.999,"status":1,"statusText":null,"serviceId":"42","count":1}
             ]}
          ]}}
    """.trimIndent()

    /** 记录每次请求的「路径 + 查询串」，用来钉住「按页要」这件事。 */
    private fun apiFor(
        requests: MutableList<String> = mutableListOf(),
        products: (String) -> String = { if (it.startsWith("page=2")) productsPage2 else productsPage1 },
    ): CampusVenueApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val query = request.url.parameters.entries()
                .joinToString("&") { (k, v) -> "$k=${v.first()}" }
            requests += if (query.isEmpty()) path else "$path?$query"
            val body = when {
                path.endsWith("/api/venue/products") -> products(query)
                path.endsWith("/api/venue/slots") -> slotsPayload
                path.endsWith("/api/venue/orders") -> ordersPayload(
                    page = queryParam(query, "page"),
                    rows = queryParam(query, "rows"),
                )
                else -> error("不该请求 $path")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        return CampusVenueApi(createToolboxClient(engine = engine))
    }

    /** 从记录下来的查询串里取一个参数（没有就给空串）。 */
    private fun queryParam(query: String, key: String): String =
        query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()

    @Test
    fun venueListMapsStringIdsAndAdvanceFields() = runTest {
        val venues = apiFor().venues()
        // 第 1 页 3 行里「没有 id」那行被跳过（`:app` 的 `id <= 0` 判据），第 2 页 1 行接上
        assertEquals(3, venues.size)
        assertEquals(listOf(341, 42, 43), venues.map { it.id })

        val peak = venues[0]
        assertEquals("博物馆负一层匹克球场", peak.name)
        assertEquals("创新港校区", peak.address)
        assertEquals("icon icon-tennis square x64 text-green", peak.iconType)
        // ⚠️ ← advanceday / advancenum（不是 :app 那份 JSON 的键，也不是 0）
        assertEquals(3, peak.advanceDay)
        assertEquals(2, peak.advanceNum)

        // 上游没给就落到 :app 那两个兜底（7 天 / 8 段），不是 0
        assertEquals(7, venues[1].advanceDay)
        assertEquals(8, venues[1].advanceNum)
        // address/icon 缺失 ⇒ null（屏上那一行不画）
        assertNull(venues[1].address)
        assertNull(venues[1].iconType)
    }

    @Test
    fun venueListFollowsPagingUntilAShortPage() = runTest {
        val requests = mutableListOf<String>()
        apiFor(requests = requests).venues()
        // 与 `:app` 同一个页大小（8）与同一个「满页就再要一页」判据 ⇒ 两页两次请求
        assertEquals(
            listOf("/api/venue/products?page=1&rows=8", "/api/venue/products?page=2&rows=8"),
            requests,
        )
    }

    @Test
    fun slotsMergeBookableAndLockedAndKeepTheAppSurplusRule() = runTest {
        val slots = apiFor().slots(serviceId = 42, date = "2026-10-09")
        // 可订 2 行 + 已占用 1 行，按 (时段, 场地名) 排序 —— 只拿可订那一半就分不出「满了」和「没这个时段」
        assertEquals(3, slots.size)
        assertEquals(listOf("08:00-09:00", "08:00-09:00", "18:00-19:00"), slots.map { it.timeSlot })
        assertEquals(listOf("场地2", "场地3", "场地1"), slots.map { it.areaName })

        val early = slots[0]
        assertEquals(4546756L, early.areaDetailId)
        assertEquals(464285L, early.stockId)
        assertEquals(9.999, early.price, 0.0)
        assertEquals("2026-10-09", early.date)
        assertEquals("42", early.serviceid)
        assertEquals(1, early.allCount)
        assertEquals(1, early.usingNum)
        // ⚠️ campus-api 对这一行给的 surplus 是 0（max(all-used,0)），这里按 `:app` 的口径给 1：
        // 上游说可订（status==1）就至少算一个剩余，否则 Web 会把 App 上可订的格子画成「已满」
        assertEquals(1, early.surplus)
        assertTrue(early.isAvailable)

        // 不可订那行（findLockArea 来的）一律 0 剩余 ⇒ 格子置灰
        assertEquals(0, slots[1].surplus)
        assertFalse(slots[1].isAvailable)
    }

    @Test
    fun orderFieldsComeFromTheWhitelistAndMissingOnesStayEmpty() = runTest {
        val page = apiFor().orders(page = 1, pageSize = 20)
        assertEquals(2, page.orders.size)

        val first = page.orders[0]
        assertEquals("1000000000000001", first.orderId)
        assertEquals(1, first.status)
        assertEquals("预订成功", first.statusText)
        assertEquals("2026-05-23 09:11:36", first.createdAt)
        assertEquals(9.999, first.price, 0.0)

        val detail = first.details.single()
        // 上游 date/startTime/endTime 都是 null ⇒ 空串，屏按空白过滤 ⇒ 那一段不画（不编一个日期出来）
        assertEquals("", detail.date)
        assertEquals("", detail.timeSlot)
        // ⚠️ campus-api 的白名单里没有场地名与场馆名 ⇒ 留空
        assertEquals("", detail.areaName)
        assertEquals("", detail.serviceName)
        assertEquals("42", detail.serviceId)
        // 于是订单卡标题落到 :app 本来就有的兜底（不是拿 merchant「体育中心」冒充场馆名）
        assertEquals("", first.venueName)
    }

    @Test
    fun orderTimeSlotJoinsStartAndEndWhenBothArePresent() = runTest {
        val second = apiFor().orders(page = 1, pageSize = 20).orders[1]
        assertEquals("2026-05-24", second.details[0].date)
        // 两个都有 ⇒ 拼成与 AreaSlot.timeSlot 同一个形状；只有一个 ⇒ 就给那一个
        assertEquals("18:00-19:00", second.details[0].timeSlot)
        assertEquals("19:00", second.details[1].timeSlot)
        assertEquals(0, second.status)
        // status == 0 才是「待支付」——模型口径与 :app 一致
        assertTrue(second.canPay)
    }

    @Test
    fun orderPageTotalStaysNullBecauseCountIsJustThisPage() = runTest {
        // campus-api 的 `count` 是这一页的条数（不是服务端总数）⇒ total 留 null，hasMore 用「满页」判据
        val page = apiFor().orders(page = 1, pageSize = 1)
        assertNull(page.total)
        assertTrue(page.hasMore)
        assertEquals(1, page.page)
        assertEquals(1, page.pageSize)
    }

    @Test
    fun webIsReadOnly() = runTest {
        val api = apiFor()
        assertFalse(api.canBook)
        assertFalse(api.canCancel)
        // 支付/预订/取消/验证码四条写路径一律抛（屏据 canBook/canCancel 根本不画那些按钮）
        assertFailsWith<RuntimeException> { api.paymentUrl("1000000000000001") }
        assertFailsWith<RuntimeException> { api.captcha(42) }
        assertFailsWith<RuntimeException> { api.prepareOrder(42, emptyList()) }
        assertFailsWith<RuntimeException> { api.cancelOrder("1000000000000001") }
    }

    @Test
    fun favoritesRoundTripInLocalStorage() = runTest {
        val api = apiFor()
        // jvm 上的 `keyValueStore` 是**整个 JVM 一份**的内存实现 ⇒ 先清干净，测试之间不互相污染
        keyValueStore("venue_favorites").clear()
        assertEquals(emptySet(), api.favorites())
        assertTrue(api.toggleFavorite(42))
        assertTrue(api.toggleFavorite(341))
        assertEquals(setOf(42, 341), api.favorites())
        assertFalse(api.toggleFavorite(42))
        assertEquals(setOf(341), api.favorites())
    }
}
