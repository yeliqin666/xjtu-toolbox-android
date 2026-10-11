package com.xjtu.toolbox.jwapp

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的**移动教务**上游（`jwapp.xjtu.edu.cn`，第 19 条真数据路由的学校那一侧）＋它登录链上游的
 * 开放平台那一跳（`org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370`）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`JwappLogin`](com.xjtu.toolbox.auth.JwappLogin) / [`JwappSession`](com.xjtu.toolbox.auth.JwappSession)
 * 一起，把**搬进 `:data` 之后**的登录与取数原样演一遍：
 *
 * ```
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&…&state=1234
 *        （没 TGC → 302 到 http://login.xjtu.edu.cn/cas/login?service=<这条 authorize 的原文>）
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 authorize?ticket=ST-n（登录页那半台在 LibraryFakeUpstream）
 * GET  https://org.xjtu.edu.cn/openplatform/oauth/authorize?…&ticket=ST-n
 *        → 302 http://jwapp.xjtu.edu.cn/app/index?token=<业务令牌>（**token 在回调 URL 里**，JwappLogin 从它抠出来）
 * GET  http://jwapp.xjtu.edu.cn/app/index?token=…      → 200（落点；此后每个业务请求带 `Authorization: <令牌>`）
 * GET  https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time   → 200（`validateLogin` 与首页成绩卡那一枪）
 * POST https://jwapp.xjtu.edu.cn/api/biz/v410/score/termScore      → 学期成绩（body 里是 {"termCode":"*"}）
 * POST https://jwapp.xjtu.edu.cn/api/biz/v410/score/scoreDetail    → 分项成绩（body 里是 {"id":"…"}）
 * ```
 *
 * ⚠️ 登录链的**第一个 host 是 `org.xjtu.edu.cn`**（与场馆 `appId=1659` / 宿舍电费 `appId=1759` 同一台
 * 开放平台，靠 `appId` 分流，见 [FakeCampusProxy][com.xjtu.toolbox.FakeCampusProxy] 的 dispatch）；
 * 回跳落在 **明文 http** 的 `jwapp.xjtu.edu.cn`（`JwappLogin.JWAPP_URL` 里那个 `redirectUri` 就是
 * http），而业务接口走 https —— 同一个 host 两种传输形态，所以 `jwapp.xjtu.edu.cn` 既在
 * `HTTPS_HOSTS`（要隧道）里，也要答明文代理过来的那一枪。
 *
 * ## 话痨一点的端点
 *
 * `school/time` 这一枪是给 `JwappSession.validateLogin()` 的：它**只判 200**，不解析正文，
 * 所以这里给的正文照真站点的形状（`{"code":200,"data":{"xnxqdm":…}}`）—— 形状对了，
 * `JwappApi.getCurrentTerm()`（首页成绩卡）将来也走这一条。
 *
 * ## 成绩样本与教务那份是**同一组课**
 *
 * `xscjcx.do`（`CjcxApi`，在 [com.xjtu.toolbox.jwxt.JwxtFakeUpstream] 里）给的是**精确总评**，
 * 屏幕上的最终成绩是它替换过的那一份 —— 所以两边的学期号与课名必须对得上（同一个学期
 * `2026-2027-1`、同一门「高等数学（上）」）。对不上不会报错，只会「精确成绩没替换成功」，
 * 那正是最难查的一种坏，所以样本刻意对齐。
 *
 * ## 夹具值全是编造的
 *
 * 「高等数学（上）」（借用本校真课名，但成绩、学分、绩点都是编的）、老师是谁不出现；
 * 学号与 `LibraryFakeUpstream.USERNAME` 一致只是为了「同一个假账号贯穿几条链」。
 */
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

        /** 回跳地址 —— 与 `JwappLogin.JWAPP_URL` 里的 `redirectUri` **逐字相同**（有断言钉着）。 */
        const val APP_INDEX_URL = "$HTTP_ORIGIN/app/index"

        /** 与 `JwappLogin.JWAPP_URL` 逐字相同的那条入口（有断言钉着）。 */
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

    /**
     * 开放平台那一跳：带 `ticket=` 回来就签一枚**业务令牌**塞进回跳 URL（`?token=`），
     * 否则把这条 authorize 原文当 `service` 交给统一认证（真站点就是这么转的）。
     */
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
                lastDetailBody = readBody(exchange)
                detailCalls.incrementAndGet()
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
