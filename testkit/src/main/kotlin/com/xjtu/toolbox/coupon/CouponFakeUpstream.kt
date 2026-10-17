package com.xjtu.toolbox.coupon

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** 假的加餐券系统（`egc.xjtu.edu.cn`）＋它登录链上游的开放平台那一跳（`org.xjtu.edu.cn/openplatform/oauth/authorizesw`）。 */
class CouponFakeUpstream {

    companion object {
        /** 业务站点（`CouponApi.BASE_URL` / `CouponLogin.BASE_URL` 的 host）。 */
        const val HOST = "egc.xjtu.edu.cn"

        /** 开放平台那一跳（`CouponLogin.ORG_REDIRECT` 的 host；`FakeCampusProxy` 按路径分派给它）。 */
        const val OAUTH_HOST = "org.xjtu.edu.cn"

        const val RECEIVE_PATH = "/page/cas/receiveCas.html"

        // ── 夹具样本 ──
        const val OAUTH_CODE = "OC-fake-1"
        const val USER_TYPE = "0"
        const val EMPLOYEE_NO = "2021000001"
        const val AUTH_TOKEN = "eyJ-coupon-fake-token"

        /** 可使用（status=1）那两批。 */
        const val USABLE_SEND_ID = "S0001"
        const val USABLE_CARD_ID = "CARD-假-0001"
        const val USABLE_NAME = "劳动节加餐券（假）"
        const val USABLE_LEFT_COUNT = "1"

        /** 可领取（status=0）那一批。 */
        const val AVAILABLE_SEND_ID = "S0002"
        const val AVAILABLE_CARD_ID = "CARD-假-0002"
        const val AVAILABLE_NAME = "校庆加餐券（假）"

        const val AMOUNT_FEN = "500"
        const val LEFT_AMOUNT_FEN = "500"
        const val DATE_START = "2026-05-01"
        const val DATE_END = "2026-05-31 23:59"
        const val PIC_PATH = "/coupon-fake/usable.png"
        const val DETAIL_TITLE = "劳动节放假加餐（假）"
        const val DETAIL_DESC = "凭券在指定食堂兑换（假）"
        const val BATCH_ID = "BATCH-1"
        const val CLOSED_PIC = "/coupon-fake/closed.png"
        const val OPEN_PIC = "/coupon-fake/open.png"

        /** `activate` 业务拒收那一档的 msg（[activateRejected] 打开后用）。 */
        const val ACTIVATE_REJECTED_MSG = "该券已领完（夹具）"

        /** 1×1 透明 PNG（真实可解码的字节，封面图那条链会真解它）。 */
        val PIC_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }

    // ── 断言用：请求原文与计数 ──

    val pageBodies: MutableList<String> = mutableListOf()
    val detailBodies: MutableList<String> = mutableListOf()
    val activateBodies: MutableList<String> = mutableListOf()
    val ssoBodies: MutableList<String> = mutableListOf()
    val receiveLandings = AtomicInteger(0)
    val orgCallbacks = AtomicInteger(0)
    var lastImageAuthorization: String? = null
        private set

    /** 业务请求（查券/详情/领取）最后一次带的 `Authorization` —— 钉「会话装饰器真的加头」用的。 */
    var lastBusinessAuthorization: String? = null
        private set

    /** 打开后所有业务响应都是 HTML（`executeRaw` 看见 `<html` 就当会话失效的那条路）。 */
    var htmlMode = false

    /** 打开后 `query.page.list` 回 `{"code":401,...}`（auth token 失效的那条路）。 */
    var forceCode401 = false

    /** 打开后 `activate` 回一个 `{"code":400,...}` 的业务拒收（券领完了 / 不能领那一档）—— 演「到了上游、上游说不给领」。 */
    @Volatile
    var activateRejected: Boolean = false

    /** 打开后 `activate` 回 `{"code":401,...}` —— 演「领券时才发现会话失效」。 */
    @Volatile
    var activateExpired: Boolean = false

    fun handleBusiness(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = exchange.requestBody.readBytes().decodeToString()
        if (htmlMode) {
            respondHtml(exchange, "<html><body>登录页（夹具）</body></html>")
            return
        }
        when {
            path == RECEIVE_PATH -> {
                receiveLandings.incrementAndGet()
                respondHtml(exchange, "<html><body>登录成功（夹具）</body></html>")
            }
            path == "/sso/login" -> {
                ssoBodies += exchange.requestURI.query.orEmpty()
                respondJson(exchange, """{"code":200,"msg":"ok","data":{"token":"$AUTH_TOKEN"}}""")
            }
            path == "/app/voucher/query.page.list" -> {
                pageBodies += body
                lastBusinessAuthorization = exchange.requestHeaders.getFirst("Authorization")
                if (forceCode401) {
                    respondJson(exchange, "{\"code\":401,\"msg\":\"令牌过期（夹具）\"}")
                    return
                }
                respondJson(exchange, pageJson(body))
            }
            path == "/app/voucher/query.details" -> {
                detailBodies += body
                respondJson(exchange, detailJson)
            }
            path == "/app/voucher/activate" -> {
                activateBodies += body
                if (activateExpired) {
                    respondJson(exchange, "{\"code\":401,\"msg\":\"令牌过期（夹具）\"}")
                    return
                }
                if (activateRejected) {
                    respondJson(exchange, "{\"code\":400,\"msg\":\"$ACTIVATE_REJECTED_MSG\"}")
                    return
                }
                respondJson(exchange, """{"code":200,"msg":"操作成功"}""")
            }
            path == PIC_PATH -> {
                lastImageAuthorization = exchange.requestHeaders.getFirst("Authorization")
                respond(exchange, 200, "image/png", PIC_BYTES)
            }
            else -> respond(exchange, 404, "text/plain", "夹具没有这条路径：$path".toByteArray())
        }
    }

    /** 开放平台那一跳：`redirect_uri` 是 base64 的 egc 接收页，解出来后原封不动带`code/userType/employeeNo` 回它——这就是 `CouponLogin.postLogin` 从 finalUrl 里解参数的那一跳。 */
    fun handleOrg(exchange: HttpExchange) {
        orgCallbacks.incrementAndGet()
        val query = exchange.requestURI.query.orEmpty()
        val code = param(query, "code") ?: OAUTH_CODE

        // `redirect_uri` 是 `base64` 前缀 + base64 内容：先去掉前缀再解。
        val encoded = param(query, "redirect_uri").orEmpty()
            .removePrefix("base64")
        val redirectUri = runCatching {
            String(Base64.getDecoder().decode(encoded))
        }.getOrNull()
        if (redirectUri.isNullOrBlank()) {
            respond(exchange, 400, "text/plain", "authorizesw 要带 redirect_uri（收到：$query）".toByteArray())
            return
        }
        val receive = if ('?' in redirectUri) "$redirectUri&" else "$redirectUri?"
        redirect(
            exchange,
            "$receive" +
                "code=${encode(code)}" +
                "&userType=$USER_TYPE" +
                "&employeeNo=$EMPLOYEE_NO",
        )
    }

    /** 按 `status` 给不同的券卡：status=1 两批可使用，status=0 一批可领取，其余给空表。 */
    private fun pageJson(body: String): String {
        val status = Regex(""""status"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
        val records = when (status) {
            "1" -> listOf(
                """{"sendId":"$USABLE_SEND_ID","showCardId":"$USABLE_CARD_ID","voucherName":"$USABLE_NAME",
                    "typeName":"加餐券","tranamt":"$AMOUNT_FEN","ltranamt":"$LEFT_AMOUNT_FEN",
                    "lknumber":"$USABLE_LEFT_COUNT","startDate":"$DATE_START","endDate":"$DATE_END","pic":"$PIC_PATH"}""",
                """{"sendId":"S0003","showCardId":"CARD-假-0003","voucherName":"毕业季加餐券（假）",
                    "typeName":"加餐券","tranamt":"300","ltranamt":"120","lknumber":"1",
                    "startDate":"$DATE_START","endDate":"$DATE_END","pic":""}""",
            )
            "0" -> listOf(
                """{"sendId":"$AVAILABLE_SEND_ID","showCardId":"$AVAILABLE_CARD_ID","voucherName":"$AVAILABLE_NAME",
                    "typeName":"加餐券","tranamt":"$AMOUNT_FEN","ltranamt":"$LEFT_AMOUNT_FEN",
                    "lknumber":"0","startDate":"$DATE_START","endDate":"$DATE_END","pic":"$PIC_PATH"}""",
            )
            else -> emptyList()
        }
        return """{"code":200,"msg":"ok","data":{"records":[${records.joinToString(",")}],"total":${records.size}}}"""
    }

    private val detailJson: String = """
        {"code":200,"msg":"ok","data":{
          "voucherName":"$USABLE_NAME","destitle":"$DETAIL_TITLE","describes":"$DETAIL_DESC",
          "tranamt":"$AMOUNT_FEN","ltranamt":"$LEFT_AMOUNT_FEN",
          "startDate":"$DATE_START","endDate":"$DATE_END","batchId":"$BATCH_ID",
          "pic":"$PIC_PATH","rclose":"$CLOSED_PIC","ropen":"$OPEN_PIC"}}
    """.trimIndent()

    // ── 小工具 ───────────────────────────────────────────────────

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
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
}