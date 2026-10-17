package com.xjtu.toolbox.ywtb

import com.sun.net.httpserver.HttpExchange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 假的「一网通办 + 消息收纳那四路」上游。 */
class YwtbFakeUpstream {

    companion object {
        /** 一网通办门户：CAS 回跳落在它身上（带 `ticket=<JWT>`）。 */
        const val HOST = "ywtb.xjtu.edu.cn"

        /** 收纳那四路各自的域名。 */
        const val MESSAGE_HOST = "message-service.xjtu.edu.cn"
        const val TRANSACTION_HOST = "transaction.xjtu.edu.cn"
        const val RESERVATION_HOST = "reservation.xjtu.edu.cn"
        const val BUS_HOST = "xjbus.xjtu.edu.cn"

        /** 四条取数 URL，与 `SchoolInbox` 里那四个常量相同（有断言钉着）。 */
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

    /** 消息那一路：四条样本（见类 KDoc）。 */
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

    /** 业务请求检查：路径与查询串必须与生产代码完全相同，并记下 `x-id-token`。 */
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


    /** 把取数 URL 剪成「路径 + 查询串」。 */
    private fun pathAndQuery(url: String): String = "/" + url.removePrefix("https://").substringAfter('/')

    private val PORTAL_PATH = "/"
    private val MESSAGES_PATH_AND_QUERY = pathAndQuery(MESSAGES_URL)
    private val TODOS_PATH_AND_QUERY = pathAndQuery(TODOS_URL)
    private val BOOKINGS_PATH_AND_QUERY = pathAndQuery(BOOKINGS_URL)
    private val BUS_PATH_AND_QUERY = pathAndQuery(BUS_URL)

}
