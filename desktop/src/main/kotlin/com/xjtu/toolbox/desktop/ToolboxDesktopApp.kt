package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentScreen
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginScreen
import com.xjtu.toolbox.auth.MfaCodeDialog
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinLaunchDialog
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.feedback.AppFeedbackSource
import com.xjtu.toolbox.feedback.FeedbackScreen
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.judge.GraduateJudgeSource
import com.xjtu.toolbox.judge.GraduateQuestionnaire
import com.xjtu.toolbox.judge.JudgeListScreen
import com.xjtu.toolbox.judge.JudgeViewModel
import com.xjtu.toolbox.judge.Questionnaire
import com.xjtu.toolbox.judge.UndergraduateJudgeSource
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.hop.HopScreen
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.score.scoreReportSource
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.inbox.AppInboxSource
import com.xjtu.toolbox.inbox.InboxScreen
import com.xjtu.toolbox.jiaocai.AppJiaocaiSource
import com.xjtu.toolbox.jiaocai.TextbookScreen
import com.xjtu.toolbox.desktop.jiaocai.DesktopJiaocaiTermTextbooks
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Source
import com.xjtu.toolbox.jiaocai1.Jiaocai1LibraryScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1PageImages
import com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1ViewModel
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.jiaocai1.jiaocai1CacheRoot
import com.xjtu.toolbox.social.MatchScreen
import com.xjtu.toolbox.coupon.AppCouponSource
import com.xjtu.toolbox.coupon.CouponScreen
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
import com.xjtu.toolbox.desktop.media.DesktopDownloadedFilesHost
import com.xjtu.toolbox.desktop.media.DesktopDownloads
import com.xjtu.toolbox.desktop.media.DesktopVideoPlayerHost
import com.xjtu.toolbox.media.DownloadManagerScreen
import com.xjtu.toolbox.jwapp.AppJwappScoreSource
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.iclassface.AppIclassfaceSource
import com.xjtu.toolbox.iclassface.IclassfaceScreen
import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.community.GithubNetwork
import com.xjtu.toolbox.community.GithubOAuthDeviceAuthRepository
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.attendance.AppAttendanceSource
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.dzpz.AppTranscriptSource
import com.xjtu.toolbox.dzpz.TranscriptScreen
import com.xjtu.toolbox.desktop.dzpz.DesktopTranscriptSaver
import com.xjtu.toolbox.pay.PaymentCodeApi
import com.xjtu.toolbox.pay.PaymentCodeScreen
import com.xjtu.toolbox.pay.PaymentSelectionStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.shell.AppShell
import com.xjtu.toolbox.shell.ShellNavState
import com.xjtu.toolbox.shell.ShellPageHost
import com.xjtu.toolbox.shell.ShellTarget
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.calculateIsWideLayout
import com.xjtu.toolbox.ui.glass.glassNavStyle
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.desktop.venue.DesktopSlideCaptchaHost
import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 桌面外壳（窗口模式）。 */
@Composable
internal fun ToolboxDesktopApp(
    auth: DesktopAuth,
    // 开场那一页 = 设置里那格「默认启动 Tab」（规范化值 → 本端页面的映射表在 [desktopStartTarget]）：
    // 以前这里是写死的 `AppRoute.Main`，设置页里那一行成了一个真能点、但没人读的假开关。
    initialTarget: DesktopTarget = desktopStartTarget(desktopSettings.defaultTab),
    /** 公告取数那一枪（[DesktopBulletins.fetch]）。 */
    bulletinFetch: suspend (String) -> BulletinApi.FetchResult? = { BulletinApi.fetch(it) },
    /** 屁岱的宿主面（`:core` 的 [AgentHost]）。 */
    agentHost: AgentHost? = null,
    /**
     * 单实例锁没抢到时的原因（见 `:shared:model` 的 `InstanceLock`）：非 `null` 就在顶上映一条横幅，
     * 如实说明这一端只读。`null` = 本进程就是写者（正常那一档）。
     */
    readOnlyReason: String? = null,
) {
    var target by remember { mutableStateOf(initialTarget) }
    val scope = rememberCoroutineScope()

    // 屁岱的宿主面：进程内一份（与 `:app` 把屁岱挂在导航项上同位 —— 切页不丢会话）。
    val agent = agentHost ?: remember(auth) { DesktopAgentHost(auth) }


    val bulletins = remember { DesktopBulletins(scope, fetch = bulletinFetch) }
    LaunchedEffect(bulletins) { bulletins.check() }

    var appearanceTick by remember { mutableIntStateOf(0) }
    val darkModeOverride = remember(appearanceTick) { desktopSettings.darkMode }
    val dynamicColor = remember(appearanceTick) { desktopSettings.dynamicColor }
    // 界面风格（`nav_bar_style`）：与深色模式同一条路 —— 设置页改完走 `onAppearanceChanged`（appearanceTick++）重读
    val navBarStyle = remember(appearanceTick) { desktopSettings.navBarStyle }

    val customColorHex = remember(appearanceTick) { desktopSettings.themeSeedHex }
    XJTUToolBoxTheme(darkModeOverride = darkModeOverride, dynamicColor = dynamicColor, customColorHex = customColorHex) {

    Column(Modifier.fillMaxSize()) {
        // 只读横幅：锁被别人拿走了就在最上面说一句（不遮不盖，也不假装写得了）
        readOnlyReason?.let { DesktopReadOnlyBar(it) }

    // 冷启动：有落盘凭据就直接进主界面（幂等，重复组合无害）
    LaunchedEffect(auth) { auth.restore() }

    if (!auth.loggedIn) {
        LoginScreen(
            title = "西交工具箱",
            subtitle = "用你的校园账号登录。凭据只留在本机，取数直接从学校站点走。",
            username = auth.username,
            onUsernameChange = { auth.username = it; auth.onLoginInputChanged() },
            password = auth.password,
            onPasswordChange = { auth.password = it; auth.onLoginInputChanged() },
            state = auth.loginState,
            onSubmit = { scope.launch { auth.login() } },
            footer = "凭据存成权限 0600 的文件（只有你这台机器的这个用户可以读）。" +
                "登录需要两步验证时会在窗口里弹出来。",
        )
    } else {
        // 共享壳（`:core` 的 AppShell）：底栏 / 侧栏 / 当前页那一层 / 形态学全在它里面，
        // 与 `:app` 是同一份代码。这一端只交三样：六格、当前页、以及「留下谁」那一档。
        val navState = ShellNavState(
            tabs = desktopTabs(),
            target = target.toShell(),
            onSelect = { tab ->
                // 六格里只有「某一屏」与「全部」两种目标（见 desktopTabs）；Custom（端自己的页）
                // 这一端还没有 ⇒ 映射不出来就不动（不假装能去）。
                tab.target.toDesktop()?.let { target = it }
            },
        )
        // 窗口形状喂给共享壳（`:app` 那边由 `AppNavigation` 算好往下提供）：窗口够宽时底栏换侧栏，
        // 判据就是 `:core` 那条 `calculateIsWideLayout` —— 与 Android 在宽屏 / 折叠屏上同一套。
            val wideLayout = calculateIsWideLayout()
            CompositionLocalProvider(LocalIsWideLayout provides wideLayout) {
            AppShell(
                nav = navState,
                // 这一端的导航模型是**路由表**（43 条里 37 条不属于任何一格）⇒ 只画当前这一页，
                // 不做「进过的 tab 常驻」（那是 `:app` 五格的形态，见 ShellPageHost）。
                pageHost = ShellPageHost.CURRENT_ONLY,
                    // 真读设置里的界面风格（与 `:app` 同一个键）：不宽屏时是玻璃胶囊底栏，选「经典」就是今天这一套
                    glassStyle = glassNavStyle(navBarStyle, wideLayout),
                // 没有系统栏、没有刘海：窗口 chrome 全是 0（壳那边于是只剩底栏本体）
                bottomInset = 0.dp,
                contentWindowInsets = WindowInsets(0),
                page = { opened, _ ->
                    // 43 条顶层分支都在 DesktopPage 里（`when (route)`）——这一段只分派「哪一种页」。
                    when (val openedTarget = opened.target) {
                        is ShellTarget.App -> DesktopPage(
                            auth = auth,
                            route = openedTarget.route,
                            bulletins = bulletins,
                            agentHost = agent,
                            onAppearanceChanged = { appearanceTick++ },
                            onNavigate = { target = it },
                        )

                        // 「全部」那一页 = 索引页。Custom（端自己的页）这一端还没有 ⇒ 落到同一页，不白屏。
                        ShellTarget.AllPages, is ShellTarget.Custom -> RoutesPage(auth) { target = it }
                    }
                },
            )
        }
    }

    // 登录页与主界面都要挂：登录本身就可能要短信二验
    DesktopMfaDialog(auth)

    // 小游戏排行榜的弹窗（榜单、改名），同 MFA 一样挂在壳上；上榜询问由各游戏页自己挂
    com.xjtu.toolbox.game.board.LeaderboardHost()

    // 冷启动公告挂在壳上（登录页与主界面都要能弹）；⚠️ 它是独立 AWT 窗口，离屏证据图里画不到。
    bulletins.launchBulletin?.let { bulletin ->
        BulletinLaunchDialog(
            bulletin = bulletin,
            show = bulletins.showLaunchDialog,
            onDismiss = { bulletins.dismissDialog(bulletin) },
            onPrimary = { bulletins.confirmDialog(bulletin) },
            onSubmitPoll = { bulletins.submitPoll(bulletin, it) },
        )
    }
    }
}
}


/** 这一端真能画的路由（`:core` 里有屏 + 取数在 `:data`，两者缺一不可）。 */
internal val DESKTOP_SUPPORTED_ROUTES = listOf(
    AppRoute.Main to "首页",
    AppRoute.SchoolCalendar to "校历",
    AppRoute.Library to "图书馆座位",
    AppRoute.Fitness to "体测",
    AppRoute.SchoolCourse to "全校课表",

    AppRoute.Schedule to "我的课表",
    AppRoute.ScoreReport to "成绩",
    AppRoute.Judge to "学生评教",
    AppRoute.CampusCard to "校园卡",
    AppRoute.EmptyRoom to "空闲教室",
    AppRoute.Venue to "体育场馆",
    AppRoute.Inbox to "消息收纳",
    AppRoute.Notification to "通知公告",
    AppRoute.YellowPage to "黄页",
    AppRoute.Faculty to "教师检索",
    AppRoute.Transcript to "电子成绩单",
    AppRoute.Coupon to "加餐券",

    AppRoute.DownloadManager to "下载管理",

    AppRoute.Attendance to "考勤",
    // `Lms` 是带 `courseId` 的 data class（其余多数是 data object）⇒ 这一栏要用**默认实例**
    // `AppRoute.Lms()`（`id` = "lms"，与 :app 服务表里那一条同一个 id）。
    AppRoute.Lms() to "思源学堂",

    // 三域的名字照 `:app` 服务表里的叫法（`AppServices.kt`）：「成绩」（jwapp）/
    // 「快速考勤流水」（iclassface） / 「社区」。桌面索引页上这一栏就是同名同 id。
    AppRoute.JwappScore to "成绩",
    AppRoute.Iclassface to "快速考勤流水",
    AppRoute.Community to "社区",

    AppRoute.Feedback to "反馈与建议",

    AppRoute.Jiaocai to "教材查询",

    AppRoute.Jiaocai1 to "教材全文",
    AppRoute.Games to "游戏合集",
    AppRoute.Game2048 to "GPA 2048",
    AppRoute.GameBlocks to "方块",
    AppRoute.GameGomoku to "五子棋",
    AppRoute.GameHop to "跳一跳",
    AppRoute.GameGo to "围棋",
    AppRoute.GameXiangqi to "象棋",

    AppRoute.PaymentCode to "付款码",

    AppRoute.WebVpnConverter to "WebVPN 转换器",

    // 内置浏览器：桌面是外链中转页。`Browser` 是 data class ⇒ 必须写 `()`。
    AppRoute.Browser() to "浏览器",

    AppRoute.Accounts to "账号管理",

    AppRoute.Agent to "屁岱",

    AppRoute.Match to "匹配交友",
)

/** 屏已在 `:core`、但这一端还没有数据源的路由。 */
internal val DESKTOP_PENDING_ROUTES = emptyList<Pair<AppRoute, String>>()


/** 一屏共享页。 */

/** 「进这一屏之前先把它的站点会话建起来」。 */
@Composable
private fun DesktopSiteGate(
    auth: DesktopAuth,
    siteKey: String,
    siteName: String,
    content: @Composable (SiteSession) -> Unit,
) {
    val site = auth.sessionManager.getSiteOrNull(siteKey)
    var attempt by remember(siteKey) { mutableIntStateOf(0) }
    var failure by remember(siteKey) { mutableStateOf<String?>(null) }
    var ready by remember(siteKey) { mutableStateOf(site?.hasLogin == true) }

    LaunchedEffect(siteKey, attempt) {
        if (ready) return@LaunchedEffect
        failure = null
        ready = runCatching { auth.ensureSession(siteKey) }.fold(
            onSuccess = { true },
            onFailure = { failure = FriendlyError.of(it, "建立$siteName 会话"); false },
        )
    }

    when {
        ready && site != null -> content(site)
        failure != null -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "$siteName 会话没建起来",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(failure.orEmpty(), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
            Text(
                "这一屏需要先登录「$siteName」。它与图书馆是同一套凭据；这里失败通常是那个子系统自己出问题" +
                    "（体测服务历史上返回过 502），不是账号问题。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 12.sp,
            )
            TextButton(text = "重试", onClick = { attempt++ }, minWidth = 120.dp)
        }
        else -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                "正在准备$siteName 会话…",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
            )
        }
    }
}

/** 一屏共享页 —— 见文件上方那段 KDoc（「每个屏只注入这一端能提供的东西」）。 */
@Composable
private fun DesktopPage(
    auth: DesktopAuth,
    route: AppRoute,
    /** 公告宿主面（只有首页用得上：顶部那条堆叠条）。 */
    bulletins: DesktopBulletins,
    /** 屁岱的宿主面（只有 `AppRoute.Agent` 用得上；外壳持有，切页不丢会话）。 */
    agentHost: AgentHost,
    /** 外观设置改完之后戳一下外壳（设置屏通过 `DesktopSettingsHost` 回调过来，见 `ToolboxDesktopApp`）。 */
    onAppearanceChanged: () -> Unit,
    onNavigate: (DesktopTarget) -> Unit,
) {
    val back = { onNavigate(DesktopTarget.App(AppRoute.Library)) }
    when (route) {
        AppRoute.Main -> DesktopHomeScreen(
            auth = auth,
            bulletins = bulletins,
            onNavigate = onNavigate,
        )

        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember { SchoolCalendarApi() },
            onBack = back,
        )
        AppRoute.Faculty -> FacultyScreen(
            source = remember { FacultyApiSource() },
            onBack = back,
            onOpenUrl = { openInBrowser(it) },
        )
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember { YellowPageApi(yellowPageClient) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        AppRoute.Library -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LibraryScreen(
                source = auth.librarySource,
                onBack = back,
                reAuthenticate = {
                    // 「重新认证」：凭据在会话管家里，直接 `ensureSite(userInitiated = true)`。
                    auth.sessionManager.ensureSite(DesktopAuth.LIBRARY_SITE_KEY, userInitiated = true)
                },
                showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("library_hint_shown", true) },
            )
        }
        // 体测：v3 加密协议优先、失败退回 legacy PHP；会话进门时才建（体测会 502）。
        AppRoute.Fitness -> DesktopSiteGate(auth, DesktopAuth.FITNESS_SITE_KEY, "体测") { site ->
            FitnessScreen(
                source = remember(site) { FitnessApi(site) },
                onBack = back,
            )
        }
        AppRoute.SchoolCourse -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
            SchoolCourseScreen(
                source = remember(site) { AppSchoolCourseSource(site) },
                onBack = back,
            )
        }
        AppRoute.Schedule -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { _ ->
            ScheduleScreen(
                host = remember { DesktopScheduleHost(auth) },
                onBack = back,
                onNavigate = { r ->
                    when (r) {
                            is AppRoute.Browser -> desktopBrowserTarget(r.url)?.let { openInBrowser(it) }
                        else -> onNavigate(DesktopTarget.App(r))
                    }
                },
            )
        }
        AppRoute.ScoreReport -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
            val studentId = AccountContext.activeAccountId?.takeIf { it.isNotBlank() }
            if (studentId == null) {
                MissingIdentityPage(
                    onNavigate = onNavigate,
                    title = "成绩报表要一个学号，现在取不到",
                    lines = listOf(
                        "成绩报表按学号取数，学号来自这次登录。取不到通常是登录还没走完，或者这一份装配是" +
                            "从落盘凭据恢复的 —— 重新登录一次即可。",
                        "教务那边的会话本身是好的（能进这一屏就说明它建起来了）。",
                    ),
                )
            } else {
                ScoreReportScreen(
                    source = remember(site, studentId) { scoreReportSource(site, studentId) },
                    cache = remember(studentId) { DesktopScoreReportCache(studentId) },
                    onBack = back,
                )
            }
        }

        AppRoute.Judge -> if (AccountContext.activeAccountType == AccountType.POSTGRADUATE) {
            val source = remember { GraduateJudgeSource(auth.sessionManager) }
            val vm: JudgeViewModel<GraduateQuestionnaire> =
                viewModel(key = "judge-postgraduate") { JudgeViewModel(source) }
            JudgeListScreen("学生评教", vm, back)
        } else {
            DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
                val username = AccountContext.activeAccountId?.takeIf { it.isNotBlank() }
                if (username == null) {
                    MissingIdentityPage(
                        onNavigate = onNavigate,
                        title = "评教要一个学号，现在取不到",
                        lines = listOf(
                            "评教是按学号取「我的问卷」的，学号来自这次登录。取不到通常是登录还没走完，或者" +
                                "这一份装配是从落盘凭据恢复的 —— 重新登录一次即可。",
                            "教务那边的会话本身是好的（能进这一屏就说明它建起来了）。",
                        ),
                    )
                } else {
                    val source = remember(site, username) { UndergraduateJudgeSource(site, username) }
                    val vm: JudgeViewModel<Questionnaire> =
                        viewModel(key = "judge-undergraduate") { JudgeViewModel(source) }
                    JudgeListScreen("学生评教", vm, back)
                }
            }
        }

        AppRoute.CampusCard -> DesktopSiteGate(auth, DesktopAuth.CAMPUS_CARD_SITE_KEY, "校园卡") { site ->
            CampusCardScreen(
                source = remember(site) { AppCampusCardSource(site) },
                onBack = back,
                mealReportExport = DesktopMealReportExport,
            )
        }

        AppRoute.PaymentCode -> DesktopSiteGate(auth, DesktopAuth.CAMPUS_CARD_SITE_KEY, "校园卡") { site ->
            PaymentCodeScreen(
                source = remember(site) { PaymentCodeApi(site) },
                selections = remember { PaymentSelectionStore(secureKeyValueStore("payment_code")) },
                onDismiss = back,
            )
        }

        AppRoute.EmptyRoom -> {
            val prefs = remember { keyValueStore("empty_room") }
            EmptyRoomScreen(
                source = remember { AppEmptyRoomSource(auth.sessionManager) },
                // 身份证：与其余端一样传当前账号类型（研究生在屏上不提供直查教务那一档）
                accountType = AccountContext.activeAccountType,
                onBack = back,
                showCdnTip = !prefs.getBoolean("empty_room_cdn_tip", false),
                onCdnTipRead = { prefs.putBoolean("empty_room_cdn_tip", true) },
            )
        }

        AppRoute.Venue -> DesktopSiteGate(auth, DesktopAuth.VENUE_SITE_KEY, "体育场馆") { site ->
            val hintPrefs = remember { keyValueStore("feature_hints") }
            VenueScreen(
                source = remember(site) { AppVenueSource(site, canBook = true) },
                onBack = back,
                onOpenBrowser = { url, then ->
                        // 见 `DesktopBrowser.kt` 的 `desktopBrowserTarget`：桌面收不到回调 ⇒ **只交第一跳**
                        //（url 是 OAuth 入口，then 是登录后那一站；直开 then 等于跳过登录）。两个都空时不替它编一个地址。
                        // `then` 只用来在屏上如实说明「跳不过去」（`DesktopBrowserScreen`）。
                        desktopBrowserTarget(url)?.let { openInBrowser(it) }
                },
                    // 「自动识别验证码」那个设置项：以前这个参数没人传 ⇒ 屏内恒走默认 `{ false }`，
                    // 设置页里的开关点下去什么也不会发生。每次预订现读（VM 侧的约定），与 `:app` 同一个键。
                    autoSolveCaptcha = { desktopSettings.venueAutoSolveCaptchaEnabled },
                captchaHost = remember { DesktopSlideCaptchaHost },
                    // 「功能说明」里那两句由宿传（默认那两句是 `:app` 的 WebView 写法，桌面两句都不成立）：
                    // 验证码：桌面有解算器也能被设置关掉；支付：交的是系统浏览器，收不到回调。
                    captchaHint = "验证码默认自动识别（设置里可关）；没识别过就手动滑",
                    payHint = "支付用系统浏览器打开：这里没有内置浏览器，登录后那一跳要自己再走一次",
                showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("venue_hint_shown", true) },
            )
        }

        // 消息收纳：源自己 ensure 会话，不套 `DesktopSiteGate`；屏没有 VM，进门由外壳拉一轮（`isDue` 判 TTL）。
        AppRoute.Inbox -> {
            val source = remember { AppInboxSource(auth.sessionManager) }
            // 收纳按账号分命名空间（`:app` 传的是登录账号）：桌面端就是登录时写进
            // `AccountContext.activeAccountId` 的那个，与 `InboxStore` 的默认读法是同一个。
            val account = AccountContext.activeAccountId?.takeIf { it.isNotBlank() }
            var failure by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(source) {
                failure = runCatching {
                    if (source.isDue(account, System.currentTimeMillis())) source.refresh(account)
                }.exceptionOrNull()?.let { FriendlyError.of(it, "刷新消息收纳") }
            }
            Column(Modifier.fillMaxSize()) {
                failure?.let { msg ->
                    Text(
                        msg,
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    InboxScreen(
                        source = source,
                        onBack = back,
                        onOpen = { id ->
                            when (val r = appRouteOf(id)) {
                                    is AppRoute.Browser -> desktopBrowserTarget(r.url)?.let { openInBrowser(it) }
                                null -> Unit
                                else -> onNavigate(DesktopTarget.App(r))
                            }
                        },
                        account = account,
                    )
                }
            }
        }
        AppRoute.Notification -> NotificationScreen(
            source = remember { AppNoticeSource() },
            onBack = back,
            onNavigate = { r ->
                when (r) {
                        is AppRoute.Browser -> desktopBrowserTarget(r.url)?.let { openInBrowser(it) }
                    else -> onNavigate(DesktopTarget.App(r))
                }
            },
        )
        AppRoute.Coupon -> DesktopSiteGate(auth, DesktopAuth.COUPON_SITE_KEY, "餐券系统") { site ->
            CouponScreen(
                source = remember(site) { AppCouponSource(site) },
                onBack = back,
            )
        }
        AppRoute.Transcript -> DesktopSiteGate(auth, DesktopAuth.DZPZ_SITE_KEY, "电子凭证") { site ->
            TranscriptScreen(
                source = remember(site) { AppTranscriptSource(site) },
                onBack = back,
                saveSink = DesktopTranscriptSaver,
            )
        }


        // 考勤：有自己的独立登录入口，会话进门时才建。请假附件走系统文件对话框
        // （`DesktopFileDialogs.pickEvidenceFiles`，与 `:app` 的 SAF 那一格同语义：取消 = 空表）。
        AppRoute.Attendance -> DesktopSiteGate(auth, DesktopAuth.ATTENDANCE_SITE_KEY, "考勤") { site ->
            AttendanceScreen(
                source = remember(site) { AppAttendanceSource(site) },
                onBack = back,
                onOpenIclassface = { onNavigate(DesktopTarget.App(AppRoute.Iclassface)) },
                // FileDialog 是**阻塞**的，不在 UI 线程上弹（主线程那侧会卡住整张画面）
                pickEvidenceFiles = { withContext(Dispatchers.IO) { DesktopFileDialogs.pickEvidenceFiles() } },
            )
        }
        is AppRoute.Lms -> DesktopSiteGate(auth, DesktopAuth.LMS_SITE_KEY, "思源学堂") { site ->
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LmsScreen(
                source = remember(site) { AppLmsSource(site, DesktopLmsSaver) },
                initialCourseId = route.courseId,
                initialActivityId = route.activityId,
                onBack = back,
                cacheStore = null,
                showFirstUseHint = !hintPrefs.getBoolean("lms_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("lms_hint_shown", true) },
                videoPlayer = DesktopVideoPlayerHost,
            )
        }

        AppRoute.JwappScore -> DesktopSiteGate(auth, DesktopAuth.JWAPP_SITE_KEY, "移动教务") { _ ->
            val source = remember(auth) { AppJwappScoreSource(auth.sessionManager) }
            JwappScoreScreen(
                source = source,
                studentId = AccountContext.activeAccountId.orEmpty(),
                onBack = back,
                onOpenReport = { onNavigate(DesktopTarget.App(AppRoute.ScoreReport)) },
                onOpenTranscript = if (AccountContext.activeAccountType == AccountType.UNDERGRADUATE) {
                    { onNavigate(DesktopTarget.App(AppRoute.Transcript)) }
                } else null,
                jwxt = source,
                cache = null,
            )
        }

        AppRoute.Iclassface -> DesktopSiteGate(auth, DesktopAuth.ICLASSFACE_SITE_KEY, "快速考勤流水") { site ->
            IclassfaceScreen(
                source = remember(site) { AppIclassfaceSource(site) },
                onBack = back,
            )
        }

        // 社区：上游是 GitHub，不套 `DesktopSiteGate`；登录态存储名与键不能改。
        AppRoute.Community -> CommunityScreen(
            session = remember { SecureGithubSession.get() },
            deviceAuth = remember {
                GithubOAuthDeviceAuthRepository(GithubNetwork.client, CommunityRepo.CLIENT_ID)
            },
            onBack = back,
            onOpenLegacyFeedback = { onNavigate(DesktopTarget.App(AppRoute.Feedback)) },
        )

        // 反馈：上游是飞书开放平台，不套 `DesktopSiteGate`；凭据没配时表单隐藏、只剩外链。
        AppRoute.Feedback -> FeedbackScreen(
            source = remember { AppFeedbackSource },
            onOpenUrl = { url -> if (url.isNotBlank()) openInBrowser(url) },
            onBack = back,
        )

        AppRoute.Jiaocai1 -> DesktopSiteGate(auth, DesktopAuth.JIAOCAI_SITE_KEY, "教材中心") { site ->
            val source = remember(site) { AppJiaocai1Source(site, jiaocai1CacheRoot()) }
            val shelf = remember { DesktopJiaocai1ShelfStore() }
            val vm: Jiaocai1ViewModel = viewModel(key = "jiaocai1-library") {
                Jiaocai1ViewModel(source, shelf)
            }
            vm.bind()
            Jiaocai1LibraryScreen(
                vm = vm,
                images = remember(source) { Jiaocai1PageImages(source) },
                onBack = back,
                onOpenBook = { ssno, title ->
                    onNavigate(DesktopTarget.App(AppRoute.Jiaocai1Reader(ssno, title)))
                },
            )
        }
        AppRoute.Jiaocai -> DesktopSiteGate(auth, DesktopAuth.JIAOCAI_SITE_KEY, "教材中心") { site ->
            TextbookScreen(
                source = remember(site) { AppJiaocaiSource(site) },
                termTextbooks = DesktopJiaocaiTermTextbooks,
                jiaocai1Source = remember(site) { AppJiaocai1Source(site, jiaocai1CacheRoot()) },
                jiaocai1Shelf = remember { DesktopJiaocai1ShelfStore() },
                jiaocai1Cache = null,
                onBack = back,
                onOpenBook = { ssno, title ->
                    onNavigate(DesktopTarget.App(AppRoute.Jiaocai1Reader(ssno, title)))
                },
                initialTab = 0,
                authExpiredRoute = AppRoute.Jiaocai,
            )
        }
        // 阅读器：同一个站点、同一个取数（页面字节与磁盘缓存都在 `AppJiaocai1Source` 里），
        // 解码与内存 LRU 在 `:core` 的 `Jiaocai1PageImages`。书架那份存储与上面共用一个文件。
        is AppRoute.Jiaocai1Reader -> DesktopSiteGate(auth, DesktopAuth.JIAOCAI_SITE_KEY, "教材中心") { site ->
            Jiaocai1ReaderScreen(
                source = remember(site) { AppJiaocai1Source(site, jiaocai1CacheRoot()) },
                shelfStore = remember { DesktopJiaocai1ShelfStore() },
                ssno = route.ssno,
                fallbackTitle = route.title,
                onBack = back,
            )
        }
        AppRoute.DownloadManager -> DownloadManagerScreen(
                host = DesktopDownloads.manager,
            onBack = back,
            dirInfo = DesktopDownloads.dirInfo,
            // 文件那一组：桌面的「登记表」就是 ~/Downloads 这个目录本身（见那个 object 的 KDoc）
            files = DesktopDownloadedFilesHost,
            onPlayTask = { task -> DesktopDownloads.openDownloadedFile(task.filePath) },
        )
        // 设置：屏在 `:core`（`SettingsScreen`，与 `:app` 同一份），
        // 这一端只装配两样：落盘的偏好存储（`desktopSettings`）与宿主槽位（`DesktopSettingsHost`，
        // 声明这一端**真会照着办**的行）。宿主没有的那几组（通知与提醒 / 数据与隐私 / 更新）
        // 整组不画 —— 见那个类的 KDoc 里那张表与 TODO。
        AppRoute.Settings -> SettingsScreen(
            store = desktopSettings,
            host = remember { DesktopSettingsHost(onAppearanceChanged) },
            onBack = back,
        )
        AppRoute.Accounts -> AccountManagerScreen(
            accountManager = rememberDesktopAccounts(auth),
            activeAccountId = auth.activeAccountId,
            onBack = back,
        )
        AppRoute.Games -> GamesScreen(
            onBack = back,
            onNavigate = { onNavigate(DesktopTarget.App(it)) },
            // 桌面没有 BLE ⇒ 棋类那几行不显示「联机」，也不许诺一个点了会失败的模式
            supportsOnline = false,
        )
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        AppRoute.GameGomoku -> GomokuScreen(onBack = back)
        AppRoute.GameHop -> HopScreen(onBack = back)
        AppRoute.GameGo -> GoScreen(onBack = back)
        AppRoute.WebVpnConverter -> WebVpnConverterScreen(
            codec = WebVpnUtil,
            onBack = back,
            onOpenWithWebVpn = { url -> openInBrowser(url) },
            onOpenApply = { openInBrowser(com.xjtu.toolbox.webvpn.WebVpnDenied.APPLY_URL) },
        )

        is AppRoute.Browser -> DesktopBrowserScreen(
            url = route.url,
            then = route.then,
            codec = WebVpnUtil,
            onNavigate = onNavigate,
        )
        AppRoute.GameXiangqi -> XiangqiScreen(onBack = back)

        // 合成西交大（`AppRoute.GameMerge`）：这一条卡的不是**取数**，而是**载体**。
        //
        // 游戏本体是 `:app` 的 `assets/games/merge/`：HTML + 776 行 JS + 本地 Matter.js 物理引擎
        //（408KB，全程不联网），跑在 Android 的 WebView 里。桌面端没有 WebView —— Compose Desktop
        // 的渲染栈是 skiko/Skia，没有浏览器内核；依赖里也没有任何 JVM 上的 JS 引擎
        //（`gradle/libs.versions.toml` 里没有 nashorn / rhino / graaljs / jcef / JavaFX WebView，
        // 而且 JDK 21 已经不再自带 nashorn）⇒ 这 408KB 页面在这一端没有能执行它的地方。
        //
        // 所以这里**如实说明**，而不是落进通用的「没搬过来」页（那一页写的是「取数还在 :app」——
        // 对这条路由是错的），更不另写一屏「简化版合成西交大」冒充它（那是本仓库验收判过 fail 的
        // 「自制探针」模式）。它**不进** `DESKTOP_SUPPORTED_ROUTES`（那里只放真能用的）。
        //
        // TODO（要真点亮它，两条路都要动依赖/资源，不在这一轮）：
        //  ① 引入 JCEF 或 JavaFX WebView 当宿主的 WebView 面，并把 `assets/games/merge/` 搬进
        //     `:core` 的资源（与跳一跳那 9 张地标图同一个搬法）；
        //  ② 把游戏规则（`LEVELS` / 合成计分 / 投放权重）搬进 `:core` 用 Compose 重画 —— 但物理
        //     是它的本体，重画出来是**另一个游戏**，不划算。
        AppRoute.GameMerge -> HostUnavailableScreen(
            route = route,
            title = "合成西交大在桌面上跑不了",
            lines = listOf(
                "它是 WebView 里跑的游戏：HTML + 776 行 JS + 本地 Matter.js 物理引擎，" +
                    "本体在 Android 的 assets/games/merge/（408KB，全程不联网）。",
                "桌面端没有 WebView（Compose Desktop 的渲染栈是 skiko/Skia），依赖里也没有任何 " +
                    "JVM 上的 JS 引擎 ⇒ 这份页面在这一端没有能执行它的地方。",
                "不另写一屏「简化版」冒充它：物理是它的本体，重画出来是另一个游戏。",
            ),
            onNavigate = onNavigate,
        )

        // 匹配交友（`AppRoute.Match`，也是最后一条从「说明页」变真屏的路由）。
        //
        // 它一条请求都不发（KDoc：「全程离线……算契合度这一步在本机完成」）：把自己的档案压成一段码、
        // 把对方的码解回来，交集全从**本机已有的缓存**里算。本轮它按五步法搬进了共享层：
        // 算法与模型（`MatchProfile` / `MatchData` / `DiningHabit`）与屏都进了 `:core`，
        // deflate/inflate 与 URL-safe Base64 切成了 `:core` 的平台缝（Android/jvm 是原来那两份实现；
        // wasmJs 的 D12 ② 之后是 commonMain 里那两份纯 Kotlin 实现），扫码与相册那两条长在平台类型上的路
        // 收成了 `MatchScanner`。
        //
        // 这一端真交出来的是 [DesktopMatchHost]（课表族缓存 = 与「我的课表」「首页」同一份 0600 键值文件）
        // ⇒ **离线算交集这一件事在这一端是真算的**：自己的课表/考试从本端缓存来，对方的码用
        // `:core` 的算法解出来比。另外两维（学工档案 / 校园卡消费）在这一端**没有**（见那份 KDoc 的表），
        // 界面上照旧是禁用 + 写明原因，不拿空数据凑一维。
        //
        // `scanner = null`：桌面没有相机，也没有相册 ⇒ 那一档一个字都不画，屏上直说这一端只能粘
        // 文字码（那颗按钮真能用）。TODO：要么接一个桌面端的相机/截图取码面（`MatchScanner` 的第三份实现），
        // 要么把「选一张图」接到系统文件选择器 + `:core` 的图片缝上去 —— 两者都不在本轮。
        AppRoute.Match -> MatchScreen(
            host = remember(auth) { DesktopMatchHost(auth) },
            onBack = back,
            scanner = null,
            // 那两颗扫码按钮不画（桌面没有相机/相册），但"为什么没有"得说一句：
            // 否则用户会以为这一端坏了，或以为码只能手打。
            gapNote = "这一端没有相机，也没有相册，扫不了码：请用手机的「匹配」扫一下，或者让对方把文字码发给你、" +
                "粘在下面那一格（粘进来的码照常能算，契合度是在本机算的）。",
        )
        AppRoute.Agent -> DesktopAgentScreen(agentHost, onNavigate)

        // 其余路由显示「没搬过来」，不拿有数据的屏冒充。
        else -> NotPortedScreen(route, onNavigate)
    }
}
/** 屁岱在桌面的宿主壳 —— `:core` 的 [AgentScreen] 自己不带顶栏。 */
@Composable
private fun DesktopAgentScreen(host: AgentHost, onNavigate: (DesktopTarget) -> Unit) {
    // 屏把这三格送上来（见 AgentScreen 的 KDoc）：标题跟着助手名走，进配置面板时变成「配置」。
    var title by remember { mutableStateOf(AgentConfig.DEFAULT_ASSISTANT_NAME) }
    var actions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var navIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navIcon?.invoke()
            Text(
                title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            actions?.invoke(this)
        }
        AgentScreen(
            host = host,
            // 挑图走系统文件对话框（`rememberDesktopAgentImagePicker`）；这一档由 honored 里
            // 的 IMAGE_INPUT 决定要不要给入口，两边同源。
            imagePicker = rememberDesktopAgentImagePicker(),
            onNavigate = { route -> onNavigate(DesktopTarget.App(route)) },
            onTitleChange = { title = it },
            onActionsChange = { actions = it },
            onNavIconChange = { navIcon = it },
        )
    }
}


/** 全部页面：先列真能用的，再列屏在了但没数据源的。 */
@Composable
internal fun RoutesPage(auth: DesktopAuth, onNavigate: (DesktopTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("桌面端（Stage A）", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "屏全部来自 :core —— 与 Android、Web 是同一份源码。取数来自 :data：" +
                "这一端自己登录、自己直连学校站点，不经过任何我方的服务器。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "已登录 · 会话已就绪（图书馆站点）",
            color = cs.primary,
            fontSize = 12.sp,
        )

        Text("真能用（${DESKTOP_SUPPORTED_ROUTES.size}）", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        DESKTOP_SUPPORTED_ROUTES.forEach { (route, label) ->
            TextButton(
                text = "$label（${route.id}）",
                onClick = { onNavigate(DesktopTarget.App(route)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "屏在了、还没有数据源（${DESKTOP_PENDING_ROUTES.size}）",
            color = cs.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (DESKTOP_PENDING_ROUTES.isEmpty())
                "现在是 0：17 条真数据路由都搬完了 —— 「屏在 :core 而取数还在 :app」的路由一条不剩。" +
                    "这一栏留着是因为它是 Stage A 的进度表：屏先落地、取数后到是常规的中间态，" +
                    "下一屏要搬时它会重新长出条目。"
            else
                "下面这些屏已经是 :core 的共享屏，卡的是**取数**：它们的 `*Api` / 站点类还在 :app。" +
                    "搬到 :data 一条就能用一条。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        DESKTOP_PENDING_ROUTES.forEach { (route, label) ->
            TextButton(
                text = "$label（${route.id}）",
                onClick = { onNavigate(DesktopTarget.App(route)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TextButton(
            text = "退出登录（清除这条账号的凭据、会话与记录）",
            onClick = { auth.logout() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "退出会删掉：这条账号的记录、它的 cookie 与站点快照。" +
                "其余账号一条不动（多账号在「账号管理」那一屏）。" +
                "共享的机器上请不要留着登录态。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
    }
}

/** 「这一屏还没搬到桌面」的占位页。 */
@Composable
private fun NotPortedScreen(route: AppRoute, onNavigate: (DesktopTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("这一屏还没搬到桌面", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("AppRoute.${route::class.simpleName ?: "?"}", color = cs.primary, fontSize = 13.sp)
        Text("路由 id：${route.id}", color = cs.onBackgroundVariant, fontSize = 12.sp)
        Text(
            if (route.loginType != null)
                "它需要 ${route.loginType} 的站点会话 —— 那个站点的类与它的 *Login 还在 :app，" +
                    "没搬进 :data。搬一个站点就有数据源。"
            else
                "它的取数还在 :app / campus-api 的只读端，没进 :core / :data。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "桌面的取数不走 campus-api（设计文档 C2：任何人独立安装、独立登录）——" +
                "所以 Stage 0 那批脚手架屏幕这一轮换成了如实说明，而不是继续借 campus-api 画画面。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/** 「这一屏卡的不是取数，而是宿主的某一项能力」的说明。 */
@Composable
private fun HostUnavailableScreen(
    route: AppRoute,
    title: String,
    lines: List<String>,
    onNavigate: (DesktopTarget) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        // 自己画底：`NotPortedScreen` / `MissingIdentityPage` 那两页没画，靠窗口底色 —— 而离屏
        // 证据图里没有窗口，于是深色模式下会变成「浅字透明底」（图上看着像坏了）。这里补上。
        modifier = Modifier.fillMaxSize().background(cs.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("AppRoute.${route::class.simpleName ?: "?"}", color = cs.primary, fontSize = 13.sp)
        Text("路由 id：${route.id}", color = cs.onBackgroundVariant, fontSize = 12.sp)
        lines.forEach { line ->
            Text(line, color = cs.onBackgroundVariant, fontSize = 12.sp)
        }
        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}
/** 「没有身份就取不了数」的说明。 */
@Composable
private fun MissingIdentityPage(
    onNavigate: (DesktopTarget) -> Unit,
    title: String,
    lines: List<String>,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        lines.forEachIndexed { index, line ->
            Text(
                line,
                color = cs.onBackgroundVariant,
                fontSize = if (index == 0) 13.sp else 12.sp,
            )
        }
        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/** MFA（短信二验）弹窗宿主。 */
@Composable
private fun DesktopMfaDialog(auth: DesktopAuth) {
    val manager = auth.sessionManager
    DisposableEffect(manager) {
        val detach = manager.attachMfaHost()
        onDispose { detach() }
    }

    val active by manager.activeMfaRequest.collectAsState()
    val request = active ?: return

    var phone by remember(request) { mutableStateOf<String?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val rejections by request.rejections.collectAsState()

    LaunchedEffect(request) {
        runCatching { withContext(Dispatchers.IO) { request.mfaContext.getPhoneNumber() } }
            .onSuccess { phone = it }
            .onFailure { error = FriendlyError.of(it, "获取验证手机号") }
    }
    LaunchedEffect(rejections) { if (rejections > 0) error = "验证码不对，请重新输入" }

    MfaCodeDialog(
        siteName = request.siteName,
        phone = phone,
        error = error,
        // 被拒一次就换一个 attempt ⇒ 输入框自己清空（见 MfaCodeDialog 的 attempt 参数）
        attempt = rejections,
        onSubmit = { code -> if (!request.submit(code)) error = "提交失败" },
        onCancel = { request.cancel() },
    )
}
