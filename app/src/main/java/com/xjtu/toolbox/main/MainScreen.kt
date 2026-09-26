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

/**
 * 主界面：底栏（手机）/ 侧栏（宽屏）+ 五个 tab，是返回栈的栈底。
 *
 * 跳转一律走 [router]；选中哪个 tab 由 [tabs] 持有（提在导航根部，深链、快捷方式能直接切 tab）。
 */
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

    // miuix：底栏条目可单独设选中色。选中跟主题色（含取色），未选中仍走容器字色。
    // 底栏图标两个状态共用一枚实心图标（见 BottomTab），所以这两个色值是选中/未选中的
    // 全部差别所在：经典底栏走下面的 NavigationBarItemColors，玻璃底栏自己取它们给图标和文字。
    val navSelectedContent = MiuixTheme.colorScheme.primary
    val navUnselectedContent = MiuixTheme.colorScheme.onSurfaceContainer
    val navItemColors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = navSelectedContent,
        unselectedContentColor = navUnselectedContent,
    )
    // 各格图标的光学尺寸不同（见 BottomTab.iconSize），统一放进这么大的框里居中：不统一的话
    // 每格「图标 + 文字」的总高会差出几 dp，五格的文字基线就错开了。
    val navIconBox = remember { BottomTab.entries.maxOf { it.iconSize } }

    // 富触感（PR T）：只有用户自己点底栏 / 侧栏切 tab 才震一下轻 tick。
    // 深链、快捷方式、搜索跳 tab 走 router，不震——那不是手上的动作。
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

    // 大屏适配：宽屏（侧边 NavigationRail）与否由导航根部统一算好向下提供，
    // 规则见 ui/WindowSize.kt。平板、折叠屏内屏、手机横屏都可能进这一支。
    val isWide = isWideLayout()

    // 界面风格（plan2 §17 第 12 条）：沿用原来「底栏风格」的取值，"floating" = 玻璃（默认），
    // "classic" = 经典。选经典时，底栏回到经典样式，所有玻璃点（底栏、侧栏、气泡、搜索浮层、
    // 二级页顶栏）一起退回不透明；经典同时就是「性能模式」，不另设玻璃开关。
    val glassStyle = navBarStyle == CredentialStore.NAV_STYLE_FLOATING
    // 玻璃底栏只在手机竖屏换（宽度 < 600dp 且不是宽屏）。平板竖屏保留原来的悬浮胶囊：
    // 那里刚在 PR A 修过，不再动。
    val useGlassBar = glassStyle && !isWide && currentWindowSize() == WindowSize.Compact
    // 玻璃的采样源：录下各 tab 的页面内容（见下面 tab 内容区的 layerBackdrop）。
    // 只录内容区，不录整个 Scaffold：底栏在 Scaffold 里面，录整个 Scaffold 就成了
    // 「玻璃采样自己」的环，RenderThread 会直接 SIGSEGV。
    val appBackdrop = rememberLayerBackdrop()
    // 玻璃底栏把自己导出成一层，屁岱气泡的尖角伸到底栏上时采的是「页面 + 底栏」合起来的样子，
    // 不然尖角下面透出来的是页面，和底栏断开（plan2 §16.6）。
    val glassBarExport = rememberLayerBackdrop()
    val phoneBubbleBackdrop = rememberCombinedBackdrop(appBackdrop, glassBarExport)

    // 悬浮底栏的总占位高度。
    // - 玻璃底栏：本体 58dp（GLASS_BAR_HEIGHT），离系统导航条 8dp（没有导航条时离屏幕底边 20dp）；
    // - 平板竖屏的悬浮胶囊：取自 miuix FloatingNavigationBar 的实现，胶囊本体最小 52dp，
    //   外加底部留白（有系统导航条时 26dp + inset，否则 36dp）。
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val glassBarBottomGap = if (navInset > 0.dp) 8.dp else 20.dp
    // 两种悬浮底栏都挂在 Scaffold 的 floatingToolbar 槽位里。contentWindowInsets 的手机
    // 分支已经把底部导航条排除掉（内容要从小白条下面穿过，见下方 Scaffold 参数处），
    // 所以槽位底边只比屏幕底边高 Scaffold.kt 里那个 private 的 FloatingToolbarSpacing
    // 的 4dp，取不到只能照抄数值。槽位内容按「想要的位置 − 槽位已给的位置」补偿：
    //   - 玻璃底栏自己不垫底，补「navInset + 想要的留白 − 4dp」：底边仍落在
    //     navInset + 8dp（没有导航条时 20dp）处，视觉与旧版完全一致；
    //   - 平板竖屏的 miuix 胶囊内部自己垫了 26dp + inset（NavigationBar.kt 的
    //     bottomPaddingValue），槽位多让的只有那 4dp，向下压回去即可。
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

    // PIDAI tab 的标题与顶栏按钮。屁岱作为 0 级页不再自带 Scaffold/TopAppBar，
    // 标题（助手名字可改）和「会话列表 / 设置」两个按钮由它反向送上来，
    // 走的是 COURSES tab 已有的同一套 slot 机制。
    var agentTitle by remember { mutableStateOf("屁岱") }
    var agentHeaderActions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var agentHeaderNavIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // ── 首页数据的主动拉取 ──
    //
    // 挂在 MainScreen 而不是 HomeTab：tab 是懒加载的（`composedTabs` 只在选中过之后才加），
    // 而默认启动 Tab 允许设成日程/学辅/我的。挂在 HomeTab 上就意味着
    // **用户不点一次首页，主动拉取一次都不会跑**——校园卡余额、成绩、图书馆状态全是空的，
    // 依赖它们的屁岱提醒自然也永远不触发。
    LaunchedEffect(loginState.accountId, loginState.campusCardCacheVersion) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        HomeStatsRefresher.refreshDue(context, loginState.sessionManager, loginState.accountType)
        HomeSignals.bumpStatsVersion()
    }

    ProactiveReminderLoop(loginState)

    // 待在屁岱这一页时不让它自动冒泡：人已经在跟它聊了，从底栏探头说闲话既遮输入框也很怪。
    LaunchedEffect(selectedTab) {
        ProactiveBubbleHost.autoSuppressed = selectedTab == BottomTab.PIDAI
    }

    // 点屁岱：切到它的 tab，同时让它说句闲话。几种底栏行为必须一致，所以提到这里共用一份。
    val onPidaiTap: () -> Unit = {
        userSelectTab(BottomTab.PIDAI)
        // 点了就是要进屁岱页：闲话不再从底栏冒泡（气泡会压住输入框），交给首屏的屁岱说。
        // 正事气泡（余额不足、要上课了）不动，离开屁岱页后照常显示。
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

    // 气泡冒出来的那一刻给一下 LOW_TICK（PR T §11.3）：按气泡 id 触发，同一条气泡重组不会重复震
    val bubbleId = ProactiveBubbleHost.message?.id
    LaunchedEffect(bubbleId) {
        if (bubbleId != null) haptics.lowTick()
    }

    // ── 屁岱主动提醒气泡 ──
    //
    // 挂在**底栏自己身上**，不挂 Scaffold 内容层：气泡和导航栏是同一个 Column 里的上下邻居，
    // 位置由布局自己得出，绘制层级也自然在最上。底栏版和侧栏版只差「朝哪个方向、摆在哪」，
    // 三个回调（打开/关掉/超时）必须完全一致，所以提出来一份，两个展示位各自只负责定位。
    val bubbleView: @Composable (ProactiveMessage, BubbleArrowSide, Dp) -> Unit = { msg, arrowSide, maxWidth ->
        ProactiveBubbleView(
            message = msg,
            arrowSide = arrowSide,
            maxWidth = maxWidth,
            // 底栏上的气泡采「页面 + 玻璃底栏」合起来那一层；侧栏气泡和平板竖屏的旧胶囊上
            // 只采页面内容（那两种底栏本身不是玻璃，没有导出层）
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

    // 气泡外面套了个"零高度"的 layout：照常测量、往上溢出绘制，但对外宣称高度为 0。
    // 不这么做的话，气泡一出现就会把底栏撑高，Scaffold 重算 contentPadding，整页内容跟着往上跳一下。
    val proactiveBubbleSlot: @Composable () -> Unit = {
        val msg = ProactiveBubbleHost.message
        // 在屁岱页不画底栏气泡：它会压住输入框，而且人已经在跟屁岱说话了
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

    // 侧栏屁岱按钮在根坐标系里的位置，宽屏气泡靠它定位。
    var pidaiAnchor by remember { mutableStateOf<Rect?>(null) }
    // 覆盖层自己的根坐标。两者相减才是气泡该放的本地偏移：MainScreen 在返回栈里，
    // push 子页的转场动画会把整页横向平移，直接拿根坐标当本地坐标用就会在那几帧里偏掉。
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // 骨架：Row 包 Scaffold，不是 Scaffold 包 Row。
    //
    // 侧栏若挂在 Scaffold 的内容区里，外面套着 `.padding(padding)`——而 padding.top
    // 就是顶栏高度，各 tab 的顶栏又不一样高（屁岱是小标题、别的是会折叠的大标题、
    // 日程还多一条副标题和 bottomContent）。于是切 tab、滚动列表时侧栏跟着上下跳。
    // 搬到 Scaffold 外面，侧栏就只受窗口约束，顶栏怎么折叠都跟它无关。
    // 对照 miuix 示例 example/shared/.../AppContent.kt 的 Row { rail; NavDisplay }。
    //
    // 手机上 isWide == false，Row 里只剩 Scaffold 一个孩子。
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
        val railState = rememberNavigationRailState()
        // 和 miuix 侧栏内部同一条弹簧（阻尼 1、刚度 322、收尾阈值 0.001），用来判断「侧栏还在动」
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
                    // 宽屏下 tab 切换动画会横向平移内容，不裁剪的话会画到侧栏上。
                    .then(if (isWide) Modifier.clipToBounds() else Modifier)
                    // 侧栏展开 / 收起期间，内容区一直按**终点宽度**排版，只随侧栏平移、被裁剪：
                    // 不这样的话每帧都按新宽度把整个 tab 重新测量一遍，玻璃顶栏也跟着每帧重模糊。
                    .then(if (isWide) Modifier.railSettledWidth(railState, railProgress) else Modifier),
                // 宽屏时左侧的刘海/侧边导航条已经被侧栏自己吃掉了（NavigationRail 的
                // defaultWindowInsetsPadding），这里再留一次就是双重留白。同 miuix 示例 WideScreenContent。
                contentWindowInsets = if (isWide) {
                    WindowInsets.systemBars.union(
                        WindowInsets.displayCutout.exclude(WindowInsets.displayCutout.only(WindowInsetsSides.Start)),
                    )
                } else {
                    // 手机端：底部导航条（小白条）不参与内容留白，内容一直铺到屏幕底边，
                    // 从透明小白条和半透明玻璃底栏下面滚过（iOS 效果）。列表末尾的净空由
                    // 各 tab 的 extraBottomPadding = floatingBarReserve 自己补（内含 navInset）。
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                },
                // 不再自己垫高：两种悬浮底栏都走 floatingToolbar 槽位，miuix Scaffold 会把提示条放在
                // 整个槽位（底栏 + 头顶的屁岱气泡）之上；经典底栏走 bottomBar，同样自动让开。
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
                // 玻璃风格下，每个 tab 的内容都铺到顶栏下面，所以顶部留白不在这一层统一加，
                // 交给各 tab 放进自己的滚动内容（contentTopPadding）。经典风格照旧整体下移。
                // 顶栏高度只能在布局阶段读：miuix 给的 padding 内部是个 state，折叠时每帧都变。
                // 在组合阶段读它再当参数传给每个打开过的 tab，顶栏每折叠一帧所有 tab 都重组一遍，明显掉帧；
                // 所以各 tab 拿「见过的最大顶栏高度」这个稳定值，差额由 followTopBar 在布局阶段补位移。
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
                        // 玻璃的采样源就是这一层：各 tab 的页面内容。经典风格下不录，省一次离屏绘制。
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
                                // 悬浮胶囊底栏是**浮在内容上**的，不占 Scaffold 的 contentPadding。
                                // 别的 tab 是滚动列表，底部被盖住无所谓；屁岱有个钉在底边的输入栏，
                                // 不补这段高度就会被胶囊压住。经典底栏本身占位，无需额外补。
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
                            // 仲英学辅资料站（zyxf.top）：列表、检索、下载走它的公开只读接口，原生排版
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

                    // 登录恢复的非阻塞提示条（底部，不遮挡欢迎卡片）。
                    // 必须自己让开悬浮底栏：底栏浮在内容之上、不占 contentPadding，
                    // 漏了补就正好压在半透明的玻璃底栏下面。
                    RestoreBanner(
                        visible = isRestoring,
                        step = restoreStep,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = floatingBarReserve),
                    )

                    FeedbackPromptSheet()
                    PasswordInvalidatedDialog(loginState, onUpdatePassword = { router.open(AppRoute.Settings) })
                }

                // 全局搜索覆盖层（跨 tab 共用同一个浮层，渲染优先级高于普通导航）
                if (showGlobalSearch) {
                    // 搜索浮层盖在整页上面，用页面内容做玻璃背景；经典风格下给 null，退回不透明
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

                // 扫码登录覆盖层（首页左上角入口）
                if (showQrLogin) {
                    QrLoginScreen(
                        sessionManager = accountManager.sessionManager,
                        onBack = { showQrLogin = false },
                        // 图书馆桌面座位码：进图书馆页，定位到那个区并弹出这个座位的预约确认
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

/**
 * 主界面的顶栏。五个 tab 都是玻璃（经典风格除外），和二级页顶栏同一套画法，见 glassBarSurface；
 * 采样源是 tab 内容区，顶栏不在那一层里面，不会形成环。
 */
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
    // 屁岱永远用折叠态标题：它是从下往上长的聊天，大标题会随滚动忽大忽小。
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
            // 首页左上角：扫一扫入口（扫码登录 / 图书馆座位码）
            if (selectedTab == BottomTab.HOME) {
                IconButton(onClick = onScan) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "扫一扫", tint = MiuixTheme.colorScheme.onSurface)
                }
            }
        },
        actions = {
            if (selectedTab == BottomTab.COURSES) courseActions?.invoke(this)
            // 首页全局搜索入口
            if (selectedTab == BottomTab.HOME) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "搜索", tint = MiuixTheme.colorScheme.onSurface)
                }
            }
        },
        bottomContent = {
            if (selectedTab == BottomTab.COURSES) {
                // 日程 tab 的顶栏（连同标签行、周标题栏）做成一整块玻璃（plan2 Y2）：只用一个 drawBackdrop，
                // 三块各做各的会在接缝处出现三条模糊边。挂在玻璃顶栏下面的标签行、周胶囊，底色跟着换成半透明
                CompositionLocalProvider(LocalOnGlassBar provides glassStyle) {
                    courseBottomContent?.invoke()
                }
            }
        },
    )
}

/**
 * 五个 tab 的内容区：懒加载（选中过才进组合），切走的 tab 留在组合里只是透明，
 * 切换时带一点横移 + 缩放的过渡。
 */
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
                        // 藏起来的 tab 沿用它最后一次的测量约束：它们只是透明度为 0，仍在组合里，
                        // 不冻住的话宽屏侧栏展开 / 收起时内容区宽度逐帧在变，打开过的每个 tab
                        // 都跟着逐帧重新测量布局——实测打开过三个 tab 时每帧 37ms，动画掉到 30 帧。
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
                    // 切走的 tab 仍在组合里，靠它让里面的常驻动画（首页渐变）停下
                    CompositionLocalProvider(LocalPageVisible provides isActive) {
                        content(tab)
                    }
                }
            }
        }
    }
}

/**
 * 屁岱主动提醒：只算文案，不管展示。算完塞进 [ProactiveBubbleHost]，展示位是屁岱头顶的气泡。
 * 数据全部读本地缓存，不为提醒额外发任何请求。
 *
 * 挂在 MainScreen 层而不是某个 tab 里，理由同首页数据的主动拉取（tab 是懒加载的）。
 * 循环评估而不是只算一次：余额变化、临近上课、新成绩落盘都在运行期发生，
 * 只在冷启动算一次的话表现就是"冒过一次以后再也不冒了"。真正的节流交给 [ProactiveRules.pick] 里的冷却判断。
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
            // 成绩与通知由 HomeStatsRefresher 抓取后留下游标，这里只读不抓——
            // 「一次抓取、两处消费」，气泡不为自己额外发请求。图书馆同理。
            val pendingScores = HomeStats.pendingNewScores(context)
            val unseenNotice = HomeStats.unseenNoticeTitle(context)
            val unseenNoticeLink = HomeStats.unseenNoticeLink(context)
            val libraryTodo = HomeSignals.libraryUrgentAction
            // 考试同样是"只读不抓"：日程页每次加载都会把考试表写进 DataCache，这里直接读那份。
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
                // 冒过就消费掉，避免同一条反复提醒。冷却只管"多久不再说"，
                // 不负责"这件事已经说过了"——两者混用会导致冷却一过又推一遍旧消息。
                when (msg.id) {
                    "grade" -> HomeStats.setPendingNewScores(context, 0)
                    "notice" -> HomeStats.clearUnseenNotice(context)
                    "schedule_change" -> ScheduleDiff.setPending(context, null)
                    "attendance" -> HomeSignals.attendanceAlert = null
                    "coupon" -> HomeSignals.couponAlert = null
                    // 清空而非重查：待办是否还在只有图书馆服务端知道，这里现拉一次会给冒泡加一次网络等待。
                    // 下一轮 HomeStatsRefresher 会按最新状态重新填上，签完到则不再填。
                    "library" -> HomeSignals.libraryUrgentAction = null
                }
                ProactiveBubbleHost.message = msg
            }
            delay(ProactiveRules.EVAL_INTERVAL_MS)
        }
    }
}

/** 日程 tab：顶栏的副标题、按钮、标签行由日程页反向送上来，挂到主界面的顶栏里。 */
@Composable
private fun CoursesTab(
    loginState: AppLoginState,
    onNavigate: (AppRoute) -> Unit,
    scrollBehavior: ScrollBehavior,
    extraBottomPadding: Dp,
    /** 玻璃顶栏盖在内容上面时顶栏的高度，交给日程页各栏做顶部留白（Y2）。 */
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
            // 详情面板的下钻目标（教材全文 / 课程回放 / 考勤）都在别的子系统里，
            // 走带登录拦截的 router，免得落地页自己再弹一次未登录。
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

/**
 * [frozen] 为真时，用上一次（未冻结时）的约束测量子内容，外面的约束怎么变都不传进去。
 *
 * 约束没变，Compose 就跳过子树的重新测量，于是整棵子树不跟着父布局的尺寸动画逐帧重排。
 * 自己报给父布局的尺寸仍按当前约束收紧，不会撑破父布局。
 * [frozen] 在布局阶段读，状态变了只触发重新布局、不触发重组。
 */
@Composable
private fun Modifier.freezeLayoutWhile(frozen: () -> Boolean): Modifier {
    // remember 住：MainScreen 重组时修饰符会重建，不记住的话冻结期间一重组就丢了原来的约束
    val last = remember { arrayOfNulls<Constraints>(1) }
    return this.layout { measurable, constraints ->
        val use = if (frozen()) last[0] ?: constraints else constraints.also { last[0] = it }
        val placeable = measurable.measure(use)
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.place(0, 0)
        }
    }
}
