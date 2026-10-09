package com.xjtu.toolbox.desktop

import com.sun.net.httpserver.HttpServer
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.library.LibraryCampus
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.LibraryPages
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.library.SeatResult
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **Stage A 第二步的验收**（`docs/desktop-port-plan.md` §6 / 交接文档 §3.1）：
 * 桌面端**用窗口里输进来的凭据真登进去**，然后**用它自己那份取数**读图书馆。
 *
 * ## 它与 `:data:jvmTest` 那条是什么关系（不是重复）
 *
 * `LibraryLoginSessionJvmTest` 验的是**会话内核本身**（`:data`）：它自己拼一个
 * `SessionManager` + `SiteBackedLibrarySession`，证明「同一份数据层能在 JVM 上用真 CAS 会话跑」。
 *
 * 这一条验的是**桌面端自己的装配**（`:desktop` 的 `DesktopAuth` + `DesktopLibrarySource`）：
 * 用户在登录页敲的那些字，是不是真的能走完 CAS、把凭据落到 0600 的文件里、跨冷启动恢复、
 * 并在同一份会话上读出座位。装配错一步（少设凭据、少传缓存公钥、命名空间没切）这里就红，
 * 而那三条在 `:data:jvmTest` 里都不会被碰到 —— 那边根本没有 `DesktopAuth`。
 *
 * ## 手法与那条完全一致
 *
 * 假上游（`:testkit`，页面原文只有一份）+ 本地 HTTP **代理**（URL 一个字符都不改）。
 * 进程级缓存（`HttpClients.base` 抄下的 ProxySelector、`PersistentCookieJar`、
 * `secureKeyValueStore`）的坑见 [withFakeCas] 的注释。
 */
class DesktopAuthLibraryJvmTest {

    private lateinit var server: HttpServer
    private lateinit var fake: LibraryFakeUpstream

    /**
     * 进程级的「把连接指到本次夹具」开关 —— 必须**常驻**且读一个可变端口。
     *
     * 为什么：`HttpClients.base` 是进程级 `by lazy`，**它会把当时的默认 ProxySelector 抄进自己的
     * 配置**，而 `SessionBackend.client` 又是从它 `newBuilder()` 派生的。于是「每条测试装一个新的
     * selector」只有第一条生效，后面几条仍然拿着上一条的端口（服务已停 ⇒ Connection refused）。
     * 这与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest` 踩的是同一个坑（那边有完整记述）。
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
     * 起假上游（当代理用）+ 把落盘存储指到临时目录，并在里面跑 [block]。
     *
     * 返回类型固定 `Unit`：JUnit4 要求测试方法返回 void，而 `assertNotNull` 这类断言是**有返回值**的
     * （Kotlin 的 Unit 协变只在期望类型是 Unit 时生效）。交接文档 §5.5 记着这条坑：
     * 一旦方法返回了断言值，整个类直接 `InvalidTestClassError`，而 `gradlew` 只打一行
     * `initializationError`。
     *
     * ⚠️ 顺序要紧：cookie jar 与 `secureKeyValueStore` 都是**进程级**缓存（App 里正是靠这一点让
     * 前台/后台共用同一个 jar）⇒ 必须在建任何 [DesktopAuth] **之前**把上一轮留下的内存态与文件
     * 一起清掉，否则上一条测试的 cookie 会把「真登录」变成 SSO 直通。
     */
    private fun withFakeCas(block: () -> Unit) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake = LibraryFakeUpstream(LibraryFakeUpstream.LIBRARY_BASE, casEnabled = true)
        server.createContext("/", fake::handle)
        server.executor = Executors.newCachedThreadPool()
        server.start()

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-test").toFile()
        // ⚠️ **两条命名空间都要清**：桌面端登录时会把 backends 换到账号命名空间
        //（`cookies_normal_<学号>`），所以只清 `_default` 等于什么都没清 —— 上一条测试留下的 TGC
        // 会让本次「真登录」变成 SSO 直通（`credentialPosts` 恒为 0）。这个坑第一次跑就踩到了。
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        // 凭据文件也要清：它是进程级缓存的 KeyValueStore（`stores` 那张表）
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
        // 清 in-memory：`PersistentCookieJar` 按文件名全进程共享一份内存表，只删文件不够
        for (name in listOf(
            "cookies_normal_default", "cookies_webvpn_default",
            "cookies_normal$suffix", "cookies_webvpn$suffix",
        )) {
            PersistentCookieJar(name).clear()
        }
        // 这条也复位：`AccountContext` 是进程级的，上一条测试登录完会把它留着
        AccountContext.activeAccountId = null

        // ⚠️ DesktopAuth 必须在装上 selector 之后再建（OkHttp 的 proxySelector 在建客户端时取默认值）
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

    /**
     * 照真机的路径建一份「已经缓存过 RSA 公钥」的凭据存储 + 桌面装配。
     *
     * 为什么要预置公钥：`XJTULogin` 取公钥那一枪硬编码 `https://login.xjtu.edu.cn/cas/jwt/publicKey`，
     * 本地假上游给不了（要 CONNECT+TLS）。真机上这份缓存由首次登录自动取回并写进凭据文件
     * （[JvmCredentialStore.rsaPublicKey]），测试就是把那一步的结果直接摆好 —— 不为此在生产代码里开口子。
     */
    private fun newAuth(): Pair<DesktopAuth, JvmCredentialStore> {
        val store = JvmCredentialStore()
        store.rsaPublicKey = TestRsaKey.publicKeyBase64
        return DesktopAuth(store) to store
    }

    /** 走一次「用户在登录页敲字然后按登录」。 */
    private fun login(auth: DesktopAuth): Boolean {
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        return runBlocking { auth.login() }
    }

    // ══════ 真登录 ══════

    @Test
    fun `真登录：凭据经 RSA 加密提交到 CAS，TGC 与站点会话落盘，凭据写进 0600 的文件`() = withFakeCas {
        val (auth, store) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 走的是表单 POST，不是 SSO 直通：用户名对得上，密码解得出来（即真是 __RSA__ 加密过的）
        assertEquals(1, fake.credentialPosts.get(), "应恰好提交一次凭据")
        assertEquals(LibraryFakeUpstream.USERNAME, fake.lastPostedUsername)
        assertEquals(LibraryFakeUpstream.PASSWORD, fake.lastPostedPassword)

        val site = auth.librarySite
        assertTrue(site.hasLogin, "登录后站点应是已登录态")
        val jar = auth.sessionManager.backend(com.xjtu.toolbox.auth.AccessMode.NORMAL).cookieJar
        assertNotNull(jar.findCookieByName("TGC"), "统一认证的 TGC 应落在本端 cookie 存储里")
        assertNotNull(jar.findCookieByName("JSESSIONID"), "座位系统自己的会话 cookie 也应落盘")

        // 凭据自己记下来了（下次冷启动靠它免登），且密码**不留在界面状态里**
        assertEquals(LibraryFakeUpstream.USERNAME to LibraryFakeUpstream.PASSWORD, store.load())
        assertEquals("", auth.password, "登录成功后界面上的密码字段应被清空")
        assertEquals("", auth.username, "界面上的账号字段不预填已存凭据（红线：学号不上屏）")

        // 文件权限：0600（同目录的 `SecureStore.jvm.kt` 那条口径）
        val file = java.io.File(requireNotNull(dataRootOverride), "${JvmCredentialStore.FILE_NAME}.properties")
        assertTrue(file.exists(), "凭据应落成文件：$file")
        val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            perms,
            "凭据文件只该给本用户读写",
        )
    }

    @Test
    fun `登不上：不落盘凭据，命名空间退回匿名，界面拿到的是中文短文案`() = withFakeCas {
        val (auth, store) = newAuth()
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = "definitely-not-the-password"
        val ok = runBlocking { auth.login() }

        assertTrue(!ok, "错密码不该登得上")
        assertNull(store.load(), "失败一次就不该记住任何凭据")
        assertNull(com.xjtu.toolbox.account.AccountContext.activeAccountId, "失败后命名空间应退回匿名")
        assertTrue(!auth.loggedIn, "失败后仍是未登录态")
        val state = auth.loginState
        assertIs<com.xjtu.toolbox.auth.LoginUiState.Failed>(state, "界面应拿到失败态")
        assertTrue(state.message.isNotBlank(), "失败文案不该是空的")
        assertTrue(
            !state.message.contains(LibraryFakeUpstream.USERNAME),
            "失败文案里不得出现学号（红线）：${state.message}",
        )
    }

    // ══════ 真登录之后：桌面端自己那份取数读得出数据 ══════

    @Test
    fun `真登录之后：桌面端自己的取数读得出校区、区域、座位与我的预约`() = withFakeCas {
        val (auth, _) = newAuth()
        assertTrue(login(auth))
        val source: LibrarySource = auth.librarySource

        assertEquals(LibraryCampus.XINGQING, runBlocking { source.campus() })

        val areas = runBlocking { source.areas("xingqing2floor") }
        assertEquals(
            mapOf("north2east" to "北楼二层外文库（东）", "north2west" to "北楼二层外文库（西）"),
            areas,
        )

        val seats = assertIs<SeatResult.Success>(runBlocking { source.seats("north2east") })
        assertEquals(listOf("A01", "A02", "A10"), seats.seats.map { it.seatId })
        assertEquals(listOf(true, false, true), seats.seats.map { it.available })

        val booking = runBlocking { source.myBooking() }.getOrThrow()
        assertEquals("A01", booking?.seatId)
        assertEquals("已预约", booking?.statusText)
        assertContains(booking?.actionUrls.orEmpty().keys, "取消预约")

        // 桌面端能写：写路径的编排在 `:data` 的 `LibraryApi` 里，一行未改
        assertTrue(source.canBook, "桌面端直连站点 ⇒ 写路径是真的")
        assertTrue(source.hasSeatPlan, "座位布局与平面图端点都在")
    }

    // ══════ 冷启动 ══════

    @Test
    fun `冷启动：同一个数据目录里新开一个 DesktopAuth，恢复之后不再提交密码`() = withFakeCas {
        val (first, _) = newAuth()
        assertTrue(login(first))
        val postsAfterLogin = fake.credentialPosts.get()

        // 模拟冷启动：同一个落盘目录、全新的装配（新的 SessionManager、新的站点实例）
        val (restarted, store) = newAuth()
        assertEquals(1, fake.credentialPosts.get(), "新建装配本身不该触发任何登录")
        restarted.restore()

        assertTrue(restarted.loggedIn, "有落盘凭据 ⇒ 直接进已登录态，不该再让用户输一遍")
        assertEquals("", restarted.username, "恢复不该把学号填进登录页的输入框（红线）")
        assertEquals(LibraryFakeUpstream.USERNAME to LibraryFakeUpstream.PASSWORD, store.load())

        val site: SiteSession = restarted.librarySite
        val seats = assertIs<SeatResult.Success>(runBlocking { restarted.librarySource.seats("north2east") })
        assertEquals(listOf("A01", "A02", "A10"), seats.seats.map { it.seatId })
        assertEquals(postsAfterLogin, fake.credentialPosts.get(), "恢复 + 取数都不该再提交密码")
    }

    // ══════ 会话失效：内核自己重登 + 重放（与 :data 那条同一口径）══════

    @Test
    fun `会话失效时内核自动重登并重放，调用方拿到正确结果`() = withFakeCas {
        val (auth, _) = newAuth()
        assertTrue(login(auth))
        val site = auth.librarySite
        val epochBefore = site.loginEpoch
        val postsBefore = fake.credentialPosts.get()
        val ticketsBefore = fake.tickets.get()

        // 让下一枪业务请求回一个**真的 CAS 登录页**（`CasLoginPages` 认得出）：
        // 内核应判定认证失效 → 重认证 → 重放同一条请求。
        fake.expireNextBusinessRequest()
        val areas = runBlocking { auth.librarySource.areas("xingqing2floor") }
        assertEquals(2, areas.size, "重放之后应拿到真数据：$areas")

        assertTrue(site.loginEpoch > epochBefore, "应真的重认证过一次")
        assertTrue(fake.tickets.get() > ticketsBefore, "重认证走的是真链路（CAS 又签了 ticket）")
        assertEquals(postsBefore, fake.credentialPosts.get(), "TGC 还在 ⇒ 重认证应当免密，不再提交密码")
    }

    @Test
    fun `一直回登录页：抛共享基类的会话失效异常，由共享屏捕获去重登`() = withFakeCas {
        val (auth, _) = newAuth()
        assertTrue(login(auth))

        fake.loginMode.set(true)
        val e = assertFailsWith<SessionExpiredException> {
            runBlocking { auth.librarySource.areas("xingqing2floor") }
        }
        assertEquals("图书馆", e.siteName)
        assertEquals("图书馆登录态已失效", e.message)
    }

    // ══════ 退出登录 ══════

    @Test
    fun `退出登录：凭据、cookie、站点快照一起删（共享机器上不留登录态）`() = withFakeCas {
        val (auth, store) = newAuth()
        assertTrue(login(auth))
        val suffix = com.xjtu.toolbox.account.AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        assertNotNull(store.load())

        auth.logout()

        assertTrue(!auth.loggedIn, "退出后应回到登录页")
        assertNull(store.load(), "凭据文件里的账号密码应被删掉")
        assertEquals("", auth.username, "退出后界面字段也清空")
        // 这条账号的 cookie 与站点快照都被删（不是「留着下次免密」——桌面是可分发客户端）
        val jarName = "cookies_normal$suffix"
        assertNull(PersistentCookieJar(jarName).findCookieByName("TGC"), "退出后不该还留着 TGC")
        val root = requireNotNull(dataRootOverride)
        assertTrue(!File(root, "$jarName.properties").exists(), "退出后 cookie 文件应被删掉")
        assertTrue(
            !File(root, "sites_normal$suffix.properties").exists(),
            "退出后站点快照文件应被删掉（否则下一个人点一下就能 SSO 直通）",
        )
        assertTrue(
            !File(root, "${JvmCredentialStore.FILE_NAME}.properties").readText().contains(LibraryFakeUpstream.PASSWORD),
            "凭据文件里不应再残留密码明文",
        )
        // 再登一次：会重新走完整 CAS（没有旧 cookie 可以 SSO 直通）
        //
        // 重新种一份 RSA 公钥：`logout()` 走的是 `clear()`，把公钥一并抹了（与 Android 把
        // `rsaPublicKey` 绑在账号记录上同一条口径）⇒ 真机上这一次登录会去 https 取一遍公钥。
        // 本地假上游给不了 https（要 CONNECT+TLS），所以把那次取回的结果直接摆好。
        store.rsaPublicKey = TestRsaKey.publicKeyBase64
        assertTrue(login(auth), "退出后应当还能重新登进来：${auth.loginState}")
        assertEquals(2, fake.credentialPosts.get(), "第二次登录应重新提交凭据（不是靠残留会话直通）")
    }

    /** `LibraryPages.BASE_URL` 与夹具的 `LIBRARY_BASE` 必须一致 —— 不一致说明有人改了生产基址。 */
    @Test
    fun `夹具与生产都指向同一个座位系统基址`() {
        assertEquals(LibraryFakeUpstream.LIBRARY_BASE, LibraryPages.BASE_URL)
    }
}
