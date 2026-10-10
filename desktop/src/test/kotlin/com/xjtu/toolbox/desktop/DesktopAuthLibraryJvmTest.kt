package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.defaultTermIndex
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.fitness.FitnessProtocol
import com.xjtu.toolbox.fitness.FitnessYear
import com.xjtu.toolbox.faculty.FacultyApi
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
     *  1. **两件进程级前置必须在碰 `HttpClients` 之前装**（也就建任何 [DesktopAuth] 之前，
     *     见 [FakeCampusProxy.installFakeUpstreams]）：常驻 selector（`HttpClients.base` 会把当时的
     *     默认 `ProxySelector` 抄进自己的配置）与假上游那枚自签 https 证书的信任库
     *     （客户端一建出来就把平台 trust manager 抄走了）；
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

        FakeCampusProxy.installFakeUpstreams()
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
    // ══════ 体测：第五条真数据路由（要登录，而且这个站点是 https）══════

    /**
     * 走的是**真登录链**：桌面登录页那一步的 CAS 表单 POST（凭据 RSA 加密）之后，体测站点
     * 靠同一份凭据/TGC 自己走完 CAS → `LOGIN_URL?ticket=` → launch 回调 → `UserInfo`，
     * 会话参数落进站点快照；然后断言 `FitnessApi` 在那份快照上读出的东西。
     *
     * 期望值全部来自 `:testkit` 的夹具样本（`:testkit` 的 `FitnessFakeUpstream`），不是从跑通的
     * 实现里抄的：分数还会过 `:core` 那两条共享口径（`formatFitnessScore` 两位小数、
     * `fitnessItemName` 按 sex 换名），所以期望写法就是「夹具原文 + 那两条口径」。
     *
     * ⚠️ 这一条是**唯一**走 https 的用例：假上游为此自带 CONNECT 前置与一枚自签证书
     *（见 `:testkit` 的 `FakeUpstreamFront`）。最后一段特意把 cookie（含 CAS 的 TGC）全清掉
     * 再强制重登一次 —— 证明体测这条链自己就能从「CAS 表单 POST」一路走到 launch 回调，
     * 而不是靠图书馆那次留下的 TGC 免密直通。
     */
    @Test
    fun `真登录之后：体测站点自己走完 CAS 回跳，快照里落着 launch，读得出学年与成绩`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议
        assertEquals(FitnessProtocol.TARGET_HOST, FitnessFakeUpstream.HOST)
        assertEquals(FitnessProtocol.LOGIN_URL, FitnessFakeUpstream.LOGIN_URL)
        assertEquals(FitnessProtocol.H5_HOME_URL, FitnessFakeUpstream.H5_HOME_URL)
        assertEquals(FitnessProtocol.API_V3, FitnessFakeUpstream.API_V3)
        assertEquals(FitnessProtocol.USER_INFO_URL, FitnessFakeUpstream.USER_INFO_URL)
        assertEquals(FitnessProtocol.LEGACY_API_ROOT, FitnessFakeUpstream.LEGACY_API_ROOT)

        // ── 会话是从 CAS 回跳的那个 URL 里解出来的（不是往快照里塞的）──
        val site = auth.fitnessSite
        assertTrue(site.hasLogin, "登录页那一步应当把体测站点也登起来")
        assertEquals(1, fake.fitness.launchCallbacks.get(), "CAS 回跳只该发出一次 launch 回调")
        assertTrue(fake.fitness.casRedirects.get() >= 1, "没带 ticket 时应当被交给统一认证")
        assertTrue(fake.fitness.userInfoCalls.get() >= 1, "postLogin 里那一枪 UserInfo 应当打到夹具")
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // 快照里的字段：取数要的那八个 `SESSION_FIELDS` 一个不能少；
        // ⚠️ 七个必需字段里 `user_type` 是唯一被**消费掉**的 —— `extractLaunch` 拿它换算成 `role`
        // 之后就不原样留着（`SESSION_FIELDS` 里也没有它），所以那一项按 `role` 断言（见下）。
        for (field in FitnessProtocol.SESSION_FIELDS) {
            assertTrue(site.localToken[field]?.isNotBlank() == true, "快照里缺 $field：${site.localToken}")
        }
        for (field in FitnessProtocol.REQUIRED_LAUNCH_FIELDS - "user_type") {
            assertTrue(site.localToken[field]?.isNotBlank() == true, "快照里缺 $field：${site.localToken}")
        }
        assertEquals(FitnessFakeUpstream.UID, site.localToken["uid"])
        assertEquals(FitnessFakeUpstream.TOKEN, site.localToken["token"])
        assertEquals(FitnessFakeUpstream.STUDENT_NUM, site.localToken["student_num"])
        assertEquals(FitnessFakeUpstream.CARD_ID, site.localToken["card_id"])
        assertEquals(FitnessFakeUpstream.NONCE, site.localToken["nonce"])
        // role 由 user_type 换算（ROLE_BY_USER_TYPE），referer 取的是回调的碎片路径
        assertEquals(
            FitnessProtocol.ROLE_BY_USER_TYPE.getValue(FitnessFakeUpstream.USER_TYPE).toString(),
            site.localToken["role"],
        )
        assertEquals(FitnessProtocol.H5_HOME_URL, site.localToken["referer_url"])

        // ── 取数：`AppRoute.Fitness` 那一屏用的就是这个 `FitnessApi` ──
        val api = FitnessApi(site)
        val years = runBlocking { api.years() }
        assertEquals(
            listOf(
                FitnessYear(FitnessFakeUpstream.YEAR_OLD, FitnessFakeUpstream.YEAR_OLD_NAME, checked = false),
                FitnessYear(FitnessFakeUpstream.YEAR_NEW, FitnessFakeUpstream.YEAR_NEW_NAME, checked = true),
            ),
            years,
        )

        val score = runBlocking { api.score(FitnessFakeUpstream.YEAR_NEW) }
        assertTrue(fake.fitness.v3Calls.get() >= 2, "v3 端点应当真被打过（学年 + 成绩）")
        assertEquals(FitnessFakeUpstream.STUDENT_NUM, score.studentNumber)
        assertEquals(FitnessFakeUpstream.STUDENT_NAME, score.studentName)
        assertEquals("78.80", score.totalScore, "总分要过 `formatFitnessScore`（两位小数）")
        assertEquals("良好", score.totalGrade)
        assertEquals(FitnessFakeUpstream.SEX, score.sex)
        assertEquals(FitnessFakeUpstream.GRADE, score.grade)
        assertEquals(FitnessFakeUpstream.REPORT_STATUS, score.reportStatus)

        // 七个分项：名字（按 sex 换名）、分数、等级、配色都是 `FitnessApi.loadScore` 的口径
        // 夹具刻意没给 `50m_*` ⇒ 那一行落到「未测 / 缺项」
        assertEquals(
            listOf("身高 / 体重", "肺活量", "立定跳远", "坐位体前屈", "引体向上", "50 米", "1000 米"),
            score.items.map { it.name },
        )
        assertEquals(
            listOf("85.00", "4123.00", "2.31", "12.50", "9.00", "未测", "4.12"),
            score.items.map { it.value },
        )
        assertEquals(
            listOf("良好", "优秀", "良好", "优秀", "及格", "缺项", "良好"),
            score.items.map { it.grade },
        )
        assertEquals(
            listOf("good", "excellent", "good", "excellent", "pass", "", "good"),
            score.items.map { it.tone },
        )

        // ── legacy 那一路（`FitnessApi` 的兜底）：两路必须给出同一份业务数据 ──
        fake.fitness.v3Down.set(true)
        val viaLegacy = runBlocking { api.score(FitnessFakeUpstream.YEAR_NEW) }
        assertEquals(score, viaLegacy, "v3 挂了应当由 legacy 给出同一份成绩")
        assertTrue(fake.fitness.legacyCalls.get() >= 1, "应当真打到 legacy 端点")
        fake.fitness.v3Down.set(false)

        // ── 体测这条链自己就能登进来：把 cookie（含 TGC）全清掉再强制重登一次 ──
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        PersistentCookieJar("cookies_normal$suffix").clear()
        val postsBefore = fake.library.credentialPosts.get()
        runBlocking {
            site.ensureLogin(
                LibraryFakeUpstream.USERNAME,
                LibraryFakeUpstream.PASSWORD,
                force = true,
                userInitiated = true,
            )
        }
        assertEquals(
            postsBefore + 1,
            fake.library.credentialPosts.get(),
            "TGC 被清掉后，体测这次登录应当自己提交一次凭据（CAS 表单 POST）",
        )
        assertEquals(2, fake.fitness.launchCallbacks.get(), "并且又走了一遍 CAS 回的 launch 回调")
        assertEquals(FitnessFakeUpstream.TOKEN, site.localToken["token"], "快照里的 token 应当是夹具发的那份")
        assertEquals(years, runBlocking { FitnessApi(site).years() }, "重登之后取数照样读得出")
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

    // ══════ 「某个子系统挂了」不该把整个客户端挡在门外 ══════

    /**
     * 体测服务历史上真的返回过 502（见 `:data` 的 `FitnessSession` KDoc）。所以登录页那一步
     * **只硬要求主站（图书馆）**，其余站点是「尽力预热、失败只记不抛」；那些站点在**自己那一屏**
     * 进门时再补登（`ToolboxDesktopApp` 的 `DesktopSiteGate`）。
     *
     * 这一条就是那个设计的回归网：体测全站 503 时，登录必须照样成功、图书馆照样能读，
     * 而体测那一屏自己报错（不是把登录态弄坏）。
     */
    @Test
    fun `体测服务挂了也不阻塞登录（主站照常，进那一屏才报错）`() = withFakeCampus {
        fake.fitness.outage = true
        val (auth, _) = newAuth()

        assertTrue(login(auth), "体测挂了不该让登录失败：${auth.loginState}")
        assertTrue(auth.loggedIn, "登录态应当照常建立")

        // 主站（图书馆）硬要求，所以它必须真能用
        val seats = assertIs<SeatResult.Success>(runBlocking { auth.librarySource.seats("north2east") })
        assertEquals(listOf("A01", "A02", "A10"), seats.seats.map { it.seatId })

        // 体测那一屏自己失败（Gate 会把这条失败画成「重试」页），但不影响登录态
        assertTrue(
            runCatching { runBlocking { auth.ensureSession(DesktopAuth.FITNESS_SITE_KEY) } }.isFailure,
            "体测服务挂着时，补登它应当失败",
        )
        assertTrue(auth.loggedIn, "体测失败不该把登录态弄坏")
        assertEquals(0, fake.fitness.launchCallbacks.get(), "服务挂着时不该有成功的 launch 回调")
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

    // ══════ 教师检索：另一条免登录的路（OkHttpClient 可注入 ⇒ 一条拦截器，不需要服务器）══════

    @Test
    fun `教师检索：免登录就能检索到教师，缺的主页地址由英文接口补齐`() {
        // 同样不需要 `withFakeCampus`：`FacultyApi` 的 `OkHttpClient` 可注入，端口在调用方一侧。
        val api = FacultyApi(mockFacultyClient())
        val page = runBlocking { api.search() }

        assertEquals(4, page.total)
        assertEquals(listOf(20101L, 20102L, 20103L, 20104L), page.members.map { it.teacherId })
        // 名字两边的空格被 trim（夹具里刻意带着空格）
        assertEquals("示例甲", page.members[0].name)
        // 第 2 行在中文接口里没有 url ⇒ 英文接口按 teacherId 补上（电气学院过半的人都这样）
        assertTrue(
            page.members[1].homepageUrl.contains("example-b"),
            "英文接口应把主页地址补上：${page.members[1].homepageUrl}",
        )
        // 第 4 行是站外地址（ORCID）⇒ 原样返回、不算「空」，不该被英文接口覆盖
        assertEquals("https://orcid.org/0000-0000-0000-0000", page.members[3].homepageUrl)

        // 四张筛选 id 表从 search.jsp 的 HTML 里解析（免登录）
        val filters = runBlocking { api.loadFilters() }
        assertTrue(filters.colleges.isNotEmpty(), "学院表应解析出来：$filters")
    }
}
