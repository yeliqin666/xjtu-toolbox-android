package com.xjtu.toolbox.dormpower

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的**宿舍电费系统**（`ssn.xjtu.edu.cn/cems`）＋它登录链上游的开放平台那一跳
 * （`org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759`）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`SsnLogin`](com.xjtu.toolbox.auth.SsnLogin) + [`SsnSession`](com.xjtu.toolbox.auth.SsnSession)
 * 一起，把**搬进 `:data` 之后**的登录、缴费页入口与取数原样演一遍（第 17 条真数据路由）：
 *
 * ```
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759&redirectUri=http://ssn.xjtu.edu.cn/cems/index/mobile/pay&…
 *       → 302 http://login.xjtu.edu.cn/cas/login?service=<上面的 authorize 地址>（登录页在 LibraryFakeUpstream 那半台 CAS 里）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 authorize?…&ticket=ST-n
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?…&ticket=ST-n → 302 http://ssn.xjtu.edu.cn/cems/index/mobile/pay?code=SSN-fake-1
 * GET  http://ssn.xjtu.edu.cn/cems/index/mobile/pay?code=… → 200（HTML 里内嵌 cid；`freshPayUrl` 的网络拦截器把这跳记下来后剥掉 query 再放行）
 * GET  https://ssn.xjtu.edu.cn/cems/mobile/addr/list?cid=…        → {"code":0,"data":[房间]}
 * GET  https://ssn.xjtu.edu.cn/cems/mobile/meterAccount/electricity?roomId=… → {"code":0,"data":12.5}
 * ```
 *
 * ## 为什么一个夹具扮两个域名
 *
 * 与 `VenueFakeUpstream` 同一条规矩（「一个 host 只能由一个夹具答」）：`org.xjtu.edu.cn` 与
 * `ssn.xjtu.edu.cn` 属于**同一条路由的同一条登录链**，所以放在一个文件里、两个入口
 * （[handleOauth] / [handle]）。`org.xjtu.edu.cn` 同时被场馆（appId=1659）、加餐券（authorizesw）
 * 用着 —— `FakeCampusProxy` 按路径 / `appId` 分派，本夹具只答 `appId=1759` 那一支。
 *
 * ## 登录链被省略的那一跳
 *
 * 真站点上 OAuth 回调回来的是 `?code=` 换站点会话；这里**直接让 CAS 签的 `?ticket=` 回到
 * authorize 页，再由 authorize 以 `?code=` 跳到缴费页**。对被测代码没有可观察差别
 *（`SsnLogin.postLogin` 只判「最终落在缴费页且页面里有 `cid`」；`freshPayUrl` 只认「路径是
 * `/cems/index/mobile/pay` 且带 `code` 的那一跳」）。这条省略写在这里，免得被读成「真站点就这两跳」。
 *
 * ## 写路径刻意没有夹具
 *
 * 绑定 / 解绑 / 充值都发生在缴费页里（浏览器端的事），本夹具没有那些端点：真被打到就是 404。
 *
 * ## 业务端点不验会话
 *
 * 夹具要钉的是**解析与请求形状**：`/cems/mobile/…` 直接给数据（真站点会先判会话，那时走的是
 * `SiteSession.executeWithReAuth` 那条路，已由图书馆/教务/校园卡那几条钉子守着了）。
 *
 * ## 夹具值全是编造的
 *
 * `cid` 是 32 位十六进制（满足真站点 `[0-9a-fA-F]{16,64}` 的形状）、宿舍名「示例园区/1栋/101」
 *（不许出现真人/真楼宇）、电量 `12.5` 度 —— 与 `LibraryFakeUpstream.USERNAME` 一致只是为了
 *「同一个假账号贯穿几条链」。
 */
class DormPowerFakeUpstream {

    companion object {
        /** 业务站点（`SsnLogin.BASE_URL` 的 host）。 */
        const val HOST = "ssn.xjtu.edu.cn"

        /** 开放平台那一跳（`SsnLogin.SSN_OAUTH_URL` 的 host；与场馆共用，`FakeCampusProxy` 按 `appId` 分派）。 */
        const val OAUTH_HOST = "org.xjtu.edu.cn"

        /** OAuth 入口路径（`SsnLogin.SSN_OAUTH_URL` 里的那个）。 */
        const val OAUTH_PATH = "/openplatform/oauth/authorize"

        /** 缴费页（`SsnLogin.PAY_PAGE_URL` 的 path；`freshPayUrl` 就拦这一跳）。 */
        const val PAY_PATH = "/cems/index/mobile/pay"

        // ── 夹具样本 ──

        /** 开放平台那一跳回给缴费页的一次性 code。 */
        const val OAUTH_CODE = "SSN-fake-1"

        /** 缴费页 HTML 里内嵌的 `cid`（绑定、列表、电量都要带它；必须是 16~64 位十六进制）。 */
        const val CID = "0123456789abcdef0123456789abcdef"

        /** 唯一一间「已绑定」宿舍。 */
        const val ROOM_ID = "42000101"
        const val ROOM_NAME = "示例园区/1栋/101"

        /** 剩余电量（度）。 */
        const val KWH = 12.5

        /** 缴费页原文：`loginUrl` 为空 = 已登录，`cid` 内嵌在脚本里（`SsnLogin.postLogin` 的读法）。 */
        val PAY_PAGE_HTML = """<html><body><script>var loginUrl = ""; var cid = "$CID";</script></body></html>"""
    }

    // ── 断言用：请求计数与原文 ──

    /** 开放平台入口被打开几次（每次 `freshPayUrl` / 登录都会走一遍）。 */
    val oauthLandings = AtomicInteger(0)

    /** 缴费页被 GET 几次（登录的落点那一跳）。 */
    val payLandings = AtomicInteger(0)

    /** 宿舍列表被要几次。 */
    val roomCalls = AtomicInteger(0)

    /** 电量被要几次。 */
    val kwhCalls = AtomicInteger(0)

    /** 最近一次电量请求带的 `roomId`。 */
    @Volatile
    var lastKwhRoomId: String? = null
        private set

    /** 最近一次宿舍列表请求带的 `cid`。 */
    @Volatile
    var lastListCid: String? = null
        private set

    /**
     * 开放平台那一跳（`/openplatform/oauth/authorize?appId=1759`）。
     *
     * - **第一次**（没带票）：302 到统一认证，`service` = 这个 authorize 地址**原样**
     *   （CAS 签完票会带着 `ticket=` 回到这里）；
     * - **带票回来**：302 到 `redirectUri`（缴费页）并附上一次性 `code` —— 这就是
     *   `SsnLogin.postLogin` 落点、`freshPayUrl` 网络拦截器抓的那一跳。
     *
     * ⚠️ 用 `rawQuery`（百分号编码原文）而不是 `query`：`URI.getQuery` 已解码，`redirectUri`
     * 里若带 `&` 会被截断 —— 和 `LibraryFakeUpstream` 那条 OAuth2 入口同一个坑（见它的 KDoc）。
     */
    fun handleOauth(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val raw = exchange.requestURI.rawQuery.orEmpty()
        if (path != OAUTH_PATH || exchange.requestMethod != "GET") {
            badRequest(exchange, "开放平台只认 GET $OAUTH_PATH，收到：${exchange.requestMethod} $path")
            return
        }
        if (!raw.contains("appId=1759") || !raw.contains("responseType=code")) {
            badRequest(exchange, "OAuth 入口少了 appId/responseType，收到：$raw")
            return
        }
        if (param(raw, "redirectUri") != PAY_URL) {
            badRequest(exchange, "redirectUri 不是 $PAY_URL，收到：${param(raw, "redirectUri")}")
            return
        }
        oauthLandings.incrementAndGet()
        if (param(raw, "ticket") != null) {
            // CAS 签完票回来：把一次性 code 交给缴费页（真站点这里是换会话，见类 KDoc 的「省略的那一跳」）
            redirect(exchange, "$PAY_URL?code=$OAUTH_CODE")
            return
        }
        // 第一次：整条 authorize 地址（含 rawQuery 原文）当 service 交给 CAS
        redirect(
            exchange,
            "http://${com.xjtu.toolbox.library.LibraryFakeUpstream.CAS_HOST}/cas/login?service=" +
                encode("https://$OAUTH_HOST$OAUTH_PATH?$raw"),
        )
    }

    /** `ssn.xjtu.edu.cn` 的缴费页与两条取数接口。 */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when (path) {
            PAY_PATH -> {
                payLandings.incrementAndGet()
                respondHtml(exchange, PAY_PAGE_HTML)
            }
            "/cems/mobile/addr/list" -> {
                lastListCid = param(query, "cid")
                roomCalls.incrementAndGet()
                respondJson(
                    exchange,
                    """{"code":0,"msg":"ok","data":[{"roomId":"$ROOM_ID","roomName":"$ROOM_NAME"}]}""",
                )
            }
            "/cems/mobile/meterAccount/electricity" -> {
                lastKwhRoomId = param(query, "roomId")
                kwhCalls.incrementAndGet()
                respondJson(exchange, """{"code":0,"msg":"ok","data":$KWH}""")
            }
            else -> respond(exchange, 404, "text/plain", "夹具没有这条路径：$path".toByteArray())
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json.toByteArray())

    private fun respondHtml(exchange: HttpExchange, html: String) =
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain", message.toByteArray())

    private val PAY_URL = "http://$HOST$PAY_PATH"
}