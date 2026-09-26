package com.xjtu.toolbox.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.account.AccountManager
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AgentScreen
import com.xjtu.toolbox.agent.BubbleArrowSide
import com.xjtu.toolbox.agent.ProactiveBubbleHost
import com.xjtu.toolbox.agent.ProactiveBubbleView
import com.xjtu.toolbox.agent.ProactiveMessage
import com.xjtu.toolbox.agent.ProactiveRules
import com.xjtu.toolbox.auth.AppLoginState
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
import com.xjtu.toolbox.profile.ProfileTab
import com.xjtu.toolbox.qrlogin.QrLoginScreen
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.ScheduleDiff
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.ui.WindowSize
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.ui.currentWindowSize
import com.xjtu.toolbox.ui.glass.LocalAppBackdrop
import com.xjtu.toolbox.ui.glass.LocalOnGlassBar
import com.xjtu.toolbox.ui.glass.followTopBar
import com.xjtu.toolbox.ui.glass.glassBarSurface
import com.xjtu.toolbox.ui.glass.glassBarTint
import com.xjtu.toolbox.ui.glass.rememberStableTopPadding
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.ui.rememberHaptics
import com.xjtu.toolbox.zyxf.ZyxfBrowseScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import java.time.LocalDateTime

/** 主界面（返回栈栈底）：底栏 / 宽屏侧栏 + 五个 tab。跳转走 [router]，选中的 tab 在 [tabs]。 */
@Composable
internal fun MainScreen(
    router: AppRouter,
    tabs: MainTabState,
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
    val homeTheme by appearance.homeTheme.collectAsStateWithLifecycle()
    val showQuickActions by appearance.showQuickActions.collectAsStateWithLifecycle()

    val selectedTab = tabs.selected
    val navAccountCount = remember(loginState.accountId) { accountManager.accountList().size }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var showGlobalSearch by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showQrLogin -> showQrLogin = false
            showGlobalSearch -> showGlobalSearch = false
            selectedTab != BottomTab.HOME -> tabs.selected = BottomTab.HOME
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

    // 选中 / 未选中只靠颜色区分（两态共用同一枚图标）
    val navSelectedContent = MiuixTheme.colorScheme.primary
    val navUnselectedContent = MiuixTheme.colorScheme.onSurfaceContainer
    val navItemColors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = navSelectedContent,
        unselectedContentColor = navUnselectedContent,
    )
    // 图标统一放进同样高的框里，五格文字基线才对齐
    val navIconBox = remember { BottomTab.entries.maxOf { it.iconSize } }

    // 只有手动点 tab 才震动，程序切 tab 不震
    val haptics = rememberHaptics()
    fun userSelectTab(tab: BottomTab) {
        if (tab != selectedTab) haptics.tick()
        tabs.selected = tab
    }

    // ── 各 Tab 独立的滚动折叠状态 ──
    val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val coursesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val toolsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val profileScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val agentScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    val isWide = isWideLayout()

    // 界面风格：玻璃（默认）或经典；经典下所有玻璃效果一起关掉，兼作性能模式
    val glassStyle = navBarStyle == CredentialStore.NAV_STYLE_FLOATING
    // 玻璃底栏只用于手机竖屏，平板竖屏用 miuix 悬浮胶囊
    val useGlassBar = glassStyle && !isWide && currentWindowSize() == WindowSize.Compact
    // 玻璃采样源只录 tab 内容区；录整个 Scaffold 会让底栏采样自己，RenderThread 直接崩
    val appBackdrop = rememberLayerBackdrop()
    // 底栏导出成一层，屁岱气泡尖角伸到底栏上时采的是「页面 + 底栏」
    val glassBarExport = rememberLayerBackdrop()
    val phoneBubbleBackdrop = rememberCombinedBackdrop(appBackdrop, glassBarExport)

    // 悬浮底栏的总占位：玻璃底栏 58dp + 离导航条 8dp（无导航条 20dp）；
    // 平板悬浮胶囊 52dp + 26dp + inset（无导航条 36dp），数值照 miuix 实现
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val glassBarBottomGap = if (navInset > 0.dp) 8.dp else 20.dp
    // floatingToolbar 槽位离屏幕底边有 miuix 私有的 4dp（FloatingToolbarSpacing），这里补偿掉
    val miuixFloatingToolbarSpacing = 4.dp
    val glassBarSlotBottomPadding = (navInset + glassBarBottomGap - miuixFloatingToolbarSpacing).coerceAtLeast(0.dp)
    val floatingBarReserve = when {
        useGlassBar -> GLASS_BAR_HEIGHT + glassBarBottomGap + navInset
        !isWide && glassStyle -> FLOATING_BAR_HEIGHT + (if (navInset > 0.dp) 26.dp + navInset else 36.dp)
        else -> 0.dp
    }

    // COURSES tab 副标题 + actions slot + bottomContent slot
    var courseSubtitle by remember { mutableStateOf("") }
    var courseHeaderActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var courseHeaderBottomContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // PIDAI tab 的标题与顶栏按钮，由屁岱页反向送上来
    var agentTitle by remember { mutableStateOf("屁岱") }
    var agentHeaderActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var agentHeaderNavIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // 首页数据拉取挂在这里而不是 HomeTab：tab 懒加载，默认启动 tab 不是首页时也要拉
    LaunchedEffect(loginState.accountId, loginState.campusCardCacheVersion) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        HomeStatsRefresher.refreshDue(context, loginState.sessionManager, loginState.accountType)
        HomeSignals.bumpStatsVersion()
    }

    ProactiveReminderLoop(loginState)

    // 在屁岱页时不自动冒泡（会遮输入框）
    LaunchedEffect(selectedTab) {
        ProactiveBubbleHost.autoSuppressed = selectedTab == BottomTab.PIDAI
    }

    // 点屁岱：切 tab，并让它在首屏说句闲话（底栏上的闲话气泡收掉，正事气泡保留）
    val onPidaiTap: () -> Unit = {
        userSelectTab(BottomTab.PIDAI)
        if (ProactiveBubbleHost.message?.id == ProactiveRules.CHATTER_ID) ProactiveBubbleHost.clear()
        ProactiveRules.pickOnTap(context)?.let { line ->
            ProactiveRules.markTapped(context, line)
            ProactiveBubbleHost.heroLine = line.text
            ProactiveBubbleHost.heroPokes++
        }
    }
    val navState = MainNavState(
        selected = selectedTab,
        isLoggedIn = loginState.isLoggedIn,
        accountCount = navAccountCount,
        onSelect = ::userSelectTab,
        onPidaiTap = onPidaiTap,
    )

    // 新气泡出现时轻震一下
    val bubbleId = ProactiveBubbleHost.message?.id
    LaunchedEffect(bubbleId) {
        if (bubbleId != null) haptics.lowTick()
    }

    // 屁岱提醒气泡，底栏版和侧栏版共用这一份，各自只管定位
    val bubbleView: @Composable (ProactiveMessage, BubbleArrowSide, Dp) -> Unit = { msg, arrowSide, maxWidth ->
        ProactiveBubbleView(
            message = msg,
            arrowSide = arrowSide,
            maxWidth = maxWidth,
            backdrop = if (arrowSide == BubbleArrowSide.Bottom && useGlassBar) phoneBubbleBackdrop else appBackdrop,
            glass = glassStyle,
            onOpen = {
                ProactiveRules.markUseful(context, msg.id)
                ProactiveBubbleHost.clear()
                val route = msg.openRoute
                when {
                    route != null -> router.open(route)
                    msg.prompt.isNotBlank() -> {
                        AgentPendingPrompt.set(msg.prompt, msg.eventSnapshot)
                        tabs.selected = BottomTab.PIDAI
                    }
                    else -> tabs.selected = BottomTab.PIDAI
                }
            },
            onDismiss = {
                ProactiveRules.markDismissed(context, msg.id)
                ProactiveBubbleHost.clear()
            },
            onTimeout = { ProactiveBubbleHost.clear() },
        )
    }

    // 零高度 layout 往上溢出绘制：气泡出现不撑高底栏，页面不跳
    val proactiveBubbleSlot: @Composable () -> Unit = {
        val msg = ProactiveBubbleHost.message
        if (msg != null && selectedTab != BottomTab.PIDAI) {
            val screenWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                bubbleView(msg, BubbleArrowSide.Bottom, (screenWidth - 32.dp).coerceAtLeast(200.dp))
            }
        }
    }

    // 宽屏气泡定位用：侧栏屁岱的根坐标，减去覆盖层自己的根坐标（转场平移时两者会不同）
    var pidaiAnchor by remember { mutableStateOf<Rect?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // Row 包 Scaffold：侧栏放在 Scaffold 外面，才不会随各 tab 顶栏高度变化上下跳
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
        val railState = rememberNavigationRailState()
        // 与 miuix 侧栏内部同一条弹簧，用来判断侧栏是否还在动
        val railProgress = animateFloatAsState(
            targetValue = if (railState.isExpanded) 1f else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 322f, visibilityThreshold = 0.001f),
            label = "railProgress",
        )
        Row(Modifier.fillMaxSize()) {
            if (isWide) {
                MainNavigationRail(navState, railState, onPidaiBoundsChange = { pidaiAnchor = it })
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isWide) Modifier.clipToBounds() else Modifier)
                    .then(if (isWide) Modifier.railSettledWidth(railState, railProgress) else Modifier),
                // 宽屏时起始侧 inset 已被侧栏吃掉，别再留一次
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
                snackbarHost = { SnackbarHost(router.messages) },
                topBar = {
                    MainTopBar(
                        selectedTab = selectedTab,
                        glassStyle = glassStyle,
                        backdrop = appBackdrop,
                        scrollBehavior = when (selectedTab) {
                            BottomTab.HOME -> homeScrollBehavior
                            BottomTab.COURSES -> coursesScrollBehavior
                            BottomTab.PIDAI -> agentScrollBehavior
                            BottomTab.TOOLS -> toolsScrollBehavior
                            BottomTab.PROFILE -> profileScrollBehavior
                        },
                        agentTitle = agentTitle,
                        agentNavIcon = agentHeaderNavIcon,
                        agentActions = agentHeaderActions,
                        courseSubtitle = courseSubtitle,
                        courseActions = courseHeaderActions,
                        courseBottomContent = courseHeaderBottomContent,
                        onScan = { showQrLogin = true },
                        onSearch = { showGlobalSearch = true },
                    )
                },
                bottomBar = {
                    if (!isWide && !glassStyle) ClassicBottomBar(navState, navItemColors, proactiveBubbleSlot)
                },
                floatingToolbar = {
                    when {
                        useGlassBar -> GlassBottomBar(
                            nav = navState,
                            selectedColor = navSelectedContent,
                            unselectedColor = navUnselectedContent,
                            iconBox = navIconBox,
                            backdrop = appBackdrop,
                            exportedBackdrop = glassBarExport,
                            bottomPadding = glassBarSlotBottomPadding,
                            bubble = proactiveBubbleSlot,
                        )
                        !isWide && glassStyle -> FloatingBottomBar(
                            nav = navState,
                            colors = navItemColors,
                            offsetY = miuixFloatingToolbarSpacing,
                            bubble = proactiveBubbleSlot,
                        )
                    }
                },
            ) { padding ->
                // 玻璃风格下内容铺到顶栏下面，顶部留白交给各 tab 的滚动内容。顶栏高度折叠时每帧在变，
                // 各 tab 只拿稳定的最大值，差额由 followTopBar 在布局阶段补，避免每帧重组
                val stableTopBar = rememberStableTopPadding(padding)
                val tabTopPadding = if (glassStyle) stableTopBar.value else 0.dp
                val layoutDirection = LocalLayoutDirection.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            if (glassStyle) {
                                PaddingValues(
                                    start = padding.calculateStartPadding(layoutDirection),
                                    end = padding.calculateEndPadding(layoutDirection),
                                    bottom = padding.calculateBottomPadding(),
                                )
                            } else {
                                padding
                            },
                        ),
                ) {
                    TabHost(
                        selectedTab = selectedTab,
                        modifier = if (glassStyle) Modifier.layerBackdrop(appBackdrop) else Modifier,
                        tabModifier = { if (glassStyle) Modifier.followTopBar({ stableTopBar.value }, padding) else Modifier },
                    ) { tab ->
                        when (tab) {
                            BottomTab.HOME -> HomeTab(
                                loginState,
                                isRestoring = isRestoring,
                                onNavigate = router::open,
                                onNavigateToProfile = { tabs.selected = BottomTab.PROFILE },
                                onNavigateToCourses = { tabs.selected = BottomTab.COURSES },
                                scrollBehavior = homeScrollBehavior,
                                extraBottomPadding = floatingBarReserve,
                                homeTheme = homeTheme,
                                showQuickActions = showQuickActions,
                                bulletins = heroBulletins,
                                onBulletinTap = onHeroBulletinTap,
                                onBulletinDismiss = onHeroBulletinDismiss,
                                contentTopPadding = tabTopPadding,
                            )
                            BottomTab.PIDAI -> AgentScreen(
                                // 输入栏钉在底边，要让开浮在上面的底栏
                                extraBottomPadding = floatingBarReserve,
                                hostBottomPadding = padding.calculateBottomPadding(),
                                scrollBehavior = agentScrollBehavior,
                                onTitleChange = { agentTitle = it },
                                onActionsChange = { agentHeaderActions = it },
                                onNavIconChange = { agentHeaderNavIcon = it },
                                onNavigate = router::open,
                                contentTopPadding = tabTopPadding,
                            )
                            BottomTab.COURSES -> CoursesTab(
                                loginState = loginState,
                                onNavigate = router::open,
                                scrollBehavior = coursesScrollBehavior,
                                extraBottomPadding = floatingBarReserve,
                                contentTopPadding = tabTopPadding,
                                onSubtitleChange = { courseSubtitle = it },
                                onActionsChange = { courseHeaderActions = it },
                                onBottomContentChange = { courseHeaderBottomContent = it },
                            )
                            BottomTab.TOOLS -> ZyxfBrowseScreen(
                                contentPadding = PaddingValues(bottom = floatingBarReserve),
                                scrollBehavior = toolsScrollBehavior,
                                contentTopPadding = tabTopPadding,
                            )
                            BottomTab.PROFILE -> ProfileTab(
                                loginState,
                                credentialStore,
                                accountManager,
                                onNavigate = router::open,
                                scrollBehavior = profileScrollBehavior,
                                extraBottomPadding = floatingBarReserve,
                                onWarmupRequest = onWarmupRequest,
                                contentTopPadding = tabTopPadding,
                            )
                        }
                    }

                    RestoreBanner(
                        visible = isRestoring,
                        step = restoreStep,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = floatingBarReserve),
                    )

                    FeedbackPromptSheet()
                    PasswordInvalidatedDialog(loginState, onUpdatePassword = { router.open(AppRoute.Settings) })
                }

                if (showGlobalSearch) {
                    CompositionLocalProvider(LocalAppBackdrop provides if (glassStyle) appBackdrop else null) {
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
                            accountType = loginState.accountType,
                        )
                    }
                }

                if (showQrLogin) {
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
                                )
                            )
                            router.open(AppRoute.Library)
                        },
                    )
                }
            }
        }

        if (isWide) {
            RailProactiveBubble(
                anchor = { pidaiAnchor },
                overlayOrigin = { overlayOrigin },
                bubbleView = bubbleView,
            )
        }
    }
}

/** 主界面顶栏，玻璃风格下采样 tab 内容区。 */
@Composable
private fun MainTopBar(
    selectedTab: BottomTab,
    glassStyle: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    scrollBehavior: ScrollBehavior,
    agentTitle: String,
    agentNavIcon: (@Composable () -> Unit)?,
    agentActions: (@Composable RowScope.() -> Unit)?,
    courseSubtitle: String,
    courseActions: (@Composable RowScope.() -> Unit)?,
    courseBottomContent: (@Composable () -> Unit)?,
    onScan: () -> Unit,
    onSearch: () -> Unit,
) {
    val tint = glassBarTint()
    val color = if (glassStyle) Color.Transparent else MiuixTheme.colorScheme.surface
    val modifier = if (glassStyle) Modifier.glassBarSurface(backdrop, tint) else Modifier
    // 屁岱是自下而上的聊天，只用小标题
    if (selectedTab == BottomTab.PIDAI) {
        SmallTopAppBar(
            title = agentTitle,
            color = color,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            navigationIcon = { agentNavIcon?.invoke() },
            actions = { agentActions?.invoke(this) },
        )
        return
    }
    val title = when (selectedTab) {
        BottomTab.HOME -> "岱宗盒子"
        BottomTab.COURSES -> "日程"
        BottomTab.PIDAI -> agentTitle
        BottomTab.TOOLS -> "仲英学辅资料站"
        BottomTab.PROFILE -> "我的"
    }
    TopAppBar(
        color = color,
        modifier = modifier,
        title = title,
        largeTitle = title,
        subtitle = if (selectedTab == BottomTab.COURSES) courseSubtitle else "",
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (selectedTab == BottomTab.HOME) {
                IconButton(onClick = onScan) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "扫一扫", tint = MiuixTheme.colorScheme.onSurface)
                }
            }
        },
        actions = {
            if (selectedTab == BottomTab.COURSES) courseActions?.invoke(this)
            if (selectedTab == BottomTab.HOME) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "搜索", tint = MiuixTheme.colorScheme.onSurface)
                }
            }
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

/** 五个 tab 的内容区：选中过才进组合，切走的留在组合里只是透明；切换带横移 + 缩放。 */
@Composable
private fun TabHost(
    selectedTab: BottomTab,
    modifier: Modifier,
    tabModifier: () -> Modifier,
    content: @Composable (BottomTab) -> Unit,
) {
    var composedTabs by remember { mutableStateOf(setOf(selectedTab)) }
    LaunchedEffect(selectedTab) { composedTabs = composedTabs + selectedTab }
    var previousTabOrdinal by rememberSaveable { mutableIntStateOf(selectedTab.ordinal) }
    val tabSwitchDirection = selectedTab.ordinal.compareTo(previousTabOrdinal)
    LaunchedEffect(selectedTab) {
        delay(280)
        previousTabOrdinal = selectedTab.ordinal
    }
    val tabSlideDistance = with(LocalDensity.current) { 28.dp.toPx() }
    Box(modifier.fillMaxSize()) {
        BottomTab.entries.forEach { tab ->
            key(tab) {
                if (tab !in composedTabs) return@key
                val isActive = selectedTab == tab
                val tabAlpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(if (isActive) 240 else 180),
                    label = "tabAlpha",
                )
                val tabOffset by animateFloatAsState(
                    targetValue = when {
                        isActive || tabSwitchDirection == 0 -> 0f
                        tab.ordinal < selectedTab.ordinal -> -tabSlideDistance
                        else -> tabSlideDistance
                    },
                    animationSpec = tween(260),
                    label = "tabOffset",
                )
                val tabScale by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.985f,
                    animationSpec = tween(260),
                    label = "tabScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        // 隐藏的 tab 冻结测量约束，侧栏动画时不跟着逐帧重排
                        .freezeLayoutWhile { !isActive && tabAlpha == 0f }
                        .then(tabModifier())
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
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    // 让隐藏 tab 里的常驻动画停下
                    CompositionLocalProvider(LocalPageVisible provides isActive) {
                        content(tab)
                    }
                }
            }
        }
    }
}

/**
 * 屁岱主动提醒：定时用本地缓存算一条提醒塞进 [ProactiveBubbleHost]，不发请求。
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
            val minutes = focus?.let { Duration.between(LocalDateTime.now(), it.startAt).toMinutes() }
            val pendingScores = HomeStats.pendingNewScores(context)
            val unseenNotice = HomeStats.unseenNoticeTitle(context)
            val unseenNoticeLink = HomeStats.unseenNoticeLink(context)
            val libraryTodo = HomeSignals.libraryUrgentAction
            val nextExam = withContext(Dispatchers.IO) { ExamCountdown.fromCache(context) }
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
                    "schedule_change" -> ScheduleDiff.setPending(context, null)
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

/** 日程 tab：副标题、按钮、标签行由日程页送上来挂到主顶栏。 */
@Composable
private fun CoursesTab(
    loginState: AppLoginState,
    onNavigate: (AppRoute) -> Unit,
    scrollBehavior: ScrollBehavior,
    extraBottomPadding: Dp,
    contentTopPadding: Dp,
    onSubtitleChange: (String) -> Unit,
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit,
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit,
) {
    Box(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        ScheduleScreen(
            site = loginState.sessionManager?.getSiteOrNull("jwxt"),
            studentId = loginState.activeUsername,
            onBack = {},
            onSubtitleChange = onSubtitleChange,
            onActionsChange = onActionsChange,
            onBottomContentChange = onBottomContentChange,
            contentBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            topAppBarScrollBehavior = scrollBehavior,
            onNavigate = onNavigate,
        )
    }
}

@Composable
private fun RestoreBanner(visible: Boolean, step: String, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        RestoreBannerContent(step)
    }
}

@Composable
private fun RestoreBannerContent(step: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                step.ifEmpty { "正在恢复登录..." },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
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
        summary = "「${loginState.passwordInvalidatedSiteName}」登录失败，已暂停其他系统的自动登录以保护账号。请在设置中更新密码。",
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

/** [frozen] 为真时沿用上一次的测量约束，子树不跟着父布局尺寸动画重排。 */
@Composable
private fun Modifier.freezeLayoutWhile(frozen: () -> Boolean): Modifier {
    val last = remember { arrayOfNulls<Constraints>(1) }
    return this.layout { measurable, constraints ->
        val use = if (frozen()) last[0] ?: constraints else constraints.also { last[0] = it }
        val placeable = measurable.measure(use)
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.place(0, 0)
        }
    }
}
