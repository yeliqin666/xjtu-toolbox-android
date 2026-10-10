package com.xjtu.toolbox.jwxt

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「教务系统」上游 —— 扮演 `jwxt.xjtu.edu.cn`（全校课表 + 成绩报表两条真数据路由的学校那一侧）。
 *
 * ## 它扮演到什么程度
 *
 * ```
 * GET  <HOME_URL>                      →（不带 ticket/jwxt 会话）302 到统一认证（service = HOME_URL）
 * POST http://login.xjtu.edu.cn/cas/login →（凭据表单，由 LibraryFakeUpstream 扮演 CAS）
 * GET  <HOME_URL>?ticket=ST-n          → 200 首页（会话 cookie 落下来）—— 登录链的最后一跳
 * GET  <KCB_CX> 的 `*default/index.do`  → 200（`SchoolCourseApi.ensureAppInitialized` 的预热）
 * POST <KCB_CX>/modules/bjkcb/dqxnxq.do   → 当前学期
 * POST <KCB_CX>/modules/bjkcb/xnxqcx.do   → 学期列表（请求里必须带 `*order=-DM`）
 * POST <DEPARTMENTS_URL>               → 开课单位（`/jwapp/code/…` 那张 id 表）
 * POST <KCB_CX>/modules/qxkcb/qxfbkccx.do → 全校课表一页（请求里必须带 `querySetting`）
 * GET  <REPORT_URL>?reportlet=…&xh=…    → 帆软报表初始页（`FR.SessionMgr.register`）
 * GET  <REPORT_URL>?…&op=page_content&sessionID=…&pn=N → 第 N 页表格
 * ```
 *
 * 真实站点那条登录链还夹着 `org.xjtu.edu.cn` 的 OAuth 三跳（`client_id=1675` →
 * `callbackAuthorize` → 教务）。这里**直接 302 到统一认证**：夹具要演的是「CAS 回跳之后
 * 客户端拿到的那个 URL 与页面」，OAuth 中间那几跳对被测代码没有可观察差别
 * （`JwxtLogin.postLogin` 只判「最终停在 jwxt 上」）。这条省略写在这里，免得被读成「真站点就两跳」。
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是所有站点共用的一台 CAS，`FakeCampusProxy` 把它分派给
 * [LibraryFakeUpstream]（它完整扮演了登录页 / 表单 POST / 签 ticket / TGC）。这个夹具只负责
 * `jwxt.xjtu.edu.cn` 那一半：**CAS 回跳之后**的事。
 *
 * ## 契约：逐字段对着 `:data` 里的解析函数写
 *
 * - 学期 / 开课单位 / 课表行的键，就是 `SchoolCourseApi` 真正读的那些
 *   （`datas.xnxqcx.rows[].DM/MC`、`datas.code.rows[].id/name`、`datas.qxfbkccx.rows[]` 的
 *   `KCH/KCM/KXH/SKJS/KKDWDM_DISPLAY/XF/XS/SKXS/SYXS/SJXS/XKZRS/KRL/SKBJ/YPSJDD/XXXQDM_DISPLAY/`
 *   `SFXGXK/XGXKLBDM_DISPLAY/KNZXS/NSXKRS/NVSXKRS/JXBID/XNXQDM`）；
 *   **刻意留空几个键**（第二、三行）—— 缺字段的默认值（`safeInt/safeDouble` → 0）就是这么钉住的。
 * - 学期列表的**行序就是上游的序**（真实站点按 `*order=-DM` 排好才回）：解析只做映射不排序，
 *   所以夹具按 DM 倒序给，并**校验请求里真带了 `*order=-DM`** —— 少一个断言，「上游排序」这条
 *   口径漂了都没人知道。
 * - 开课单位**故意乱序给**：`getDepartments` 自己按名字排（断言里就是排好的序）。
 * - 成绩报表的页数、会话 id、学期标题与课程行都照 `ScoreReportApi` 的判据写：
 *   `FR.SessionMgr.register('<id>'`、`FR._p.reportTotalPage = <n>`、单列表格行 = 学期标题、
 *   三列行 = 课程（课程名 / 学分 / 成绩），表头与学分行不是数字的行会被跳过。
 *
 * ## 认不出的路径要响亮
 *
 * 路径不认识 → 404，**body 里带着完整 URL**；认识的路径但形体不对（该 POST 的用了 GET、
 * 请求里少了必备字段）→ 400，body 里带着实际收到的样子。URL 一漂就该一眼看出来。
 */
class JwxtFakeUpstream {

    companion object {
        const val HOST = "jwxt.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        /** 登录入口页 —— 与 `XJTULogin.JWXT_URL` 必须逐字相同（有断言钉着）。 */
        const val HOME_URL = "$ORIGIN/jwapp/sys/homeapp/index.do"

        /** 课表查询应用（`SchoolCourseApi` 的 `appBase`）。 */
        const val KCB_CX = "$ORIGIN/jwapp/sys/kcbcx"
        const val CURRENT_TERM_URL = "$KCB_CX/modules/bjkcb/dqxnxq.do"
        const val TERM_LIST_URL = "$KCB_CX/modules/bjkcb/xnxqcx.do"
        const val DEPARTMENTS_URL = "$ORIGIN/jwapp/code/44e02e19-e31b-4916-91b2-0a04380cbd3a.do"
        const val QUERY_URL = "$KCB_CX/modules/qxkcb/qxfbkccx.do"
        const val APP_INDEX_URL = "$KCB_CX/*default/index.do"

        /** 帆软成绩报表。 */
        const val REPORT_URL = "$ORIGIN/jwapp/sys/frReport2/show.do"

        /** 回跳后本站自己的会话 cookie（`JwxtSession.validateLogin` 不看它，只是让回跳像真的）。 */
        const val JWXT_SESSION_VALUE = "JSESSIONID-jwxt-1"

        // ── 学期样本（与 `MC` 的写法一起给：解析要的就是这两个键）──

        const val TERM_NEW = "2026-2027-1"
        const val TERM_NEW_NAME = "2026-2027学年 第一学期"
        const val TERM_OLD = "2025-2026-2"
        const val TERM_OLD_NAME = "2025-2026学年 第二学期"

        /** 第三行**只有 DM**：解析的兜底是「没有 MC 就拿 DM 当名字」，这条就是钉它的。 */
        const val TERM_BARE = "2024-2025-1"

        const val DEPT_PHYSICS = "物理学院"
        const val DEPT_MATH = "数学与统计学院"
        const val DEPT_MECH = "机械工程学院"

        // ── 课表样本 ──

        const val COURSE_1_NAME = "高等数学（上）"
        const val COURSE_2_NAME = "大学物理"
        const val COURSE_3_NAME = "艺术导论"
        const val TOTAL_SIZE = 3

        // ── 成绩报表样本 ──

        const val FR_SESSION_ID = "7391502846"
        const val REPORT_TOTAL_PAGES = 2

        /** 学生的学号（= CAS 那组假账号里的同一个；登录之后 `AccountContext.activeAccountId` 就是它）。 */
        const val STUDENT_ID = LibraryFakeUpstream.USERNAME

        /** 第一页的学期标题 → 学期代码 `2025-2026-1`。 */
        const val SCORE_TERM_SPRING = "2025-2026-1"
        const val SCORE_TERM_SPRING_HEADING = "2025-2026学年 第一学期"
        /** 第二页的学期标题是**夏季小学期**（一个数字都没有）→ `2025-2026-3`。 */
        const val SCORE_TERM_SUMMER = "2025-2026-3"
        const val SCORE_TERM_SUMMER_HEADING = "2025-2026学年 夏季小学期"

        const val SCORE_1_NAME = "高等数学（上）"
        const val SCORE_2_NAME = "大学物理"
        const val SCORE_3_NAME = "工程训练"
    }

    // ── 服务器可变状态：动作打进来时计数/记录，好让断言「这一枪真打到了哪里」──

    /** 没带 ticket、本站也没有会话 cookie 时，把人交给统一认证的次数。 */
    val casRedirects = AtomicInteger()

    /** CAS 回跳（带 ticket）落在本站的次数 —— 用户级「登录真的走完了」。 */
    val ticketLandings = AtomicInteger()

    /** `ensureAppInitialized` 的预热请求打过几次。 */
    val appIndexCalls = AtomicInteger()

    val currentTermCalls = AtomicInteger()
    val termListCalls = AtomicInteger()
    val departmentCalls = AtomicInteger()
    val queryCalls = AtomicInteger()

    /** 成绩报表的翻页请求打过几次（`totalPages=2` ⇒ 一次 `pn=1` + 一次 `pn=2`）。 */
    val reportPageCalls = AtomicInteger()

    /** 最近一次 `xnxqcx.do` 的表单原文（断言 `*order=-DM` 真发出去了）。 */
    @Volatile
    var lastTermListForm: String? = null
        private set

    /** 最近一次 `qxfbkccx.do` 的表单原文（断言 `querySetting` 与分页参数真发出去了）。 */
    @Volatile
    var lastQueryForm: String? = null
        private set

    /** 最近一次报表初始页请求里带的学号（`xh=`）。 */
    @Volatile
    var lastReportStudentId: String? = null
        private set

    /** 最近一次翻页请求里带的会话 id（`sessionID=`），应当等于本夹具发的 [FR_SESSION_ID]。 */
    @Volatile
    var lastReportSessionId: String? = null
        private set

    // ── 样本：页面与 JSON 原文（消费者共用这一份）──────────────────

    /** 登录链最后一跳（CAS 回跳之后）落到的首页。**不能**长得像 CAS 登录页。 */
    val homePage = """
        <html><head><title>西安交通大学教务管理系统</title></head><body>
          <div id="app">本科教务 · 学生端</div>
        </body></html>
    """.trimIndent()

    /** `kcbcx` 应用的首页（预热用，客户端只 close 不解析）。 */
    val appIndexPage = """
        <html><head><title>课程表查询</title></head><body>
          <div id="app">全校课程查询</div>
        </body></html>
    """.trimIndent()

    /** 当前学期：`datas.dqxnxq.rows[0].DM`。 */
    val currentTermJson = """
        {"datas":{"dqxnxq":{"rows":[{"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"}]}}}
    """.trimIndent()

    /** 学期列表：三行，**倒序给**（上游按 `*order=-DM` 排好），第三行只有 DM。 */
    val termListJson = """
        {"datas":{"xnxqcx":{"rows":[
          {"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"},
          {"DM":"$TERM_OLD","MC":"$TERM_OLD_NAME"},
          {"DM":"$TERM_BARE"}
        ]}}}
    """.trimIndent()

    /** 开课单位：**乱序给** —— `getDepartments` 自己按名字排（断言里是排好的序）。 */
    val departmentsJson = """
        {"datas":{"code":{"rows":[
          {"id":"20001000","name":"$DEPT_PHYSICS"},
          {"id":"01001000","name":"$DEPT_MATH"},
          {"id":"10001000","name":"$DEPT_MECH"}
        ]}}}
    """.trimIndent()

    /**
     * 全校课表一页。
     *
     * 第二行缺 `XKZRS`/`SJXS`/`NSXKRS`/`NVSXKRS`、第三行缺 `YPSJDD`/`KNZXS`
     * —— `safeInt/safeDouble` 的默认值（0）与「空串」就是靠这几格钉住的。
     */
    val queryJson = """
        {"datas":{"qxfbkccx":{"totalSize":$TOTAL_SIZE,"rows":[
          {"KCH":"MATH1001","KCM":"$COURSE_1_NAME","KXH":"01","SKJS":"示例甲",
           "KKDWDM_DISPLAY":"$DEPT_MATH","XF":"5.0","XS":"80","SKXS":"80","SYXS":"0","SJXS":"0",
           "XKZRS":"120","KRL":"150","SKBJ":"电气2401-2402",
           "YPSJDD":"兴庆校区 主楼A101 周一 1-2节","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"0","XGXKLBDM_DISPLAY":"","KNZXS":"5.0","NSXKRS":"80","NVSXKRS":"40",
           "JXBID":"JXB-1001","XNXQDM":"$TERM_NEW"},
          {"KCH":"PHYS1002","KCM":"$COURSE_2_NAME","KXH":"02","SKJS":"示例乙",
           "KKDWDM_DISPLAY":"$DEPT_PHYSICS","XF":"4.0","XS":"64","SKXS":"48","SYXS":"16",
           "KRL":"120","SKBJ":"物理2401",
           "YPSJDD":"兴庆校区 中2楼1200 周三 3-4节","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"1","XGXKLBDM_DISPLAY":"基础通识类核心课","KNZXS":"4.0",
           "JXBID":"JXB-1002","XNXQDM":"$TERM_NEW"},
          {"KCH":"GEN1003","KCM":"$COURSE_3_NAME","KXH":"05","SKJS":"示例丙",
           "KKDWDM_DISPLAY":"人文社会科学学院","XF":"2.0","XS":"32","SKXS":"32",
           "XKZRS":"80","KRL":"80","SKBJ":"全校公选","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"1","XGXKLBDM_DISPLAY":"基础通识类选修课",
           "JXBID":"JXB-1003","XNXQDM":"$TERM_NEW"}
        ]}}}
    """.trimIndent()

    /**
     * 成绩报表的初始页：只给会话 id（`extractFrSessionId` 的第一条判据）。
     *
     * ⚠️ **不能**带 `FR._p.reportTotalPage`：初始页没有它，页数只从第一页表格里读
     * （少了这条，`extractTotalPages` 的兜底 1 就永远测不到）。
     */
    val reportInitPage = """
        <html><head><title>学生成绩报表</title>
        <script>FR.SessionMgr.register('$FR_SESSION_ID', {bookID:'bkdsglxjtu/XAJTDX_BDS_CJ.cpt'});</script>
        </head><body><div id="fr-container"></div></body></html>
    """.trimIndent()

    /**
     * 成绩报表第 1 页。
     *
     * 四类行**各来一条**，把 `parseCoursesFromHtml` 的判据全钉住：
     *  1. 两列行（`tds.size < 3`）→ 跳过；
     *  2. 三列但在学期标题**之前** → 跳过（`currentTerm == null`）；
     *  3. 表头（课程名是「课程」）→ 跳过；
     *  4. 学分行不是数字（`—`）→ 跳过。
     * 真正成行的两条：数字成绩（95 → 4.3）与等级制（优秀 → 不参与 GPA ⇒ `gpa == null`）。
     */
    val reportPage1 = """
        <html><body>
        <script>FR._p.reportTotalPage = $REPORT_TOTAL_PAGES;</script>
        <table><tbody>
          <tr><td>第 1 页</td><td>共 $REPORT_TOTAL_PAGES 页</td></tr>
          <tr><td>先修统计</td><td>2.0</td><td>90</td></tr>
          <tr><td>$SCORE_TERM_SPRING_HEADING</td></tr>
          <tr><td>课程</td><td>学分</td><td>成绩</td></tr>
          <tr><td>$SCORE_1_NAME</td><td>5.0</td><td>95</td></tr>
          <tr><td>$SCORE_2_NAME</td><td>4.0</td><td>优秀</td></tr>
          <tr><td>体育</td><td>—</td><td>—</td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    /** 成绩报表第 2 页：换一个学期标题（夏季小学期 → 学期代码 `-3`），再给一条课程。 */
    val reportPage2 = """
        <html><body>
        <table><tbody>
          <tr><td>$SCORE_TERM_SUMMER_HEADING</td></tr>
          <tr><td>$SCORE_3_NAME</td><td>1.0</td><td>88</td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == HOME_PATH -> handleHome(exchange, query)
            // okhttp 会不会把 `*` 转义成 %2A 取决于它的 path 编码集（当前版本是原样发）——
            // 两种都认，免得换版本就 404。
            path == APP_INDEX_PATH || path == APP_INDEX_PATH.replace("*", "%2A") -> {
                appIndexCalls.incrementAndGet()
                respondHtml(exchange, appIndexPage)
            }
            path == CURRENT_TERM_PATH -> requirePost(exchange) {
                currentTermCalls.incrementAndGet()
                respondJson(exchange, currentTermJson)
            }
            path == TERM_LIST_PATH -> requirePost(exchange) {
                lastTermListForm = it
                if (!it.contains("*order=-DM")) {
                    badRequest(exchange, "xnxqcx.do 少了 *order=-DM，收到：$it")
                    return@requirePost
                }
                termListCalls.incrementAndGet()
                respondJson(exchange, termListJson)
            }
            path == DEPARTMENTS_PATH -> requirePost(exchange) {
                departmentCalls.incrementAndGet()
                respondJson(exchange, departmentsJson)
            }
            path == QUERY_PATH -> requirePost(exchange) {
                lastQueryForm = it
                if (!it.contains("querySetting=")) {
                    badRequest(exchange, "qxfbkccx.do 少了 querySetting，收到：$it")
                    return@requirePost
                }
                queryCalls.incrementAndGet()
                respondJson(exchange, queryJson)
            }
            path == REPORT_PATH -> handleReport(exchange, query)
            else -> notFound(exchange)
        }
    }

    /**
     * 登录入口页：带 ticket 或已经握着本站会话 cookie ⇒ 给首页；否则 302 到统一认证
     * （`service` 就是本页地址，真站点也是这么把票据交回来的）。
     */
    private fun handleHome(exchange: HttpExchange, query: String) {
        if ("ticket=" in query) {
            ticketLandings.incrementAndGet()
            exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=$JWXT_SESSION_VALUE; Path=/")
            respondHtml(exchange, homePage)
            return
        }
        if (JWXT_SESSION_VALUE in cookieHeader(exchange)) {
            respondHtml(exchange, homePage)
            return
        }
        casRedirects.incrementAndGet()
        redirect(exchange, "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(HOME_URL)}")
    }

    /** 帆软报表：初始页（`reportlet=`）与翻页（`op=page_content`）两条，认不出就响亮地失败。 */
    private fun handleReport(exchange: HttpExchange, query: String) {
        if ("op=page_content" in query) {
            val pn = param(query, "pn")?.toIntOrNull()
            val sessionId = param(query, "sessionID")
            lastReportSessionId = sessionId
            if (sessionId != FR_SESSION_ID) {
                badRequest(exchange, "翻页请求的 sessionID=$sessionId，夹具发的是 $FR_SESSION_ID")
                return
            }
            val page = when (pn) {
                1 -> reportPage1
                2 -> reportPage2
                else -> {
                    badRequest(exchange, "没有第 $pn 页（夹具只有 $REPORT_TOTAL_PAGES 页）")
                    return
                }
            }
            reportPageCalls.incrementAndGet()
            respondHtml(exchange, page)
            return
        }
        if ("reportlet=" in query) {
            val studentId = param(query, "xh")
            lastReportStudentId = studentId
            if (studentId.isNullOrBlank()) {
                badRequest(exchange, "报表初始页少了 xh=，收到：$query")
                return
            }
            if (!query.contains("bkdsglxjtu/XAJTDX_BDS_CJ.cpt")) {
                badRequest(exchange, "报表初始页的 reportlet 不是成绩报表，收到：$query")
                return
            }
            respondHtml(exchange, reportInitPage)
            return
        }
        badRequest(exchange, "认不出的报表请求：$query")
    }

    // ── 响应的形体检查 ────────────────────────────────────────────

    /** POST 端点：必须真带表单（GET 打过来就 400，免得「方法漂了照样通过」）。 */
    private inline fun requirePost(exchange: HttpExchange, respond: (String) -> Unit) {
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
        respond(exchange, 404, "text/plain; charset=utf-8", "no such jwxt path: $url".toByteArray())
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun readBody(exchange: HttpExchange): String =
        runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    // 路由表与 URL 常量一处定义（免得两边各自漂移）。
    private val HOME_PATH = HOME_URL.removePrefix(ORIGIN)
    private val APP_INDEX_PATH = APP_INDEX_URL.removePrefix(ORIGIN)
    private val CURRENT_TERM_PATH = CURRENT_TERM_URL.removePrefix(ORIGIN)
    private val TERM_LIST_PATH = TERM_LIST_URL.removePrefix(ORIGIN)
    private val DEPARTMENTS_PATH = DEPARTMENTS_URL.removePrefix(ORIGIN)
    private val QUERY_PATH = QUERY_URL.removePrefix(ORIGIN)
    private val REPORT_PATH = REPORT_URL.removePrefix(ORIGIN)
}
