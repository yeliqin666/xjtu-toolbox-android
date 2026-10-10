package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「图书馆座位系统（可选：再扮演统一认证）」上游 —— 三个消费者**共用同一批页面原文**。
 *
 * ## 它为什么在 `:testkit`，而不是某个模块的 test 源集里
 *
 * 它曾经住在 `:data:jvmTest`。搬到独立模块的**唯一**原因是：桌面端的离屏证据
 *（`renderScreens` 要画出「用真会话登进去的图书馆」那张图）也必须看到同一批页面，
 * 而 Gradle 跨模块共享不了 test 源集（`java-test-fixtures` 对 KMP 不生效）。
 * 于是它成了一条谁都不会依赖进生产构件的编译单元 —— 详情见 `testkit/build.gradle.kts` 的 KDoc。
 * 两份夹具都只用 JDK（`com.sun.net.httpserver` / `java.security`），所以搬动零成本。
 *
 * ## 为什么是一个共享夹具
 *
 * - [LibraryDataLayerJvmTest]（Stage 0）：**直连会话**（`DirectLibrarySession`，没有登录内核），
 *   用 okhttp 拦截器把 `rg.lib.xjtu.edu.cn` 重写到本地端口，验的是「同一份数据层在 JVM 上真跑」。
 * - `LibraryLoginSessionJvmTest`（Stage A）：**真会话**（`SessionManager` + `SiteSession` +
 *   `XJTULogin` 真登录），用本地 HTTP 代理保住 URL 不变，验的是「数据层长了它自己的登录会话」。
 * - `:desktop` 的 `DesktopAuthLibraryJvmTest` / `renderScreens`（Stage A 第二步）：驱动的是
 *   **桌面端自己的装配**（`DesktopAuth` + `DesktopLibrarySource`）—— 验的是「用输入的凭据登进去
 *   之后，桌面端那份取数在这份会话上真读得出座位」。
 *
 * 三者的差别**只在传输、会话与装配**，页面/接口形状必须一模一样 —— 否则「同一批断言」这句话就不成立。
 * 所以夹具抽到这里：字符串只有一份，改一处三处一起动。
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

        /**
         * 统一认证签发密码加密用的 RSA 公钥。
         *
         * 真站点上这条路是 **https**（`XJTULogin.fetchRsaPublicKeyFromServer` 硬编码的
         * `https://login.xjtu.edu.cn/cas/jwt/publicKey`）。绝大多数站点根本不走它
         *（登录器把会话管家缓存的公钥传进来），但 `CampusCardLogin` 是唯一不收 `cachedRsaKey` 的那一个
         *（见 `LibraryLogin` 的 KDoc），所以校园卡这条链每次登录都会真的取一次 ——
         * 夹具因此也得能回答它：`FakeCampusProxy.HTTPS_HOSTS` 里有 [CAS_HOST]，
         * 这一条路由给的正是 [TestRsaKey.publicKeyBase64]（与客户端那份同一个密钥对）。
         */
        const val PUBLIC_KEY_PATH = "/cas/jwt/publicKey"

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

    /** `GET /cas/jwt/publicKey` 被打了几次（只有不收 `cachedRsaKey` 的那一两个站点会走它）。 */
    val publicKeyCalls = AtomicInteger(0)

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

    /**
     * 平面图**底图**（`区域码.jpg`）。
     *
     * 以前这里只有 8 个字节的 JPEG 文件头 —— `getPlanImage` 只认文件头、不解码，够用。
     * Stage A 第二步加了一条「桌面端真登进去」的离屏证据，而平面图那一屏要把字节**解码**出来
     *（`decodePlanImages` → skiko 的 `Image.makeFromEncoded`）⇒ 假数据立刻露馅：
     * 屏上只剩一句「平面图解码失败」，看上去像个 bug。所以改成用 ImageIO 真画一张 JPEG。
     *
     * 尺寸必须比 [qseatuistJson] 里的矩形容得下（那里最大到 50+30 / 20+40），而且与
     * [tileJpegBytes] **同比例** —— `decodePlanImages` 会拿比例筛掉尺寸对不上的状态图。
     */
    val jpegBytes: ByteArray = planJpeg(background = 0xE8EEF7, gridLine = 0x9AB4D8)

    /** 平面图**状态贴图**（`-book` / `-inside` / `-leave` / `blanket`）。同尺寸、同比例；颜色不同好在图上认得出。 */
    val tileJpegBytes: ByteArray = planJpeg(background = 0xFFF3D6, gridLine = 0xE0B860)

    /**
     * 画一张 [w]×[h] 的 JPEG：平淡底色 + 网格 + 边框。
     *
     * 刻意不用外部图片资源：夹具的整份页面原文都是**代码里的字符串**（改一处所有消费者一起动），
     * 图片走同一套。AWT 的 `ImageIO` 在 headless JVM 下也能用（`:data:jvmTest` 与桌面端的测试
     * 都是 headless）。
     */
    private fun planJpeg(background: Int, gridLine: Int): ByteArray {
        val image = java.awt.image.BufferedImage(200, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(background)
        g.fillRect(0, 0, 200, 300)
        g.color = java.awt.Color(gridLine)
        for (x in 0..200 step 20) g.drawLine(x, 0, x, 300)
        for (y in 0..300 step 20) g.drawLine(0, y, 200, y)
        g.drawRect(0, 0, 199, 299)
        g.dispose()
        return java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(image, "jpg", it)
        }.toByteArray()
    }

    /** 这些文件名存在（其余一律「查不到」—— 那条「不是图片就返回 null」的断言靠它）。 */
    private fun planImageBytes(name: String): ByteArray? = when {
        name == "north2east.jpg" -> jpegBytes
        name == "blanket.jpg" -> tileJpegBytes
        name.endsWith("-book.jpg") || name.endsWith("-inside.jpg") || name.endsWith("-leave.jpg") -> tileJpegBytes
        else -> null
    }

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
            casEnabled && path == PUBLIC_KEY_PATH -> {
                publicKeyCalls.incrementAndGet()
                respond(exchange, 200, "text/plain; charset=utf-8", TestRsaKey.publicKeyBase64.toByteArray())
            }

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
            // 存在的那几张图分别回真 JPEG；其余（含错误页）走 HTML/404，
            // 好让「不是图片就返回 null」那条断言继续有意义
            path.startsWith("/static/images/ui10/") -> {
                val bytes = planImageBytes(path.substringAfterLast('/'))
                if (bytes != null) respond(exchange, 200, "image/jpeg", bytes)
                else respondHtml(exchange, "<html><body>not found</body></html>")
            }
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
            redirect(exchange, serviceWithTicket(service, ticketFor(service)))
        } else {
            respondHtml(exchange, casLoginPage)
        }
    }

    /**
     * 把票据交回去：**塞进 query**，不能拼在字符串末尾。
     *
     * 一网通办那个 `service` 自己带一个 `#/Index` 片段（见 `YwtbLogin.YWTB_LOGIN_URL` 里那个 `path`），
     * 拼在末尾的话票据会落进**片段**里 —— 客户端读的是 `response.request.url.queryParameter("ticket")`，
     * 于是什么也读不到（实测：`YwtbLogin` 报「无法获取 YWTB ticket」）。真 CAS 也是把 ticket 放进
     * query 的（片段照旧留在最后）。
     */
    private fun serviceWithTicket(service: String, ticket: String): String {
        val hash = service.indexOf('#')
        val head = if (hash >= 0) service.substring(0, hash) else service
        val fragment = if (hash >= 0) service.substring(hash) else ""
        val separator = if ('?' in head) "&" else "?"
        return "$head$separator" + "ticket=$ticket" + fragment
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
        redirect(exchange, serviceWithTicket(service, ticketFor(service)))
    }

    /**
     * 签一张回跳票据。绝大多数站点拿到的是 `ST-n` 那种不透明票据；**一网通办例外**：
     * `YwtbLogin.postLogin` 要从 `ticket=` 里解出一枚 JWT 的 payload（`idToken`），
     * 真站点发的就是那个形状 ⇒ 夹具按 `service` 分得清，照真实形状签一枚。
     *
     * 为什么在这里而不是新开一个 host 的夹具：`login.xjtu.edu.cn` 是**所有站点共用**的一台 CAS，
     * 按 host 分派的话它只能属于一个夹具（见 `FakeCampusProxy`）；一网通办那半台（门户与四路取数）
     * 在 `YwtbFakeUpstream` 里。
     */
    private fun ticketFor(service: String): String {
        val n = tickets.incrementAndGet()
        if (YwtbFakeUpstream.HOST !in service) return "ST-$n"
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"idToken":"${YwtbFakeUpstream.ID_TOKEN}","sub":"$USERNAME"}""".toByteArray())
        // 头一个 base64url 的 header、签名字段随便给（本端不验签，真站点也不由本端验）
        return "eyJhbGciOiJSUzI1NiJ9.$payload.sig"
    }

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
