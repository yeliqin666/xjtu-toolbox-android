package com.xjtu.toolbox.nav

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.createSavedStateHandle
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.browser.BrowserScreen
import com.xjtu.toolbox.calendar.SchoolCalendarScreen
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.appCampusCardStore
import com.xjtu.toolbox.card.CampusCardScreen
import com.xjtu.toolbox.card.CouponEntryStat
import com.xjtu.toolbox.community.CommunityScreen
import com.xjtu.toolbox.coupon.CouponScreen
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.dzpz.TranscriptScreen
import com.xjtu.toolbox.emptyroom.AppEmptyRoomSource
import com.xjtu.toolbox.emptyroom.EmptyRoomCache
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyAvatar
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
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.jiaocai.JiaocaiScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen
import com.xjtu.toolbox.jiaocai1.Jiaocai1Screen
import com.xjtu.toolbox.judge.GraduateJudgeScreen
import com.xjtu.toolbox.judge.JudgeScreen
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.library.AppLibrarySource
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.library.LibraryStatus
import com.xjtu.toolbox.lms.LmsScreen
import com.xjtu.toolbox.main.AppRouter
import com.xjtu.toolbox.media.DownloadManagerScreen
import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.score.appScoreReportCache
import com.xjtu.toolbox.score.scoreReportSource
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.social.MatchScreen
import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.SliderCaptchaView
import com.xjtu.toolbox.venue.SolvedCaptcha
import com.xjtu.toolbox.venue.VenueCaptchaSolver
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.dormpower.DormPowerScreen
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.yellowpage.YellowPageScreen
import com.xjtu.toolbox.yellowpage.appYellowPageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
            // 取数从 `:core` 的 EmptyRoomScreen 里挪到这里的 AppEmptyRoomSource（三档全保留，行为不变）；
            // 它现在住在 `:data`（桌面端第 10 条真数据路由），落盘改成 `:core` 的 EmptyRoomStore 缝 ——
            // `:app` 传的就是原来那份 `EmptyRoomCache`（一份文件、一个键、一个 TTL 都没动）。
            // 「CDN 说明读没读过」仍然存在 CredentialStore 里（与搬之前同一个键），屏只收一个初值 + 一个回写回调。
            val context = LocalContext.current
            val credentialStore = remember(context) { CredentialStore(context) }
            EmptyRoomScreen(
                source = remember { AppEmptyRoomSource(loginState.sessionManager, EmptyRoomCache(context)) },
                accountType = remember { credentialStore.accountType },
                onBack = back,
                showCdnTip = !credentialStore.hasReadEmptyRoomCdnTip,
                onCdnTipRead = { credentialStore.hasReadEmptyRoomCdnTip = true },
            )
        }
        entry<AppRoute.Notification>(transition = expand(AppRoute.Notification::class)) {
            // 取数仍走原来的 jsoup 爬虫（AppNoticeSource 只是把它包成 :core 的端口）。
            NotificationScreen(source = remember { AppNoticeSource() }, onBack = back, onNavigate = router::open)
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
            val libraryContext = LocalContext.current
            WithSite("library") { site ->
                // 屏与 ViewModel 都在 :core（`com.xjtu.toolbox.library.LibraryScreen`），取数在 **:data**
                // （`LibraryApi` + `LibraryPages` 搬过去了，逻辑一行未改），Android 侧只留 AppLibrarySource
                // 这个宿主壳：Dispatchers.IO、平面图字节的磁盘缓存、以及不随页面取消的那个 restoreScope。
                // 座位收藏也不在这里了 —— 它进了共享的 LibraryFavorites（同一个文件/键/值类型）。
                // 四处宿主能力在这里注入，行为与搬之前一致：
                //  1. onBookingChanged —— 「我的预约」一变就往外发（提醒 / 首页信号 / 收纳待办），
                //     原来写在屏里直接调 LibraryStatus.publish(context, ...)；
                //  2. reAuthenticate —— 原来屏自己读 LocalAppLoginState 的凭据再 site.ensureLogin(force = true)；
                //  3. landscapeLock —— 全屏看座位图时把 Activity 转横屏、关掉时恢复原来的方向；
                //  4. 首次使用提示读没读过（feature_hints 里那个 library_hint_shown，宿主读初值 + 回写）。
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
                    // 全屏那个 Dialog 的窗口属性：Android 侧这行与搬迁前逐字一致（多一个 decorFitsSystemWindows）
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
                // 屏与 ViewModel 都在 :core（`com.xjtu.toolbox.card.CampusCardScreen`），取数从那里挪到
                // 屏与 ViewModel 都在 :core（`com.xjtu.toolbox.card.CampusCardScreen`），取数现在是 `:data` 的
                // AppCampusCardSource（`CampusCardApi` 一行未改）；它唯一的宿主依赖是落盘，这里注入 :app 那份
                // （`appCampusCardStore` 就是原来的 `CampusCardCache`，文件与 key 一个没动）。
                //  1. savedState —— 与原来一样用 createSavedStateHandle()（时间范围能跟着进程恢复）；
                //  2. onCacheUpdated —— 首页 tab 的缓存版本号（原来写在屏里直接读 LocalAppLoginState）；
                //  3. onBalanceChanged —— 桌面小组件刷新（原来由 VM 直接调 CampusCardWidgetUpdater）；
                //  4. couponStat —— 「加餐券」入口的状态（原来屏自己读 HomeStats.pushed）。
                val cardContext = LocalContext.current
                CampusCardScreen(
                    source = remember(site) { AppCampusCardSource(site, appCampusCardStore(cardContext)) },
                    onBack = back,
                    // 跟着设置实时变：直接读存储只在进页那一刻读一次，页面开着时切风格不会跟过来
                    glass = com.xjtu.toolbox.ui.glass.LocalGlassStyle.current,
                    onOpenCoupon = { router.open(AppRoute.Coupon) },
                    onCacheUpdated = { loginState.campusCardCacheVersion++ },
                    onBalanceChanged = {
                        com.xjtu.toolbox.widget.CampusCardWidgetUpdater.requestUpdate(cardContext)
                    },
                    couponStat = {
                        withContext(Dispatchers.IO) {
                            HomeStats.pushed(cardContext, AppRoute.Coupon)?.let { stat ->
                                CouponEntryStat(stat.value, stat.detail)
                            }
                        }
                    },
                    savedState = { createSavedStateHandle() },
                )
            }
        }
        entry<AppRoute.Coupon>(transition = expand(AppRoute.Coupon::class)) {
            WithSite("coupon") { CouponScreen(site = it, onBack = back) }
        }
        entry<AppRoute.ScoreReport>(transition = expand(AppRoute.ScoreReport::class)) {
            // 成绩报表屏已搬进 :core；这里注入取数（帆软报表）与缓存（DataCache）——
            // 取数（帆软报表）已搬进 :data（与 ScoreReportApi 同包，桌面端用同一份）；
            // 缓存（DataCache）还在 :app —— 学号与账号隔离留在这一侧。
            WithSite("jwxt") { site ->
                val scoreContext = LocalContext.current
                val studentId = loginState.activeUsername
                ScoreReportScreen(
                    source = remember(site, studentId) { scoreReportSource(site, studentId) },
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
            // 取数搬进 `:data`（`AppVenueSource` 包住原来的 `VenueApi`，行数没变）；收藏搬进
            // `:core` 的 `VenueFavorites`（`KeyValueStore`，**同一份文件、同一个键名** ⇒ 老收藏不丢）；
            // 滑块控件与自动识别器是**屏上的两个槽位**
            // （它们长在 `Bitmap`/`Base64` 上，搬不进 `:core`），在这里注入。
            //
            // 三处宿主能力：
            //  - 「自动识别验证码」设置项仍然是**现读** `CredentialStore`（搬之前 VM 就是这么每次预订
            //    现读一次的，关掉后下一次预订立刻生效）——lambda 用 `remember` 稳住，不要每次重组新建；
            //  - 「功能说明弹过了没」还是原来那个 `feature_hints` 文件里的 `venue_hint_shown` 键，
            //    改成「宿主读初值 + 一个回写回调」传进屏（与空闲教室同一套）；
            //  - 支付仍走内置浏览器（`AppRoute.Browser(url, then)`），行为与搬之前逐字一致。
            val context = LocalContext.current
            val hintPrefs = remember(context) {
                context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE)
            }
            WithSite("venue") { site ->
                VenueScreen(
                    source = remember(site) { AppVenueSource(site) },
                    onBack = back,
                    onOpenBrowser = { url, then -> router.open(AppRoute.Browser(url, then)) },
                    autoSolveCaptcha = remember(credentialStore) {
                        { credentialStore.venueAutoSolveCaptchaEnabled }
                    },
                    // 自动识别：识别器与「盖章」都在 :app（用的是 `java.time` 的 ISO_INSTANT，与手滑
                    // 那条路径同一个格式）。屏给出「验证码是什么时候出现在屏幕上的」，用来算两个时刻。
                    solveCaptcha = { data, shownAt ->
                        VenueCaptchaSolver.solve(data)?.let { solved ->
                            SolvedCaptcha(
                                sliderResult = VenueCaptchaSolver.stamp(solved.sliderResult, shownAt),
                                releaseAfterMillis = VenueCaptchaSolver.releaseAt(solved.sliderResult),
                            )
                        }
                    },
                    captchaView = { data, onSolved ->
                        SliderCaptchaView(
                            backgroundImageBase64 = data.backgroundImage,
                            sliderImageBase64 = data.sliderImage,
                            bgOriginalWidth = data.bgWidth,
                            bgOriginalHeight = data.bgHeight,
                            sliderOriginalWidth = data.sliderWidth,
                            sliderOriginalHeight = data.sliderHeight,
                            onSlideComplete = onSolved,
                        )
                    },
                    showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("venue_hint_shown", true).apply() },
                )
            }
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
            // 取数仍是原来的 SchoolCourseApi（AppSchoolCourseSource 只是把它包成 :core 的端口）；
            // 上游给全字段 ⇒ 屏上的人数/学时那几块照旧都在（Web 端才缺）。
            // 没会话就直接退回 —— 与搬迁前屏里 `site == null` 那一支逐字同义。
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
            // 取数（学校那四路）已经搬进 `:data` 的 `AppInboxSource`；屏与 store/rules 都在 `:core`。
            // 本端专属的那一条（有座位待办就现查一次图书馆）也跟着搬了，只是把「现查 + 发出」
            // 做成一条构造参数传进去 —— `Context` 与 `LibraryStatus` 都留在 `:app`。
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
            )
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
            // 取数仍是同一个 FacultyApi（FacultyApiSource 只是把它包成 :core 的端口）——
            // 两者本轮都搬进了 :data（Stage A 收尾）：同包同名解析到新家，这里只改了适配器的名字；
            // 头像仍是原来的 FacultyAvatar（BitmapFactory + LruCache），行为逐字不变。
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
