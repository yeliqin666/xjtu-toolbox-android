package com.xjtu.toolbox.main

import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.layout
import androidx.compose.material.icons.outlined.Notifications
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.nav.ExpandOriginSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.agent.AgentOverlays
import com.xjtu.toolbox.agent.AgentInsightEngine
import com.xjtu.toolbox.agent.AgentInsightHub
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AndroidAgentHost
import com.xjtu.toolbox.agent.BubbleArrowSide
import com.xjtu.toolbox.agent.ProactiveBubbleHost
import com.xjtu.toolbox.agent.ProactiveBubbleView
import com.xjtu.toolbox.agent.ProactiveMessage
import com.xjtu.toolbox.agent.ProactiveRules
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.feedback.FeedbackPromptSheet
import com.xjtu.toolbox.home.GlobalSearchScreen
import com.xjtu.toolbox.home.HomeSignals
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.home.HomeStatsRefresher
import com.xjtu.toolbox.home.HomeTab
import com.xjtu.toolbox.library.LibraryFocus
import com.xjtu.toolbox.library.LibraryQrArea
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.AppPageAsTab
import com.xjtu.toolbox.nav.AppPageDeps
import com.xjtu.toolbox.inbox.InboxBell
import com.xjtu.toolbox.profile.ProfileTab
import com.xjtu.toolbox.qrlogin.QrLoginScreen
import com.xjtu.toolbox.schedule.ScheduleDiff
import com.xjtu.toolbox.schedule.cachedNextExam
import com.xjtu.toolbox.schedule.scheduleDiffStore
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.shell.AppShell
import com.xjtu.toolbox.shell.ShellAccountBadge
import com.xjtu.toolbox.shell.ShellHandles
import com.xjtu.toolbox.shell.ShellNavState
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.ui.glass.CouiPageTopAppBar
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import com.xjtu.toolbox.ui.glass.LocalCouiPageBackdrop
import com.xjtu.toolbox.ui.glass.LocalAppBackdrop
import com.xjtu.toolbox.ui.glass.LocalOnGlassBar
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.ui.rememberHaptics
import com.xjtu.toolbox.zyxf.ZyxfBrowseScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import java.time.LocalDateTime

/** 主界面（返回栈栈底）：底栏 / 宽屏侧栏 + 用户选的那几格（见 BottomBarTabs）。 */
@Composable
internal fun MainScreen(
    router: AppRouter,
    tabs: MainTabState,
    /** 放到底栏上的子页用它画（见 AppPageAsTab）。 */
    pageDeps: AppPageDeps,
    loginState: AppLoginState,
    credentialStore: CredentialStore,
    accountManager: AccountManager,
    isRestoring: Boolean,
    restoreStep: String,
    onWarmupRequest: () -> Unit,
    heroBulletins: List<Bulletin>,
    onHeroBulletinTap: (Bulletin) -> Unit,
    onHeroBulletinDismiss: (Bulletin) -> Unit,
) {
    val context = LocalContext.current
    val appearance = AppearanceSettings.get(context)
    val navBarStyle by appearance.navBarStyle.collectAsStateWithLifecycle()
    val showQuickActions by appearance.showQuickActions.collectAsStateWithLifecycle()
    val barIds by appearance.bottomTabs.collectAsStateWithLifecycle()
    val bar = remember(barIds) { BarTab.barOf(barIds) }
    val shellTabs = remember(bar) { androidShellTabs(bar) }
    val pidaiInBar = bar.any { it.core == BottomTab.PIDAI }
    LaunchedEffect(bar) { tabs.bar = bar }

    // 各页的小岱卡（通知精选、荐座……）都走这一个入口；「接着问小岱」切到小岱 tab
    remember(loginState) {
        AgentInsightHub.insights = AgentInsightEngine(AndroidAgentHost(context.applicationContext, loginState)) { prompt, snapshot ->
            AgentPendingPrompt.set(prompt, snapshot)
            router.selectTab(BottomTab.PIDAI)
        }
    }

    // 刚把当前这格移出底栏时回首页（画面先按首页画，状态随后跟上）
    val selectedBarTab = bar.firstOrNull { it.id == tabs.selectedId } ?: bar.first()
    LaunchedEffect(selectedBarTab.id) { tabs.selectedId = selectedBarTab.id }
    /** 选中的原生格；选中的是放上来的子页时为 null。 */
    val selectedTab: BottomTab? = selectedBarTab.core
    val navAccountCount = remember(loginState.accountId) { accountManager.accountList().size }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var showGlobalSearch by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    // 搜索 / 扫一扫从哪颗圆钮长出来；null = 从别处打开，直接淡入
    var revealSource by remember { mutableStateOf<ExpandOriginSource?>(null) }
    // 两个覆盖层的展开进度：提到这里，底下的页面才能跟着往后退
    val searchReveal = remember { androidx.compose.animation.core.Animatable(0f) }
    val qrReveal = remember { androidx.compose.animation.core.Animatable(0f) }

    // ── 各 Tab 独立的滚动折叠状态 ──
    // 主 tab 的大字顶栏是 COUI 原版 LargeTopAppBar，折叠状态就是 COUI 的
    // CouiCollapsingAppBarState（弹簧、吸附、反向展开全在原厂代码里）。
    val coursesScrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val toolsScrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val profileScrollBehavior = rememberCouiTopAppBarScrollBehavior()
    // 小岱的聊天列表仍用 MIUIX 滚动行为驱动自己的列表联动（顶栏已换成钉死的 COUI 小标题）
    val agentScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    val isWide = isWideLayout()

    // 界面风格：玻璃（默认）或经典；经典下所有玻璃效果一起关掉，兼作性能模式
    val glassStyle = navBarStyle == CredentialStore.NAV_STYLE_FLOATING

    // COURSES tab 副标题 + actions slot + bottomContent slot
    var courseHeaderActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var courseHeaderBottomContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // PIDAI tab 的标题与顶栏按钮，由小岱页反向送上来
    var agentTitle by remember { mutableStateOf("小岱") }
    val agentOverlays = remember { AgentOverlays() }
    var agentHeaderActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var agentHeaderNavIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // 首页数据拉取挂在这里而不是 HomeTab：tab 懒加载，默认启动 tab 不是首页时也要拉
    LaunchedEffect(loginState.accountId, loginState.campusCardCacheVersion) {
        launch {
            HomeStatsRefresher.refreshDue(context, loginState.sessionManager, loginState.accountType)
            HomeSignals.bumpStatsVersion()
        }
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        // 校内外判定和刷新并行：跟随全局模式的站点在 SessionManager 里等判定落定，直连站点（教务等）不必等
        launch { runCatching { loginState.ensureCampusDetected() } }
        // 装新包会清掉课表缓存：首页「下一项安排」、考试倒计时要靠它，不等用户进日程页
        val manager = loginState.sessionManager
        if (manager != null) withContext(Dispatchers.IO) {
            val cache = com.xjtu.toolbox.data.DataCache(context)
            if (com.xjtu.toolbox.schedule.ScheduleCache.isComplete(cache)) return@withContext
            try {
                val site = manager.ensureSite(com.xjtu.toolbox.auth.LoginType.JWXT, silent = true)
                com.xjtu.toolbox.schedule.ScheduleSourceRouter.ensureCached(
                    cache, com.xjtu.toolbox.schedule.JwxtScheduleApi(site), manager,
                    studentId = loginState.activeUsername,
                )
                HomeSignals.scheduleVersion++
                com.xjtu.toolbox.widget.ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
                com.xjtu.toolbox.notification.DynamicScheduleScheduler.requestRefresh(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainScreen", "补课表缓存失败", e)
            }
        }
    }
    // 切回前台也跑一轮：各源按 TTL 决定拉不拉，图书馆签到这类有时效的状态不会停在上次打开时。
    // 和上面撞车时 refreshDue 自己会跳过后到的那次
    val resumeScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        com.xjtu.toolbox.notification.DynamicScheduleScheduler.requestRefresh(context)
        com.xjtu.toolbox.widget.ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
        resumeScope.launch {
            launch { runCatching { loginState.ensureCampusDetected() } }
            HomeStatsRefresher.refreshDue(context, loginState.sessionManager, loginState.accountType)
            HomeSignals.bumpStatsVersion()
        }
    }

    ProactiveReminderLoop(loginState)

    // 在小岱页时不自动冒泡（会遮输入框）
    LaunchedEffect(selectedTab) {
        ProactiveBubbleHost.autoSuppressed = selectedTab == BottomTab.PIDAI
    }

    // 只有手动点 tab 才震动，程序切 tab 不震
    val haptics = rememberHaptics()

    // 点小岱：切 tab，并让它在首屏说句闲话（底栏上的闲话气泡收掉，正事气泡保留）
    val onPidaiTap: () -> Unit = {
        if (selectedTab != BottomTab.PIDAI) haptics.tick()
        tabs.select(BottomTab.PIDAI)
        if (ProactiveBubbleHost.message?.id == ProactiveRules.CHATTER_ID) ProactiveBubbleHost.clear()
        ProactiveRules.pickOnTap(context)?.let { line ->
            ProactiveRules.markTapped(context, line)
            ProactiveBubbleHost.heroLine = line.text
            ProactiveBubbleHost.heroPokes++
        }
    }
    // 共享壳点某一格只走这一条回调（它不认哪一格是谁）：小岱那一格顺带冒气泡，其余格只切 + 震一下
    val onSelectTab: (ShellTab) -> Unit = { tab ->
        if (tab.id == BottomTab.PIDAI.name) {
            onPidaiTap()
        } else {
            if (tab.id != selectedBarTab.id) haptics.tick()
            ProactiveBubbleHost.clear()
            tabs.selectedId = tab.id
        }
    }

    // 小岱那一格的自绘（会动的机器人 + 气泡的锚点）；小岱不在栏上时锚点作废
    var pidaiAnchor by remember { mutableStateOf<Rect?>(null) }
    val tabArt = remember(shellTabs) {
        AndroidShellTabArt(
            tabs = shellTabs,
            pidai = PIDAI_TAB,
            onPidaiBoundsChange = { pidaiAnchor = it },
        )
    }

    val navState = ShellNavState(
        tabs = shellTabs,
        // 当前页 = 选中的那一格指向的那一页（每格与一屏一一对应，见 BarTab.shellTab）
        target = selectedBarTab.shellTab().target,
        onSelect = onSelectTab,
        accountBadge = ShellAccountBadge.of(navAccountCount, loginState.isLoggedIn),
        returning = tabs.returning,
    )

    // 新气泡出现时轻震一下
    val bubbleId = ProactiveBubbleHost.message?.id
    LaunchedEffect(bubbleId) {
        if (bubbleId != null) haptics.lowTick()
    }

    // 小岱提醒气泡，底栏版和侧栏版共用这一份，各自只管定位。玻璃采样层从共享壳手里拿
    // （见 `ShellHandles`）：尖角伸到底栏上时采「页面 + 底栏」的合成层。
    val bubbleView: @Composable (ShellHandles, ProactiveMessage, BubbleArrowSide, Dp, Dp?) -> Unit =
        { handles, msg, arrowSide, maxWidth, arrowFromStart ->
            ProactiveBubbleView(
                message = msg,
                arrowSide = arrowSide,
                arrowFromStart = arrowFromStart,
                maxWidth = maxWidth,
                backdrop = if (arrowSide != BubbleArrowSide.Start && handles.glassBarPresent) {
                    handles.combinedBackdrop
                } else {
                    handles.contentBackdrop
                },
                glass = glassStyle,
                onOpen = {
                    ProactiveRules.markUseful(context, msg.id)
                    ProactiveBubbleHost.clear()
                    // 气泡说的事收纳里也有一份：点开就是看过，别让收纳的红点还挂着
                    com.xjtu.toolbox.inbox.InboxStore.acknowledge(msg.inboxIds, msg.inboxCategory)
                    val route = msg.openRoute
                    when {
                        route != null -> router.open(route)
                        msg.prompt.isNotBlank() -> {
                            AgentPendingPrompt.set(msg.prompt, msg.eventSnapshot)
                            router.selectTab(BottomTab.PIDAI)
                        }
                        else -> router.selectTab(BottomTab.PIDAI)
                    }
                },
                onDismiss = {
                    ProactiveRules.markDismissed(context, msg.id)
                    ProactiveBubbleHost.clear()
                },
                onTimeout = { ProactiveBubbleHost.clear() },
            )
        }

    // 宽屏气泡定位用：侧栏小岱的根坐标，减去覆盖层自己的根坐标（转场平移时两者会不同）
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    AppShell(
        nav = navState,
        rootModifier = Modifier
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
        glassStyle = glassStyle,
        // 系统导航条那一格：玻璃底栏的空隙与浮空占位都按它算（见 ShellLayout）
        bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        // 窗口 chrome 是各端自己的事：宽屏时起始侧 inset 已被侧栏吃掉，手机则把内容铺到屏幕底边
        contentWindowInsets = if (isWide) {
            WindowInsets.systemBars.union(
                WindowInsets.displayCutout.exclude(WindowInsets.displayCutout.only(WindowInsetsSides.Start)),
            )
        } else {
            // 手机：内容铺到屏幕底边，从小白条和玻璃底栏下面滚过；末尾净空由各 tab 自己补
            WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
        tabArt = tabArt,
        snackbarHost = { SnackbarHost(router.messages) },
        topBar = { shellTab, handles ->
            // 顶栏留在这一端：它有 Android 的扫一扫图标（`R.drawable.ic_scan`）与每格自己的表头槽位。
            // 每页一份，按这一页自己的格子画，不看当前选中项
            val barTab = shellTab?.id?.let(BottomTab::of)
            MainTopBar(
                selectedTab = barTab,
                glassStyle = glassStyle,
                // 主 tab 的折叠行为来自 COUI；小岱 tab 的 SmallTopAppBar 仍吃 MIUIX
                couiScrollBehavior = when (barTab) {
                    BottomTab.COURSES -> coursesScrollBehavior
                    BottomTab.TOOLS -> toolsScrollBehavior
                    BottomTab.PROFILE -> profileScrollBehavior
                    else -> null
                },
                agentTitle = agentTitle,
                agentNavIcon = agentHeaderNavIcon,
                agentActions = agentHeaderActions,
                courseActions = courseHeaderActions,
                courseBottomContent = courseHeaderBottomContent,
                onScan = { ProactiveBubbleHost.clear(); revealSource = null; showQrLogin = true },
                onSearch = { ProactiveBubbleHost.clear(); revealSource = null; showGlobalSearch = true },
                onInbox = { ProactiveBubbleHost.clear(); router.open(AppRoute.Inbox) },
            )
        },
        barBubble = { handles ->
            val msg = ProactiveBubbleHost.message
            if (msg != null && selectedTab != BottomTab.PIDAI) {
                val screenWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
                // 小岱不在底栏上时没有可指的，气泡不画尖角
                val side = if (pidaiInBar) BubbleArrowSide.Bottom else BubbleArrowSide.None
                BarBubbleSlot(anchorX = pidaiAnchor?.center?.x?.takeIf { pidaiInBar }) { arrowFromStart ->
                    bubbleView(handles, msg, side, (screenWidth - 32.dp).coerceAtLeast(200.dp), arrowFromStart)
                }
            }
        },
        // 宽屏侧栏小岱旁的气泡（侧栏内部会裁掉溢出内容，所以做成并列的覆盖层）
        railOverlay = { handles ->
            RailProactiveBubble(
                anchor = { pidaiAnchor?.takeIf { pidaiInBar } },
                overlayOrigin = { overlayOrigin },
                bubbleView = { msg, side, maxWidth -> bubbleView(handles, msg, side, maxWidth, null) },
            )
        },
        // 搜索、扫一扫是盖满整屏的浮层：共享壳把它们放在最外层，不放进 Scaffold 的内容区
        overlays = { handles ->
            // 小岱的历史对话侧栏和设置页：盖住顶栏和底栏
            if (selectedTab == BottomTab.PIDAI) agentOverlays.Content()
            RevealOverlay(
                visible = showGlobalSearch,
                source = revealSource,
                progress = searchReveal,
            ) {
                CompositionLocalProvider(
                    LocalAppBackdrop provides if (glassStyle) handles.contentBackdrop else null,
                ) {
                    GlobalSearchScreen(
                        onBack = { showGlobalSearch = false },
                        onNavigate = { route ->
                            showGlobalSearch = false
                            router.open(route)
                        },
                        onAskAgent = { prompt ->
                            showGlobalSearch = false
                            AgentPendingPrompt.set(prompt)
                            router.selectTab(BottomTab.PIDAI)
                        },
                        onOpenUrl = { url ->
                            showGlobalSearch = false
                            router.open(AppRoute.Browser(url))
                        },
                        onOpenWithWebVpn = { vpnUrl ->
                            showGlobalSearch = false
                            pageDeps.onOpenWithWebVpn(vpnUrl)
                        },
                        accountType = loginState.accountType,
                    )
                }
            }

            RevealOverlay(
                visible = showQrLogin,
                source = revealSource,
                progress = qrReveal,
            ) {
                QrLoginScreen(
                    sessionManager = accountManager.sessionManager,
                    onBack = { showQrLogin = false },
                    // 图书馆座位码：进图书馆页并定位到这个座位
                    onLibrarySeat = { qr ->
                        showQrLogin = false
                        LibraryFocus.request(
                            LibraryFocus.Target(
                                campusId = LibraryQrArea.campusOf(qr.areaCode).id,
                                areaCode = qr.areaCode,
                                seatId = qr.seat,
                            ),
                        )
                        router.open(AppRoute.Library)
                    },
                )
            }
        },
        // 壳级弹层：反馈邀请与「密码可能已变更」
        sheets = {
            FeedbackPromptSheet(
                source = com.xjtu.toolbox.feedback.AppFeedbackSource,
                versionCode = com.xjtu.toolbox.BuildConfig.VERSION_CODE,
                versionName = com.xjtu.toolbox.BuildConfig.VERSION_NAME,
            )
            PasswordInvalidatedDialog(loginState, onUpdatePassword = { router.open(AppRoute.Settings) })
        },
        onBack = {
            when {
                showQrLogin -> showQrLogin = false
                showGlobalSearch -> showGlobalSearch = false
                // 先退回上一格（当前页缩回自己的按钮）；没有历史了再回首页
                tabs.back(bar.mapTo(HashSet()) { it.id }) -> Unit
                selectedTab != BottomTab.HOME -> tabs.jumpHome()
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
        },
        restoring = isRestoring,
        restoreStep = restoreStep,
        page = { shellPage, insets ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                // 覆盖层放大时，底下的页面慢慢往后退
                val reveal = maxOf(searchReveal.value, qrReveal.value)
                val s = androidx.compose.ui.util.lerp(1f, com.xjtu.toolbox.nav.ButtonZoom.RECEDE_SCALE, reveal)
                scaleX = s
                scaleY = s
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) ProactiveBubbleHost.clear()
                    }
                }
            }) {
                // 原生五格各一个分支（判据是格 id：BottomTab 是存盘与启动意图按它认的那个名字），
                // 其余是用户放上来的子页（`else`）。`shellPage.tab` 在 Android 上不会为 null
                when (shellPage.tab?.id) {
                    BottomTab.HOME.name -> HomeTab(
                        loginState,
                        isRestoring = isRestoring,
                        onNavigate = router::open,
                        onNavigateToProfile = { tabs.select(BottomTab.PROFILE) },
                        onNavigateToCourses = { router.selectTab(BottomTab.COURSES) },
                        // 首页不要顶栏，也就没有大标题的展开折叠
                        scrollBehavior = null,
                        extraBottomPadding = insets.bottomBarReserve,
                        showQuickActions = showQuickActions,
                        bulletins = heroBulletins,
                        onBulletinTap = onHeroBulletinTap,
                        onBulletinDismiss = onHeroBulletinDismiss,
                        contentTopPadding = insets.contentTopPadding,
                        contentTopShift = insets.contentTopShift,
                        topActions = {
                            HomeTopActions(
                                onInbox = { source ->
                                    // 收纳页以这颗圆钮为支点放大（转场按圆形起点走 ButtonZoom）
                                    val from = source.boundsInWindow()
                                    if (from != null) {
                                        com.xjtu.toolbox.nav.ExpandOrigins.arm(
                                            AppRoute.Inbox.id,
                                            com.xjtu.toolbox.nav.ExpandOrigins.Origin(
                                                from,
                                                from.width / 2f,
                                                // 面板上画的是圆钮每帧的真实画面，落地时和原位一致（见 RevealOverlay）
                                                snapshot = source.content,
                                                source = source,
                                                restFill = Color.Transparent,
                                                restShadow = false,
                                            ),
                                        )
                                    }
                                    router.open(AppRoute.Inbox)
                                },
                                onScan = { source ->
                                    revealSource = source
                                    showQrLogin = true
                                },
                                onSearch = { source ->
                                    revealSource = source
                                    showGlobalSearch = true
                                },
                            )
                        },
                    )

                    BottomTab.PIDAI.name -> AndroidAgentScreen(
                        loginState = loginState,
                        onNavigate = router::open,
                        // 输入栏钉在底边，要让开浮在上面的底栏
                        extraBottomPadding = insets.bottomBarReserve,
                        contentTopPadding = insets.contentTopPadding,
                        scrollBehavior = agentScrollBehavior,
                        onTitleChange = { agentTitle = it },
                        onActionsChange = { agentHeaderActions = it },
                        onNavIconChange = { agentHeaderNavIcon = it },
                        overlays = agentOverlays,
                    )

                    BottomTab.COURSES.name -> CoursesTab(
                        loginState = loginState,
                        onNavigate = router::open,
                        scrollBehavior = coursesScrollBehavior,
                        extraBottomPadding = insets.bottomBarReserve,
                        contentTopPadding = insets.contentTopPadding,
                        onActionsChange = { courseHeaderActions = it },
                        onBottomContentChange = { courseHeaderBottomContent = it },
                    )

                    BottomTab.TOOLS.name -> ZyxfBrowseScreen(
                        contentPadding = PaddingValues(bottom = insets.bottomBarReserve),
                        scrollBehavior = toolsScrollBehavior,
                        contentTopPadding = insets.contentTopPadding,
                    )

                    BottomTab.PROFILE.name -> ProfileTab(
                        loginState,
                        credentialStore,
                        accountManager,
                        onNavigate = router::open,
                        scrollBehavior = profileScrollBehavior,
                        extraBottomPadding = insets.bottomBarReserve,
                        onWarmupRequest = onWarmupRequest,
                        contentTopPadding = insets.contentTopPadding,
                    )

                    else -> bar.firstOrNull { it.id == shellPage.tab?.id }?.route?.let { route ->
                        RouteTabPage(route, insets, glassStyle, loginState) {
                            AppPageAsTab(route, pageDeps, onBack = { tabs.select(BottomTab.HOME) })
                        }
                    }
                }
            }
        },
    )
}

/**
 * 搜索、扫一扫这类整屏覆盖层的进出场：给了 [source] 就从那颗圆钮长成整页、收起时缩回去
 * （见 ButtonZoom）；没有起点就淡入淡出。
 *
 * 圆钮全程「挪进」覆盖层（[ExpandOriginSource.lifted]）：面板里画的是它每帧的真实画面（玻璃透出的底色、
 * 高光都在），收起落地那一帧再换回原位，前后像素一致。
 */
@Composable
private fun RevealOverlay(
    visible: Boolean,
    source: ExpandOriginSource?,
    progress: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    if (visible) shown = true
    // 起点在打开那一下定死，收起时回到同一处
    val button = remember(shown) { source?.takeIf { shown } }
    val from = remember(button) { button?.boundsInWindow() }
    val screenCornerPx = with(LocalDensity.current) {
        top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius().toPx()
    }
    LaunchedEffect(visible) {
        if (visible) {
            if (from != null) button?.lifted = { progress.value }
            progress.animateTo(1f, tween(800, easing = com.xjtu.toolbox.nav.ButtonZoom.Easing))
        } else if (shown) {
            progress.animateTo(0f, tween(620, easing = com.xjtu.toolbox.nav.ButtonZoom.Easing))
            // 同一帧：覆盖层撤走、圆钮回原位
            button?.lifted = null
            shown = false
        }
    }
    // 覆盖层被直接移出组合时也把圆钮还回去
    androidx.compose.runtime.DisposableEffect(button) { onDispose { button?.lifted = null } }
    if (!shown) return
    // 一层淡灰压住后面的内容（底栏也在覆盖层下面），和子页转场的变暗同一个意思
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = com.xjtu.toolbox.nav.ButtonZoom.RECEDE_DIM * progress.value)) }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = progress.value
                    if (from != null && size.width > 0f) {
                        com.xjtu.toolbox.nav.ButtonZoom.apply(this, from, size, t, screenCornerPx)
                        alpha = com.xjtu.toolbox.nav.ButtonZoom.contentAlpha(t)
                    } else {
                        alpha = t
                    }
                },
        ) { content() }
        if (from != null && button != null) {
            var full by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
            Box(Modifier.fillMaxSize().onSizeChanged { full = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }) {
                com.xjtu.toolbox.nav.ButtonMorphCover(
                    from, { full }, { progress.value }, screenCornerPx, MiuixTheme.colorScheme.surface,
                    // 圆钮本身是玻璃、只有一圈白色微光：面板从透明长起，贴近按钮时不带深色投影
                    restFill = Color.Transparent,
                    restShadow = false,
                ) { button.content(Modifier.fillMaxSize()) }
            }
        }
    }
}

/** 「扫一扫」圆钮。[onScan] 收到这颗圆钮，覆盖层从它长出来。 */
@Composable
private fun HomeScanAction(onScan: (ExpandOriginSource) -> Unit) {
    val tint = MiuixTheme.colorScheme.onSurface
    val source = rememberExpandOriginSource()
    HomeRoundAction(onClick = { onScan(source) }, source = source) {
        Icon(
            androidx.compose.ui.res.painterResource(com.xjtu.toolbox.R.drawable.ic_scan),
            contentDescription = "扫一扫",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 首页顶栏右上角那一排圆钮：搜索、扫码、消息。 */
@Composable
private fun HomeTopActions(
    onInbox: (ExpandOriginSource) -> Unit,
    onScan: (ExpandOriginSource) -> Unit,
    onSearch: (ExpandOriginSource) -> Unit,
) {
    val tint = MiuixTheme.colorScheme.onSurface
    val searchSource = rememberExpandOriginSource()
    val inboxSource = rememberExpandOriginSource()
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeRoundAction(
            onClick = { onSearch(searchSource) },
            source = searchSource,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = tint, modifier = Modifier.size(21.dp))
        }
        HomeScanAction(onScan = onScan)
        // 铃铛：角标画在**圆钮外面这一层**。圆钮的液态玻璃把内容裁成圆形，画在里面的右上角
        // 一定会被那道圆弧削掉一块；角标位置还是原来的（圆钮右上角）。
        Box {
            HomeRoundAction(
                onClick = { onInbox(inboxSource) },
                source = inboxSource,
            ) {
                InboxBell(
                    {},
                    clickable = false,
                    iconSize = 22.dp,
                    containerSize = HomeTopActionSize,
                    showBadge = false,
                )
            }
            com.xjtu.toolbox.inbox.InboxUnreadBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 8.dp)
                    .graphicsLayer { alpha = if (com.xjtu.toolbox.nav.ExpandOrigins.isHidden(inboxSource)) 0f else 1f },
            )
        }
    }
}

/** 首页顶栏圆钮的视觉直径：COUI 顶栏按钮是 40dp 圆（外加 48dp 最小交互尺寸）。 */
private val HomeTopActionSize = 40.dp

/**
 * 顶栏圆钮：COUI-Kit `TopAppBarButton` 原样——40dp 圆形视觉 + 48dp 交互尺寸、AGSL 浮层材质
 * （边缘光、内光晕、轮廓投影、跟手形变与按压弹簧）。材质采的是首页顶栏表面录下的那一层
 * （`LocalCouiToolbarBackdrop`），**两套首页布局下是同一颗钮**，切换功能区不会换材质。
 *
 * [source] 只用于「覆盖层从这颗钮长出来」的一镜到底起点。
 */
@Composable
private fun HomeRoundAction(
    onClick: () -> Unit,
    /** 记下这颗圆钮的位置：点开的页面 / 覆盖层从它长出来。 */
    source: ExpandOriginSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.then(if (source != null) Modifier.expandOriginSource(source) else Modifier)) {
        CouiToolbarActionButton(onClick = onClick, content = content)
    }
}

/** 主界面顶栏：glass 风格下由 COUI 顶栏表面采样这一页正文（主 tab 大字、小岱钉死小标题）。 */
@Composable
private fun MainTopBar(
    /** null = 选中的是放上来的子页（自带顶栏）。 */
    selectedTab: BottomTab?,
    glassStyle: Boolean,
    /** COUI 折叠行为：主 tab 的 COUI 大字顶栏用它（首页/子页不用）。 */
    couiScrollBehavior: CouiTopBarScrollBehavior?,
    agentTitle: String,
    agentNavIcon: (@Composable () -> Unit)?,
    agentActions: (@Composable RowScope.() -> Unit)?,
    courseActions: (@Composable RowScope.() -> Unit)?,
    courseBottomContent: (@Composable () -> Unit)?,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onInbox: () -> Unit,
) {
    val color = if (glassStyle) Color.Transparent else MiuixTheme.colorScheme.surface
    // 首页没有顶栏（标题「岱宗盒子」没有信息量）：按钮挪进首页内容里，状态栏留白由 Scaffold 的 insets 给；
    // 放到底栏上的子页自带顶栏
    if (selectedTab == null || selectedTab == BottomTab.HOME) return
    // 小岱是自下而上的聊天，只用钉死的小标题（COUI 原版 TopAppBar，52dp）
    if (selectedTab == BottomTab.PIDAI) {
        CouiPageTopAppBar(
            title = agentTitle,
            pinned = true,
            color = color,
            pageBackdrop = LocalCouiPageBackdrop.current,
            navigationIcon = { agentNavIcon?.invoke() },
            actions = { agentActions?.invoke(this) },
        )
        return
    }
    val title = when (selectedTab) {
        BottomTab.PIDAI -> agentTitle
        BottomTab.TOOLS -> ZYXF_TITLE
        else -> selectedTab.label
    }
    // 主 tab 的大字顶栏就是 COUI 原版 LargeTopAppBar：110dp → 52dp、原版 AppBarLayout 测量与
    // CouiTopAppBarSurface 渐变模糊；折叠进度由 COUI 自己的 scrollBehavior 驱动。
    CouiPageTopAppBar(
        color = color,
        title = title,
        largeTitle = title,
        scrollBehavior = couiScrollBehavior,
        pageBackdrop = LocalCouiPageBackdrop.current,
        actions = {
            if (selectedTab == BottomTab.COURSES) courseActions?.invoke(this)
        },
        bottomContent = {
            if (selectedTab == BottomTab.COURSES) {
                // 日程的标签行、周标题栏和顶栏是一整块玻璃，分开画接缝处会有模糊边
                CompositionLocalProvider(LocalOnGlassBar provides glassStyle) {
                    courseBottomContent?.invoke()
                }
            }
        },
    )
}

/**
 * 小岱主动提醒：定时用本地缓存算一条提醒塞进 [ProactiveBubbleHost]，不发请求。
 * 节流由 [ProactiveRules.pick] 的冷却负责。
 */
@Composable
private fun ProactiveReminderLoop(loginState: AppLoginState) {
    val context = LocalContext.current
    LaunchedEffect(loginState.accountId, loginState.isLoggedIn) {
        delay(ProactiveRules.FIRST_DELAY_MS)
        val cardPrefs = CampusCardCache.cardPrefs(context)
        while (true) {
            val balance = cardPrefs.getFloat("card_balance_cache", -1f).takeIf { it >= 0f }?.toDouble()
            val focus = HomeSignals.scheduleReminder
            val minutes = focus?.let { Duration.between(LocalDateTime.now(com.xjtu.toolbox.util.CampusZoneId), it.startAt).toMinutes() }
            val pendingScores = HomeStats.pendingNewScores(context)
            val unseenNotice = HomeStats.unseenNoticeTitle(context)
            val unseenNoticeLink = HomeStats.unseenNoticeLink(context)
            val libraryTodo = HomeSignals.libraryUrgentAction
            val nextExam = withContext(Dispatchers.IO) { cachedNextExam(com.xjtu.toolbox.data.DataCache(context)) }
            val msg = ProactiveRules.pick(
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
                    "-> ${msg?.text ?: "无"}",
            )
            if (msg != null && ProactiveBubbleHost.message == null && !ProactiveBubbleHost.autoSuppressed) {
                ProactiveRules.markShown(context, msg)
                // 说过就消费掉，否则冷却一过会重推同一条
                when (msg.id) {
                    "grade" -> HomeStats.setPendingNewScores(context, 0)
                    "notice" -> HomeStats.clearUnseenNotice(context)
                    "schedule_change" -> ScheduleDiff.setPending(scheduleDiffStore(), null)
                    "attendance" -> HomeSignals.attendanceAlert = null
                    "coupon" -> HomeSignals.couponAlert = null
                    // 清空即可，下一轮刷新会按服务端状态重填
                    "library" -> HomeSignals.libraryUrgentAction = null
                }
                ProactiveBubbleHost.message = msg
            }
            delay(ProactiveRules.EVAL_INTERVAL_MS)
        }
    }
}

/** 日程页：副标题、按钮、标签行由日程页送上来，挂到宿主的顶栏（主顶栏或压栈页自己的）。 */
@Composable
internal fun CoursesTab(
    loginState: AppLoginState,
    onNavigate: (AppRoute) -> Unit,
    scrollBehavior: CouiTopBarScrollBehavior,
    extraBottomPadding: Dp,
    contentTopPadding: Dp,
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit,
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit,
) {
    // 日程页的宿主面（本地落盘 / 教务会话 / 分享面板 / 考勤与思源那几档）—— 屏在 `:core`，
    // 这一端只把它认得的那些能力接上去。见 `AppScheduleHost` 那张表。
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheduleHost = remember(loginState) {
        com.xjtu.toolbox.schedule.AppScheduleHost(context, loginState)
    }
    Box(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        ScheduleScreen(
            host = scheduleHost,
            onBack = {},
            onActionsChange = onActionsChange,
            onBottomContentChange = onBottomContentChange,
            contentBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            topAppBarScrollBehavior = scrollBehavior,
            onNavigate = onNavigate,
        )
    }
}

/** 某个站点登录失败、疑似密码已改：暂停其他系统的自动登录，提示去更新密码。 */
@Composable
private fun PasswordInvalidatedDialog(loginState: AppLoginState, onUpdatePassword: () -> Unit) {
    if (!loginState.passwordInvalidatedDialogVisible) return
    val dismiss = { loginState.passwordInvalidatedDialogVisible = false }
    BackHandler(onBack = dismiss)
    OverlayDialog(
        show = true,
        title = "登录密码可能已变更",
        summary = "「${loginState.passwordInvalidatedSiteName}」登录失败，已暂停自动登录以免账号被锁，请更新密码。",
        onDismissRequest = dismiss,
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "稍后", onClick = dismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "去更新密码",
                onClick = {
                    dismiss()
                    onUpdatePassword()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

