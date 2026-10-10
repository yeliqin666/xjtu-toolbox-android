package com.xjtu.toolbox.fitness

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「体测系统」上游 —— 扮演 `tyxylp.xjtu.edu.cn`（体测这条真数据路由的学校那一侧）。
 *
 * ## 它扮演到什么程度
 *
 * 完整那条链，一环不省：
 *
 * ```
 * GET  <LOGIN_URL>                       →（不带 ticket）302 到统一认证（service = LOGIN_URL）
 * POST http://login.xjtu.edu.cn/cas/login →（凭据表单，由 LibraryFakeUpstream 扮演 CAS）
 * GET  <LOGIN_URL>?ticket=ST-n            → 302 到 **launch 回调 URL**（会话参数在 URL 碎片里）
 * GET  <H5_HOME_URL>（碎片不上网）         → 200 H5 首页 —— 登录链的最后一跳
 * POST <USER_INFO_URL>                    → v3 信封：会话初始化（`FitnessLogin.postLogin` 会打）
 * POST <API_V3>/fitness/fitnessYear       → 学年列表
 * POST <API_V3>/Report/getStudentScore    → 某学年成绩
 * ```
 *
 * 后两条另有 **legacy** 一路（`LEGACY_API_ROOT/…`），形状与 v3 完全相同 —— `FitnessApi`
 * 是「v3 优先、失败退回 legacy」，两路都得给得出同一份业务数据，否则退回时测到的是 404。
 * 想强制走 legacy：[v3Down]。
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是**所有站点共用**的一台 CAS，`FakeCampusProxy` 把它分派给
 * [LibraryFakeUpstream]（它已经完整扮演了 CAS 的登录页 / 表单 POST / 签 ticket / TGC）。
 * 这个夹具只负责 `tyxylp.xjtu.edu.cn` 那一半：**CAS 回跳之后**的事。
 *
 * ## 契约：逐字段对着 `FitnessApi` / `FitnessProtocol` 的解析写
 *
 * - launch 回调 URL 的东西全部来自 `FitnessProtocol.extractLaunch` 的判据：`#` 必须有、
 *   碎片里 `?` 之后的 query 里 [FitnessProtocol.REQUIRED_LAUNCH_FIELDS] 七个字段一个不能少，
 *   而且 `user_type` 必须是 `ROLE_BY_USER_TYPE` 认识的值（否则它直接抛「用户类型异常」）；
 *   碎片路径决定 `referer`（= [H5_HOME_URL]，`FitnessApi` 拿它当 Referer 头）。
 *   ⚠️ 这里**多发一个 `nonce`**：它不在必需字段里，但 `FitnessProtocol.sessionFromTokens`
 *   要求它也在（八个 `SESSION_FIELDS` 缺一就返回 null）—— 少了它，取数会以
 *   「体测会话未初始化」失败，而回调本身看起来是成功的。
 * - 数据端点给的是 `FitnessApi.loadYears` / `loadScore` 真正读的那些键
 *   （`list[].year_num/name/checked`、`student_num/total_score/…/<项>_score|_grade|_class`），
 *   值取完还会过 `formatFitnessScore`（两位小数）与 `fitnessItemName`（按 `sex` 换名）。
 * - 信封形状：`{"status":1,"info":…,"data":<业务对象>}`。v3 那一路 `FitnessProtocol.parseEnvelope`
 *   支持「整段密文 / `data` 是密文 / **`data` 就是明文对象**」三种，这里用第三种：
 *   假上游不必持有 AES 密钥与签名盐（`:testkit` 刻意零依赖），客户端那半边的加密照常发生
 *   （请求体的 `data=` 确实是密文，见 [requireV3Envelope] 对它的形体检查）。
 *
 * ## 认不出的路径要响亮
 *
 * 路径不认识 → 404，**body 里带着完整 URL**；认识的路径但形体不对（method 不对、
 * v3 请求体不像加密信封）→ 400，body 里带着实际收到的样子。URL 一漂就该一眼看出来，
 * 而不是被「反正也是 JSON」蒙混过去。
 */
class FitnessFakeUpstream {

    companion object {
        const val HOST = "tyxylp.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        const val LOGIN_URL =
            "$ORIGIN/bdlp_h5_fitness_test/public/index.php/index/login/xjtuLogin"
        const val H5_HOME_URL =
            "$ORIGIN/bdlp_h5_fitness_test/view/h5xajt/#/pages/index/index"
        const val API_V3 = "$ORIGIN/v3/api.php"
        const val USER_INFO_URL = "$API_V3/WpLogin/UserInfo"
        const val LEGACY_API_ROOT = "$ORIGIN/bdlp_h5_fitness_test/public/index.php/index"

        // 路径从上面的 URL 里切出来（一处定义，免得路由表与 URL 常量各自漂移）。
        // H5 首页的路径要连碎片一起去掉：碎片是给客户端解析的，根本不会发到服务器。
        private val LOGIN_PATH = LOGIN_URL.removePrefix(ORIGIN)
        private val H5_PATH = H5_HOME_URL.removePrefix(ORIGIN).substringBefore('#')
        private val USER_INFO_PATH = USER_INFO_URL.removePrefix(ORIGIN)
        private val V3_ROOT_PATH = "$API_V3".removePrefix(ORIGIN)
        private val LEGACY_ROOT_PATH = LEGACY_API_ROOT.removePrefix(ORIGIN)

        /**
         * 会话样本。学号就是 CAS 那组假账号里的同一个（同一个人在两个系统里），
         * 其余是可读的编造值 —— 断言全部从这里取，不从实现的输出里抄。
         */
        const val UID = "uid-2401"
        const val TOKEN = "token-7f3a91c2"
        const val SCHOOL_ID = "school-xjtu"
        const val TERM_ID = "term-2026-1"
        const val STUDENT_NUM = LibraryFakeUpstream.USERNAME
        const val CARD_ID = "card-8821"
        const val USER_TYPE = "2"
        const val NONCE = "004242"

        /** 成绩样本（学年 / 姓名 / 总分 / 性别 …）。 */
        const val YEAR_OLD = "2025"
        const val YEAR_OLD_NAME = "2025-2026学年"
        const val YEAR_NEW = "2026"
        const val YEAR_NEW_NAME = "2026-2027学年"
        const val STUDENT_NAME = "示例同学"
        const val SEX = "男"
        const val GRADE = "大二"
        const val REPORT_STATUS = "已出报告"
    }

    // ── 服务器可变状态：动作打进来时计数，好让断言「这一枪真打到了哪里」 ──

    /** 发出过几个 launch 回调（= CAS 回跳真的走完了）。 */
    val launchCallbacks = AtomicInteger()

    /** 没带 ticket 时把人交给统一认证的次数。 */
    val casRedirects = AtomicInteger()

    /** `WpLogin/UserInfo` 被打了几次（`postLogin` 里那一枪 + 每次探活）。 */
    val userInfoCalls = AtomicInteger()

    val v3Calls = AtomicInteger()
    val legacyCalls = AtomicInteger()

    /** 打开后 v3 回一个 `status != 1` 的信封 ⇒ `FitnessApi` 退回 legacy（两路都要能验）。 */
    val v3Down = AtomicBoolean(false)

    // ── 样本：业务数据（v3 与 legacy 共用同一份，信封不同而已）──────

    /** 学年列表：`FitnessApi.loadYears` 只读 `year_num` / `name` / `checked`。 */
    val yearsJson = """
        {"list":[
          {"year_num":"$YEAR_OLD","name":"$YEAR_OLD_NAME","checked":false},
          {"year_num":"$YEAR_NEW","name":"$YEAR_NEW_NAME","checked":true}
        ]}
    """.trimIndent()

    /**
     * 成绩单：键就是 `FitnessApi.loadScore` 取的那批。
     *
     * ⚠️ 刻意**不给** `50m_*`：那两个键缺了以后 `value` 落到「未测」、`grade` 落到「缺项」——
     * 缺项的降级口径要是被改坏，这里会红。
     */
    val scoreJson = """
        {
          "student_num":"$STUDENT_NUM",
          "student_name":"$STUDENT_NAME",
          "sex":"$SEX",
          "grade":"$GRADE",
          "total_score":"78.8",
          "total_grade":"良好",
          "report_type":"1",
          "report_status":"$REPORT_STATUS",
          "bmi_score_new":"85",
          "bmi_grade":"良好",
          "bmi_class":"good",
          "vc_score":"4123",
          "vc_grade":"优秀",
          "vc_class":"excellent",
          "jump_score":"2.31",
          "jump_grade":"良好",
          "jump_class":"good",
          "sit_and_reach_score":"12.5",
          "sit_and_reach_grade":"优秀",
          "sit_and_reach_class":"excellent",
          "pull_and_sit_score":"9",
          "pull_and_sit_grade":"及格",
          "pull_and_sit_class":"pass",
          "run_score":"4.12",
          "run_grade":"良好",
          "run_class":"good"
        }
    """.trimIndent()

    /** `WpLogin/UserInfo` 的业务对象：`requestUserInfo` 只要求它是个非空对象。 */
    val userInfoJson = """
        {"uid":"$UID","student_num":"$STUDENT_NUM","student_name":"$STUDENT_NAME",
         "school_id":"$SCHOOL_ID","term_id":"$TERM_ID","ostype":"5","role":1}
    """.trimIndent()

    /** H5 首页（登录链最后一跳）。**不能**长得像 CAS 登录页：`CasLoginPages` 会据此判会话失效。 */
    val h5Page = """
        <html><head><title>西安交通大学体测</title></head><body>
          <div id="app">体测查询</div>
        </body></html>
    """.trimIndent()

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == LOGIN_PATH -> handleLogin(exchange, query)
            path == H5_PATH && exchange.requestMethod == "GET" -> respondHtml(exchange, h5Page)

            path == USER_INFO_PATH -> requireV3Envelope(exchange) {
                userInfoCalls.incrementAndGet()
                respondJson(exchange, envelope(userInfoJson))
            }
            path == "$V3_ROOT_PATH/fitness/fitnessYear" -> requireV3Envelope(exchange) {
                v3Calls.incrementAndGet()
                if (v3Down.get()) respondJson(exchange, v3DownJson) else respondJson(exchange, envelope(yearsJson))
            }
            path == "$V3_ROOT_PATH/Report/getStudentScore" -> requireV3Envelope(exchange) {
                v3Calls.incrementAndGet()
                if (v3Down.get()) respondJson(exchange, v3DownJson) else respondJson(exchange, envelope(scoreJson))
            }

            path == "$LEGACY_ROOT_PATH/fitness/fitnessYear" -> requireLegacyPost(exchange) {
                legacyCalls.incrementAndGet()
                respondJson(exchange, envelope(yearsJson))
            }
            path == "$LEGACY_ROOT_PATH/Report/getStudentScore" -> requireLegacyPost(exchange) {
                legacyCalls.incrementAndGet()
                respondJson(exchange, envelope(scoreJson))
            }

            else -> notFound(exchange)
        }
    }

    /**
     * 业务登录入口：
     * - 带 `ticket=` ⇒ CAS 已经认过人，这里 302 到 launch 回调（真站点也是这么把会话参数交给 H5 的）；
     * - 没带 ⇒ 302 到统一认证，`service` 就是本站登录入口（`XJTULogin` 的 init 走的正是这一枪）。
     */
    private fun handleLogin(exchange: HttpExchange, query: String) {
        if ("ticket=" in query) {
            launchCallbacks.incrementAndGet()
            redirect(exchange, launchCallbackUrl())
            return
        }
        casRedirects.incrementAndGet()
        redirect(exchange, "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(LOGIN_URL)}")
    }

    /** CAS 回跳后的落点：会话参数在 URL 碎片里（`#/pages/index/index?uid=…`），七个必需字段 + `nonce`。 */
    private fun launchCallbackUrl(): String =
        H5_HOME_URL + "?" + listOf(
            "uid" to UID,
            "token" to TOKEN,
            "school_id" to SCHOOL_ID,
            "term_id" to TERM_ID,
            "student_num" to STUDENT_NUM,
            "card_id" to CARD_ID,
            "user_type" to USER_TYPE,
            "nonce" to NONCE,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    // ── 响应的形体检查（形体不对就响亮地失败，别用「反正也是 JSON」蒙混过去）──────

    /** v3 请求必须是 `ostype=5&data=<密文>` 的表单 POST —— 客户端改了形体这里立刻 400。 */
    private fun requireV3Envelope(exchange: HttpExchange, respond: () -> Unit) {
        val body = readBody(exchange)
        if (exchange.requestMethod != "POST" || !body.contains("ostype=5") || !body.contains("data=")) {
            badRequest(exchange, "expected v3 encrypted form POST, got ${exchange.requestMethod} ${exchange.requestURI}")
            return
        }
        respond()
    }

    private fun requireLegacyPost(exchange: HttpExchange, respond: () -> Unit) {
        readBody(exchange)
        if (exchange.requestMethod != "POST") {
            badRequest(exchange, "expected legacy POST, got ${exchange.requestMethod} ${exchange.requestURI}")
            return
        }
        respond()
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

    /** v3 与 legacy 的信封形状相同（差别只在路径），业务数据同一份。 */
    private fun envelope(business: String) = """{"status":1,"info":"查询成功","data":$business}"""

    private val v3DownJson = """{"status":0,"info":"v3 挂了（夹具开关）","data":""}"""

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange) {
        val url = exchange.requestURI.toString()
        respond(
            exchange,
            404,
            "text/plain; charset=utf-8",
            "no such fitness path: $url".toByteArray(),
        )
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun readBody(exchange: HttpExchange): String =
        runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

}
