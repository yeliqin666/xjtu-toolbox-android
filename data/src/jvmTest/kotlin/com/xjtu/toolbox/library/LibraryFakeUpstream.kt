package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「图书馆座位系统（可选：再扮演统一认证）」上游 —— 两条 JVM 测试**共用同一批页面原文**。
 *
 * ## 为什么是一个共享夹具
 *
 * - [LibraryDataLayerJvmTest]（Stage 0）：**直连会话**（`DirectLibrarySession`，没有登录内核），
 *   用 okhttp 拦截器把 `rg.lib.xjtu.edu.cn` 重写到本地端口，验的是「同一份数据层在 JVM 上真跑」。
 * - `LibraryLoginSessionJvmTest`（Stage A）：**真会话**（`SessionManager` + `SiteSession` +
 *   `XJTULogin` 真登录），用本地 HTTP 代理保住 URL 不变，验的是「数据层长了它自己的登录会话」。
 *
 * 两条测试的差别**只在传输与会话**，页面/接口形状必须一模一样 —— 否则「同一批断言」这句话就不成立。
 * 所以夹具抽到这里：字符串只有一份，改一处两条测试一起动。
 *
 * ## 两种传输怎么共用它
 *
 * 夹具只认 `path`/`query`/`Cookie`，不关心请求怎么到达这里；页面上需要绝对地址的地方（如 302 的
 * `Location`）用构造参数 [base] 拼：
 * - 直连模式：[base] = 本地 `http://127.0.0.1:<port>`（拦截器已经把 URL 换掉了）；
 * - 代理模式：[base] = `http://rg.lib.xjtu.edu.cn:8086`（URL 保持原样，`Location` 也必须是原域名）。
 *
 * ## [casEnabled]
 *
 * 打开后多出 `/cas/login` 两条路由，并且 `/seat/` 不带 `?ticket=` 时按真实站点那样 302 到统一认证。
 * 直连那条测试不开（它没有会话内核，走不到 CAS）。
 */
class LibraryFakeUpstream(
    /** 页面上绝对地址用的 base（见类 KDoc：直连模式给本地地址，代理模式给真实域名）。 */
    private val base: String,
    /** 是否同时扮演统一认证（真登录那条测试要）。 */
    private val casEnabled: Boolean = false,
) {

    companion object {
        const val LIBRARY_HOST = "rg.lib.xjtu.edu.cn"

        /** 座位系统真实 base —— 生产代码里 `LibraryPages.BASE_URL` 就是它。 */
        const val LIBRARY_BASE = "http://$LIBRARY_HOST:8086"

        /** 统一认证主机名 —— `XJTULogin.casPath` 只认这个 host（端口不计）。 */
        const val CAS_HOST = "login.xjtu.edu.cn"

        const val USERNAME = "2021000001"
        const val PASSWORD = "correct-horse-battery"
        const val TGC_VALUE = "TGC-fake-1"
        const val LIBRARY_SESSION_VALUE = "JSESSIONID-fake-1"

        /** 统一认证登录页上的 `execution`。 */
        private const val CAS_EXECUTION = "e1s1"
    }

    // ── 服务器可变状态：动作打进来时翻一下，好让「动作后复核」有东西可查 ──

    val swapped = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)

    /** 只要为真，`/qspace` 就回登录页（「会话失效」那条测试用）。 */
    val loginMode = AtomicBoolean(false)

    /** 一次性失效：下一个业务请求回登录页，随后自动复位（「内核自动重登 + 重放」那条测试用）。 */
    private val expireOnce = AtomicBoolean(false)

    /** CAS：真提交过几次凭据（`>0` 说明确实走过表单 POST，而不是只靠 SSO / 快照）。 */
    val credentialPosts = AtomicInteger(0)

    /** CAS：发出过几个 ticket。 */
    val tickets = AtomicInteger(0)

    /** 最近一次凭据 POST 提交上来的用户名 / 解密后的密码（没提交过则为 null）。 */
    @Volatile var lastPostedUsername: String? = null
        private set

    @Volatile var lastPostedPassword: String? = null
        private set

    fun expireNextBusinessRequest() {
        expireOnce.set(true)
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
        respond(exchange, 200, "application/json", body.toByteArray())

    private fun redirect(exchange: HttpExchange, location: String, cookie: String? = null) {
        if (cookie != null) exchange.responseHeaders.add("Set-Cookie", cookie)
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    /** 302 到「我的预约」页：`bookSeat` 正是按「最终落到 /my/」判成功的。 */
    private fun redirectToMy(exchange: HttpExchange) = redirect(exchange, "$base/my/")

    // ── 夹具：与学校页面同形状 ─────────────────────────────────────

    /** `/modify`：账号资料页，校区是 `select#rplace` 的选中项。 */
    val modifyPage = """
        <html><body><form>
          <input name="csrf_token" value="tok-1">
          <input id="email" name="email" value="a@b.c">
          <input id="tel" name="tel" value="13800000000">
          <select id="rplace" name="rplace">
            <option value="east" selected>兴庆</option>
            <option value="west">雁塔</option>
          </select>
        </form></body></html>
    """.trimIndent()

    /** `/qspace`：区域名（`sp`）+ 空座统计（`scount`）。 */
    val qspaceJson = """
        {"sp":{"north2east":"北楼二层外文库（东）","north2west":"北楼二层外文库（西）"},
         "scount":{"north2east":[24,9],"north2west":[18,2]}}
    """.trimIndent()

    /** `/qseat`：`seat` 里 0 = 空位，非 0 = 被占。 */
    val qseatJson = """
        {"scount":{"north2east":[24,9]},
         "seat":{"A10":0,"A02":1,"A01":0}}
    """.trimIndent()

    /** `/qseatuist`：五个元素的数组，第 5 个是状态码（2 = 空闲）。坐标是字符串。 */
    val qseatuistJson = """
        {"A01":["10","20","30","40","2"],"cancel":["0","0","0","0","2"],"A02":["50","20","30","40","0"]}
    """.trimIndent()

    /** 一张最小的 JPEG 头（`getPlanImage` 只认文件头，不解码）。 */
    val jpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46
    )

    /** 「我的预约」：`div.well` 是当前预约、`div.notwell` 是历史。座位行是第一个 `<hr>` 的尾随文本。 */
    fun myPage(seat: String): String = """
        <html><body>
        <div class="well"><div class="row">
          <div class="col-md-9 cta-contents"><h3 class="cta-title">业务类型:&nbsp;预约座位<br>
          <hr>北楼二层外文库（东）&nbsp;$seat
          <h4><a href="/updateseat">我想换座</a></h4></div>
          <div class="col-md-3 cta-button"><center><h4>预约状态:</h4><h3>已预约</h3>
          <script>showConfirmModal('确认', 'cancel', '88')</script>
          <script>showConfirmModal('确认', 'ruguan1', '88')</script>
          </center></div>
        </div></div>
        </body></html>
    """.trimIndent()

    val noBookingPage = """
        <html><body><div class="notwell">暂无预约记录</div></body></html>
    """.trimIndent()

    /** 座位系统的一页（`LibraryLogin.looksLikeSeatPage` 靠这几个词认出「已经进站了」）。 */
    val seatPage = """
        <html><head><title>座位预约</title></head><body>
          <div class="qseat">座位预约系统</div>
          <div class="seat tab-select btn-group"></div>
        </body></html>
    """.trimIndent()

    /**
     * 统一认证登录页，**形状照真页**：`id="fm1"` + `execution` 是 CAS 的表单判据
     * （`CasLoginPages`），`name="execution"` 是 `XJTULogin` 提取防 CSRF 字段的判据，
     * `mfaEnabled: false` 让状态机跳过短信检测（`XJTULogin.extractMfaEnabled`）。
     */
    val casLoginPage = """
        <html><head><title>统一身份认证</title></head><body>
        <form id="fm1" action="/cas/login" method="post">
          <input id="username" name="username" type="text" value="">
          <input id="password" name="password" type="password" value="">
          <input type="hidden" name="execution" value="$CAS_EXECUTION">
          <input type="hidden" name="_eventId" value="submit">
        </form>
        <script>var globalConfig = {"mfaEnabled": false};</script>
        </body></html>
    """.trimIndent()

    /** 一个「被重定向到统一身份认证」的响应体：`id="loginForm"` 就足够让本端判成会话失效。 */
    val loginPage = """<html><body><form id="loginForm" action="/cas/login"></form></body></html>"""

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            casEnabled && path == "/cas/login" && exchange.requestMethod == "POST" -> handleCasPost(exchange, query)
            casEnabled && path == "/cas/login" -> handleCasGet(exchange, query, cookieHeader(exchange))

            path == "/modify" -> respondHtml(exchange, modifyPage)
            // 会话失效那两条路：一次性失效优先（内核重登 + 重放），其次是一直失效（判据那条）
            path == "/qspace" -> when {
                expireOnce.getAndSet(false) -> {
                    // 业务站把这一枪拦回统一认证时，会顺手作废它自己那枚会话 cookie
                    // （Max-Age=0 ⇒ okhttp 的 cookie jar 会把同名同 path 的那条删掉）。
                    // 这样「重认证」就必须真的重走一趟 CAS —— 有 TGC 仍然免密，但会签新 ticket。
                    exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0")
                    respondHtml(exchange, casLoginPage)
                }
                loginMode.get() -> respondHtml(exchange, loginPage)
                else -> respondJson(exchange, qspaceJson)
            }
            path == "/qseat" -> respondJson(exchange, qseatJson)
            path == "/qseatuist" -> respondJson(exchange, qseatuistJson)

            path == "/seat/" -> when {
                !casEnabled -> redirectToMy(exchange)
                "ticket=" in query -> {
                    // CAS 回跳：真站点在这里种下它自己的会话 cookie，并把页面给出来。
                    // ⚠️ 不能再 302 回 /seat/（那样会少了 ticket 又被拦回 CAS，转圈到
                    // OkHttp 的「Too many follow-up requests」）。
                    exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=$LIBRARY_SESSION_VALUE; Path=/")
                    respondHtml(exchange, seatPage)
                }
                "kid=" in query -> redirectToMy(exchange)
                // 已进站（有本站会话 cookie）：直接给页面 —— validateLogin 走的就是这一枪
                LIBRARY_SESSION_VALUE in cookieHeader(exchange) -> respondHtml(exchange, seatPage)
                else -> redirectToCas(exchange)
            }
            path == "/updateseat/" -> {
                swapped.set(true)
                respondHtml(exchange, "<html><body>已换座</body></html>")
            }
            path == "/my/" && "cancel=1" in query -> {
                cancelled.set(true)
                respondHtml(exchange, "<html><body><div class='alert'>已取消</div></body></html>")
            }
            path == "/my/" -> respondHtml(
                exchange,
                if (cancelled.get()) noBookingPage else myPage(if (swapped.get()) "A02" else "A01"),
            )
            // 只有那一张图存在；其余（含错误页）走 404，好让「不是图片就返回 null」那条断言有意义
            path.endsWith("/north2east.jpg") -> respond(exchange, 200, "image/jpeg", jpegBytes)
            path.startsWith("/static/images/ui10/") -> respondHtml(exchange, "<html><body>not found</body></html>")
            else -> respond(exchange, 404, "text/plain", "nope".toByteArray())
        }
    }

    /** 业务站把人拦到统一认证：真实站点就是这么 302 的（带 `service=`）。 */
    private fun redirectToCas(exchange: HttpExchange) {
        val service = "$base/seat/"
        redirect(exchange, "http://$CAS_HOST/cas/login?service=${encode(service)}")
    }

    /**
     * 统一认证入口：带 TGC 就直接签 ticket 回跳（SSO），否则给登录表单。
     * 这条分支让「重认证」那条路走的是**同一条真实链路**（有 TGC 免密，没 TGC 才提交密码）。
     */
    private fun handleCasGet(exchange: HttpExchange, query: String, cookie: String) {
        val service = param(query, "service") ?: "$base/seat/"
        if ("TGC=$TGC_VALUE" in cookie) {
            redirect(exchange, "$service" + (if ("?" in service) "&" else "?") + "ticket=ST-${tickets.incrementAndGet()}")
        } else {
            respondHtml(exchange, casLoginPage)
        }
    }

    /** 凭据表单：认用户名 + RSA 解出的密码，种 TGC，签 ticket 回跳业务站。 */
    private fun handleCasPost(exchange: HttpExchange, query: String) {
        val form = exchange.requestBody.readBytes().decodeToString()
        val username = param(form, "username")
        val encrypted = param(form, "password").orEmpty()
        val password = TestRsaKey.decrypt(encrypted)
        if (username != USERNAME || password != PASSWORD) {
            respondHtml(exchange, "<html><body><div class=\"alert-danger\">用户名或密码错误</div></body></html>")
            return
        }
        lastPostedUsername = username
        lastPostedPassword = password
        credentialPosts.incrementAndGet()
        val service = param(query, "service") ?: "$base/seat/"
        exchange.responseHeaders.add("Set-Cookie", "TGC=$TGC_VALUE; Path=/")
        redirect(exchange, "$service" + (if ("?" in service) "&" else "?") + "ticket=ST-${tickets.incrementAndGet()}")
    }

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
