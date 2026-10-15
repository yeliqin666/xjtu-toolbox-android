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
import com.xjtu.toolbox.dormpower.DormPowerApi
import com.xjtu.toolbox.dormpower.DormPowerScreen
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
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

/**
 * 桌面外壳（窗口模式 · Stage A 第二步）。
 *
 * ## 它现在是什么
 *
 * 一层很薄的壳，只做四件事：
 *  1. 没登录 → [LoginScreen]（`:core` 的共享登录屏），登录状态由 [DesktopAuth] 持有；
 *  2. 已登录 → **共享壳**（`:core` 的 [AppShell]：底栏 / 侧栏 + 当前页）；
 *  3. 全程挂着 MFA 弹窗宿主（登录本身就可能要短信二验，登录页上也一样要）；
 *  4. 「全部页面」那张索引页如实列出**哪些路由真能用、哪些还没搬**。
 *

 * ## 外壳是共享壳（Stage B 的主角）
 *
 * 「底栏长什么样、画在哪、当前页怎么摆、宽窄怎么自适应」都不在这一端了 —— 它们在 `:core` 的
 * [AppShell] 里，与 `:app`（Android）是**同一份代码**。这一端只交三样：
 *
 *  1. 六格（[desktopTabs]，顺序 / 文字 / 图标与搬迁前那份 `DESKTOP_TABS` 逐字相同）；
 *  2. 当前页（[DesktopTarget] ↔ [ShellTarget] 的换算，见 `DesktopShell.kt`）；
 *  3. 「留下谁」那一档（[ShellPageHost.CURRENT_ONLY]：这一端的导航模型是**路由表**，
 *     43 条里 37 条不属于任何一格，逐条常驻既没意义、又会一直涨）。
 *
 * 于是「非 tab 路由不高亮任何一格」这件事不用这一端写一行判据：它是 `:core` 的
 * [ShellNavState.selected]（`target` 落在哪一格上就高亮哪一格，落不上就不高亮）。
 *
 * 窗口何谓「宽」也由壳判：这一端只把窗口形状算好喂下去（`LocalIsWideLayout`，
 * 判据就是 `:core` 那条 `calculateIsWideLayout` —— 与 Android 在宽屏 / 折叠屏上同一套），
 * 窗口够宽时底栏自动变成侧栏。
 * 数据全来自 `:data`（会话内核 + `LibraryApi`），**没有任何我方的服务器**（设计文档 C2）。
 *
 * ## 与 `:web` 的外壳是什么关系
 *
 * 同一批屏、同一套路由表（`AppRoute`），差别只有两处，两处都是**平台决定的**：
 *
 * | | `:web` | `:desktop` |
 * |---|---|---|
 * | 数据从哪来 | 同源反代到 campus-api（浏览器只能这样） | 进程内直取 `:data`（自己登录、自己取数） |
 * | 外链怎么开 | `window.open` / `location.assign` | `java.awt.Desktop.browse` |
 *
 * ## 底栏那几格，只放这一端**真能画**的东西（诚实的一条）
 *
 * 底栏那六格（见 [desktopTabs]）都是这一端真能画的屏：`:core` 里有屏 + 取数在 `:data`。
 * 第一格是**首页**（`AppRoute.Main` → `:core` 的 `HomeScreen` + 本端的 `DesktopHomeScreen` 宿主面）：
 * 它与 `:app` 的「Tab 1」是同一份屏，只是这一端没有玻璃顶栏，消息那一格画在首页里。
 * 「全部」那一页里的「真能用」就是这张表（[DESKTOP_SUPPORTED_ROUTES]）；17 条真数据路由
 * 搬完之后 [DESKTOP_PENDING_ROUTES] **空了** —— 「屏在 `:core` 而取数还在 `:app`」那条路由
 * 一条不剩。索引页上「还没有数据源」那一栏照旧画出来（数字是进度，不是装饰），
 * 所以那一行现在是 0。
 *
 * 屏上的「返回」箭头一律回图书馆首页：这一端没有返回栈（底栏就是导航），与 Stage 0 那条
 * 「返回 = 回默认页」同一条口径。
 *
 * ## 公告（这一轮点亮的宿主面）
 *
 * 公告这条链现在四层全在共享层：模型与规则（`:core` 的 `bulletin/Bulletin.kt`）、本地状态
 * （`:core` 的 `BulletinStore`，键值缝）、「现在该看哪些 / 关掉一条意味着什么」
 * （`:core` 的 `BulletinFeed`，本轮从 `:app` 的 `LaunchNotices` 搬过去的那一半）、取数
 * （`:data` 的 `BulletinApi`，UA 的版本号由宿主递进去）。本端只剩装配：`DesktopBulletins`
 * 持有 [BulletinStore] 并在冷启动拉一次；顶部那条堆叠条画在首页里（`HomeCard.BULLETIN_BAR`），
 * 那条弹框挂在**壳**上（同一刻只弹一层，与 `:app` 的 `LaunchNoticeDialogs` 同一条口径）。
 *
 * `:app` 的 `LaunchNotices` 还管着自动更新检查与本地更新日志 —— 那两样属于更新域，桌面
 * 是 `DesktopUpdate` 那套（`.deb`/tar.gz 没有「下载并安装 APK」那条路），本端公告宿主面
 * **只有公告那一半**，见 `DesktopBulletins` 的 KDoc。
 *
 * @param auth 会话 + 凭据 + 登录状态（`Main.kt` 与离屏证据各建一个）
 * @param initialTarget 首屏。默认**首页**（`AppRoute.Main`）—— 与 `:app` 落在「Tab 1」同一条口径，
 *   它也是这一端第一屏就有真数据的那一屏（课表缓存里那几格 + 公告条 + 消息那一格）。
 *   其余几屏照旧从底栏/索引页进去。
 */
@Composable
internal fun ToolboxDesktopApp(
    auth: DesktopAuth,
    initialTarget: DesktopTarget = DesktopTarget.App(AppRoute.Main),
    /**
     * 公告取数那一枪（[DesktopBulletins.fetch]）。
     *
     * 默认是**真站点**（`:data` 的 `BulletinApi`，Gitee 优先、失败退 GitHub）。留这一格是为了
     * 离屏证据运行：那一枪要拿夹具公告（见 `RenderScreens`），而不是把真公告画进图里
     *（与图书馆那张 `library-demo.png` 注入 `DemoLibrarySource` 同一条口径）。
     */
    bulletinFetch: suspend (String) -> BulletinApi.FetchResult? = { BulletinApi.fetch(it) },
    /**
     * 屁岱的宿主面（`:core` 的 [AgentHost]）。
     *
     * 默认是**本端真装配**的那一份（`DesktopAgentHost`：配置/会话/记忆真落盘、`:data` 的 SSE
     * 传输、五件接得上的工具）。留这一格与上面的 [bulletinFetch] 同一条理由：离屏证据运行要拿
     * **夹具传输**（见 `RenderScreens` 的 `agent.png`）—— 那一枪不该真去连一次 LLM。
     */
    agentHost: AgentHost? = null,
) {
    var target by remember { mutableStateOf(initialTarget) }
    val scope = rememberCoroutineScope()

    // 屁岱的宿主面：进程内一份（与 `:app` 把屁岱挂在导航项上同位 —— 切页不丢会话）。
    val agent = agentHost ?: remember(auth) { DesktopAgentHost(auth) }


    // 公告（顶部那条堆叠条 + 冷启动弹框）：四层都在共享层，这一端只装配（见 `DesktopBulletins`）。
    // 冷启动跑一次：先铺本地缓存，再去拉远端那份 JSON（拉不到就继续用缓存）。
    // 证据运行把 `bulletinFetch` 换成夹具 —— 离屏那一枪不该去拉真的公告。
    val bulletins = remember { DesktopBulletins(scope, fetch = bulletinFetch) }
    LaunchedEffect(bulletins) { bulletins.check() }

    // 外观设置：`:core` 的 `SettingsStore`（这一端落盘在 0600 的 `app_settings.properties`）。
    // 键值缝没有监听器 ⇒ 设置页改完由 `DesktopSettingsHost.onAppearanceChanged` 戳一下这个计数，
    // 外壳重新读一次（于是「在设置页把深色模式改成始终深色」当场就变 —— 这也是设置项在
    // 桌面上「真能读写」的可见证据：`:app` 那边是 `AppearanceSettings` 监听同一个文件，不用这一戳）。
    var appearanceTick by remember { mutableIntStateOf(0) }
    val darkModeOverride = remember(appearanceTick) { desktopSettings.darkMode }
    val dynamicColor = remember(appearanceTick) { desktopSettings.dynamicColor }

    XJTUToolBoxTheme(darkModeOverride = darkModeOverride, dynamicColor = dynamicColor) {

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
        CompositionLocalProvider(LocalIsWideLayout provides calculateIsWideLayout()) {
            AppShell(
                nav = navState,
                // 这一端的导航模型是**路由表**（43 条里 37 条不属于任何一格）⇒ 只画当前这一页，
                // 不做「进过的 tab 常驻」（那是 `:app` 五格的形态，见 ShellPageHost）。
                pageHost = ShellPageHost.CURRENT_ONLY,
                // 这一端没有「界面风格」这一项（设置页里那两行如实不画，见 DesktopSettingsHost）
                // ⇒ 一律经典：搬迁前这一端画的也是 miuix 经典底栏，不是玻璃胶囊。
                glassStyle = false,
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

    // 冷启动那一条公告：与 MFA 弹窗同一个位置 —— 挂在**壳**上，登录页与主界面都要能弹
    //（`:app` 的 `LaunchNoticeDialogs` 也在 `MainActivity` 那一层）。同一刻只弹一条，
    // 挑哪一条的判据在 `:core` 的 `BulletinRules.pickLaunchDialog`。
    //
    // ⚠️ 它在桌面上是一个**独立的 AWT 窗口**（miuix 的 `WindowBottomSheet` 在非 Android 目标是
    // `androidx.compose.ui.window.Dialog`，与 `MfaCodeDialog` 同一条）⇒ 离屏证据图里画不到它，
    // 只能由 `:desktop:run` 在真窗口里给。所以公告那两处里，有图的是首页顶部那条
    //（`home.png` / `main.png`），弹框那条由 `DesktopHomeJvmTest` 钉「挑中了哪一条」。
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


/**
 * 这一端**真能画**的路由（`:core` 里有屏 + 取数在 `:data`，两者缺一不可）。
 *
 * 十三条真取数：图书馆 / 体测 / 全校课表 / 成绩 / 评教 / 校园卡 / 体育场馆（**要登录**，走会话内核 +
 * 进那一屏再建会话，见 `DesktopSiteGate`）、消息收纳（一网通办那四路：站点要登，但会话由**源自己
 * ensure** —— `AppRoute.Inbox.loginType` 是 null，与空闲教室同型）、通知公告（29 个公开的公告页，
 * **一个都不用登录** —— `AppRoute.Notification.loginType` 也是 null，由源自己爬）、空闲教室（三档数据源要登的站点
 * 不同 ⇒ 也是由源自己 ensure，见那一段的注释）、
 * 校历 / 黄页 / 教师检索（**免登录**的公开门户接口）。
 *
 * 三条**不需要校园会话、但也不需要取数**的：WebVPN 转换器（本机算的字符串互转，见 `:core` 的
 * `WebVpnCodec` 缝）、账号管理（多账号：看/切/增/删，动作本体是 `:desktop` 的 `DesktopAccounts`），
 * 以及内置浏览器（`AppRoute.Browser`：桌面没有内嵌浏览器这一层 ⇒ 这一端的等价物是**外链中转页**，
 * 只做那条路由真交出去的那件事 —— 把地址交给系统浏览器，见 `DesktopBrowser.kt`）。
 *
 * 其余是纯 UI 的游戏（与数据源无关，三端同一份）。
 *
 * 最后一条是**屁岱**（`AppRoute.Agent`）：屏与调度循环在 `:core`，工具层五件里有两件取数走
 * `:data`（黄页 / 教师检索，都免登录），宿主面（配置 / 会话 / 记忆三份真落盘、`:data` 的 SSE
 * 传输）在本端 —— 见 `DesktopAgentHost`（那里也列了它没接的那几档与 TODO）。
 * 它同样不是校园站点：`AppRoute.Agent.loginType` 是 null。
 */
internal val DESKTOP_SUPPORTED_ROUTES = listOf(
    // 首页（第 31 条真数据路由，也是底栏的第一格）：屏是 `:core` 的 `HomeScreen`，这一端提供
    // 宿主面（课表缓存 / 节假日 / 真能打开的路由表 / 公告 / 消息那一格），见 `DesktopHomeScreen`。
    // 它不在 `:app` 的服务目录（`AppServices`）里 —— 那边首页是一个 tab、不是一条路由，
    // 所以首页宫格也不会出现「首页」这个格子。
    AppRoute.Main to "首页",
    AppRoute.SchoolCalendar to "校历",
    AppRoute.Library to "图书馆座位",
    AppRoute.Fitness to "体测",
    AppRoute.SchoolCourse to "全校课表",

    // 我的课表（第 30 条真数据路由，也是本批的主角）：屏与 ViewModel 本轮按五步法搬进了 `:core`
    // （模型 / 端口 / 宿主槽位都就位），取数是 `:data` 的 `JwxtScheduleApi` + `ScheduleSourceRouter`，
    // 落盘是这个端的键值文件（见 `DesktopScheduleHost` 那张表）。名字照 `:app` 服务表里的叫法
    //（「我的课表」）。它与「全校课表」是**同一个教务站点**、同一份会话。
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

    // 下载管理（本批的 `download_manager`）：**不需要任何校园会话**（`AppRoute.DownloadManager`
    // 的 `loginType` 是 null）—— 它读的是本端的下载队列（`:core` 的状态机 + 一份 JSON 落盘）
    // 与视频落盘目录；真下载由 `:data` 的 `JvmDownloadEngine` 发 HTTP（与 Android 另一份实现，
    // 同一套语义）。名字照 `:app` 服务表里的叫法（「下载管理」）。
    AppRoute.DownloadManager to "下载管理",
    AppRoute.DormPower to "宿舍电费",

    AppRoute.Attendance to "考勤",
    // `Lms` 是带 `courseId` 的 data class（其余多数是 data object）⇒ 这一栏要用**默认实例**
    // `AppRoute.Lms()`（`id` = "lms"，与 :app 服务表里那一条同一个 id）。
    AppRoute.Lms() to "思源学堂",

    // 三域的名字照 `:app` 服务表里的叫法（`AppServices.kt`）：「成绩」（jwapp）/
    // 「快速考勤流水」（iclassface） / 「社区」。桌面索引页上这一栏就是同名同 id。
    AppRoute.JwappScore to "成绩",
    AppRoute.Iclassface to "快速考勤流水",
    AppRoute.Community to "社区",

    // 反馈（第 22 条真数据路由）：名字照 `:app` 服务表里的叫法（「反馈与建议」）。
    // 它**不是**校园站点：后端是飞书开放平台，凭据由宿主在启动时注入（见 `:core` 的
    // `FeedbackSource` KDoc）⇒ 不套 `DesktopSiteGate`。
    AppRoute.Feedback to "反馈与建议",

    // 教材查询（`jiaocai` 域，第 24 条真数据路由）：站点是**教材中心**（`jiaocai.lib`）——
    // 与下面那条全文库路由同一个站点、同一套屏（`:core` 的 `TextbookScreen`），
    // 只是默认落在「查教材」栅。
    AppRoute.Jiaocai to "教材查询",

    // 教材全文库（第 22 批里的 `jiaocai1`）：名字照 `:app` 服务表里的叫法（「教材全文」）。
    // 站点是**教材中心**（`jiaocai.lib`）—— 全文库借它的会话，所以套的是 `JIAOCAI_SITE_KEY`
    // （值就是 `jiaocai`）。阅读器那条参数路由（`Jiaocai1Reader`）不单独列在索引页里：
    // 它是从书架/检索结果进去的二级屏，与 `AppRoute.Lms` 那种「带 courseId 的入口」不同。
    AppRoute.Jiaocai1 to "教材全文",
    AppRoute.Games to "游戏合集",
    AppRoute.Game2048 to "GPA 2048",
    AppRoute.GameBlocks to "方块",
    AppRoute.GameGomoku to "五子棋",
    AppRoute.GameHop to "跳一跳",
    AppRoute.GameGo to "围棋",
    AppRoute.GameXiangqi to "象棋",

    // 付款码（第 26 条真数据路由）：站点就是**校园卡**（`AppRoute.PaymentCode.loginType`
    // = `CAMPUS_CARD`，与 `:app` 导航层那句 `WithSite("campus_card")` 同一条口径）——
    // 它借的就是校园卡那个 JWT（`synjones-auth: bearer <JWT>`），不另建会话。
    AppRoute.PaymentCode to "付款码",

    // WebVPN 转换器（第 28 条真数据路由）：**不需要任何校园会话**、也不发请求 ——
    // 本机算的字符串互转。名字照 `:app` 服务表里的叫法（「WebVPN」/「校外访问转换」），
    // 索引页这一栏用屏自己的标题（WebVPN）。
    AppRoute.WebVpnConverter to "WebVPN 转换器",

    // 内置浏览器（`AppRoute.Browser`）：与上面那条同型 —— **不取数、也不需要校园会话**，一条请求都不发。
    // 名字照 `:app` 那一屏自己的标题（`BrowserScreen` 的 `pageTitle` 初值就是「浏览器」）；这一端的
    // 等价物是外链中转页（`DesktopBrowser.kt`：目标网址 + 交给系统浏览器 + 复制）。
    // 带参形状：`Browser` 是 data class ⇒ 这里必须写 `()`（裸写 `AppRoute.Browser` 编不过，
    // 与上面那批 `AppRoute.Lms()` 同型）。
    AppRoute.Browser() to "浏览器",

    // 账号管理（多账号，第 29 条真数据路由）：**不需要校园会话就能进**（进去是为了看/换/增/删），
    // 但它每一个动作都要会话内核：切账号 = 换绑 backends、新增 = 先探活登录再落库。
    // 名字照 `:app` 服务表里的叫法（「账号管理」/「切换或添加统一认证账号」）。
    AppRoute.Accounts to "账号管理",

    // 屁岱（`AppRoute.Agent`，最后一条点亮的路由）：名字照 `:app` 底栏那一格的叫法（`BottomTab.PIDAI`
    // 的 `"屁岱"`，也是它自己顶栏上的那个标题）。屏、调度循环、工具层的纯逻辑都在 `:core`，
    // 这一端交宿主面（配置 / 会话 / 记忆三份真落盘、`:data` 的 SSE 传输）与五件接得上的工具 ——
    // 见 `DesktopAgentHost`。它也不套 `DesktopSiteGate`（这一端接的两档取数都免登录）。
    AppRoute.Agent to "屁岱",

    // 匹配交友（`AppRoute.Match`，第 33 条真数据路由）：名字照 `:app` 服务表里的叫法（「匹配交友」）。
    // 屏与算法本轮搬进 `:core`，这一端只交宿主面（课表族缓存与「我的课表」「首页」同一份）——
    // 它一条请求都不发，所以「离线算交集」在这一端是真算的；学工档案与校园卡消费这一端没有（见
    // `DesktopMatchHost` 的表），扫码那一档也没有相机 ⇒ 那两颗按钮一个字都不画。
    AppRoute.Match to "匹配交友",
)

/**
 * **屏已经搬进 `:core`、但这一端还没有数据源**的路由 —— 也就是 Stage A 的口径进度表。
 *
 * **现在是空的**：第 17 条（考勤）搬完之后，`AppRoute` 里「屏在 `:core` 而取数还没搬」的
 * 那种路由一条不剩。这个列表与 [DESKTOP_SUPPORTED_ROUTES] 一样留在代码里而不是删掉：
 * 屏先落地、取数后到是 Stage A 里的**常规**中间态（这一份历史就是 17 次），下一屏要搬时
 * 它还会重新长出条目；索引页上那一栏也照旧画出来（数字是进度，不是装饰）。
 *
 * 进来一条就说明它卡的是同一件事：取数还在 `:app`（`Campus*Api` 的只读端，或某个站点的
 * `*Login` + 站点类）。搬到 `:data` 一条，这里就划掉一条（`docs/desktop-port-plan.md` §5.1／§3.2）。
 */
internal val DESKTOP_PENDING_ROUTES = emptyList<Pair<AppRoute, String>>()


/**
 * 一屏共享页。每个屏只注入**这一端能提供的东西**：
 * - 图书馆：取数换成 `:data`（真会话 + 真 `LibraryApi`），并且**重新认证那一枪也能打**
 *   （宿主手里就有凭据与会话 —— 这正是只读端没有的能力，见 `LibraryScreen` 的 `reAuthenticate`）；
 * - 外链交给系统浏览器（`java.awt.Desktop.browse`）；
 * - 「功能说明弹过了没」这类本地偏好走 `:core` 的 `keyValueStore`（JVM 侧是落盘的 Properties 文件）。
 *
 * 需要站点会话的屏（现在只有体测）走 [DesktopSiteGate] —— 会话按需建立，见那一段的 KDoc。
 */

/**
 * 「进这一屏之前先把它的站点会话建起来」—— 与 `:app` 同一条口径：那边是导航层
 * `AppRouter.open(route)` 先按 `route.loginType` 的 `ensureSite` 再跳，所以屏自己从不处理
 * 「会话未初始化」。
 *
 * 为什么不放在登录页那一步：体测服务历史上真返回过 502（见 `:data` 的 `FitnessSession` 的 KDoc），
 * 把每个站点都塞进 `login()` 会让「某个子系统自己挂了」变成「整个桌面端登不进去」。
 * 放在路由上，最坏只是这一屏自己报错 —— 而且这里给了「重试」，服务恢复后点一下就回来。
 */
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

/**
 * 一屏共享页 —— 见文件上方那段 KDoc（「每个屏只注入这一端能提供的东西」）。
 */
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
    // 这一端没有返回栈，底栏就是导航 ⇒ 屏上的返回一律回图书馆首页（与 Stage 0 同一条口径）
    val back = { onNavigate(DesktopTarget.App(AppRoute.Library)) }
    when (route) {
        // 首页（`AppRoute.Main`，第 31 条真数据路由）：与 `:app` 的「Tab 1」**同一份屏**
        //（`:core` 的 `HomeScreen`）。本端只提供宿主面：课表缓存（`DesktopScheduleHost`）、
        // 节假日、真能打开的路由表、公告、以及消息那一格。见 `DesktopHomeScreen` 的 KDoc
        //（那里列了它真出数的几样与如实降级的那几样，包括没有桌面小组件这件事）。
        AppRoute.Main -> DesktopHomeScreen(
            auth = auth,
            bulletins = bulletins,
            onNavigate = onNavigate,
        )

        // 校历：**免登录**的公开门户接口 ⇒ 不需要任何会话适配器，`：data` 的取数直接用
        //（这正是「屏在 `:core` + 取数在 `:data`」的报偿：搬一个 35 行的 IO 适配器就多一屏）。
        // `calendarImage` 不传：那是 `:app` 的教务处图片接口（`SchoolCalendarImageApi`），
        // 用到 `Intent` 与 `DataCache`，还没搬——不传就是不画那一档，不假装有。
        AppRoute.SchoolCalendar -> SchoolCalendarScreen(
            source = remember { SchoolCalendarApi() },
            onBack = back,
        )
        // 教师检索：**免登录**（`faculty.xjtu.edu.cn` 检索 + `gr.xjtu.edu.cn` 主页），取数已搬进 `:data`。
        // 适配器 `FacultyApiSource` 也是共享的（`:app` 与桌面用同一份，不再各写一份）。
        // 头像用默认的 `InitialsFacultyAvatar`（首字圆）：`:app` 那份是 BitmapFactory + LruCache
        //（Android 专属），而 `gr.xjtu.edu.cn` 不给 CORS 头 —— 不假装能取到图。
        AppRoute.Faculty -> FacultyScreen(
            source = remember { FacultyApiSource() },
            onBack = back,
            onOpenUrl = { openInBrowser(it) },
        )
        // 黄页：另一条**免登录**的公开门户接口，而且 `:core` 里那份 `YellowPageApi` 早就搬完了
        //（第 1 步 okhttp→Ktor 时搬的，端口收一个 `HttpClient`）⇒ 桌面只需给一条客户端。
        // `cache = null`：`:core` 的黄页缓存缝要一个按账号隔离的落盘实现（`:app` 是 `DataCache`），
        // 桌面端还没有那份宿主存储（属 §5.4）——不缓存，直接请求，屏上的语义不变。
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
                    // 用户点「重新认证」时强制重登一次。`:app` 那一份读的是 App 的凭据 +
                    // `site.ensureLogin(force = true)`；桌面端的凭据就在会话管家里，所以直接走
                    // `ensureSite(userInitiated = true)`（凭据已在，失败会抛出来给屏显示）。
                    auth.sessionManager.ensureSite(DesktopAuth.LIBRARY_SITE_KEY, userInitiated = true)
                },
                showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("library_hint_shown", true) },
            )
        }
        // 体测：站点是 **https**，取数走 `:data` 的 `FitnessApi`（v3 加密协议优先、失败退回 legacy PHP）。
        // 站点会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它 ——
        // 体测服务历史上真的返回过 502，不能因为它挂了就把人挡在登录页外。
        AppRoute.Fitness -> DesktopSiteGate(auth, DesktopAuth.FITNESS_SITE_KEY, "体测") { site ->
            FitnessScreen(
                source = remember(site) { FitnessApi(site) },
                onBack = back,
            )
        }
        // 教务（全校课表）：站点会话**进门时才建**（见 `DesktopSiteGate`）。取数就是 `:data` 里那份
        // `AppSchoolCourseSource`（包住原来的 `SchoolCourseApi`）—— `:core` 的屏只认端口。
        AppRoute.SchoolCourse -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { site ->
            SchoolCourseScreen(
                source = remember(site) { AppSchoolCourseSource(site) },
                onBack = back,
            )
        }
        // 我的课表（第 30 条真数据路由）：屏与 ViewModel 都在 `:core`，这一端只提供**拿得到的
        // 宿主面**（`DesktopScheduleHost` 那张表：键值落盘 / 教务会话 / 节假日 / 思源与考勤）。
        // 站点会话**进门时才建**（见 `DesktopSiteGate`）—— 与全校课表 / 成绩共用同一个教务站点。
        //
        // 这一屏是唯一一屏要传 `host` 的：其余几屏拿的是**一个端口**（`source`），日程页要的是
        // 一整组能力（本地落盘、分享面板、小组件与首页信号……），端口逐个拆反而看不清哪些端没有。
        AppRoute.Schedule -> DesktopSiteGate(auth, DesktopAuth.JWXT_SITE_KEY, "教务") { _ ->
            ScheduleScreen(
                host = remember { DesktopScheduleHost(auth) },
                onBack = back,
                onNavigate = { r ->
                    when (r) {
                        // 与下面 `InboxScreen` / `NotificationScreen` 那两处同一条口径：外链交给系统浏览器，
                        // 带 `then` 时交的是第二跳（判据在 `DesktopBrowser.kt` 的 `desktopBrowserTarget`）。
                        is AppRoute.Browser -> desktopBrowserTarget(r.url, r.then)?.let { openInBrowser(it) }
                        else -> onNavigate(DesktopTarget.App(r))
                    }
                },
            )
        }
        // 成绩：同一个教务站点（两条路由共用一份会话）。取数要一个**学号**，它闭在适配器里；
        // 桌面端没有登录取学号那一套（`LoginScreen` 只有账号密码，学号不落屏），所以用登录时
        // 写进 `AccountContext.activeAccountId` 的那个 —— 它**取不到就画错误页**，绝不拿空学号
        // 去请求（那会遇到一张“查无此人”的报表，看起来像屏坏了）。
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
                    // SWR 缓存：与 `:app` 同一个键（`score_report_$studentId`）。读到旧成绩先秒显、
                    // 同时再拉一发；拉回来写回去。`:desktop` 这边落在平台键值缝上
                    // （见 DesktopScoreReportCache；`:app` 的那一份仍在 `:app`，本批零改动）。
                    cache = remember(studentId) { DesktopScoreReportCache(studentId) },
                    onBack = back,
                )
            }
        }

        // 评教（第八条真数据路由）—— 本科 / 研究生是**两条不同的链路**，与 `:app` 的导航层同一个判据
        // （`AccountContext.activeAccountType`，即 `AppRoute.Judge.loginType` 里那个 `isPostgraduateSession()`）：
        //
        // - **本科**：与全校课表 / 成绩**共用同一个教务站点**（所以走同一个 `DesktopSiteGate`）。参评人
        //   （`CPR`）就是登录时写进 `AccountContext.activeAccountId` 的学号 —— 取不到就画说明页，
        //   不拿空学号去请求（那会领回一张空卷子，看着像屏坏了）；
        // - **研究生**：问卷在 gste、课程信息在 gmis。这条**不套 Gate**：`GraduateJudgeSource` 自己
        //   `ensureSite`（gste 进屏时登、gmis 到一键评教时才登）—— 这正是 `:app` 那边的语义，
        //   只是把宿主那一发缓到真正取数的时候。
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

        // 校园卡（第九条真数据路由）：站点是 **https**，取数走 `:data` 的 `AppCampusCardSource`
        // （包住原来的 `CampusCardApi`）。会话**进门时才建**（见 `DesktopSiteGate`）—— 登录页那一步
        // 只是尽力预热它，ncard 自己挂了不该把人挡在门外。
        // 缓存传 `null`：桌面没有按账号分文件的宿主存储（属 §5.4），不缓存 —— 与 Web 端同一条口径：
        // 屏仍旧自己取数，只是没有「首屏秒开」那一档。加餐券那条二级入口也不传（桌面没有那份首页摘要）。
        AppRoute.CampusCard -> DesktopSiteGate(auth, DesktopAuth.CAMPUS_CARD_SITE_KEY, "校园卡") { site ->
            CampusCardScreen(
                source = remember(site) { AppCampusCardSource(site) },
                onBack = back,
            )
        }

        // 付款码（第 26 条真数据路由）：屏在 `:core`（`PaymentCodeScreen`）、取数在 `:data`
        // （`PaymentCodeApi`，两端共用同一份），借的会话就是**校园卡**那个站点
        // （`AppRoute.PaymentCode.loginType` = `CAMPUS_CARD`）⇒ 套 `DesktopSiteGate`。
        //
        // 这一端多传/少传两件东西：
        //  - **加餐券勾选的落盘**：`:app` 那边接的是 `SharedPreferences("payment_code")`，
        //    桌面接 `secureKeyValueStore("payment_code")` —— 同一份 0600 的 Properties 文件，
        //    所以「勾了哪几张券」在桌面是**真落盘**（与 `:data` 的凭据 / cookie 同一套落盘口径）；
        //  - **窗口满亮那一半不传**（它本来就不在共享屏里）：`:app` 的 `pay/AppPaymentCode.kt`
        //    会把窗口调到 `BRIGHTNESS_OVERRIDE_FULL` 好让扫码枪扫得动，而桌面窗口的亮度归
        //    系统电源策略管 —— 这里不假装能改它。屏幕常亮那一半在共享屏里（`KeepScreenOn`），
        //    桌面是空实现（不需要）。
        // 「关闭」= 回图书馆首页：桌面没有返回栈，底栏就是导航（与其余屏同一条口径）。
        AppRoute.PaymentCode -> DesktopSiteGate(auth, DesktopAuth.CAMPUS_CARD_SITE_KEY, "校园卡") { site ->
            PaymentCodeScreen(
                source = remember(site) { PaymentCodeApi(site) },
                selections = remember { PaymentSelectionStore(secureKeyValueStore("payment_code")) },
                onDismiss = back,
            )
        }

        // 空闲教室（第十条真数据路由）：三档数据源（实时状态 / CDN 课表 / 直查教务）都在 `:data`
        // 的 `AppEmptyRoomSource` 里 —— 会话由**那个源自己 ensure**（实时状态那一档登智慧教室
        // `js`、直查那一档登教务），所以这一条**不套** `DesktopSiteGate`：`AppRoute.EmptyRoom.loginType`
        // 本来就是 null（「按数据源不同要登的站点不同，由页面自己登」），与研究生评教那条同型。
        // 落盘传 `null`：桌面没有按账号分命名空间的宿主存储（属 §5.4），屏仍旧自己取数，
        // 只是少了下拉刷新之外的那一档磁盘兜底 —— 与 Web / 校园卡同一条口径。
        // 「CDN 说明读没读过」与 Web 同一个键、同一个 pref 文件（`empty_room`，屏自己的偏好也在那儿）。
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

        // 体育场馆（第十一条真数据路由）：站点是**明文 http**（`202.117.17.144:8080`），登录要先过
        // `org.xjtu.edu.cn` 的 OAuth2（appId=1659）→ CAS → 回跳 `/web/cas/oauth2url.html`。会话
        // **进门时才建**（见 `DesktopSiteGate`）—— 登录页那一步只是尽力预热它，场馆站自己挂了最坏
        // 只影响这一屏。
        //
        // 取数是 `:data` 的 `AppVenueSource`（包住原来的 `VenueApi`）；收藏走 `:core` 共享的
        // `VenueFavorites`（同一份文件、同一个键 ⇒ 与 App 那边看到的是同一批收藏）。
        //
        // ✅ Stage B 之后 `canBook = true`：下单要解滑块，桌面现在有宿主（`DesktopSlideCaptchaHost`，
        // 新写的滑块 UI + 拖动回调；识别/提交逻辑在 `:data`），`VenueScreen` 的 `captchaHost` 槽位
        // 由它填上 ⇒ 「确认预订」→ 验证码弹窗 → 拖动 → 提交这条写路径在桌面走通。
        // 自动识别（`solveCaptcha`）由桌面宿主提供，但桌面没有「自动识别验证码」设置项 ⇒
        // `autoSolveCaptcha` 用默认 false，停在手动拖动那一档（与功能说明文案一致）。
        // 支付那一跳：`:app` 是内置浏览器串两跳（先 OAuth 入口，`isAuthHop` 判完再 `loadUrl(支付页)`）；
        // 桌面端没有可观察的页面 ⇒ `openInBrowser` 一次只交一个地址，按 `desktopBrowserTarget` 的
        // 判据把**第二跳那个支付页**交出去（跳过去之后站点自己会先要一次登录）。
        // 「功能说明弹过了没」与 `:app` 同一个 pref 文件、同一个键（`feature_hints` / `venue_hint_shown`）。
        AppRoute.Venue -> DesktopSiteGate(auth, DesktopAuth.VENUE_SITE_KEY, "体育场馆") { site ->
            val hintPrefs = remember { keyValueStore("feature_hints") }
            VenueScreen(
                source = remember(site) { AppVenueSource(site, canBook = true) },
                onBack = back,
                onOpenBrowser = { url, then ->
                    // 见 `DesktopBrowser.kt` 的 `desktopBrowserTarget`：桌面收不到回调 ⇒ 只挑一个地址
                    //（优先第二跳那个支付页）；两个都空时不替它编一个地址。
                    desktopBrowserTarget(url, then)?.let { openInBrowser(it) }
                },
                captchaHost = remember { DesktopSlideCaptchaHost },
                showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("venue_hint_shown", true) },
            )
        }

        // 消息收纳（第十二条真数据路由）：取数是 `:data` 的 `AppInboxSource`（包住搬进 `:data` 的
        // `SchoolInbox` —— 一网通办那四路）。会话由**那个源自己 ensure**（`AppRoute.Inbox.loginType`
        // 是 null），所以不套 `DesktopSiteGate`，与空闲教室 / 研究生评教同型。
        //
        // 进门先拉一轮：这一屏**没有 ViewModel**（数据在 `:core` 的 `InboxStore` 里，写入方是学校那四路、
        // 首页定时刷新、桌面小组件…），屏自己只在「下拉刷新」里调 `refresh` —— `:app` 那边进门那一枪是
        // 导航层与首页 `HomeStatsRefresher` 打的。桌面没有那两份，所以由外壳在进屏时补上：「该不该拉」
        // 仍由源自己判（`isDue`，TTL 30 分钟），失败就把原因写在屏上方（屏没有参数可以收下这一发）。
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
                        // 与 `:app` / Web 同一条口径：外链交给系统浏览器（桌面没有内置浏览器），
                        // 其余按路由 id 走站内导航（认不出的直接忽略 —— 可能是旧版本存下的）。
                        // 带 `then` 时交的是第二跳，见 `DesktopBrowser.kt` 的 `desktopBrowserTarget`。
                        onOpen = { id ->
                            when (val r = appRouteOf(id)) {
                                is AppRoute.Browser -> desktopBrowserTarget(r.url, r.then)?.let { openInBrowser(it) }
                                null -> Unit
                                else -> onNavigate(DesktopTarget.App(r))
                            }
                        },
                        account = account,
                    )
                }
            }
        }
        // 通知公告（第十三条真数据路由）：取数是 `:data` 的 `AppNoticeSource`（包住搬进 `:data` 的
        // `NotificationApi` —— okhttp + jsoup 爬 29 个公开的公告页，含那条纯 HTTP 就能解开的动态挑战）。
        // 29 个源**一个都不用登录**，不靠任何站点会话 ⇒ 不套 `DesktopSiteGate`
        // （`AppRoute.Notification.loginType` 就是 null）；这个源连 `SessionManager` 都不收。
        // 点开一条通知就是 `AppRoute.Browser(link)` ⇒ 外链交给系统浏览器（与消息收纳同一口径；
        // 带 `then` 时交第二跳，见 `DesktopBrowser.kt` 的 `desktopBrowserTarget`）。
        AppRoute.Notification -> NotificationScreen(
            source = remember { AppNoticeSource() },
            onBack = back,
            onNavigate = { r ->
                when (r) {
                    is AppRoute.Browser -> desktopBrowserTarget(r.url, r.then)?.let { openInBrowser(it) }
                    else -> onNavigate(DesktopTarget.App(r))
                }
            },
        )
        // 加餐券（第十五条真数据路由）：站点是 **https** 的 `egc.xjtu.edu.cn`，而且
        // **它有自己的独立登录页**（CAS OAuth2 `client_id=1596` → `org.xjtu.edu.cn` 开放平台
        // → 回落到 receiveCas.html → 换 `auth_token`，见 `:data` 的 `CouponLogin`）—— 不借别的
        // 已搬站点的会话。会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏与分页/领取的编排都在 `:core`（`CouponScreen` + `CouponViewModel`），取数是 `:data` 的
        // `AppCouponSource`（包住搬进 `:data` 的 `CouponApi`，字段口径一行未改）。券封面图也走
        // 这个源（`loadImage`，本端 = 站点会话客户端 + `:core` 图片缝解码）。唯一留在这端的空白是
        // 首页摘要：桌面没有那份 HomeStats，`onSummary` 不传（屏的默认空实现）—— 如实不写。
        AppRoute.Coupon -> DesktopSiteGate(auth, DesktopAuth.COUPON_SITE_KEY, "餐券系统") { site ->
            CouponScreen(
                source = remember(site) { AppCouponSource(site) },
                onBack = back,
            )
        }
        // 电子成绩单（第十四条真数据路由）：站点是 **https** 的 `dzpz.xjtu.edu.cn`，而且
        // **它有自己的独立登录页**（Ecology 的 `Login.jsp` → CAS OAuth2 `client_id=new9940`
        // → 回跳换 `loginidweaver`，见 `:data` 的 `DzpzLogin`）—— 不借别的已搬站点的会话。
        // 会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏与七步流程都在 `:core`（`TranscriptScreen` + `TranscriptViewModel`），取数是 `:data` 的
        // `AppTranscriptSource`（包住搬进 `:data` 的 `TranscriptApi`，七步的表单/字段口径一行未改）。
        // 唯一留在这一端的是 **PDF 落盘**：桌面没有 MediaStore 与「我的 · 下载管理」，
        // `DesktopTranscriptSaver` 写用户的下载目录（`:core` 的 `TranscriptSaveSink` 槽位）。
        AppRoute.Transcript -> DesktopSiteGate(auth, DesktopAuth.DZPZ_SITE_KEY, "电子凭证") { site ->
            TranscriptScreen(
                source = remember(site) { AppTranscriptSource(site) },
                onBack = back,
                saveSink = DesktopTranscriptSaver,
            )
        }

        // 宿舍电费（第十七条真数据路由）：站点是 `ssn.xjtu.edu.cn/cems`，而且**它有自己的独立登录链**
        // —— `org.xjtu.edu.cn` 开放平台 OAuth2（appId=1759）→ 统一认证 → 带 `code` 回缴费页
        //（站点点下会话、页面里内嵌 `cid`，见 `:data` 的 `SsnLogin` / `SsnSession`）—— 不借校园卡 /
        // 教务的会话。会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏与取数端口在 `:core`（`DormPowerScreen` + `DormPowerSource`），取数是 `:data` 的
        // `DormPowerApi`（`freshPayUrl` 每次重走 OAuth、拦下带 code 的那一跳）。桌面端没有内置
        // 浏览器 ⇒ `browser` 槽位用系统浏览器打开、本窗只给一句去向；首页摘要那档（`:app` 的
        // `onLeave`）桌面没有，不传（默认空实现）—— 如实不写。
        AppRoute.DormPower -> DesktopSiteGate(auth, DesktopAuth.SSN_SITE_KEY, "宿舍电费") { site ->
            DormPowerScreen(
                source = remember(site) { DormPowerApi(site) },
                onBack = back,
                browser = { url -> DesktopPayPage(url) },
            )
        }

        // 考勤（第十六条真数据路由）：站点是 `bk-kq.xjtu.edu.cn`（本科生）/ `yjs-kq.xjtu.edu.cn`，
        // 而且**它有自己的独立登录入口** —— `AttendanceLogin` 直连业务站的 CAS `student-pc` 入口
        //（账号类型已知；失败自动退回门户流程），回调带 loginRequestId+ticket 换业务令牌 ——
        // 不借别的已搬站点的会话（见 `:data` 的 `AttendanceSession` / `AttendanceLogin`）。会话
        // **进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏/VM 与取数端口在 `:core`（`AttendanceScreen` + `AttendanceSource`），取数是 `:data` 的
        // `AppAttendanceSource`（包住搬过来的 `AttendanceApi` + `LeaveApi`，字段口径一行未改）。
        // 桌面端没有 SAF 文件选择器 ⇒ `pickEvidenceFiles` 用默认（空表，不发附件）；「快速考勤
        // 流水」那条二级入口桌面还没有（导航过去是未搬说明页）—— 如实降级。
        AppRoute.Attendance -> DesktopSiteGate(auth, DesktopAuth.ATTENDANCE_SITE_KEY, "考勤") { site ->
            AttendanceScreen(
                source = remember(site) { AppAttendanceSource(site) },
                onBack = back,
                onOpenIclassface = { onNavigate(DesktopTarget.App(AppRoute.Iclassface)) },
            )
        }
        // 思源学堂（第十八条真数据路由）：站点是 **https** 的 `lms.xjtu.edu.cn`，而且**不借别的
        // 已搬站点的会话** —— `LmsLogin.loginUrl` 就是 lms 根路径：先摸 lms → 302 到统一认证
        //（CAS 签票）→ 回跳 lms 种 session cookie，与教务同型（没有 dzpz/coupon 那种独立登录页）。
        // 会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏在 `:core`（`LmsScreen`：课程列表 → 活动列表 → 活动详情，画法与文案一行未改），取数是
        // `:data` 的 `AppLmsSource`（包住搬进 `:data` 的 `LmsApi`）。落盘是 `DesktopLmsSaver`
        //（写 `~/Downloads/<课程名>/…`；桌面没有 MediaStore 与「我的 · 下载管理」，也没有回放
        // 下载队列 ⇒ `supportsVideoQueue = false`，批量对话框不露出「课堂回放」那一档）；落盘缓存
        // 传 null（桌面没有按账号分文件的宿主存储）。视频播放器走 `:core` 的 `VideoPlayerHost`：
        // 桌面这份把流地址交给系统播放器（没有内置播放器，见 `DesktopVideoPlayerHost` 的 KDoc）。
        // `Lms` 带 `courseId`（`lms?courseId=`）⇒ 与 `:app` 的 `entry<AppRoute.Lms>` 同一条口径：
        // **按类型**分派（`is`），带不带 courseId 都进这一屏（屏自己按 courseId 决定落哪门课）。
        is AppRoute.Lms -> DesktopSiteGate(auth, DesktopAuth.LMS_SITE_KEY, "思源学堂") { site ->
            val hintPrefs = remember { keyValueStore("feature_hints") }
            LmsScreen(
                source = remember(site) { AppLmsSource(site, DesktopLmsSaver) },
                onBack = back,
                cacheStore = null,
                showFirstUseHint = !hintPrefs.getBoolean("lms_hint_shown", false),
                onFirstUseHintRead = { hintPrefs.putBoolean("lms_hint_shown", true) },
                videoPlayer = DesktopVideoPlayerHost,
            )
        }

        // 成绩（移动教务，第十九条真数据路由）：站点是 `jwapp.xjtu.edu.cn`，它**有自己的独立登录链**
        // （开放平台 `org.xjtu.edu.cn` 的 `authorize?appId=1370` → 统一认证 → 带 `token` 回
        // `jwapp.xjtu.edu.cn/app/index`，token 从回调 URL 里抠出来当 `Authorization` 头）。
        // 会话**进门时才建**（见 `DesktopSiteGate`）：登录页那一步只是尽力预热它。
        //
        // 屏在 `:core`（`JwappScoreScreen` + VM：本学期成绩 / GPA / 分项 / 选课计算，画法与文案一行未改），
        // 取数是 `:data` 的 `AppJwappScoreSource`——它同时实现那两个端口：`source`（jwapp 一站）与
        // `jwxt`（教务一站，精确成绩 `xscjcx.do` / 未评教名单 / 成绩报表）。两个站点各自独立会话，
        // 所以这一屏要的站点是两个（jwxt 已在登录页那一步预热过）。
        //
        // 学号照成绩报表 / 评教那两屏同一条口径：用登录时写进 `AccountContext.activeAccountId` 的
        // 那个（桌面没有「登录取学号」那一套）。`cache` 传 null：桌面没有按账号分文件的 `DataCache`
        // ⇒ 这一屏不先给缓存秒显，其余流程一字不改。
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

        // 快速考勤流水（人脸识别签到，第二十条真数据路由）：站点是 `iclassface.xjtu.edu.cn`，它也有
        // 自己的独立登录链（CAS OAuth2 `client_id=1543` → `org.xjtu.edu.cn` 开放平台中转 → 回落
        // `/oauth/callback/xjtu_new`，凭据就是 `PHPSESSID` cookie）。`mustUseWebVpn = true`，
        // 但登不登得上交给会话内核判（`IclassfaceLogin` 本身认 WebVPN 形态的落地页）。
        //
        // 屏在 `:core`（`IclassfaceScreen`：选日期 → 表格，画法与文案一行未改），取数是 `:data` 的
        // `AppIclassfaceSource`（包住 `IclassfaceApi` 的 `POST /checkin/records` + Jsoup 解表）。
        AppRoute.Iclassface -> DesktopSiteGate(auth, DesktopAuth.ICLASSFACE_SITE_KEY, "快速考勤流水") { site ->
            IclassfaceScreen(
                source = remember(site) { AppIclassfaceSource(site) },
                onBack = back,
            )
        }

        // 社区（第二十一条真数据路由）：上游是 GitHub（`api.github.com` 的 GraphQL + `github.com/login`
        // 的设备码流程），与学校的站点无关，所以**不套** `DesktopSiteGate`（没有学校会话要建）。
        //
        // 登录态是 `:data` 的 `SecureGithubSession`：存储名与键与搬迁前逐字相同
        // （`github_community` / `token` / `login`；Android 的 actual 仍是 `SecurePrefs`，桌面是一份
        // 0600 的落盘文件）⇒ Android 上已有的登录态与桌面不会互盖。取数与设备码都是 `:data` 里那两份
        // （与 Android 同一份实现）。「旧版反馈」在桌面还没搬（那是 `:app` 的 WebView 页）⇒
        // 跳过去是那张如实说明页，不假装它在这儿。
        AppRoute.Community -> CommunityScreen(
            session = remember { SecureGithubSession.get() },
            deviceAuth = remember {
                GithubOAuthDeviceAuthRepository(GithubNetwork.client, CommunityRepo.CLIENT_ID)
            },
            onBack = back,
            onOpenLegacyFeedback = { onNavigate(DesktopTarget.App(AppRoute.Feedback)) },
        )

        // 反馈（第 22 条真数据路由）：**不套 `DesktopSiteGate`** —— 它没有校园站点会话可建
        // （上游是飞书开放平台 `open.feishu.cn`，凭据是宿主注入的 `app_id` + `app_secret`，换一张
        // 租户令牌；见 `:core` 的 `FeedbackSource` KDoc）。屏与本地账本（`FeedbackStore`）都在
        // `:core`，取数是 `:data` 的 `AppFeedbackSource`（包住搬进 `:data` 的 `FeedbackApi`）。
        //
        // 两处如实差异（都在下面两行里看得见）：
        //  - 外链（GitHub Issue）交给系统浏览器（桌面没有内置浏览器 / 没有 `Intent`）；
        //  - 凭据没配时整个表单隐藏、只剩外链 —— 与 Android 同一个判据（`isConfigured`）。
        // 桌面端的凭据来源与 Android 同一份（`feedback.properties` / 环境变量，构建期注入，
        // 见 `DesktopFeedback`）。
        AppRoute.Feedback -> FeedbackScreen(
            source = remember { AppFeedbackSource },
            onOpenUrl = { url -> if (url.isNotBlank()) openInBrowser(url) },
            onBack = back,
        )

        // 教材全文（`jiaocai1` 域）：站点是**教材中心**（`jiaocai.lib.xjtu.edu.cn`）——
        // 全文库没有自己的登录器，借的就是它的会话（`AppRoute.Jiaocai1.loginType` = `LoginType.JIAOCAI`），
        // 所以这里套 `DesktopSiteGate(DesktopAuth.JIAOCAI_SITE_KEY)`（那个常量的值就是 `jiaocai`）。
        // 与 `:app` 那条路由的差异只有一处（如实）：`jiaocai` 域的合并页 `TextbookScreen` 现在
        // 也在 `:core` 了，桌面把它接在**同一个站点的另一条路由**（下面那一支 `AppRoute.Jiaocai`）
        // ⇒ 这两支各画一半：这一支是「书架 + 全文库」两栅（`Jiaocai1LibraryScreen`），
        // 那一支是三栅合并页、默认落在「查教材」栅。
        AppRoute.Jiaocai1 -> DesktopSiteGate(auth, DesktopAuth.JIAOCAI_SITE_KEY, "教材中心") { site ->
            val source = remember(site) { AppJiaocai1Source(site, jiaocai1CacheRoot()) }
            // 书架：桌面没有 Room ⇒ 一份 0600 的 JSON 落盘（见 `DesktopJiaocai1ShelfStore` 的 KDoc）；
            // 分类树的 DataCache 在桌面也没有等价物 ⇒ `cache` 传 null（每次进程拉一遍，屏上行为不变）。
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
        // 教材查询（`jiaocai` 域，第 24 条真数据路由）：站点还是**教材中心**，屏是 `:core` 的合并页
        // `TextbookScreen`，默认落在「查教材」栅（与 `:app` 的 `AppRoute.Jiaocai` → `initialTab = 0`
        // 同一条口径）。「本学期我的教材」那一段的数据在**教务**的缓存里，桌面还没有课表 ⇒
        // 端口回空表、那一段整段不显示（见 `DesktopJiaocaiTermTextbooks` 的 KDoc）—— 如实降级。
        AppRoute.Jiaocai -> DesktopSiteGate(auth, DesktopAuth.JIAOCAI_SITE_KEY, "教材中心") { site ->
            TextbookScreen(
                source = remember(site) { AppJiaocaiSource(site) },
                termTextbooks = DesktopJiaocaiTermTextbooks,
                jiaocai1Source = remember(site) { AppJiaocai1Source(site, jiaocai1CacheRoot()) },
                jiaocai1Shelf = remember { DesktopJiaocai1ShelfStore() },
                // 分类树的 DataCache 在桌面没有等价物 ⇒ null（每次进程拉一遍，屏上行为不变）
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
        // 下载管理：`:core` 的共享屏（与 Android 同一份，画法/文案一行未改），这一端填三个缝
        // —— 队列 + 状态机（`DesktopDownloads.manager`）、存储（0600 的 JSON）、下载目录文案。
        // 文件类下载那半传 null：桌面还没有那张登记表（如实，见 `DownloadedFilesHost` 的 KDoc）。
        AppRoute.DownloadManager -> DownloadManagerScreen(
            manager = DesktopDownloads.manager,
            onBack = back,
            dirInfo = DesktopDownloads.dirInfo,
            files = null,
            onPlayTask = { task -> DesktopDownloads.openDownloadedFile(task.filePath) },
        )
        // 设置（第 27 条真数据路由）：屏在 `:core`（`SettingsScreen`，与 `:app` 同一份），
        // 这一端只装配两样：落盘的偏好存储（`desktopSettings`）与宿主槽位（`DesktopSettingsHost`，
        // 声明这一端**真会照着办**的行）。宿主没有的那几组（通知与提醒 / 数据与隐私 / 更新）
        // 整组不画 —— 见那个类的 KDoc 里那张表与 TODO。
        AppRoute.Settings -> SettingsScreen(
            store = desktopSettings,
            host = remember { DesktopSettingsHost(onAppearanceChanged) },
            onBack = back,
        )
        // 账号管理（多账号，第 29 条真数据路由）：屏本轮搬进 `:core`
        //（`account/AccountManagerScreen.kt`，与 `:app` 同一份），它只认 `AccountsController` 那条缝
        // —— 桌面这侧是 [DesktopAccounts]（切/增/删真落盘：账号表在 `AccountStore`，凭据与
        // 会话按账号后缀分文件）。
        //
        // 两处如实差异：
        //  - 头像照片那一格不传（`accountPhoto = null`）⇒ 画首字母圆：学工系统的证件照缓存
        //    （`HelloProfileStore`）是 `:app` 的东西，桌面没有它；
        //  - 昵称一直是空 ⇒ 列表里显示学号（见 `DesktopAccounts` 的「如实降级」那段）。
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
        // 跳一跳：纯 UI（不需要任何取数）。远景地标图**不传** —— 那是平台资源
        //（`:app` = R.drawable.hop_landmark_*，Web = 它自己的取图），桌面端没有那份资源；
        // 屏自己写着「空列表 = 本端没图，就不画远景（游戏照常可玩）」，所以这是如实降级、不是坏掉。
        AppRoute.GameHop -> HopScreen(onBack = back)
        AppRoute.GameGo -> GoScreen(onBack = back)
        // WebVPN 转换器（第 28 条真数据路由）：这一屏**不需要任何校园会话**，也一条请求都不发
        // —— 它是本机算的字符串互转（域名 AES-128-CFB 加密 / 反向解密）。屏本轮搬进 `:core`
        //（`webvpn/WebVpnConverterScreen.kt`），转换本体仍是 `:data` 的 `WebVpnUtil`，按
        // `:core` 的 `WebVpnCodec` 缝注进去 —— Android 与桌面共用同一份实现。
        //
        // 与 `:app` 唯一不同的地方（如实）：「用 WebVPN 打开」那个按钮的去向。`:app` 进它自带的
        // 内置浏览器（自动复用 WebVPN 会话）；桌面端没有内置浏览器 ⇒ 交给系统浏览器。
        AppRoute.WebVpnConverter -> WebVpnConverterScreen(
            codec = WebVpnUtil,
            onBack = back,
            onOpenWithWebVpn = { url -> openInBrowser(url) },
        )

        // 内置浏览器（`AppRoute.Browser`）：这一屏在 `:app` 是 WebView 里的内置浏览器（顶栏地址栏 /
        // 前进后退 / 往 WebView 里注 App 已登好的校园会话 / `then=` 那一跳 / 夜间模式），桌面端没有
        // WebView 这一层 ⇒ 这一端的等价物是**外链中转页**（`DesktopBrowser.kt`）：把地址交给系统
        // 浏览器，本窗只给去向（与上面那条「用 WebVPN 打开」、以及 `DesktopPayPage` 同一套口径）。
        //
        // 消息收纳与公告里的外链**不走这条路由**：那几处是 `onOpenUrl` 槽位，直接把 url 交给系统
        // 浏览器（与 `:app` 同一条口径）。这一支是深链（`?route=browser?url=…`）与索引页点进来的那一屏。
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

        // 匹配交友（`AppRoute.Match`，第 33 条真数据路由，也是最后一条从「说明页」变真屏的路由）。
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
        )
        // 屁岱（`AppRoute.Agent`，最后一条点亮的路由）：屏与调度循环（`AgentRunner`）在 `:core`，
        // 这一端交的是宿主面与工具层（配置 / 会话 / 记忆三份真落盘、`:data` 的 SSE 传输、
        // 五件接得上的工具），见 `DesktopAgentHost`。
        //
        // 不套 `DesktopSiteGate`：`AppRoute.Agent.loginType` 是 null，而这一端接的两档取数
        //（黄页 / 教师检索）都是**免登录**的公开门户接口；会话本身要的那几档（闹钟 / 应用设置 /
        // 自建日程）这一端没有，也就不会去建任何校园会话。整个外壳本来就只在登录之后才画这些屏。
        AppRoute.Agent -> DesktopAgentScreen(agentHost, onNavigate)

        // 其余路由**如实说「没搬过来」**，不拿有数据的屏冒充（深链/误点看着像 bug，也分不清
        // 「没实现」与「坏了」）。
        else -> NotPortedScreen(route, onNavigate)
    }
}
/**
 * 屁岱在桌面的**宿主壳** —— `:core` 的 [AgentScreen] 自己不带顶栏：它把标题与「会话列表 / 配置」
 * 两颗按钮反向交给宿主（`:app` 那边画在 `MainTopAppBar` 里）。
 *
 * 这一端没有 `:app` 那种 Scaffold + TopAppBar（桌面的每一屏自己画头部，底栏在内容下面、不盖在
 * 上面），所以照它要的那三格拼一条最简的顶栏：左边会话列表开关、中间标题、右边配置。
 * 两样不传，这一端没有对应物：`scrollBehavior`（没有折叠式玻璃顶栏）与 `contentTopPadding`
 *（顶栏是这条 `Row` 里的普通一行，不浮在内容上）。
 */
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
            // 导航就是在壳里换一屏（桌面没有内置浏览器这一层，与其余屏的 onNavigate 同一条口径）。
            onNavigate = { route -> onNavigate(DesktopTarget.App(route)) },
            onTitleChange = { title = it },
            onActionsChange = { actions = it },
            onNavIconChange = { navIcon = it },
        )
    }
}


/**
 * 全部页面：先列**真能用**的，再列**屏在了但没数据源**的。这一页同时是 Stage A 的进度表，
 * 也是「退出登录」的唯一入口（桌面端还没有设置页 —— 那属于 Stage C）。
 */
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
            // 只说「已登录」，不显示学号/姓名（红线：身份该出现在账号页，不该出现在索引页）
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

/**
 * 「这一屏还没搬到桌面」的占位页 —— 与 `:web` 的 `NotPortedScreen` 同一套口径：
 * 把**具体哪一条没搬**、**它卡在哪**写在脸上，而不是落回某一屏假装能用。
 */
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

/**
 * 「这一屏卡的不是取数，而是宿主的某一项能力」的如实说明。
 *
 * 与 [NotPortedScreen] 分开是因为**那句话不同**：那页写的是「屏/取数还在 `:app`」——对
 * `game_merge`（卡 WebView）与 `schedule_match`（卡本机缓存）是错的。把两种缺口混在一页里，
 * 下一个人就会照着「搬一个站点就有数据源」去修这两条，而它们根本不在那条路上。
 *
 * 画法与回退入口与 [NotPortedScreen] / [MissingIdentityPage] 同一套（这是本文件的第三条同类页；
 * 差别只有标题与那几句，所以不再抄第四份布局）。
 */
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
/**
 * 「没有身份就取不了数」的如实说明 —— 现在成绩报表与本科评教两屏会走到这里。
 *
 * 它们的取数都要一个**学号**，而桌面端的学号来自登录时写进 `AccountContext.activeAccountId` 的那个值
 * （`JwappScoreViewModel` 在 App 上用 `loginState.activeUsername`，同一件事）。取不到时不拿空学号
 * 去请求 —— 教务会回一张「查无此人」的报表、或一张空卷子，看上去像屏坏了；也不直接崩，那连回退都没有了。
 *
 * 两屏整合到同一个页而不是各写一份：差别只有标题与那两句话，画法与回退入口是同一套。
 */
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

/**
 * MFA（短信二验）弹窗宿主：把 `:data` 的 `SessionManager.activeMfaRequest` 接到 `:core` 的
 * 哑视图 [MfaCodeDialog] 上。
 *
 * 与 `:app` 的 `MfaDialogHost` 逐条对齐的四件事：
 *  1. **挂载登记**：挂上时 `attachMfaHost()`，卸载时反登记 —— 没有任何宿主时
 *     `SessionManager.verifyMfaWithUser` 会直接报错，而不是占着登录锁空等（`MfaWaitTimeout` 是 150 秒，
 *     那段时间同一边其余站点全登不上）；
 *  2. **取手机号**：在 IO 上取，取不到就报错（不静默地显示一个假号码）；
 *  3. **被拒不关窗**：`rejections` 一涨就清空输入并提示重输（最多 3 次，`SessionManager` 那边数）；
 *  4. **取消必须回传**：`req.cancel()` —— 否则登录锁一直挂着。
 */
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


/**
 * 宿舍电费的「浏览器」槽位（桌面端）—— 见 `DesktopPage` 里那条 `AppRoute.DormPower` 分支的 KDoc。
 *
 * 桌面端没有内置浏览器：拿到地址（非 null）就用系统浏览器打开，本窗只给一句去向；
 * 地址还在路上（null）时显示等待（与 `:app` 里「先出浏览器外壳」同一句语义，只是外壳换成说明）。
 */
@Composable
private fun DesktopPayPage(url: String?) {
    LaunchedEffect(url) { if (url != null) openInBrowser(url) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (url == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("正在准备缴费页入口…", color = MiuixTheme.colorScheme.onBackgroundVariant, fontSize = 13.sp)
            }
        } else {
            Text(
                "缴费页已交给系统浏览器打开\n（绑定、查询、充值都在那一页里）",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
