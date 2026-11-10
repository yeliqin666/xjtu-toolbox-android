package com.xjtu.toolbox.nav

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.createSavedStateHandle
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.account.AppAccountPhotoSource
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.attendance.AppAttendanceSource
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.browser.BrowserScreen
import com.xjtu.toolbox.bulletin.NoticeDetailScreen
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.appCampusCardStore
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.card.CouponEntryStat
import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.community.GithubNetwork
import com.xjtu.toolbox.community.GithubOAuthDeviceAuthRepository
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.coupon.AppCouponSource
import com.xjtu.toolbox.coupon.CouponScreen
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.dzpz.AppTranscriptSaver
import com.xjtu.toolbox.dzpz.AppTranscriptSource
import com.xjtu.toolbox.dzpz.TranscriptScreen
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.EmptyRoomCache
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyAvatar
import com.xjtu.toolbox.faculty.FacultyScreen
import com.xjtu.toolbox.feedback.AppFeedbackSource
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
import com.xjtu.toolbox.iclassface.AppIclassfaceSource
import com.xjtu.toolbox.iclassface.IclassfaceScreen
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.jiaocai.AppJiaocaiScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen
import com.xjtu.toolbox.jiaocai1.AppJiaocai1ShelfStore
import com.xjtu.toolbox.jiaocai1.AppJiaocai1Source
import com.xjtu.toolbox.judge.GraduateJudgeScreen
import com.xjtu.toolbox.judge.JudgeScreen
import com.xjtu.toolbox.jwapp.AppJwappScoreSource
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.jwapp.appJwappScoreCache
import com.xjtu.toolbox.library.AppLibrarySource
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.LibraryStatus
import com.xjtu.toolbox.lms.AppLmsCache
import com.xjtu.toolbox.lms.AppLmsSink
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.main.AgentRoutePage
import com.xjtu.toolbox.main.AppRouter
import com.xjtu.toolbox.main.ScheduleRoutePage
import com.xjtu.toolbox.main.ZyxfRoutePage
import com.xjtu.toolbox.media.AndroidVideoPlayerHost
import com.xjtu.toolbox.media.AppDownloadManagerScreen
import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.settings.rememberAppSettingsHost
import com.xjtu.toolbox.social.AppMatchScreen
import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.venue.VenueSlideCaptchaHost
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.webvpn.WebVpnUtil
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.yellowpage.appYellowPageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.reflect.KClass

/** 画子页要用的那几样（压栈与放到底栏上共用）。 */
class AppPageDeps(
    val router: AppRouter,
    val loginState: AppLoginState,
    val credentialStore: CredentialStore,
    val accountManager: AccountManager,
    /** WebVPN 转换页「用 WebVPN 打开」。 */
    val onOpenWithWebVpn: (String) -> Unit,
)

/**
 * 这个路由能不能在一镜到底的独立 Activity 宿主（`SeamlessPageActivity`）里单独画：
 * 只有 [appPageEntries] 注册过 entry 的页面才行；`Main` 是栈底、`PaymentCode` 是 AppRoot 的覆盖层，都不算。
 */
fun isStandaloneHostable(route: AppRoute): Boolean = when (route) {
    is AppRoute.Main, is AppRoute.PaymentCode, is AppRoute.Schedule, is AppRoute.Agent, is AppRoute.Zyxf -> false
    is AppRoute.Lms, is AppRoute.Jiaocai1Reader, is AppRoute.Browser, is AppRoute.CommunityPost, is AppRoute.NoticeDetail -> true
    else -> route in standaloneHostedRoutes
}

/** [appPageEntries] 里以 data object 形式注册过的页面（带参数的上面单独判断）。 */
private val standaloneHostedRoutes: Set<AppRoute> = setOf(
    AppRoute.EmptyRoom, AppRoute.Notification, AppRoute.Attendance, AppRoute.Judge,
    AppRoute.JwappScore, AppRoute.Library, AppRoute.CampusCard, AppRoute.Coupon,
    AppRoute.Transcript, AppRoute.Venue, AppRoute.DownloadManager, AppRoute.Jiaocai,
    AppRoute.Jiaocai1, AppRoute.SchoolCourse, AppRoute.SchoolCalendar, AppRoute.YellowPage, AppRoute.CampusBus, AppRoute.DegreeProgress,
    AppRoute.Fitness, AppRoute.Iclassface, AppRoute.Settings, AppRoute.Inbox,
    AppRoute.Community, AppRoute.Faculty, AppRoute.Accounts, AppRoute.WebVpnConverter,
    AppRoute.Games, AppRoute.Game2048, AppRoute.GameMerge, AppRoute.GameBlocks,
    AppRoute.GameHop, AppRoute.GameGomoku, AppRoute.GameGo, AppRoute.GameXiangqi,
    AppRoute.Match,
)

/**
 * 返回栈：栈底是主界面，其余子页见 [appPageEntries]。转场用 miuix-nav 默认，首页格子进来的页面从那一格放大。
 *
 * @param mainContent 栈底的主界面
 */
@Composable
fun AppNavHost(
    backStack: NavBackStack,
    deps: AppPageDeps,
    mainContent: @Composable () -> Unit,
) {
    // 放大转场按页面类型缓存，每次重组换新对象会让页面元数据跟着变
    val screenCornerPx = with(LocalDensity.current) { rememberNavSystemCornerRadius().toPx() }
    val expandTransitions = remember(screenCornerPx) { mutableMapOf<KClass<out AppRoute>, NavTransition>() }
    val back: () -> Unit = deps.router::back
    Box(Modifier.fillMaxSize()) {
        NavDisplay(backStack = backStack, onBack = back) {
            entry<AppRoute.Main> {
                val navScope = LocalNavTransitionScope.current
                // 上面的页缩回去时还算盖着，落定才恢复
                val covered by remember(navScope) { derivedStateOf { navScope.relativeDepth > 0.001f } }
                // 落定时清掉交接状态；打开中途被返回打断时退场页可能在清理之后又画一帧，两帧后再补清一次。
                // 不能持续监听：极慢的返回手势里转场每帧都在写，持续清会和它互相触发卡死主线程
                LaunchedEffect(covered, backStack.size) {
                    if (!covered && backStack.size <= 1) {
                        ExpandOrigins.clearContent()
                        androidx.compose.runtime.withFrameNanos { }
                        androidx.compose.runtime.withFrameNanos { }
                        ExpandOrigins.clearContent()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // 被上面的页盖住时算不可见：液态背景这些循环动画要停下，
                    // 否则它每帧都让整页玻璃重画，正好和转场抢时间
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.xjtu.toolbox.ui.components.LocalPageVisible provides (backStack.size <= 1 && !covered),
                    ) { mainContent() }
                    // 压暗由这一页被盖住的深度现算：页面落定时深度必为 0，不会残留
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            if (!ExpandOrigins.coveredFromOrigin.value) return@drawBehind
                            val dim = ButtonZoom.RECEDE_DIM * navScope.relativeDepth.coerceIn(0f, 1f)
                            if (dim > 0f) drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = dim))
                        },
                    )
                }
            }
            appPageEntries(deps, back) { type -> expandTransitions.getOrPut(type) { expandFromOrigin(type, screenCornerPx) } }
        }
        ExpandOriginPopOverlay(screenCornerPx)
    }
}

/**
 * 把子页 [route] 挂到底栏上画：与压栈用同一份条目（[appPageEntries]），所以任何子页都能放进底栏。
 * [onBack]（页面里的返回、会话丢了的退回）改为回首页；返回键本身由 `LocalTabRoot` 藏掉。
 */
@Composable
fun AppPageAsTab(route: AppRoute, deps: AppPageDeps, onBack: () -> Unit) {
    // 只有一页的返回栈：miuix 不公开单独取条目的接口
    val stack = remember(route) { navBackStackOf(route) }
    NavDisplay(backStack = stack, onBack = onBack) {
        appPageEntries(deps, onBack) { expandFromOrigin(it, 0f) }
    }
}

/** 返回栈里每种子页画什么；跳转一律交给 router。[back] 是页面要退回时调的。 */
fun NavEntryBuilder.appPageEntries(
    deps: AppPageDeps,
    back: () -> Unit,
    expand: (KClass<out AppRoute>) -> NavTransition,
) {
    val router = deps.router
    val loginState = deps.loginState
    val credentialStore = deps.credentialStore
    val accountManager = deps.accountManager
    val onOpenWithWebVpn = deps.onOpenWithWebVpn

    /** 拿站点会话，拿不到（被清掉、切了账号）就退回。 */
    @Composable
    fun WithSite(siteKey: String, content: @Composable (SiteSession) -> Unit) {
        val site = loginState.sessionManager?.getSiteOrNull(siteKey)
        if (site != null) content(site) else LaunchedEffect(Unit) { back() }
    }

    run {
        // 底栏那几格被用户移出底栏后，作为普通页压栈
        entry<AppRoute.Schedule>(transition = expand(AppRoute.Schedule::class)) {
            ScheduleRoutePage(loginState, onNavigate = router::open, onBack = back)
        }
        entry<AppRoute.Agent>(transition = expand(AppRoute.Agent::class)) {
            AgentRoutePage(loginState, onNavigate = router::open, onBack = back)
        }
        entry<AppRoute.Zyxf>(transition = expand(AppRoute.Zyxf::class)) {
            ZyxfRoutePage(onBack = back)
        }

        entry<AppRoute.EmptyRoom>(transition = expand(AppRoute.EmptyRoom::class)) {
            val context = LocalContext.current
            EmptyRoomScreen(
                source = remember { AppEmptyRoomSource(loginState.sessionManager, EmptyRoomCache(context)) },
                accountType = remember { credentialStore.accountType },
                onBack = back,
                showCdnTip = !credentialStore.hasReadEmptyRoomCdnTip,
                onCdnTipRead = { credentialStore.hasReadEmptyRoomCdnTip = true },
            )
        }
        entry<AppRoute.Notification>(transition = expand(AppRoute.Notification::class)) {
            NotificationScreen(
                source = remember { AppNoticeSource() },
                onBack = back,
                onNavigate = router::open,
                onAskAgent = { sendToPidai(it, router::open) },
            )
        }
        entry<AppRoute.Attendance>(transition = expand(AppRoute.Attendance::class)) {
            val attendanceContext = LocalContext.current
            WithSite("new_attendance") { site ->
                AttendanceScreen(
                    source = remember(site) { AppAttendanceSource(site) },
                    onBack = back,
                    onOpenIclassface = { router.open(AppRoute.Iclassface) },
                    savedState = { createSavedStateHandle() },
                    pickEvidenceFiles = rememberPickEvidenceFiles(attendanceContext),
                )
            }
        }
        entry<AppRoute.JwappScore>(transition = expand(AppRoute.JwappScore::class)) {
            val scoreContext = LocalContext.current
            val scoreSource = remember(loginState.sessionManager) {
                loginState.sessionManager?.let { AppJwappScoreSource(it) }
            }
            JwappScoreScreen(
                source = scoreSource,
                studentId = loginState.activeUsername,
                onBack = back,
                // 成绩单只有本科生的流程，研究生不显示入口
                onOpenTranscript = if (loginState.accountType == AccountType.UNDERGRADUATE) { { router.open(AppRoute.Transcript) } } else null,
                jwxt = scoreSource,
                cache = remember(scoreContext, loginState.accountId) {
                    appJwappScoreCache(scoreContext, loginState.accountId)
                },
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
            val libraryContext = LocalContext.current
            WithSite("library") { site ->
                val hintPrefs = remember(libraryContext) {
                    libraryContext.getSharedPreferences("feature_hints", Context.MODE_PRIVATE)
                }
                LibraryScreen(
                    source = remember(site) { AppLibrarySource(site, libraryContext) },
                    onBack = back,
                    onBookingChanged = { LibraryStatus.publish(libraryContext, it) },
                    reAuthenticate = {
                        val creds = loginState.sessionManager?.credentials ?: error("未配置凭据")
                        withContext(Dispatchers.IO) {
                            site.ensureLogin(creds.first, creds.second, force = true, userInitiated = true)
                        }
                    },
                    landscapeLock = { landscape ->
                        val activity = LocalActivity.current
                        DisposableEffect(landscape) {
                            val prev = activity?.requestedOrientation
                            if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            onDispose { if (prev != null) activity?.requestedOrientation = prev }
                        }
                    },
                    fullscreenDialogProperties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                    showFirstUseHint = !hintPrefs.getBoolean("library_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("library_hint_shown", true).apply() },
                )
            }
        }
        entry<AppRoute.CampusCard>(transition = expand(AppRoute.CampusCard::class)) {
            AwaitSite(loginState, "campus_card", onTimeout = back) { site ->
                val cardContext = LocalContext.current
                CampusCardScreen(
                    source = remember(site) { AppCampusCardSource(site, appCampusCardStore(cardContext)) },
                    onBack = back,
                    // 跟着设置实时变：直接读存储只在进页那一刻读一次，页面开着时切风格不会跟过来
                    glass = com.xjtu.toolbox.ui.glass.LocalGlassStyle.current,
                    onOpenCoupon = { router.open(AppRoute.Coupon) },
                    mealReportExport = remember(cardContext) { com.xjtu.toolbox.card.appMealReportExport(cardContext) },
                    onCacheUpdated = { loginState.campusCardCacheVersion++ },
                    onBalanceChanged = {
                        com.xjtu.toolbox.widget.CampusCardWidgetUpdater.requestUpdate(cardContext)
                    },
                    couponStat = {
                        withContext(Dispatchers.IO) {
                            HomeStats.pushed(cardContext, AppRoute.Coupon)?.let { stat ->
                                CouponEntryStat(stat.value, stat.detail, stat.count ?: 0)
                            }
                        }
                    },
                    savedState = { createSavedStateHandle() },
                )
            }
        }
        entry<AppRoute.Coupon>(transition = expand(AppRoute.Coupon::class)) {
            val couponContext = LocalContext.current
            WithSite("coupon") { site ->
                CouponScreen(
                    source = remember(site) { AppCouponSource(site) },
                    onBack = back,
                    onSummary = { value, detail, count ->
                        // 只查了一类时张数不全，沿用上次算全的那个数（后台刷新会再算准）
                        val badge = count ?: HomeStats.pushed(couponContext, AppRoute.Coupon)?.count
                        HomeStats.push(couponContext, AppRoute.Coupon, value, detail, count = badge)
                    },
                )
            }
        }
        entry<AppRoute.Transcript>(transition = expand(AppRoute.Transcript::class)) {
            val transcriptContext = LocalContext.current
            WithSite("dzpz") { site ->
                TranscriptScreen(
                    source = remember(site) { AppTranscriptSource(site) },
                    onBack = back,
                    saveSink = remember(transcriptContext) { AppTranscriptSaver(transcriptContext) },
                )
            }
        }
        entry<AppRoute.Venue>(transition = expand(AppRoute.Venue::class)) {
            val context = LocalContext.current
            val hintPrefs = remember(context) {
                context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE)
            }
            WithSite("venue") { site ->
                VenueScreen(
                    source = remember(site) { AppVenueSource(site) },
                    onBack = back,
                    onOpenBrowser = { url, then -> router.open(AppRoute.Browser(url, then)) },
                    captchaHost = VenueSlideCaptchaHost,
                    showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("venue_hint_shown", true).apply() },
                )
            }
        }
        entry<AppRoute.DownloadManager>(transition = expand(AppRoute.DownloadManager::class)) {
            AppDownloadManagerScreen(onBack = back)
        }
        entry<AppRoute.Lms>(transition = expand(AppRoute.Lms::class)) { route ->
            val manager = loginState.sessionManager
            if (manager == null) {
                LaunchedEffect(Unit) { back() }
            } else {
                val lmsContext = LocalContext.current
                val accountId = loginState.accountId
                val hintPrefs = remember { lmsContext.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
                LmsScreen(
                    // 第一次取数时才登录，页面不用等
                    source = remember(manager) {
                        AppLmsSource.lazy(
                            { manager.ensureSite(requireNotNull(route.loginType), userInitiated = true) },
                            AppLmsSink(lmsContext),
                        )
                    },
                    onBack = back,
                    initialCourseId = route.courseId,
                    initialActivityId = route.activityId,
                    cacheStore = remember(accountId) { AppLmsCache(lmsContext, accountId.ifEmpty { null }) },
                    showFirstUseHint = !hintPrefs.getBoolean("lms_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("lms_hint_shown", true).apply() },
                    videoPlayer = AndroidVideoPlayerHost,
                    onAskAgent = { sendToPidai(it, router::open) },
                )
            }
        }
        entry<AppRoute.Jiaocai>(transition = expand(AppRoute.Jiaocai::class)) {
            WithSite("jiaocai") {
                AppJiaocaiScreen(
                    site = it,
                    onBack = back,
                    onOpenBook = { ssno, title -> router.open(AppRoute.Jiaocai1Reader(ssno, title)) },
                    initialTab = 0,
                    authExpiredRoute = AppRoute.Jiaocai,
                )
            }
        }
        entry<AppRoute.Jiaocai1>(transition = expand(AppRoute.Jiaocai1::class)) {
            WithSite("jiaocai") {
                AppJiaocaiScreen(
                    site = it,
                    onBack = back,
                    onOpenBook = { ssno, title -> router.open(AppRoute.Jiaocai1Reader(ssno, title)) },
                    initialTab = 2,
                    authExpiredRoute = AppRoute.Jiaocai1,
                )
            }
        }
        // 横向翻页，关掉页内侧滑返回
        entry<AppRoute.Jiaocai1Reader>(
            transition = expand(AppRoute.Jiaocai1Reader::class),
            swipeDismiss = NavSwipeDirection.None,
        ) { reader ->
            WithSite("jiaocai") {
                val jiaocai1Context = LocalContext.current
                val jiaocai1Source = remember(it) { AppJiaocai1Source(it, jiaocai1Context.cacheDir) }
                Jiaocai1ReaderScreen(
                    source = jiaocai1Source,
                    shelfStore = remember(jiaocai1Context) { AppJiaocai1ShelfStore(jiaocai1Context) },
                    ssno = reader.ssno,
                    fallbackTitle = reader.title,
                    onBack = back,
                )
            }
        }
        entry<AppRoute.SchoolCourse>(transition = expand(AppRoute.SchoolCourse::class)) {
            // 没会话就直接退回。
            val jwxtSite = loginState.sessionManager?.getSiteOrNull("jwxt")
            if (jwxtSite == null) {
                LaunchedEffect(Unit) { back() }
            } else {
                SchoolCourseScreen(
                    source = remember(jwxtSite) { AppSchoolCourseSource(jwxtSite) },
                    onBack = back,
                )
            }
        }
        entry<AppRoute.SchoolCalendar>(transition = expand(AppRoute.SchoolCalendar::class)) {
            SchoolCalendarScreen(
                source = remember { com.xjtu.toolbox.calendar.SchoolCalendarApi() },
                onBack = back,
                calendarImage = { year -> com.xjtu.toolbox.calendar.AppSchoolCalendarImage(year) },
            )
        }
        entry<AppRoute.YellowPage>(transition = expand(AppRoute.YellowPage::class)) {
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
        entry<AppRoute.DegreeProgress>(transition = expand(AppRoute.DegreeProgress::class)) {
            val degreeContext = LocalContext.current
            val degreeSource = remember(loginState.sessionManager) {
                loginState.sessionManager?.let { com.xjtu.toolbox.degree.appDegreeSource(degreeContext, it) }
            }
            if (degreeSource != null) {
                com.xjtu.toolbox.degree.DegreeScreen(
                    source = degreeSource,
                    onBack = back,
                    onAskAgent = { sendToPidai(it, router::open) },
                )
            }
        }
        entry<AppRoute.CampusBus>(transition = expand(AppRoute.CampusBus::class)) {
            com.xjtu.toolbox.bus.BusScreen(source = com.xjtu.toolbox.bus.appBusApi, onBack = back)
        }
        entry<AppRoute.Fitness>(transition = expand(AppRoute.Fitness::class)) {
            val fitnessContext = LocalContext.current
            val account = loginState.accountId.takeIf { it.isNotEmpty() }
            WithSite("fitness") { site ->
                FitnessScreen(
                    source = remember(site) { com.xjtu.toolbox.fitness.FitnessApi(site) },
                    onBack = back,
                    onScoreLoaded = { year, score ->
                        val stat = com.xjtu.toolbox.home.fitnessHomeStat(year.name, score)
                        HomeStats.push(fitnessContext, AppRoute.Fitness, stat.value, stat.detail, account, stat.lines)
                        HomeStats.markFetched(fitnessContext, AppRoute.Fitness, account)
                        com.xjtu.toolbox.home.HomeSignals.bumpStatsVersion()
                    },
                )
            }
        }
        entry<AppRoute.Iclassface>(transition = expand(AppRoute.Iclassface::class)) {
            WithSite("iclassface") { site ->
                IclassfaceScreen(
                    source = remember(site) { AppIclassfaceSource(site) },
                    onBack = back,
                )
            }
        }
        // WebView 常有横向滚动，关掉页内侧滑返回
        entry<AppRoute.Browser>(
            transition = expand(AppRoute.Browser::class),
            swipeDismiss = NavSwipeDirection.None,
        ) { browser ->
            val url = browser.url
            val host = remember(url) { runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() }
            val destinationHost = remember(browser.then) { runCatching { Uri.parse(browser.then).host?.lowercase() }.getOrNull() }
            BrowserScreen(
                initialUrl = url,
                thenUrl = browser.then,
                site = loginState.sessionManager?.getSiteOrNull(siteKeyForHost(destinationHost ?: host.orEmpty()))
                    ?: loginState.sessionManager?.getSiteOrNull("jwxt"),
                cookieClient = if (url.contains("webvpn.xjtu.edu.cn", ignoreCase = true)) {
                    loginState.webVpnClientOrNull
                } else {
                    null
                },
                extraCookieDomains = listOfNotNull(host, destinationHost),
                onAskAgent = { sendToPidai(it, router::open) },
                onBack = back,
            )
        }
        entry<AppRoute.Settings>(transition = expand(AppRoute.Settings::class)) {
            SettingsScreen(
                store = remember { SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE)) },
                host = rememberAppSettingsHost(
                    credentialStore = credentialStore,
                    onNetworkModeChanged = { loginState.onNetworkChanged() },
                ),
                onBack = back,
            )
        }
        entry<AppRoute.Inbox>(transition = expand(AppRoute.Inbox::class)) {
            val inboxContext = androidx.compose.ui.platform.LocalContext.current
            val sessionManager = loginState.sessionManager
            com.xjtu.toolbox.inbox.InboxScreen(
                source = remember(inboxContext) {
                    com.xjtu.toolbox.inbox.AppInboxSource(
                        sessionManager,
                        com.xjtu.toolbox.inbox.appInboxLibraryBooking(inboxContext, sessionManager),
                    )
                },
                onBack = back,
                onOpen = { router.open(it) },
                account = loginState.accountId.ifEmpty { null },
                onAskPidai = { item ->
                    com.xjtu.toolbox.agent.AgentPendingPrompt.set(
                        "展开说说「${item.title}」",
                        snapshot = "用户从消息收纳里你发的这条消息点进来：\n${item.title}\n${item.body}",
                    )
                    router.open(AppRoute.Agent)
                },
                onForwardToAgent = { sendToPidai(it, router::open) },
                termStart = androidx.compose.runtime.produceState<kotlinx.datetime.LocalDate?>(null, inboxContext) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTermSchedule(com.xjtu.toolbox.data.DataCache(inboxContext))?.start
                        }.getOrNull()
                    }
                }.value,
            )
        }
        // 公告详情：首页公告条「查看详情」的落点。ROM 支持金标联盟一镜到底时由
        // sharedview.NoticeDetailActivity 承载同一个屏；其余设备压这条路由，从公告卡放大进来。
        entry<AppRoute.NoticeDetail>(transition = expand(AppRoute.NoticeDetail::class)) { route ->
            NoticeDetailScreen(
                notice = route,
                onBack = back,
                onOpenUrl = { url -> router.open(AppRoute.Browser(url)) },
            )
        }
        entry<AppRoute.Community>(transition = expand(AppRoute.Community::class)) {
            CommunityScreen(
                session = remember { SecureGithubSession.get() },
                deviceAuth = remember {
                    GithubOAuthDeviceAuthRepository(
                        GithubNetwork.client,
                        CommunityRepo.CLIENT_ID,
                    )
                },
                onBack = back,
            )
        }
        entry<AppRoute.CommunityPost>(transition = expand(AppRoute.CommunityPost::class)) { route ->
            CommunityScreen(
                session = remember { SecureGithubSession.get() },
                deviceAuth = remember { GithubOAuthDeviceAuthRepository(GithubNetwork.client, CommunityRepo.CLIENT_ID) },
                onBack = back,
                discussionNumber = route.number,
            )
        }
        entry<AppRoute.Faculty>(transition = expand(AppRoute.Faculty::class)) {
            FacultyScreen(
                source = remember { FacultyApiSource() },
                onBack = back,
                onOpenUrl = { url -> router.open(AppRoute.Browser(url)) },
                avatar = { member, size -> FacultyAvatar(member, size) },
            )
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
            XiangqiScreen(onBack = back, onlineLobby = rememberAppOnlineLobby())
        }
        entry<AppRoute.Match>(transition = expand(AppRoute.Match::class)) {
            AppMatchScreen(onBack = back)
        }
        entry<AppRoute.Accounts>(transition = expand(AppRoute.Accounts::class)) {
            AccountManagerScreen(
                accountManager = accountManager,
                activeAccountId = loginState.accountId,
                accountPhoto = AppAccountPhotoSource,
                onBack = back,
            )
        }
        entry<AppRoute.WebVpnConverter>(transition = expand(AppRoute.WebVpnConverter::class)) {
            WebVpnConverterScreen(
                codec = WebVpnUtil,
                onBack = back,
                onOpenWithWebVpn = onOpenWithWebVpn,
                onOpenApply = { router.open(AppRoute.Browser(com.xjtu.toolbox.webvpn.WebVpnDenied.APPLY_URL)) },
            )
        }
        // 付款码是覆盖层，不进返回栈（见 AppRouter.open）
    }
}

/** 打开和返回都使用同一个源卡片叠加层，与目标页双向渐变。 */
@Composable
internal fun ExpandOriginPopOverlay(screenCornerPx: Float) {
    OriginContentOverlay(screenCornerPx, com.xjtu.toolbox.ui.components.AppCardColor)
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
    host == "dzpz.xjtu.edu.cn" -> "dzpz"
    "tyxylp.xjtu.edu.cn" in host -> "fitness"
    "rg.lib.xjtu.edu.cn" in host -> "library"
    "jwapp.xjtu.edu.cn" in host -> "jwapp"
    "ywtb.xjtu.edu.cn" in host || "workflow.xjtu.edu.cn" in host -> "ywtb"
    "ncard.xjtu.edu.cn" in host -> "campus_card"
    "kq.xjtu.edu.cn" in host -> "new_attendance"
    "lms.xjtu.edu.cn" in host -> "lms"
    "202.117.17.144" in host -> "venue"
    else -> "jwxt"
}

/** 请假表单「添加附件」的取文件槽位（Android 侧）：系统多选文件（SAF）。 */
@Composable
private fun rememberPickEvidenceFiles(context: Context): suspend () -> List<Pair<String, ByteArray>> {
    val pending = remember { AtomicReference<CompletableDeferred<List<Pair<String, ByteArray>>>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val loaded = uris.mapNotNull { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
            name to bytes
        }
        pending.get()?.complete(loaded)
        pending.set(null)
    }
    return suspend {
        val deferred = CompletableDeferred<List<Pair<String, ByteArray>>>()
        pending.set(deferred)
        launcher.launch(arrayOf("*/*"))
        deferred.await()
    }
}

/**
 * 把通知、活动、网页转给小岱：开新对话，问题填进输入框、页面内容挂成「附带」标签，
 * 由用户自己改一改再点发送（不替他发）。
 */
private fun sendToPidai(forward: com.xjtu.toolbox.agent.AgentForward, open: (AppRoute) -> Unit) {
    com.xjtu.toolbox.agent.AgentPendingPrompt.set(forward.text, snapshot = forward.snapshot, draftOnly = true, label = forward.label)
    open(AppRoute.Agent)
}
