package com.xjtu.toolbox.nav

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.account.AccountManagerScreen
import com.xjtu.toolbox.account.AppAccountPhotoSource
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.attendance.AppAttendanceSource
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.browser.BrowserScreen
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
import com.xjtu.toolbox.main.AppRouter
import com.xjtu.toolbox.media.AndroidVideoPlayerHost
import com.xjtu.toolbox.media.AppDownloadManagerScreen
import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.SchoolCourseScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.score.appScoreReportCache
import com.xjtu.toolbox.score.scoreReportSource
import com.xjtu.toolbox.settings.SettingsScreen
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.settings.rememberAppSettingsHost
import com.xjtu.toolbox.social.MatchScreen
import com.xjtu.toolbox.venue.AppVenueSource
import com.xjtu.toolbox.venue.VenueScreen
import com.xjtu.toolbox.venue.VenueSlideCaptchaHost
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.dormpower.DormPowerApi
import com.xjtu.toolbox.dormpower.DormPowerScreen
import com.xjtu.toolbox.webvpn.WebVpnConverterScreen
import com.xjtu.toolbox.webvpn.WebVpnUtil
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
            // 屏/VM 已搬进 `:core`（桌面端第 16 条真数据路由，与宿舍电费同一批）：屏只认
            // `:core` 的 `AttendanceSource` 端口，这里填 `:data` 的 `AppAttendanceSource` —— 与
            // 桌面端**同一份实现**（考勤流水 / 请假十条取数一个字没改）。三处宿主能力仍然在
            // 这里注入：savedState（`createSavedStateHandle`，与搬迁前同一句，选过的学期照旧恢复）、
            // pickEvidenceFiles（系统多选文件 SAF，请假的附件上传用）、onOpenIclassface。
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
            // 屏/VM 已搬进 `:core`（桌面端第 19 条真数据路由）：屏只认两个端口 —— `source`
            //（jwapp 一站）与 `jwxt`（教务一站）。`AppJwappScoreSource` 同时填这两个：它包住原来的
            // `JwappApi` / `CjcxApi` / `JudgeApi` / `ScoreReportApi`，「重试前强制重登」那一枪也
            // 从 `loginState` 移进去了（读的是同一份会话管家凭据）。缓存仍是原来的 `DataCache`
            //（同一个 key `score_all_terms`，老缓存无缝沿用），它是唯一要 `Context` 的一档。
            val scoreContext = LocalContext.current
            val scoreSource = remember(loginState.sessionManager) {
                loginState.sessionManager?.let { AppJwappScoreSource(it) }
            }
            JwappScoreScreen(
                source = scoreSource,
                studentId = loginState.activeUsername,
                onBack = back,
                onOpenReport = { router.open(AppRoute.ScoreReport) },
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
            // 屏与编排都搬进了 `:core`（`CouponScreen` + `CouponViewModel` + `CouponSource` 端口）；
            // 取数是 `:data` 的 `AppCouponSource`（包住原来的 `CouponApi`，行数没变），站点类与
            // 它的 `CouponLogin` 同批搬进 `:data`。还留在这里的只有这一端的**宿主能力**：
            // 把「待领取/待使用」摘要写回首页的 `HomeStats.push`（与搬迁前那一行逐字一致）。
            val couponContext = LocalContext.current
            WithSite("coupon") { site ->
                CouponScreen(
                    source = remember(site) { AppCouponSource(site) },
                    onBack = back,
                    onSummary = { value, detail -> HomeStats.push(couponContext, AppRoute.Coupon, value, detail) },
                )
            }
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
            // 屏与流程都搬进了 `:core`（`TranscriptScreen` + `TranscriptViewModel` + `TranscriptSource`
            // 端口）；取数是 `:data` 的 `AppTranscriptSource`（包住原来的 `TranscriptApi`，行数没变），
            // 会话仍是同一个 `dzpz` 站点（站点类与它的 `DzpzLogin` 同批搬进 `:data`，行为未改）。
            // 还留在这里的只有这一端的**宿主能力**：PDF 落盘（MediaStore + 下载管理，见 `AppTranscriptSaver`）。
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
            // 取数搬进 `:data`（`AppVenueSource` 包住原来的 `VenueApi`，行数没变）；收藏搬进
            // `:core` 的 `VenueFavorites`（`KeyValueStore`，**同一份文件、同一个键名** ⇒ 老收藏不丢）；
            // 滑块控件与自动识别器（原来这里两个 lambda）随 Stage B 的滑块共享化搬去
            // `:data`：`VenueSlideCaptchaHost` 就是它们俩的原样合成（同包同类型名，
            // Bitmap/Base64 换成 `:core` 的图片缝），行为一行不改；桌面端另有自己的宿主。
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
                    captchaHost = VenueSlideCaptchaHost,
                    showFirstUseHint = !hintPrefs.getBoolean("venue_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("venue_hint_shown", true).apply() },
                )
            }
        }
        entry<AppRoute.DormPower>(transition = expand(AppRoute.DormPower::class)) {
            // 屏已搬进 `:core`（桌面端第 17 条真数据路由，与考勤同一批）：屏只认 `:core` 的
            // `DormPowerSource` 端口，这里填 `:data` 的 `DormPowerApi` —— 与桌面端**同一份实现**
            //（`rooms` / `kwh` 两枪一个字没改，第三个方法 `freshPayUrl` 从屏上搬进来）。
            // 两处宿主能力仍然在这里注入：browser = 内置浏览器外壳；onLeave = 离开这一屏就让
            // 首页把这一路的缓存作废（原来写在屏的 DisposableEffect 里，时机未变）。
            val dormContext = LocalContext.current
            WithSite("ssn") { site ->
                DormPowerScreen(
                    source = remember(site) { DormPowerApi(site) },
                    onBack = back,
                    onLeave = { HomeStats.invalidate(dormContext, AppRoute.DormPower) },
                    browser = { url ->
                    BrowserScreen(
                        initialUrl = url.orEmpty(),
                        waiting = url == null,
                        site = site,
                        cookieClient = if (url?.contains("webvpn.xjtu.edu.cn", ignoreCase = true) == true) loginState.webVpnClientOrNull else null,
                        extraCookieDomains = listOfNotNull(url?.let { Uri.parse(it).host }),
                        onBack = back,
                    )
                }
            )
            }
        }
        entry<AppRoute.DownloadManager>(transition = expand(AppRoute.DownloadManager::class)) {
            AppDownloadManagerScreen(onBack = back)
        }
        entry<AppRoute.Lms>(transition = expand(AppRoute.Lms::class)) { route ->
            // 思源学堂整域已搬进共享内核（桌面端第 18 条真数据路由）：屏在 `:core`（同一条路由在
            // 桌面也在用），这里只填三件宿主能力 —— 取数 `AppLmsSource`（包住 `:data` 的 LmsApi，
            // 与桌面**同一份实现**）、落盘 `AppLmsSink`（MediaStore + 下载管理，方法体原样搬出）、
            // 落盘缓存 `AppLmsCache`（DataCache，按账号分命名空间，老缓存照旧读得出）、
            // 视频播放器（内置 ExoPlayer 全屏页，Android 专属 —— 桌面没有它，见 `:core` 屏的 slot）。
            WithSite("lms") { site ->
                val lmsContext = LocalContext.current
                val accountId = loginState.accountId
                val hintPrefs = remember { lmsContext.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
                LmsScreen(
                    source = remember(site) { AppLmsSource(site, AppLmsSink(lmsContext)) },
                    onBack = back,
                    initialCourseId = route.courseId,
                    cacheStore = remember(accountId) { AppLmsCache(lmsContext, accountId.ifEmpty { null }) },
                    showFirstUseHint = !hintPrefs.getBoolean("lms_hint_shown", false),
                    onFirstUseHintRead = { hintPrefs.edit().putBoolean("lms_hint_shown", true).apply() },
                    // 视频播放槽位：`:core` 的 `VideoPlayerHost` 端口，Android 这份就是 `:app/media`
                    // 那页 ExoPlayer 全屏屏（搬迁前是屏上的裸函数参，现在成一个对象的方法）。
                    videoPlayer = AndroidVideoPlayerHost,
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
                // 两条 `jiaocai` 路由共用一个宿主适配（`jiaocai/AppJiaocai.kt`）：屏与两个
                // ViewModel 都在 `:core`，取数在 `:data`，这里只有 initialTab 不同 ——
                // `JIAOCAI` 落在「查教材」栅，`JIAOCAI1` 落在「全文库」栅（与收编前同一条口径）。
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
                // 屏、书架存储与取数都已在共享内核（取数/页字节 `:data` 的 `AppJiaocai1Source`，
                // 书架 `:app` 的 `AppJiaocai1ShelfStore`（包住原来的 Room DAO），页图解码 `:core`）；
                // 宿主只负责把这两样递进去。缓存目录仍是 `context.cacheDir`（同名 `jiaocai1/` 子目录）
                // ⇒ 已有页缓存继续被认。
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
            // 屏已搬进 `:core`（桌面端第 20 条真数据路由，考勤屏上的「快速考勤流水」入口）：
            // 屏只认 `:core` 的 `IclassfaceSource` 端口，这里填 `:data` 的 `AppIclassfaceSource`
            // —— 与桌面端**同一份实现**（取数一个字没改，只是 `Dispatchers.IO` 从屏里挪进了源里）。
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
            // 屏在 `:core`（与桌面同一份）；这里只装配本端的两样：设置偏好的存储
            // （`app_settings` 那份 SharedPreferences —— 与搬迁前同一个文件、同一批键）与
            // Android 独有的那三组设置（通知与提醒 / 数据与隐私 / 更新，见 `AppSettings.kt`）。
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
            // 社区那几屏已在 `:core`；登录态（密文键值存储）与设备码登录（okhttp）本轮也搬进了
            // `:data`（桌面端第 21 条真数据路由）：`SecureGithubSession` 的存储就是**同一个
            // 文件名、同一批键**（`github_community` / `token` / `login`，Android 的 actual 仍是
            // `SecurePrefs`）⇒ 老登录态无缝沿用；设备码那几个端点与 GraphQL 的 okhttp 传输
            // （`GithubNetwork` / `GithubOAuthDeviceAuthRepository` / `HttpGithubDiscussionsRepository`）
            // 也在那里，与桌面端**同一份实现**。`：app` 因此一行装配都没有，只剩这三行注入。
            CommunityScreen(
                session = remember { SecureGithubSession.get() },
                deviceAuth = remember {
                    GithubOAuthDeviceAuthRepository(
                        GithubNetwork.client,
                        CommunityRepo.CLIENT_ID,
                    )
                },
                onBack = back,
                onOpenLegacyFeedback = { router.open(AppRoute.Feedback) },
            )
        }
        entry<AppRoute.Feedback>(transition = expand(AppRoute.Feedback::class)) {
            // 屏与落盘（`FeedbackStore`）都已在 `:core`，取数是 `:data` 的 `AppFeedbackSource`
            // （包住搬进 `:data` 的 `FeedbackApi`：飞书多维表格）。
            // `onOpenUrl` 就是搬迁前长在屏里的那两行（`Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`）——
            // 现在由宿主给：`:core` 不认识 `Intent`（桌面交给系统浏览器）。
            val feedbackContext = LocalContext.current
            FeedbackScreen(
                source = AppFeedbackSource,
                onOpenUrl = { url ->
                    runCatching {
                        feedbackContext.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onBack = back,
            )
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
            // 屏本轮搬进了 `:core`（`account/AccountManagerScreen.kt`）；动作本体（切换/新增/删除）
            // 仍在 `:app` 的 `AccountManager`（要 `Context` 清 SharedPreferences/Room/WebView 存储），
            // 它实现 `:core` 那条 `AccountsController` 缝。头像照片那一格也仍在这一端
            //（学工系统的证件照缓存）。屏的画法与文案一行未改。
            AccountManagerScreen(
                accountManager = accountManager,
                activeAccountId = loginState.accountId,
                accountPhoto = AppAccountPhotoSource,
                onBack = back,
            )
        }
        entry<AppRoute.WebVpnConverter>(transition = expand(AppRoute.WebVpnConverter::class)) {
            // 屏本轮搬进了 `:core`（`webvpn/WebVpnConverterScreen.kt`）；转换本体仍在 `:data` 的
            // `WebVpnUtil`（要用 javax.crypto 做 AES-128-CFB，进不了 commonMain）——
            // 这里按 `:core` 的 `WebVpnCodec` 缝把它注进去。画法与文案一行未改。
            WebVpnConverterScreen(
                codec = WebVpnUtil,
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

/**
 * 请假表单「添加附件」的取文件槽位（Android 侧）：系统多选文件（SAF）。
 *
 * 与搬迁前屏里那一段逐条同一口径（`OpenMultipleDocuments` + `contentResolver.openInputStream`，
 * 读不到的 URI 丢掉）；只是把「选完 → 追加」的异步形态包成挂起槽位，好让 `:core` 的屏不碰
 * ActivityResult API。取消选择 = 空表，往表单里不加任何附件。
 */
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
