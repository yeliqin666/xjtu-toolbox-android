package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.xjtu.toolbox.core.net.CampusVenueApi
import com.xjtu.toolbox.core.net.CampusEmptyRoomApi
import com.xjtu.toolbox.core.net.CampusLibraryApi
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.iclassface.IclassfaceScreen
import com.xjtu.toolbox.jiaocai.TextbookScreen
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.lms.LmsScreen
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
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Web 外壳：**共享壳 + `:core` 的屏**。
 *
 * ## 阶段 C 改了什么：外壳从"自制的 Column + 底栏"换成 `:core` 的 `AppShell`
 *
 * 搬迁前这一层是 `Column { Box(weight) { when(target) … } ; WebBottomBar(九格) }` —— 底栏的格子
 * 是"web 能做的功能"凑出来的（课表 / 校历 / 体测 / 成绩 / 黄页 / GPA2048 / 方块 / 游戏 / 社区），
 * 与 App 的五格（首页 / 日程 / 屁岱 / 学辅 / 我的）**没有一格对得上**。这正是用户说的
 * 「web 组织页面的层级逻辑和 app 简直天差地别」。
 *
 * 现在这一层只交三样，其余全在共享壳里（与桌面 `ToolboxDesktopApp` 同一分工）：
 *
 * | 交给壳的 | 这一端 | 壳负责的 |
 * |---|---|---|
 * | 五格（[webTabs]，与 `:app` 的 `BottomTab` 逐字一致） | `WebShell.kt` | 底栏 / 侧栏怎么画、宽窗怎么自适应（[LocalIsWideLayout] 与 `:app`、桌面同一套判据） |
 * | 当前页（[WebTarget] ↔ [ShellTarget]，见 `WebShell.kt`） | 这里 | 「当前页落在哪一格上」⇒ 哪一格高亮（落不上就不高亮） |
 * | 「留下谁」那一档 | `ShellPageHost.CURRENT_ONLY` | 只画当前这一页（这一端的导航模型是路由表，不是 `:app` 那五个常驻的 tab 页） |
 *
 * `glassStyle = false`：这一端没有"界面风格"这一项（设置页里那一行也不画，见 `WebSettingsHost`），
 * 搬迁前画的也是 miuix 经典底栏 —— 与桌面同一条口径。`contentWindowInsets = WindowInsets(0)` /
 * `bottomInset = 0.dp`：浏览器里没有系统栏与小白条。
 *
 * ## 每一格的归属（阶段 C 的实质：格子与 `:app` 对齐，归属如实）
 *
 * | 格 | 落点 |
 * |---|---|
 * | 首页 | **共享屏** `:core` 的 `HomeScreen` + 本端 `WebHomeHost`（真算的：问候 / 教学周 / 下一项安排 / 待办那一格；宫格按「本端真能打开的路由」筛）—— 它是这一端**所有功能屏的入口**，上一轮把这一格留成降级页，于是那些屏的入口全被关上了 |
 * | 日程 | **共享屏** `:core` 的 `ScheduleScreen` + `WebScheduleHost`（本端真接上的唯一一格，见那个类的 KDoc 与页顶那一行说明） |
 * | 屁岱 | **降级页**：`:core` 的 `AgentScreen` 要 `AgentHost`（配置 / 会话 / 记忆三份落盘、SSE 传输、工具层），本端一件都没有 |
 * | 学辅 | 本端自己的**可点目录**（`WebServicesScreen`）：`:app` 那格是学业服务目录（宫格 + 每项此刻的状态摘要），本端没有"状态摘要"那半 ⇒ 换成同一份目录的可点清单 + 会话入口，**不是**一张纯文字说明 |
 * | 我的 | **共享屏** `:core` 的 `SettingsScreen` + `WebSettingsHost`（本端没有账号这回事，所以落设置屏而不是账号页） |
 *
 * ## 没动的那几段（搬迁前刚修好的用户体验）
 *
 * `?token=<一次性令牌>` 的处理、`?route=` 的语义（`probe` / `eula` / `serve` 三个伪路由 +
 * `:core` 的 `appRouteOf`）、后端探测（[BACKEND_DETECT_TIMEOUT_MS] 超时按 campus-api）、
 * 以及 **serve 模式下"过不了闸门就落到会话屏"** 那一段：一处未改。会话屏现在用壳级
 * `ShellTarget.Custom("serve")` 表达（它不进底栏，理由见 `WebShell.kt`）。
 *
 * @param onAppearanceChanged 外观（深色模式）改完之后戳一下**主题那一层** ——
 *   主题在 `Main.kt` 上套（与 `:app` 在 `MainActivity` 套同一条口径：壳不包主题），
 *   键值缝没有监听器，所以由设置屏回调过来让外壳重新读一次。
 */
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

    LaunchedEffect(Unit) {
        // `?token=<令牌>`：**一次性入口**（今天唯一能从地址栏进来的东西）。贴进内存持有者、
        // 立刻把地址栏抹干净（免得它进历史/截图），下面那一发 `sessionState()` 顺手换成 cookie。
        browserSearchParam("token")?.let {
            serveSession.setToken(it)
            browserStripTokenParam()
        }
        // 探测只是**猜**：猜不出来（谁都没起、或卡住）就走老路 campus-api（默认形态一字不改）
        mode = withTimeoutOrNull(BACKEND_DETECT_TIMEOUT_MS) { detectApiMode(client) } ?: ApiMode.CAMPUS_API
        // serve 模式下多一事：**过不了闸门就别硬画课表**。
        //
        // 这一发 `GET /api/session` 就是「令牌换 cookie」那一步（§5.1 第 1 条），失败只有两种：
        // 手里没令牌 / 令牌不对 —— 两种的下一步都一样：把用户**直接落到「会话」屏**去贴。
        // （实测过的坑：不落这一步的话，用户打开页面看到的是课表屏上那句「term 缺失：code=401…」，
        // 而贴令牌的入口藏在底栏多出来的那一格里 —— 没人会去猜。）
        //
        // 壳级入口：会话屏**不在五格里**（它进任何一格都会让"五格与 :app 逐字一致"失效），
        // 它是一条 `ShellTarget.Custom("serve")`（见 WebShell.kt）。
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
    CompositionLocalProvider(LocalIsWideLayout provides calculateIsWideLayout()) {
        AppShell(
            nav = navState,
            // 这一端的导航模型是**路由表**（`:core` 的路由里多数不属于底栏任何一格）⇒ 只画当前这一页，
            // 不做「进过的 tab 常驻」（那是 `:app` 五格的形态，见 ShellPageHost）。
            pageHost = ShellPageHost.CURRENT_ONLY,
            glassStyle = false,
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
                            onAppearanceChanged = onAppearanceChanged,
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
                                target = WebTarget.App(AppRoute.Schedule)
                            }

                            WebRoutes.SERVICES -> WebServicesScreen(
                                serveMode = backend == ApiMode.SERVE,
                                onNavigate = { target = it },
                            )

                            WebRoutes.PROFILE -> SettingsScreen(
                                store = remember { SettingsStore() },
                                host = remember(onAppearanceChanged) { WebSettingsHost(onAppearanceChanged) },
                                // 本端没有返回栈（底栏就是导航）⇒ 返回回日程，与"默认落点"同一条口径。
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
    }
}

/**
 * 探测后端最多等多久。超时按 campus-api 处理（= 默认形态）：一个卡住的探测不该让页面白屏。
 *
 * 3 秒是「本地回环的一次 HTTP 往返」的宽松上限（同机的 `:server` / campus-api 都在毫秒量级）。
 */
private const val BACKEND_DETECT_TIMEOUT_MS = 3_000L

/**
 * `?route=<id>`：先认 Web 专有的三个（`probe` / `eula` / `serve`），再交给 `:core` 的 [appRouteOf]；
 * 认不出（旧深链、手写错）一律落回课表 —— 与 App 的 `appRouteOf` 返回 null 时同一套兜底思路。
 *
 * ⚠️ 这一段（连同 `?token=` 与探测那两段）在阶段 C **一行未改**：底栏换了、伪路由的**写法**换了
 * （现在是 `ShellTarget.Custom`，见 `WebShell.kt`），地址栏进来的语义没变。
 */
internal fun initialWebTarget(): WebTarget = when (val raw = browserRouteParam()) {
    null -> WebTarget.App(AppRoute.Schedule)
    WebRoutes.PROBE -> WebTarget.Probe
    WebRoutes.EULA -> WebTarget.Eula
    // `?route=serve`：serve 模式的会话屏（登录 / 短信二验 / 登出）。非 serve 模式下它也在路由表里
    //（深链不会突然 404），只是底栏不会长出那一格 —— 那一屏不是底栏的一部分，见 WebShell.kt。
    WebRoutes.SERVE -> WebTarget.Serve
    else -> WebTarget.App(appRouteOf(raw) ?: AppRoute.Schedule)
}

/**
 * 一屏共享页（或本端的那几张页）。每个屏只注入**这一端能提供的东西**：
 * - 日程：`:core` 的共享屏 + 本端 `WebScheduleHost`（见那个类的 KDoc：哪几条缝是真的、缺哪几档）；
 * - 我的 / 学辅：见 [WebSettingsHost] 与 [WebServicesScreen]；
 * - 首页：`:core` 的共享屏 + 本端 `WebHomeHost`（见那个类的 KDoc：真算哪几样、如实不列哪几块）；
 * - 屁岱：降级页（见 [WebUnavailableScreen]，写清缺哪一个宿主面）；
 * - 黄页：campus-api 版的 [CampusYellowPageApi]（serve 模式下是同一份类的 `ApiMode.SERVE` 那一支）；
 * - 校历/体测：campus-api 版的 [CampusSchoolCalendarApi] / [CampusFitnessApi]（同一条理由：学校域名不给 CORS 头）；
 * - 只读四域：成绩单 / 考勤 / 加餐券 / 宿舍电费（`:core` 的共享屏 + `:core` 的 `Campus*Api` 取数）。
 *   取数只认 serve 契约（§5.5）⇒ campus-api 形态下画 `WebServeOnlyScreen`；宿舍电费那一屏用的是
 *   本端自己的读数页（`:core` 那张 `DormPowerScreen` 的全部内容是缴费页浏览器，而缴费页入口不端）。
 * - 第二批只读域（2026-10-14 接线，契约 §5.6）：思源学堂 / 教材查询 / 成绩 / 快速考勤流水
 *   （同样：`:core` 的共享屏 + `:core` 的 `Campus*Api`，取数只认 serve 契约 ⇒ campus-api 形态下
 *   落 `WebServeOnlyScreen`）。那一节里哪些格子不画，逐条写在四个分支的页顶说明里。
 * - 取数：`mode` 决定 baseUrl 与解析形状（[ApiMode]），21 个 `Campus*Api` 各按自己的契约跑；
 * - 社区：登录态与设备码登录在 Web 上如实报「未配置」（见 [WebGithubSession]）；
 * - 错误文案统一用 `:core` 的 [FriendlyError] —— 与 App 字句相同。
 */
@Composable
private fun AppPage(
    route: AppRoute,
    client: HttpClient,
    /**
     * 这一份 `:web` 在对谁说话（见 [ApiMode]）：21 个 `Campus*Api` 都按它选解析形状与基址。
     * 由外壳探测一次后传下来 —— 屏自己不去猜后端（那是装配层的知识）。
     */
    mode: ApiMode,
    session: WebGithubSession,
    /** 壳算给当前页的留白（经典 / 侧栏形态下都是 0，见 `ShellPageInsets`）。 */
    insets: ShellPageInsets,
    /** 外观改完之后戳一下主题那一层（只有设置屏用得上）。 */
    onAppearanceChanged: () -> Unit,
    onNavigate: (WebTarget) -> Unit,
) {
    val back = { onNavigate(WebTarget.App(AppRoute.Schedule)) }
    when (route) {
        // 日程：与 Android / 桌面**同一个屏**（`:core` 的 `ScheduleScreen`），本端只提供宿主面
        //（`WebScheduleHost`：键值缝 + 教务取数 + 那份如实的能力清单），并把它残余的缺口写在页顶
        //（排课原样那一半，与 campus-api 形态下缺的那两栏，见 `WebScheduleSource` 的 KDoc）。
        //
        // 页内自己要去别的功能页（教材全文 / 课程回放 / 考勤那些下钻）：能走到的时候按 `WebTarget`
        // 转回来；本端没接的那两档（`LMS_LINK` / `ATTENDANCE`）不列进 honored，所以屏上不会出现。
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
                            is AppRoute.Browser -> if (r.url.isNotBlank()) navigateSameTab(r.url)
                            else -> onNavigate(WebTarget.App(r))
                        }
                    },
                )
            }
        }

        // 首页：与 Android / 桌面**同一个屏**（`:core` 的 `HomeScreen`），本端只提供宿主面
        //（`WebHomeHost`：课表缓存 / 节假日 / 本端真能打开的路由表 / 收纳那一格），它缺哪几块
        // 如实写在那个类的 KDoc 里（`BULLETIN_BAR` / `GLANCE` / `QUICK_ACTIONS` 一条都不列）。
        //
        // 它同时是这一端**所有功能屏的入口**：宫格列出的每一格都由 `WebSupport.openableRouteIds`
        // 筛过、点击走 [onNavigate] —— 上一轮的漏正是这里（门在首页宫格里，而首页当时是降级页）。
        AppRoute.Main -> WebHomeScreen(
            client = client,
            mode = mode,
            insets = insets,
            onNavigate = onNavigate,
        )

        // 屁岱：`:core` 的 `AgentScreen` 要 `AgentHost`（配置 / 会话 / 记忆三份落盘、SSE 传输、
        // 工具层那一堆）—— 本端一件都没有。如实说：浏览器里也没有那几样能落盘的地方，
        // 模型调用与工具取数都得在服务端有对应端点（今天没有）。
        AppRoute.Agent -> WebUnavailableScreen(
            title = "屁岱（Web 这一端）",
            why = "App 与桌面的屁岱是 `:core` 的 `AgentScreen`，它要一份 `AgentHost`：" +
                "配置 / 会话 / 记忆三份落盘、一条流式传输、以及工具层那几个取数口；" +
                "Web 这一端一件都没接（服务端契约里也没有对应的端点）。",
            missing = listOf(
                "`AgentHost`：模型配置与 API Key 的持有（本端没有可落盘的凭据位）",
                "会话与记忆的落盘（本端只有 localStorage，且语义与 `AgentSessionPersistence` 对不上）",
                "流式传输（`:data` 那份 SSE 客户端）",
                "工具层（黄页 / 教师检索 / 课表那几个口）",
            ),
        )

        // 内置浏览器：**Web 端的浏览器就是浏览器本身** —— 把 URL 交给它，同标签导航过去。
        //
        // :app 的 BrowserScreen 是个 WebView，它比普通浏览器多两件事：① 复用 App 已经登好的
        // 站点会话（WebVPN / 校园卡那类），② `then=` 参数在登录完成后自动跳下一站（付款页先过登录）。
        // 这两件在浏览器里都做不到（会话在 campus-api 的进程里，不在页面上），所以这里**只做**
        // 浏览器做得到的那件：导航到那个网址，要登录就由站点自己的登录页处理。
        // 这不算「许诺一个做不到的模式」—— 它本来就是浏览器打开一个网址。
        //
        // 同标签（navigateSameTab）而不是新标签：深链进页时没有用户手势，`window.open` 会被
        // 弹窗拦截器拦下、用户看到「什么都没发生」。回来靠浏览器后退键。
        //
        // ⚠️ 必须写 `is`：`AppRoute.Browser` 的两个参数都有默认值，裸写它会被当成
        // 「构造一个 Browser("", "") 再比相等」，既不匹配真实深链也不会智能转换类型。
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
        // 体测：与 Android 同一个屏、同一套模型与分项口径（:core/fitness），只换取数——
        // campus-api 按隐私口径不返回姓名/学号，所以英雄卡标题会落到兜底文案（已写在 FitnessSource 的 KDoc）。
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
        // 游戏合集：与 Android 同一个屏（:core/game/GamesScreen），战绩读同一份 GameStore
        // （Web 走 localStorage，Android 走 SharedPreferences）。合集里没搬过来的那几条
        // （合成西交大 = WebView）点进去会落到 NotPortedScreen —— 不冒充。
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
        // 象棋：与 Android 同一个屏与引擎（:core/game/xiangqi）。规则引擎原来那 6 个
        // `.java` 翻成了 Kotlin（Java 编不了 commonMain），行为由原来那 3 个单测钉住。
        // 同样没有联机那一格（浏览器没有 BLE ⇒ onlineLobby 传 null）。
        AppRoute.GameXiangqi -> XiangqiScreen(onBack = back)
        // 跳一跳：与 Android 同一个屏（:core/game/hop）。9 张地标图按端注入：Android 用
        // R.drawable、Web 用 :web 的 composeResources（同一份 webp 字节，见 WebHopLandmarks.kt）。
        AppRoute.GameHop -> HopScreen(
            onBack = back,
            landmarkImages = rememberWebHopLandmarkImages(),
        )
        // 通知公告：与 Android 同一个屏与模型（:core/notification），取数换成 campus-api ——
        // 它覆盖同样 29 个源，两端拿到的是同一批通知（差别只在「谁去爬」）。
        AppRoute.Notification -> NotificationScreen(
            source = remember(client, mode) { CampusNoticeApi(client, API_BASE, mode) },
            onBack = back,
            onNavigate = { onNavigate(WebTarget.App(it)) },
        )
        // 教师主页检索：与 Android 同一个屏与模型（:core/faculty），取数换成 campus-api 的
        // `/api/info/faculty`（免登录、同一个上游 advancesearch.jsp）。三处刻意降级写在
        // CampusFacultyApi 的 KDoc 里：筛选项表拿不到、主页不解析（改为新标签打开）、联系方式不取。
        AppRoute.Faculty -> FacultyScreen(
            source = remember(client, mode) { CampusFacultyApi(client, API_BASE, mode) },
            onBack = back,
            onOpenUrl = { openInNewTab(it) },
        )
        // 全校课表：与 Android 同一个屏与模型（:core/schedule）。campus-api 的投影**少一批字段**
        // （人数/学时、YPSJDD、开课单位与公选筛选），逐条写在 CampusSchoolCourseApi 的 KDoc 里；
        // 屏据能力开关把筛不了的那两档控件整个隐藏，人数/学时那几块不画 —— 不拿 0 冒充。
        AppRoute.SchoolCourse -> SchoolCourseScreen(
            source = remember(client, mode) { CampusSchoolCourseApi(client, API_BASE, mode) },
            onBack = back,
        )
        // 评教：与 Android 同一份屏与 ViewModel（:core/judge），取数换成 campus-api。
        // **只读** —— campus-api 永不实现提交/撤销评教，所以屏上不出现「一键全部好评」
        // 与撤回按钮（JudgeSource.canSubmit=false）；能看「哪些课还没评」。
        AppRoute.Judge -> WebJudgeScreen(client, mode, onBack = back)
        // 消息收纳：与 Android 同一个屏与 store/rules（:core/inbox），取数换成 campus-api 的
        // `/api/inbox`（同样四路）。点击行为：外链（`browser?url=`）新标签打开 —— 与 :app 的
        // 内置浏览器同义；其余按 AppRoute 走站内导航。
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
        // 空闲教室：与 Android 同一个屏与 ViewModel（:core/emptyroom），只换取数 —— campus-api 只有
        // CDN 那一档（`/api/emptyroom/cdn` 一次给全校区当天全部教室），所以 CampusEmptyRoomApi 的
        // availableSources 只报 CDN，屏上「实时状态」「直查教务」两项整个不出现（见那个类的 KDoc）。
        // Web 没有身份这回事 ⇒ accountType = null（研究生那条限制本来也走不到）。
        // 「CDN 查询说明读没读过」在 Web 存 localStorage（:app 存 CredentialStore），键名与 :app 同一个。
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
        // 校园卡：与 Android 同一个屏与 ViewModel（:core/card），只换取数 —— campus-api 的
        // `/api/card/balance` 与 `/api/card/transactions`（逐字段映射写在 CampusCardNetApi 的 KDoc 里，
        // 尤其 `amount ← signed`、`description ← channel`、`cardType ← cardName`）。
        // 四处**如实降级**（都写在 CampusCardScreen / CampusCardNetApi 的 KDoc 里）：
        //  ① 没有落盘缓存 —— 只有本次会话内存里那一份，刷新页面就没有首屏秒开（不造假缓存）；
        //  ② 没有首页 tab 的缓存版本号（onCacheUpdated 空）；③ 没有桌面小组件（onBalanceChanged 空）；
        //  ④ 没有「加餐券」那一屏 ⇒ 那条入口整个不画（couponStat = null，不是画一条点了会落到占位页的）。
        // 浏览器里也没有 SavedStateRegistryOwner ⇒ savedState 用屏的默认值（内存版 SavedStateHandle）。
        AppRoute.CampusCard -> CampusCardScreen(
            source = remember(client, mode) { CampusCardNetApi(client, API_BASE, mode) },
            onBack = back,
        )
        // 体育场馆：与 Android 同一个屏与 ViewModel（`:core/venue`）。两种形态（见 CampusVenueApi 的 KDoc）：
        // ① campus-api 那条路自己写着 `readOnly:true`（抢场要解滑块且属写操作）⇒ canBook/canCancel
        //    都是 false，屏上**没有**「确认预订」「去支付」「取消订单」，时段格子一律不可选；
        // ② serve 模式下写端点已落地（契约 §5.3.2：`/api/venue/captcha` + `/book` + `/cancel`）
        //    ⇒ canBook/canCancel 翻 true，于是屏上真能下单 —— **滑块在本端画**：服务端把挑战
        //    （图 + captchaId）按值端出来，`WebSlideCaptchaHost` 用 `:core` 的图片缝解码、按与
        //    Android/桌面同一协议采集拖动轨迹，`POST /api/venue/book` 把 captchaId + 轨迹原文带回。
        //    ⚠️ 「去支付」那一跳仍不可达（浏览器里没有场馆站的会话，支付页地址也只有 `:data` 知道）
        //    ⇒ CampusVenueApi.paymentUrl 如实给空串，见契约 §5.3.2 末尾的缺口说明。
        // 逐字段映射写在 CampusVenueApi 的 KDoc 里（订单缺 areaName/serviceName、明细时间常为 null
        // ⇒ 如实留空，屏上那几段不画）。
        // **自动识别**（`solveCaptcha`）在浏览器里没有：`:core` 的 `decodeImagePixels` 在 wasm 上恒 null，
        // 本端也不传 autoSolveCaptcha（默认 false）⇒ 停在手动滑动那一档。
        AppRoute.Venue -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            VenueScreen(
                source = remember(client, mode) { CampusVenueApi(client, API_BASE, mode) },
                onBack = back,
                // 支付那半在两种形态下都不可达（campus-api：canBook=false ⇒ 屏上不画入口；serve：
                // canBook=true 但支付页地址只有 `:data` 能拼、而且浏览器里没有场馆站的会话 ⇒
                // `CampusVenueApi.paymentUrl` 如实给空串，这一枪什么都不做）。真走到这里也只做浏览器
                // 做得到的那一件：打开那个网址；`then`（先过 App 的登录再跳下一站）在浏览器里做不到，
                // 因为会话在 serve / campus-api 进程里而不在页面上 —— 如实忽略，不假装。
                onOpenBrowser = { url, _ -> if (url.isNotBlank()) openInNewTab(url) },
                // 滑块宿主槽位（见上面那段注：serve 模式下下单要它；campus-api 的 canBook=false
                // ⇒ 验证码弹窗根本不出现）。宿主端口见 `:core` 的 `SlideCaptchaHost`。
                captchaHost = remember { WebSlideCaptchaHost },
                // 「功能说明弹过了没」在 Web 存 localStorage（:app 存 feature_hints 那个 SharedPreferences），
                // 键名与 :app 同一个；文案里那两条只在能下单那端成立的话，屏会自己按 canBook 少说。
                showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("venue_hint_shown", true) },
            )
        }
        // 图书馆座位：与 Android 同一个屏与 ViewModel（`:core/library`）。两种形态（见 CampusLibraryApi 的
        // KDoc）：campus-api 只读（写方法一律抛）；serve 模式的 `/api/library/action` 已落地（契约 §5.3.1）
        // ⇒ canBook 随服务端如实报的 `canBook:true` 翻 true —— 座位格可点、有预约/换座/签到/离开/返回/退座
        // 按钮、能切校区。`hasSeatPlan` 两个形态都是 false（§5 的 library 没有布局/底图端点）⇒ 平面图那一档
        // 整个不画（默认视图落到列表），扫码确认框不出现（那条入口只在 App 上）。逐字段映射写在
        // CampusLibraryApi 的 KDoc 里；四个宿主槽位全传空：浏览器里没有 App 的凭据可重登、没有屏幕方向、
        // 没有提醒/首页信号/收纳。
        AppRoute.Library -> {
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LibraryScreen(
                source = remember(client, mode) { CampusLibraryApi(client, API_BASE, mode) },
                onBack = back,
                showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("library_hint_shown", true) },
            )
        }

        // ── 只读四域（2026-10-13 接线，契约 §5.5）──────────────────────────────
        //
        // 这四屏的屏与模型都在 `:core`，取数是 `:core` 的四个新 `Campus*Api`（它们只认 serve 契约：
        // `ApiMode.SERVE` 那一支走新端点，campus-api 那一支**不猜、不返回空数据**，直接抛错）。
        // campus-api 形态下这四个域**没有端点**（`:app` / 桌面是走 `:data` 的 okhttp 抓的）⇒
        // 非 serve 形态画的是 `WebServeOnlyScreen`（说清「切到 serve 模式就能用」），
        // 而不是「还没搬到 Web」—— 那是假话（屏就在 `:core`）。
        //
        // 每屏顶部那条 `WebGapNote` 说的是这张屏**拿不到什么**（照 §5.5 逐条，不编）：
        // 成绩单只有「选类型」一步；考勤只有学期 + 流水表；加餐券没有封面图与「领取」；
        // 宿舍电费没有读数时间戳与「去缴费」那一跳。
        AppRoute.Transcript -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "电子成绩单",
                endpoints = "GET /api/dzpz/transcript",
                why = "成绩单走 `dzpz` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏只画得了「选类型」这一步：serve 契约 §5.5 只端了建单表单（GET /api/dzpz/transcript）。" +
                        "学籍联动 / 生成预览 PDF / 两次提交 / 签章 / 取下载链接 / 下载 PDF 六步刻意不投影" +
                        "（第二步的返回值里有学号，其余是写路径）⇒ 出 PDF 那半条链在浏览器里没有端点，" +
                        "那一块不画按钮、只写缺口。要出 PDF 请在 App 或桌面端申请。",
                )
                Box(Modifier.weight(1f)) {
                    // 没有 saveSink：浏览器这一端没有「保存到下载目录 / 下载管理」那一档（也不会有 PDF 可存）
                    TranscriptScreen(
                        source = remember(client, mode) { CampusTranscriptApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }

        AppRoute.Attendance -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "考勤",
                endpoints = "GET /api/attendance/terms + /api/attendance/records",
                why = "考勤走 `new_attendance` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "这一屏只有「学期选择 + 流水表」两样：serve 契约 §5.5 只端了这两个端点。如实不画的三处：" +
                        "① 上课教师名（记录里有这个名字，但 §5.2 的例外清单里没有它 —— 红线）；" +
                        "②「统计」那一栏（getKqtjCurrentWeek / computeCourseStatsFromRecords 没有端点，" +
                        "本端也不拿流水在本机算第二份口径）；③「打卡流水」与「请假」两栏（打卡原始流水没有端点，" +
                        "请假 / 销假是写路径、一律不端）。姓名那一格同理不画（getStudentInfo 不端）。",
                )
                Box(Modifier.weight(1f)) {
                    AttendanceScreen(
                        source = remember(client, mode) { CampusAttendanceApi(client, API_BASE, mode) },
                        onBack = back,
                        // 快速考勤流水那一屏（AppRoute.Iclassface）在本端没有取数（`iclassface` 站点的会话，
                        // 契约里没有它的端点）⇒ 顶栏那个入口不画：传 null 是「本端没有这一档」，
                        // 不是「点了不做事」。
                        onOpenIclassface = null,
                    )
                }
            }
        }

        AppRoute.Coupon -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "加餐券",
                endpoints = "GET /api/coupon/list + /api/coupon/detail",
                why = "加餐券走 `coupon` 站点的会话。",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "两处如实不画：①「领取」按钮 —— 领券（POST /app/voucher/activate）是写路径，" +
                        "serve 契约 §5.5 只端只读两格，那个按钮没有端点可走；② 券封面图 —— 那张图要带 egc 的" +
                        "Authorization 才取得到，浏览器直接打开那个 URL 会拿到登录页（契约里没有图片代理端点）" +
                        "⇒ 券卡退化成图标。金额一格单位是分（屏自己除 100 显示，本端不换算）。",
                )
                Box(Modifier.weight(1f)) {
                    CouponScreen(
                        source = remember(client, mode) { CampusCouponApi(client, API_BASE, mode) },
                        onBack = back,
                    )
                }
            }
        }

        // 宿舍电费：⚠️ 这一屏用的是 `:web` 自己的读数页（[WebDormPowerScreen]），**不是** `:core` 的
        // `DormPowerScreen` —— 后者的全部内容是「缴费页浏览器」（只要 `freshPayUrl()`），而缴费页入口
        // 在 serve 契约里没有端点（§5.5）。契约真端的是 `/api/dorm/rooms` 与 `/api/dorm/reading`，
        // 所以这里按拿得到的两个端点画一份只读读数页（理由与逐条降级写在那个文件的 KDoc 里）。
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

        // ── 第二批只读域（2026-10-14 接线，契约 §5.6）──────────────────────────────
        //
        // 思源学堂 / 教材查询 / 成绩 / 快速考勤流水。四条分支与上面那四屏同一条规矩：屏在 `:core`，
        // 本端只填取数与宿主面；这四族的取数也**只认 serve 契约**（campus-api 里没有这些端点）⇒
        // campus-api 形态下画 `WebServeOnlyScreen`（说清「切到 serve 模式就能用」），而不是
        // 「还没搬到 Web」—— 后者是假话（屏就在 `:core`，而且这一段刚刚接上）。
        //
        // 每屏页顶那条 `WebGapNote` 说的是这张屏**拿不到什么**（照 §5.6 逐条，不编）：
        // 学堂的教师名 / 附件预览与下载 / 播放与回放地址 / 提交记录；教材的**全文阅读**（「本学期我的
        // 教材」那一段 2026-10-14 批①收尾已接上，取数见 `WebJiaocaiTermTextbooks`）；
        // 成绩的分项成绩；签到的姓名与学号（屏本来也不画那两格）。
        // ⚠️ 必须写 `is`：`AppRoute.Lms` 带一个默认参数，裸写它会被当成「构造一个 Lms(null) 再比相等」
        // （与下面浏览器那一条同一条坑）。
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
                        "全文阅读（`jiaocai1` 的页图与阅读器）是另一个域名、另一套会话，本轮没有端点 ⇒ 书架与" +
                        "全文库两栏不画，各处的「读全文 / 阅读全文」入口也不画（拿了书目号只够显示「这本书有本地全文」）。",
                )
                Box(Modifier.weight(1f)) {
                    TextbookScreen(
                        source = remember(client, mode) { CampusJiaocaiApi(client, API_BASE, mode) },
                        // 「本学期我的教材」那一段的宿主端口：serve 形态下走 `GET /api/jwxt/textbooks`
                        // （学号由服务端取，本端不传）—— 见 [WebJiaocaiTermTextbooks] 的 KDoc。
                        termTextbooks = remember(client, mode) {
                            WebJiaocaiTermTextbooks(CampusJwxtScheduleApi(client, API_BASE, mode))
                        },
                        // 全文库那一半（`jiaocai1Source` / `jiaocai1Shelf`）一个都不传 = 本端没有那一档
                        // ⇒ 屏上只留「查教材」一栏，也不画任何阅读全文的入口（见那个参数的 KDoc）。
                        onBack = back,
                        // 走不到：没有全文库那一半时那个入口一个都不画（拿不到的东西不摆入口）
                        onOpenBook = { _, _ -> },
                    )
                }
            }
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
                        // 本端不知道学号（也不去要：学号是红线）⇒ 传空串，VM 因此不调未评教名单与报表
                        // 那两路 —— 那正是契约不端的两路（见 `JwappScoreViewModel.fetch` 里那一行判断）。
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

        // ── 第三批（2026-10-14 接线，契约 §5.7）──────────────────────────────
        //
        // 付款码 / 反馈与建议 / 匹配交友 / WebVPN 转换器。三条分支三种落法：
        //  - 付款码 / 反馈 / WebVPN：屏在 `:core`、取数只认 serve 契约（campus-api 里这三族没有端点）
        //    ⇒ campus-api 形态下画 `WebServeOnlyScreen`；
        //  - 匹配交友：**两种形态下都用不了**（wasm 上没有 deflate / URL-safe Base64 ⇒
        //    `MatchProfile.codecAvailable` 是 false）⇒ 落一张如实说明页 [WebMatchGapScreen]，
        //    不把那张共享屏半开着挂上去。
        // 每屏页顶那条 `WebGapNote` 说的是这一屏**拿不到什么**（照 §5.7 逐条，不编）。
        AppRoute.PaymentCode -> if (mode != ApiMode.SERVE) {
            WebServeOnlyScreen(
                title = "付款码",
                endpoints = "GET /api/pay/code + /api/pay/vouchers",
                why = "付款码借的是校园卡那个站点的会话。",
                section = "§5.7",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "两处如实不画：① 条码 / 二维码的**矩阵** —— 浏览器这一端没有编码器（wasm 的" +
                        "`qrCodeModules` / `code128Modules` 如实返回 null）⇒ 只画付款码**数字**那一档，" +
                        "两个矩阵一个都不画（不是画一张假的）；② 加餐券的**勾选框** —— 勾选会把「这一轮" +
                        "用哪几张」真的推给上游，那是写路径（serve 契约 §5.7 不端）⇒ 那几行券只读。" +
                        "⚠️ 这一屏显示的是**能付钱的东西**：`--host 0.0.0.0` 部署要先解决鉴权（§3.3）。",
                )
                Box(Modifier.weight(1f)) {
                    PaymentCodeScreen(
                        source = remember(client, mode) { CampusPayApi(client, API_BASE, mode) },
                        // 勾选的落盘存储：与 Android 同一个存储名、同一批键（wasm 上是 localStorage）。
                        // 本端是只读那一档（`canSelectVouchers = false`）⇒ 它只会读到一张空表。
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
                endpoints = "GET /api/feedback/replies + /api/feedback/public-qa",
                why = "反馈没有校园站点会话：凭据由 serve 进程启动时读 FEEDBACK_* 环境变量注入。",
                section = "§5.7",
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                WebGapNote(
                    "四处如实缺口：① 提交那一半**没有端点**（写路径不端）⇒ 屏上整块表单不画，只剩" +
                        "「去 GitHub 提 Issue」那条外链；② 工单号来自**屏自己的本地账本**（`:core` 的" +
                        "`FeedbackStore`，本端落在 localStorage），而这一端**不能提交** ⇒ 新浏览器里那份账本" +
                        "是空的 ⇒「我提过的」那一段不出现（只有账本里真有单号的人才看得到回复）；" +
                        "③ 读这一半要服务端配了那三个 `FEEDBACK_*` 环境变量：没配全时两条端点答 502，" +
                        "而屏把取数失败吞掉了 ⇒ 页面会显示成「没有新回复 / 没有问答」；④ 这一族**不套会话**，" +
                        "所以本端没有「先登录校园账号」那一步。",
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

        // 匹配交友：这一屏在浏览器里**整屏用不了** —— 它的全部动作都是「把自己的档案压成一段码」与
        // 「解朋友的码」，而那两步要 deflate 与 URL-safe Base64（wasm 上两个 actual 都如实返回 null）
        // ⇒ `MatchProfile.codecAvailable` 是 false。学工档案那一半服务端也没有端点（hello 那条链只在
        // `:app`）。所以这里画的是**如实说明页**，不是半开着的交友界面（见 [WebMatchGapScreen]）。
        AppRoute.Match -> WebMatchGapScreen(onNavigate)

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

        // 设置（`AppRoute.Settings`）：与 Android / 桌面**同一个屏**（`:core` 的 `SettingsScreen`）。
        // 本端只会从 `?route=settings` 深链走到这里（底栏第五格是"我的"，落在同一个屏上）——
        // 两条路都给同一份 store 与 host，改设置的行为完全一样。
        AppRoute.Settings -> SettingsScreen(
            store = remember { SettingsStore() },
            host = remember(onAppearanceChanged) { WebSettingsHost(onAppearanceChanged) },
            onBack = back,
        )

        // 兜底。两种可能，含义完全不同，所以分开说：
        //  - 清单（`WebSupport.openableRouteIds`）说这一条能打开、而这里**没有它的分支** ⇒ 那是
        //    **这一端的 bug**（门开着、里面没屏）⇒ 画一张指名道姓的页（叫上要改的那两处），
        //    而不是落「还没搬到 Web」—— 那会把自家漏了的屏说成"本来就搬不过来"；
        //  - 其余路线的屏还在 :app：**如实说「没搬过来」，不拿课表冒充**（以前这里落回 ScheduleScreen，
        //    深链 `?route=notification` 会静默显示课表 —— 看着像 bug，也让人分不清「没实现」与「坏了」）。
        else -> if (WebSupport.isOpenable(route)) WebSupportMismatchScreen(route, onNavigate)
        else NotPortedScreen(route, onNavigate)
    }
}

/**
 * “这一屏还没搬到 Web”的占位页 —— 只给 `:core` 里尚未存在的 AppRoute 用。
 *
 * 为什么要有它：Web 只能渲染 `:core/commonMain` 里的屏；`:app` 那 40 条路由里还有一大批
 * （成绩单 / 我的 / 校园网登录 …）因为取数挂着
 * okhttp、Context、Room、BLE 而暂时搬不过来。这页把**具体哪一条没搬**、**它需不需要登录**
 * 直接写在脸上，而不是落回课表假装能用 —— 交接文档里那条「只信工作区」的验收，看的就是这个。
 */
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
