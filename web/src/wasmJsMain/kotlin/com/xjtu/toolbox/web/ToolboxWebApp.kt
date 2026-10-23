package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import com.xjtu.toolbox.platform.WebBriefMessages
import com.xjtu.toolbox.platform.showBriefMessage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusIclassfaceApi
import com.xjtu.toolbox.core.net.CampusJiaocaiApi
import com.xjtu.toolbox.core.net.CampusJwappScoreApi
import com.xjtu.toolbox.core.net.CampusLmsApi
import com.xjtu.toolbox.core.net.CampusFeedbackApi
import com.xjtu.toolbox.core.net.CampusPayApi
import com.xjtu.toolbox.core.net.CampusWebVpnApi
import com.xjtu.toolbox.core.net.CampusJwxtScheduleApi
import com.xjtu.toolbox.feedback.FeedbackScreen
import com.xjtu.toolbox.pay.PaymentCodeScreen
import com.xjtu.toolbox.pay.PaymentSelectionStore
import com.xjtu.toolbox.social.MatchScreen
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.community.GithubSession
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.coupon.CouponScreen
import com.xjtu.toolbox.dzpz.TranscriptScreen
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.core.net.CampusCardNetApi
import com.xjtu.toolbox.core.net.CampusAttendanceApi
import com.xjtu.toolbox.core.net.CampusCouponApi
import com.xjtu.toolbox.core.net.CampusDormPowerApi
import com.xjtu.toolbox.core.net.CampusTranscriptApi
import com.xjtu.toolbox.core.net.CampusInboxApi
import com.xjtu.toolbox.core.net.CampusEmptyRoomApi
import com.xjtu.toolbox.core.net.CampusLibraryApi
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.iclassface.IclassfaceScreen
import com.xjtu.toolbox.jiaocai.TextbookScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1LibraryScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1PageImages
import com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1ViewModel
import com.xjtu.toolbox.jiaocai1.ServeJiaocai1Api
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.media.DownloadManagerScreen
import com.xjtu.toolbox.media.ServeDownloadsApi
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.core.net.CampusFitnessApi
import com.xjtu.toolbox.core.net.CampusSchoolCourseApi
import com.xjtu.toolbox.core.net.CampusFacultyApi
import com.xjtu.toolbox.core.net.CampusNoticeApi
import com.xjtu.toolbox.core.net.CampusGradesApi
import com.xjtu.toolbox.core.net.CampusSchoolCalendarApi
import com.xjtu.toolbox.core.net.CampusYellowPageApi
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.hop.HopScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.legal.EulaScreen
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.inbox.InboxScreen
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.shell.AppShell
import com.xjtu.toolbox.shell.ShellNavState
import com.xjtu.toolbox.shell.ShellPageHost
import com.xjtu.toolbox.shell.ShellPageInsets
import com.xjtu.toolbox.shell.ShellTarget
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.calculateIsWideLayout
import com.xjtu.toolbox.ui.glass.glassNavStyle
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Web 外壳：共享壳 + `:core` 的屏。 */
@Composable
fun ToolboxWebApp(onAppearanceChanged: () -> Unit = {}) {
    val client = remember { toolboxWebClient() }
    val session = remember { WebGithubSession(client) }
    // serve 模式的会话（登录 / 短信二验 / 登出）：令牌持有者是页面级单例，见 ServeSession.kt
    val serveSession = remember { WebServeSession(client, serveToken) }
    // 后端模式：探测完成前**不起任何屏** —— 取数装配要按模式选实现，猜错了就是一批白打的请求
    var mode by remember { mutableStateOf<ApiMode?>(null) }
    val initial = remember { initialWebTarget() }
    var target: WebTarget by remember { mutableStateOf(initial) }
    val archiveActions = remember { WebArchiveActions() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var afterLogin by remember { mutableStateOf<WebTarget>(WebTarget.App(AppRoute.Schedule)) }
    // 界面风格（`nav_bar_style`）：与深色模式同一条路——外壳自己读一次，设置页改完回调捅一下重读。
    // 宽屏时期壳走侧栏，玻璃底栏不会出现，判据见 `:shared:designsystem` 的 `glassNavStyle`。
    val shellSettings = remember { SettingsStore() }
    var shellTick by remember { mutableIntStateOf(0) }
    val navBarStyle = remember(shellTick) { shellSettings.navBarStyle }
    val notifyShellAppearance = {
        shellTick++
        onAppearanceChanged()
    }
    DisposableEffect(Unit) {
        WebBriefMessages.sink = { message -> scope.launch { snackbar.showSnackbar(message) } }
        onDispose { WebBriefMessages.sink = null }
    }
    DisposableEffect(mode) {
        WebAuthEvents.onUnauthorized = {
            if (mode == ApiMode.SERVE && target != WebTarget.Serve) {
                afterLogin = target
                target = WebTarget.Serve
                showBriefMessage("登录已过期，请重新登录")
            }
        }
        onDispose { WebAuthEvents.onUnauthorized = null }
    }
    LaunchedEffect(Unit) {
        browserSearchParam("token")?.let {
            serveSession.setToken(it)
            browserStripTokenParam()
        }
        mode = withTimeoutOrNull(BACKEND_DETECT_TIMEOUT_MS) { detectApiMode(client) } ?: ApiMode.CAMPUS_API
        // serve 模式：过不了闸门（没令牌 / 令牌不对）就直接落到「会话」屏去贴令牌。
        if (mode == ApiMode.SERVE && serveSession.sessionState().isFailure) {
            target = WebTarget.Serve
        }
    }

    val backend = mode
    // 当前页交给共享壳的导航模型：五格 + 当前页 + 「点了哪一格」。
    val navState = ShellNavState(
        tabs = webTabs(),
        target = target.toShell(),
        onSelect = { tab ->
            // 五格里每一格都有对应的一档；映射不出来就不动（不假装能去）—— 与桌面同一条写法。
            tab.target.toWeb()?.let { target = it }
        },
        // 账号角标：这一端没有账号（凭据在 campus-api / serve 进程里）⇒ 内容恒为 null
        // ⇒ 壳一个角标都不画（`ShellNavState.badge` 的判据）。不是"藏起来"，是真没有数字可写。
        accountBadge = null,
    )
    // 窗口形状喂给共享壳（`:app` 那边由 `AppNavigation` 算好往下提供）：窗口够宽时底栏换侧栏，
    // 判据就是 `:core` 那条 `calculateIsWideLayout` —— 与 Android 在宽屏 / 折叠屏上同一套。
    val wideLayout = calculateIsWideLayout()
    CompositionLocalProvider(LocalIsWideLayout provides wideLayout) {
      Box(Modifier.fillMaxSize()) {
        AppShell(
            nav = navState,
            // 这一端的导航模型是**路由表**（`:core` 的路由里多数不属于底栏任何一格）⇒ 只画当前这一页，
            // 不做「进过的 tab 常驻」（那是 `:app` 五格的形态，见 ShellPageHost）。
            pageHost = ShellPageHost.CURRENT_ONLY,
            // 真读设置里的界面风格（与 `:app` 同一个键）：不宽屏时是玻璃胶囊底栏，选「经典」就是今天这一套
            glassStyle = glassNavStyle(navBarStyle, wideLayout),
            bottomInset = 0.dp,
            contentWindowInsets = WindowInsets(0),
            page = { opened, insets ->
                when {
                    backend == null -> Text(
                        "正在探测数据源（serve 模式的 :server / 同源反代 campus-api）…",
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )

                    else -> when (val t = opened.target) {
                        is ShellTarget.App -> AppPage(
                            route = t.route,
                            client = client,
                            mode = backend,
                            session = session,
                            insets = insets,
                            // 最后一格（设置屏）改完外观要回到外壳这一层重读（含界面风格）
                            onAppearanceChanged = notifyShellAppearance,
                            archiveActions = archiveActions,
                            onNavigate = { target = it },
                        )

                        // 这一端没有「全部页面」那一档（底栏里也不会长出那一格）—— 落到本端能力页，不白屏。
                        ShellTarget.AllPages -> WebServicesScreen(
                            serveMode = backend == ApiMode.SERVE,
                            onNavigate = { target = it },
                        )

                        is ShellTarget.Custom -> when (t.id) {
                            WebRoutes.PROBE -> ProbeScreen()
                            WebRoutes.EULA -> EulaScreen(onAccept = { target = WebTarget.Probe })
                            WebRoutes.SERVE -> WebServeSessionScreen(serveSession) {
                                target = afterLogin
                                afterLogin = WebTarget.App(AppRoute.Schedule)
                            }

                            WebRoutes.SERVICES -> WebServicesScreen(
                                serveMode = backend == ApiMode.SERVE,
                                onNavigate = { target = it },
                            )

                            WebRoutes.PROFILE -> SettingsScreen(
                                store = remember { SettingsStore() },
                                host = remember(onAppearanceChanged) {
                                    WebSettingsHost(onAppearanceChanged, archiveActions)
                                },
                                onBack = { target = WebTarget.App(AppRoute.Schedule) },
                            )

                            // 壳里认不出的目标（将来的 Custom id）：写明"认不出"，不白屏也不猜。
                            else -> WebUnavailableScreen(
                                title = "认不出这一页（${t.id}）",
                                why = "外壳把当前页给成了这个 Custom id，而这一端没有对应的屏。",
                                missing = listOf("Custom(\"${t.id}\") 的落点"),
                            )
                        }
                    }
                }
            },
        )
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
      }
    }
}

/** 探测后端最多等多久。 */
private const val BACKEND_DETECT_TIMEOUT_MS = 3_000L

/**
 * 规范化默认启动落点 → 这一端开场那一页（三张映射表之一：另两张是 `:app` 的 `bottomTabOf()`
 * 与 `:desktop` 的 `desktopStartTarget()`）。
 *
 * 本端五格与 `:app` 逐字同构（`WebShell.WEB_SHELL_TABS`），所以这里是一一对应：
 * `home → Main`、`schedule → Schedule`、`agent → Agent`、`services → WebTarget.Services`、
 * `profile → WebTarget.Profile`。认不出（老文件、手工改过的值）退日程：与
 * `SettingsStore.TAB_KEY_DEFAULT` 同一档。
 */
internal fun webStartTarget(defaultTab: String): WebTarget =
    when (SettingsStore.normalizeDefaultTab(defaultTab)) {
        SettingsStore.TAB_KEY_HOME -> WebTarget.App(AppRoute.Main)
        SettingsStore.TAB_KEY_AGENT -> WebTarget.App(AppRoute.Agent)
        SettingsStore.TAB_KEY_SERVICES -> WebTarget.Services
        SettingsStore.TAB_KEY_PROFILE -> WebTarget.Profile
        else -> WebTarget.App(AppRoute.Schedule)
    }

/** `?route=<id>`。 */
internal fun initialWebTarget(): WebTarget = when (val raw = browserRouteParam()) {
    // 没给 `?route=` 时落到设置里那格「默认启动 Tab」（以前写死 Schedule，那一行没人读）。
    null -> webStartTarget(SettingsStore().defaultTab)
    WebRoutes.PROBE -> WebTarget.Probe
    WebRoutes.EULA -> WebTarget.Eula
    // `?route=serve`：serve 模式的会话屏（登录 / 短信二验 / 登出）。非 serve 模式下它也在路由表里
    //（深链不会突然 404），只是底栏不会长出那一格 —— 那一屏不是底栏的一部分，见 WebShell.kt。
    WebRoutes.SERVE -> WebTarget.Serve
    else -> WebTarget.App(appRouteOf(raw) ?: AppRoute.Schedule)
}

/** 一屏共享页（或本端的那几张页）。 */
@Composable
private fun AppPage(
    route: AppRoute,
    client: HttpClient,
    /** 这一份 `:web` 在对谁说话（见 [ApiMode]）：所有 `Campus*Api` 都按它选解析形状与基址。 */
    mode: ApiMode,
    session: WebGithubSession,
    /** 壳算给当前页的留白（经典 / 侧栏形态下都是 0，见 `ShellPageInsets`）。 */
    insets: ShellPageInsets,
    /** 外观改完之后戳一下主题那一层（只有设置屏用得上）。 */
    onAppearanceChanged: () -> Unit,
    /** 设置屏「数据与隐私」那两行归档动作的宿主（外壳建一份）：屏自己不开浏览器下载锚点与文件选择框，只把点击转给这里。 */
    archiveActions: WebArchiveActions,
    onNavigate: (WebTarget) -> Unit,
) {
    val back = { onNavigate(WebTarget.App(AppRoute.Schedule)) }
    when (route) {
        AppRoute.Schedule -> Column(Modifier.fillMaxSize()) {
            WebScheduleGapNote(mode)
            Box(Modifier.weight(1f)) {
                ScheduleScreen(
                    host = remember(client, mode) { WebScheduleHost(client, mode) },
                    // 这一屏在底栏第二格（不是压栈进来的）⇒ 与 `:app` 同一写法：不画返回动作。
                    onBack = {},
                    contentBottomPadding = insets.bottomBarReserve,
                    onNavigate = { r ->
                        when (r) {
                            // 只认 url：then 这一端丢掉（没有内置浏览器那层就做不了第二跳）——
                            // 见 `WebSupport` 文件头那条「如实说明」。
                            is AppRoute.Browser -> if (r.url.isNotBlank()) navigateSameTab(r.url)
                            else -> onNavigate(WebTarget.App(r))
                        }
                    },
                )
            }
        }

        AppRoute.Main -> WebHomeScreen(
            client = client,
            mode = mode,
            insets = insets,
            onNavigate = onNavigate,
        )

        AppRoute.Agent -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "屁岱",
                endpoints = "GET/POST /api/agent/{config,models,sessions*,memory*} + POST /api/agent/chat（SSE） + /stop",
                why = "屁岱的模型调用、工具取数、会话与记忆、API Key 全在 serve 那一侧（campus-api 形态下这几个域没有端点）。",
                section = "§5.11",
            )
        } else {
            WebAgentScreen(
                client = client,
                insets = insets,
                onNavigate = onNavigate,
            )
        }

        // 内置浏览器：同标签导航（深链没有用户手势，`window.open` 会被拦）。⚠️ 必须写 `is`：裸写会被当成构造默认实例再比相等。
        // 只交第一跳 `url`：`then`（先登录再跳下一站）在浏览器里做不到 —— 整个标签页交出去了就没有回调，
        // 本端**不保证**会走到 then，也不假装会（`WebSupport` 文件头那条｜桌面端 `desktopBrowserTarget` 同口径）。
        is AppRoute.Browser -> {
            LaunchedEffect(route.url) {
                if (route.url.isNotBlank()) navigateSameTab(route.url) else back()
            }
        }
        // 校历：与 Android 同一个屏、同一份模型与算法（:core/calendar），只换取数——
        // 浏览器不能直连 workflow.xjtu.edu.cn（无 CORS 头），走 campus-api 同源反代。
        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember(client, mode) { CampusSchoolCalendarApi(client, API_BASE, mode) },
            onBack = back,
        )
        AppRoute.Fitness -> FitnessScreen(
            source = remember(client, mode) { CampusFitnessApi(client, API_BASE, mode) },
            onBack = back,
        )
        // 成绩：与 Android 同一个屏与模型（:core/score），取数换成 campus-api 的精确成绩。
        // 两个端上游不是同一个接口（:app 解析帆软报表 HTML），字段对齐写在 CampusGradesApi 的 KDoc 里。
        AppRoute.ScoreReport -> ScoreReportScreen(
            source = remember(client, mode) { CampusGradesApi(client, API_BASE, mode) },
            onBack = back,
            cache = remember { WebScoreReportCache() },
        )
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember(client, mode) { CampusYellowPageApi(client, API_BASE, mode) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        AppRoute.Games -> GamesScreen(
            onBack = back,
            onNavigate = { onNavigate(WebTarget.App(it)) },
            // 浏览器没有 BLE ⇒ 棋类那几行不显示「联机」标签，也不许诺一个点了会失败的模式。
            supportsOnline = false,
        )
        AppRoute.Game2048 -> Gpa2048Screen(onBack = back)
        AppRoute.GameBlocks -> BlocksScreen(onBack = back)
        // 五子棋：与 Android 同一个屏（:core/game/gomoku）。联机那一格不出现 ——
        // 浏览器没有 BLE，onlineLobby 传 null（不是做一个假大厅）。
        AppRoute.GameGomoku -> GomokuScreen(onBack = back)
        // 围棋：与 Android 同一个屏（:core/game/go）。同样没有联机那一格（无 BLE）。
        AppRoute.GameGo -> GoScreen(onBack = back)
        AppRoute.GameXiangqi -> XiangqiScreen(onBack = back)
        // 跳一跳：与 Android 同一个屏（:core/game/hop）。9 张地标图按端注入：Android 用
        // R.drawable、Web 用 :web 的 composeResources（同一份 webp 字节，见 WebHopLandmarks.kt）。
        AppRoute.GameHop -> HopScreen(
            onBack = back,
            landmarkImages = rememberWebHopLandmarkImages(),
        )
        AppRoute.GameMerge -> WebGameMergeScreen(onBack = back)

        // 通知公告：与 Android 同一个屏与模型（:core/notification），取数换成 campus-api ——
        // 它覆盖同样 29 个源，两端拿到的是同一批通知（差别只在「谁去爬」）。
        AppRoute.Notification -> NotificationScreen(
            source = remember(client, mode) { CampusNoticeApi(client, API_BASE, mode) },
            onBack = back,
            onNavigate = { onNavigate(WebTarget.App(it)) },
        )
        // 教师主页检索：取数是 campus-api 的 `/api/info/faculty`（降级见 CampusFacultyApi 的 KDoc）。
        AppRoute.Faculty -> FacultyScreen(
            source = remember(client, mode) { CampusFacultyApi(client, API_BASE, mode) },
            onBack = back,
            onOpenUrl = { openInNewTab(it) },
        )
        AppRoute.SchoolCourse -> SchoolCourseScreen(
            source = remember(client, mode) { CampusSchoolCourseApi(client, API_BASE, mode) },
            onBack = back,
        )
        AppRoute.Judge -> WebJudgeScreen(client, mode, onBack = back)
        AppRoute.Inbox -> InboxScreen(
            source = remember(client, mode) { CampusInboxApi(client, API_BASE, mode) },
            onBack = back,
            onOpen = { id ->
                when (val r = appRouteOf(id)) {
                    is AppRoute.Browser -> openInNewTab(r.url)
                    null -> Unit
                    else -> onNavigate(WebTarget.App(r))
                }
            },
        )
        AppRoute.EmptyRoom -> {
            val prefs = remember { keyValueStore("empty_room") }
            EmptyRoomScreen(
                source = remember(client, mode) { CampusEmptyRoomApi(client, API_BASE, mode) },
                accountType = null,
                onBack = back,
                showCdnTip = !prefs.getBoolean("empty_room_cdn_tip", false),
                onCdnTipRead = { prefs.putBoolean("empty_room_cdn_tip", true) },
            )
        }
        AppRoute.CampusCard -> CampusCardScreen(
            source = remember(client, mode) { CampusCardNetApi(client, API_BASE, mode) },
            onBack = back,
        )
        // 体育场馆：campus-api 只读；serve 下能下单，滑块在本端画，自动识别在 wasm 上没有。
        AppRoute.Venue -> WebVenueScreen(client = client, mode = mode, onBack = back)
        // 图书馆座位：campus-api 只读；serve 下能写；没有平面图端点 ⇒ 不画平面图。
        AppRoute.Library -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LibraryScreen(
                source = remember(client, mode) { CampusLibraryApi(client, API_BASE, mode) },
                onBack = back,
                showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("library_hint_shown", true) },
            )
        }

        // ── 只读四域（契约 §5.5）：非 serve 形态画 `WebServeOnlyScreen` ──
        AppRoute.Transcript -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "电子成绩单",
                endpoints = "GET /api/dzpz/transcript + POST /api/dzpz/transcript/{submit,preview} + GET /download?ticket=",
                why = "成绩单走 `dzpz` 站点的会话。",
                section = "§5.12",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏与 App / 桌面**同一个屏、同一条链**：serve 那边是**两段式**（契约 §5.12）——" +
                        "① POST /api/dzpz/transcript/submit 建申请单（幂等：10 分钟内重复点复用同一张）；" +
                        "② POST preview 生成 + 签章 + 取一次性票据；③ GET download?ticket= 换 PDF 字节。" +
                        "⚠️ 第①步**真的会在教务留一条待审批记录**（屏上那个按钮就是这件事）。" +
                        "两处与 Android / 桌面不同（都是这一端本来的样子）：进度条那五步在这里有几步是" +
                        "「同一发 HTTP」—— 学籍联动是空壳（服务端在建单那一发里自己做，返回值里有学号），" +
                        "签章与第二次提交就在 preview 那一发里；保存 PDF 交给浏览器自己下（存到浏览器的下载目录）。",
                )
                Box(Modifier.weight(1f)) {
                    // saveSink：浏览器的实现（Blob + 下载锚点，存到浏览器自己的下载目录）——
                    // 与 Android 的 MediaStore / 桌面的文件选择框同一个槽位，见 `WebTranscriptSaveSink`。
                    TranscriptScreen(
                        source = remember(client, mode) { CampusTranscriptApi(client, API_BASE, mode) },
                        onBack = back,
                        saveSink = WebTranscriptSaveSink,
                    )
                }
            }
        }

        AppRoute.Attendance -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "考勤",
                endpoints = "GET /api/attendance/{terms,records} + /api/attendance/leave/{window,preview,records} + POST /api/attendance/leave{,/withdraw,/cancel}",
                why = "考勤走 `new_attendance` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "两处如实不画：① 考勤**流水那一栏**的上课教师名（那一格是当堂上课的教师名，§5.2 的" +
                        "例外清单里没有它 —— 红线）；② 本人姓名那一格（getStudentInfo 不端）。" +
                        "⚠️ 「统计」与「打卡流水」两栏**已经真能用了**（2026-10-22 批5：契约 §5.12 的" +
                        "/api/attendance/{stats,streams}）—— 统计那一栏的数字是**服务端按同一算路算的**" +
                        "（算路在 `:data`，只有 JVM/Android 变体，浏览器里拿不到它）。" +
                        "请假那一族的**两类姓名已按 §5.2 例外 ④ 端出来**（审批人姓名、请假单里那节课的任课教师姓名）；" +
                        "「请假」那一栏**已经真能用了**（窗口 / 审批人 / 列表 / 提交 / 撤回 / 销假六条端点）；" +
                        "只有**附件**没端 —— 本端没有文件选择宿主，与桌面同一条（不发附件）。",
                )
                Box(Modifier.weight(1f)) {
                    AttendanceScreen(
                        source = remember(client, mode) { CampusAttendanceApi(client, API_BASE, mode) },
                        onBack = back,
                        onOpenIclassface = null,
                    )
                }
            }
        }

        AppRoute.Coupon -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "加餐券",
                endpoints = "GET /api/coupon/list + /api/coupon/detail + POST /api/coupon/activate",
                why = "加餐券走 `coupon` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "一处如实不画：券封面图 —— 那张图要带 egc 的 Authorization 才取得到，浏览器直接打开" +
                        "那个 URL 会拿到登录页（契约里没有图片代理端点）⇒ 券卡退化成图标。金额一格单位是分" +
                        "（屏自己除 100 显示，本端不换算）。「领取」按钮**照旧画**：领券已端" +
                        "（POST /api/coupon/activate），失败会如实报（领券被上游拒 / 会话失效都落 502）。",
                )
                Box(Modifier.weight(1f)) {
                    CouponScreen(
                        source = remember(client, mode) { CampusCouponApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }

        // 宿舍电费：用 `:web` 自己的读数页（`:core` 的 `DormPowerScreen` 只是缴费页浏览器，serve 没有那个端点）。
        AppRoute.DormPower -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "宿舍电费",
                endpoints = "GET /api/dorm/rooms + /api/dorm/reading",
                why = "宿舍电费走 `ssn` 站点的会话（`cid` 是登录时写进会话的）。",
            )
        } else {
            WebDormPowerScreen(
                source = remember(client, mode) { CampusDormPowerApi(client, API_BASE, mode) },
                onBack = back,
            )
        }

        // ── 第二批只读域（契约 §5.6）：非 serve 形态画 `WebServeOnlyScreen` ── ⚠️ 必须写 `is AppRoute.Lms`。
        is AppRoute.Lms -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "思源学堂",
                endpoints = "GET /api/lms/courses + /api/lms/activities + /api/lms/activity",
                why = "思源学堂走 `lms` 站点的会话（独立登录页，直连 CAS）。",
            )
        } else {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "四处如实不画：① 教师姓名 —— 契约不投影 `instructors`，课程卡上那一格显示「未知」；" +
                        "② 附件的预览与下载、以及「批量下载」那一整档 —— 附件的三个地址都没有端点" +
                        "（要站点会话，浏览器直接打开只会拿到登录页）；③ 回放与直播的**播放地址**、" +
                        "作业的**提交记录** —— 也都不投影（提交记录里有提交人姓名与学号）。" +
                        "详情正文是服务端端出的 HTML 原文，本端按纯文本近似显示（契约刻意不端「转纯文本」" +
                        "那个纯函数）；「已提交」标签在详情打开过之后才画。",
                )
                Box(Modifier.weight(1f)) {
                    LmsScreen(
                        source = remember(client, mode) { CampusLmsApi(client, API_BASE, mode) },
                        onBack = back,
                        // 深链 `?route=lms?courseId=` 带的课程：一到课程列表就直接翻到那门课的活动页
                        initialCourseId = route.courseId,
                        initialActivityId = route.activityId,
                        // 课表缓存那一格不传（null = 不缓存，取数照常）：浏览器里的 localStorage 与
                        // `LmsCacheStore` 的语义对不上，本端不假装有一份首屏秒开。
                        cacheStore = null,
                        // 「功能说明」弹过了没：与 App 同一个 pref 文件、同一个键（本端存 localStorage）
                        showFirstUseHint = !hintPrefs.getBoolean("lms_hint_shown", false),
                        onFirstUseHintRead = { hintPrefs.putBoolean("lms_hint_shown", true) },
                        // 播放器槽位不传：serve 形态下回放/直播的地址不投影 ⇒ 屏上没有可点的播放动作，
                        // 那条「本端没有播放器」的说明页也走不到（地址不在，那一档不出现）。
                        videoPlayer = null,
                    )
                }
            }
        }

        AppRoute.Jiaocai -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "教材查询",
                endpoints = "GET /api/jiaocai/search + /api/jiaocai/ssno",
                why = "教材中心走 `jiaocai` 站点的会话（`/entry/login` → 超星 SSO）。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏真接上了两处（2026-10-14 批①收尾）：「查教材」那一栏与【本学期我的教材】那一段" +
                        "（那一段的数据在**教务**的报表里，serve 契约 §5.8 已端出它：`GET /api/jwxt/textbooks`，" +
                        "学号由服务端从会话里取 ⇒ 与日程屏的教材那一栏同一个端点）。「查教材」那一栏检索只有一个关键词输入：" +
                        "没有院系/教师筛选，也没有翻页；结果里的书目号（ssno）在列表里恒为空，要它进详情才补。" +
                        "全文阅读（`jiaocai1`）在 serve 形态下**真能用了**（2026-10-22 批④）：那一半走契约 §5.14" +
                        "的 `/api/jiaocai1/*` ⇒ 书架与全文库两栏真画，「读全文 / 阅读全文」的入口也都接上了。" +
                        "⚠️ 页图与封面是**一发一发同源 fetch**（不做本机预取窗口），见 `WebJiaocai1Source`。",
                )
                val api = remember(client, mode) { ServeJiaocai1Api(client, API_BASE, mode) }
                Box(Modifier.weight(1f)) {
                    TextbookScreen(
                        source = remember(client, mode) { CampusJiaocaiApi(client, API_BASE, mode) },
                        termTextbooks = remember(client, mode) {
                            WebJiaocaiTermTextbooks(CampusJwxtScheduleApi(client, API_BASE, mode))
                        },
                        jiaocai1Source = remember(api) { WebJiaocai1Source(api) },
                        jiaocai1Shelf = remember(api) { WebJiaocai1ShelfStore(api) },
                        jiaocai1Cache = null,
                        onBack = back,
                        onOpenBook = { ssno, title ->
                            onNavigate(WebTarget.App(AppRoute.Jiaocai1Reader(ssno, title)))
                        },
                    )
                }
            }
        }

        AppRoute.Jiaocai1 -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "教材全文",
                endpoints = "GET /api/jiaocai1/{classify,search,book,page,cover,shelf} + POST/DELETE /api/jiaocai1/shelf{,/progress}",
                why = "全文库借的是**教材中心**那个站点的会话（全文库自己的会话由 `jiaocai.lib` 的 `portalLogin` 桥接过来）。",
                section = "§5.14",
            )
        } else {
            val api = remember(client, mode) { ServeJiaocai1Api(client, API_BASE, mode) }
            val source = remember(api) { WebJiaocai1Source(api) }
            val shelf = remember(api) { WebJiaocai1ShelfStore(api) }
            // ⚠️ key 与 `TextbookScreen` 内部那个（`jiaocai1-library`）**刻意不同**：两屏可能前后脚都进过，
            // 共用一个 key 会让后进来的那一屏拿到先前那个 VM（工厂不会再跑），而两边的装配并不等价
            //（`TextbookScreen` 那一份是三栏共用、还带着「查教材」那一半）。
            val vm: Jiaocai1ViewModel = viewModel(key = "jiaocai1-library-web") { Jiaocai1ViewModel(source, shelf) }
            vm.bind()
            Jiaocai1LibraryScreen(
                vm = vm,
                images = remember(source) { Jiaocai1PageImages(source) },
                onBack = back,
                onOpenBook = { ssno, title ->
                    onNavigate(WebTarget.App(AppRoute.Jiaocai1Reader(ssno, title)))
                },
            )
        }

        // 阅读器：同一个取数宿主、同一份书架（屏内部自己起它的 VM，与 Android / 桌面同一份）。
        is AppRoute.Jiaocai1Reader -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "教材全文阅读",
                endpoints = "GET /api/jiaocai1/{book,page,cover} + POST /api/jiaocai1/shelf{,/progress}",
                why = "阅读器与书架都在 serve 那一侧（会话借自教材中心站点）。",
                section = "§5.14",
            )
        } else {
            val api = remember(client, mode) { ServeJiaocai1Api(client, API_BASE, mode) }
            Jiaocai1ReaderScreen(
                source = remember(api) { WebJiaocai1Source(api) },
                shelfStore = remember(api) { WebJiaocai1ShelfStore(api) },
                ssno = route.ssno,
                fallbackTitle = route.title,
                onBack = back,
            )
        }

        AppRoute.JwappScore -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "成绩",
                endpoints = "GET /api/jwapp/scores + /api/jwapp/precise-scores + /api/jwapp/score-detail",
                why = "三条端点分属两个站点（`scores` 与 `score-detail` 打移动教务，`precise-scores` 打教务），两个站点各要自己的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "一处如实缺口：未评教名单与「按学号补的报表」那两路 —— 一个属评教那一侧、" +
                        "一个要学号（红线），本端都不调（学号那一格传空串）。其余都真读：学期成绩 + GPA + " +
                        "「点开一门课」的分项（`/api/jwapp/score-detail`，课程没有分项时出的是空明细而不是" +
                        "「加载失败」）。成绩是两份上游拼的：移动教务的粗分 + 教务的精确成绩，**后者拿不到时" +
                        "只出前一份**（那条端点的失败被屏的 VM 容忍），所以整屏照常可用。",
                )
                Box(Modifier.weight(1f)) {
                    val scoreSource = remember(client, mode) { CampusJwappScoreApi(client, API_BASE, mode) }
                    JwappScoreScreen(
                        source = scoreSource,
                        studentId = "",
                        onBack = back,
                        onOpenReport = { onNavigate(WebTarget.App(AppRoute.ScoreReport)) },
                        // 成绩单只有本科生的流程，而「是不是本科生」要身份才知道（本端没有那条链）
                        // ⇒ 不画那个入口（成绩单在首页宫格里本来就有入口，见 WebSupport）。
                        onOpenTranscript = null,
                        // 第二、三条上游在**教务**那一站：同一个实例把两个端口都实现了
                        // （与 `:data` 的 `AppJwappScoreSource` 同一条分工）。
                        jwxt = scoreSource,
                        // 落盘缓存不传：浏览器这边没有 `DataCache` 的等价物（不假装有首屏秒开）
                        cache = null,
                    )
                }
            }
        }

        AppRoute.Iclassface -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "快速考勤流水",
                endpoints = "GET /api/iclassface/records",
                why = "人脸识别签到走 `iclassface` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏与 App / 桌面**同一个屏、同三格**（时刻 / 地点 / 类型）：姓名与学号是红线、" +
                        "契约不投影，屏上本来也不画。时间那一格是服务端时区的**墙上时间**（上游原文），" +
                        "本端不做转换 —— 换成本地时区只会整体平移几个钟头。没有分页（上游是一天一枪）。",
                )
                Box(Modifier.weight(1f)) {
                    IclassfaceScreen(
                        source = remember(client, mode) { CampusIclassfaceApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }

        AppRoute.PaymentCode -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "付款码",
                endpoints = "GET /api/pay/code + /api/pay/vouchers + POST /api/pay/vouchers/select",
                why = "付款码借的是校园卡那个站点的会话。",
                section = "§5.7",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏与 App / 桌面**同一个屏、同一张码**：二维码与 Code128 条形码在自己这一端算出来" +
                        "（D12 ①：编码器在 `:core` 的 commonMain，与 Android 那份 zxing 逐模块相同）⇒ 手机上" +
                        "扫出来就是下面那串付款码数字。加餐券的勾选**照旧真能勾**" +
                        "（POST /api/pay/vouchers/select 真把「这一轮用哪几张」推给上游）。" +
                        "⚠️ 这一屏显示的是**能付钱的东西**：`--host 0.0.0.0` 部署要先解决鉴权（§3.3）。",
                )
                Box(Modifier.weight(1f)) {
                    PaymentCodeScreen(
                        source = remember(client, mode) { CampusPayApi(client, API_BASE, mode) },
                        // 勾选的落盘存储：与 Android 同一个存储名、同一批键（wasm 上是 localStorage）。
                        // 上游那一份勾选走 `POST /api/pay/vouchers/select`；本机这一份就是它。
                        selections = remember { PaymentSelectionStore(keyValueStore("payment_code")) },
                        // 这一屏在 `:app` 是个弹窗，"关掉"就是退出；本端的等价物是回日程（与其它屏的返回同一条）。
                        onDismiss = back,
                    )
                }
            }
        }

        AppRoute.Feedback -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "反馈与建议",
                endpoints = "GET /api/feedback/replies + /api/feedback/public-qa + POST /api/feedback/submit",
                why = "反馈没有校园站点会话：凭据由 serve 进程启动时读 FEEDBACK_* 环境变量注入。",
                section = "§5.7",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "三处如实缺口：① 这一族**不套会话** —— 反馈没有学校站点，所谓「登录」是服务端拿" +
                        "`app_id` + `app_secret` 换一张租户令牌，所以本端没有「先登录校园账号」那一步；" +
                        "② 提交要服务端配了那三个 `FEEDBACK_*` 环境变量：没配全时三条端点一律答 502（含提交那一枪），" +
                        "而屏把取数失败吞掉了 ⇒「我提过的 / 大家在问」会显示成「没有新回复」；" +
                        "③ 提交那一发在服务端**真的**往多维表格里加一行，而且工单号是**屏自己生成**的" +
                        "（`FeedbackStore`，本端落在 localStorage ⇒ 换了浏览器就找不回旧工单）。" +
                        "⚠️ 写的那一格只该走回环 + 令牌闸门：对外部署要先加反代 + 鉴权（§3.3）。",
                )
                Box(Modifier.weight(1f)) {
                    FeedbackScreen(
                        source = remember(client, mode) { CampusFeedbackApi(client, API_BASE, mode) },
                        onOpenUrl = { openInNewTab(it) },
                        onBack = back,
                    )
                }
            }
        }

        // 匹配交友：共享屏 + `WebMatchHost`，只读本机缓存；扫码走 `WebMatchScanner`。
        AppRoute.Match -> MatchScreen(
            host = remember { WebMatchHost() },
            onBack = back,
            scanner = remember { WebMatchScanner() },
        )

        AppRoute.WebVpnConverter -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "WebVPN 转换器",
                endpoints = "POST /api/webvpn/convert",
                why = "转换本体要一个对称密码：浏览器只在安全上下文（HTTPS / localhost）里有 crypto.subtle，" +
                    "而 serve 默认明文 HTTP ⇒ 这一族放在服务端（纯函数，不收会话）。",
                section = "§5.7",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "两处如实缺口：① 转换是**一发 HTTP**（服务端那份 `WebVpnUtil` 算的，与 Android / " +
                        "桌面注入的是同一个算法）⇒ 断网时转不了，而 Android / 桌面是本地纯函数；② 转换结果那颗" +
                        "「用 WebVPN 打开」只做浏览器做得到的那一件：新标签打开那个网关地址 —— 网关自己的会话" +
                        "不在这个浏览器里，所以要过它自己的登录页（`:app` 的免验证打开靠的是 App 内的 WebVPN 会话）。" +
                        "这一族**不收会话**，所以本端没有「先登录」那一步。",
                )
                Box(Modifier.weight(1f)) {
                    WebVpnConverterScreen(
                        codec = remember(client, mode) { CampusWebVpnApi(client, API_BASE, mode) },
                        onOpenWithWebVpn = { openInNewTab(it) },
                        onBack = back,
                        onOpenApply = { openInNewTab(com.xjtu.toolbox.webvpn.WebVpnDenied.APPLY_URL) },
                    )
                }
            }
        }

        AppRoute.Community -> CommunityScreen(
            session = session,
            // Web 端只有「粘贴 token」这一条登录路（设备码流程在浏览器里拿不到 device code）；
            // 登录页本身是共享屏，输入框由 supportsManualToken 长出来，:app 那边不会出现。
            deviceAuth = remember(session) { WebGithubDeviceAuth(session) },
            onBack = back,
            onOpenLegacyFeedback = {},
        )

        AppRoute.Accounts -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "账号管理",
                endpoints = "/api/accounts",
                why = "serve 的账号表（与桌面端共用同一份 xjtu_accounts）与「当前是哪个账号」都读自 serve 进程。",
                section = "§5.10",
            )
        } else {
            WebAccountsScreen(client = client, mode = mode, onBack = back)
        }
        // 下载管理：屏是 `:core` 那份 `DownloadManagerScreen`，宿主走契约 §5.13 的 `/api/downloads`
        // —— 队列 / 进度 / 落盘都真的发生在**运行 serve 那台机器**上（会议 #9 = 丙「做法①」）。
        // 三处如实差异（见 `WebDownloadsHost` 的 KDoc）：没有推送（轮询）、目录那一格要等第一次列表、
        // **不画播放按钮**（文件在那台机器上，浏览器打不开它 ⇒ 传 `onPlayTask = null`）。
        AppRoute.DownloadManager -> {
            val host = remember(client, mode) {
                WebDownloadsHost(ServeDownloadsApi(client, API_BASE, mode))
            }
            DownloadManagerScreen(
                host = host,
                onBack = back,
                dirInfo = host.dirInfo,
                // 文件那一组不画：浏览器没有「本机下载目录」这一回事（那边是 serve 那台机器的）
                files = null,
            )
        }

        AppRoute.Settings -> SettingsScreen(
            store = remember { SettingsStore() },
            host = remember(onAppearanceChanged) { WebSettingsHost(onAppearanceChanged, archiveActions) },
            onBack = back,
        )

        // 兜底：清单说能开却没有分支 ⇒ 本端 bug，画指名道姓的页；其余路由显示「没搬过来」。
        else -> if (WebSupport.isOpenable(route)) WebSupportMismatchScreen(route, onNavigate)
        else NotPortedScreen(route, onNavigate)
    }
}

/** 「这一屏还没搬到 Web」的占位页。 */
@Composable
private fun NotPortedScreen(route: AppRoute, onNavigate: (WebTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text("这一屏还没搬到 Web", color = cs.onSurface, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("AppRoute.${route::class.simpleName ?: "?"}", color = cs.primary, fontSize = 13.sp)
        Text("路由 id：${route.id}", color = cs.onBackgroundVariant, fontSize = 12.sp)
        Text(
            if (route.loginType != null) "它还需要 ${route.loginType} 的站点会话（浏览器没有 CAS 会话）。"
            else "它的取数还在 :app（okhttp / Context / Room / BLE 那一批）。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        Text(
            "Web 只渲染 :core/commonMain 里的屏：搬一屏 = 屏 + 模型 + 取数端口进 :core，两端各自注入取数。" +
                "已搬过来的可以在下面底栏或 ?route= 里直接看。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        TextButton(text = "回日程", onClick = { onNavigate(WebTarget.App(AppRoute.Schedule)) }, minWidth = 96.dp)
    }
}
