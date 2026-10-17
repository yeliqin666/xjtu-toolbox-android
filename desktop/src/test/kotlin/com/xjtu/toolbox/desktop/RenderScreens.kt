package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import androidx.compose.ui.unit.Density
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentPendingPrompt
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.coupon.CouponFakeUpstream
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackFakeUpstream
import com.xjtu.toolbox.feedback.FeedbackStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.TestRsaKey
import com.xjtu.toolbox.lms.LmsFakeUpstream
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.iclassface.IclassfaceFakeUpstream
import com.xjtu.toolbox.community.CommunityFakeUpstream
import com.xjtu.toolbox.community.GithubDeviceAccessToken
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.media.DownloadFakeUpstream
import com.xjtu.toolbox.media.DownloadItem
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.desktop.media.DesktopDownloads
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.social.MatchData
import com.xjtu.toolbox.social.MatchProfile
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.util.todayInSystemZone
import com.xjtu.toolbox.notification.NotificationFakeUpstream
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** `./gradlew :desktop:renderScreens` —— 把屏离屏渲染成 PNG，作为桌面端的可视化证据。 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    // 先清掉上一次的 PNG：Stage 0 那几张（`library-live.png` / `shell-live.png`）是 campus-api 脚手架
    // 时代的证据，已经不对应今天的实现 —— 留着只会被当成「现在的桌面端长这样」。
    outDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.forEach { it.delete() }

    // ── 两个进程级前置，顺序不能换 ──
    // ① 落盘存储指到临时目录（必须在碰 `secureKeyValueStore` / `PersistentCookieJar` 之前）。
    val shotsRoot = Files.createTempDirectory("xjtu-desktop-shots").toFile()
    dataRootOverride = shotsRoot
    // ② 常驻代理 selector + 假上游自签证书的信任库，必须在 `HttpClients.base` 初始化之前装。
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

    /** 在离屏场景里「点一下」写着 [label] 的那个控件。 */
    fun clickByLabel(scene: ImageComposeScene, label: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        val target = findNode(root) { node ->
            val click = node.config.getOrNull(SemanticsActions.OnClick)
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            click != null && texts.any { it.text == label }
        } ?: return false
        return target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
    }

    /** 往唯一那个带 `SetText` 能力的输入框里塞一段文本（靠 semantics，不靠坐标）。 */
    fun setTextOnFirstField(scene: ImageComposeScene, text: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        val target = findNode(root) { it.config.getOrNull(SemanticsActions.SetText) != null } ?: return false
        return target.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) ?: false
    }

    /** 语义树里有没有写着（含）[text] 的节点 —— 用作「这一枪真打到屏上了」的判据。 */
    fun hasText(scene: ImageComposeScene, text: String): Boolean {
        val root = scene.semanticsOwners.firstOrNull()?.rootSemanticsNode ?: return false
        return findNode(root) { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) }
        } != null
    }


    // ── ① 登录页（空输入框，不预填学号）──
    shot("login.png", frames = 4) { ToolboxDesktopApp(DesktopAuth()) }

    shot("library-demo.png", frames = 4) {
        LibraryScreen(
            source = DemoLibrarySource(),
            onBack = {},
            showFirstUseHint = false,
            onFirstUseHintRead = {},
        )
    }


    // ── ③ 黄页（免登录；MockEngine 喂假响应）──
    shot("yellowpage.png", frames = 8) {
        YellowPageScreen(
            api = YellowPageApi(mockYellowPageClient()),
            onBack = {},
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
    }

    // ── ④ 教师检索（免登录；拦截器喂假上游）──
    shot("faculty.png", frames = 12) {
        FacultyScreen(
            source = FacultyApiSource(FacultyApi(mockFacultyClient())),
            onBack = {},
            onOpenUrl = {},
        )
    }

    // ── ⑤⑥⑦⑧⑨ 真登录之后 ──
    withFakeCampus { auth ->
        // 假上游那组编出来的账号密码（不是任何人的学号）。
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
        // 外壳 + 图书馆屏：这一张要拍的是**外壳本身**（登录之后底栏与当前页正确切换），
        // 所以首屏显式给图书馆 —— 外壳的默认首屏现在是**首页**（`AppRoute.Main`，见 `home.png`）。
        shot("shell-after-login.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Library)) }

        // 体测：走外壳 + 真路由；站点是 https，假上游自带 CONNECT 前置与自签证书。
        shot("fitness.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Fitness)) }

        // 校历：免登录，基址指向假上游。
        shot("calendar.png", frames = 12) {
            SchoolCalendarScreen(
                source = SchoolCalendarApi(SchoolCalendarFakeUpstream.URL),
                onBack = {},
            )
        }
        // 全校课表：屏不会自己查询，要点「搜索」（等当前学期加载完才 enabled，所以多试几帧）。
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

        shot("judge.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Judge)) }

        shot("campuscard.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.CampusCard)) }

        var paymentShown = false
        shot(
            "payment_code.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!paymentShown && frame >= 4) paymentShown = hasText(scene, CampusCardFakeUpstream.BAR_CODE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.PaymentCode)) }
        check(paymentShown) {
            "payment_code.png：屏上没有夹具那串付款码（${CampusCardFakeUpstream.BAR_CODE}）—— 多半没取到数"
        }
        // 顺带确认夹具码真能编成模块阵列。
        val codeMatrix = requireNotNull(qrCodeModules(CampusCardFakeUpstream.BAR_CODE)) {
            "付款码在桌面这一端应当真编得出二维码"
        }
        println("付款码模块阵列：${codeMatrix.width}x${codeMatrix.height}（解回来见 DesktopAuthPaymentCodeJvmTest）")

        // 空闲教室：会话由源自己 ensure；默认只筛「空闲」，点「全部 N」把四间都摆出来。
        var pickedAll = false
        shot(
            "emptyroom.png",
            frames = 22,
            // 计数来自夹具那四间（`EmptyRoomFakeUpstream.liveJson`）；夹具改了这里就会响亮地失败
            onFrame = { frame, scene -> if (!pickedAll && frame >= 6) pickedAll = clickByLabel(scene, "全部 4") },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.EmptyRoom)) }
        check(pickedAll) { "emptyroom.png：没点到「全部 4」这个筛选（semantics 里没找到）" }
        shot("venue.png", frames = 20) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Venue)) }

        // 消息收纳：屏没有 VM，进屏那一发由外壳打；图上必须有夹具那条待办。
        var inboxShown = false
        shot(
            "inbox.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!inboxShown && frame >= 4) inboxShown = hasText(scene, YwtbFakeUpstream.TODO_TITLE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Inbox)) }
        check(inboxShown) { "inbox.png：屏上没有夹具那条待办（${YwtbFakeUpstream.TODO_TITLE}）—— 多半没拉到数据" }

        // 通知公告：免登录，默认来源是教务处；拿不到夹具那条标题就失败。
        var noticeShown = false
        shot(
            "notification.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!noticeShown && frame >= 4) noticeShown = hasText(scene, NotificationFakeUpstream.JWC_TITLE_1)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Notification)) }
        check(noticeShown) {
            "notification.png：屏上没有夹具那条通知（${NotificationFakeUpstream.JWC_TITLE_1}）—— 多半没取到数"
        }

        var couponShown = false
        shot(
            "coupon.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!couponShown && frame >= 4) couponShown = hasText(scene, CouponFakeUpstream.USABLE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Coupon)) }
        check(couponShown) {
            "coupon.png：屏上没有夹具那张券卡（${CouponFakeUpstream.USABLE_NAME}）—— 多半没取到数"
        }

        var transcriptFormShown = false
        shot(
            "transcript.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!transcriptFormShown && frame >= 4) transcriptFormShown = hasText(scene, DzpzFakeUpstream.TYPE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Transcript)) }
        check(transcriptFormShown) {
            "transcript.png：屏上没有夹具那个成绩单类型（${DzpzFakeUpstream.TYPE_NAME}）—— 多半没取到表单"
        }


        var payShown = false
        shot(
            "dormpower.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!payShown && frame >= 4) payShown = hasText(scene, "缴费页已交给系统浏览器打开")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.DormPower)) }
        check(payShown) {
            "dormpower.png：屏上没有「缴费页已交给系统浏览器打开」—— 多半没取到缴费页入口"
        }

        var attendanceShown = false
        shot(
            "attendance.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!attendanceShown && frame >= 5) attendanceShown = hasText(scene, AttendanceFakeUpstream.COURSE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Attendance)) }
        check(attendanceShown) {
            "attendance.png：屏上没有夹具那条课名（${AttendanceFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        // 思源学堂：⚠️ 先把功能说明置成已读，它的 WindowDialog 在离屏场景里建不出来。
        keyValueStore("feature_hints").putBoolean("lms_hint_shown", true)
        var lmsShown = false
        shot(
            "lms.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!lmsShown && frame >= 5) lmsShown = hasText(scene, LmsFakeUpstream.COURSE_NAME)
            },
        // ⚠️ 必须写 `AppRoute.Lms()`：只写 `AppRoute.Lms` 拿到的是 @Serializable 合成的 Companion。
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Lms())) }
        check(lmsShown) {
            "lms.png：屏上没有夹具那门课（${LmsFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        // 成绩：三条上游（jwapp 粗分、教务精确成绩、未评教名单 + 报表）都要真走一遍。
        var scoreShown = false
        shot(
            "jwapp.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (!scoreShown && frame >= 6) scoreShown = hasText(scene, JwappFakeUpstream.COURSE_NAME)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.JwappScore)) }
        check(scoreShown) {
            "jwapp.png：屏上没有夹具那门课（${JwappFakeUpstream.COURSE_NAME}）—— 多半没取到数"
        }

        var iclassfaceShown = false
        shot(
            "iclassface.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!iclassfaceShown && frame >= 5) {
                    iclassfaceShown = hasText(scene, IclassfaceFakeUpstream.RECORD_LOCATION)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Iclassface)) }
        check(iclassfaceShown) {
            "iclassface.png：屏上没有夹具那条记录（${IclassfaceFakeUpstream.RECORD_LOCATION}）—— 多半没取到数"
        }

        // 社区：先写一份夹具令牌进登录态，`signIn` 会用它向假 GitHub 查一次用户名。
        val communityLogin = runBlocking {
            SecureGithubSession.get().signIn(
                GithubDeviceAccessToken(
                    accessToken = CommunityFakeUpstream.ACCESS_TOKEN,
                    tokenType = "bearer",
                    scopes = emptySet(),
                )
            )
        }
        check(communityLogin.getOrNull() == CommunityFakeUpstream.VIEWER_LOGIN) {
            "社区那张图要先拿到一份夹具登录态：$communityLogin"
        }
        var communityShown = false
        shot(
            "community.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!communityShown && frame >= 5) {
                    communityShown = hasText(scene, CommunityFakeUpstream.DISCUSSION_TITLE)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Community)) }
        check(communityShown) {
            "community.png：屏上没有夹具那条帖子（${CommunityFakeUpstream.DISCUSSION_TITLE}）—— 多半没取到数"
        }

        // 反馈：注入指向夹具的凭据，并往本地账本补一张工单，图上才看得到开发者回复。
        FeedbackApi.config = FeedbackApi.Config(
            appId = FeedbackFakeUpstream.APP_ID,
            appSecret = FeedbackFakeUpstream.APP_SECRET,
            baseToken = FeedbackFakeUpstream.BASE_TOKEN,
            submitTable = FeedbackFakeUpstream.SUBMIT_TABLE,
            replyTable = FeedbackFakeUpstream.REPLY_TABLE,
            versionLabel = "${DesktopBuildConfig.VERSION_NAME} (desktop)",
            deviceLabel = "Linux amd64（证据运行）",
            osLabel = "Linux / Java（证据运行）",
        )
        FeedbackStore.addTicket(
            FeedbackStore.Ticket(
                id = FeedbackFakeUpstream.TICKET,
                category = "功能坏了",
                content = "示例反馈：桌面端的按钮点不动（证据运行自己写进账本的那一条）",
                createdAt = java.lang.System.currentTimeMillis(),
            ),
        )
        var feedbackShown = false
        shot(
            "feedback.png",
            // 比其余图高：要把工单下面的开发者回复框进来。
            height = 1400,
            frames = 20,
            onFrame = { frame, scene ->
                if (!feedbackShown && frame >= 5) {
                    feedbackShown = hasText(scene, FeedbackFakeUpstream.REPLY_TEXT)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Feedback)) }
        check(feedbackShown) {
            "feedback.png：屏上没有夹具那条回复（${FeedbackFakeUpstream.REPLY_TEXT}）—— 多半没取到数"
        }

        // ⚠️ 先把使用声明置成已读：miuix 的 `WindowDialog` 在离屏场景里建不出来。
        keyValueStore("feature_hints").putBoolean("jiaocai1_hint_shown", true)

        // 教材全文库：借教材中心的会话，`PortalRedirect` 跟那张跳转页；点两下分类树触发检索。
        var jiaocaiCategoryOpened = false
        var jiaocaiSearched = false
        var jiaocaiBookShown = false
        shot(
            "jiaocai1.png",
            frames = 30,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!jiaocaiCategoryOpened) {
                        jiaocaiCategoryOpened = clickByLabel(scene, Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME)
                    } else if (!jiaocaiSearched) {
                        jiaocaiSearched = clickByLabel(scene, Jiaocai1FakeUpstream.CHILD_CATEGORY_NAME)
                    } else if (!jiaocaiBookShown) {
                        jiaocaiBookShown = hasText(scene, Jiaocai1FakeUpstream.BOOK_TITLE)
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Jiaocai1)) }
        check(jiaocaiCategoryOpened) { "jiaocai1.png：没点到一级分类（${Jiaocai1FakeUpstream.ROOT_CATEGORY_NAME}）" }
        check(jiaocaiSearched) { "jiaocai1.png：没点到子类（${Jiaocai1FakeUpstream.CHILD_CATEGORY_NAME}）" }
        check(jiaocaiBookShown) {
            "jiaocai1.png：屏上没有夹具那本书（${Jiaocai1FakeUpstream.BOOK_TITLE}）—— 多半没取到数"
        }

        var readerShown = false
        shot(
            "jiaocai1-reader.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (!readerShown && frame >= 6) readerShown = hasText(scene, "1 / 34")
            },
        ) {
            ToolboxDesktopApp(
                auth,
                DesktopTarget.App(AppRoute.Jiaocai1Reader(Jiaocai1FakeUpstream.SSNO, Jiaocai1FakeUpstream.BOOK_TITLE)),
            )
        }
        check(readerShown) { "jiaocai1-reader.png：屏上没有「1 / 34」—— 多半没打开那本书" }
        // 教材查询：点一下搜索建议词触发检索；桌面上没有「本学期我的教材」那一段。
        var jiaocaiSuggestTapped = false
        var jiaocaiSearchBookShown = false
        shot(
            "jiaocai.png",
            frames = 24,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!jiaocaiSuggestTapped) {
                        jiaocaiSuggestTapped = clickByLabel(scene, JIAOCAI_SUGGESTION)
                    } else if (!jiaocaiSearchBookShown) {
                        jiaocaiSearchBookShown = hasText(scene, Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE)
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Jiaocai)) }
        check(jiaocaiSuggestTapped) { "jiaocai.png：没点到搜索建议（$JIAOCAI_SUGGESTION）" }
        check(jiaocaiSearchBookShown) {
            "jiaocai.png：屏上没有夹具那本教材（${Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE}）—— 多半没取到数"
        }
        // 下载管理（本批的 `download_manager` 路由）：走**真路由**（外壳 → `AppRoute.DownloadManager`，
        // 不要任何站点会话）；但这一张图不是「屏画得出来」的证明，而是**那条队列真下得动**的证明 ——
        // 先让证据运行自己排一条（夹具那条直链，走假上游），**等它真落到磁盘**，再截图。
        // 图上那行「示例活动（下载夹具）」与那个体积就是真下载的结果，不是插进去的假记录。
        // TODO（如实）：真机上这条队列目前还**没有入口** —— 思源学堂的落盘在桌面端是
        // `DesktopLmsSaver`，它的 `supportsVideoQueue = false`（批量对话框不露「课堂回放」那一档）。
        // 也就是：队列、状态机、执行器、落盘都是真的，但「谁来排一条」还差那一步。
        val downloads = DesktopDownloads.manager
        runBlocking {
            downloads.enqueueDownloads(
                courseName = DownloadFakeUpstream.COURSE_NAME,
                activityTitle = DownloadFakeUpstream.ACTIVITY_TITLE,
                activityId = 7,
                videos = listOf(
                    DownloadItem("instructor", DownloadFakeUpstream.INSTRUCTOR_URL),
                    DownloadItem("encoder", DownloadFakeUpstream.ENCODER_URL),
                ),
            )
        }
        val downloadDeadline = System.currentTimeMillis() + 20_000
        var finished = downloads.let { runBlocking { it.getAllTasks() } }
        while (System.currentTimeMillis() < downloadDeadline &&
            finished.any { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
        ) {
            Thread.sleep(50)
            finished = runBlocking { downloads.getAllTasks() }
        }
        check(finished.all { it.status == DownloadStatus.COMPLETED }) {
            "download_manager.png：夹具那条直链没下完（${finished.map { "${it.activityTitle}=${it.status}" }}）"
        }
        val downloadedFiles = finished.map { java.io.File(it.filePath) }
        check(downloadedFiles.all { it.isFile && it.length() == DownloadFakeUpstream.SIZE_BYTES.toLong() }) {
            "download_manager.png：文件没真落到磁盘（${downloadedFiles.map { it.absolutePath }}）"
        }
        var downloadShown = false
        shot(
            "download_manager.png",
            frames = 16,
            onFrame = { frame, scene ->
                if (!downloadShown && frame >= 4) downloadShown = hasText(scene, DownloadFakeUpstream.ACTIVITY_TITLE)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.DownloadManager)) }
        check(downloadShown) {
            "download_manager.png：屏上没有夹具那场活动（${DownloadFakeUpstream.ACTIVITY_TITLE}）—— 多半没取到队列"
        }
        val homeTerm = "2026-2027-1"
        val homeToday = todayInSystemZone()
        val homeCache = DesktopScheduleHost(auth).cache(AccountContext.activeAccountId)
        // 两节课分放「今天晚些」与「明天早八」：任何钟点跑都有「下一项安排」；用自建日程前缀，不受节假日影响。
        fun homeFixtureCourse(dayOfWeek: Int, startMinute: Int, endMinute: Int) = CourseItem(
            courseName = "示例课程（夹具）",
            teacher = "示例老师（夹具）",
            location = "主楼A-101",
            weekBits = "1".repeat(20),
            dayOfWeek = dayOfWeek,
            startSection = 1,
            endSection = 2,
            courseCode = "custom_fixture_home",
            startMinuteOfDay = startMinute,
            endMinuteOfDay = endMinute,
        )
        ScheduleCache.writeCurrentTerm(homeCache, homeTerm)
        // 开学日期 = 三周前 ⇒ 首页那一行固定是「第 4 周」（与 `DesktopHomeJvmTest` 同一个夹具）。
        // 日期加减走 `fromEpochDays`：与 `:core` 的 `SchoolCalendarModels.plusDays` 同一个写法。
        val homeStart = LocalDate.fromEpochDays(homeToday.toEpochDays() - 21)
        ScheduleCache.writeStartDate(homeCache, homeTerm, homeStart, weeks = 20)
        ScheduleCache.writeOptimizedCourses(
            homeCache,
            homeTerm,
            listOf(
                homeFixtureCourse(homeToday.dayOfWeek.ordinal + 1, 22 * 60, 23 * 60 + 30),
                homeFixtureCourse(
                    LocalDate.fromEpochDays(homeToday.toEpochDays() + 1).dayOfWeek.ordinal + 1,
                    8 * 60,
                    9 * 60 + 35,
                ),
            ),
        )
        ScheduleCache.writeExams(
            homeCache,
            homeTerm,
            listOf(
                ExamItem(
                    courseName = "示例考试（夹具）",
                    examDate = LocalDate.fromEpochDays(homeToday.toEpochDays() + 5).toString(),
                    examTime = "09:00-11:00",
                    location = "主楼B-201",
                ),
            ),
        )
        val homeBulletinTitle = "首页公告条（夹具）"
        val homeBulletinJson = """{"bulletins":[{"id":"fixture-home-bar","level":"info","title":"$homeBulletinTitle",""" +
            """"body":"值都是编的","url":"https://example.invalid/fixture"}]}"""
        val homeBulletinFetch: suspend (String) -> BulletinApi.FetchResult? = {
            BulletinApi.FetchResult(BulletinRules.parsePayload(homeBulletinJson), homeBulletinJson)
        }

        var homeCourseShown = false
        var homeWeekShown = false
        var homeExamShown = false
        var homeBulletinShown = false
        shot(
            "home.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!homeCourseShown) homeCourseShown = hasText(scene, "示例课程（夹具）")
                    if (!homeWeekShown) homeWeekShown = hasText(scene, "第4周")
                    if (!homeExamShown) homeExamShown = hasText(scene, "还有 5 天")
                    if (!homeBulletinShown) homeBulletinShown = hasText(scene, homeBulletinTitle)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch) }
        check(homeCourseShown) { "home.png：屏上没出现夹具那节课 —— 首页没读出课表缓存？" }
        check(homeWeekShown) { "home.png：屏上没有「第4周」—— 开学日期那一格没算出来？" }
        check(homeExamShown) { "home.png：屏上没有考试倒计时 —— 考试表那一格没读出来？" }
        check(homeBulletinShown) { "home.png：顶部没有夹具那条公告 —— 公告条没接上共享层？" }

        // 主壳：同一个路由、宽窗拍一张 —— 首页接进**现有导航壳**之后的形态（底栏六格 + 首页高亮）。
        var mainShellShown = false
        var mainHomeTabShown = false
        var mainBulletinShown = false
        shot(
            "main.png",
            width = 900,
            height = 900,
            frames = 8,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!mainShellShown) mainShellShown = hasText(scene, "示例课程（夹具）")
                    if (!mainHomeTabShown) mainHomeTabShown = hasText(scene, "首页")
                    if (!mainBulletinShown) mainBulletinShown = hasText(scene, homeBulletinTitle)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch) }
        check(mainShellShown) { "main.png：宽窗下的首页没画出夹具那节课" }
        check(mainHomeTabShown) { "main.png：底栏上没有「首页」那一格 —— 首页没接进现有壳？" }
        check(mainBulletinShown) { "main.png：顶部没有夹具那条公告" }

        var agentWelcomeShown = false
        shot(
            "agent_welcome.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!agentWelcomeShown) agentWelcomeShown = hasText(scene, "先接一个模型")
                }
            },
        ) {
            ToolboxDesktopApp(
                auth,
                DesktopTarget.App(AppRoute.Agent),
                agentHost = fixtureAgentHost(auth, FixtureAgentTransport()),
            )
        }
        check(agentWelcomeShown) { "agent_welcome.png：没落在「先接一个模型」那张欢迎页上 —— AppRoute.Agent 没接上宿主面？" }


        // 「全部页面」：这一页的 height 单独调大（不是其余图那个 900）—— 它是一张要读完的长表，
        // 而这一轮要证明的正是那个 0。把整页都框进来，免得拿半张图交差。
        shot("routes.png", height = 1900, frames = 4) { ToolboxDesktopApp(auth, DesktopTarget.Routes) }

        secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE).putString("dark_mode", SettingsStore.DARK_MODE_DARK)
        var settingsShown = false
        var settingsToggled = false
        var settingsDegraded = false
        shot(
            "settings.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!settingsShown) settingsShown = hasText(scene, "版本号")
                    if (!settingsToggled) settingsToggled = clickByLabel(scene, "跟随系统取色")
                    if (settingsShown && settingsToggled && !settingsDegraded) {
                        // 桌面没有的那三组 + 那几行：屏上**一个字都不该出现**
                        settingsDegraded = listOf("新通知提醒", "立即检查更新", "默认启动 Tab", "课表显示考勤")
                            .none { hasText(scene, it) }
                    }
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Settings)) }
        check(settingsShown) { "settings.png：屏上没有「版本号」那一行 —— 多半没渲染出设置页" }
        check(settingsToggled) { "settings.png：没点到「跟随系统取色」那个开关（semantics 里没找到）" }
        check(settingsDegraded) {
            "settings.png：屏上出现了桌面没有的那几项（通知提醒 / 检查更新 / 默认启动 Tab / 课表考勤）"
        }
        check(desktopSettings.dynamicColor) { "settings.png：点了开关，但偏好没落盘" }
        val settingsFile = File(shotsRoot, "${SettingsStore.APP_SETTINGS_FILE}.properties")
        check(settingsFile.exists()) { "settings.png：落盘文件不存在：${settingsFile.absolutePath}" }
        println("设置落盘：${settingsFile.absolutePath}")

        var vpnTyped = false
        var vpnConverted = false
        shot(
            "webvpn_converter.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!vpnTyped) vpnTyped = setTextOnFirstField(scene, "https://jwxt.xjtu.edu.cn/")
                    if (vpnTyped && !vpnConverted) vpnConverted = clickByLabel(scene, "转换")
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.WebVpnConverter)) }
        check(vpnTyped) { "webvpn_converter.png：没找到那个输入框（semantics 里没有 SetText）" }
        check(vpnConverted) { "webvpn_converter.png：没点到「转换」" }

        // ── 内置浏览器（桌面端 = 外链中转页）：只画不点（按钮会真开浏览器 / 碰剪贴板）──
        val browserUrl = "https://notice.example.invalid/2026/summer-camp"
        var browserShown = false
        var browserUrlShown = false
        var notPortedShown = false
        shot(
            "browser.png",
            frames = 6,
            onFrame = { frame, scene ->
                if (frame >= 3) {
                    if (!browserShown) browserShown = hasText(scene, "桌面端没有内嵌浏览器")
                    if (!browserUrlShown) browserUrlShown = hasText(scene, browserUrl)
                    if (!notPortedShown) notPortedShown = hasText(scene, "这一屏还没搬到桌面")
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Browser(browserUrl))) }
        check(browserShown) { "browser.png：屏上没有那句「桌面端没有内嵌浏览器」—— 多半没走到中转页" }
        check(browserUrlShown) { "browser.png：屏上没有路由带的那个网址（$browserUrl）—— 参数没传到屏上" }
        check(!notPortedShown) { "browser.png：落到了 `NotPortedScreen` 那张占位页 —— 这一条路由本该有真分支" }

        // ── 我的课表 ── ⚠️ 必须排在「账号管理」之前（那一块会真切账号）。
        var scheduleCourseShown = false
        var scheduleCellShown = false
        shot(
            "schedule.png",
            width = 900,
            height = 900,
            // 进屏那一枪要走：建教务会话 → 拉课表 + 考试 + 学期列表 → 落缓存 → 上屏。
            frames = 24,
            onFrame = { frame, scene ->
                if (frame >= 6 && !scheduleCourseShown) {
                    scheduleCourseShown = hasText(scene, JwxtFakeUpstream.SCHEDULE_1_COURSE)
                }
                if (scheduleCourseShown && !scheduleCellShown) {
                    scheduleCellShown = hasText(scene, JwxtFakeUpstream.SCHEDULE_1_ROOM)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Schedule)) }
        check(scheduleCourseShown) { "schedule.png：屏上没出现夹具的课名 —— 多半没取到课表（会话或取数断了）" }
        check(scheduleCellShown) { "schedule.png：课名在、教室不在 —— 课程格子没画出来？" }
        check(
            com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTerm(
                DesktopScheduleHost(auth).cache(AccountContext.activeAccountId),
            ) != null,
        ) { "schedule.png：拍完了但课表缓存是空的 —— 上屏的数据没落盘" }
        val agent = fixtureAgentHost(auth, FixtureAgentTransport())
        agent.saveConfig(AgentConfig(apiKey = "fixture-key", model = "fixture-model"))
        AgentPendingPrompt.set("帮我看一下桌面这一端接上了没有")
        var agentReplied = false
        shot(
            "agent.png",
            frames = 20,
            onFrame = { frame, scene ->
                if (!agentReplied && frame >= 4) agentReplied = hasText(scene, AGENT_FIXTURE_REPLY)
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Agent), agentHost = agent) }
        check(agentReplied) { "agent.png：屏上没有那条夹具回复 —— 待发消息没发出去，或流式回包没上屏" }
        // 配置与那一轮会话都落在本端数据根目录里（逐条钉落盘的是 `DesktopAgentJvmTest`）。
        val agentConfigFile = File(shotsRoot, "agent_config${AccountContext.safeSuffix()}.properties")
        check(agentConfigFile.isFile) { "agent.png：配置没落盘：${agentConfigFile.absolutePath}" }
        check(
            shotsRoot.walkTopDown().any {
                it.isFile && it.name.startsWith("convo_") && it.readText().contains(AGENT_FIXTURE_REPLY)
            },
        ) { "agent.png：拍完了但那轮会话没落盘" }
        println("屁岱落盘：${agentConfigFile.absolutePath}")


        val secondAccountId = "2021000002"
        AccountStore().upsert(
            Account(
                accountId = secondAccountId,
                password = "fixture-password-2",
                accountType = com.xjtu.toolbox.auth.AccountType.POSTGRADUATE,
                lastUsedAt = System.currentTimeMillis(),
            ),
        )
        runBlocking { DesktopAccounts(auth).switchTo(secondAccountId) }
        check(AccountContext.activeAccountId == secondAccountId) {
            "accounts.png：切账号没真的换绑命名空间（当前账号还是 ${AccountContext.activeAccountId}）"
        }
        var accountsShown = false
        shot(
            "accounts.png",
            frames = 12,
            onFrame = { frame, scene ->
                if (!accountsShown && frame >= 4) accountsShown = hasText(scene, "已保存的账号（2）")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Accounts)) }
        check(accountsShown) { "accounts.png：屏上没有「已保存的账号（2）」—— 多半没读到账号表" }
        check(AccountStore().list().map { it.accountId }.contains(LibraryFakeUpstream.USERNAME)) {
            "accounts.png：夹具账号不在账号表里（真登录那一步应当把它写进去）"
        }

        var mergeShown = false
        shot(
            "game_merge.png",
            frames = 4,
            onFrame = { _, scene ->
                if (!mergeShown) mergeShown = hasText(scene, "合成西交大在桌面上跑不了")
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.GameMerge)) }
        check(mergeShown) { "game_merge.png：没画到那张说明页" }

        val matchTerm = "2026-2027-1"
        val matchCourseName = "大学物理（夹具）"
        val matchPeerName = "小张（夹具）"
        val matchCache = DesktopScheduleHost(auth).cache(AccountContext.activeAccountId)
        fun matchCourse(day: Int, from: Int, to: Int, code: String, teacher: String, room: String) = CourseItem(
            courseName = matchCourseName,
            teacher = teacher,
            location = room,
            weekBits = "1".repeat(20),
            dayOfWeek = day,
            startSection = from,
            endSection = to,
            courseCode = code,
            courseType = "",
        )
        fun matchExam(date: String) = ExamItem(
            courseName = matchCourseName,
            courseCode = "FIX-PHY",
            examDate = date,
            examTime = "09:00-11:00",
            location = "主楼A-101",
            seatNumber = "12",
        )
        ScheduleCache.writeCurrentTerm(matchCache, matchTerm)
        ScheduleCache.writeOptimizedCourses(
            matchCache,
            matchTerm,
            listOf(
                matchCourse(1, 1, 2, "FIX-MATH", "张老师（夹具）", "主楼A-101"),
                matchCourse(3, 5, 6, "FIX-PHY", "李老师（夹具）", "中2-2101"),
            ),
        )
        ScheduleCache.writeExams(matchCache, matchTerm, listOf(matchExam("2027-01-08")))
        val theirMatchCode = MatchProfile.encode(
            MatchProfile.build(
                local = MatchData.Local(
                    courses = listOf(matchCourse(3, 5, 6, "FIX-PHY", "李老师（夹具）", "中2-3305")),
                    exams = listOf(matchExam("2027-01-08")),
                ),
                nickname = matchPeerName,
            ),
        )
        check(theirMatchCode.isNotEmpty()) {
            "schedule_match.png：夹具那段对方码编不出来（jvm 上 deflate/Base64 两条缝都应当有）"
        }
        var matchTyped = false
        var matchComputed = false
        var matchIntersectionShown = false
        shot(
            "schedule_match.png",
            width = 560,
            height = 1900,
            frames = 16,
            onFrame = { frame, scene ->
                if (frame >= 4) {
                    if (!matchTyped) matchTyped = setTextOnFirstField(scene, theirMatchCode)
                    if (matchTyped && !matchComputed) matchComputed = clickByLabel(scene, "算一算")
                }
                if (matchComputed && !matchIntersectionShown) {
                    matchIntersectionShown =
                        hasText(scene, "一起上 1 门课") && hasText(scene, matchCourseName)
                }
            },
        ) { ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Match)) }
        check(matchTyped) { "schedule_match.png：没找到那个输入框（semantics 里没有 SetText）" }
        check(matchComputed) { "schedule_match.png：没点到「算一算」" }
        check(matchIntersectionShown) {
            "schedule_match.png：屏上没有夹具算出来的交集 —— 解码或逐维比较断了"
        }
        // ── 深色外壳：`home-dark.png` / `main-dark.png` ──
        // ⚠️ 必须在所有其它图之后：`dark_mode` 写进进程级 store，会把后面的图带成深色；先切回本账号。
        runBlocking { DesktopAccounts(auth).switchTo(LibraryFakeUpstream.USERNAME) }
        secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE)
            .putString("dark_mode", SettingsStore.DARK_MODE_DARK)
        shot("home-dark.png", frames = 12) {
            ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch)
        }
        shot("main-dark.png", width = 900, height = 900, frames = 8) {
            ToolboxDesktopApp(auth, DesktopTarget.App(AppRoute.Main), homeBulletinFetch)
        }

        // 三条判据都读落盘的 PNG：底栏、侧栏、页面亮像素占比都要跟着主题走。
        val lightBar = bandOf(outDir, "home.png", 60, 888, 460, 898)
        val darkBar = bandOf(outDir, "home-dark.png", 60, 888, 460, 898)
        check(lightBar.maxLuma >= 200) { "home.png：底栏那一条不是浅色（$lightBar）—— 底栏没按主题铺色？" }
        check(darkBar.maxLuma <= 80) {
            "home-dark.png：底栏那一条在深色下还是亮的（$darkBar）—— 外壳铺的那层没跟着深色走？"
        }
        val lightRail = bandOf(outDir, "main.png", 2, 100, 6, 800)
        val darkRail = bandOf(outDir, "main-dark.png", 2, 100, 6, 800)
        check(lightRail.maxLuma >= 200) { "main.png：宽窗下侧栏那一条不是浅色（$lightRail）" }
        check(darkRail.maxLuma <= 80) { "main-dark.png：宽窗下侧栏那一条在深色下还是亮的（$darkRail）" }
        val lightPage = bandOf(outDir, "home.png", 20, 60, 500, 830)
        val darkPage = bandOf(outDir, "home-dark.png", 20, 60, 500, 830)
        check(lightPage.brightRatio > 0.5f) { "home.png：页面那一层不是浅色（$lightPage）" }
        check(darkPage.brightRatio < 0.2f) {
            "home-dark.png：深色下页面那一层还有一大片亮像素（$darkPage）—— 三层里有一层没换底？"
        }
        println("深浅对照：底栏 $lightBar → $darkBar；侧栏 $lightRail → $darkRail；页面 $lightPage → $darkPage")

    }
    exitProcess(0)
}

/** 「查教材」栅那行搜索建议里的一个词（与 `JiaocaiSearchContent` 里的字面量一致）。 */
private const val JIAOCAI_SUGGESTION = "高等数学"

/** 假上游：`withFakeCampus` 期间有效（把端口灌给常驻 selector 的就是它）。 */
private lateinit var fake: FakeCampusProxy

/** 把假的校园上游起起来（`:testkit` 的 [FakeCampusProxy]）。 */
private fun withFakeCampus(block: (DesktopAuth) -> Unit) {
    wipeSecureStore(accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, null))
    wipeSecureStore(accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, LibraryFakeUpstream.USERNAME))
    // 账号表（`AccountStore`，存储名 `xjtu_accounts`）同样清一下：多账号之后它会在「真登录」那一步
    // 长出一条夹具账号，而 `restore()` 会直接拿它恢复成已登录 —— 跑两次就该是同一种起点。
    wipeSecureStore(AccountStore.FILE_NAME)
    AccountContext.activeAccountId = null
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

    // 预置 RSA 公钥：否则 `XJTULogin` 会去 https 取，假上游给不了。
    val credentials = JvmCredentialStore()
    credentials.rsaPublicKey = TestRsaKey.publicKeyBase64

    fake.start()
    try {
        block(DesktopAuth(credentials))
    } finally {
        fake.close()
    }
}

/** 在 AWT 事件队列上执行并等结果（所有 Compose 调用都必须落在 EDT 上）。 */
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

/** 从落盘的那张证据图上量一块矩形区域：最亮值（0..255）与「亮像素」（任一通道 > 200）的占比。 */
private class BandStats(val maxLuma: Int, val brightRatio: Float) {
    override fun toString(): String = "最亮=$maxLuma 亮占比=${(brightRatio * 100).toInt()}%"
}

private fun bandOf(shotsDir: File, name: String, x0: Int, y0: Int, x1: Int, y1: Int): BandStats {
    val image = requireNotNull(ImageIO.read(File(shotsDir, name))) { "读不到证据图：$name" }
    var maxLuma = 0
    var bright = 0
    var total = 0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val argb = image.getRGB(x, y)
            val luma = maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
            if (luma > maxLuma) maxLuma = luma
            if (luma > 200) bright++
            total++
        }
    }
    return BandStats(maxLuma, bright.toFloat() / total)
}
