package com.xjtu.toolbox

import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppNavigator
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AgentRuntimeHooks
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.home.GlobalSearchScreen
import kotlinx.coroutines.launch

// ── 主屏幕（底部导航栏）──────────────────

@Composable
internal fun MainScreen(
    navController: AppNavigator,
    loginState: AppLoginState,
    credentialStore: CredentialStore,
    accountManager: com.xjtu.toolbox.account.AccountManager,
    isRestoring: Boolean = false,
    restoreStep: String = "",
    restoreGateReady: Boolean = true,
    pendingTab: String? = null,
    onPendingTabConsumed: () -> Unit = {},
    pendingLaunchRoute: String? = null,
    onPendingLaunchConsumed: () -> Unit = {},
    onWarmupRequest: () -> Unit = {},
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
    heroBulletins: List<Bulletin> = emptyList(),
    onHeroBulletinTap: (Bulletin) -> Unit = {},
    onHeroBulletinDismiss: (Bulletin) -> Unit = {},
) {
    // 读取设置的默认 Tab
    val defaultTabOrdinal = remember {
        val saved = credentialStore.defaultTab
        BottomTab.entries.indexOfFirst { it.name == saved }.coerceAtLeast(0)
    }
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(defaultTabOrdinal) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]
    val navAccountCount = remember(loginState.accountId) { accountManager.accountList().size }

    // 底栏风格
    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    DisposableEffect(Unit) {
        AgentRuntimeHooks.applyNavBarStyle = { v -> navBarStyle = v }
        onDispose { AgentRuntimeHooks.applyNavBarStyle = null }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var showGlobalSearch by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }

    LaunchedEffect(pendingTab) {
        val tabName = pendingTab ?: return@LaunchedEffect
        val matched = BottomTab.entries.firstOrNull { it.name == tabName }
        if (matched != null) {
            selectedTabOrdinal = matched.ordinal
        }
        onPendingTabConsumed()
    }

    BackHandler {
        when {
            showQrLogin -> showQrLogin = false
            showGlobalSearch -> showGlobalSearch = false
            selectedTab != BottomTab.HOME -> selectedTabOrdinal = BottomTab.HOME.ordinal
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    (context as? android.app.Activity)?.finishAffinity()
                } else {
                    lastBackPressTime = now
                    android.widget.Toast.makeText(context, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 自动登录状态
    val showAutoLoginSheet = remember { mutableStateOf(false) }
    var autoLoginMessage by remember { mutableStateOf("") }
    var autoLoginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // miuix：底栏条目可单独设选中色。选中跟主题色（含取色），未选中仍走容器字色。
    val navItemColors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = MiuixTheme.colorScheme.primary,
        unselectedContentColor = MiuixTheme.colorScheme.onSurfaceContainer,
    )

    fun switchToTab(tab: BottomTab) {
        selectedTabOrdinal = tab.ordinal
    }

    // 富触感（PR T）：只有用户自己点底栏 / 侧栏切 tab 才震一下轻 tick。
    // 深链、快捷方式、搜索跳 tab 走 switchToTab，不震——那不是手上的动作。
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    fun userSelectTab(ordinal: Int) {
        if (ordinal != selectedTabOrdinal) haptics.tick()
        selectedTabOrdinal = ordinal
    }

    fun navigateToTarget(target: String) {
        // 有独立 tab 的功能一律切 tab，不 push 子页——否则同一个页面会存在
        // "带返回箭头的子页"和"底栏 tab"两副面孔，返回行为还不一致。
        // 深链、启动器快捷方式、全局搜索、首页服务列表全都汇流到这里。
        when (target) {
            Routes.SCHEDULE -> switchToTab(BottomTab.COURSES)
            Routes.AGENT -> switchToTab(BottomTab.PIDAI)
            else -> navController.navigate(target)
        }
    }

    fun navigateWithLogin(target: String, type: LoginType) {
        // 记录使用轨迹：下次冷启动据此做免密 SSO 预热（见 startBackgroundLoginWarmup）
        runCatching { credentialStore.recordRecentSite(type.siteKey()) }
        // 维护中的服务：直接提示，不进入页面也不触发登录，避免无谓的认证压力
        if (target in maintenanceRoutes) {
            val label = maintenanceLabels[target] ?: type.label
            scope.launch { snackbarHostState.showSnackbar("$label 学校系统维护中，暂不可用", duration = SnackbarDuration.Short) }
            return
        }
        // 快速网络检测（ConnectivityManager，瞬时，不阻塞）
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null && cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // 离线可用的路由（有本地缓存支持）
        val offlineCapableRoutes = setOf(Routes.SCHEDULE, Routes.JWAPP_SCORE)

        // ── 断网处理（优先于所有登录检查）──
        if (!isOnline) {
            if (target in offlineCapableRoutes) {
                navigateToTarget(target)
                scope.launch { snackbarHostState.showSnackbar("无网络连接，展示已缓存数据", duration = SnackbarDuration.Short) }
            } else {
                scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
            }
            return
        }

        fun siteReady(t: LoginType): Boolean =
            loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true

        if (siteReady(type)) {
            navigateToTarget(target)
        } else if (loginState.hasCredentials) {
            // 用户主动点击：永远允许立即登录（即使刚才取消过 MFA），由用户自己决定再次取消还是验证。
            // 有保存的凭据，尝试自动登录
            showAutoLoginSheet.value = true
            autoLoginMessage = "正在连接${type.label}…"
            val autoLoginTimeoutMs = when (type) {
                LoginType.COUPON,
                LoginType.FITNESS,
                LoginType.NEW_ATTENDANCE -> 180_000L
                // 场馆/电子凭证等走「CAS OAuth → org 中转 → 业务站」多跳链路，
                // 叠加 CasGate 限频与 WebVPN 改写后 25s 常不够用，超时即表现为"打不开"。
                else -> 60_000L
            }
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) {
                        // 用户正在等这个页面：豁免站点失败冷却，别让"点了没反应"发生
                        loginState.sessionManager?.ensureSite(type, userInitiated = true)
                    }
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    if (result != null) {
                        navigateToTarget(target)
                    } else {
                        // 登录未完成：可能是网络不通 / 密码错误 / 服务故障。
                        // 不再展示「受限请连 WebVPN/校园网」这种迷惑提示，SessionManager 已按网络环境处理。
                        if (target in offlineCapableRoutes) {
                            navigateToTarget(target)
                            scope.launch {
                                snackbarHostState.showSnackbar("${type.label}暂未连通，展示已缓存数据", duration = SnackbarDuration.Short)
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("${type.label}连接超时，请稍后重试", duration = SnackbarDuration.Short)
                            }
                        }
                    }
                } catch (e: Exception) {
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    android.util.Log.e("Login", "ensureSite($type) failed for $target", e)
                    // 登录态失效（reAuth 失败）→ 清站点会话 + 重新 ensureSite（CAS 触发 MFA 时会自动弹窗）
                    if (e is AuthExpiredException) {
                        android.util.Log.w("Login", "AuthExpired for $type, retrying full SiteSession login")
                        loginState.sessionManager?.getSiteOrNull(type.siteKey())?.invalidateLogin()
                        autoLoginMessage = "正在重新登录${type.label}..."
                        showAutoLoginSheet.value = true
                        autoLoginJob = scope.launch {
                            try {
                                val r2 = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) { loginState.sessionManager?.ensureSite(type, userInitiated = true) }
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                if (r2 != null) {
                                    navigateToTarget(target)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("${type.label}暂未就绪", duration = SnackbarDuration.Short) }
                                }
                            } catch (e2: Exception) {
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                scope.launch { snackbarHostState.showSnackbar("${type.label}暂未就绪", duration = SnackbarDuration.Short) }
                            }
                        }
                        return@launch
                    }
                    // 离线可用路由降级
                    if (target in offlineCapableRoutes) {
                        navigateToTarget(target)
                        scope.launch { snackbarHostState.showSnackbar("网络不佳，展示已缓存数据", duration = SnackbarDuration.Short) }
                    } else {
                        val detail = e.message?.take(40)?.takeIf { it.isNotBlank() }
                        val msg = when (e) {
                            is java.io.IOException -> detail ?: "网络不佳，请检查网络连接"
                            else -> detail ?: "${type.label}暂未就绪"
                        }
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                    }
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("请先登录后使用${type.label}", duration = SnackbarDuration.Short)
            }
        }
    }

    LaunchedEffect(pendingLaunchRoute, restoreGateReady) {
        if (!restoreGateReady) return@LaunchedEffect
        val route = pendingLaunchRoute ?: return@LaunchedEffect
        onPendingLaunchConsumed()
        val type = loginTypeForRoute(route)
        if (type != null) {
            navigateWithLogin(route, type)
        } else {
            navigateToTarget(route)
        }
    }

    // 监听 Screen 内 API 抛 AuthExpiredException 时设置的 pendingRetry：
    // 自动 popBackStack + 重新 navigateWithLogin（含必要的 MFA），整个过程对用户透明。
    LaunchedEffect(loginState.pendingRetry) {
        val req = loginState.pendingRetry ?: return@LaunchedEffect
        loginState.pendingRetry = null
        val (type, route) = req
        // 当前 Screen 已抛异常退出（Screen 内会主动 onBack），navController 应在 main 上层。
        // 给一个短暂 delay 让 popBackStack 动画完成，避免与 navigateWithLogin 抢导航。
        kotlinx.coroutines.delay(200)
        navigateWithLogin(route, type)
    }

    // ── 各 Tab 独立的滚动折叠状态 ──
    val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val coursesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val toolsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val profileScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val agentScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 大屏适配：宽屏（侧边 NavigationRail）与否由导航根部统一算好向下提供，
    // 规则见 ui/WindowSize.kt。平板、折叠屏内屏、手机横屏都可能进这一支。
    val isWide = com.xjtu.toolbox.ui.isWideLayout()

    // 宽屏没有底栏，可底栏风格是一路透传给各 tab 的（它们据此补底部留白）。
    // 不换成 "rail" 的话，宽屏下底栏已经不渲染了，子页面却照样留 96dp 空白。
    val effectiveNavStyle = if (isWide) "rail" else navBarStyle

    // 界面风格（plan2 §17 第 12 条）：沿用原来「底栏风格」的取值，"floating" = 玻璃（默认），
    // "classic" = 经典。选经典时，底栏回到经典样式，所有玻璃点（底栏、侧栏、气泡、搜索浮层、
    // 二级页顶栏）一起退回不透明；经典同时就是「性能模式」，不另设玻璃开关。
    val glassStyle = navBarStyle == CredentialStore.NAV_STYLE_FLOATING
    // 玻璃底栏只在手机竖屏换（宽度 < 600dp 且不是宽屏）。平板竖屏保留原来的悬浮胶囊：
    // 那里刚在 PR A 修过，不再动。
    val useGlassBar = glassStyle && !isWide &&
        com.xjtu.toolbox.ui.currentWindowSize() == com.xjtu.toolbox.ui.WindowSize.Compact
    // 玻璃的采样源：录下各 tab 的页面内容（见下面 tab 内容区的 layerBackdrop）。
    // 只录内容区，不录整个 Scaffold：底栏在 Scaffold 里面，录整个 Scaffold 就成了
    // 「玻璃采样自己」的环，RenderThread 会直接 SIGSEGV。
    val appBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    // 玻璃底栏把自己导出成一层，屁岱气泡的尖角伸到底栏上时采的是「页面 + 底栏」合起来的样子，
    // 不然尖角下面透出来的是页面，和底栏断开（plan2 §16.6）。
    val glassBarExport = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val phoneBubbleBackdrop = com.kyant.backdrop.backdrops.rememberCombinedBackdrop(appBackdrop, glassBarExport)

    // 悬浮底栏的总占位高度。
    // - 玻璃底栏：本体 64dp，离系统导航条 12dp（没有导航条时离屏幕底边 20dp）；
    // - 平板竖屏的悬浮胶囊：取自 miuix FloatingNavigationBar 的实现，胶囊本体最小 52dp，
    //   外加底部留白（有系统导航条时 26dp + inset，否则 36dp）。
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val glassBarBottomGap = if (navInset > 0.dp) 12.dp else 20.dp
    val floatingBarReserve = when {
        useGlassBar -> GLASS_BAR_HEIGHT + glassBarBottomGap + navInset
        !isWide && navBarStyle == "floating" ->
            FLOATING_BAR_HEIGHT + (if (navInset > 0.dp) 26.dp + navInset else 36.dp)
        else -> 0.dp
    }

    // COURSES tab 副标题 + actions slot + bottomContent slot
    var courseSubtitle by remember { mutableStateOf("") }
    var courseHeaderActions by remember { mutableStateOf<(@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?>(null) }
    var courseHeaderBottomContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // PIDAI tab 的标题与顶栏按钮。屁岱作为 0 级页不再自带 Scaffold/TopAppBar，
    // 标题（助手名字可改）和「会话列表 / 设置」两个按钮由它反向送上来，
    // 走的是 COURSES tab 已有的同一套 slot 机制。
    var agentTitle by remember { mutableStateOf("屁岱") }
    var agentHeaderActions by remember { mutableStateOf<(@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?>(null) }
    var agentHeaderNavIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // ── 首页数据的主动拉取 ──
    //
    // 挂在 MainScreen 而不是 HomeTab：tab 是懒加载的（`composedTabs` 只在选中过之后才加），
    // 而默认启动 Tab 允许设成日程/学辅/我的。挂在 HomeTab 上就意味着
    // **用户不点一次首页，主动拉取一次都不会跑**——校园卡余额、成绩、图书馆状态全是空的，
    // 依赖它们的屁岱提醒自然也永远不触发。
    LaunchedEffect(loginState.accountId, loginState.campusCardCacheVersion) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        com.xjtu.toolbox.home.HomeStatsRefresher.refreshDue(
            context,
            loginState.sessionManager,
            loginState.accountType,
        )
        com.xjtu.toolbox.home.HomeSignals.bumpStatsVersion()
    }

    // ── 屁岱主动提醒：只算文案，不管展示 ──
    //
    // 算完塞进 ProactiveBubbleHost，展示位是底栏正中那颗屁岱头顶的气泡。
    // 数据全部读本地缓存，不为提醒额外发任何请求。
    //
    // 同样提到了 MainScreen 层，理由和上面那个循环一样。它早先还有过一次搬家：
    // 原本长在首页「卡片主题」的 else 分支里，图标主题下永远不执行。
    //
    // 循环评估而不是只算一次：余额变化、临近上课、新成绩落盘都在运行期发生，
    // 只在冷启动算一次的话表现就是"冒过一次以后再也不冒了"。
    // 真正的节流交给 ProactiveRules.pick() 里的冷却判断。
    LaunchedEffect(loginState.accountId, loginState.isLoggedIn) {
        kotlinx.coroutines.delay(com.xjtu.toolbox.agent.ProactiveRules.FIRST_DELAY_MS)
        val cardPrefs = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(context)
        while (true) {
            // 余额直接读校园卡缓存，不再依赖首页把它算好递过来。
            val balance = cardPrefs.getFloat("card_balance_cache", -1f)
                .takeIf { it >= 0f }?.toDouble()
            val focus = com.xjtu.toolbox.home.HomeSignals.scheduleReminder
            val minutes = focus?.let {
                java.time.Duration.between(java.time.LocalDateTime.now(), it.startAt).toMinutes()
            }
            // 成绩与通知由 HomeStatsRefresher 抓取后留下游标，这里只读不抓——
            // 「一次抓取、两处消费」，气泡不为自己额外发请求。图书馆同理。
            val pendingScores = com.xjtu.toolbox.home.HomeStats.pendingNewScores(context)
            val unseenNotice = com.xjtu.toolbox.home.HomeStats.unseenNoticeTitle(context)
            val unseenNoticeLink = com.xjtu.toolbox.home.HomeStats.unseenNoticeLink(context)
            val libraryTodo = com.xjtu.toolbox.home.HomeSignals.libraryUrgentAction
            // 考试同样是"只读不抓"：日程页每次加载都会把考试表写进 DataCache，
            // 这里直接读那份。为了提醒单独去拉一次教务是不值得的。
            val nextExam = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.xjtu.toolbox.schedule.ExamCountdown.fromCache(context)
            }
            val msg = com.xjtu.toolbox.agent.ProactiveRules.pick(
                ctx = context,
                balance = balance,
                nextCourseName = focus?.name,
                minutesToClass = minutes,
                newGradeCount = pendingScores,
                latestNotice = unseenNotice,
                libraryPendingAction = libraryTodo,
                examCountdown = nextExam,
                latestNoticeLink = unseenNoticeLink,
                accountType = loginState.accountType,
            )
            android.util.Log.d(
                "Proactive",
                "evaluate: loggedIn=${loginState.isLoggedIn} balance=$balance " +
                    "nextCourse=${focus?.name} minutes=$minutes " +
                    "newScores=$pendingScores notice=${unseenNotice?.take(12)} " +
                    "libraryTodo=$libraryTodo exam=${nextExam?.exam?.courseName}/${nextExam?.daysLeft} " +
                    "-> ${msg?.text ?: "无"}"
            )
            if (msg != null &&
                com.xjtu.toolbox.agent.ProactiveBubbleHost.message == null &&
                !com.xjtu.toolbox.agent.ProactiveBubbleHost.autoSuppressed
            ) {
                com.xjtu.toolbox.agent.ProactiveRules.markShown(context, msg)
                // 冒过就消费掉，避免同一条反复提醒。冷却只管"多久不再说"，
                // 不负责"这件事已经说过了"——两者混用会导致冷却一过又推一遍旧消息。
                when (msg.id) {
                    "grade" -> com.xjtu.toolbox.home.HomeStats.setPendingNewScores(context, 0)
                    "notice" -> com.xjtu.toolbox.home.HomeStats.clearUnseenNotice(context)
                    "schedule_change" ->
                        com.xjtu.toolbox.schedule.ScheduleDiff.setPending(context, null)
                    "attendance" -> com.xjtu.toolbox.home.HomeSignals.attendanceAlert = null
                    "coupon" -> com.xjtu.toolbox.home.HomeSignals.couponAlert = null
                    // 清空而非重查：待办是否还在只有图书馆服务端知道，这里现拉一次会给冒泡加一次网络等待。
                    // 下一轮 HomeStatsRefresher 会按最新状态重新填上，签完到则不再填。
                    "library" -> com.xjtu.toolbox.home.HomeSignals.libraryUrgentAction = null
                }
                com.xjtu.toolbox.agent.ProactiveBubbleHost.message = msg
            }
            kotlinx.coroutines.delay(com.xjtu.toolbox.agent.ProactiveRules.EVAL_INTERVAL_MS)
        }
    }

    // 点屁岱：切到它的 tab，同时让它说句闲话。
    // 两种底栏各渲染一次按钮，行为必须一致，所以提到这里共用一份。
    // 待在屁岱这一页时不让它自动冒泡：人已经在跟它聊了，从底栏探头说闲话既遮输入框也很怪。
    LaunchedEffect(selectedTab) {
        com.xjtu.toolbox.agent.ProactiveBubbleHost.autoSuppressed = selectedTab == BottomTab.PIDAI
    }

    val onPidaiTap: () -> Unit = {
        userSelectTab(BottomTab.PIDAI.ordinal)
        // 正事气泡（余额不足、要上课了）优先级高于闲话，不许被戳一下就顶掉。
        val current = com.xjtu.toolbox.agent.ProactiveBubbleHost.message
        if (current == null || current.id == com.xjtu.toolbox.agent.ProactiveRules.CHATTER_ID) {
            com.xjtu.toolbox.agent.ProactiveRules.pickOnTap(context)?.let { line ->
                com.xjtu.toolbox.agent.ProactiveRules.markTapped(context, line)
                com.xjtu.toolbox.agent.ProactiveBubbleHost.message = line
            }
        }
    }

    // 气泡冒出来的那一刻给一下 LOW_TICK（PR T §11.3）：按气泡 id 触发，同一条气泡重组不会重复震
    val bubbleId = com.xjtu.toolbox.agent.ProactiveBubbleHost.message?.id
    LaunchedEffect(bubbleId) {
        if (bubbleId != null) haptics.lowTick()
    }

    // ── 屁岱主动提醒气泡 ──
    //
    // 挂在**底栏自己身上**，不再挂 Scaffold 内容层。上一版靠"底栏高度 = 项高 + 导航条 inset"
    // 手算 offset，换成胶囊底栏就错位；而且内容层先于底栏绘制，气泡会被胶囊压住半截。
    // 现在气泡和导航栏是同一个 Column 里的上下邻居——位置由布局自己得出，绘制层级也自然在最上。
    //
    // 气泡外面套了个"零高度"的 layout：照常测量、往上溢出绘制，但对外宣称高度为 0。
    // 不这么做的话，气泡一出现就会把 bottomBar 撑高，Scaffold 重算 contentPadding，
    // 整页内容跟着往上跳一下。
    // 底栏版和侧栏版只差「朝哪个方向、摆在哪」，三个回调（打开/关掉/超时）必须完全一致，
    // 所以提出来一份，两个展示位各自只负责定位。
    val bubbleView: @Composable (com.xjtu.toolbox.agent.ProactiveMessage, com.xjtu.toolbox.agent.BubbleArrowSide, androidx.compose.ui.unit.Dp) -> Unit =
        { msg, arrowSide, maxWidth ->
            com.xjtu.toolbox.agent.ProactiveBubbleView(
                message = msg,
                arrowSide = arrowSide,
                maxWidth = maxWidth,
                // 底栏上的气泡采「页面 + 玻璃底栏」合起来那一层；侧栏气泡和平板竖屏的旧胶囊上
                // 只采页面内容（那两种底栏本身不是玻璃，没有导出层）
                backdrop = if (arrowSide == com.xjtu.toolbox.agent.BubbleArrowSide.Bottom && useGlassBar) {
                    phoneBubbleBackdrop
                } else {
                    appBackdrop
                },
                glass = glassStyle,
                onOpen = {
                    com.xjtu.toolbox.agent.ProactiveRules.markUseful(context, msg.id)
                    val route = msg.openRoute
                    com.xjtu.toolbox.agent.ProactiveBubbleHost.clear()
                    if (!route.isNullOrBlank()) {
                        val type = loginTypeForRoute(route)
                        if (type != null) navigateWithLogin(route, type)
                        else navigateToTarget(route)
                    } else if (msg.prompt.isNotBlank()) {
                        AgentPendingPrompt.set(msg.prompt, msg.eventSnapshot)
                        selectedTabOrdinal = BottomTab.PIDAI.ordinal
                    } else {
                        selectedTabOrdinal = BottomTab.PIDAI.ordinal
                    }
                },
                onDismiss = {
                    com.xjtu.toolbox.agent.ProactiveRules.markDismissed(context, msg.id)
                    com.xjtu.toolbox.agent.ProactiveBubbleHost.clear()
                },
                onTimeout = { com.xjtu.toolbox.agent.ProactiveBubbleHost.clear() },
            )
        }

    val proactiveBubbleSlot: @Composable () -> Unit = {
        val msg = com.xjtu.toolbox.agent.ProactiveBubbleHost.message
        if (msg != null) {
            val screenWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                bubbleView(
                    msg,
                    com.xjtu.toolbox.agent.BubbleArrowSide.Bottom,
                    (screenWidth - 32.dp).coerceAtLeast(200.dp),
                )
            }
        }
    }

    // 侧栏屁岱按钮在根坐标系里的位置，宽屏气泡靠它定位。
    var pidaiAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 覆盖层自己的根坐标。两者相减才是气泡该放的本地偏移：
    // MainScreen 在 NavHost 里，push 子页的转场动画会把整页横向平移，
    // 直接拿根坐标当本地坐标用就会在那几帧里偏掉。
    var overlayOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 骨架：Row 包 Scaffold，不是 Scaffold 包 Row。
    //
    // 侧栏原本挂在 Scaffold 的内容区里，外面套着 `.padding(padding)`——而 padding.top
    // 就是顶栏高度，各 tab 的顶栏又不一样高（屁岱是小标题、别的是会折叠的大标题、
    // 日程还多一条副标题和 bottomContent）。于是切 tab、滚动列表时侧栏跟着上下跳。
    // 搬到 Scaffold 外面，侧栏就只受窗口约束，顶栏怎么折叠都跟它无关。
    // 对照 miuix 示例 example/shared/.../AppContent.kt 的 Row { rail; NavDisplay }。
    //
    // 手机上 isWide == false，Row 里只剩 Scaffold 一个孩子，等价于原来的结构。
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
    Row(Modifier.fillMaxSize()) {
    if (isWide) {
        MainNavigationRail(
            selectedTab = selectedTab,
            onSelect = { userSelectTab(it.ordinal) },
            onPidaiTap = onPidaiTap,
            isLoggedIn = loginState.isLoggedIn,
            accountCount = navAccountCount,
            onPidaiBoundsChange = { pidaiAnchor = it },
        )
    }
    Scaffold(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // 宽屏下 tab 切换动画会横向平移内容，不裁剪的话会画到侧栏上。
            .then(if (isWide) Modifier.clipToBounds() else Modifier),
        // 宽屏时左侧的刘海/侧边导航条已经被侧栏自己吃掉了（NavigationRail 的
        // defaultWindowInsetsPadding），这里再留一次就是双重留白。同 miuix 示例 WideScreenContent。
        contentWindowInsets = if (isWide) {
            WindowInsets.systemBars.union(
                WindowInsets.displayCutout.exclude(
                    WindowInsets.displayCutout.only(WindowInsetsSides.Start),
                ),
            )
        } else {
            WindowInsets.systemBars.union(WindowInsets.displayCutout)
        },
        snackbarHost = {
            Box(Modifier.padding(bottom = floatingBarReserve)) {
                SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            // 屁岱永远用折叠态标题：它是从下往上长的聊天，大标题会随滚动忽大忽小。
            // 学辅原来也在这一档，理由是"它是个 WebView，没有可驱动折叠的原生滚动"；
            // 现在那一页换成了原生列表，正是大标题适用的场景，于是移回下面那一档。
            if (selectedTab == BottomTab.PIDAI) {
                top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                    title = agentTitle,
                    color = MiuixTheme.colorScheme.surface,
                    scrollBehavior = agentScrollBehavior,
                    navigationIcon = { agentHeaderNavIcon?.invoke() },
                    actions = { agentHeaderActions?.invoke(this) },
                )
            } else {
            // 日程 tab 的顶栏（连同标签行、周胶囊，都在这一个 TopAppBar 里）做成一整块玻璃（plan2 Y2）：
            // 只用一个 drawBackdrop，三块各做各的会在接缝处出现三条模糊边。
            // 采样源是 R2 录下的 tab 内容区；顶栏不在那一层里面，不会形成环。
            val coursesGlass = glassStyle && selectedTab == BottomTab.COURSES
            val topBarGlassTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.72f)
            TopAppBar(
                color = if (coursesGlass) androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface,
                modifier = if (coursesGlass) {
                    Modifier.drawBackdrop(
                        backdrop = appBackdrop,
                        shape = { androidx.compose.foundation.shape.RoundedCornerShape(0.dp) }, // 直角必须写成 0dp 圆角，RectangleShape 开折射会闪退
                        effects = {
                            // 折射需要的采样余量先让出来，再叠色彩增强、模糊、折射
                            padding = maxOf(padding, 16.dp.toPx())
                            vibrancy()
                            blur(8.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp)
                            lens(refractionHeight = 12.dp.toPx(), refractionAmount = 16.dp.toPx())
                        },
                        // 标题、周胶囊的字要一直看得清：玻璃上再压一层表面色
                        onDrawSurface = { drawRect(topBarGlassTint) },
                    )
                } else {
                    Modifier
                },
                title = when (selectedTab) {
                    BottomTab.HOME -> "岱宗盒子"
                    BottomTab.COURSES -> "日程"
                    BottomTab.PIDAI -> agentTitle
                    BottomTab.TOOLS -> "仲英学辅资料站"
                    BottomTab.PROFILE -> "我的"
                },
                largeTitle = when (selectedTab) {
                    BottomTab.HOME -> "岱宗盒子"
                    BottomTab.COURSES -> "日程"
                    BottomTab.PIDAI -> agentTitle
                    BottomTab.TOOLS -> "仲英学辅资料站"
                    BottomTab.PROFILE -> "我的"
                },
                subtitle = if (selectedTab == BottomTab.COURSES) courseSubtitle else "",
                scrollBehavior = when (selectedTab) {
                    BottomTab.HOME -> homeScrollBehavior
                    BottomTab.COURSES -> coursesScrollBehavior
                    BottomTab.PIDAI -> agentScrollBehavior
                    BottomTab.TOOLS -> toolsScrollBehavior
                    BottomTab.PROFILE -> profileScrollBehavior
                },
                navigationIcon = {
                    // 屁岱左上角：会话列表（抽屉从左滑出，触发它的按钮就该在左边）
                    if (selectedTab == BottomTab.PIDAI) {
                        agentHeaderNavIcon?.invoke()
                    }
                    // 首页左上角：扫码登录入口
                    if (selectedTab == BottomTab.HOME) {
                        IconButton(onClick = { showQrLogin = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.QrCodeScanner,
                                contentDescription = "扫码登录",
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    if (selectedTab == BottomTab.COURSES) {
                        courseHeaderActions?.invoke(this)
                    }
                    if (selectedTab == BottomTab.PIDAI) {
                        agentHeaderActions?.invoke(this)
                    }
                    // 首页全局搜索入口
                    if (selectedTab == BottomTab.HOME) {
                        IconButton(onClick = { showGlobalSearch = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                bottomContent = {
                    if (selectedTab == BottomTab.COURSES) {
                        // 挂在玻璃顶栏下面的标签行、周胶囊，底色跟着换成半透明
                        CompositionLocalProvider(com.xjtu.toolbox.ui.glass.LocalOnGlassBar provides coursesGlass) {
                            courseHeaderBottomContent?.invoke()
                        }
                    }
                }
            )
            }
        },
        bottomBar = if (!isWide && navBarStyle == "classic") {
            {
              Column {
                proactiveBubbleSlot()
                NavigationBar(
                    mode = NavigationBarDisplayMode.IconAndText
                ) {
                    // 5 个 tab（miuix 两种底栏都支持 2–5 项），屁岱在正中。
                    // 它和其他四个一样是 0 级页、一样切 selectedTab，只是**长得不一样**：
                    // 渲染成会动的机器人而不是灰度线性图标 + 文字。
                    BottomTab.entries.forEach { tab ->
                        if (tab == BottomTab.PIDAI) {
                            val pidaiStyle = com.xjtu.toolbox.agent.pidaiNavAppearance()
                            com.xjtu.toolbox.agent.PidaiNavButton(
                                onClick = onPidaiTap,
                                excited = com.xjtu.toolbox.agent.ProactiveBubbleHost.message != null,
                                thinking = com.xjtu.toolbox.agent.AgentThinkingHost.isThinking,
                                selected = selectedTab == tab,
                                diameter = 38.dp,
                                liftUp = 8.dp,
                                paper = MiuixTheme.colorScheme.surface,
                                ink = pidaiStyle.ink,
                                shape = pidaiStyle.shape,
                                skin = pidaiStyle.skin,
                                modifier = Modifier.weight(1f),
                            )
                            return@forEach
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { userSelectTab(tab.ordinal) },
                            icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            label = tab.label,
                            colors = navItemColors,
                            badge = bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)
                        )
                    }
                }
              }
            }
        } else {
            {}
        },
        floatingToolbar = if (useGlassBar) {
            {
              Column(
                  Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 16.dp)
                      .padding(bottom = navInset + glassBarBottomGap),
              ) {
                proactiveBubbleSlot()
                com.xjtu.toolbox.ui.glass.GlassBottomTabs(
                    tabs = BottomTab.entries.map { tab ->
                        // 屁岱那一格免染色：GlassBottomTabs 会把整排内容再画一遍做强调色，
                        // 屁岱是会动的机器人，不能被复制出第二份（plan2 §9.3A）
                        com.xjtu.toolbox.ui.glass.GlassTab(key = tab, tintExempt = tab == BottomTab.PIDAI) { selected ->
                            if (tab == BottomTab.PIDAI) {
                                val pidaiStyle = com.xjtu.toolbox.agent.pidaiNavAppearance()
                                com.xjtu.toolbox.agent.PidaiNavButton(
                                    onClick = onPidaiTap,
                                    excited = com.xjtu.toolbox.agent.ProactiveBubbleHost.message != null,
                                    thinking = com.xjtu.toolbox.agent.AgentThinkingHost.isThinking,
                                    selected = selected,
                                    diameter = 40.dp,
                                    // paper 现在是「挖空」，眼睛处直接透出后面的玻璃（PR O 第 1 步）
                                    paper = MiuixTheme.colorScheme.surfaceContainerHigh,
                                    ink = pidaiStyle.ink,
                                    shape = pidaiStyle.shape,
                                    skin = pidaiStyle.skin,
                                )
                            } else {
                                Box {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    // 角标照常画，不用玻璃（底栏里不能再嵌 layerBackdrop，§9.4）
                                    bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)?.let { badge ->
                                        Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp)) { badge() }
                                    }
                                }
                                Text(tab.label, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    },
                    selectedIndex = selectedTabOrdinal,
                    onTabSelected = { index ->
                        // 拖到屁岱松手也要走 onPidaiTap：它既切 tab，也负责戳一下冒闲话气泡
                        if (index == BottomTab.PIDAI.ordinal) onPidaiTap() else userSelectTab(index)
                    },
                    backdrop = appBackdrop,
                    exportedBackdrop = glassBarExport,
                    glass = true,
                )
              }
            }
        } else if (!isWide && navBarStyle == "floating") {
            {
              Column {
                proactiveBubbleSlot()
                FloatingNavigationBar(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                ) {
                    BottomTab.entries.forEach { tab ->
                        if (tab == BottomTab.PIDAI) {
                            val pidaiStyle = com.xjtu.toolbox.agent.pidaiNavAppearance()
                            com.xjtu.toolbox.agent.PidaiNavButton(
                                onClick = onPidaiTap,
                                excited = com.xjtu.toolbox.agent.ProactiveBubbleHost.message != null,
                                thinking = com.xjtu.toolbox.agent.AgentThinkingHost.isThinking,
                                selected = selectedTab == tab,
                                diameter = 40.dp,
                                paper = MiuixTheme.colorScheme.surfaceContainerHigh,
                                ink = pidaiStyle.ink,
                                shape = pidaiStyle.shape,
                                skin = pidaiStyle.skin,
                            )
                            return@forEach
                        }
                        FloatingNavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { userSelectTab(tab.ordinal) },
                            icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            label = tab.label,
                            colors = navItemColors,
                            badge = bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)
                        )
                    }
                }
              }
            }
        } else {
            {}
        }
    ) { padding ->
        // 玻璃风格下，日程 tab 的内容要铺到顶栏下面（Y2），所以顶部留白不在这一层统一加，
        // 改成下面逐个 tab 加：日程交给它自己的滚动内容，其余 tab 照旧整体下移。
        val topBarPadding = padding.calculateTopPadding()
        val contentLayoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    if (glassStyle) {
                        PaddingValues(
                            start = padding.calculateStartPadding(contentLayoutDirection),
                            end = padding.calculateEndPadding(contentLayoutDirection),
                            bottom = padding.calculateBottomPadding(),
                        )
                    } else {
                        padding
                    },
                ),
        ) {
            // 需要联网的无登录路由（空闲教室、通知公告等纯网络功能）
            val networkRequiredRoutes = setOf(
                Routes.EMPTY_ROOM,
                Routes.NOTIFICATION,
                Routes.YELLOW_PAGE,
                Routes.AGENT
            )
            val onNavigateWithNetCheck: (String) -> Unit = { route ->
                if (route == Routes.SCHEDULE) {
                    switchToTab(BottomTab.COURSES)
                } else if (route == Routes.AGENT) {
                    // 屁岱现在是 0 级 tab，没网也能进（进去看到的是空对话 + 提示），
                    // 不该像子页那样被联网检查拦在门外。
                    switchToTab(BottomTab.PIDAI)
                } else if (route in networkRequiredRoutes) {
                    val cm2 = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val online = cm2?.activeNetwork != null && cm2.getNetworkCapabilities(cm2.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (online) {
                        navController.navigate(route)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
                    }
                } else {
                    navController.navigate(route)
                }
            }
            var composedTabs by remember { mutableStateOf(setOf(selectedTab)) }
            LaunchedEffect(selectedTab) { composedTabs = composedTabs + selectedTab }
            var previousTabOrdinal by rememberSaveable { mutableIntStateOf(selectedTabOrdinal) }
            val tabSwitchDirection = when {
                selectedTabOrdinal > previousTabOrdinal -> 1
                selectedTabOrdinal < previousTabOrdinal -> -1
                else -> 0
            }
            LaunchedEffect(selectedTabOrdinal) {
                kotlinx.coroutines.delay(280)
                previousTabOrdinal = selectedTabOrdinal
            }
            val tabSlideDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
            // 玻璃的采样源就是这一层：各 tab 的页面内容。经典风格下不录，省一次离屏绘制。
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (glassStyle) Modifier.layerBackdrop(appBackdrop) else Modifier),
            ) {
                BottomTab.entries.forEach { tab ->
                    key(tab) {
                        if (tab in composedTabs) {
                            val isActive = selectedTab == tab
                            val tabAlpha by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = tween(if (isActive) 240 else 180),
                                label = "tabAlpha"
                            )
                            val tabOffset by animateFloatAsState(
                                targetValue = when {
                                    isActive -> 0f
                                    tabSwitchDirection == 0 -> 0f
                                    tab.ordinal < selectedTabOrdinal -> -tabSlideDistance
                                    else -> tabSlideDistance
                                },
                                animationSpec = tween(260),
                                label = "tabOffset"
                            )
                            val tabScale by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0.985f,
                                animationSpec = tween(260),
                                label = "tabScale"
                            )
                            Box(
                                Modifier.fillMaxSize()
                                    .then(
                                        if (glassStyle && tab != BottomTab.COURSES) Modifier.padding(top = topBarPadding)
                                        else Modifier
                                    )
                                    .zIndex(if (isActive) 1f else 0f)
                                    .graphicsLayer {
                                        alpha = tabAlpha
                                        translationX = tabOffset
                                        scaleX = tabScale
                                        scaleY = tabScale
                                    }
                                    .pointerInput(isActive) {
                                        if (!isActive) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                        .changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                when (tab) {
                                    BottomTab.HOME -> HomeTab(
                                        loginState,
                                        isRestoring = isRestoring,
                                        onNavigate = onNavigateWithNetCheck,
                                        onNavigateWithLogin = ::navigateWithLogin,
                                        onNavigateToProfile = { selectedTabOrdinal = BottomTab.PROFILE.ordinal },
                                        onNavigateToCourses = { selectedTabOrdinal = BottomTab.COURSES.ordinal },
                                        scrollBehavior = homeScrollBehavior,
                                        navBarStyle = effectiveNavStyle,
                                        homeTheme = homeTheme,
                                        showQuickActions = showQuickActions,
                                        bulletins = heroBulletins,
                                        onBulletinTap = onHeroBulletinTap,
                                        onBulletinDismiss = onHeroBulletinDismiss,
                                    )
                                    BottomTab.PIDAI -> com.xjtu.toolbox.agent.AgentScreen(
                                        // 悬浮胶囊底栏是**浮在内容上**的，不占 Scaffold 的
                                        // contentPadding。别的 tab 是滚动列表，底部被盖住无所谓；
                                        // 屁岱有个钉在底边的输入栏，不补这段高度就会被胶囊压住。
                                        // 经典底栏本身占位，无需额外补。
                                        extraBottomPadding = floatingBarReserve,
                                        hostBottomPadding = padding.calculateBottomPadding(),
                                        // 0 级页：不自带 Scaffold/TopAppBar，也没有返回箭头。
                                        // 返回键的语义由 MainScreen 统一管（非首页 tab → 回首页）。
                                        asTab = true,
                                        scrollBehavior = agentScrollBehavior,
                                        onTitleChange = { agentTitle = it },
                                        onActionsChange = { agentHeaderActions = it },
                                        onNavIconChange = { agentHeaderNavIcon = it },
                                        onNavigate = onNavigateWithNetCheck,
                                    )
                                    BottomTab.COURSES -> CoursesTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = coursesScrollBehavior, extraBottomPadding = floatingBarReserve, contentTopPadding = if (glassStyle) topBarPadding else 0.dp, onSubtitleChange = { courseSubtitle = it }, onActionsChange = { courseHeaderActions = it }, onBottomContentChange = { courseHeaderBottomContent = it })
                                    BottomTab.TOOLS -> ToolsTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = toolsScrollBehavior, navBarStyle = effectiveNavStyle)
                                    BottomTab.PROFILE -> ProfileTab(
                                        loginState,
                                        ::navigateWithLogin,
                                        credentialStore,
                                        accountManager,
                                        scrollBehavior = profileScrollBehavior,
                                        onNavigateToDownloads = { navController.navigate(Routes.DOWNLOAD_MANAGER) },
                                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                                        onNavigateToFeedback = { navController.navigate(Routes.FEEDBACK) },
                                        onNavigateToAccounts = { navController.navigate(com.xjtu.toolbox.Routes.ACCOUNTS) },
                                        navBarStyle = effectiveNavStyle,
                                        onWarmupRequest = onWarmupRequest
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 登录恢复非阻塞提示条（底部，不遮挡欢迎卡片）
            androidx.compose.animation.AnimatedVisibility(
                visible = isRestoring,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            restoreStep.ifEmpty { "正在恢复登录..." },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // 自动登录弹窗（OverlayDialog 风格统一）
            BackHandler(enabled = showAutoLoginSheet.value) {
                autoLoginJob?.cancel()
                showAutoLoginSheet.value = false
                autoLoginJob = null
            }
            OverlayDialog(
                show = showAutoLoginSheet.value,
                title = "自动登录中",
                summary = autoLoginMessage,
                onDismissRequest = {
                    autoLoginJob?.cancel()
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                }
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    TextButton(
                        text = "取消",
                        onClick = {
                            autoLoginJob?.cancel()
                            showAutoLoginSheet.value = false
                            autoLoginJob = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            com.xjtu.toolbox.feedback.FeedbackPromptSheet()

            // ── 密码失效弹窗 ─────────────────────────────────────────
            if (loginState.passwordInvalidatedDialogVisible) {
                BackHandler(enabled = true) { loginState.passwordInvalidatedDialogVisible = false }
                OverlayDialog(
                    show = true,
                    title = "登录密码可能已变更",
                    summary = "「${loginState.passwordInvalidatedSiteName}」登录失败，已暂停其他系统的自动登录以保护账号。请在设置中更新密码。",
                    onDismissRequest = { loginState.passwordInvalidatedDialogVisible = false }
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "稍后",
                            onClick = { loginState.passwordInvalidatedDialogVisible = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "去更新密码",
                            onClick = {
                                loginState.passwordInvalidatedDialogVisible = false
                                navController.navigate(Routes.SETTINGS)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }

    // 全局搜索覆盖层（跨 tab 共用同一个浮层，渲染优先级高于普通导航）
    if (showGlobalSearch) {
        // 搜索浮层盖在整页上面，用页面内容做玻璃背景；经典风格下给 null，退回不透明
        CompositionLocalProvider(com.xjtu.toolbox.ui.glass.LocalAppBackdrop provides if (glassStyle) appBackdrop else null) {
        GlobalSearchScreen(
            onBack = { showGlobalSearch = false },
            onNavigate = { route ->
                showGlobalSearch = false
                val type = loginTypeForRoute(route)
                if (type != null) navigateWithLogin(route, type)
                else navigateToTarget(route)
            },
            onAskAgent = { prompt ->
                showGlobalSearch = false
                AgentPendingPrompt.set(prompt)
                switchToTab(BottomTab.PIDAI)
            },
            accountType = loginState.accountType,
        )
        }
    }

    // 扫码登录覆盖层（首页左上角入口）
    if (showQrLogin) {
        com.xjtu.toolbox.qrlogin.QrLoginScreen(
            sessionManager = accountManager.sessionManager,
            onBack = { showQrLogin = false },
        )
    }
    }  // Scaffold content
    }  // Row

    // 侧栏屁岱的主动提醒气泡。
    //
    // 不能放进 NavigationRail：它内部是一个 verticalScroll 的 Column，
    // 向右溢出的气泡会被裁掉。改成与 Row 并列的覆盖层，按按钮的根坐标定位。
    // 侧栏气泡拆成单独的组件：屁岱在侧栏展开 / 收起时逐帧移动，pidaiAnchor 每帧都变。
    // 以前在这里（MainScreen 的组合阶段）直接读它，动画期间整个 MainScreen 每帧重组，侧栏动画卡成幻灯片。
    // 现在只有 RailProactiveBubble 这一小块跟着重组；没有气泡时它什么都不做。
    if (isWide) {
        RailProactiveBubble(
            anchor = { pidaiAnchor },
            overlayOrigin = { overlayOrigin },
            bubbleView = bubbleView,
        )
    }
    }  // 最外层 Box
}

/** 宽屏侧栏屁岱旁边的主动气泡，尖角朝左指着屁岱。锚点、覆盖层原点都用函数传进来，只在这里读。 */
@Composable
private fun RailProactiveBubble(
    anchor: () -> androidx.compose.ui.geometry.Rect?,
    overlayOrigin: () -> androidx.compose.ui.geometry.Offset,
    bubbleView: @Composable (com.xjtu.toolbox.agent.ProactiveMessage, com.xjtu.toolbox.agent.BubbleArrowSide, androidx.compose.ui.unit.Dp) -> Unit,
) {
    val bubbleMsg = com.xjtu.toolbox.agent.ProactiveBubbleHost.message ?: return
    val rect = anchor() ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowWidthPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    val bubbleMax = with(density) {
        (windowWidthPx - rect.right).toDp() - 24.dp
    }.coerceIn(180.dp, 360.dp)
    val gapPx = with(density) { 8.dp.roundToPx() }
    Box(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(
                constraints.copy(minWidth = 0, minHeight = 0),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val origin = overlayOrigin()
                val x = ((rect.right - origin.x).toInt() + gapPx)
                    .coerceAtMost((constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val y = (rect.center.y - origin.y - placeable.height / 2f).toInt()
                    .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(x, y)
            }
        },
    ) {
        bubbleView(bubbleMsg, com.xjtu.toolbox.agent.BubbleArrowSide.Start, bubbleMax)
    }
}

/**
 * 宽屏侧栏。整体在 Scaffold **之外**，只受窗口约束，顶栏怎么折叠都跟它无关（见 MainScreen 注释）。
 *
 * miuix 0.9.3 起 NavigationRail 去掉了 mode 参数，改为传 state 获得可展开侧栏：
 * 收起态为图标+小字，展开态为「图标 + 文字」横向排布，顶部自带展开/收起按钮。
 * 它自己处理状态栏 / 侧边导航条 / 起始侧刘海的 inset（defaultWindowInsetsPadding），
 * 外面不要再补 padding。
 */
@Composable
private fun MainNavigationRail(
    selectedTab: BottomTab,
    onSelect: (BottomTab) -> Unit,
    onPidaiTap: () -> Unit,
    isLoggedIn: Boolean,
    accountCount: Int,
    onPidaiBoundsChange: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val railState = top.yukonga.miuix.kmp.basic.rememberNavigationRailState()
    top.yukonga.miuix.kmp.basic.NavigationRail(
        color = MiuixTheme.colorScheme.surface,
        state = railState,
        expandContentDescription = "展开导航栏",
        collapseContentDescription = "收起导航栏"
    ) {
        BottomTab.entries.forEach { tab ->
            if (tab == BottomTab.PIDAI) {
                // 屁岱在侧栏里也是那颗会眨眼、会思考、能换皮肤的机器人，
                // 不再退化成灰度线性图标——否则宽屏用户看到的是另一个应用。
                val pidaiStyle = com.xjtu.toolbox.agent.pidaiNavAppearance()
                // 展开 / 收起要和邻居一起平滑地挪。miuix 的 NavigationRailItem 跟着侧栏内部的一条
                // 0..1 弹簧进度逐帧插值位置，那个进度（LocalNavigationRailExpandInfo）是 internal 的，
                // 拿不到；这里用同一条弹簧（阻尼 1、响应 0.35s）自己算一份，几何也照它的公式：
                // 图标从「收起时居中」插值到「展开时左侧 12 + 14dp」，文字跟着淡入。
                // 以前按 isExpanded 直接在居中和靠左之间硬切，别的格子在滑、只有屁岱跳一下。
                val railProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (railState.isExpanded) 1f else 0f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 322f),
                    label = "pidaiRailProgress",
                )
                androidx.compose.ui.layout.Layout(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与 NavigationRailDefaults.ItemVerticalPadding 对齐，
                        // 保证它和邻居在侧栏里是同一套等距节奏。
                        .padding(vertical = 12.dp)
                        .clickable(onClick = onPidaiTap),
                    content = {
                    com.xjtu.toolbox.agent.PidaiNavButton(
                        onClick = onPidaiTap,
                        excited = com.xjtu.toolbox.agent.ProactiveBubbleHost.message != null,
                        thinking = com.xjtu.toolbox.agent.AgentThinkingHost.isThinking,
                        selected = selectedTab == tab,
                        diameter = 40.dp,
                        paper = MiuixTheme.colorScheme.surface,
                        ink = pidaiStyle.ink,
                        shape = pidaiStyle.shape,
                        skin = pidaiStyle.skin,
                        modifier = Modifier.onGloballyPositioned { onPidaiBoundsChange(it.boundsInRoot()) },
                    )
                    // ExpandedLabelFontSize，与邻居的展开态对齐；收起时透明，不占位置
                    Text(
                        tab.label,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = railProgress },
                    )
                    },
                ) { measurables, constraints ->
                    val f = railProgress.coerceIn(0f, 1f)
                    val icon = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val labelMax = (constraints.maxWidth - 26.dp.roundToPx() - icon.width - 16.dp.roundToPx() - 26.dp.roundToPx())
                        .coerceAtLeast(0)
                    val label = measurables[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = labelMax))
                    // 收起：在 80dp（NavigationRailDefaults.MinWidth）宽的侧栏里居中；
                    // 展开：ExpandedItemHorizontalMargin(12) + ExpandedItemContentHorizontalPadding(14)
                    val collapsedX = (80.dp.toPx() - icon.width) / 2f
                    val expandedX = 26.dp.toPx()
                    val iconX = androidx.compose.ui.util.lerp(collapsedX, expandedX, f).toInt()
                    val height = maxOf(icon.height, label.height)
                    layout(constraints.maxWidth, height) {
                        icon.placeRelative(iconX, (height - icon.height) / 2)
                        // ExpandedItemIconTextSpacing(16)
                        if (f > 0f) label.placeRelative(iconX + icon.width + 16.dp.roundToPx(), (height - label.height) / 2)
                    }
                }
                return@forEach
            }
            top.yukonga.miuix.kmp.basic.NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                label = tab.label,
                badge = bottomTabBadge(tab, isLoggedIn, accountCount)
            )
        }
    }
}

private fun bottomTabBadge(
    tab: BottomTab,
    isLoggedIn: Boolean,
    accountCount: Int,
): (@Composable () -> Unit)? {
    if (tab != BottomTab.PROFILE) return null
    return when {
        accountCount > 1 -> {
            {
                Badge {
                    Text(accountCount.coerceAtMost(99).toString())
                }
            }
        }
        !isLoggedIn -> {
            { Badge() }
        }
        else -> null
    }
}

// ══════════════════════════════════════════
//  Tab 2 — 日程
// ══════════════════════════════════════════

@Composable
private fun CoursesTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigate: (String) -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 玻璃顶栏盖在内容上面时顶栏的高度，交给日程页各栏做顶部留白（Y2）。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onSubtitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?) -> Unit = {},
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
    ) {
        ScheduleScreen(
            site = loginState.sessionManager?.getSiteOrNull("jwxt"),
            studentId = loginState.activeUsername,
            onBack = {},
            showTopBar = false,
            onSubtitleChange = onSubtitleChange,
            onActionsChange = onActionsChange,
            onBottomContentChange = onBottomContentChange,
            contentBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            topAppBarScrollBehavior = scrollBehavior,
            // 详情面板的下钻目标（教材全文 / 课程回放 / 考勤）都在别的子系统里，
            // 走带登录的跳转，免得落地页自己再弹一次未登录。
            onNavigate = { route ->
                val type = loginTypeForRoute(route)
                if (type != null) onNavigateWithLogin(route, type) else onNavigate(route)
            },
        )
    }
}

/**
 * 用学校登记的身份校正账号类型。
 *
 * 之前这个开关只能用户自己在设置里选，选错的后果不是"显示不对"而是 CAS
 * 选身份时走错分支、一串子系统登不上，而普通用户根本没法判断自己该选哪个
 * （"我是直博生算研究生吗"）。既然一网通办已经把身份告诉我们了，就别再问。
 *
 * 识别不出来时什么都不做——保留用户原有设置，不用猜测覆盖已知。
 */
internal fun applyDetectedAccountType(
    context: android.content.Context,
    loginState: AppLoginState,
    accountManager: com.xjtu.toolbox.account.AccountManager,
    identityTypeName: String?,
) {
    val detected = com.xjtu.toolbox.auth.AccountType.fromIdentityName(identityTypeName) ?: return
    if (detected == loginState.accountType) return
    loginState.accountType = detected
    loginState.sessionManager?.accountType =
        if (detected == com.xjtu.toolbox.auth.AccountType.POSTGRADUATE) {
            com.xjtu.toolbox.auth.XJTULogin.AccountType.POSTGRADUATE
        } else {
            com.xjtu.toolbox.auth.XJTULogin.AccountType.UNDERGRADUATE
        }
    com.xjtu.toolbox.util.CredentialStore(context).accountType = detected
    val id = loginState.accountId
    if (id.isNotEmpty()) {
        val store = com.xjtu.toolbox.account.AccountStore(context)
        store.get(id)?.let { store.upsert(it.copy(accountType = detected)) }
    }
}

// ══════════════════════════════════════════
//  Tab 3 — 仲英学辅资料站（zyxf.top）
// ══════════════════════════════════════════

/**
 * Tab 3 —— 仲英学辅资料站。
 *
 * 从"把 zyxf.top 整站塞进 WebView"改成原生：列表、检索、下载走它的公开只读接口，
 * 排版用 MIUIX，和应用其它页面一致（见 [com.xjtu.toolbox.zyxf.ZyxfBrowseScreen]）。
 * 预览那一层仍然是 WebView，但只装一个壳页借阿里云 IMM 的渲染，
 * 不再把它整套 SPA 拖进来。
 */
@Composable
private fun ToolsTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigate: (String) -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating"
) {
    com.xjtu.toolbox.zyxf.ZyxfBrowseScreen(
        contentPadding = PaddingValues(
            bottom = if (navBarStyle == "floating") 96.dp else 0.dp,
        ),
        scrollBehavior = scrollBehavior,
    )
}
