package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.defaultTermIndex
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
import com.xjtu.toolbox.yellowpage.YellowPageApi
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * **Stage A 第二步的验收**（`docs/desktop-port-plan.md` §6 / 交接文档 §3.1）：
 * 桌面端**用窗口里输进来的凭据真登进去**，然后**用它自己那份取数**读图书馆；校历那条免登录的
 * 路也一并钉住。
 *
 * ## 它与 `:data:jvmTest` 那条是什么关系（不是重复）
 *
 * `LibraryLoginSessionJvmTest` 验的是**会话内核本身**（`:data`）：它自己拼一个
 * `SessionManager` + `SiteBackedLibrarySession`，证明「同一份数据层能在 JVM 上用真 CAS 会话跑」。
 *
 * 这一条验的是**桌面端自己的装配**（`:desktop` 的 `DesktopAuth` + `DesktopLibrarySource`）：
 * 用户在登录页敲的那些字，是不是真的能走完 CAS、把凭据落到 0600 的文件里、跨冷启动恢复、
 * 并在同一份会话上读出座位。装配错一步（少设凭据、少传缓存公钥、命名空间没切）这里就红，
 * 而那几条在 `:data:jvmTest` 里都不会被碰到 —— 那边根本没有 `DesktopAuth`。
 *
 * ## 手法
 *
 * 假上游（`:testkit` 的 `FakeCampusProxy`：一个本地 HTTP **代理**，按 host 分派图书馆与校历）+ URL
 * 一个字符都不改。进程级缓存（`HttpClients.base` 抄下的 ProxySelector、`PersistentCookieJar`、
 * `secureKeyValueStore`）的坑见 [withFakeCampus] 的注释。
 */
class DesktopAuthLibraryJvmTest {

    private lateinit var fake: FakeCampusProxy

    /**
     * 起假上游（当代理用）+ 把落盘存储指到临时目录，并在里面跑 [block]。
     *
     * 返回类型固定 `Unit`：JUnit4 要求测试方法返回 void，而 `assertNotNull` 这类断言是**有返回值**的
     * （Kotlin 的 Unit 协变只在期望类型是 Unit 时生效）。交接文档 §5.5 记着这条坑：
     * 一旦方法返回了断言值，整个类直接 `InvalidTestClassError`，而 `gradlew` 只打一行
     * `initializationError`。
     *
     * ⚠️ 顺序要紧，三条都不能换：
     *  1. **装 selector 必须在碰 `HttpClients` 之前**（也就是建任何 [DesktopAuth] 之前）——
     *     它是进程级 `by lazy`，会把当时的默认 `ProxySelector` 抄进自己的配置；
     *  2. cookie jar 与 `secureKeyValueStore` 都是**进程级**缓存（App 里正是靠这一点让前台/后台
     *     共用同一个 jar）⇒ 必须把上一轮留下的内存态与文件一起清掉，否则上一条测试的 cookie
     *     会把「真登录」变成 SSO 直通；
     *  3. 清 cookie 要连**账号命名空间**一起清（`cookies_normal_<学号>`）：桌面登录会把 backends
     *     换到那边，只清 `_default` 等于什么都没清 —— 这个坑第一次跑就踩到了。
     */
    private fun withFakeCampus(block: () -> Unit) {
        fake = FakeCampusProxy(casEnabled = true)

        dataRootOverride = Files.createTempDirectory("xjtu-desktop-test").toFile()
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

        FakeCampusProxy.installProxySelector()
        fake.start()
        try {
            block()
        } finally {
            fake.close()
            dataRootOverride = null
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
    fun `真登录：凭据经 RSA 加密提交到 CAS，TGC 与站点会话落盘，凭据写进 0600 的文件`() = withFakeCampus {
        val (auth, store) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 走的是表单 POST，不是 SSO 直通：用户名对得上，密码解得出来（即真是 __RSA__ 加密过的）
        assertEquals(1, fake.library.credentialPosts.get(), "应恰好提交一次凭据")
        assertEquals(LibraryFakeUpstream.USERNAME, fake.library.lastPostedUsername)
        assertEquals(LibraryFakeUpstream.PASSWORD, fake.library.lastPostedPassword)

        val site = auth.librarySite
        assertTrue(site.hasLogin, "登录后站点应是已登录态")
        val jar = auth.sessionManager.backend(com.xjtu.toolbox.auth.AccessMode.NORMAL).cookieJar
        assertNotNull(jar.findCookieByName("TGC"), "统一认证的 TGC 应落在本端 cookie 存储里")
        assertNotNull(jar.findCookieByName("JSESSIONID"), "座位系统自己的会话 cookie 也应落盘")

        // 凭据自己记下来了（下次冷启动靠它免登），且两个输入框都清空了（红线：学号不上屏/不进日志）
        assertEquals(LibraryFakeUpstream.USERNAME to LibraryFakeUpstream.PASSWORD, store.load())
        assertEquals("", auth.password, "登录成功后界面上的密码字段应被清空")
        assertEquals("", auth.username, "登录成功后界面上的学号字段也应清空")

        // 文件权限：0600（同目录的 `SecureStore.jvm.kt` 那条口径）
        val file = File(requireNotNull(dataRootOverride), "${JvmCredentialStore.FILE_NAME}.properties")
        assertTrue(file.exists(), "凭据应落成文件：$file")
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(file.toPath()),
            "凭据文件只该给本用户读写",
        )
    }

    @Test
    fun `登不上：不落盘凭据，命名空间退回匿名，界面拿到的是中文短文案`() = withFakeCampus {
        val (auth, store) = newAuth()
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = "definitely-not-the-password"
        val ok = runBlocking { auth.login() }

        assertTrue(!ok, "错密码不该登得上")
        assertNull(store.load(), "失败一次就不该记住任何凭据")
        assertNull(AccountContext.activeAccountId, "失败后命名空间应退回匿名")
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
    fun `真登录之后：桌面端自己的取数读得出校区、区域、座位与我的预约`() = withFakeCampus {
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

    // ══════ 校历：免登录的那条路 ══════

    @Test
    fun `校历：免登录就能取到学期与假期（桌面端第二条真能用的路由）`() = withFakeCampus {
        // 注意：**没有任何登录**。这个接口是公开门户接口，`:data` 的 `SchoolCalendarApi` 直接用。
        // 基址指向假上游：真机那条是 https，纯 HTTP 假代理给不了 CONNECT 隧道（见夹具的 KDoc）；
        // 校历解析不看 URL，所以这不影响测的是不是真解析链路。
        val terms = runBlocking { SchoolCalendarApi(SchoolCalendarFakeUpstream.URL).terms() }

        assertEquals(2, terms.size, "假上游给了两个学期")
        val first = terms.first()
        assertEquals("2026-2027学年第一学期", first.termName)
        assertEquals(LocalDate.parse("2026-09-07"), first.startDate)
        // 结束日取的是考试周结束（exam_end），不是 end_date —— 口径在 `:core` 的解析里
        assertEquals(LocalDate.parse("2027-01-15"), first.endDate)
        assertEquals(18, first.totalWeeks)
        assertEquals(listOf("中秋节", "国庆节", "元旦"), first.events.map { it.name })
        // 国庆节那条要带上 specialEvents 里的说明文字（按标题对上）
        assertEquals("放假 7 天；调休与补课安排以教务处通知为准。", first.events[1].remark)

        // 默认选中哪一学期：今天（2026-10-09）在第一学期内 ⇒ 选它，而不是"最后一个"或"最老的"
        assertEquals(0, defaultTermIndex(terms, LocalDate.parse("2026-10-09")))
        assertEquals(5, first.currentWeek(LocalDate.parse("2026-10-09")))
    }

    // ══════ 冷启动 ══════

    @Test
    fun `冷启动：同一个数据目录里新开一个 DesktopAuth，恢复之后不再提交密码`() = withFakeCampus {
        val (first, _) = newAuth()
        assertTrue(login(first))
        val postsAfterLogin = fake.library.credentialPosts.get()

        // 模拟冷启动：同一个落盘目录、全新的装配（新的 SessionManager、新的站点实例）
        val (restarted, store) = newAuth()
        assertEquals(1, fake.library.credentialPosts.get(), "新建装配本身不该触发任何登录")
        restarted.restore()

        assertTrue(restarted.loggedIn, "有落盘凭据 ⇒ 直接进已登录态，不该再让用户输一遍")
        assertEquals("", restarted.username, "恢复不该把学号填进登录页的输入框（红线）")
        assertEquals(LibraryFakeUpstream.USERNAME to LibraryFakeUpstream.PASSWORD, store.load())

        val site: SiteSession = restarted.librarySite
        val seats = assertIs<SeatResult.Success>(runBlocking { restarted.librarySource.seats("north2east") })
        assertEquals(listOf("A01", "A02", "A10"), seats.seats.map { it.seatId })
        assertEquals(postsAfterLogin, fake.library.credentialPosts.get(), "恢复 + 取数都不该再提交密码")
    }

    // ══════ 会话失效：内核自己重登 + 重放（与 :data 那条同一口径）══════

    @Test
    fun `会话失效时内核自动重登并重放，调用方拿到正确结果`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth))
        val site = auth.librarySite
        val epochBefore = site.loginEpoch
        val postsBefore = fake.library.credentialPosts.get()
        val ticketsBefore = fake.library.tickets.get()

        // 让下一枪业务请求回一个**真的 CAS 登录页**（`CasLoginPages` 认得出）：
        // 内核应判定认证失效 → 重认证 → 重放同一条请求。
        fake.library.expireNextBusinessRequest()
        val areas = runBlocking { auth.librarySource.areas("xingqing2floor") }
        assertEquals(2, areas.size, "重放之后应拿到真数据：$areas")

        assertTrue(site.loginEpoch > epochBefore, "应真的重认证过一次")
        assertTrue(fake.library.tickets.get() > ticketsBefore, "重认证走的是真链路（CAS 又签了 ticket）")
        assertEquals(postsBefore, fake.library.credentialPosts.get(), "TGC 还在 ⇒ 重认证应当免密，不再提交密码")
    }

    @Test
    fun `一直回登录页：抛共享基类的会话失效异常，由共享屏捕获去重登`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth))

        fake.library.loginMode.set(true)
        val e = assertFailsWith<SessionExpiredException> {
            runBlocking { auth.librarySource.areas("xingqing2floor") }
        }
        assertEquals("图书馆", e.siteName)
        assertEquals("图书馆登录态已失效", e.message)
    }

    // ══════ 退出登录 ══════

    @Test
    fun `退出登录：凭据、cookie、站点快照一起删（共享机器上不留登录态）`() = withFakeCampus {
        val (auth, store) = newAuth()
        assertTrue(login(auth))
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
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
        assertEquals(2, fake.library.credentialPosts.get(), "第二次登录应重新提交凭据（不是靠残留会话直通）")
    }

    /** `LibraryPages.BASE_URL` 与夹具的 `LIBRARY_BASE` 必须一致 —— 不一致说明有人改了生产基址。 */
    @Test
    fun `夹具与生产都指向同一个座位系统基址`() {
        assertEquals(LibraryFakeUpstream.LIBRARY_BASE, LibraryPages.BASE_URL)
    }

    // ══════ 黄页：另一条免登录的路（Ktor 接口 ⇒ 用 MockEngine，不需要服务器）══════

    @Test
    fun `黄页：免登录就能取到机构通讯录（停用的被滤掉，按 sort 与 id 排序）`() {
        // 没有 `withFakeCampus`：这条根本不需要会话，也不走 `HttpClients.base`
        //（`:core` 的 `YellowPageApi` 收一个 `HttpClient`，端口在调用方一侧）。
        val data = runBlocking { YellowPageApi(mockYellowPageClient()).getData() }

        assertEquals(listOf("教务处", "学生工作部（处）"), data.categories.map { it.name })
        // (sort, id) 升序：(1,11) → (1,20) → (2,12)
        assertEquals(
            listOf("教学运行中心", "学生事务大厅", "综合办公室"),
            data.departments.map { it.name },
        )
        // 电话里的 `/` 拆成两条（`phoneItems`），屏上因此有两个可拨号码
        assertEquals(listOf("029-82668891", "029-82668892"), data.departments[1].phoneItems)
        // 「数据更新于」的格式化口径（与 `:core` 那条单测同一个期望值）
        assertEquals("2026年08月01日", data.updateTime)
    }
}
