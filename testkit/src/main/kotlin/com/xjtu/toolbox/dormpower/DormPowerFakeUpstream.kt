package com.xjtu.toolbox.dormpower

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** 假的宿舍电费系统（`ssn.xjtu.edu.cn/cems`）＋它登录链上游的开放平台那一跳（`org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759`）。 */
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

    /** 开放平台那一跳（`/openplatform/oauth/authorize?appId=1759`）。 */
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