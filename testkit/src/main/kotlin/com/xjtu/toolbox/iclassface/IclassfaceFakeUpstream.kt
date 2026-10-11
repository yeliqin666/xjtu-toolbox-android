package com.xjtu.toolbox.iclassface

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的**人脸识别签到**上游（`iclassface.xjtu.edu.cn`，第 20 条真数据路由的学校那一侧）＋它登录链
 * 上游的开放平台那一跳（`org.xjtu.edu.cn/openplatform/oauth/authorizesw`，与加餐券共用同一台开放
 * 平台、同一个路径，靠 `redirect_uri` 里指向谁分流 —— 见
 * [FakeCampusProxy][com.xjtu.toolbox.FakeCampusProxy] 的 dispatch）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`IclassfaceLogin`](com.xjtu.toolbox.iclassface.IclassfaceLogin) /
 * [`IclassfaceSession`](com.xjtu.toolbox.auth.IclassfaceSession) 一起，把**搬进 `:data` 之后**的
 * 登录与取数原样演一遍：
 *
 * ```
 * GET  https://login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=1543&redirect_uri=<org authorizesw>
 *        （没 TGC → 302 /cas/login?service=<这条 authorize 的原文>；登录页那半台在 LibraryFakeUpstream）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 authorize?ticket=ST-n
 * GET  …/cas/oauth2.0/authorize?…&ticket=ST-n → 302 https://org.xjtu.edu.cn/openplatform/oauth/authorizesw?redirect_uri=…&code=OC-fake-n
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorizesw?redirect_uri=<iclassface callback>&code=…
 *        → 302 https://iclassface.xjtu.edu.cn/oauth/callback/xjtu_new?code=OC-fake-n
 * GET  https://iclassface.xjtu.edu.cn/oauth/callback/xjtu_new?code=…  → 种 PHPSESSID，302 /face/detect
 * GET  https://iclassface.xjtu.edu.cn/face/detect                   → 200 首页（`IclassfaceLogin` 判「落在本站」）
 * POST https://iclassface.xjtu.edu.cn/checkin/records  （form: date=YYYY-MM-DD）→ 服务端渲染的表格
 * ```
 *
 * ⚠️ `IclassfaceSession.mustUseWebVpn = true`：真机上校外要走 WebVPN 网关。夹具演的是**直连**
 * 那一面（测试里 `AccessMode` 是 NORMAL），与考勤那条链同一口径 —— `IclassfaceLogin` 本身两种形态
 * 都认（`WebVpnUtil.isAtTargetSite`）。
 *
 * ## 登录链那一跳的省略
 *
 * 真站点上 `authorizesw` 会把 code 换成 `iclassface` 自己的会话票据再回跳；夹具**原样把 code 带
 * 回 `/oauth/callback/xjtu_new`** —— `IclassfaceLogin.postLogin` 只判「最终落在 iclassface 上」，
 * 中间那次换票对被测代码没有可观察差别（与 `VenueFakeUpstream` / `CouponFakeUpstream` 同一种省略）。
 *
 * ## 表格形状照 `IclassfaceApi` 的解析写
 *
 * 一行 6 格：`<序号, 姓名, 学号, 时间, 地点, 类型>`；少于 6 格的行解析时被跳过 —— 夹具刻意在末尾
 * 摆一行**只有 3 格**的残缺行（分页/合计行的常见形态），谁把它算成一条记录就会多出一条。
 *
 * ## 夹具值全是编造的
 *
 * 「张三」、学号 `2021000001`（= `LibraryFakeUpstream.USERNAME`，同一个假账号贯穿几条链）、
 * 地点「中3-2306」、类型「电子班牌刷卡」—— 都不是真人真记录。
 */
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

        /** 回跳落点 —— 与 `IclassfaceLogin.LOGIN_URL` 里那个 `redirect_uri` 的内层**逐字相同**。 */
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

        /**
         * `POST /checkin/records` 的响应：一行完整记录 + 一行只有 3 格的残缺行
         *（解析会跳过它 —— 「少于 6 格不算记录」这条口径就是靠它钉住的）。
         */
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

    /**
     * 开放平台那一跳：`redirect_uri` 是**内层的 iclassface 回调**（明文 URL，不是 base64 ——
     * 加餐券那条才是 base64，所以两个消费方在 dispatch 里按 `redirect_uri` 里的域名分流），
     * 原封不动带上 `code` 回它。
     */
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
