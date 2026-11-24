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
import com.xjtu.toolbox.feedback.FeedbackScreen
import com.xjtu.toolbox.core.net.CampusPayApi
import com.xjtu.toolbox.core.net.CampusWebVpnApi
import com.xjtu.toolbox.core.net.CampusJwxtScheduleApi
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
import com.xjtu.toolbox.core.net.CampusSchoolCalendarApi
import com.xjtu.toolbox.core.net.CampusYellowPageApi
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.fitness.FitnessScreen
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
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
                                title = "这个页面暂时打不开",
                                why = "请返回首页。（${t.id}）",
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
            WebServeOnlyScreen(title = "小岱")
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
        AppRoute.YellowPage -> YellowPageScreen(
            api = remember(client, mode) { CampusYellowPageApi(client, API_BASE, mode) },
            onBack = back,
            errorText = { FriendlyError.of(it, "加载黄页") },
        )
        AppRoute.CampusBus -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "校车查询")
        } else {
            com.xjtu.toolbox.bus.BusScreen(
                source = remember(client) { com.xjtu.toolbox.bus.CampusBusApi(client, API_BASE) },
                onBack = back,
            )
        }
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

        // ── 只在 serve 形态有的四域：非 serve 形态画 `WebServeOnlyScreen` ──
        AppRoute.Transcript -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "电子成绩单")
        } else {
            // serve 那边两段式：submit 建申请单（真会在教务留待审批记录）→ preview 签章取票 → download 换 PDF
            Column(Modifier.fillMaxSize()) {
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
            WebServeOnlyScreen(title = "考勤")
        } else {
            // 契约不投影流水里的上课教师名与本人姓名（隐私红线）；请假不支持附件
            Column(Modifier.fillMaxSize()) {
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
            WebServeOnlyScreen(title = "加餐券")
        } else {
            // 券封面图要带 egc 的 Authorization，契约没有图片代理 ⇒ 券卡退化成图标
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    CouponScreen(
                        source = remember(client, mode) { CampusCouponApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }


        // ── 第二批只读域：非 serve 形态画 `WebServeOnlyScreen` ── ⚠️ 必须写 `is AppRoute.Lms`。
        is AppRoute.Lms -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "思源学堂")
        } else {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            // 契约不投影教师姓名、附件地址、回放地址与提交记录；详情正文按纯文本近似显示
            Column(Modifier.fillMaxSize()) {
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
                        // 「去提交作业」：学堂官方 h5 提交页，同标签页打开（浏览器里有统一认证会话就直通，
                        // 没有就在那一页按统一认证登一次 —— 与「等一个平台」的取舍一致）。
                        onOpenUrl = { url -> navigateSameTab(url) },
                    )
                }
            }
        }

        AppRoute.Jiaocai -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "教材查询")
        } else {
            // 检索只有关键词、没有翻页；页图与封面逐张同源 fetch（见 `WebJiaocai1Source`）
            Column(Modifier.fillMaxSize()) {
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
            WebServeOnlyScreen(title = "教材全文")
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
            WebServeOnlyScreen(title = "教材全文阅读")
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
            WebServeOnlyScreen(title = "成绩")
        } else {
            // 移动教务粗分 + 教务精确成绩两份拼；后者拿不到时只出前一份。未评教名单不调
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    val scoreSource = remember(client, mode) { CampusJwappScoreApi(client, API_BASE, mode) }
                    JwappScoreScreen(
                        source = scoreSource,
                        onBack = back,
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

        AppRoute.DegreeProgress -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "学业进度")
        } else {
            val degreeSource = remember(client, mode) { com.xjtu.toolbox.core.net.CampusDegreeApi(client, API_BASE, mode) }
            com.xjtu.toolbox.degree.DegreeScreen(source = degreeSource, onBack = back)
        }

        AppRoute.Iclassface -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "快速考勤流水")
        } else {
            // 时间是服务端时区的墙上时间（上游原文），不做时区转换
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    IclassfaceScreen(
                        source = remember(client, mode) { CampusIclassfaceApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }

        AppRoute.PaymentCode -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "付款码")
        } else {
            // ⚠️ 能付钱的东西：`--host 0.0.0.0` 部署要先解决鉴权（用 --https 或反代）
            Column(Modifier.fillMaxSize()) {
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

        // 匹配交友：共享屏 + `WebMatchHost`，只读本机缓存；扫码走 `WebMatchScanner`。
        AppRoute.Match -> MatchScreen(
            host = remember { WebMatchHost() },
            onBack = back,
            scanner = remember { WebMatchScanner() },
        )

        AppRoute.WebVpnConverter -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "WebVPN 转换器")
        } else {
            // 转换在服务端算（浏览器只在安全上下文里有 crypto.subtle）；「用 WebVPN 打开」要过网关自己的登录页
            Column(Modifier.fillMaxSize()) {
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
            onOpenFeedback = if (mode == ApiMode.SERVE) {
                { onNavigate(WebTarget.App(AppRoute.Feedback)) }
            } else null,
        )

        // 反馈凭据在 serve 进程（FEEDBACK_* 环境变量），campus-api 形态没有这一屏
        AppRoute.Feedback -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "反馈与投票")
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote("反馈记录保存在当前浏览器，清除浏览器数据后将无法查看。")
                Box(Modifier.weight(1f)) {
                    FeedbackScreen(
                        source = remember(client, mode) { CampusFeedbackApi(client, API_BASE, mode) },
                        onOpenUrl = { openInNewTab(it) },
                        onBack = back,
                        onOpenCommunity = { onNavigate(WebTarget.App(AppRoute.Community)) },
                    )
                }
            }
        }

        is AppRoute.CommunityPost -> CommunityScreen(
            session = session,
            deviceAuth = remember(session) { WebGithubDeviceAuth(session) },
            onBack = back,
            discussionNumber = route.number,
        )

        AppRoute.Accounts -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(title = "账号管理")
        } else {
            WebAccountsScreen(client = client, mode = mode, onBack = back)
        }
        // 下载管理：屏是 `:core` 那份 `DownloadManagerScreen`，宿主走 `/api/downloads`
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
        else NotPortedScreen(onNavigate)
    }
}

/** 网页版还没有的那一屏。 */
@Composable
private fun NotPortedScreen(onNavigate: (WebTarget) -> Unit) {
    WebUnavailableScreen(title = "网页版暂不支持此功能", why = "请在手机上使用。") {
        Button(
            onClick = { onNavigate(WebTarget.App(AppRoute.Schedule)) },
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) { Text("返回", color = MiuixTheme.colorScheme.onPrimary) }
    }
}
