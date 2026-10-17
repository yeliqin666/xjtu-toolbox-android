package com.xjtu.toolbox.jwapp

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.util.concurrent.atomic.AtomicInteger

/** 假的移动教务上游（`jwapp.xjtu.edu.cn`）＋开放平台那一跳（`authorize?appId=1370`）。 */
class JwappFakeUpstream {

    companion object {
        /** 业务站点（`JwappApi.baseUrl` / `JwappLogin` 回跳的那个 host）。 */
        const val HOST = "jwapp.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"
        const val HTTP_ORIGIN = "http://$HOST"

        /** 开放平台那一跳（`JwappLogin.JWAPP_URL` 的 host；`FakeCampusProxy` 按 appId 分派给它）。 */
        const val OAUTH_HOST = "org.xjtu.edu.cn"
        const val OAUTH_PATH = "/openplatform/oauth/authorize"
        const val APP_ID = "1370"

        /** 回跳地址，与 `JwappLogin.JWAPP_URL` 里的 `redirectUri` 相同（有断言钉着）。 */
        const val APP_INDEX_URL = "$HTTP_ORIGIN/app/index"

        /** 与 `JwappLogin.JWAPP_URL` 相同的入口（有断言钉着）。 */
        const val OAUTH_URL =
            "https://$OAUTH_HOST$OAUTH_PATH?appId=$APP_ID&redirectUri=$APP_INDEX_URL" +
                "&responseType=code&scope=user_info&state=1234"

        /** 回跳 URL 里那个令牌（`JwappLogin.postLogin` 抠出来当 `Authorization` 头）。 */
        const val TOKEN = "jwapp-fake-token-2026"

        // ── jwapp 的端点 ──

        const val TIME_URL = "$ORIGIN/api/biz/v410/common/school/time"
        const val GRADE_PATH = "/api/biz/v410/score/termScore"
        const val GRADE_URL = "$ORIGIN$GRADE_PATH"
        const val DETAIL_PATH = "/api/biz/v410/score/scoreDetail"
        const val DETAIL_URL = "$ORIGIN$DETAIL_PATH"

        // ── 成绩样本（与教务 `xscjcx.do` 那份对齐：同一个学期号、同一门课）──

        const val TERM_CODE = "2026-2027-1"
        const val TERM_NAME = "2026-2027学年 第一学期"

        /** jwapp 给的**粗分**（教务那份会给精确值，屏上最终显示的是替换后的）。 */
        const val COURSE_NAME = "高等数学（上）"
        const val COURSE_ID = "SCORE-1"
        const val COURSE_SCORE = "86"
        const val COURSE_POINT = 5.0
        const val COURSE_EXAM_TYPE = "考试"
        const val COURSE_EXAM_PROP = "必修"

        /** 分项：平时 30% + 期末 70%（`itemPercent` 是带 `%` 的字符串，服务端原文如此）。 */
        const val DETAIL_SCORE = "88"
        const val DETAIL_GPA = 3.9
        const val ITEM_1_NAME = "平时"
        const val ITEM_1_PERCENT = "30%"
        const val ITEM_1_SCORE = "85"
        const val ITEM_2_NAME = "期末"
        const val ITEM_2_PERCENT = "70%"
        const val ITEM_2_SCORE = "90"

        /** 「这门课没有分项」那一档的课程 id：拿它去问 `scoreDetail` 会得到一张`code=200` 但 `data=null` 的响应。 */
        const val DETAIL_MISSING_ID = "SCORE-NODETAIL"
        const val DETAIL_MISSING_JSON =
            """{"code":200,"msg":"该课程暂无分项成绩","data":null}"""

        val CURRENT_TERM_JSON = """
            {"code":200,"msg":"ok","data":{"xnxqdm":"$TERM_CODE"}}
        """.trimIndent()

        val GRADE_JSON = """
            {"code":200,"msg":"ok","data":{"termScoreList":[
              {"termCode":"$TERM_CODE","termName":"$TERM_NAME","scoreList":[
                {"id":"$COURSE_ID","termCode":"$TERM_CODE","courseName":"$COURSE_NAME","score":"$COURSE_SCORE",
                 "passFlag":true,"coursePoint":$COURSE_POINT,"examType":"$COURSE_EXAM_TYPE",
                 "examProp":"$COURSE_EXAM_PROP","majorFlag":"1","replaceFlag":false,"specificReason":null}
              ]}
            ]}}
        """.trimIndent()

        val DETAIL_JSON = """
            {"code":200,"msg":"ok","data":{"courseName":"$COURSE_NAME","coursePoint":$COURSE_POINT,
             "examType":"$COURSE_EXAM_TYPE","examProp":"$COURSE_EXAM_PROP","score":"$DETAIL_SCORE","gpa":$DETAIL_GPA,
             "passFlag":true,"itemList":[
               {"itemName":"$ITEM_1_NAME","itemPercent":"$ITEM_1_PERCENT","itemScore":"$ITEM_1_SCORE"},
               {"itemName":"$ITEM_2_NAME","itemPercent":"$ITEM_2_PERCENT","itemScore":"$ITEM_2_SCORE"}
             ]}}
        """.trimIndent()

        /** 回跳落点页（`JwappLogin.postLogin` 只读 finalUrl，页面本身是什么不影响判定）。 */
        val APP_INDEX_HTML = """
            <html><head><title>移动教务</title></head><body>移动教务（夹具）</body></html>
        """.trimIndent()

        /** 业务请求带的 `Authorization` 头（钉「会话装饰器真的加头」）。 */
        const val AUTH_HEADER = "Authorization"
    }

    // ── 断言用：请求计数与最近参数 ──

    /** 开放平台那条 authorize 被摸了几次。 */
    val oauthLandings = AtomicInteger(0)

    /** 带 `token` 的回跳落点被摸了几次。 */
    val tokenLandings = AtomicInteger(0)

    /** `school/time` 被摸了几次（`validateLogin` 那一枪）。 */
    val validateCalls = AtomicInteger(0)

    /** `termScore` 被摸了几次。 */
    val gradeCalls = AtomicInteger(0)

    /** `scoreDetail` 被摸了几次。 */
    val detailCalls = AtomicInteger(0)

    /** 最近一次 `termScore` 请求体（应该带 `"termCode":"*"`）。 */
    var lastGradeBody: String? = null
        private set

    /** 最近一次 `scoreDetail` 请求体（应该带 `"id":"SCORE-1"`）。 */
    var lastDetailBody: String? = null
        private set

    /** 最近一次业务请求带的 `Authorization`（令牌就是 [TOKEN]）。 */
    var lastAuthorization: String? = null
        private set

    /** 开放平台那一跳：带 `ticket=` 回来就签一枚业务令牌塞进回跳 URL（`?token=`），否则把这条 authorize 原文当 `service` 交给统一认证（真站点就是这么转的）。 */
    fun handleOauth(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val raw = exchange.requestURI.rawQuery.orEmpty()
        if (path != OAUTH_PATH || exchange.requestMethod != "GET") {
            badRequest(exchange, "开放平台只认 GET $OAUTH_PATH，收到：${exchange.requestMethod} $path")
            return
        }
        if (!raw.contains("appId=$APP_ID") || !raw.contains("responseType=code")) {
            badRequest(exchange, "OAuth 入口少了 appId/responseType，收到：$raw")
            return
        }
        if (param(raw, "redirectUri") != APP_INDEX_URL) {
            badRequest(exchange, "redirectUri 不是 $APP_INDEX_URL，收到：${param(raw, "redirectUri")}")
            return
        }
        oauthLandings.incrementAndGet()
        if (param(raw, "ticket") != null) {
            // CAS 签完票回来：真站点在这里 302 到 redirectUri 并带上业务令牌（把 code 换令牌那一跳
            // 对被测代码没有可观察差别 —— `JwappLogin` 只读回跳 URL 里的 `token=`）。
            redirect(exchange, "$APP_INDEX_URL?token=$TOKEN")
            return
        }
        // ⚠️ 用 rawQuery 原文拼 service（不要再包一层 encode）：`param()` 自己会解码，
        // 而这条 authorize 自己带好几个 `&` 参数（与 LibraryFakeUpstream 那条同款坑）。
        redirect(
            exchange,
            "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=" +
                encode("https://$OAUTH_HOST$OAUTH_PATH?$raw"),
        )
    }

    /** `jwapp.xjtu.edu.cn` 的回跳落点与三个业务端点（明文回跳 + https 业务两种形态都落在这里）。 */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val authorization = exchange.requestHeaders.getFirst(AUTH_HEADER)
        if (authorization != null) lastAuthorization = authorization
        when {
            path == "/app/index" && "token=" in query -> {
                tokenLandings.incrementAndGet()
                respondHtml(exchange, APP_INDEX_HTML)
            }
            path == "/api/biz/v410/common/school/time" -> {
                validateCalls.incrementAndGet()
                respondJson(exchange, CURRENT_TERM_JSON)
            }
            path == GRADE_PATH -> {
                lastGradeBody = readBody(exchange)
                gradeCalls.incrementAndGet()
                respondJson(exchange, GRADE_JSON)
            }
            path == DETAIL_PATH -> {
                val body = readBody(exchange)
                lastDetailBody = body
                detailCalls.incrementAndGet()
                // 「这门课没有分项」那一档：真站点给 `code:200` + `data:null`（见 [DETAIL_MISSING_ID]）。
                if (body.contains("\"id\":\"$DETAIL_MISSING_ID\"")) {
                    respondJson(exchange, DETAIL_MISSING_JSON)
                    return
                }
                respondJson(exchange, DETAIL_JSON)
            }
            else -> respond(
                exchange,
                404,
                "text/plain; charset=utf-8",
                "夹具没有这条路径：$path?$query".toByteArray(),
            )
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().decodeToString()

    private fun param(raw: String, name: String): String? = raw.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

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

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}
