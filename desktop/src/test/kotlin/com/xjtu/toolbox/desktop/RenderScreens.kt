package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * `./gradlew :desktop:renderScreens` —— 把屏**离屏**渲染成 PNG，作为桌面端的可视化证据。
 *
 * ## 它回答哪几条验收
 *
 * | 图 | 内容 | 证明什么 |
 * |---|---|---|
 * | `login.png` | 登录页（空输入框） | `:core` 的共享登录屏在桌面真画得出来；**不预填学号**（红线） |
 * | `library-after-login.png` | 图书馆座位屏，**用刚输入的凭据真登进去**之后的 | Stage A 的验收本体：桌面端自己登录、自己取数（`:data` 的会话内核 + `LibraryApi`） |
 * | `shell-after-login.png` | 整个外壳（底栏 + 图书馆屏） | 外壳与底栏在登录后正确切换 |
 * | `fitness.png` | 体测屏（真路由：外壳 → `AppRoute.Fitness` → `FitnessApi`） | 第五条真数据路由：https 站点、CAS 回跳后的 launch 会话、v3 取数 |
 * | `schoolcourse.png` | 全校课表屏（真路由 + 真查询：外壳 → `AppRoute.SchoolCourse` → `AppSchoolCourseSource`） | 第六条真数据路由：登录后的学期 / 开课单位 / 课程卡片都是夹具样本（屏不会自己发查询，这张图靠 semantics 点一下「搜索」，见 [clickByLabel]） |
 * | `scorereport.png` | 成绩报表屏（真路由：外壳 → `AppRoute.ScoreReport` → `scoreReportSource`） | 第七条真数据路由：学号取自登录时的 `AccountContext.activeAccountId`，图上按学期分组的成绩是夹具样本 |
 * | `judge.png` | 学生评教屏（真路由：外壳 → `AppRoute.Judge` → `UndergraduateJudgeSource`） | 第八条真数据路由：与课表/成绩同一个教务站点（同一份会话），上游是 `wspjyyapp`；图上未评那三张卡片的课名/教师/标签都是夹具样本 |
 * | `campuscard.png` | 校园卡屏（真路由：外壳 → `AppRoute.CampusCard` → `AppCampusCardSource`） | 第九条真数据路由：**https** 站点（ncard）、CAS 回跳那一跳上换 JWT，缓存传 `null`；图上余额 / 待入账 / 今日三餐与流水都是夹具样本 |
 * | `routes.png` | 「全部页面」索引页 | 如实列出「真能用 / 还没有数据源」，并给出退出登录入口 |
 * | `library-demo.png` | 同一屏 + 固定假数据 | 布局与组件本身可复现（不依赖网络/会话，改屏时用它对比） |
 *
 * ## 「真登进去」是怎么在没有真账号的机器上做到的
 *
 * 用 [LibraryFakeUpstream]（`:testkit`）扮演学校那一侧：座位系统 → 302 到统一认证 →
 * **表单 POST 凭据（密码 RSA 加密）** → 种 TGC → 签 ticket 回跳 → 座位系统会话。
 * 本地 HTTP 服务器当 **代理**（`ProxySelector`），URL 一个字符都不改 —— 会话内核里有一批
 * 按 host 判断的判据（`XJTULogin.casPath` 只认 `login.xjtu.edu.cn`、`LibrarySession.validateLogin`
 * 认 `rg.lib.xjtu.edu.cn`），重写 URL 会让它们全部落空，那测的就不是真内核
 * （这条与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest` 是同一条口径）。
 *
 * ⚠️ 两个坑（都是实测踩到的，注释在代码里）：
 * 1. `HttpClients.base` 是进程级 `by lazy`，会把当时的默认 `ProxySelector` **抄进自己的配置** ⇒
 *    selector 必须**常驻**、读一个可变端口，且在**建任何会话之前**装上；
 * 2. `XJTULogin` 取 RSA 公钥那一枪硬编码 `https://login.xjtu.edu.cn/cas/jwt/publicKey`，
 *    本地假上游给不了（要 CONNECT+TLS）⇒ 照真实路径预置一份缓存公钥（真机上那份由首次登录
 *    自动取回并存进凭据文件，见 [JvmCredentialStore.rsaPublicKey]）。
 *
 * ## ⚠️ 为什么这里**不画** MFA 弹窗
 *
 * `:core` 的 `MfaCodeDialog` 用的是 miuix 的 `WindowDialog`，它在桌面上的实现是
 * `androidx.compose.ui.window.Dialog` ⇒ **一个独立的 AWT 窗口**（`DialogWindow`）。
 * 离屏场景（[ImageComposeScene]）画不进另一扇窗，而没有显示服务器时连创建它都不行。
 * 所以二验那一屏的证据只能由 `:desktop:run` 在真窗口里给（也是它与 `:app` 那份逐条对齐的原因：
 * 同一个组件、同一套文案）。
 *
 * ## 为什么它在 test 源集里
 *
 * 它要 [LibraryFakeUpstream]（`:testkit`）——「任何密码都收」的假统一认证绝不能出现在交付物里。
 * 见 `desktop/build.gradle.kts` 的文件头。
 *
 * `exitProcess(0)` 是必要的：skiko 与 AWT 的事件线程不是守护线程，不主动退出这个 JavaExec 会挂着。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    // 先清掉上一次的 PNG：Stage 0 那几张（`library-live.png` / `shell-live.png`）是 campus-api 脚手架
    // 时代的证据，已经不对应今天的实现 —— 留着只会被当成「现在的桌面端长这样」。
    outDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.forEach { it.delete() }

    // ── 两个进程级前置，顺序都不能换 ──────────────────────────────────────────
    //
    // ① 落盘存储指到临时目录：证据运行绝不碰用户真实数据（`~/.local/share/xjtu-toolbox`），
    //    也绝不会把「假登录」产生的 cookie 留在一个真账号旁边。必须在任何
    //    `secureKeyValueStore` / `PersistentCookieJar` 被碰之前设。
    dataRootOverride = Files.createTempDirectory("xjtu-desktop-shots").toFile()
    // ② 常驻代理 selector（此刻端口 0 = 不走代理）+ 假上游那枚自签 https 证书的信任库。
    //    两者都必须在 `HttpClients.base` 第一次被初始化**之前**装：它抄下的是「没有代理」，
    //    而客户端建出来那一刻就把平台 trust manager 也抄走了（体测是 https）。
    //    详见文件头第 1 条坑与 `:testkit` 的 `FakeUpstreamFront`。
    FakeCampusProxy.installFakeUpstreams()

    fun shot(
        name: String,
        width: Int = 520,
        height: Int = 900,
        frames: Int = 12,
        /** 每帧渲染**之前**在 EDT 上跑一次 —— 需要「点一下」的屏用它（见 [clickByLabel]）。 */
        onFrame: ((frame: Int, scene: ImageComposeScene) -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        // 每个场景一个独立的 ViewModelStoreOwner：:core 的屏用 `viewModel { }` 建 VM，
        // 桌面不像 Android 那样自带一个（与 Main.kt 里给真窗口装的是同一个东西）。
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val scene = onEdt {
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    XJTUToolBoxTheme { content() }
                }
            }
        }
        try {
            var image = onEdt { scene.render(0L) }
            repeat(frames) { i ->
                // 帧间让出调用线程：EDT 就在这段空档里把 VM 的取数协程跑完
                Thread.sleep(400)
                image = onEdt {
                    // 需要「点一下」的屏在这里动手（见 [clickByLabel]）：必须在渲染前、且在 EDT 上
                    onFrame?.invoke(i, scene)
                    scene.render((i + 1) * 400_000_000L)
                }
            }
            val bytes = requireNotNull(image.encodeToData()) { "PNG 编码失败：$name" }.bytes
            val file = File(outDir, name)
            file.writeBytes(bytes)
            println("wrote ${file.absolutePath} (${bytes.size} bytes)")
        } finally {
            onEdt { scene.close() }
        }
    }

    /** 深度优先找第一个满足 [predicate] 的语义节点（合并树里按钮自己就带着子文本）。 */
    fun findNode(root: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        if (predicate(root)) return root
        return root.children.firstNotNullOfOrNull { findNode(it, predicate) }
    }

    /**
     * 在离屏场景里「点一下」写着 [label] 的那个控件 —— 靠 **semantics** 找它、直接调它的 `OnClick`，
     * **不靠坐标**：坐标会随布局漂，而 semantics 是屏自己声明的（`clickable`/`Button` 都会带上）。
     *
     * 为什么需要它：全校课表那一屏**不会**自己发查询 —— 那一枪要用户点「搜索」，而离屏场景没有鼠标。
     * 没有语义树（或按钮那时候还是 `enabled = false`）时返回 false，调用方下一帧再试。
     */
    fun clickByLabel(scene: ImageComposeScene, label: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        val target = findNode(root) { node ->
            val click = node.config.getOrNull(SemanticsActions.OnClick)
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            click != null && texts.any { it.text == label }
        } ?: return false
        return target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
    }


    // ── ① 登录页（空输入框 · 红线：不预填学号）────────────────────────────────
    // 走真外壳：没登录 ⇒ 它自己落在 LoginScreen 上，这张图同时证明「壳的切换」是对的。
    // 单给一个 DesktopAuth（临时数据目录里没有任何凭据 ⇒ 不会被 restore 静默带走）。
    shot("login.png", frames = 4) { ToolboxDesktopApp(DesktopAuth()) }

    // ── ② 图书馆屏 + 固定假数据：布局/组件本身可复现，与网络无关 ────────────────
    shot("library-demo.png", frames = 4) {
        LibraryScreen(
            source = DemoLibrarySource(),
            onBack = {},
            showFirstUseHint = false,
            onFirstUseHintRead = {},
        )
    }


    // ── ③ 黄页（免登录；Ktor 接口 ⇒ MockEngine 喂假响应，不需要服务器/代理）────────
    // 这张图要的是「有数据时屏长什么样」：类别标签、部门行、可拨号码。
    // 它**不走** FakeCampusProxy —— 黄页根本不需要会话（见 FakeKtorClients.kt 的 KDoc）。
    shot("yellowpage.png", frames = 8) {
        YellowPageScreen(
            api = YellowPageApi(mockYellowPageClient()),
            onBack = {},
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
    }

    // ── ④ 教师检索（免登录；OkHttpClient 可注入 ⇒ 拦截器喂假上游，不需要服务器/代理）────
    shot("faculty.png", frames = 12) {
        FacultyScreen(
            source = FacultyApiSource(FacultyApi(mockFacultyClient())),
            onBack = {},
            onOpenUrl = {},
        )
    }

    // ── ⑤⑥⑦⑧⑨ 真登录之后（同一份假上游：图书馆与体测要登录、校历不要）──────────────
    withFakeCampus { auth ->
        // 「用户在登录页敲了字」这一步：用的是假上游那组**编出来的**账号密码
        //（`LibraryFakeUpstream.USERNAME` = 2021000001，不是任何人的学号）。
        // 真窗口里这两个值来自键盘，这里来自夹具 —— 除此之外与 `:desktop:run` 走的是同一条路。
        auth.username = LibraryFakeUpstream.USERNAME
        auth.password = LibraryFakeUpstream.PASSWORD
        val ok = runBlocking { auth.login() }
        println(
            "真登录结果=$ok  状态=${auth.loginState}  " +
                "CAS 提交凭据次数=${fake.library.credentialPosts.get()}  签发的 ticket 数=${fake.library.tickets.get()}"
        )
        check(ok) { "假上游上的真登录应当成功：${auth.loginState}" }

        shot("library-after-login.png", frames = 20) {
            LibraryScreen(
                source = auth.librarySource,
                onBack = {},
                reAuthenticate = {
                    auth.sessionManager.ensureSite(DesktopAuth.LIBRARY_SITE_KEY, userInitiated = true)
                },
                showFirstUseHint = false,
                onFirstUseHintRead = {},
            )
        }
        shot("shell-after-login.png", frames = 20) { ToolboxDesktopApp(auth) }

        // 体测（第五条真数据路由）：这张图走**外壳 + 真路由**（`AppRoute.Fitness` → `FitnessApi`），
        // 所以它同时证明「路由真接上了」与「数据是真的」（学年胶囊 / 姓名 / 总分 / 七个分项）。
        // 站点是 **https**：假上游为此自带 CONNECT 前置与一枚自签证书（见 `:testkit` 的
        // `FakeUpstreamFront`）—— 这是「URL 一个字符都不改」那条口径在 https 站点的代价。
        shot("fitness.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Fitness)) }

        // 校历：**免登录**的公开门户接口 —— 与上面那张不同，它不需要任何会话。
        // 这是「屏在 :core + 取数在 :data」的直接报偿：搬一个 35 行的 IO 适配器就多一屏。
        // 基址指向假上游（`SchoolCalendarApi` 的基址本来就是构造参数）：真机那条是 https，
        // 而这里只需要「取数链路是真的」—— 直接指过来最省事，也不用为它再签一枚证书。
        shot("calendar.png", frames = 12) {
            SchoolCalendarScreen(
                source = SchoolCalendarApi(SchoolCalendarFakeUpstream.URL),
                onBack = {},
            )
        }
        // 全校课表（第六条真数据路由）：也是**真路由**（外壳 → `DesktopSiteGate` → `AppSchoolCourseSource`）。
        // ⚠️ 这一屏**不会**自己发查询 —— 那一枪要用户点「搜索」，所以这里用 semantics 点一下
        // （见 [clickByLabel]）：点不到就多试几帧，因为「搜索」按钮要等当前学期加载完才 `enabled`。
        var searched = false
        shot(
            "schoolcourse.png",
            frames = 22,
            onFrame = { frame, scene -> if (!searched && frame >= 5) searched = clickByLabel(scene, "搜索") },
        ) {
            ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.SchoolCourse))
        }
        // 点不到就是「这张图里没有课程」，响亮地失败 —— 别把空屏当证据交出去
        check(searched) { "schoolcourse.png：没点到「搜索」按钮（semantics 里没找到）" }

        // 成绩（第七条）：学号取自登录时写进 `AccountContext.activeAccountId` 的那个（屏上就是这么取的），
        // 所以这张图里是真能取到数据的；真取不到学号时屏会画「要一个学号」那张说明页（不是崩）。
        shot("scorereport.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.ScoreReport)) }

        // 评教（第八条）：假账号默认是本科生（`credentials.accountType` 的默认值）⇒ 走的是本科那一条
        // （与课表/成绩同一个教务站点、同一份会话）。图上应是**未评那三张卡片**（过程评教一行 + 期末评教
        // 两行，标签由 `PGLXDM` 换算），而不是错误页/转圈 —— 这是拿屏当证据时最容易糊过去的一格。
        shot("judge.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Judge)) }

        // 校园卡（第九条）：走**真路由**（外壳 → `AppRoute.CampusCard` → `DesktopSiteGate` →
        // `AppCampusCardSource`，缓存传 `null`）。图上应是夹具那张家底：余额/待入账/今日三餐 +
        // 七条流水的商户与金额，而不是错误页或转圈。
        shot("campuscard.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.CampusCard)) }

        shot("routes.png", frames = 4) { ToolboxDesktopApp(auth, DesktopTarget.Routes) }
    }

    exitProcess(0)
}

/** 假上游：`withFakeCampus` 期间有效（把端口灌给常驻 selector 的就是它）。 */
private lateinit var fake: FakeCampusProxy

/**
 * 把假的校园上游起起来（`:testkit` 的 [FakeCampusProxy]：一个本地 HTTP 代理，按 host 分派
 * 图书馆与校历），并在里面跑 [block]。
 *
 * 与 `:data:jvmTest` 的 `LibraryLoginSessionJvmTest.withFakeCas` 是同一套手法，
 * 差别有两处：那边每条测试自己清理落盘存储（这边整个进程只有一次，所以前置在 `main` 里）；
 * 这边的代理同时扮两个域名（校历那张图要一次跑通两条链路）。
 */
private fun withFakeCampus(block: (DesktopAuth) -> Unit) {
    fake = FakeCampusProxy(casEnabled = true)

    // 保险：临时目录里本来不该有东西，但这两行让「跑了两次」也不会互相污染。
    // ⚠️ 账号命名空间也要清（桌面登录会把 backends 换到 `cookies_normal_<学号>`）。
    val suffix = com.xjtu.toolbox.account.AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
    for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
        wipeSecureStore("${base}_default")
        wipeSecureStore("$base$suffix")
    }
    wipeSecureStore(JvmCredentialStore.FILE_NAME)
    for (name in listOf(
        "cookies_normal_default", "cookies_webvpn_default",
        "cookies_normal$suffix", "cookies_webvpn$suffix",
    )) {
        PersistentCookieJar(name).clear()
    }

    // 预置一份缓存的 RSA 公钥（照真机的路径：首次登录取回后存进凭据文件，之后复用）。
    // 没有它，`XJTULogin` 会去 `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取 —— https，
    // 本地假上游给不了（见文件头第 2 条坑）。
    val credentials = JvmCredentialStore()
    credentials.rsaPublicKey = TestRsaKey.publicKeyBase64

    fake.start()
    try {
        block(DesktopAuth(credentials))
    } finally {
        fake.close()
    }
}

/**
 * 在 AWT 事件队列上执行并等结果（**所有 Compose 调用都必须落在 EDT 上**）。
 *
 * 为什么：`:core` 的屏一律用 `viewModel { }` + `viewModelScope`，而 viewModelScope 跑在
 * `Dispatchers.Main.immediate` 上 —— 桌面端的 Main 就是 AWT 事件队列。真窗口里这没问题
 * （Compose Desktop 的渲染循环本身就在 EDT 上），但离屏场景是在**调用线程**上渲染的，
 * 不在 EDT 就会出现「VM 在 EDT 上更新状态、渲染线程同时在 measure/layout」⇒ Skia 直接抛
 * `performMeasureAndLayout called during measure layout`（实测踩到，且表现为「屏上永远没有数据」）。
 *
 * `SwingUtilities.invokeAndWait` 自己会把异常包进 `InvocationTargetException`，
 * 这里解开一层，好让失败的堆栈指到真正的出错位置。
 */
private fun <T> onEdt(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
