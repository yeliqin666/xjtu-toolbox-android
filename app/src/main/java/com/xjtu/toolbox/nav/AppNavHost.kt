package com.xjtu.toolbox.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.browser.BrowserScreen
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.coupon.CouponScreen
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.dzpz.TranscriptScreen
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.feedback.FeedbackScreen
import com.xjtu.toolbox.fitness.FitnessScreen
import com.xjtu.toolbox.game.GamesScreen
import com.xjtu.toolbox.game.g2048.Gpa2048Screen
import com.xjtu.toolbox.game.go.GoScreen
import com.xjtu.toolbox.game.gomoku.GomokuScreen
import com.xjtu.toolbox.game.net.rememberAppOnlineLobby
import com.xjtu.toolbox.game.merge.MergeGameScreen
import com.xjtu.toolbox.game.blocks.BlocksScreen
import com.xjtu.toolbox.game.hop.HopScreen
import com.xjtu.toolbox.game.hop.rememberHopLandmarkImages
import com.xjtu.toolbox.game.xiangqi.XiangqiScreen
import com.xjtu.toolbox.iclassface.IclassfaceScreen
import com.xjtu.toolbox.jiaocai.JiaocaiScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1Screen
import com.xjtu.toolbox.judge.GraduateJudgeScreen
import com.xjtu.toolbox.judge.JudgeScreen
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.main.AppRouter
import com.xjtu.toolbox.media.DownloadManagerScreen
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.score.appScoreReportCache
import com.xjtu.toolbox.score.appScoreReportSource
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.social.MatchScreen
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.dormpower.DormPowerScreen
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.yellowpage.appYellowPageApi
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import kotlin.reflect.KClass

/**
 * 返回栈里每种页面画什么；跳转一律交给 [router]。转场用 miuix-nav 默认，首页格子进来的页面从那一格放大。
 *
 * @param mainContent 栈底的主界面
 * @param onOpenWithWebVpn WebVPN 转换页「用 WebVPN 打开」
 */
@Composable
fun AppNavHost(
    backStack: NavBackStack,
    router: AppRouter,
    loginState: AppLoginState,
    credentialStore: CredentialStore,
    accountManager: AccountManager,
    onOpenWithWebVpn: (String) -> Unit,
    mainContent: @Composable () -> Unit,
) {
    // 放大转场按页面类型缓存，每次重组换新对象会让页面元数据跟着变
    val screenCornerPx = with(LocalDensity.current) { rememberNavSystemCornerRadius().toPx() }
    val expandTransitions = remember(screenCornerPx) { mutableMapOf<KClass<out AppRoute>, NavTransition>() }
    fun expand(type: KClass<out AppRoute>): NavTransition =
        expandTransitions.getOrPut(type) { expandFromOrigin(type, screenCornerPx) }

    val back: () -> Unit = router::back

    /** 拿站点会话，拿不到（被清掉、切了账号）就退回。 */
    @Composable
    fun WithSite(siteKey: String, content: @Composable (SiteSession) -> Unit) {
        val site = loginState.sessionManager?.getSiteOrNull(siteKey)
        if (site != null) content(site) else LaunchedEffect(Unit) { back() }
    }

    NavDisplay(backStack = backStack, onBack = back) {
        entry<AppRoute.Main> { mainContent() }

        entry<AppRoute.EmptyRoom>(transition = expand(AppRoute.EmptyRoom::class)) {
            EmptyRoomScreen(onBack = back, sessionManager = loginState.sessionManager)
        }
        entry<AppRoute.Notification>(transition = expand(AppRoute.Notification::class)) {
            NotificationScreen(onBack = back, onNavigate = router::open)
        }
        entry<AppRoute.Attendance>(transition = expand(AppRoute.Attendance::class)) {
            WithSite("new_attendance") { site ->
                AttendanceScreen(
                    site = site,
                    onBack = back,
                    onOpenIclassface = { router.open(AppRoute.Iclassface) },
                )
            }
        }
        entry<AppRoute.JwappScore>(transition = expand(AppRoute.JwappScore::class)) {
            JwappScoreScreen(
                site = loginState.sessionManager?.getSiteOrNull("jwapp"),
                jwxtSite = loginState.sessionManager?.getSiteOrNull("jwxt"),
                studentId = loginState.activeUsername,
                onBack = back,
                onOpenReport = { router.open(AppRoute.ScoreReport) },
                // 成绩单只有本科生的流程，研究生不显示入口
                onOpenTranscript = if (loginState.accountType == AccountType.UNDERGRADUATE) { { router.open(AppRoute.Transcript) } } else null,
            )
        }
        entry<AppRoute.Judge>(transition = expand(AppRoute.Judge::class)) {
            val sm = loginState.sessionManager
            when {
                sm == null -> LaunchedEffect(Unit) { back() }
                isPostgraduateSession() -> GraduateJudgeScreen(sessionManager = sm, onBack = back)
                else -> WithSite("jwxt") { JudgeScreen(site = it, username = loginState.activeUsername, onBack = back) }
            }
        }
        entry<AppRoute.Library>(transition = expand(AppRoute.Library::class)) {
            WithSite("library") { LibraryScreen(site = it, onBack = back) }
        }
        entry<AppRoute.CampusCard>(transition = expand(AppRoute.CampusCard::class)) {
            AwaitSite(loginState, "campus_card", onTimeout = back) {
                CampusCardScreen(
                    site = it,
                    onBack = back,
                    // 跟着设置实时变：直接读存储只在进页那一刻读一次，页面开着时切风格不会跟过来
                    glass = com.xjtu.toolbox.ui.glass.LocalGlassStyle.current,
                    onOpenCoupon = { router.open(AppRoute.Coupon) },
                )
            }
        }
        entry<AppRoute.Coupon>(transition = expand(AppRoute.Coupon::class)) {
            WithSite("coupon") { CouponScreen(site = it, onBack = back) }
        }
        entry<AppRoute.ScoreReport>(transition = expand(AppRoute.ScoreReport::class)) {
            // 成绩报表屏已搬进 :core；这里注入取数（帆软报表）与缓存（DataCache）——
            // 两者都在 score/ScoreReportApp.kt 里装配，学号与账号隔离留在 :app 这一侧。
            WithSite("jwxt") { site ->
                val scoreContext = LocalContext.current
                val studentId = loginState.activeUsername
                ScoreReportScreen(
                    source = remember(site, studentId) { appScoreReportSource(site, studentId) },
                    onBack = back,
                    cache = remember(loginState.accountId, studentId) {
                        appScoreReportCache(scoreContext, loginState.accountId.takeIf { it.isNotEmpty() }, studentId)
                    },
                )
            }
        }
        entry<AppRoute.Transcript>(transition = expand(AppRoute.Transcript::class)) {
            WithSite("dzpz") { TranscriptScreen(site = it, onBack = back) }
        }
        entry<AppRoute.Venue>(transition = expand(AppRoute.Venue::class)) {
            WithSite("venue") { VenueScreen(
                    site = it,
                    credentialStore = credentialStore,
                    onOpenBrowser = { url, then -> router.open(AppRoute.Browser(url, then)) },
                    onBack = back,
                ) }
        }
        entry<AppRoute.DormPower>(transition = expand(AppRoute.DormPower::class)) {
            WithSite("ssn") { site ->
                DormPowerScreen(site = site, onBack = back) { url ->
                    BrowserScreen(
                        initialUrl = url.orEmpty(),
                        waiting = url == null,
                        site = site,
                        cookieClient = if (url?.contains("webvpn.xjtu.edu.cn", ignoreCase = true) == true) loginState.webVpnClientOrNull else null,
                        extraCookieDomains = listOfNotNull(url?.let { Uri.parse(it).host }),
                        onBack = back,
                    )
                }
            }
        }
        entry<AppRoute.DownloadManager>(transition = expand(AppRoute.DownloadManager::class)) {
            DownloadManagerScreen(onBack = back)
        }
        entry<AppRoute.Lms>(transition = expand(AppRoute.Lms::class)) { route ->
            WithSite("lms") { LmsScreen(site = it, onBack = back, initialCourseId = route.courseId) }
        }
        entry<AppRoute.Jiaocai>(transition = expand(AppRoute.Jiaocai::class)) {
            WithSite("jiaocai") {
                JiaocaiScreen(
                    site = it,
                    onBack = back,
                    onOpenFullText = { ssno, title -> router.open(AppRoute.Jiaocai1Reader(ssno, title)) },
                )
            }
        }
        entry<AppRoute.Jiaocai1>(transition = expand(AppRoute.Jiaocai1::class)) {
            WithSite("jiaocai") {
                Jiaocai1Screen(
                    site = it,
                    onBack = back,
                    onOpenBook = { ssno, title -> router.open(AppRoute.Jiaocai1Reader(ssno, title)) },
                )
            }
        }
        // 横向翻页，关掉页内侧滑返回
        entry<AppRoute.Jiaocai1Reader>(
            transition = expand(AppRoute.Jiaocai1Reader::class),
            swipeDismiss = NavSwipeDirection.None,
        ) { reader ->
            WithSite("jiaocai") {
                Jiaocai1ReaderScreen(site = it, ssno = reader.ssno, fallbackTitle = reader.title, onBack = back)
            }
        }
        entry<AppRoute.SchoolCourse>(transition = expand(AppRoute.SchoolCourse::class)) {
            SchoolCourseScreen(site = loginState.sessionManager?.getSiteOrNull("jwxt"), onBack = back)
        }
        entry<AppRoute.SchoolCalendar>(transition = expand(AppRoute.SchoolCalendar::class)) {
            // 校历屏已搬进 :core；这里只注入取数实现与原图槽（见 calendar/SchoolCalendarImageSlot.kt）。
            SchoolCalendarScreen(
                source = remember { com.xjtu.toolbox.calendar.SchoolCalendarApi() },
                onBack = back,
                calendarImage = { year -> com.xjtu.toolbox.calendar.AppSchoolCalendarImage(year) },
            )
        }
        entry<AppRoute.YellowPage>(transition = expand(AppRoute.YellowPage::class)) {
            // 黄页屏已搬进 :core；传数与缓存装配留在 :app（见 yellowpage/YellowPageApp.kt）。
            // remember 的 key 仍是 accountId：DataCache 按账号隔离，切账号要换新实例。
            val yellowPageContext = LocalContext.current
            val yellowPageApi = remember(loginState.accountId) {
                appYellowPageApi(yellowPageContext, loginState.accountId.takeIf { it.isNotEmpty() })
            }
            YellowPageScreen(
                api = yellowPageApi,
                onBack = back,
                errorText = { FriendlyError.of(it, "加载") },
            )
        }
        entry<AppRoute.Fitness>(transition = expand(AppRoute.Fitness::class)) {
            // 体测屏已搬进 :core；这里只注入取数实现（v3+legacy 两条路仍留在 :app 的 FitnessApi）。
            WithSite("fitness") { site ->
                FitnessScreen(
                    source = remember(site) { com.xjtu.toolbox.fitness.FitnessApi(site) },
                    onBack = back,
                )
            }
        }
        entry<AppRoute.Iclassface>(transition = expand(AppRoute.Iclassface::class)) {
            WithSite("iclassface") { IclassfaceScreen(site = it, onBack = back) }
        }
        // WebView 常有横向滚动，关掉页内侧滑返回
        entry<AppRoute.Browser>(
            transition = expand(AppRoute.Browser::class),
            swipeDismiss = NavSwipeDirection.None,
        ) { browser ->
            val url = browser.url
            val host = remember(url) { runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() }
            BrowserScreen(
                initialUrl = url,
                thenUrl = browser.then,
                site = loginState.sessionManager?.getSiteOrNull(siteKeyForHost(host.orEmpty()))
                    ?: loginState.sessionManager?.getSiteOrNull("jwxt"),
                cookieClient = if (url.contains("webvpn.xjtu.edu.cn", ignoreCase = true)) {
                    loginState.webVpnClientOrNull
                } else {
                    null
                },
                extraCookieDomains = listOfNotNull(host),
                onBack = back,
            )
        }
        entry<AppRoute.Settings>(transition = expand(AppRoute.Settings::class)) {
            SettingsScreen(credentialStore = credentialStore, onBack = back)
        }
        entry<AppRoute.Inbox>(transition = expand(AppRoute.Inbox::class)) {
            com.xjtu.toolbox.inbox.InboxScreen(onBack = back, onOpen = { router.open(it) })
        }
        entry<AppRoute.Community>(transition = expand(AppRoute.Community::class)) {
            // 社区那几屏已搬进 :core：登录态（加密偏好）与设备码登录（okhttp）留在 :app，
            // 由导航层注入 —— 与 GithubDiscussionsRepository 是同一条缝，界面不再自己 get(context)。
            val communityContext = LocalContext.current
            CommunityScreen(
                session = remember { com.xjtu.toolbox.community.PrefsGithubSession.get(communityContext) },
                deviceAuth = remember {
                    com.xjtu.toolbox.community.GithubOAuthDeviceAuthRepository(
                        com.xjtu.toolbox.community.GithubNetwork.client,
                        com.xjtu.toolbox.community.CommunityRepo.CLIENT_ID,
                    )
                },
                onBack = back,
                onOpenLegacyFeedback = { router.open(AppRoute.Feedback) },
            )
        }
        entry<AppRoute.Feedback>(transition = expand(AppRoute.Feedback::class)) {
            FeedbackScreen(onBack = back)
        }
        entry<AppRoute.Faculty>(transition = expand(AppRoute.Faculty::class)) {
            FacultyScreen(onBack = back, onOpenUrl = { url -> router.open(AppRoute.Browser(url)) })
        }

        entry<AppRoute.Games>(transition = expand(AppRoute.Games::class)) {
            GamesScreen(onBack = back, onNavigate = router::open)
        }
        entry<AppRoute.Game2048>(transition = expand(AppRoute.Game2048::class)) {
            Gpa2048Screen(onBack = back)
        }
        entry<AppRoute.GameMerge>(transition = expand(AppRoute.GameMerge::class), swipeDismiss = NavSwipeDirection.None) {
            MergeGameScreen(onBack = back)
        }
        entry<AppRoute.GameBlocks>(transition = expand(AppRoute.GameBlocks::class), swipeDismiss = NavSwipeDirection.None) {
            BlocksScreen(onBack = back)
        }
        entry<AppRoute.GameHop>(transition = expand(AppRoute.GameHop::class), swipeDismiss = NavSwipeDirection.None) {
            HopScreen(onBack = back, landmarkImages = rememberHopLandmarkImages())
        }
        entry<AppRoute.GameGomoku>(transition = expand(AppRoute.GameGomoku::class), swipeDismiss = NavSwipeDirection.None) {
            GomokuScreen(onBack = back, onlineLobby = rememberAppOnlineLobby())
        }
        entry<AppRoute.GameGo>(transition = expand(AppRoute.GameGo::class), swipeDismiss = NavSwipeDirection.None) {
            GoScreen(onBack = back, onlineLobby = rememberAppOnlineLobby())
        }
        entry<AppRoute.GameXiangqi>(transition = expand(AppRoute.GameXiangqi::class), swipeDismiss = NavSwipeDirection.None) {
            XiangqiScreen(onBack = back)
        }
        entry<AppRoute.Match>(transition = expand(AppRoute.Match::class)) {
            MatchScreen(onBack = back)
        }
        entry<AppRoute.Accounts>(transition = expand(AppRoute.Accounts::class)) {
            AccountManagerScreen(accountManager = accountManager, loginState = loginState, onBack = back)
        }
        entry<AppRoute.WebVpnConverter>(transition = expand(AppRoute.WebVpnConverter::class)) {
            WebVpnConverterScreen(
                onBack = back,
                onOpenWithWebVpn = onOpenWithWebVpn,
            )
        }
        // 日程、屁岱是底栏 tab，付款码是覆盖层，都不进返回栈（见 AppRouter.open）
    }
}

/** 等站点会话出现再显示（刚登上时可能晚一两帧注册），约 1.5 秒还没有就 [onTimeout]。 */
@Composable
fun AwaitSite(
    loginState: AppLoginState,
    siteKey: String,
    onTimeout: () -> Unit,
    content: @Composable (SiteSession) -> Unit,
) {
    var site by remember { mutableStateOf(loginState.sessionManager?.getSiteOrNull(siteKey)) }
    val ready = site
    if (ready != null) {
        content(ready)
    } else {
        LaunchedEffect(Unit) {
            repeat(12) {
                delay(120)
                loginState.sessionManager?.getSiteOrNull(siteKey)?.let {
                    site = it
                    return@LaunchedEffect
                }
            }
            onTimeout()
        }
    }
}

/** 内置浏览器打开某个站点时带哪套登录会话的 cookie。 */
private fun siteKeyForHost(host: String): String = when {
    "tyxylp.xjtu.edu.cn" in host -> "fitness"
    "rg.lib.xjtu.edu.cn" in host -> "library"
    "jwapp.xjtu.edu.cn" in host -> "jwapp"
    "ywtb.xjtu.edu.cn" in host -> "ywtb"
    "ncard.xjtu.edu.cn" in host -> "campus_card"
    "kq.xjtu.edu.cn" in host -> "new_attendance"
    "lms.xjtu.edu.cn" in host -> "lms"
    "202.117.17.144" in host -> "venue"
    "ssn.xjtu.edu.cn" in host -> "ssn"
    else -> "jwxt"
}
