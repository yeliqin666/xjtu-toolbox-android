package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 假的「图书馆座位系统（可选：再扮演统一认证）」上游 —— 三个消费者共用同一批页面原文。 */
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

        /** 统一认证签发密码加密用的 RSA 公钥。 */
        const val PUBLIC_KEY_PATH = "/cas/jwt/publicKey"

        /** CAS 的 OAuth2 授权入口（`/cas/oauth2.0/authorize?client_id=…&redirect_uri=…`）。 */
        const val OAUTH_AUTHORIZE_PATH = "/cas/oauth2.0/authorize"

        const val USERNAME = "2021000001"
        const val PASSWORD = "correct-horse-battery"
        const val TGC_VALUE = "TGC-fake-1"
        const val LIBRARY_SESSION_VALUE = "JSESSIONID-fake-1"

        /** 统一认证登录页上的 `execution`。 */
        private const val CAS_EXECUTION = "e1s1"


        /** 取绑定手机号（`MFAContext.getPhoneNumber`）：`state` 就是二次认证页上的 `secState`。 */
        const val MFA_PHONE_PATH = "/cas/sec/initByType/securephone"

        /** 校验短信验证码（`MFAContext.verifyCode`）。 */
        const val MFA_VERIFY_PATH = "/attest/api/guard/securephone/valid"

        /** 验证码的样本值：**只有它会被接受**，其余一律按服务端拒绝回（`code != 0`）。 */
        const val MFA_CODE = "123456"

        /** 手机号一次验证流程里的 `gid`（`verifyCode` 要原样带回）。 */
        const val MFA_GID = "gid-1"

        /** 返回的手机号：真站点也是中间四位屏蔽（所以它是**可投影给界面**的那个形状）。 */
        const val MFA_PHONE_MASKED = "138****0000"
    }

    // ── 服务器可变状态：动作打进来时翻一下，好让「动作后复核」有东西可查 ──

    val swapped = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)

    // ── 写路径剧本：默认全通，下面的开关把某一条支路掰到失败 ──

    /** 座位已被他人占：`/seat/?kid=` 回一个带提示的页面，`bookSeat` 的失败分支就是从它读原因。 */
    val seatTaken = AtomicBoolean(false)

    /** 签过到了（`/my/?firstruguan=1`）：页面上「入馆签到」消失、改渲染「中途离开」。 */
    val signedIn = AtomicBoolean(false)

    /** 正处在「中途离开」状态（`/my/?midleave=1` 之后、`/my/?midreturn=1` 之前）。 */
    val away = AtomicBoolean(false)

    /** 账号资料里的校区（`select#rplace` 的选中项）；`POST /modify` 会改它。 */
    @Volatile
    var currentRplace: String = "east"

    /** 最近一次 `POST /modify` 提交上来的 `rplace`（切校区那一枪真的发出去了没有）。 */
    @Volatile
    var lastPostedRplace: String? = null
        private set

    /** 只要为真，`/qspace` 就回登录页（「会话失效」那条测试用）。 */
    val loginMode = AtomicBoolean(false)

    /** 一次性失效：下一个业务请求回登录页，随后自动复位（「内核自动重登 + 重放」那条测试用）。 */
    private val expireOnce = AtomicBoolean(false)

    /** CAS：真提交过几次凭据（`>0` 说明确实走过表单 POST，而不是只靠 SSO / 快照）。 */
    val credentialPosts = AtomicInteger(0)

    /** CAS：发出过几个 ticket。 */
    val tickets = AtomicInteger(0)

    /** CAS：OAuth2 授权入口签发过几个 `code=OC-…`（走 OAuth2 登录链的子系统用）。 */
    val oauthCodes = AtomicInteger(0)

    /** `GET /cas/jwt/publicKey` 被打了几次（只有不收 `cachedRsaKey` 的那一两个站点会走它）。 */
    val publicKeyCalls = AtomicInteger(0)

    /** 二次认证：取手机号那一枪打过几次（`MFAContext.getPhoneNumber`）。 */
    val mfaPhoneCalls = AtomicInteger(0)

    /** 二次认证：校验验证码那一枪打过几次（`MFAContext.verifyCode`）—— 错码也算（那是真打到服务端了）。 */
    val mfaVerifyCalls = AtomicInteger(0)

    /** 二次认证剧本的开关：开上之后，凭据 POST 的落点不是带 ticket 的回跳，而是 [CasSafetyVerifyPage]。 */
    val requireSafetyVerify = AtomicBoolean(false)

    /** 二次认证页被给出去几次（= 密码对之后真被拦到了那一页）。 */
    val safetyVerifyLandings = AtomicInteger()

    /** 二次认证页的隐藏表单被回提了几次（= 验证码真过了、`MfaContext.verifyCode` 走到了最后一步）。 */
    val safetyVerifySubmissions = AtomicInteger()

    /** 最近一次回提的表单原文（断言 `secState`/`execution`/`_eventId`/`submit`/`fpVisitorId` 真发出去了）。 */
    @Volatile
    var lastSafetyVerifyForm: String? = null
        private set

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

    /** `POST /modify`（切校区） */
    private fun handleModifyPost(exchange: HttpExchange) {
        val form = exchange.requestBody.readBytes().decodeToString()
        if (param(form, "csrf_token").isNullOrBlank()) {
            badRequest(exchange, "切校区要带 csrf_token（收到：$form）")
            return
        }
        val rplace = param(form, "rplace").orEmpty()
        if (rplace.isBlank()) {
            badRequest(exchange, "切校区要带 rplace（收到：$form）")
            return
        }
        lastPostedRplace = rplace
        currentRplace = rplace
        respondHtml(exchange, modifyPage)
    }

    // ── 夹具：与学校页面同形状 ─────────────────────────────────────

    /** `/modify`：账号资料页，校区是 `select#rplace` 的选中项。 */
    /** `/modify`：账号资料页，校区是 `select#rplace` 的选中项（[currentRplace]，`POST /modify` 会改它）。 */
    val modifyPage: String
        get() = """
        <html><body><form>
          <input name="csrf_token" value="tok-1">
          <input id="email" name="email" value="a@b.c">
          <input id="tel" name="tel" value="13800000000">
          <select id="rplace" name="rplace">
            <option value="east"${if (currentRplace == "east") " selected" else ""}>兴庆</option>
            <option value="west"${if (currentRplace == "west") " selected" else ""}>雁塔</option>
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

    /** 平面图底图（`区域码.jpg`）。 */
    val jpegBytes: ByteArray = planJpeg(background = 0xE8EEF7, gridLine = 0x9AB4D8)

    /** 平面图**状态贴图**（`-book` / `-inside` / `-leave` / `blanket`）。同尺寸、同比例；颜色不同好在图上认得出。 */
    val tileJpegBytes: ByteArray = planJpeg(background = 0xFFF3D6, gridLine = 0xE0B860)

    /** 画一张 [w]×[h] 的 JPEG：平淡底色 + 网格 + 边框。 */
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

    /** 「我的预约」：`div.well` 是当前预约、`div.notwell` 是历史。 */
    fun myPage(seat: String): String = """
        <html><body>
        <div class="well"><div class="row">
          <div class="col-md-9 cta-contents"><h3 class="cta-title">业务类型:&nbsp;预约座位<br>
          <hr>北楼二层外文库（东）&nbsp;$seat
          <h4><a href="/updateseat">我想换座</a></h4></div>
          <div class="col-md-3 cta-button"><center><h4>预约状态:</h4><h3>已预约</h3>
          <script>showConfirmModal('确认', 'cancel', '88')</script>
          <script>showConfirmModal('确认', '$stateAction', '88')</script>
          </center></div>
        </div></div>
        </body></html>
    """.trimIndent()

    /** 此刻页面上那一个状态动作（`showConfirmModal` 的第二个参数 = 页面 JS 的动作名）： 没签到 ⇒ `ruguan1`（入馆签到） */
    private val stateAction: String
        get() = when {
            !signedIn.get() -> "ruguan1"
            away.get() -> "return"
            else -> "leave"
        }

    /** 座位已被占的那张页面（原因文本照真站点的措辞，`bookingFailureReason` 认得它）。 */
    val takenSeatPage = "<html><body><div class='alert'>该座位已被他人预约</div></body></html>"

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

    /** 统一认证登录页，形状照真页。 */
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
            // ⚠️ 用 `rawQuery`：`getQuery()` 已解码，service 里的 `&` 会被当成分隔符截断。
            casEnabled && path == "/cas/login" && exchange.requestMethod == "POST" ->
                handleCasPost(exchange, exchange.requestURI.rawQuery.orEmpty())
            casEnabled && path == "/cas/login" -> handleCasGet(exchange, exchange.requestURI.rawQuery.orEmpty(), cookieHeader(exchange))
            casEnabled && path == OAUTH_AUTHORIZE_PATH ->
                handleCasOauthAuthorize(exchange, exchange.requestURI.rawQuery.orEmpty(), cookieHeader(exchange))
            casEnabled && path == PUBLIC_KEY_PATH -> {
                publicKeyCalls.incrementAndGet()
                respond(exchange, 200, "text/plain; charset=utf-8", TestRsaKey.publicKeyBase64.toByteArray())
            }
            // 短信二次验证的两枪（Safety Verify 流程）：取手机号 + 校验验证码。
            // 触发这一整套的是二次认证页（页面原文见 `CasSafetyVerifyPage`，两个业务站夹具都发它）。
            casEnabled && path == MFA_PHONE_PATH -> handleMfaPhone(exchange, query)
            casEnabled && path == MFA_VERIFY_PATH && exchange.requestMethod == "POST" -> handleMfaVerify(exchange)

            path == "/modify" && exchange.requestMethod == "POST" -> handleModifyPost(exchange)
            path == "/modify" -> respondHtml(exchange, modifyPage)
            // 会话失效那两条路：一次性失效优先（内核重登 + 重放），其次是一直失效（判据那条）
            path == "/qspace" -> when {
                expireOnce.getAndSet(false) -> {
                    // 业务站拦回统一认证时作废自己的会话 cookie（Max-Age=0），逼重认证真走一趟 CAS。
                    exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0")
                    respondHtml(exchange, casLoginPage)
                }
                loginMode.get() -> respondHtml(exchange, loginPage)
                else -> respondJson(exchange, qspaceJson)
            }
            path == "/qseat" -> respondJson(exchange, qseatJson)
            path == "/qseatuist" -> respondJson(exchange, qseatuistJson)

            path == "/seat/" -> when {
                // 座位被占那一支（写路径的失败剧本）：两条传输都要能看到它，所以放在 `!casEnabled` 之前
                seatTaken.get() && "kid=" in query -> respondHtml(exchange, takenSeatPage)
                !casEnabled -> redirectToMy(exchange)
                "ticket=" in query -> {
                    // ⚠️ 不能再 302 回 /seat/：没有 ticket 会被拦回 CAS，转到「Too many follow-up requests」。
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
            // 入馆签到 / 中途离开 / 中途返回：三个都用 `/my/` 上的那一枪，只是页面状态跟着变
            //（下一发 `/my/` 会渲染出新的那一个按钮 —— `executeAction` 的复核就靠这个差异）。
            path == "/my/" && "firstruguan=1" in query -> {
                signedIn.set(true)
                respondHtml(exchange, "<html><body><div class='alert'>签到成功</div></body></html>")
            }
            path == "/my/" && "midleave=1" in query -> {
                away.set(true)
                respondHtml(exchange, "<html><body><div class='alert'>已暂离</div></body></html>")
            }
            path == "/my/" && "midreturn=1" in query -> {
                away.set(false)
                respondHtml(exchange, "<html><body><div class='alert'>已返回</div></body></html>")
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

    /** 统一认证入口：带 TGC 就直接签 ticket 回跳（SSO），否则给登录表单。 */
    private fun handleCasGet(exchange: HttpExchange, query: String, cookie: String) {
        val service = param(query, "service") ?: "$base/seat/"
        if ("TGC=$TGC_VALUE" in cookie) {
            redirect(exchange, serviceWithTicket(service, ticketFor(service)))
        } else {
            respondHtml(exchange, casLoginPage)
        }
    }

    /** OAuth2 授权入口：带 TGC 就签一个 `code` 回 `redirect_uri`，否则把这个 authorize 地址本身当作 `service` 交给登录页（真 CAS 就是这么转的：`/cas/login?service=<authorize url>`）——凭据 POST 后 `handleCasPost` 会把票据加回 authorize，再回来时 TGC 已在 jar 里，于是签 code。 */
    private fun handleCasOauthAuthorize(exchange: HttpExchange, query: String, cookie: String) {
        val redirectUri = param(query, "redirect_uri")
        if (redirectUri.isNullOrBlank()) {
            badRequest(exchange, "OAuth2 authorize 要带 redirect_uri（收到：$query）")
            return
        }
        if ("TGC=$TGC_VALUE" in cookie) {
            val code = "OC-fake-${oauthCodes.incrementAndGet()}"
            redirect(exchange, appendParam(redirectUri, "code", code))
        } else {
            // [query] 是 rawQuery（百分号编码原文）⇒ 拼进 service 时**不要再包一层 encode**：
            // 那样会把参数里的 `%` 变成 `%25`，绕一圈回来仍是原样的编码值 —— 保持一层编码正好。
            redirect(exchange, "https://$CAS_HOST/cas/login?service=${encode("https://$CAS_HOST$OAUTH_AUTHORIZE_PATH?$query")}")
        }
    }

    /** 把票据交回去：塞进 query，不能拼在字符串末尾。 */
    private fun serviceWithTicket(service: String, ticket: String): String {
        val hash = service.indexOf('#')
        val head = if (hash >= 0) service.substring(0, hash) else service
        val fragment = if (hash >= 0) service.substring(hash) else ""
        val separator = if ('?' in head) "&" else "?"
        return "$head$separator" + "ticket=$ticket" + fragment
    }

    /** 往 URL 上挂一个参数：已有 query 就用 `&`，否则用 `?`；片段留在最后。 */
    private fun appendParam(url: String, name: String, value: String): String {
        val hash = url.indexOf('#')
        val head = if (hash >= 0) url.substring(0, hash) else url
        val fragment = if (hash >= 0) url.substring(hash) else ""
        return "$head${if ('?' in head) '&' else '?'}$name=$value$fragment"
    }

    /** 凭据表单：认用户名 + RSA 解出的密码，种 TGC，签 ticket 回跳业务站。 */
    private fun handleCasPost(exchange: HttpExchange, query: String) {
        val form = exchange.requestBody.readBytes().decodeToString()
        // 回提优先：它是另一条形态的同名请求（见上面的 KDoc）
        if ("secState=" in form) {
            handleSafetyVerifySubmit(exchange, query, form)
            return
        }
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
        // TGC 先种下：真站点上「密码已经对了、但还要过一次安全验证」正是这个形态
        // （二次认证回去之后不必再交一次密码）
        exchange.responseHeaders.add("Set-Cookie", "TGC=$TGC_VALUE; Path=/")
        if (requireSafetyVerify.get()) {
            safetyVerifyLandings.incrementAndGet()
            respondHtml(exchange, CasSafetyVerifyPage.HTML)
            return
        }
        redirect(exchange, serviceWithTicket(service, ticketFor(service)))
    }

    /** 二次认证页的隐藏表单回提：字段对不上就 400 + 原文，合格就签 ticket 把登录链走完。 */
    private fun handleSafetyVerifySubmit(exchange: HttpExchange, query: String, form: String) {
        lastSafetyVerifyForm = form
        val rejection = CasSafetyVerifyPage.rejection(form)
        if (rejection != null) {
            badRequest(exchange, rejection)
            return
        }
        safetyVerifySubmissions.incrementAndGet()
        requireSafetyVerify.set(false)
        val service = param(query, "service") ?: "$base/seat/"
        redirect(exchange, serviceWithTicket(service, ticketFor(service)))
    }

    /** 签一张回跳票据。 */
    private fun ticketFor(service: String): String {
        val n = tickets.incrementAndGet()
        if (YwtbFakeUpstream.HOST !in service) return "ST-$n"
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"idToken":"${YwtbFakeUpstream.ID_TOKEN}","sub":"$USERNAME"}""".toByteArray())
        // 头一个 base64url 的 header、签名字段随便给（本端不验签，真站点也不由本端验）
        return "eyJhbGciOiJSUzI1NiJ9.$payload.sig"
    }

    /** 取绑定手机号：`state` 必须就是二次认证页上的 `secState`，否则响亮地 400。 */
    private fun handleMfaPhone(exchange: HttpExchange, query: String) {
        val state = param(query, "state")
        if (state != CasSafetyVerifyPage.SEC_STATE) {
            badRequest(exchange, "二次认证取手机号的 state 该是 ${CasSafetyVerifyPage.SEC_STATE}，收到：$state")
            return
        }
        mfaPhoneCalls.incrementAndGet()
        respondJson(
            exchange,
            """{"code":0,"data":{"gid":"$MFA_GID","securePhone":"$MFA_PHONE_MASKED"}}""",
        )
    }

    /** 校验短信验证码：只认 [MFA_CODE]，其余按真站点的失败响应回（`code:1` + 中文 message）。 */
    private fun handleMfaVerify(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        val gid = jsonField(body, "gid")
        val code = jsonField(body, "code")
        if (gid.isNullOrBlank() || code == null) {
            badRequest(exchange, "校验验证码要带 gid 与 code（收到：$body）")
            return
        }
        if (gid != MFA_GID) {
            badRequest(exchange, "校验验证码的 gid 该是本夹具发的 $MFA_GID，收到：$gid")
            return
        }
        mfaVerifyCalls.incrementAndGet()
        if (code != MFA_CODE) {
            respondJson(exchange, """{"code":1,"message":"验证码错误"}""")
            return
        }
        respondJson(exchange, """{"code":0,"data":{"status":"2"}}""")
    }

    /** 认得出的路径但形体不对 ⇒ 响亮地 400（与其余夹具同一条纪律）。 */
    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    /** 从 `{"k":"v",…}` 里取一个字符串字段（夹具只认这一种形状，不为它引 JSON 依赖）。 */
    private fun jsonField(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
