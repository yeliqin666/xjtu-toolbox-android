package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.defaultTermIndex
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.emptyroom.RoomSource
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.FitnessFakeUpstream
import com.xjtu.toolbox.inbox.AppInboxSource
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxData
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.fitness.FitnessProtocol
import com.xjtu.toolbox.fitness.FitnessYear
import com.xjtu.toolbox.judge.JudgeCard
import com.xjtu.toolbox.judge.UndergraduateJudgeSource
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.card.allTransactions
import com.xjtu.toolbox.auth.CampusCardLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.auth.VenueLogin
import com.xjtu.toolbox.auth.YwtbLogin
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.VenueFakeUpstream
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.SchoolCourseQuery
import com.xjtu.toolbox.score.scoreReportSource
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

    // ══════ 全校课表与成绩：同一个教务站点的两条路由 ══════

    /**
     * 从**真登录链**（窗口里输凭据 → CAS 表单 POST → 回跳教务）走到「全校课表」那一屏的取数。
     *
     * 期望值全部来自 `:testkit` 的 `JwxtFakeUpstream`（夹具）—— 不是从跑通的实现里抄的。
     * 两条路由共用一个站点，所以这里连着把成绩报表也读一遍：会话只有一份（`auth.jwxtSite`），
     * 只要一条链能站在同一个站点上取两种数，就说明站点注册与挂载都对。
     */
    @Test
    fun `真登录之后：教务自己走完 CAS，全校课表与成绩报表都读得出夹具样本`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议
        assertEquals(com.xjtu.toolbox.auth.XJTULogin.JWXT_URL, JwxtFakeUpstream.HOME_URL)

        // ── 会话是 CAS 回跳那条链里拿到的（不是往快照里塞的）──
        val site = auth.jwxtSite
        assertTrue(site.hasLogin, "登录页那一步应当把教务站点也登起来（尽力预热）")
        assertTrue(fake.jwxt.casRedirects.get() >= 1, "没带 ticket 时应当被交给统一认证")
        assertEquals(1, fake.jwxt.ticketLandings.get(), "CAS 回跳只该落在教务首页一次")
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // ── 全校课表（`AppRoute.SchoolCourse` 那一屏用的就是这个源）──
        val courses = AppSchoolCourseSource(site)
        assertEquals(
            listOf(
                JwxtFakeUpstream.TERM_NEW to JwxtFakeUpstream.TERM_NEW_NAME,
                JwxtFakeUpstream.TERM_OLD to JwxtFakeUpstream.TERM_OLD_NAME,
                // 夹具第三行只有 DM ⇒ 名字退回 DM
                JwxtFakeUpstream.TERM_BARE to JwxtFakeUpstream.TERM_BARE,
            ),
            runBlocking { courses.terms() }.map { it.code to it.name },
        )
        assertEquals(JwxtFakeUpstream.TERM_NEW, runBlocking { courses.currentTerm() })
        assertEquals(
            listOf(JwxtFakeUpstream.DEPT_MATH, JwxtFakeUpstream.DEPT_MECH, JwxtFakeUpstream.DEPT_PHYSICS),
            runBlocking { courses.departments() }.map { it.name },
        )

        // 筛条件留空（界面上就是「不限」），学期用当前学期
        val page = runBlocking {
            courses.query(SchoolCourseQuery(termCode = JwxtFakeUpstream.TERM_NEW), page = 1, pageSize = 20)
        }
        assertEquals(JwxtFakeUpstream.TOTAL_SIZE, page.totalSize)
        assertEquals(
            listOf(
                JwxtFakeUpstream.COURSE_1_NAME,
                JwxtFakeUpstream.COURSE_2_NAME,
                JwxtFakeUpstream.COURSE_3_NAME,
            ),
            page.courses.map { it.courseName },
        )
        assertEquals(5.0, page.courses[0].credit)
        assertEquals(30, page.courses[0].remaining, "150 容量 - 120 已选")
        // 夹具刻意没给第二行的选课人数 ⇒ `safeInt` 的默认值 0（不是「猜一个」）
        assertEquals(0, page.courses[1].enrollCount)

        // ── 成绩报表：学号用登录时写进去的那个（桌面端屏上就是这么取的）──
        val studentId = assertNotNull(AccountContext.activeAccountId, "登录之后应当有账号 id")
        val grades = runBlocking { scoreReportSource(site, studentId).grades() }
        assertEquals(JwxtFakeUpstream.STUDENT_ID, fake.jwxt.lastReportStudentId, "报表请求该带登录那个学号")
        assertEquals(
            listOf(
                JwxtFakeUpstream.SCORE_1_NAME to "95",
                JwxtFakeUpstream.SCORE_2_NAME to "优秀",
                JwxtFakeUpstream.SCORE_3_NAME to "88",
            ),
            grades.map { it.courseName to it.score },
        )
        // 学期分组口径：标题都读成了可比的学期代码，且夏季小学期得到 `-3`
        assertEquals(
            listOf(JwxtFakeUpstream.SCORE_TERM_SUMMER, JwxtFakeUpstream.SCORE_TERM_SPRING),
            grades.map { it.term }.distinct().sortedDescending(),
        )
    }

    /**
     * 两条路由**共用一份站点会话**：进门时那一发 `ensureSession("jwxt")` 是幂等的 ——
     * 第二条路由（同一轮里已登过）不再多打一次 CAS。
     */
    @Test
    fun `教务那两条路由共用一份会话：第二条进门不再重登`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth))
        val landings = fake.jwxt.ticketLandings.get()
        val posts = fake.library.credentialPosts.get()

        runBlocking { auth.ensureSession(DesktopAuth.JWXT_SITE_KEY) }
        runBlocking { auth.ensureSession(DesktopAuth.JWXT_SITE_KEY) }

        assertEquals(landings, fake.jwxt.ticketLandings.get(), "会话还在新鲜窗口内，不该再走一遍 CAS")
        assertEquals(posts, fake.library.credentialPosts.get(), "也不该再提交一次凭据")
    }

    // ══════ 评教：第八条真数据路由（本科那条链路）══════

    /**
     * 真登录链之后走「评教」那一屏用的那个源（`UndergraduateJudgeSource`）—— 桌面端 `AppRoute.Judge`
     * 本科那一条就是它（研究生那条是 `GraduateJudgeSource`，它自己 `ensureSite` 两个站点）。
     *
     * 期望值全部来自 `:testkit` 的 [JwxtFakeUpstream] 夹具：未评两份（过程在前、期末在后）、已评一份，
     * 以及 `card()` 的三项口径（课程 / 教师 / 标签）—— 屏上画的就是这三个字段，标签由 `PGLXDM` 换算
     * （01 期末 / 05 过程），key 是 `WJDM_JXBID_BPR`（撤回时按它定位）。
     *
     * ⚠️ 进门那一发走的是 `DesktopSiteGate` 干的事（`ensureSession("jwxt")`）：评教与全校课表 / 成绩
     * 是**同一个站点、同一份会话**，所以这里顺带钉住「评教不会再多登一次」。
     */
    @Test
    fun `真登录之后：本科评教读出夹具样本，未评两份与卡片口径都对`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth))
        val postsAfterLogin = fake.library.credentialPosts.get()

        runBlocking { auth.ensureSession(DesktopAuth.JWXT_SITE_KEY) }
        assertEquals(
            postsAfterLogin,
            fake.library.credentialPosts.get(),
            "评教与课表/成绩共用一份会话，不该再提交一次凭据",
        )

        // 桌面端选哪条链路看的是 `AccountContext` 里的身份（默认本科生 ⇒ 走教务那一条）
        assertEquals(AccountType.UNDERGRADUATE, AccountContext.activeAccountType)
        val username = assertNotNull(AccountContext.activeAccountId, "登录之后应当有账号 id")
        val source = UndergraduateJudgeSource(auth.jwxtSite, username)

        val (unfinished, finished) = runBlocking { source.load() }
        assertEquals(
            listOf(
                JwxtFakeUpstream.JUDGE_COURSE_MID,
                JwxtFakeUpstream.JUDGE_COURSE_FINAL,
                JwxtFakeUpstream.JUDGE_COURSE_FINAL_BARE,
            ),
            unfinished.map { it.KCM },
        )
        assertEquals(listOf(JwxtFakeUpstream.JUDGE_COURSE_DONE), finished.map { it.KCM })

        assertEquals(
            listOf(
                JudgeCard(
                    key = "${JwxtFakeUpstream.JUDGE_WJDM_MID}_${JwxtFakeUpstream.JUDGE_JXBID_MID}_" +
                        JwxtFakeUpstream.JUDGE_TEACHER_MID,
                    course = JwxtFakeUpstream.JUDGE_COURSE_MID,
                    teacher = JwxtFakeUpstream.JUDGE_TEACHER_MID,
                    tag = "过程评教",
                ),
                JudgeCard(
                    key = "${JwxtFakeUpstream.JUDGE_WJDM_FINAL}_${JwxtFakeUpstream.JUDGE_JXBID_FINAL}_" +
                        JwxtFakeUpstream.JUDGE_BPR_FINAL,
                    course = JwxtFakeUpstream.JUDGE_COURSE_FINAL,
                    teacher = JwxtFakeUpstream.JUDGE_TEACHER_FINAL,
                    tag = "期末评教",
                ),
                JudgeCard(
                    key = "${JwxtFakeUpstream.JUDGE_WJDM_FINAL_BARE}_" +
                        "${JwxtFakeUpstream.JUDGE_JXBID_FINAL_BARE}_示例庚",
                    course = JwxtFakeUpstream.JUDGE_COURSE_FINAL_BARE,
                    teacher = JwxtFakeUpstream.JUDGE_TEACHER_FINAL_BARE,
                    tag = "期末评教",
                ),
            ),
            unfinished.map { source.card(it) },
        )
        // 已评那一张：本科端可提交也可撤回（`canSubmit` 默认 true、`undo` 非空 ⇒ 屏上会出现那两个按钮）
        assertEquals("期末评教", source.card(finished[0]).tag)
        assertNotNull(source.undo, "本科端能撤回（屏上那个按钮据此出现）")
        assertEquals("，确定继续？", source.confirmText)
    }

    // ══════ 校园卡：第九条真数据路由 ══════

    /**
     * 真登录链之后走「校园卡」那一屏用的那个源（`:data` 的 `AppCampusCardSource`，桌面端
     * `AppRoute.CampusCard` 就是它，缓存传 `null` —— 桌面没有按账号分文件的宿主存储）。
     *
     * 期望值全部来自 `:testkit` 的 [CampusCardFakeUpstream]：卡号 / 姓名 / 学号 / 余额 / 待入账 /
     * 有效期 / 卡类型，以及七条流水的金额、商户名、余额 —— 不是从跑通的实现里抄的。
     *
     * 进门那一发走的是 `DesktopSiteGate` 干的事（`ensureSession("campus_card")`）：这里顺带钉住
     * 「登录页那一步已经尽力预热过它 ⇒ 进屏不会再登一次」。
     */
    @Test
    fun `真登录之后：校园卡自己走完 CAS，卡面与流水都读得出夹具样本`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议
        assertEquals(CampusCardLogin.LOGIN_URL, CampusCardFakeUpstream.HOME_URL)

        // ── 登录页那一步应当已经把这个站点预热起来（尽力预热，失败只记不抛）──
        assertTrue(fake.campusCard.casRedirects.get() >= 1, "没带 ticket 时应当被交给统一认证")
        assertEquals(1, fake.campusCard.ticketLandings.get(), "CAS 回跳只该落在 ncard 入口一次")
        assertEquals(1, fake.campusCard.tokenCalls.get(), "ticket 只该换一次 JWT")
        assertTrue(fake.campusCard.userInfoCalls.get() >= 1, "登录链上应当拉过一次用户资料")
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // 进门那一发（Gate 干的事）是幂等的：会话还新鲜，不该再走一遍 CAS
        val landings = fake.campusCard.ticketLandings.get()
        val posts = fake.library.credentialPosts.get()
        val site = auth.campusCardSite
        runBlocking { auth.ensureSession(DesktopAuth.CAMPUS_CARD_SITE_KEY) }
        assertTrue(site.hasLogin, "登录页那一步应当把校园卡站点也登起来")
        assertEquals(landings, fake.campusCard.ticketLandings.get(), "会话还在新鲜窗口内，不该再走一遍 CAS")
        assertEquals(posts, fake.library.credentialPosts.get(), "也不该再提交一次凭据")

        // ── 取数：`AppRoute.CampusCard` 那一屏用的就是这个源（缓存传 null）──
        val source = AppCampusCardSource(site)
        val card = runBlocking { source.card() }
        assertEquals(CampusCardFakeUpstream.CARD_ACCOUNT, card.account)
        assertEquals(CampusCardFakeUpstream.USER_NAME, card.name)
        assertEquals(CampusCardFakeUpstream.STUDENT_NO, card.studentNo)
        assertEquals(CampusCardFakeUpstream.BALANCE_CENTS / 100.0, card.balance)
        assertEquals(CampusCardFakeUpstream.PENDING_CENTS / 100.0, card.pendingAmount)
        assertEquals(CampusCardFakeUpstream.EXPIRE_DATE, card.expireDate)
        assertEquals(CampusCardFakeUpstream.CARD_TYPE, card.cardType)
        assertEquals(false, card.lostFlag)
        assertEquals(false, card.frozenFlag)

        // 屏首屏走的就是 `allTransactions`（最多 12 页、允许残缺），夹具只有一页 ⇒ 只打一枪
        val transactions = runBlocking {
            source.allTransactions(startDate = LocalDate(2026, 10, 1), endDate = LocalDate(2026, 10, 10))
        }
        assertEquals(CampusCardFakeUpstream.TOTAL, transactions.size)
        assertEquals(
            listOf(
                CampusCardFakeUpstream.MERCHANT_CANTEEN,
                CampusCardFakeUpstream.MERCHANT_CANTEEN,
                "充值",
                CampusCardFakeUpstream.MERCHANT_DUMPLING,
                CampusCardFakeUpstream.MERCHANT_UNKNOWN,
                CampusCardFakeUpstream.MERCHANT_CARD_CENTER,
                CampusCardFakeUpstream.MERCHANT_REFUND,
            ),
            transactions.map { it.displayMerchant },
        )
        assertEquals(
            listOf(
                -CampusCardFakeUpstream.TX_LUNCH_AMOUNT_CENTS / 100.0,
                -CampusCardFakeUpstream.TX_BREAKFAST_AMOUNT_CENTS / 100.0,
                CampusCardFakeUpstream.TX_RECHARGE_AMOUNT_CENTS / 100.0,
                -CampusCardFakeUpstream.TX_QRCODE_AMOUNT_CENTS / 100.0,
                -CampusCardFakeUpstream.TX_UNKNOWN_OUT_AMOUNT_CENTS / 100.0,
                CampusCardFakeUpstream.TX_UNKNOWN_IN_AMOUNT_CENTS / 100.0,
                CampusCardFakeUpstream.TX_REFUND_AMOUNT_CENTS / 100.0,
            ),
            transactions.map { it.amount },
        )
        assertEquals(1, fake.campusCard.turnoverCalls.get(), "总数到齐 ⇒ 屏的首屏只打一页流水的枪")
    }

    // ══════ 空闲教室：第十条真数据路由 ══════

    /**
     * 真登录链之后走「空闲教室」那一屏用的那个源（`:data` 的 `AppEmptyRoomSource`，桌面端
     * `AppRoute.EmptyRoom` 就是它：落盘传 `null`）。
     *
     * 这条同时钉住**那个路由为什么不套 `DesktopSiteGate`**：`AppRoute.EmptyRoom.loginType` 是 null
     * （三档数据源要登的站点不同）⇒ 会话由源自己 ensure。所以这里也顺手钉住「登录页那一步**没有**
     * 预热这个站点」：`js` 刻意不在 `DesktopAuth.SESSION_SITE_KEYS` 里（只有这一屏的一档要它）。
     *
     * 期望值全部来自 `:testkit` 的 [EmptyRoomFakeUpstream]：楼顺序、四种状态、人数、课程与教师、
     * 当天课表的 11 节占用与座位数 —— 不是从跑通的实现里抄的。
     */
    @Test
    fun `真登录之后：空闲教室自己把智慧教室会话登起来，三档里两档读得出夹具样本`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // ── 登录页那一步不该碰这个站点（它不在 SESSION_SITE_KEYS 里）──
        val js = assertNotNull(
            auth.sessionManager.getSiteOrNull(DesktopAuth.JS_SITE_KEY),
            "js 站点得注册好 —— AppEmptyRoomSource 构造时就按它取会话，没注册那一档永远不可用",
        )
        assertTrue(!js.hasLogin, "登录页那一步没有预热它（只有这一屏的一档要它）")
        assertEquals(0, fake.emptyRoom.tokenCalls.get(), "没进那一屏就不该走这一趟 CAS + 换令牌")

        // ── 进屏：`AppRoute.EmptyRoom` 用的就是这个源 ──
        val source = AppEmptyRoomSource(auth.sessionManager)
        assertEquals(
            listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT),
            source.availableSources,
            "桌面能提供三档（实时状态 / CDN 课表 / 直查教务）",
        )

        // 第一枪实时状态：源自己 `ensureSite("js")` ⇒ 走完整 CAS + 用票换令牌
        val snapshot = runBlocking { source.liveSnapshot(EmptyRoomFakeUpstream.LIVE_CAMPUS, force = true) }
        assertTrue(js.hasLogin, "实时状态那一档自己把智慧教室会话登起来了")
        assertEquals(1, fake.emptyRoom.ticketLandings.get(), "CAS 回跳只该落在 js 入口一次")
        assertEquals(1, fake.emptyRoom.tokenCalls.get(), "票据只该换一次令牌")
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // ── 教室列表与状态：全部来自夹具原文 ──
        assertEquals(listOf(EmptyRoomFakeUpstream.BUILDING_A, EmptyRoomFakeUpstream.BUILDING_E), snapshot.buildings)
        assertEquals(
            listOf(
                EmptyRoomFakeUpstream.ROOM_A101,
                EmptyRoomFakeUpstream.ROOM_A102,
                EmptyRoomFakeUpstream.ROOM_E303,
                EmptyRoomFakeUpstream.ROOM_E305,
            ),
            snapshot.rooms.map { it.name },
        )
        assertEquals(2, snapshot.freeCount, "两间空闲")
        assertEquals(1, snapshot.inUseCount, "一间「其它使用」（没排课但有人）")
        assertEquals(1, snapshot.inClassCount, "一间上课中")
        val inClass = snapshot.rooms.first { it.name == EmptyRoomFakeUpstream.ROOM_A101 }
        assertEquals(EmptyRoomFakeUpstream.LIVE_COURSE, inClass.course)
        assertEquals(EmptyRoomFakeUpstream.LIVE_TEACHER, inClass.teacher)
        assertEquals(EmptyRoomFakeUpstream.LIVE_IN_CLASS_PEOPLE, inClass.people)
        assertEquals(EmptyRoomFakeUpstream.SEATS_A101, inClass.seats)

        // ── CDN 那一档：屏进实时状态时会顺带取一份当天课表（按教室名对上课节条）──
        val schedule = runBlocking {
            source.rooms(
                campus = EmptyRoomFakeUpstream.LIVE_CAMPUS,
                buildings = snapshot.buildings.toSet(),
                date = EmptyRoomFakeUpstream.DATE,
                direct = false,
                force = true,
                onProgress = { _, _ -> },
            )
        }
        val byName = schedule.associateBy { it.name }
        assertEquals(EmptyRoomFakeUpstream.SEATS_A101, byName.getValue(EmptyRoomFakeUpstream.ROOM_A101).size)
        assertEquals(
            listOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0),
            byName.getValue(EmptyRoomFakeUpstream.ROOM_A101).status,
            "上午排满、下午空着（11 节状态）",
        )
        assertEquals(List(11) { 0 }, byName.getValue(EmptyRoomFakeUpstream.ROOM_A102).status)
        assertTrue(fake.emptyRoom.cdnCalls.get() >= 1, "CDN 那一档应当打过夹具")

        // 座位数（课表页的课程详情要显示「XX座」）
        assertEquals(
            EmptyRoomFakeUpstream.SEATS_A101,
            runBlocking { source.seatCount(EmptyRoomFakeUpstream.ROOM_A101) },
        )

        // 桌面没有按账号分命名空间的宿主存储（传 null）⇒ 两处磁盘兜底都只能是 null
        assertNull(source.readStaleLive(EmptyRoomFakeUpstream.LIVE_CAMPUS))
        assertNull(
            source.readStaleRooms(
                EmptyRoomFakeUpstream.LIVE_CAMPUS,
                setOf(EmptyRoomFakeUpstream.BUILDING_A),
                EmptyRoomFakeUpstream.DATE,
                direct = false,
            ),
        )
    }

    // ══════ 体育场馆：第十一条真数据路由 ══════

    /**
     * 真登录链之后走「体育场馆」那一屏用的那个源（`:data` 的 `AppVenueSource`，桌面端
     * `AppRoute.Venue` 就是它：`canBook = false` —— 这一端没有滑块控件，下单那一步走不完）。
     *
     * 这条同时钉住那一条登录链：场馆站的入口不是它自己，而是 `org.xjtu.edu.cn` 的 OAuth2
     *（appId=1659）→ CAS → 回跳 `/web/cas/oauth2url.html` → 首页 userno。场馆站本身是**明文 http**
     *（不走 CONNECT 隧道）；只有 OAuth 入口那一跳是 https。
     *
     * 期望值全部来自 `:testkit` 的 [VenueFakeUpstream]：9 个场馆（两页拼起来，`id=0` 那行被跳过）、
     * 某天七个时段（可订 + 已占两路合并后排序）、我的订单两条 —— 不是从跑通的实现里抄的。
     */
    @Test
    fun `真登录之后：体育场馆走完 OAuth 与 CAS，场馆列表与时段都读得出夹具样本`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议
        assertEquals(VenueLogin.BASE_URL, VenueFakeUpstream.ORIGIN)
        assertEquals(VenueLogin.VENUE_OAUTH_URL, VenueFakeUpstream.OAUTH_URL)

        // ── 登录页那一步已经尽力预热过它（它在 SESSION_SITE_KEYS 里）──
        assertTrue(fake.venue.oauthCalls.get() >= 1, "OAuth 入口那一跳应当被打开过")
        assertEquals(1, fake.venue.ticketLandings.get(), "CAS 回跳只该落在那个回调上一次")
        assertTrue(fake.library.tickets.get() >= 1, "统一认证应当真签过 ticket")

        // 进门那一发（Gate 干的事）：会话已经新鲜，不该再走一遍 CAS
        runBlocking { auth.ensureSession(DesktopAuth.VENUE_SITE_KEY) }
        val landings = fake.venue.ticketLandings.get()
        val posts = fake.library.credentialPosts.get()
        val site = auth.venueSite
        assertTrue(site.hasLogin, "登录页那一步应当把场馆站点也登起来")
        runBlocking { auth.ensureSession(DesktopAuth.VENUE_SITE_KEY) }
        assertEquals(landings, fake.venue.ticketLandings.get(), "会话还在新鲜窗口内，不该再走一遍 CAS")
        assertEquals(posts, fake.library.credentialPosts.get(), "也不该再提交一次凭据")

        // ── 取数：`AppRoute.Venue` 那一屏用的就是这个源 ──
        val source = AppVenueSource(site, canBook = false)
        assertEquals(false, source.canBook, "桌面没有滑块控件 ⇒ 如实声明不能下单")
        assertTrue(source.canCancel, "取消不需要滑块 ⇒ 这一件照旧能做")

        val venues = runBlocking { source.venues() }
        assertEquals(
            listOf(
                VenueFakeUpstream.VENUE_A_NAME,
                VenueFakeUpstream.VENUE_B_NAME,
                "示例场馆丙",
                "",
                VenueFakeUpstream.VENUE_F_NAME,
                "示例场馆庚",
                "示例场馆辛",
                VenueFakeUpstream.VENUE_ASCII_NAME,
                VenueFakeUpstream.VENUE_GBK_NAME,
            ),
            venues.map { it.name },
        )
        assertEquals(2, fake.venue.listCalls.get(), "第一页满 8 条 ⇒ 必须再要一页")
        assertEquals(VenueFakeUpstream.VENUE_A_ADDRESS, venues.first().address)
        assertEquals(VenueFakeUpstream.VENUE_A_ICON, venues.first().iconType)
        assertEquals(VenueFakeUpstream.VENUE_A_ADVANCE_DAY, venues.first().advanceDay)
        assertEquals(VenueFakeUpstream.VENUE_A_ADVANCE_NUM, venues.first().advanceNum)

        // 时段：可订 + 已占两路合并后按 (时段, 场地名) 排序
        val date = "2026-10-12"
        val slots = runBlocking { source.slots(VenueFakeUpstream.VENUE_A_ID, date) }
        assertEquals(1, fake.venue.okCalls.get())
        assertEquals(1, fake.venue.lockCalls.get())
        assertEquals(
            listOf(5101L, 5102L, 5105L, 5103L, 5104L, 5202L, 5201L),
            slots.map { it.areaDetailId },
        )
        assertEquals(
            listOf(7, 1, 5, 6, 1, 0, 0),
            slots.map { it.surplus },
            "status==1 才是可订（全满的兜到 1）；已占那两格剩余为 0",
        )
        assertEquals(date, slots.first().date, "date 用的是请求里那一天")
        assertEquals(
            listOf("场地1", "场地2", "场地3", "场地1", "预订", "场地1", "场地3"),
            slots.map { it.areaName },
        )

        // 我的订单那一栏：两条（空 orderid 的行与数组里不是对象的元素都被丢掉）
        val orders = runBlocking { source.orders(page = 1, pageSize = 20) }
        assertEquals(
            listOf(VenueFakeUpstream.ORDER_PAID_ID, VenueFakeUpstream.ORDER_PENDING_ID),
            orders.orders.map { it.orderId },
        )
        assertEquals(2, orders.total)
        assertEquals(false, orders.hasMore)

        // 收藏：桌面与 App 看到的是同一份（`:core` 共享的 `VenueFavorites`，JVM 侧是内存 store）
        val before = runBlocking { source.favorites() }
        assertTrue(VenueFakeUpstream.VENUE_A_ID !in before, "这条用例开始时不该已收藏：$before")
        assertTrue(runBlocking { source.toggleFavorite(VenueFakeUpstream.VENUE_A_ID) })
        assertTrue(VenueFakeUpstream.VENUE_A_ID in runBlocking { source.favorites() })
        assertTrue(!runBlocking { source.toggleFavorite(VenueFakeUpstream.VENUE_A_ID) }, "再翻一次回到原位")
    }

    // ══════ 消息收纳：第十二条真数据路由 ══════

    /**
     * 真登录之后走「消息收纳」那一屏用的那个源（`:data` 的 `AppInboxSource`，桌面端 `AppRoute.Inbox`
     * 就是它：构造参数只有会话管家，图书馆座位那条补拉的缝传 `null` —— 桌面端没有任何东西写
     * `LIBRARY` 那一类待办，早返回与空实现同义）。
     *
     * 它同时钉住那条登录链：一网通办门户 → CAS（凭据 RSA 加密）→ 回跳门户时 `ticket=` 是一枚
     * **JWT**（`YwtbLogin` 从它的 payload 里读出 idToken）→ 四路取数都带 `x-id-token`。
     * 四路 URL 与夹具**逐字相同**（夹具按「路径 + 查询串」逐字比对，不认识就 404）。
     *
     * 期望值全部来自 `:testkit` 的 [YwtbFakeUpstream]（两条消息 —— 四条样本里两条该被滤掉、两条事务
     * 中心待办、预约中心 3 条与校车 0 条），不是从跑通的实现里抄的；屏上读的就是下面这些 store 投影。
     */
    @Test
    fun `真登录之后：消息收纳自己登上一网通办，四路取数都读得出夹具样本`() = withFakeCampus {
        val (auth, _) = newAuth()
        assertTrue(login(auth), "用假下游的账号密码应当登得上：${auth.loginState}")

        // 夹具与生产必须指同一个门户（登录入口的 service 参数就是它）；四路 URL 由夹具逐字比对
        assertTrue(
            YwtbLogin.YWTB_LOGIN_URL.contains(YwtbFakeUpstream.HOST),
            "一网通办登录入口回的就是夹具那个门户：${YwtbLogin.YWTB_LOGIN_URL}",
        )

        // ── 登录页那一步已经尽力预热过它（它在 SESSION_SITE_KEYS 里）──
        assertTrue(fake.ywtb.portalLandings.get() >= 1, "登录页那一步应当把一网通办也登起来")
        runBlocking { auth.ensureSession(DesktopAuth.YWTB_SITE_KEY) }
        val landings = fake.ywtb.portalLandings.get()
        val posts = fake.library.credentialPosts.get()
        assertTrue(auth.sessionManager.getSite(DesktopAuth.YWTB_SITE_KEY).hasLogin, "登录页那一步应当把一网通办站点也登起来")
        runBlocking { auth.ensureSession(DesktopAuth.YWTB_SITE_KEY) }
        assertEquals(landings, fake.ywtb.portalLandings.get(), "会话还在新鲜窗口内，不该再走一遍 CAS")
        assertEquals(posts, fake.library.credentialPosts.get(), "也不该再提交一次凭据")

        // ── 取数：`AppRoute.Inbox` 那一屏用的就是这个源 ──
        val source = AppInboxSource(auth.sessionManager)
        val account = AccountContext.activeAccountId
        assertEquals(LibraryFakeUpstream.USERNAME, account, "进门那一发按登录账号分命名空间")
        assertTrue(source.isDue(account, System.currentTimeMillis()), "第一次进屏还没拉过 ⇒ 该拉")
        runBlocking { source.refresh(account) }

        assertEquals(1, fake.ywtb.messageCalls.get(), "消息那一路应当恰好被打一次")
        assertEquals(1, fake.ywtb.todoCalls.get(), "事务中心那一路应当恰好被打一次")
        assertEquals(1, fake.ywtb.bookingCalls.get(), "预约中心那一路应当恰好被打一次")
        assertEquals(1, fake.ywtb.busCalls.get(), "校车那一路应当恰好被打一次")
        assertEquals(YwtbFakeUpstream.BUS_URL, fake.ywtb.lastBusinessUrl.get(), "最后一枪就是校车那一条 URL")
        assertEquals(YwtbFakeUpstream.ID_TOKEN, fake.ywtb.lastIdToken.get(), "四路都只认 CAS 换来那颗 x-id-token")
        assertTrue(!source.isDue(account, System.currentTimeMillis()), "刚拉过 ⇒ TTL 内不再拉")

        // 桌面端没有图书馆座位那一路 ⇒ 有座位待办也只是空转（不抛、不用 Context）
        runBlocking {
            source.afterRefresh(
                InboxData(
                    todos = mapOf(
                        InboxCategories.LIBRARY to listOf(
                            InboxItem(id = "library:入馆签到", category = InboxCategories.LIBRARY, source = "图书馆", title = "座位 A01 待入馆签到"),
                        ),
                    ),
                ),
            )
        }

        // ── 屏读的就是 InboxStore（收纳按账号存）──
        val data = InboxStore.load(account)
        assertEquals(
            listOf("school:${YwtbFakeUpstream.MESSAGE_SIGNED_ID}", "school:${YwtbFakeUpstream.MESSAGE_BARE_ID}"),
            data.messages.map { it.id },
            "四条消息样本里两条该被滤掉（事务中心那条、座位那条）",
        )
        assertEquals(YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE, data.messages.first().source, "来源取正文末尾的落款")
        assertEquals(
            2,
            data.todos.getValue(InboxCategories.SCHOOL_TODO).size,
            "事务中心两条：一条有 taskId、一条没有（id 回落成「标题@addTime」）",
        )
        assertEquals(
            "todo:${YwtbFakeUpstream.TODO_ID}",
            data.todos.getValue(InboxCategories.SCHOOL_TODO).first().id,
        )
        val booking = data.todos.getValue(InboxCategories.BOOKING).single()
        assertEquals(
            "预约中心 有 ${YwtbFakeUpstream.BOOKING_RESERVATION_TOTAL} 个待使用的预约",
            booking.title,
            "预约与校车只报数量（校车 0 条 ⇒ 一条都不出）",
        )

        // 屏上那两栏的投影（`InboxRules` 是 :core 的口径，这里只验「真读得出东西」）
        val now = System.currentTimeMillis()
        assertEquals(2, InboxRules.groups(data, now).size, "两条消息两类")
        assertEquals(3, InboxRules.todos(data, now).size, "两条事务中心待办 + 一条预约")
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
