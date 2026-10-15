package com.xjtu.toolbox.library

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.FakeUpstreamProxySelector
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

/** 桌面端用自己登录的会话跑通图书馆那条竖切。 */
class LibraryLoginSessionJvmTest {

    private lateinit var server: HttpServer
    private lateinit var fake: LibraryFakeUpstream

    /** 进程级的「把连接指到本测试的夹具」开关 —— 就是 `:testkit` 那一份 [FakeUpstreamProxySelector]。 */
    private val UpstreamProxy = FakeUpstreamProxySelector

    /** 把上游夹具当代理起起来，并把进程的 ProxySelector 指过去（结束复原）。 */
    private fun withFakeCas(block: () -> Unit) {
        // 进程级前置，必须在建任何客户端之前：trust manager 在 `Builder.build()` 时就定死，晚装必然 PKIX 失败。
        FakeCampusProxy.installFakeUpstreams()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled = true)
        server.createContext("/", fake::handle)
        server.executor = Executors.newCachedThreadPool()
        server.start()

        // ⚠️ cookie jar 与 `secureKeyValueStore` 是进程级缓存：必须在建 SessionManager 之前清掉上一轮的内存态与文件。
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
        UpstreamProxy.install()
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

    /** 跑一次真登录并返回站点会话。 */
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

        // 被弹回登录页就抛会话失效异常（内核或数据层抛，同属一个基类）。
        fake.loginMode.set(true)
        val e = assertFailsWith<SessionExpiredException> { runBlocking { api.getFloorAreas("xingqing2floor") } }
        assertEquals("图书馆", e.siteName)
        assertEquals("图书馆登录态已失效", e.message)
        assertIs<SessionExpiredFailure>(e)
        assertIs<java.io.IOException>(e)
    }
}
