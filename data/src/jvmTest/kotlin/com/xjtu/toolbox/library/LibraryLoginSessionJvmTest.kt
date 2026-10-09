package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.auth.AccessMode
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.xjtu.toolbox.auth.LibrarySession as LibrarySiteSession

/**
 * **Stage A 的验收**（`docs/desktop-port-plan.md` §6）：桌面端**用它自己登录的会话**跑通图书馆那条竖切。
 *
 * ## 与 `LibraryDataLayerJvmTest` 的关系
 *
 * 那一条验的是「同一份数据层在 JVM 上真跑」，会话是一条**脚手架**（`DirectLibrarySession`：不发登录、
 * 不重认证）。这一条把那半换成真的：[SessionManager] + [SiteSession] + `XJTULogin` +
 * `LibraryLogin` —— 也就是 Android 上跑的那一整条**会话内核**，现在它也在 `:data`。
 * 夹具、页面原文、断言口径全部与那一条相同（共用 [LibraryFakeUpstream]）。
 *
 * ## 传输：本地 HTTP 代理，而不是重写 URL
 *
 * 会话内核里有一批**按 host 判断**的判据（`XJTULogin.casPath` 只认 `login.xjtu.edu.cn`、
 * `LibraryLogin.looksLikeSeatPage` 与 `LibrarySession.validateLogin` 认 `rg.lib.xjtu.edu.cn`）。
 * 重写 URL 会让这些判据全部落空 —— 那测的就不是真内核了。
 *
 * 所以这一条用**代理**：`ProxySelector` 把所有连接指到本地夹具，URL 一个字符都不改。于是走的是
 * 完整真实链路：座位系统 → 302 到统一认证 → 表单 POST 凭据 → 种 TGC → 签 ticket 回跳 →
 * 站点会话 cookie。页面之外没有任何东西被「为测试而改」。
 *
 * ## 它钉住的四件事
 *
 * | 要验的 | 在哪条测试 |
 * |---|---|
 * | 真登录：凭据经 RSA 加密提交到 CAS，拿到 TGC 与站点会话 | [真登录] |
 * | 冷启动：换一个 SessionManager 从落盘快照 + cookie 恢复，不再重登 | [冷启动从落盘快照恢复] |
 * | 数据层在这份会话上读 / 写都对 | [读路径与 Android 端同一份解析] / [写路径] |
 * | 会话失效：内核自动重登（TGC 免密、新 ticket）并重放 | [会话失效时内核自动重登并重放] |
 */
class LibraryLoginSessionJvmTest {

    private lateinit var server: HttpServer
    private lateinit var fake: LibraryFakeUpstream

    /**
     * 进程级的「把连接指到本测试的夹具」开关。
     *
     * ⚠️ 为什么必须是**可变端口 + 常驻**的 ProxySelector，而不是每条测试装一个新的：
     * `HttpClients.base` 是进程级 `by lazy`，**它会把当时的默认 ProxySelector 抄进自己的配置**，
     * 而 `SessionBackend.client` 又是从它 `newBuilder()` 派生的。于是「本次测试装的 selector」
     * 只会对第一条测试生效，后面几条仍然拿着上一条的端口（服务已停 ⇒ Connection refused）。
     * 装一个读 [port] 的 selector 就绕开了：谁派生都指向当前这条测试的夹具。
     */
    private object UpstreamProxy : ProxySelector() {
        @Volatile var port: Int = 0

        override fun select(uri: URI): List<Proxy> {
            val p = port
            return if (p == 0) listOf(Proxy.NO_PROXY)
            else listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }

    /**
     * 把上游夹具当**代理**起起来，并把进程的 ProxySelector 指过去（结束复原）。
     *
     * 返回类型固定 `Unit`：JUnit4 要求测试方法返回 void，而 `assertNotNull`/`assertIs`
     * 这类断言是会返回值的（Kotlin 的 Unit 协变只在期望类型是 Unit 时生效）。
     */
    private fun withFakeCas(block: () -> Unit) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled = true)
        server.createContext("/", fake::handle)
        server.executor = Executors.newCachedThreadPool()
        server.start()

        // 落盘存储指到临时目录：测试之间不互相污染，也不碰用户真实数据。
        //
        // ⚠️ 顺序要紧：cookie jar 与 `secureKeyValueStore` 都是**进程级**缓存的（App 里也正是
        // 靠这一点让前台/后台共用同一个 jar），所以必须在建任何 SessionManager **之前**
        // 把上一轮测试留下的内存态与文件一起清掉。
        dataRootOverride = Files.createTempDirectory("xjtu-data").toFile()
        for (name in listOf(
            "cookies_normal_default", "cookies_webvpn_default",
            "sites_normal_default", "sites_webvpn_default",
        )) {
            wipeSecureStore(name)
        }
        PersistentCookieJar("cookies_normal_default").clear()
        PersistentCookieJar("cookies_webvpn_default").clear()

        // ⚠️ SessionManager 必须在装上它之后再建（OkHttp 的 proxySelector 在建客户端时取默认值）。
        if (ProxySelector.getDefault() !== UpstreamProxy) ProxySelector.setDefault(UpstreamProxy)
        UpstreamProxy.port = server.address.port
        try {
            block()
        } finally {
            UpstreamProxy.port = 0
            dataRootOverride = null
            server.stop(0)
        }
    }

    /** `:app` 的 `AppLibrarySession` 在 JVM 上的对应物：把内核的会话缝接到数据层那条缝上。 */
    private class SiteBackedLibrarySession(private val site: SiteSession) : LibrarySession {
        override val client: OkHttpClient get() = site.client
        override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)
        /** 与 `:app` 那份的区别只有异常类型（那边给 `AuthExpiredException`，同一个共享基类）。 */
        override fun authExpired(siteName: String) = SessionExpiredException(siteName)
    }

    /** 按真实 App 的装配顺序建一个会话管家：注入缓存的 RSA 公钥、注册图书馆站点、设凭据。 */
    private fun newManager(): SessionManager = SessionManager().apply {
        cachedRsaKey = TestRsaKey.publicKeyBase64
        register(LibrarySiteSession())
        setCredentials(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
    }

    /**
     * 跑一次真登录并返回站点会话。
     *
     * 站点实例是**全新**的（没有内存会话、cookie 也是刚清过的），所以 `ensureLogin` 的
     * 「新鲜窗口」快路径不成立，这一趟必然走完整 CAS 链路 —— 不需要 `force = true` 去凑。
     */
    private fun login(manager: SessionManager): SiteSession = runBlocking {
        manager.ensureSite("library", userInitiated = true)
    }

    // ══════ 真登录 ══════

    @Test
    fun `真登录：凭据经 RSA 加密提交到 CAS，TGC 与站点会话都落到本端存储`() = withFakeCas {
        val manager = newManager()
        val site = login(manager)

        // 走的是表单 POST，不是 SSO 直通：用户名对得上，密码解得出来（即真是 __RSA__ 加密过的）
        assertEquals(1, fake.credentialPosts.get(), "应恰好提交一次凭据")
        assertEquals(LibraryFakeUpstream.USERNAME, fake.lastPostedUsername)
        assertEquals(LibraryFakeUpstream.PASSWORD, fake.lastPostedPassword)

        assertTrue(site.hasLogin, "登录后站点应是已登录态")
        assertNotNull(
            manager.backend(AccessMode.NORMAL).cookieJar.findCookieByName("TGC"),
            "统一认证的 TGC 应落在本端 cookie 存储里",
        )
        assertNotNull(
            manager.backend(AccessMode.NORMAL).cookieJar.findCookieByName("JSESSIONID"),
            "座位系统自己的会话 cookie 也应落盘（说明 ticket 回跳真走完了）",
        )
    }

    // ══════ 冷启动：从落盘快照 + cookie 恢复，不再重登 ══════

    @Test
    fun `冷启动从落盘快照恢复：新管家不再提交密码`() = withFakeCas {
        login(newManager())
        val postsAfterLogin = fake.credentialPosts.get()

        // 模拟冷启动：同一批落盘存储、新的 SessionManager、新注册的站点实例
        val restarted = newManager()
        val site = restarted.getSite("library")
        assertTrue(!site.hasLogin, "刚注册的站点实例还没有内存会话（全靠恢复）")

        val same = runBlocking { restarted.ensureSite("library") }

        assertTrue(same.hasLogin, "应从快照恢复成已登录")
        assertEquals(0L, site.loginEpoch, "恢复 + 探活通过 ⇒ 一次登录都不该发生")
        assertEquals(postsAfterLogin, fake.credentialPosts.get(), "不该再提交密码")
    }

    // ══════ 数据层在这份真会话上跑（与直连那条同一批断言） ══════

    @Test
    fun `读路径与 Android 端同一份解析`() = withFakeCas {
        val site = login(newManager())
        val api = LibraryApi(SiteBackedLibrarySession(site))

        assertEquals(LibraryCampus.XINGQING, runBlocking { api.getCurrentCampus() })

        val areas = runBlocking { api.getFloorAreas("xingqing2floor") }
        assertEquals(
            mapOf("north2east" to "北楼二层外文库（东）", "north2west" to "北楼二层外文库（西）"),
            areas,
        )
        assertEquals("北楼二层外文库（东）", api.areaNameOf("north2east"))
        assertEquals("xingqing2floor", api.floorOfArea("north2east"))
        assertEquals(
            mapOf("north2east" to AreaStats(9, 24), "north2west" to AreaStats(2, 18)),
            api.cachedAreaStats,
        )

        val seats = assertIs<SeatResult.Success>(runBlocking { api.getSeats("north2east") })
        assertEquals(listOf("A01", "A02", "A10"), seats.seats.map { it.seatId })
        assertEquals(listOf(true, false, true), seats.seats.map { it.available })

        val layout = runBlocking { api.getSeatLayout("north2east") }
        assertEquals(listOf("A01", "A02"), layout.seats.map { it.seatId })
        assertEquals(PlanSeat.FREE, layout.seats.first { it.seatId == "A01" }.status)
        assertEquals(PlanSeat.BOOKED, layout.seats.first { it.seatId == "A02" }.status)

        val bytes = runBlocking { api.getPlanImage("north2east.jpg") }
        assertTrue(bytes != null && bytes.size == fake.jpegBytes.size, "应原样返回 JPEG 字节")
        assertEquals(null, runBlocking { api.getPlanImage("nope.jpg") })

        val booking = runBlocking { api.fetchMyBooking() }.getOrThrow()
        assertEquals("A01", booking?.seatId)
        assertEquals("北楼二层外文库（东）", booking?.area)
        assertEquals("已预约", booking?.statusText)
        assertEquals(
            mapOf(
                "取消预约" to "${LibraryPages.BASE_URL}/my/?cancel=1&ri=88",
                "入馆签到" to "${LibraryPages.BASE_URL}/my/?firstruguan=1&ri=88",
            ),
            booking?.actionUrls,
        )
    }

    @Test
    fun `写路径：预约、换座、取消都打在真会话上`() = withFakeCas {
        val site = login(newManager())
        val api = LibraryApi(SiteBackedLibrarySession(site))

        val booked = runBlocking { api.bookSeat("A01", "north2east", autoSwap = false) }
        assertTrue(booked.success, "应成功：${booked.message}")
        assertContains(booked.message, "预约成功")
        assertTrue(booked.finalUrl.orEmpty().contains("/my/"))

        val swapped = runBlocking { api.swapSeat("A02", "north2east") }
        assertTrue(swapped.success, "换座应成功：${swapped.message}")
        assertContains(swapped.message, "A02")
        assertTrue(fake.swapped.get(), "应走过 /updateseat/")

        val cancelled = runBlocking { api.executeAction("${LibraryPages.BASE_URL}/my/?cancel=1&ri=88") }
        assertTrue(cancelled.success, "取消应成功：${cancelled.message}")
        assertTrue(fake.cancelled.get(), "应打过 cancel 那条地址")
    }

    // ══════ 会话失效：内核自己重登 + 重放 ══════

    @Test
    fun `会话失效时内核自动重登并重放`() = withFakeCas {
        val site = login(newManager())
        val api = LibraryApi(SiteBackedLibrarySession(site))
        val epochBefore = site.loginEpoch
        val postsBefore = fake.credentialPosts.get()
        val ticketsBefore = fake.tickets.get()

        // 让下一枪业务请求回一个**真的 CAS 登录页**（`CasLoginPages` 认得出）：
        // 内核应判定认证失效 → 重认证 → 重放同一条请求，调用方拿到的仍是正确结果。
        fake.expireNextBusinessRequest()
        val areas = runBlocking { api.getFloorAreas("xingqing2floor") }
        assertEquals(
            mapOf("north2east" to "北楼二层外文库（东）", "north2west" to "北楼二层外文库（西）"),
            areas,
        )

        assertTrue(site.loginEpoch > epochBefore, "应真的重认证过一次")
        assertTrue(fake.tickets.get() > ticketsBefore, "重认证走的是真链路（CAS 又签了 ticket）")
        assertEquals(postsBefore, fake.credentialPosts.get(), "TGC 还在 ⇒ 重认证应当免密，不再提交密码")
    }

    @Test
    fun `会话失效：一直回登录页时抛共享基类的异常，且实现 SessionExpiredFailure`() = withFakeCas {
        val site = login(newManager())
        val api = LibraryApi(SiteBackedLibrarySession(site))

        // 与直连那条同一个口径：本端判到「被弹回登录页」就抛会话失效异常。
        // 这里它可能由内核（重放仍失效）或数据层（认页面判据）抛出 —— 两者同属一个共享基类。
        fake.loginMode.set(true)
        val e = assertFailsWith<SessionExpiredException> { runBlocking { api.getFloorAreas("xingqing2floor") } }
        assertEquals("图书馆", e.siteName)
        assertEquals("图书馆登录态已失效", e.message)
        assertIs<SessionExpiredFailure>(e)
        assertIs<java.io.IOException>(e)
    }
}
