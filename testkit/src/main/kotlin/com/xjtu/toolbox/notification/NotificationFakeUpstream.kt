package com.xjtu.toolbox.notification

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 假的通知公告上游。 */
class NotificationFakeUpstream {

    companion object {
        /** 教务处（JWC）：屏的默认来源 —— 证据图靠它不必点任何筛选。 */
        const val JWC_HOST = "dean.xjtu.edu.cn"

        /** 化工学院（CLET）：拆分日期模板 + 动态挑战。 */
        const val CLET_HOST = "clet.xjtu.edu.cn"

        /** OA 通知：另一族爬虫（表格行 + `gotodetail`）。 */
        const val OA_HOST = "oa.xjtu.edu.cn"

        /** 三个 host 都是 https ⇒ 都进 `FakeCampusProxy.HTTPS_HOSTS`（一枚证书三个 SAN）。 */
        val HOSTS = setOf(JWC_HOST, CLET_HOST, OA_HOST)

        // ── 教务处（JWC）样本：标准 /info/ 列表 ──

        const val JWC_TITLE_1 = "关于 2026 年国庆节放假安排的通知"
        const val JWC_TITLE_2 = "关于 2026 年秋季学期期中教学检查的通知"
        const val JWC_TITLE_3 = "关于做好 2026 年本科生毕业设计选题工作的通知"
        const val JWC_TITLE_4 = "关于 2026 年秋季学期选课工作的通知"
        const val JWC_TITLE_5 = "关于举办第十六届青年教师教学竞赛的通知"
        const val JWC_TITLE_6 = "关于 2026 年国家级大学生创新训练项目申报的通知"

        const val JWC_DATE_1 = "2026-09-25"
        const val JWC_DATE_2 = "2026-09-22"
        const val JWC_DATE_3 = "2026-09-18"
        const val JWC_DATE_4 = "2026-09-15"
        const val JWC_DATE_5 = "2026-09-11"
        const val JWC_DATE_6 = "2026-09-08"

        /** `a > i` 里的标签原文是 `【通知】`，抠出来应当是「通知」（方括号被 trim 掉）。 */
        const val JWC_TAG = "通知"

        /** 第二页的 URL：`span.p_next` 里写的是**倒序编号**（第 2 页 = jxtz2/28.htm，不是 jxtz2/2.htm）。 */
        const val JWC_PAGE2_PATH = "/jxxx/jxtz2/28.htm"

        /** 教务处列表页（= `NotificationSource.JWC.baseUrl` 的路径）。 */
        const val JWC_LIST_PATH = "/jxxx/jxtz2.htm"

        /** 条目链接：页面上写的是**根相对**路径（`/info/…`），所以解析出来就是不带栏目目录的那一条。 */
        const val JWC_LINK_1 = "https://$JWC_HOST/info/1002/9001.htm"
        const val JWC_LINK_2 = "https://$JWC_HOST/info/1002/9002.htm"
        const val JWC_LINK_3 = "https://$JWC_HOST/info/1002/9003.htm"
        // ── 化工学院（CLET）样本：拆分日期模板 ──

        const val CLET_TITLE_1 = "关于 2026 年秋季学期期末考试安排的通知"
        const val CLET_TITLE_2 = "关于 2026 年国庆节放假期间教学安排的通知"
        const val CLET_TITLE_3 = "关于开展 2026 年实验室安全检查的通知"

        /** 页面上写的是 `<span><b>MM/DD</b>YYYY</span>`，拼回来应当是这几个日期。 */
        const val CLET_DATE_1 = "2026-09-20"
        const val CLET_DATE_2 = "2026-09-28"
        const val CLET_DATE_3 = "2026-10-09"

        /** 化工学院列表页（= `NotificationSource.CLET.baseUrl` 的路径）。 */
        const val CLET_LIST_PATH = "/xwgg/tzgg.htm"

        /** 条目链接：这一页写的是相对路径（`info/…`），而 `resolveUrl` 是拿列表页 URL 当基址解的 ⇒ 结果多一层栏目目录（`/xwgg/`）。 */
        const val CLET_LINK_1 = "https://$CLET_HOST/xwgg/info/1012/3456.htm"
        // ── OA 通知样本：表格行 ──

        const val OA_TITLE_1 = "关于 2026 年国庆节放假安排的通知"
        const val OA_TITLE_2 = "关于 2026 年家庭经济困难学生认定的通知"
        const val OA_TITLE_3 = "关于 2026 年研究生国家奖学金评审的通知"
        const val OA_TITLE_4 = "关于 2026 年秋季学期教职工体检安排的通知"
        const val OA_TITLE_5 = "关于 2026 年校园网络设备维护的通知"
        const val OA_TITLE_6 = "关于 2026 年实验室安全培训的通知"

        const val OA_DEPT_1 = "教务处"
        const val OA_DEPT_2 = "学生处"
        const val OA_DEPT_3 = "研究生院"
        const val OA_DEPT_4 = "校医院"
        const val OA_DEPT_5 = "网络信息中心"
        const val OA_DEPT_6 = "实验室与设备管理处"

        const val OA_DATE_1 = "2026-09-19"
        const val OA_DATE_2 = "2026-09-17"
        const val OA_DATE_3 = "2026-09-14"
        const val OA_DATE_4 = "2026-09-11"
        const val OA_DATE_5 = "2026-09-07"
        const val OA_DATE_6 = "2026-09-03"

        /** `gotodetail('…')` 里的流程 id（真站点是一串十六进制）。 */
        const val OA_ID_1 = "402881e5906d1a4f01906d4b3a5a0001"
        const val OA_ID_2 = "402881e5906d1a4f01906d4b3a5a0002"
        const val OA_ID_3 = "402881e5906d1a4f01906d4b3a5a0003"
        const val OA_ID_4 = "402881e5906d1a4f01906d4b3a5a0004"
        const val OA_ID_5 = "402881e5906d1a4f01906d4b3a5a0005"
        const val OA_ID_6 = "402881e5906d1a4f01906d4b3a5a0006"

        /** 详情页（`OaNoticeCrawler` 里的 `DETAIL_URL`）：条目链接就是「它 + `processInsId=<id>`」。 */
        const val OA_DETAIL_URL = "https://oa.xjtu.edu.cn/zxgg_infonew.jsp"

        /** OA 列表页（= `NotificationSource.OA.baseUrl` 的路径）。 */
        const val OA_LIST_PATH = "/zxgg_index.jsp"

        /** 条目链接：详情页 + `processInsId=<gotodetail 里那个 id>`。 */
        const val OA_LINK_1 = "$OA_DETAIL_URL?processInsId=$OA_ID_1"
        const val OA_LINK_2 = "$OA_DETAIL_URL?processInsId=$OA_ID_2"
        const val OA_LINK_3 = "$OA_DETAIL_URL?processInsId=$OA_ID_3"
        // ── 挑战那一路 ──

        /** 挑战 POST 的端点（客户端拼的是 `scheme://host/dynamic_challenge`）。 */
        const val CHALLENGE_PATH = "/dynamic_challenge"

        const val CHALLENGE_A = 17
        const val CHALLENGE_B = 25
        const val CHALLENGE_OPERATOR = "-"

        /** 新版：页面给算式，客户端自己算 ⇒ 17 - 25。 */
        const val CHALLENGE_ANSWER = CHALLENGE_A - CHALLENGE_B

        /** 挑战页里的诱饵：旧版字段与新版摆在同一段脚本里，新版必须优先（不许用 999）。 */
        const val CHALLENGE_DECOY_ANSWER = 999

        /** 通过挑战后服务端签的 `client_id`（客户端之后每个请求都带 `Cookie: client_id=<它>`）。 */
        const val CHALLENGE_CLIENT_ID = "client-id-clet-1"

        // ── 站内检索（博达 CMS 的全文检索表单）──

        /** 首页上的检索表单 action（`SiteSearch.FORM_ACTION_RE` 认的就是这个形状）。 */
        const val SEARCH_ACTION = "search.jsp?wbtreeid=1002"

        const val SEARCH_KEYWORD = "放假"
        const val SEARCH_TITLE_1 = "关于 2026 年国庆节放假安排的通知"
        const val SEARCH_TITLE_2 = "关于 2026 年暑期放假的通知"

        /** 结果行里那个分类标签（`【通知】` ⇒ 抠成「通知」，且要从标题里去掉）。 */
        const val SEARCH_CATEGORY = "通知"
        const val SEARCH_DATE_1 = "2026-09-25"
        const val SEARCH_DATE_2 = "2026-07-10"

        /** 检索结果的条目链接（根相对路径，基址就是 `search.jsp` 那一层）。 */
        const val SEARCH_LINK_1 = "https://$JWC_HOST/info/1002/9005.htm"
        const val SEARCH_LINK_2 = "https://$JWC_HOST/info/1002/9006.htm"

        /** 只写「09-28」没有年份的那一行：没法按时间排 ⇒ 该被丢掉，且算一次 undated。 */
        const val SEARCH_UNDATED_TITLE = "关于 2026 年秋季学期调休安排的通知"

        /** JS 的 `hash = ((hash << 5) - hash) + charCode`（即 `hash * 31 + c`） */
        private fun expectedHash(challengeId: String, answer: Int, userAgent: String): Long {
            var hash = 0
            for (c in "$challengeId$answer${userAgent.take(10)}") hash = hash * 31 + c.code
            return kotlin.math.abs(hash.toLong())
        }

        private val ID_BODY_RE = Regex(""""challenge_id"\s*:\s*"([^"]+)"""")
        private val ANSWER_BODY_RE = Regex(""""answer"\s*:\s*(-?\d+)""")
        private val UA_BODY_RE = Regex(""""userAgent"\s*:\s*"([^"]*)"""")
        private val HASH_BODY_RE = Regex(""""hash"\s*:\s*(\d+)""")
    }

    // ── 服务器可变状态：动作打进来时计数/记录，好让断言「这一枪真打到了哪里」──

    /** 挑战页被发出去几次（带着失效/没有 `client_id` 的那一枪）。 */
    val challengePages = AtomicInteger()

    /** `POST /dynamic_challenge` 收到几次。 */
    val challengePosts = AtomicInteger()

    /** 挑战通过几次（服务端签出 `client_id`）。 */
    val challengeAccepts = AtomicInteger()

    /** 挑战被拒几次（含 [rejectNextChallengePosts] 那次「服务端偶发」）。 */
    val challengeRejections = AtomicInteger()

    /** 测试可以让服务端先拒 [set] 次。 */
    val rejectNextChallengePosts = AtomicInteger()

    /** 每次挑战 POST 的 `challenge_id`（钉「每轮都是新 id」）。 */
    val challengeIdsPosted = CopyOnWriteArrayList<String>()

    /** 最近一次挑战 POST 的 body 原文（字段级断言用）。 */
    val lastChallengeBody = AtomicReference<String?>(null)

    val lastChallengeReferer = AtomicReference<String?>(null)
    val lastChallengeRequestedWith = AtomicReference<String?>(null)
    val lastChallengeContentType = AtomicReference<String?>(null)

    /** `true` 时发**旧版**挑战页（只有 `answer`，没有 `a`/`b`/`operator`）⇒ 走另一种请求体。 */
    val legacyChallenge = AtomicBoolean(false)

    /** 三个 host 各自取了几次列表（钉「谁真被打过」）。 */
    val jwcListCalls = AtomicInteger()
    val jwcPage2Calls = AtomicInteger()
    val cletListCalls = AtomicInteger()
    val oaListCalls = AtomicInteger()

    /** 教务处的首页被取过几次（检索表单就在它上面 —— `SiteSearch.actionFor` 的那一枪）。 */
    val searchFormCalls = AtomicInteger()

    /** 最近一次检索：关键词（Base64 解回来）与页码。 */
    val lastSearchKeyword = AtomicReference<String?>(null)
    val lastSearchPage = AtomicReference<Int?>(null)

    /** 服务端当前认的那枚 `client_id`（只在通过挑战后非空）。 */
    private val clientId = AtomicReference<String?>(null)

    /** 每张挑战页的期望答案（每张一次性，用完即删）。 */
    private val answers = ConcurrentHashMap<String, Int>()
    private val challengeSeq = AtomicInteger()

    /** 客户端手里那枚 `client_id` 是按域名缓存一天的（进程级），而每个用例都新建一个夹具。 */

    // ── 路由 ─────────────────────────────────────────────────────

    /** 教务处：首页（检索表单）/ 列表首页 / 列表第二页 / 检索结果页。 */
    fun handleJwc(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/" -> {
                searchFormCalls.incrementAndGet()
                respondHtml(exchange, searchFormPage())
            }
            path == JWC_LIST_PATH -> {
                jwcListCalls.incrementAndGet()
                respondHtml(exchange, jwcPage1())
            }
            path == JWC_PAGE2_PATH -> {
                jwcPage2Calls.incrementAndGet()
                respondHtml(exchange, jwcPage2())
            }
            path == "/search.jsp" -> handleSearch(exchange)
            else -> notFound(exchange)
        }
    }


    /** 化工学院：挑战页 / 挑战 POST / 列表页。 */
    fun handleClet(exchange: HttpExchange) {
        if (exchange.requestURI.path == CHALLENGE_PATH) {
            if (exchange.requestMethod != "POST") {
                badRequest(exchange, "挑战端点只认 POST，收到 ${exchange.requestMethod}")
                return
            }
            handleChallengePost(exchange)
            return
        }
        if (offeredClientId(exchange) == null) {
            // 没带（或带的是失效的）client_id ⇒ 站点把人拦在挑战页上
            challengePages.incrementAndGet()
            respondHtml(exchange, challengePage())
            return
        }
        cletListCalls.incrementAndGet()
        respondHtml(exchange, cletPage())
    }

    /** OA：列表首页 / `?strPageNo=2`。 */
    fun handleOa(exchange: HttpExchange) {
        if (exchange.requestURI.path != OA_LIST_PATH) {
            notFound(exchange)
            return
        }
        if (exchange.requestMethod != "GET") {
            // 站内检索那一枪是 POST `_ggzt`，这一轮没有夹具 ⇒ 响亮地失败，别假装搜过了
            badRequest(exchange, "OA 的检索（POST _ggzt）这一轮没有夹具，只演列表")
            return
        }
        oaListCalls.incrementAndGet()
        val pageNo = pageQuery(exchange).toIntOrNull() ?: 1
        respondHtml(exchange, if (pageNo <= 1) oaPage1() else oaPage2())
    }

    // ── 页面原文 ─────────────────────────────────────────────────

    /** 教务处首页：只为一个东西而存在。 */
    private fun searchFormPage(): String = """
        <html><head><title>教务处</title></head><body>
          <form name="searchform" method="get" action="/$SEARCH_ACTION">
            <input type="text" name="lucenenewssearchkey" value="" />
            <input type="submit" value="搜索" />
          </form>
          <div class="list_rnr"><ul>
            <li><a href="/info/1002/9001.htm">$JWC_TITLE_1</a><span class="time">$JWC_DATE_1</span></li>
          </ul></div>
        </body></html>
    """.trimIndent()

    /** 教务处列表第一页：标准 `/info/` 条 + `<span class="time">` 完整日期 + `<i>` 标签 + 「下页」。 */
    private fun jwcPage1(): String = """
        <html><head><title>教务处 - 教学通知</title></head><body>
          <div class="list_rnr"><ul>
            <li><a href="/info/1002/9001.htm" title="$JWC_TITLE_1"><p>$JWC_TITLE_1</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_1</span></li>
            <li><a href="/info/1002/9002.htm" title="$JWC_TITLE_2"><p>$JWC_TITLE_2</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_2</span></li>
            <li><a href="/info/1002/9003.htm" title="$JWC_TITLE_3"><p>$JWC_TITLE_3</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_3</span></li>
          </ul></div>
          <div class="page"><span class="p_next"><a href="jxtz2/28.htm">下一页</a></span></div>
        </body></html>
    """.trimIndent()

    /** 教务处列表第二页：没有「下页」⇒ `hasMore = false`。 */
    private fun jwcPage2(): String = """
        <html><head><title>教务处 - 教学通知 第 2 页</title></head><body>
          <div class="list_rnr"><ul>
            <li><a href="/info/1002/8901.htm" title="$JWC_TITLE_4"><p>$JWC_TITLE_4</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_4</span></li>
            <li><a href="/info/1002/8902.htm" title="$JWC_TITLE_5"><p>$JWC_TITLE_5</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_5</span></li>
            <li><a href="/info/1002/8903.htm" title="$JWC_TITLE_6"><p>$JWC_TITLE_6</p><i>【$JWC_TAG】</i></a><span class="time">$JWC_DATE_6</span></li>
          </ul></div>
        </body></html>
    """.trimIndent()

    /** 博达检索的结果页：分类标签在 `<a>` 里、日期在同一个结果行里、第三行只有 `MM-DD`。 */
    private fun searchResultPage(): String = """
        <html><head><title>检索结果</title></head><body>
          <div class="result">
            <div class="line"><a href="/info/1002/9005.htm">【$SEARCH_CATEGORY】$SEARCH_TITLE_1</a><span class="date">$SEARCH_DATE_1</span></div>
            <div class="line"><a href="/info/1002/9006.htm">$SEARCH_TITLE_2</a><span class="date">$SEARCH_DATE_2</span></div>
            <div class="line"><a href="/info/1002/9007.htm">$SEARCH_UNDATED_TITLE</a><span class="date">09-28</span></div>
          </div>
          <div class="page"><a href="/$SEARCH_ACTION&currentnum=2">下一页</a></div>
        </body></html>
    """.trimIndent()

    /** 化工学院列表（CLET 模板） */
    private fun cletPage(): String = """
        <html><head><title>化学工程与技术学院 - 通知公告</title></head><body>
          <div class="list_rlb"><ul>
            <li><a href="info/1012/3456.htm">$CLET_TITLE_1</a><span><b>09/20</b>2026</span></li>
            <li><a href="info/1012/3457.htm">$CLET_TITLE_2</a><span><b>09/28</b>2026</span></li>
            <li><a href="info/1012/3458.htm">$CLET_TITLE_3</a><span><b>10/09</b>2026</span></li>
          </ul></div>
        </body></html>
    """.trimIndent()

    /** OA 第一页：表格行 + `页次: 1/2 页`。 */
    private fun oaPage1(): String = """
        <html><head><title>办公自动化 - 最新公告</title></head><body>
          <table>
            <tr><td class="timedate1">$OA_DEPT_1（$OA_DATE_1）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_1" onclick="gotodetail('$OA_ID_1')">$OA_TITLE_1</a></td></tr>
            <tr><td class="timedate1">$OA_DEPT_2（$OA_DATE_2）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_2" onclick="gotodetail('$OA_ID_2')">$OA_TITLE_2</a></td></tr>
            <tr><td class="timedate1">$OA_DEPT_3（$OA_DATE_3）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_3" onclick="gotodetail('$OA_ID_3')">$OA_TITLE_3</a></td></tr>
          </table>
          <div class="pagemeta">页次: 1/2 页</div>
        </body></html>
    """.trimIndent()

    /** OA 第二页：`页次: 2/2 页` ⇒ `hasMore = false`。 */
    private fun oaPage2(): String = """
        <html><head><title>办公自动化 - 最新公告 第 2 页</title></head><body>
          <table>
            <tr><td class="timedate1">$OA_DEPT_4（$OA_DATE_4）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_4" onclick="gotodetail('$OA_ID_4')">$OA_TITLE_4</a></td></tr>
            <tr><td class="timedate1">$OA_DEPT_5（$OA_DATE_5）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_5" onclick="gotodetail('$OA_ID_5')">$OA_TITLE_5</a></td></tr>
            <tr><td class="timedate1">$OA_DEPT_6（$OA_DATE_6）</td><td><a class="noa_list" href="javascript:void(0)" title="$OA_TITLE_6" onclick="gotodetail('$OA_ID_6')">$OA_TITLE_6</a></td></tr>
          </table>
          <div class="pagemeta">页次: 2/2 页</div>
        </body></html>
    """.trimIndent()

    /** 挑战页：`dynamic_challenge` 这个串 + 一段 JS 常量。 */
    private fun challengePage(): String {
        val id = "demo-challenge-${challengeSeq.incrementAndGet()}"
        answers[id] = CHALLENGE_ANSWER
        val constants = if (legacyChallenge.get()) {
            """
            var challengeId = '$id';
            var answer = $CHALLENGE_ANSWER;
            """.trimIndent()
        } else {
            """
            var answer = $CHALLENGE_DECOY_ANSWER;
            var challengeId = '$id';
            var a = $CHALLENGE_A, b = $CHALLENGE_B, operator = '$CHALLENGE_OPERATOR';
            """.trimIndent()
        }
        return """
            <html><head><title>人机验证</title></head><body>
              <div id="dynamic_challenge">请稍候，正在校验你的浏览器环境，通过后会自动继续。</div>
              <script>
            $constants
              </script>
            </body></html>
        """.trimIndent()
    }

    // ── 挑战 POST ────────────────────────────────────────────────

    /** 挑战那一路的请求形体逐个字段验一遍。 */
    private fun handleChallengePost(exchange: HttpExchange) {
        challengePosts.incrementAndGet()
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        lastChallengeBody.set(body)
        lastChallengeReferer.set(exchange.requestHeaders.getFirst("Referer"))
        lastChallengeRequestedWith.set(exchange.requestHeaders.getFirst("X-Requested-With"))
        lastChallengeContentType.set(exchange.requestHeaders.getFirst("Content-Type"))

        reject("Content-Type 应当是 application/json; charset=utf-8，收到 ${lastChallengeContentType.get()}") {
            lastChallengeContentType.get()?.startsWith("application/json") != true
        }?.let { return respondChallenge(exchange, it) }
        reject("Referer 应当带上是那个列表页，收到 ${lastChallengeReferer.get()}") {
            lastChallengeReferer.get()?.endsWith(CLET_LIST_PATH) != true
        }?.let { return respondChallenge(exchange, it) }
        reject("X-Requested-With 应当是 XMLHttpRequest") {
            lastChallengeRequestedWith.get() != "XMLHttpRequest"
        }?.let { return respondChallenge(exchange, it) }

        val id = ID_BODY_RE.find(body)?.groupValues?.get(1)
            ?: return respondChallenge(exchange, "body 里没有 challenge_id：$body")
        challengeIdsPosted += id
        val expected = answers.remove(id)
            ?: return respondChallenge(exchange, "challenge_id 不认识（或已经用过）：$id")
        val answer = ANSWER_BODY_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()
        if (answer != expected) {
            return respondChallenge(exchange, "answer 应当是 $expected，收到 $answer")
        }
        // 服务端偶发拒绝：真站点会这么抖，客户端为此重取一次页面再解一轮
        if (rejectNextChallengePosts.get() > 0) {
            rejectNextChallengePosts.decrementAndGet()
            // 计数在 respondChallenge 里加（这一条也是「被拒」的一种）
            return respondChallenge(exchange, "Invalid challenge")
        }
        val ua = UA_BODY_RE.find(body)?.groupValues?.get(1)
        if (legacyChallenge.get()) {
            if (ua.isNullOrBlank() || !body.contains("\"cookieEnabled\":true") ||
                !body.contains("\"deviceMemory\":8") || !body.contains("\"hardwareConcurrency\":4") ||
                !body.contains("\"timezone\":\"Asia/Shanghai\"")
            ) {
                return respondChallenge(exchange, "旧版挑战的 browser_info 少了字段：$body")
            }
        } else {
            val hash = HASH_BODY_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()
            if (ua.isNullOrBlank() || hash == null) {
                return respondChallenge(exchange, "新版挑战的 body 少了 userAgent 或 hash：$body")
            }
            val want = expectedHash(id, expected, ua)
            if (hash != want) {
                return respondChallenge(exchange, "hash 应当是 $want（challengeId+answer+UA 前 10 位），收到 $hash")
            }
            // 新版还有那一套 browser_info：screen / timezoneOffset / hasTouchEvents
            if (!body.contains("\"timezoneOffset\":-480") || !body.contains("\"hasTouchEvents\":false") ||
                !body.contains("\"colorDepth\":24")
            ) {
                return respondChallenge(exchange, "新版挑战的 browser_info 少了字段：$body")
            }
        }
        clientId.set(CHALLENGE_CLIENT_ID)
        challengeAccepts.incrementAndGet()
        respondJson(
            exchange,
            """{"success":true,"client_id":"$CHALLENGE_CLIENT_ID"}""",
        )
    }

    /** 把「理由」说进响应里（客户端会把它打进日志），而不是静默地不给 `client_id`。 */
    private fun respondChallenge(exchange: HttpExchange, reason: String) {
        challengeRejections.incrementAndGet()
        respondJson(exchange, """{"success":false,"message":"$reason"}""")
    }

    private inline fun reject(reason: String, predicate: () -> Boolean): String? =
        if (predicate()) reason else null

    // ── 检索 ─────────────────────────────────────────────────────

    /** 检索结果：`newskeycode2` 是关键词 Base64 后再 URL 编码（客户端就是这么发的） */
    private fun handleSearch(exchange: HttpExchange) {
        val query = exchange.requestURI.query.orEmpty()
        val raw = query.split('&').firstOrNull { it.startsWith("newskeycode2=") }?.substringAfter("newskeycode2=")
        lastSearchKeyword.set(raw?.let(::decodeKeyword))
        lastSearchPage.set(
            query.split('&').firstOrNull { it.startsWith("currentnum=") }?.substringAfter("currentnum=")?.toIntOrNull(),
        )
        respondHtml(exchange, searchResultPage())
    }

    // ── 请求侧小工具 ─────────────────────────────────────────────

    /** 请求上带的 `client_id` —— 只有和服务端手里那枚**相等**才算通过过挑战。 */
    private fun offeredClientId(exchange: HttpExchange): String? {
        val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
        val value = cookie.split(';')
            .firstOrNull { it.trim().startsWith("client_id=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return value?.takeIf { it == clientId.get() }
    }

    /** `newskeycode2` 解回来 —— 关键词是 Base64 之后再 URL 编码发的。 */
    private fun decodeKeyword(raw: String): String? {
        val candidates = listOfNotNull(
            raw,
            runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrNull(),
        )
        for (candidate in candidates) {
            runCatching { String(Base64.getDecoder().decode(candidate), StandardCharsets.UTF_8) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun pageQuery(exchange: HttpExchange): String =
        exchange.requestURI.query.orEmpty()
            .split('&')
            .firstOrNull { it.startsWith("strPageNo=") }
            ?.substringAfter('=') ?: "1"

    // ── 响应小工具 ───────────────────────────────────────────────

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun badRequest(exchange: HttpExchange, reason: String) {
        respond(exchange, 400, "text/plain; charset=utf-8", reason.toByteArray(StandardCharsets.UTF_8))
    }

    private fun notFound(exchange: HttpExchange) {
        val url = exchange.requestURI.toString()
        respond(
            exchange,
            404,
            "text/plain; charset=utf-8",
            "no such notification path: $url".toByteArray(StandardCharsets.UTF_8),
        )
    }
}
