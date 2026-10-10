package com.xjtu.toolbox.ywtb

import com.sun.net.httpserver.HttpExchange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 假的「一网通办 + 消息收纳那四路」上游 —— 桌面端第 12 条真数据路由（消息收纳 / 统一收件箱）。
 *
 * ## 它扮演哪五个域名
 *
 * 收纳在真实站点上是**一个站点（一网通办）的四路取数**：四路都只认 CAS 回跳里那颗
 * `x-id-token`，但**各自的域名不同** ⇒ 一个夹具扮五个 host（`FakeCampusProxy` 按 host 分派，
 * 五个都进 `HTTPS_HOSTS`，一枚证书五个 SAN）：
 *
 * ```
 * GET  https://ywtb.xjtu.edu.cn/?path=…&ticket=<JWT>       → 门户页 200（登录链的终点）
 * GET  https://message-service.xjtu.edu.cn/…getAppMessageList/new?pageIndex=0&pageSize=30
 * GET  https://transaction.xjtu.edu.cn/…center-list/getToDoList?pageIndex=1&pageSize=30
 * GET  https://reservation.xjtu.edu.cn/api/myreservation/my?state=1&useState=0&current=1&size=20
 * GET  https://xjbus.xjtu.edu.cn/api/school/bus/user/pageReservationUsers?status=1&current=1&size=20
 * ```
 *
 * 四条取数的 URL 与 `:data` 的 `SchoolInbox` 里那些**逐字相同**（测试里有一条断言钉着）：
 * 换域名、改分页参数都会让断言红，而不是「夹具照答、口径悄悄漂了」。
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是所有站点共用的一台 CAS，`FakeCampusProxy` 把它分派给
 * `LibraryFakeUpstream`（它完整扮演了登录页 / 表单 POST / 签 ticket / TGC）。一网通办这条链
 * 与别处的**唯一**差别是：CAS 回跳时那颗 ticket 是**一枚 JWT**（`YwtbLogin` 从它的 payload 里
 * 读出 `idToken`），所以那个夹具按 `service` 认得出一网通办、照真实形状签一枚 JWT ——
 * 见 `LibraryFakeUpstream` 里的 `ticketFor`。
 *
 * ## 夹具摆的东西：四条取数各摆一组样本
 *
 * - **消息**（`data` 是数组）：四条 —— ① 正文末尾带落款（来源取落款）且 `mobileUrl` 非空；
 *   ② `appId` = 事务中心那个常量（**必须被滤掉**：它在事务中心那一路里已经收过了）；
 *   ③ 落款是「图书馆预约系统」（**必须被滤掉**：座位那类消息活几分钟，见 `InboxRules.isShortLived`）；
 *   ④ 没有落款且 `title` 为空 ⇒ 来源取 `appName`、标题回落成来源。
 * - **事务中心待办**（`data.items`）：两条 —— ① 有 `taskId`、`appName`、`mHandleUrl`（办理链接成路由）；
 *   ② 没有 `taskId`（id 回落成 `todo:标题@addTime`）、`timeOut` 为 `true`（正文尾上「已超时」）、
 *   `appName`/`custom1` 都空 ⇒ 来源回落成「事务中心」、标题取 `transactionName`。
 * - **预约中心 / 校车**：只报数量（两个接口的条目字段没有样本，生产代码也只读 `data.total`）。
 *   预约中心回 3 ⇒ 出一条「预约中心 有 3 个待使用的预约」；校车回 0 ⇒ **一条都不该出**
 *   （把 `total <= 0` 那条分支钉住）。
 *
 * ⚠️ 时间戳按**当下**算（`yyyy-MM-dd HH:mm:ss`，北京时区）：收纳有条 30 天的保留期
 * （`InboxRules.KEEP_MS`），写死日期的话过一个多月这些条目都会被 `InboxRules.merge` 清掉，
 * 「真数据」那条断言与那张证据图会凭白变空 —— 那种红看不出是环境还是代码引起的。
 *
 * ## 认不出的路径要响亮
 *
 * 五个 host 上不认识的路径 → 404，body 里带着完整 URL。URL 一漂就该一眼看出来。
 */
class YwtbFakeUpstream {

    companion object {
        /** 一网通办门户：CAS 回跳落在它身上（带 `ticket=<JWT>`）。 */
        const val HOST = "ywtb.xjtu.edu.cn"

        /** 收纳那四路各自的域名。 */
        const val MESSAGE_HOST = "message-service.xjtu.edu.cn"
        const val TRANSACTION_HOST = "transaction.xjtu.edu.cn"
        const val RESERVATION_HOST = "reservation.xjtu.edu.cn"
        const val BUS_HOST = "xjbus.xjtu.edu.cn"

        /**
         * 四条取数 URL —— 与 `:data` 的 `SchoolInbox` 里那四个常量**逐字相同**（测试有断言钉着）。
         * 分页/状态参数也是生产代码真发的那些：夹具要验的是「客户端发的就是这一条」。
         */
        const val MESSAGES_URL =
            "https://$MESSAGE_HOST/center/api/v1/instantMessage/getAppMessageList/new?pageIndex=0&pageSize=30"
        const val TODOS_URL =
            "https://$TRANSACTION_HOST/ttc/api/ttc/center-list/getToDoList?pageIndex=1&pageSize=30"
        const val BOOKINGS_URL =
            "https://$RESERVATION_HOST/api/myreservation/my?state=1&useState=0&current=1&size=20"
        const val BUS_URL =
            "https://$BUS_HOST/api/school/bus/user/pageReservationUsers?status=1&current=1&size=20"

        /** 事务中心推来的消息和它的待办是同一件事 —— 与生产代码里那个常量同一个值。 */
        const val TRANSACTION_APP_ID = "b125b6f0e46911ebc909e55a42ec966e"

        /** CAS 回跳那枚 JWT ticket 里 `idToken` 的值 —— 也是四路请求上 `x-id-token` 该带的值。 */
        const val ID_TOKEN = "fake-ywtb-id-token-1"

        // ── 消息样本 ──

        const val MESSAGE_SIGNED_ID = "m1"
        const val MESSAGE_SIGNED_TITLE = "电费余额不足"
        const val MESSAGE_SIGNED_SOURCE = "公寓用电管理系统"
        const val MESSAGE_SIGNED_LINK = "https://ywtb.xjtu.edu.cn/main.html#/notice?id=m1"

        /** 事务中心推来的那条：`appId` 是那个常量 ⇒ 不收。 */
        const val MESSAGE_TRANSACTION_ID = "m2"

        /** 落款是「图书馆预约系统」⇒ 不收（座位那类消息活几分钟）。 */
        const val MESSAGE_SEAT_ID = "m3"

        /** 没有落款、`title` 也是空的 ⇒ 来源取 `appName`、标题回落成来源。 */
        const val MESSAGE_BARE_ID = "m4"
        const val MESSAGE_BARE_APP_NAME = "研究生院"
        const val MESSAGE_BARE_LINK = "https://gs.xjtu.edu.cn/x"

        // ── 事务中心待办样本 ──

        const val TODO_ID = "t1"
        const val TODO_TITLE = "在校本科生电子成绩单申请"
        const val TODO_SOURCE = "电子凭证"
        const val TODO_NODE = "成绩单申请"
        const val TODO_LINK = "https://dzpz.xjtu.edu.cn/m/handle?id=1"

        /** 没有 `taskId` 的那条：id 回落成 `todo:标题@addTime`，`timeOut=true` ⇒ 正文带「已超时」。 */
        const val TODO_BARE_TITLE = "宿舍电费待缴"
        const val TODO_BARE_SOURCE = "事务中心"
        const val TODO_BARE_NODE = "宿舍电费缴纳"
        const val TODO_BARE_LINK = "https://ywtb.xjtu.edu.cn/main.html#/pay"

        // ── 预约 / 校车样本（只报数量）──

        const val BOOKING_RESERVATION_TOTAL = 3

        /** 校车那边一份待使用预约都没有 ⇒ 「校车 有 0 个…」那条不该出现。 */
        const val BOOKING_BUS_TOTAL = 0

        /** 门户页的标题（`XJTULogin` 只要求它**不像**统一认证登录页）。 */
        const val PORTAL_TITLE = "师生综合服务大厅"

        private const val SECOND = 1000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 门户会话 cookie（让回跳那一跳像真的：真站点在这里种下自己的会话）。 */
        const val PORTAL_SESSION_VALUE = "SESSION-ywtb-1"

        /** 相对当下 [offsetMs] 毫秒的「yyyy-MM-dd HH:mm:ss」（北京时区）—— 见类 KDoc 里那条保留期。 */
        fun beijing(offsetMs: Long, now: Long = System.currentTimeMillis()): String =
            TIME.format(Instant.ofEpochMilli(now + offsetMs).atZone(ZONE).toLocalDateTime())
    }

    // ── 服务器可变状态：动作打进来时计数/记录，好让断言「这一枪真打到了哪里」──

    /** 四路各被打了几次（每条取数应当恰好一次）。 */
    val messageCalls = AtomicInteger()
    val todoCalls = AtomicInteger()
    val bookingCalls = AtomicInteger()
    val busCalls = AtomicInteger()

    /** CAS 回跳落在门户上几次（带 ticket 的那一枪）。 */
    val portalLandings = AtomicInteger()

    /** 最近一次业务请求带的 `x-id-token`（应当是 CAS 那枚 JWT 里的 [ID_TOKEN]）。 */
    val lastIdToken = AtomicReference<String?>(null)

    /** 最近一次业务请求的完整 URL（断言客户端发的是那一条，而不是「夹具碰巧也认」）。 */
    val lastBusinessUrl = AtomicReference<String?>(null)

    // ── 样本：页面与 JSON 原文（消费者共用这一份）──────────────────

    /** 登录链的终点：门户首页（**不能**长得像统一认证登录页）。 */
    val portalPage = """
        <html><head><title>$PORTAL_TITLE</title></head><body>
          <div id="app">一网通办 · 消息与待办</div>
        </body></html>
    """.trimIndent()

    /**
     * 消息那一路：四条样本（见类 KDoc）。`data` 是**数组** —— 这是 `SchoolInbox.parseMessages`
     * 认的第一种形状（另一种是 `data.list`，那条回落留在 `:data:jvmTest` 的解析用例里钉住）。
     */
    fun messagesJson(now: Long = System.currentTimeMillis()): String = """
        {"code":0,"data":[
          {"id":"$MESSAGE_SIGNED_ID","title":"$MESSAGE_SIGNED_TITLE","appId":"ycz7pmawmfpksprtio309vow",
           "appName":"消息平台","editTime":"${beijing(-3 * HOUR, now)}",
           "content":"<p>您当前的电费余额不足，请及时缴费。 ($MESSAGE_SIGNED_SOURCE)</p>",
           "mobileUrl":"$MESSAGE_SIGNED_LINK","url":""},
          {"id":"$MESSAGE_TRANSACTION_ID","title":"待办催办","appId":"$TRANSACTION_APP_ID",
           "appName":"事务中心","editTime":"${beijing(-2 * HOUR, now)}",
           "content":"<p>事务中心推来的消息和它的待办是同一件事。</p>"},
          {"id":"$MESSAGE_SEAT_ID","title":"预约超时","appId":"ycz7pmawmfpksprtio309vow",
           "appName":"消息平台","editTime":"${beijing(-HOUR, now)}",
           "content":"<p>您的预约已经超时，即将在五分钟后释放。 (图书馆预约系统)</p>"},
          {"id":"$MESSAGE_BARE_ID","title":"","appId":"ycz7pmawmfpksprtio309vow",
           "appName":"$MESSAGE_BARE_APP_NAME","editTime":"${beijing(-30 * MINUTE, now)}",
           "content":"<p>没有落款的正文，来源取 appName。</p>","url":"$MESSAGE_BARE_LINK"}
        ]}
    """.trimIndent()

    /** 事务中心待办那一路：两条样本（`data.items` 形状）。 */
    fun todosJson(now: Long = System.currentTimeMillis()): String = """
        {"code":0,"data":{"pageIndex":2,"items":[
          {"nodeName":"$TODO_NODE","addTime":"${beijing(-HOUR, now)}","title":"$TODO_TITLE",
           "appName":"$TODO_SOURCE","custom1":"师生可信电子凭证门户","taskId":"$TODO_ID",
           "mHandleUrl":"$TODO_LINK","timeOut":"false"},
          {"nodeName":"$TODO_BARE_NODE","addTime":"${beijing(-5 * HOUR, now)}","transactionName":"$TODO_BARE_TITLE",
           "custom1":"","timeOut":"true","viewUrl":"$TODO_BARE_LINK"}
        ]}}
    """.trimIndent()

    /** 预约中心：只报数量（3 条待使用）。 */
    private fun bookingsJson() = """{"code":0,"data":{"total":$BOOKING_RESERVATION_TOTAL}}"""

    /** 校车：0 条 ⇒ 一条待办都不该出。 */
    private fun busJson() = """{"code":0,"data":{"total":$BOOKING_BUS_TOTAL}}"""

    // ── 路由 ─────────────────────────────────────────────────────

    /** 门户（[HOST]）：只认带 `ticket=` 的登录回跳。 */
    fun handlePortal(exchange: HttpExchange) {
        val query = exchange.requestURI.query.orEmpty()
        if (exchange.requestURI.path != PORTAL_PATH || "ticket=" !in query) {
            notFound(exchange)
            return
        }
        portalLandings.incrementAndGet()
        exchange.responseHeaders.add("Set-Cookie", "SESSION=$PORTAL_SESSION_VALUE; Path=/")
        respond(exchange, 200, "text/html; charset=utf-8", portalPage.toByteArray())
    }

    /** 消息那一路。 */
    fun handleMessages(exchange: HttpExchange) {
        val expected = MESSAGES_PATH_AND_QUERY
        if (!matches(exchange, expected)) return
        messageCalls.incrementAndGet()
        respondJson(exchange, messagesJson())
    }

    /** 事务中心待办那一路。 */
    fun handleTodos(exchange: HttpExchange) {
        if (!matches(exchange, TODOS_PATH_AND_QUERY)) return
        todoCalls.incrementAndGet()
        respondJson(exchange, todosJson())
    }

    /** 预约中心那一档。 */
    fun handleBookings(exchange: HttpExchange) {
        if (!matches(exchange, BOOKINGS_PATH_AND_QUERY)) return
        bookingCalls.incrementAndGet()
        respondJson(exchange, bookingsJson())
    }

    /** 校车那一档。 */
    fun handleBus(exchange: HttpExchange) {
        if (!matches(exchange, BUS_PATH_AND_QUERY)) return
        busCalls.incrementAndGet()
        respondJson(exchange, busJson())
    }

    /**
     * 业务请求的形体检查：**路径与查询串都要与生产代码发的逐字相同**（多一个少一个参数都 404），
     * 并把 `x-id-token` 记下来（四路都只认它 —— 少了它就等于没登录）。
     */
    private fun matches(exchange: HttpExchange, expectedPathAndQuery: String): Boolean {
        val actual = exchange.requestURI.path + "?" + exchange.requestURI.query.orEmpty()
        lastBusinessUrl.set("https://" + exchange.requestHeaders.getFirst("Host") + actual)
        lastIdToken.set(exchange.requestHeaders.getFirst("x-id-token"))
        if (actual != expectedPathAndQuery) {
            notFound(exchange)
            return false
        }
        return true
    }

    // ── 响应小工具 ────────────────────────────────────────────────

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange) {
        val url = exchange.requestURI.toString()
        respond(exchange, 404, "text/plain; charset=utf-8", "no such ywtb path: $url".toByteArray())
    }


    /** 把取数 URL 剪成「路径 + 查询串」（夹具按这一串逐字比对，见 [matches]）。 */
    private fun pathAndQuery(url: String): String = "/" + url.removePrefix("https://").substringAfter('/')

    private val PORTAL_PATH = "/"
    private val MESSAGES_PATH_AND_QUERY = pathAndQuery(MESSAGES_URL)
    private val TODOS_PATH_AND_QUERY = pathAndQuery(TODOS_URL)
    private val BOOKINGS_PATH_AND_QUERY = pathAndQuery(BOOKINGS_URL)
    private val BUS_PATH_AND_QUERY = pathAndQuery(BUS_URL)

}
