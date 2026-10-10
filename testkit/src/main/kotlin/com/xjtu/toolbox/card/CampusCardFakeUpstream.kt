package com.xjtu.toolbox.card

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「校园卡（ncard）」上游 —— 扮演 `ncard.xjtu.edu.cn`（校园卡这条真数据路由的学校那一侧）。
 *
 * ## 它扮演到什么程度
 *
 * 完整那条链，一环不省：
 *
 * ```
 * GET  <HOME_URL>                         →（不带 ticket）302 到统一认证（service = HOME_URL）
 * POST http://login.xjtu.edu.cn/cas/login →（凭据表单，由 LibraryFakeUpstream 扮演 CAS）
 * GET  <HOME_URL>&ticket=ST-n             → 200 首页 —— 登录链的最后一跳（`postLogin` 在这里接管）
 * POST <TOKEN_URL>                        → JWT：`{"access_token":…}`（Basic Auth + 表单里带 ticket）
 * GET  <USER_URL>                         → 用户资料 `data.cardAccount / name / sno`
 * GET  <CARD_URL>                         → 卡面     `data.card[0].elec_accamt / unsettle_amount / barflag / …`
 * GET  <TURNOVER_URL>?size=&current=&…    → 流水一页 `data.total / data.records[]`
 * ```
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是**所有站点共用**的一台 CAS，`FakeCampusProxy` 把它分派给
 * [LibraryFakeUpstream]（它完整扮演了登录页 / 表单 POST / 签 ticket / TGC）。这个夹具只负责
 * `ncard.xjtu.edu.cn` 那一半：**CAS 回跳之后**的事。
 *
 * 真站点那条链还夹着 `org.xjtu.edu.cn` 开放平台的两跳，这里**直接 302 到统一认证**：夹具要演的是
 * 「CAS 回跳之后客户端拿到的那个 URL 与页面」（`CampusCardLogin.postLogin` 只判「ticket 在不在、
 * 落地是不是 ncard」），中间那两跳对被测代码没有可观察差别。这条省略写在这里，
 * 免得被读成「真站点就两跳」。
 *
 * ## 契约：逐字段对着 `:data` 里的解析函数写
 *
 * - 站点是 **https**，所以 `FakeCampusProxy` 的 `HTTPS_HOSTS` 里有它（走 CONNECT + 自签证书）。
 * - 用户资料 / 卡面 / 流水的键，就是 `CampusCardLogin.fetchUserInfo` 与 `CampusCardApi` 真正读的那些；
 *   `code` 给的是**数字** 200（`CampusCardContract.businessCode` 的 intValue 那一支）。
 * - `cardname` 两侧**带空格**、`unsettle_amount` 是**字符串** `"5000"` —— 解析的 `trim()` 与
 *   `requireLong` 的字符串分支因此都有人钉着。
 * - 七条流水把 `signedAmountCents` 的每条分支各摆一条（表在下方的常量区）：`typeFrom` 权威判据、
 *   关键词兜底、`toAccount` 兜底、服务端已经给了负数、以及 `toMerchant` 是空串时退回 `resume`。
 *
 * ## 认不出的路径要响亮
 *
 * 路径不认识 → 404，**body 里带着完整 URL**；认识的路径但形体不对（该 POST 的用了 GET、
 * 少了 ticket / 少了分页参数 / 没带 bearer）→ 400，body 里带着实际收到的样子。URL 一漂就该一眼看出来。
 */
class CampusCardFakeUpstream {

    companion object {
        const val HOST = "ncard.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        /** 登录入口 —— 与 `CampusCardLogin.LOGIN_URL` 必须逐字相同（有断言钉着）。 */
        const val HOME_URL = "$ORIGIN/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5"

        /** ticket 换 JWT（`CampusCardLogin.TOKEN_URL`）。 */
        const val TOKEN_URL = "$ORIGIN/berserker-auth/oauth/token"

        /** 用户资料（`CampusCardLogin.USER_URL`）。 */
        const val USER_URL = "$ORIGIN/berserker-base/user?synAccessSource=h5"

        /** 卡面（`CampusCardApi.getCardInfo`）。 */
        const val CARD_URL = "$ORIGIN/berserker-app/ykt/tsm/queryCard?synAccessSource=h5"

        /** 流水的路径（`CampusCardApi.getTransactions` 自己拼分页查询串）。 */
        const val TURNOVER_PATH = "/berserker-search/search/personal/turnover"

        // ── 会话与用户资料样本（都是编出来的值）──

        /** 校园卡账号（不是学号），`CampusCardSession.localToken["card_account"]`/`CardInfo.account` 用的就是它。 */
        const val CARD_ACCOUNT = "255001"

        const val USER_NAME = "示例甲"

        /** 学号就是 CAS 那组假账号里的同一个（同一个人，与 `JwxtFakeUpstream.STUDENT_ID` 同源）。 */
        const val STUDENT_NO = LibraryFakeUpstream.USERNAME

        /** ticket 换回来的 JWT。业务请求的 `Synjones-Auth: bearer <它>` 就是这一串。 */
        const val ACCESS_TOKEN = "ncard-fake-jwt-7f3a91c2"

        // ── 卡面样本 ──

        /** 电子钱包余额（分）。与流水里最新那条（[TX_LUNCH_BALANCE_CENTS]）**一致** —— 同一个人同一张卡。 */
        const val BALANCE_CENTS = 334806L

        /** 待入账（分）。这一格夹具给的是**字符串**，钉 `requireLong` 的字符串分支。 */
        const val PENDING_CENTS = 5000L

        const val CARD_TYPE = "校园卡（学生）"

        /** `expdate` 上游给 8 位数字，解析要换成 `yyyy-MM-dd`。 */
        const val EXPIRE_DATE_RAW = "20301231"
        const val EXPIRE_DATE = "2030-12-31"

        // ── 流水样本：七条，把 `signedAmountCents` 的每条分支各摆一条 ──
        //
        // | 行 | 时间 | 分 | 方向判据 | 钉的是什么 |
        // |---|---|---|---|---|
        // | [TX_LUNCH] | 10-10 12:30 | −1850 | `typeFrom="2"` | 权威判据说支出 ⇒ 负（`消费` 关键词也在，但用不上）|
        // | [TX_BREAKFAST] | 10-10 08:12 | −350 | `typeFrom="2"` | 同上；`toMerchant` 两侧带空格 ⇒ `trim()` |
        // | [TX_RECHARGE] | 10-09 08:05 | +20000 | `typeFrom="1"` | 权威判据说收入 ⇒ 正（即使 `toAccount=0`）|
        // | [TX_QRCODE] | 10-08 18:45 | −350 | 无 `typeFrom`，关键词 | `二维码支付`/`qrcode-payment` 那条支出通道；`toMerchant` 是**空串** ⇒ 退回 `resume` |
        // | [TX_UNKNOWN_OUT] | 10-07 09:05 | −1200 | 无 `typeFrom`、两份关键词都不认 | `toAccount≠0` ⇒ 钱转出去了 ⇒ 负；`toMerchant`/`resume` 都空 ⇒ 「未知商户」|
        // | [TX_UNKNOWN_IN] | 10-06 20:00 | +1000 | 同上 | `toAccount=0` ⇒ 钱还在自己账上 ⇒ 正 |
        // | [TX_REFUND] | 10-05 11:20 | −1500 | `tranamt` 本身是负的 | **原样返回**：服务端已经定过方向，不再按 `typeFrom="1"` 翻成正数 |
        //
        // 余额一列是自洽的（相邻两条之差 = 中间那条的金额），最新那条与 [BALANCE_CENTS] 相等。

        const val TX_LUNCH = "2026-10-10 12:30:05"
        const val TX_LUNCH_AMOUNT_CENTS = 1850L
        const val TX_LUNCH_BALANCE_CENTS = 334806L

        const val TX_BREAKFAST = "2026-10-10 08:12:00"
        const val TX_BREAKFAST_AMOUNT_CENTS = 350L
        const val TX_BREAKFAST_BALANCE_CENTS = 336656L

        const val TX_RECHARGE = "2026-10-09 08:05:00"
        const val TX_RECHARGE_AMOUNT_CENTS = 20000L
        const val TX_RECHARGE_BALANCE_CENTS = 337006L

        const val TX_QRCODE = "2026-10-08 18:45:10"
        const val TX_QRCODE_AMOUNT_CENTS = 350L
        const val TX_QRCODE_BALANCE_CENTS = 317006L

        const val TX_UNKNOWN_OUT = "2026-10-07 09:05:00"
        const val TX_UNKNOWN_OUT_AMOUNT_CENTS = 1200L
        const val TX_UNKNOWN_OUT_BALANCE_CENTS = 317356L

        const val TX_UNKNOWN_IN = "2026-10-06 20:00:00"
        const val TX_UNKNOWN_IN_AMOUNT_CENTS = 1000L
        const val TX_UNKNOWN_IN_BALANCE_CENTS = 318556L

        const val TX_REFUND = "2026-10-05 11:20:00"
        const val TX_REFUND_AMOUNT_CENTS = -1500L
        const val TX_REFUND_BALANCE_CENTS = 317556L

        /** 商户名（都是编出来的名字，与任何真人无关）。 */
        const val MERCHANT_CANTEEN = "学苑食堂三楼"
        const val MERCHANT_DUMPLING = "珍念水饺"
        const val MERCHANT_UNKNOWN = "未知商户"
        const val MERCHANT_CARD_CENTER = "校园卡中心"
        const val MERCHANT_REFUND = "教材科"

        /** 服务端总数 = 七条（夹具只有一页）。 */
        const val TOTAL = 7
    }

    // ── 服务器可变状态：动作打进来时计数/记录，好让断言「这一枪真打到了哪里」──

    /** 没带 ticket 时把人交给统一认证的次数。 */
    val casRedirects = AtomicInteger()

    /** CAS 回跳（带 ticket）落在本站的次数 —— 用户级「登录真的走完了」。 */
    val ticketLandings = AtomicInteger()

    /** ticket 换 JWT 打了几次。 */
    val tokenCalls = AtomicInteger()

    /** 用户资料 / 卡面 / 流水各打了几次。 */
    val userInfoCalls = AtomicInteger()
    val cardCalls = AtomicInteger()
    val turnoverCalls = AtomicInteger()

    /** 最近一次 ticket 兑换的表单原文（断言确实把 ticket 当凭据发了出去）。 */
    @Volatile
    var lastTokenForm: String? = null
        private set

    /** 最近一次流水请求的查询串（断言分页与日期真的发出去了）。 */
    @Volatile
    var lastTurnoverQuery: String? = null
        private set

    // ── 样本：响应原文（消费者共用这一份）──────────────────────────

    /** 登录链最后一跳（CAS 回跳之后）落到的首页。**不能**长得像 CAS 登录页。 */
    val homePage = """
        <html><head><title>校园卡</title></head><body>
          <div id="app">校园卡服务</div>
        </body></html>
    """.trimIndent()

    /** ticket 换 JWT：`CampusCardLogin.exchangeTicketForToken` 只读 `access_token`。 */
    val tokenJson = """{"access_token":"$ACCESS_TOKEN","token_type":"bearer","expires_in":7200}"""

    /** 用户资料：三个字段都是 `requiredText` 的判据（缺一个整个登录就算失败）。 */
    val userJson = """
        {"code":200,"message":"成功","data":{
          "cardAccount":"$CARD_ACCOUNT","name":"$USER_NAME","sno":"$STUDENT_NO"
        }}
    """.trimIndent()

    /**
     * 卡面：`card` 是**数组**，解析取第 0 个。
     *
     * `cardname` 两侧刻意带空格（`trim()` 的判据）、`unsettle_amount` 刻意是字符串
     * （`requireLong` 的字符串分支）、`barflag`/`freezeflag` 是数字 0（不是布尔）。
     */
    val cardJson = """
        {"code":200,"message":"成功","data":{"card":[
          {"elec_accamt":$BALANCE_CENTS,"unsettle_amount":"$PENDING_CENTS",
           "barflag":0,"freezeflag":0,"expdate":"$EXPIRE_DATE_RAW","cardname":" $CARD_TYPE ",
           "accname":"$USER_NAME"}
        ]}}
    """.trimIndent()

    /** 流水一页：行序就是上游的序（入账时间倒序）。 */
    val turnoverJson = """
        {"code":200,"message":"成功","data":{"total":$TOTAL,"records":[
          {"jndatetimeStr":"$TX_LUNCH","tranamt":$TX_LUNCH_AMOUNT_CENTS,"icon":"consume",
           "turnoverType":"消费","resume":"$MERCHANT_CANTEEN-电子账户消费","toMerchant":"$MERCHANT_CANTEEN",
           "typeFrom":"2","toAccount":88001,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_LUNCH_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_BREAKFAST","tranamt":$TX_BREAKFAST_AMOUNT_CENTS,"icon":"consume",
           "turnoverType":"消费","resume":"$MERCHANT_CANTEEN-电子账户消费","toMerchant":" $MERCHANT_CANTEEN ",
           "typeFrom":"2","toAccount":88001,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_BREAKFAST_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_RECHARGE","tranamt":$TX_RECHARGE_AMOUNT_CENTS,"icon":"recharge",
           "turnoverType":"充值","resume":"充值-支付宝转账","toMerchant":"",
           "typeFrom":"1","toAccount":0,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_RECHARGE_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_QRCODE","tranamt":$TX_QRCODE_AMOUNT_CENTS,"icon":"qrCode-payment",
           "turnoverType":"二维码支付","resume":"$MERCHANT_DUMPLING-电子账户消费","toMerchant":"",
           "toAccount":88002,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_QRCODE_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_UNKNOWN_OUT","tranamt":$TX_UNKNOWN_OUT_AMOUNT_CENTS,"icon":"unknown-channel",
           "turnoverType":"校内转账","resume":"","toAccount":66001,"fromAccount":"$CARD_ACCOUNT",
           "cardBalance":$TX_UNKNOWN_OUT_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_UNKNOWN_IN","tranamt":$TX_UNKNOWN_IN_AMOUNT_CENTS,"icon":"unknown-channel",
           "turnoverType":"校内转账","resume":"","toMerchant":"$MERCHANT_CARD_CENTER",
           "toAccount":0,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_UNKNOWN_IN_BALANCE_CENTS},
          {"jndatetimeStr":"$TX_REFUND","tranamt":$TX_REFUND_AMOUNT_CENTS,"icon":"refund",
           "turnoverType":"退款","resume":"$MERCHANT_REFUND-退款","toMerchant":"$MERCHANT_REFUND",
           "typeFrom":"1","toAccount":88003,"fromAccount":"$CARD_ACCOUNT","cardBalance":$TX_REFUND_BALANCE_CENTS}
        ]}}
    """.trimIndent()

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when (path) {
            REDIRECT_PATH -> handleEntry(exchange, query)
            TOKEN_PATH -> requirePostForm(exchange) { handleToken(exchange, it) }
            USER_PATH -> requireBearer(exchange) {
                userInfoCalls.incrementAndGet()
                respondJson(exchange, userJson)
            }
            CARD_PATH -> requireBearer(exchange) {
                cardCalls.incrementAndGet()
                respondJson(exchange, cardJson)
            }
            TURNOVER_PATH -> requireBearer(exchange) { handleTurnover(exchange, query) }
            else -> notFound(exchange)
        }
    }

    /**
     * 登录入口页：带 ticket ⇒ 给首页（ticket 由 `postLogin` 从落地 URL 里读）；否则 302 到统一认证
     * （`service` 就是本页地址，真站点也是这么把票据交回来的）。
     */
    private fun handleEntry(exchange: HttpExchange, query: String) {
        if ("ticket=" in query) {
            ticketLandings.incrementAndGet()
            respondHtml(exchange, homePage)
            return
        }
        casRedirects.incrementAndGet()
        redirect(exchange, "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(HOME_URL)}")
    }

    /** ticket 换 JWT：`Authorization: Basic …` + 表单里 `username`/`password` 都是那个 ticket。 */
    private fun handleToken(exchange: HttpExchange, form: String) {
        lastTokenForm = form
        val basic = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        if (!basic.startsWith("Basic ")) {
            badRequest(exchange, "token 兑换少了 Authorization: Basic，收到：$basic")
            return
        }
        val fields = formFields(form)
        val ticket = fields["username"]
        if (ticket.isNullOrEmpty() || fields["password"] != ticket) {
            badRequest(exchange, "token 兑换的 username/password 该是同一个 ticket，收到：$form")
            return
        }
        if (fields["grant_type"] != "password") {
            badRequest(exchange, "token 兑换的 grant_type 该是 password，收到：$form")
            return
        }
        tokenCalls.incrementAndGet()
        respondJson(exchange, tokenJson)
    }

    /**
     * 流水：分页参数与日期区间一个不能少（夹具只认这套查询），`current > 1` 回空表 ——
     * 夹具只有一页，翻页翻过头要响亮地失败，而不是编几行出来。
     */
    private fun handleTurnover(exchange: HttpExchange, query: String) {
        lastTurnoverQuery = query
        val params = formFields(query)
        val missing = listOf("size", "current", "timeFrom", "timeTo", "synAccessSource")
            .filter { params[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            badRequest(exchange, "流水查询少了 ${missing.joinToString("/")}，收到：$query")
            return
        }
        if (params["synAccessSource"] != "h5") {
            badRequest(exchange, "流水查询的 synAccessSource 该是 h5，收到：$query")
            return
        }
        val current = params.getValue("current").toIntOrNull()
        if (current == null || current <= 0) {
            badRequest(exchange, "流水查询的 current 该是正整数，收到：$query")
            return
        }
        turnoverCalls.incrementAndGet()
        respondJson(exchange, if (current == 1) turnoverJson else emptyPageJson)
    }

    /** 夹具只有一页：翻到第 2 页起是空表（总数照旧），`allTransactions` 会据此响亮地失败。 */
    private val emptyPageJson = """{"code":200,"message":"成功","data":{"total":$TOTAL,"records":[]}}"""

    // ── 响应的形体检查 ────────────────────────────────────────────

    /** 业务请求必须带 `Synjones-Auth: bearer <夹具发的那个 JWT>`（`CampusCardSession` 注入的就是它）。 */
    private inline fun requireBearer(exchange: HttpExchange, respond: () -> Unit) {
        val header = exchange.requestHeaders.getFirst("Synjones-Auth").orEmpty()
        if (!header.equals("bearer $ACCESS_TOKEN", ignoreCase = true)) {
            badRequest(exchange, "少了正确的 Synjones-Auth（收到：$header）")
            return
        }
        respond()
    }

    /** POST 端点：必须真带表单（GET 打过来就 400，免得「方法漂了照样通过」）。 */
    private inline fun requirePostForm(exchange: HttpExchange, respond: (String) -> Unit) {
        val body = readBody(exchange)
        if (exchange.requestMethod != "POST") {
            badRequest(exchange, "expected POST, got ${exchange.requestMethod} ${exchange.requestURI}")
            return
        }
        respond(body)
    }

    // ── 响应小工具 ────────────────────────────────────────────────

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange) {
        val url = exchange.requestURI.toString()
        respond(exchange, 404, "text/plain; charset=utf-8", "no such ncard path: $url".toByteArray())
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun readBody(exchange: HttpExchange): String =
        runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")

    /** `k=v&k=v` → 字段表（查询串与表单原文同一个形状）。 */
    private fun formFields(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { part ->
            val value = part.substringAfter('=', "")
            part.substringBefore('=') to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // 路由表与 URL 常量一处定义（免得两边各自漂移）。
    private val REDIRECT_PATH = HOME_URL.removePrefix(ORIGIN).substringBefore('?')
    private val TOKEN_PATH = TOKEN_URL.removePrefix(ORIGIN)
    private val USER_PATH = USER_URL.removePrefix(ORIGIN).substringBefore('?')
    private val CARD_PATH = CARD_URL.removePrefix(ORIGIN).substringBefore('?')
}
