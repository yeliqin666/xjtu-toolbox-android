package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccessMode
import com.xjtu.toolbox.auth.CasLoginPages
import com.xjtu.toolbox.library.CasSafetyVerifyPage
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.net.URI
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `/api/session*` 四个端点的契约测试（`docs/api-contract.md` §5 的「P0（新）」那一行）。
 *
 * ## 它验的是哪一层
 *
 * `:data:jvmTest` 验的是**会话内核**（登录状态机 / MFA 状态机 / 站点会话），`:desktop:test` 验的是
 * **桌面端自己的装配**。这一条验的是**同一份数据层 × HTTP 形状**：四个端点、信封、
 * 令牌换 cookie、409 互斥、以及**短信二验那条往返**（浏览器侧轮询 → 用户输码 → 内核校验 → 登录完成）。
 *
 * ## 手法与纪律
 *
 * - **真 socket**：CIO 起在端口 `0`（系统挑），客户端用 JDK 自带的 `java.net.http`
 *   —— 与 [ServeServerTest] 的既有 harness 同一条口径（那边那份只有 `get`，这里需要 POST 与
 *   异步请求，所以是本文件里的第二份小 harness：宁可少 20 行复用，也不去动已经钉住六条验收的旧文件）；
 * - **真上游**：`:testkit` 的 [FakeCampusProxy]（本地 HTTP 代理按 host 分派）。登录是**真的**
 *   走完 CAS（表单 POST + RSA 加密密码 + TGC + ticket 回跳），MFA 是**真的**走完
 *   「Safety Verify 页 → 取手机号 → 校验验证码 → secState 回提」四步（见 `:testkit` 的 `JwxtFakeUpstream` KDoc）；
 * - **数据根一律临时目录**（`dataRootOverride`）：凭据、cookie、站点快照、令牌都不落用户真实目录；
 * - **红线**：每一个 `/api/session*` 的响应体都过一遍 [assertNoIdentity]（不许出现学号/姓名）。
 *
 * ⚠️ 顺序上有一条不能换：[FakeCampusProxy.installFakeUpstreams] **必须在构造 [ServeSession] 之前**
 * （它一构造就会建 OkHttpClient，而客户端在建出来那一刻就把 trust manager 与 ProxySelector 抄走了）。
 */
class ServeSessionTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private lateinit var fake: FakeCampusProxy

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `未登录：查会话报没会话，并把令牌换成 cookie`() = withServeSession { serve ->
        val http = serve.http
        val response = http.get(SESSION_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, code(response))
        assertEquals(false, authenticatedField(response), "还没登录过 ⇒ 没会话")
        assertNoIdentity(response.body(), "未登录的 GET $SESSION_PATH")

        // 令牌换 cookie：形状逐项钉住（§3.2 的第二种形态）。断言里**不打印**这一行的值（令牌不进输出）。
        val setCookie = response.headers().allValues("Set-Cookie")
            .singleOrNull { it.startsWith("${AccessToken.COOKIE_NAME}=") }
        assertNotNull(setCookie, "GET $SESSION_PATH 应当把访问令牌换成一枚 cookie")
        assertTrue(setCookie.contains("Path=/"), "cookie 要覆盖整站（/api 与静态产物都在根下）")
        assertTrue(setCookie.contains("HttpOnly"), "cookie 必须是 HttpOnly —— 否则页面脚本能读走令牌")
        assertTrue(setCookie.contains("SameSite=Lax"), "cookie 的 SameSite 得是 Lax（不写 = 浏览器默认，不可控）")

        // 换到之后**只带 cookie**也过闸门（证明换的就是闸门认的那一枚，而不是另起一个名字）
        val viaCookie = http.get(SESSION_PATH, bearer = null, cookie = cookieValue(setCookie))
        assertEquals(200, viaCookie.statusCode())
        assertEquals(false, authenticatedField(viaCookie))

        // 错 cookie 照旧 401：这一步没有把闸门放松
        assertEquals(401, http.get(SESSION_PATH, bearer = null, cookie = "not-the-token").statusCode())

        // 没有挂起的询问时如实报 pending:false（不是 404、也不是 500）
        assertEquals(false, mfaState(serve).pending)

        // 请求体形状不对 ⇒ 400 + **信封**（不是 Ktor 那个不带信封的 400）
        val malformed = http.postJson(SESSION_PATH + "/login", "{\"username\":")
        assertEquals(400, malformed.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, code(malformed))
    }

    @Test
    fun `真登录：CAS 表单提交后 authenticated 变 true，凭据落盘`() = withServeSession { serve ->
        val response = serve.login()
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, code(response))
        assertEquals(true, authenticatedField(response), "登上了就该报 true")
        assertNoIdentity(response.body(), "POST $SESSION_PATH/login")

        // ── 走的是**真的** CAS：提交过凭据、用户名对得上、统一认证真签过 ticket ──
        assertEquals(1, fake.library.credentialPosts.get(), "登录页那一步应当恰好提交一次凭据")
        assertEquals(LibraryFakeUpstream.USERNAME, fake.library.lastPostedUsername)
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // ── 会话真的在：主站登上了、TGC 落在本端 cookie 存储里 ──
        assertTrue(
            serve.session.sessionManager.getSite(ServeSession.LIBRARY_SITE_KEY).hasLogin,
            "硬要求的主站（图书馆）应当已登录",
        )
        val jar = serve.session.sessionManager.backend(AccessMode.NORMAL).cookieJar
        assertNotNull(jar.findCookieByName("TGC"), "TGC 应当落在本端 cookie 存储里")

        // ── 凭据落盘（下次冷启动靠它免登）。文件在临时数据根里，刻意不在这里再断言一遍 0600 ——
        //    那一条由 `:desktop` 的 DesktopAuthLibraryJvmTest 对**同一份**存储钉着。──
        assertEquals(
            LibraryFakeUpstream.USERNAME to LibraryFakeUpstream.PASSWORD,
            JvmCredentialStore().load(),
            "登录成功应当把凭据记下来",
        )

        // ── 之后 GET 也报 true（同一份会话，不是「登录响应里写死的 true」）──
        assertEquals(true, authenticatedField(serve.http.get(SESSION_PATH)))
    }

    @Test
    fun `错凭据：4xx + 中文短句，凭据不落盘、命名空间退回匿名`() = withServeSession { serve ->
        val response = serve.http.postJson(
            SESSION_PATH + "/login",
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"definitely-not-the-password"}""",
        )

        assertEquals(401, response.statusCode(), "凭据被拒 ⇒ 401（重发同一个请求没有意义）")
        assertEquals(ApiErrors.LOGIN_FAILED, code(response))
        val message = message(response)
        assertTrue(message.any { it.code in 0x4e00..0x9fff }, "失败文案该是能直接给用户看的中文短句：$message")
        assertNoIdentity(response.body(), "错凭据的 POST $SESSION_PATH/login")

        assertNull(JvmCredentialStore().load(), "失败一次就不该记住任何凭据")
        assertNull(AccountContext.activeAccountId, "失败后命名空间应当退回匿名")
        assertEquals(false, authenticatedField(serve.http.get(SESSION_PATH)))
    }

    /**
     * 冷启动静默恢复：落盘凭据还在 ⇒ 新的 serve 实例一上来就是「有身份」，**且不联网**。
     *
     * 为什么把假上游先关掉：恢复那一步只允许做本地装配（重新绑命名空间 + 塞回凭据/指纹/公钥），
     * 不许顺手探活 —— 那会让「打开页面」这个最该快的动作多等一次网络（契约 §5 的口径）。
     * 关掉上游之后任何网络请求都会失败，于是「断言通过」本身就证明了它没联网。
     */
    @Test
    fun `冷启动静默恢复：落盘凭据让新实例直接是有身份，且不联网`() = withServeSession { serve ->
        assertEquals(200, serve.login().statusCode())
        fake.close()

        val fresh = ServeSession()
        assertEquals(true, fresh.authenticated, "落盘凭据应当让新实例直接算「有会话」")
        assertEquals(LibraryFakeUpstream.USERNAME, fresh.sessionManager.credentials?.first)
        fresh.logout()
    }

    @Test
    fun `登出：凭据、cookie 一起清掉，authenticated 变 false`() = withServeSession { serve ->
        assertEquals(200, serve.login().statusCode())
        val jar = serve.session.sessionManager.backend(AccessMode.NORMAL).cookieJar
        assertNotNull(jar.findCookieByName("TGC"))

        val response = serve.http.postJson(SESSION_PATH + "/logout", "{}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, code(response))
        assertEquals(false, authenticatedField(response))
        assertNoIdentity(response.body(), "POST $SESSION_PATH/logout")

        assertNull(JvmCredentialStore().load(), "登出要连凭据一起删")
        assertNull(jar.findCookieByName("TGC"), "登出要连本机 cookie 一起清（共用机器上不留 TGC）")
        assertEquals(false, authenticatedField(serve.http.get(SESSION_PATH)))
    }

    /**
     * **短信二验的完整往返**（契约 §5 里那条轮询形态的验收）。
     *
     * 剧本在夹具那一侧（`:testkit` 的 `JwxtFakeUpstream` 那个 Safety Verify 页 + CAS 的取手机号/校验验证码两枪）：
     * 教务站点在 TGC 已下发之后仍被拦到二次认证页 ⇒ `REQUIRE_MFA` ⇒ 内核挂起等验证码。
     * 这里验的是**浏览器那一侧的四件事**：轮询看得到询问、挂起期间别的端点照常、错码 rejections 递增、
     * 对码把登录跑完。
     */
    @Test
    fun `短信二验：挂起时其它端点照常，错码 rejections 递增，对码完成登录`() = withServeSession { serve ->
        fake.jwxt.requireSafetyVerify.set(true)

        // ── 先钉住夹具与判据：这一页必须**就是** `CasLoginPages` 认得的那种页（首页则不是）──
        assertTrue(
            CasLoginPages.isSafetyVerifyPage(CasSafetyVerifyPage.HTML),
            "夹具的二次认证页要能被 CasLoginPages 认出来，否则这条剧本测的就不是真判据",
        )
        assertFalse(CasLoginPages.isSafetyVerifyPage(fake.jwxt.homePage), "首页不该被认成二次认证页")

        val pending = serve.http.postJsonAsync(SESSION_PATH + "/login", LOGIN_BODY)

        // ── 轮询：询问挂上来了，形状逐字段对 ──
        val first = awaitMfa(serve) { it.pending }
        assertEquals("教务系统", first.siteName, "站点名是内核给的（屏上那句「…需要短信验证码」用它）")
        assertEquals(0, first.rejections)
        assertEquals(3, first.attemptsLeft, "内核允许 3 次验证码（ServeSession.MFA_MAX_ATTEMPTS）")
        assertEquals(1, fake.jwxt.safetyVerifyLandings.get(), "登录链应当真的在二次认证那一步被拦下")
        assertEquals(1, fake.library.mfaPhoneCalls.get(), "弹窗出现时应当取过一次绑定手机号（也是 verifyCode 要的 gid）")

        // ── 挂起期间别的端点照常 200（MFA 只挂住那一发登录，不阻塞进程）──
        assertEquals(200, serve.http.get(API_STATUS_PATH).statusCode())
        assertEquals(false, authenticatedField(serve.http.get(SESSION_PATH)), "登录还没跑完 ⇒ 还不算「有身份」")

        // ── 并发第二发登录：409（不许两个登录互相 reconfigure 把状态搅坏）──
        val second = serve.http.postJson(SESSION_PATH + "/login", LOGIN_BODY)
        assertEquals(409, second.statusCode())
        assertEquals(ApiErrors.LOGIN_BUSY, code(second))
        assertTrue(message(second).any { it.code in 0x4e00..0x9fff }, "409 也带中文短句")

        // ── 错验证码：服务端拒绝 ⇒ rejections 递增，询问还在（弹窗留着重输）──
        val wrong = serve.http.postJson(SESSION_PATH + "/mfa", """{"code":"000000"}""")
        assertEquals(200, wrong.statusCode())
        val rejected = awaitMfa(serve) { it.rejections >= 1 }
        assertEquals(1, rejected.rejections)
        assertEquals(2, rejected.attemptsLeft)
        assertTrue(rejected.pending, "错一次验证码不该把询问结束掉")
        assertFalse(pending.isDone, "也不该把那一发登录结束掉")

        // ── 对码：登录跑完 ──
        val right = serve.http.postJson(SESSION_PATH + "/mfa", """{"code":"${LibraryFakeUpstream.MFA_CODE}"}""")
        assertEquals(200, right.statusCode())
        assertNoIdentity(right.body(), "POST $SESSION_PATH/mfa")

        val done = pending.get(LOGIN_AWAIT_SECONDS, TimeUnit.SECONDS)
        assertEquals(200, done.statusCode(), "对码之后挂起的那一发登录应当以 200 收尾")
        assertEquals(true, authenticatedField(done), "并且如实报已登录")
        assertNoIdentity(done.body(), "MFA 完成后的 POST $SESSION_PATH/login")
        assertEquals(true, authenticatedField(serve.http.get(SESSION_PATH)))

        // ── 夹具侧：这一趟真的走完了四步（取手机号 → 两次校验 → secState 回提）──
        assertEquals(2, fake.library.mfaVerifyCalls.get(), "错码与对码各真打到服务端一次")
        assertEquals(1, fake.jwxt.safetyVerifySubmissions.get(), "验证码过了才该回提 secState 表单")
        val form = fake.jwxt.lastSafetyVerifyForm.orEmpty()
        assertTrue(
            "secState=${CasSafetyVerifyPage.SEC_STATE}" in form,
            "回提要带二次认证页上的 secState：$form",
        )
        assertTrue(
            "execution=${CasSafetyVerifyPage.EXECUTION}" in form,
            "回提要带页上那个新的 execution",
        )
        assertTrue("_eventId=${CasSafetyVerifyPage.EVENT_ID}" in form, "回提要带 _eventId")

        // 询问已经撤了
        assertEquals(false, awaitMfa(serve) { !it.pending }.pending)
    }

    /**
     * 短信二验**取消**：`POST {"cancel":true}` 之后，挂起的那一发登录以 4xx 收尾。
     *
     * ⚠️ 这一条必须打在**硬要求的主站（图书馆）**上，不能打在预热站点上：预热站点失败只记不抛
     *（「单个子系统挂了不能把人挡在门外」是登录语义的一部分）⇒ 在教务上取消只会让教务那一站跳过，
     * 整次登录照旧成功。所以这条剧本把二次认证页发在**凭据 POST 的落点**（图书馆那条链），
     * 于是「用户取消了验证」= 这次登录没成 —— 这正是契约里 `cancel` 该有的语义。
     *
     * 不取消的话内核会一直等到 150 秒超时，那段时间里第二发登录只能拿 409 —— 用户看到的是「卡住了」。
     */
    @Test
    fun `短信二验取消：POST cancel 之后挂起的那一发登录以 4xx 收尾`() = withServeSession { serve ->
        fake.library.requireSafetyVerify.set(true)
        val pending = serve.http.postJsonAsync(SESSION_PATH + "/login", LOGIN_BODY)

        val first = awaitMfa(serve) { it.pending }
        assertEquals("图书馆", first.siteName, "这一次拦住登录的是主站（硬要求的那个）")
        assertEquals(1, fake.library.safetyVerifyLandings.get(), "凭据对之后应当被拦到二次认证页")

        val cancel = serve.http.postJson(SESSION_PATH + "/mfa", """{"cancel":true}""")
        assertEquals(200, cancel.statusCode())
        assertNoIdentity(cancel.body(), "POST $SESSION_PATH/mfa（cancel）")

        val failed = pending.get(LOGIN_AWAIT_SECONDS, TimeUnit.SECONDS)
        assertTrue(
            failed.statusCode() in 400..499,
            "取消之后挂起的那一发登录应当以 4xx 收尾（实际 ${failed.statusCode()}）",
        )
        assertTrue(message(failed).any { it.code in 0x4e00..0x9fff }, "文案要是中文短句：${message(failed)}")
        assertNoIdentity(failed.body(), "取消后的 POST $SESSION_PATH/login")

        // 收干净了：没有会话、没有凭据、询问也撤了；并且真的**没**继续往后走（连预热的第一个站点都没碰到）
        assertEquals(false, authenticatedField(serve.http.get(SESSION_PATH)))
        assertNull(JvmCredentialStore().load())
        assertEquals(false, awaitMfa(serve) { !it.pending }.pending)
        assertEquals(0, fake.library.safetyVerifySubmissions.get(), "取消之后不该回提 secState 表单")
        assertEquals(0, fake.jwxt.casRedirects.get(), "主站就失败了，根本不该走到预热的那些站点")
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────────────────

    /**
     * 起假上游（当代理用）+ 把落盘存储指到临时目录，跑 [block]，收尾关上游。
     *
     * 与 `:desktop` 的 `DesktopAuthLibraryJvmTest.withFakeCampus` 逐条同源（三个坑都一样）：
     *  1. **两件进程级前置必须在碰 `HttpClients` 之前装**（也就建任何 [ServeSession] 之前）：
     *     常驻 selector 与假上游那枚自签 https 证书的信任库；
     *  2. cookie jar 与 `secureKeyValueStore` 都是**进程级**缓存（App 里正是靠这一点让前后台共用
     *     同一个 jar）⇒ 必须把上一轮留下的内存态与文件一起清掉，否则上一条测试的 TGC 会把
     *     「真登录」变成 SSO 直通；
     *  3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）：登录会把 backends 换到那边。
     */
    private fun withServeSession(block: (Serve) -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)
        dataRootOverride = Files.createTempDirectory("xjtu-serve-session").toFile()
        wipeStoredSession()
        AccountContext.activeAccountId = null

        FakeCampusProxy.installFakeUpstreams()
        fake.start()
        try {
            block(startServe())
        } finally {
            fake.close()
            dataRootOverride = null
        }
    }

    private fun startServe(): Serve {
        // 端口 0 = 系统挑（绝不抢 8123：那上面可能正跑着用户在用的 serve-same-origin.py）
        val token = AccessToken.newToken()
        val session = ServeSession()
        val config = ServeConfig(port = 0, distDir = Files.createTempDirectory("xjtu-serve-dist").toFile())
        val server = serveServer(config, token, session)
        server.start(wait = false)
        started = server
        val connector = runBlocking { server.engine.resolvedConnectors().single() }
        return Serve(session, Harness(connector.host, connector.port, token))
    }

    /** 进程级的落盘态：cookie 文件 + cookie jar 的内存表 + 凭据文件，全部清一遍。 */
    private fun wipeStoredSession() {
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        for (name in listOf(
            "cookies_normal_default",
            "cookies_webvpn_default",
            "cookies_normal$suffix",
            "cookies_webvpn$suffix",
        )) {
            PersistentCookieJar(name).clear()
        }
    }

    /** 「用户在登录页敲字然后按登录」——用夹具那组假账号。 */
    private fun Serve.login(): HttpResponse<String> = http.postJson(SESSION_PATH + "/login", LOGIN_BODY)

    /** 轮询 `/api/session/mfa` 直到 [predicate] 成立（超过 [MFA_AWAIT_MS] 就响亮地失败）。 */
    private fun awaitMfa(serve: Serve, predicate: (MfaSnapshot) -> Boolean): MfaSnapshot {
        val deadline = System.currentTimeMillis() + MFA_AWAIT_MS
        var last: MfaSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            val snapshot = mfaState(serve)
            last = snapshot
            if (predicate(snapshot)) return snapshot
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("等 MFA 状态超时（最后看到：${last?.describe()}）")
    }

    private fun mfaState(serve: Serve): MfaSnapshot {
        val response = serve.http.get(SESSION_PATH + "/mfa")
        assertEquals(200, response.statusCode(), "轮询 MFA 状态应当是 200")
        assertNoIdentity(response.body(), "GET $SESSION_PATH/mfa")
        val data = data(response)
        return MfaSnapshot(
            pending = data.optBoolean("pending") ?: false,
            siteName = data.optString("siteName"),
            rejections = data.optInt("rejections") ?: 0,
            attemptsLeft = data.optInt("attemptsLeft") ?: 0,
        )
    }

    /** §3.4 那条红线的实现：`/api/session*` 的响应体**绝不许**出现学号/姓名。 */
    private fun assertNoIdentity(body: String, where: String) {
        for (forbidden in listOf("username", "studentId", "学号", "姓名")) {
            assertFalse(body.contains(forbidden), "$where 的响应体里不该出现「$forbidden」")
        }
        assertFalse(body.contains(LibraryFakeUpstream.USERNAME), "$where 的响应体里不该出现学号")
    }

    /** 起好的会话装配 + 一个真 HTTP 客户端。 */
    private class Serve(val session: ServeSession, val http: Harness)

    /** 一个真 HTTP 客户端（JDK 自带；默认带令牌，`bearer = null` 表示「只带 cookie」）。 */
    private class Harness(private val host: String, private val port: Int, private val token: String) {

        private val client: HttpClient = HttpClient.newBuilder()
            // 明文 HTTP 上不必去试 h2c 升级：CIO 只讲 HTTP/1.1
            .version(HttpClient.Version.HTTP_1_1)
            // ⚠️ 必须显式「不走代理」：假上游装的是**进程级** ProxySelector（见 `FakeUpstreamProxySelector`），
            // 它把每个连接都指到那个端口 —— 连我们自己这台 `127.0.0.1` 上的 serve 也不例外
            // （表现是假上游回一句 404 "nope"，看上去像路由没挂上）。JDK 的 HttpClient 也认那个默认值。
            .proxy(ProxySelector.of(null))
            .build()

        fun get(path: String, bearer: String? = token, cookie: String? = null): HttpResponse<String> =
            client.send(request(path, bearer, cookie).GET().build(), HttpResponse.BodyHandlers.ofString())

        fun postJson(path: String, body: String, bearer: String? = token, cookie: String? = null): HttpResponse<String> =
            client.send(
                request(path, bearer, cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        /**
         * 异步发一发（MFA 那条链要它）：登录会挂起等验证码，测试得在它挂着的时候去轮询/取消。
         * 刻意**不设**请求超时：挂起本身是这条验收的一部分（内核的等待上限是 150 秒）。
         */
        fun postJsonAsync(path: String, body: String): CompletableFuture<HttpResponse<String>> =
            client.sendAsync(
                request(path, token, null)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        private fun request(path: String, bearer: String?, cookie: String?): HttpRequest.Builder =
            HttpRequest.newBuilder(URI.create("http://$host:$port$path")).apply {
                bearer?.let { header("Authorization", "Bearer $it") }
                cookie?.let { header("Cookie", "${AccessToken.COOKIE_NAME}=$it") }
            }
    }

    /** 轮询看到的那份形状（字段名与线上一致）。 */
    private class MfaSnapshot(
        val pending: Boolean,
        val siteName: String?,
        val rejections: Int,
        val attemptsLeft: Int,
    ) {
        fun describe(): String = "pending=$pending siteName=$siteName rejections=$rejections attemptsLeft=$attemptsLeft"
    }

    private companion object {
        /** 夹具那组假账号（`:testkit`，编造值 —— 不是任何真人的学号）。 */
        val LOGIN_BODY = """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** 等 MFA 询问挂上来 / 等那一发登录收尾的上限（内核的等待上限是 150 秒，这里只留够正常路径）。 */
        const val MFA_AWAIT_MS = 20_000L
        const val LOGIN_AWAIT_SECONDS = 30L
        const val POLL_INTERVAL_MS = 100L
    }
}

// ── 断言小工具（响应体按 JSON 读；本文件到处都在读那几个字段）──────────────────────────

/** 信封的 `code`（= HTTP 状态码那一套，见 [ApiErrors]）。 */
private fun code(response: HttpResponse<String>): Int =
    response.envelope().getValue("code").jsonPrimitive.content.toInt()

/** 信封的 `message`（失败时是给用户看的中文短句）。 */
private fun message(response: HttpResponse<String>): String =
    response.envelope().optString("message").orEmpty()

private fun String.asJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun data(response: HttpResponse<String>): JsonObject = response.envelope().getValue("data").jsonObject

private fun authenticatedField(response: HttpResponse<String>): Boolean =
    data(response).getValue("authenticated").jsonPrimitive.content.toBoolean()

/** `serve_token=<令牌>; Path=/…` → `<令牌>`（断言里不打印它）。 */
private fun cookieValue(setCookie: String): String = setCookie.substringBefore(';').substringAfter('=')

