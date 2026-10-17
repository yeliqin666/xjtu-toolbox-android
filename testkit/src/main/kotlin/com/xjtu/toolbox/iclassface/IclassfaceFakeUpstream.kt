package com.xjtu.toolbox.iclassface

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** 假的人脸识别签到上游（`iclassface.xjtu.edu.cn`）＋它登录链上游的开放平台那一跳（与加餐券共用，靠 `redirect_uri` 分流）。 */
class IclassfaceFakeUpstream {

    companion object {
        /** 业务站点（`IclassfaceLogin.BASE_URL` / `IclassfaceApi` 的 host）。 */
        const val HOST = "iclassface.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        /** 开放平台那一跳（`IclassfaceLogin.LOGIN_URL` 里 `redirect_uri` 指向的 host）。 */
        const val OAUTH_HOST = "org.xjtu.edu.cn"
        const val OAUTH_PATH = "/openplatform/oauth/authorizesw"

        /** CAS OAuth2 的 `client_id`（人脸识别签到平台），`IclassfaceLogin.LOGIN_URL` 里那个值。 */
        const val CLIENT_ID = "1543"

        /** 回跳落点，与 `IclassfaceLogin.LOGIN_URL` 里 `redirect_uri` 的内层相同。 */
        const val CALLBACK_PATH = "/oauth/callback/xjtu_new"
        const val CALLBACK_URL = "$ORIGIN$CALLBACK_PATH"

        /** 登录后的首页（`IclassfaceLogin.FACE_DETECT_URL`）。 */
        const val FACE_DETECT_URL = "$ORIGIN/face/detect"

        /** 取数端点（`IclassfaceApi.CHECKIN_URL`）。 */
        const val CHECKIN_URL = "$ORIGIN/checkin/records"
        const val CHECKIN_PATH = "/checkin/records"

        /** 会话 cookie（`PHPSESSID`）—— 真站点上登录之后唯一要带的东西。 */
        const val SESSION_COOKIE = "PHPSESSID_iclassface_fake"

        // ── 记录样本（一行真记录 + 一行残缺行）──

        const val RECORD_NAME = "张三"
        const val RECORD_STUDENT_NO = "2021000001"
        const val RECORD_TIME = "2026-06-04 14:14:37"
        const val RECORD_LOCATION = "中3-2306"
        const val RECORD_TYPE = "电子班牌刷卡"

        /** 首页 HTML（`postLogin` 只看 URL，不看正文；这是给「页面长什么样」留个样本）。 */
        val FACE_DETECT_HTML = """
            <html><head><title>人脸识别签到</title></head><body>
              <div id="app">人脸识别签到（夹具）</div>
            </body></html>
        """.trimIndent()

        /** `POST /checkin/records` 的响应：一行完整记录 + 一行只有 3 格的残缺行（解析会跳过它）。 */
        val RECORDS_HTML = """
            <html><body><table class="table">
              <thead><tr><th>序号</th><th>姓名</th><th>学号</th><th>时间</th><th>地点</th><th>类型</th></tr></thead>
              <tbody>
                <tr><td>1</td><td>$RECORD_NAME</td><td>$RECORD_STUDENT_NO</td>
                    <td>$RECORD_TIME</td><td>$RECORD_LOCATION</td><td>$RECORD_TYPE</td></tr>
                <tr><td>2</td><td>合计</td><td>1 条</td></tr>
              </tbody>
            </table></body></html>
        """.trimIndent()

        /** 空表（当天还没有记录）—— 屏上应当显示「那天没有任何记录」，不是错误页。 */
        val EMPTY_RECORDS_HTML = """
            <html><body><table class="table"><tbody></tbody></table></body></html>
        """.trimIndent()
    }

    // ── 断言用：计数与最近参数 ──

    /** `authorizesw` 那一跳被摸了几次。 */
    val orgCallbacks = AtomicInteger(0)

    /** `/oauth/callback/xjtu_new` 被摸了几次。 */
    val callbackCalls = AtomicInteger(0)

    /** `/face/detect` 被摸了几次（`postLogin` 的落点判据）。 */
    val faceDetectCalls = AtomicInteger(0)

    /** `POST /checkin/records` 被摸了几次。 */
    val checkinCalls = AtomicInteger(0)

    /** 最近一次查询带的 `date=`（断言打出去的日期就是屏上选的那天）。 */
    var lastDate: String? = null
        private set

    /** 打开后 `checkin/records` 一律回空表（「当天没有记录」那条分支）。 */
    @Volatile
    var emptyRecords = false

    /** 开放平台那一跳：`redirect_uri` 是内层的 iclassface 回调（明文 URL，不是 base64）。 */
    fun handleOrg(exchange: HttpExchange) {
        val raw = exchange.requestURI.rawQuery.orEmpty()
        if (exchange.requestURI.path != OAUTH_PATH) {
            badRequest(exchange, "开放平台这一跳只认 $OAUTH_PATH，收到：${exchange.requestURI.path}")
            return
        }
        orgCallbacks.incrementAndGet()
        val redirectUri = param(raw, "redirect_uri")
        if (redirectUri != CALLBACK_URL) {
            badRequest(exchange, "redirect_uri 不是 $CALLBACK_URL，收到：$redirectUri")
            return
        }
        val code = param(raw, "code") ?: "OC-fake-iclassface-1"
        redirect(exchange, "$redirectUri?code=${encode(code)}")
    }

    /** `iclassface.xjtu.edu.cn`：登录链的两跳与取数那一枪。 */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == CALLBACK_PATH -> {
                callbackCalls.incrementAndGet()
                exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=yes; Path=/")
                redirect(exchange, FACE_DETECT_URL)
            }
            path == "/face/detect" -> {
                faceDetectCalls.incrementAndGet()
                respondHtml(exchange, FACE_DETECT_HTML)
            }
            path == CHECKIN_PATH -> {
                lastDate = param(exchange.requestBody.readBytes().decodeToString(), "date")
                checkinCalls.incrementAndGet()
                respondHtml(exchange, if (emptyRecords) EMPTY_RECORDS_HTML else RECORDS_HTML)
            }
            else -> respond(
                exchange,
                404,
                "text/plain; charset=utf-8",
                "夹具没有这条路径：$path".toByteArray(),
            )
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun param(raw: String, name: String): String? = raw.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondHtml(exchange: HttpExchange, html: String) =
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}
