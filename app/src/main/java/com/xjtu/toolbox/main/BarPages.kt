package com.xjtu.toolbox.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.home.BottomBarTabs
import com.xjtu.toolbox.ui.components.MorphingLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.xjtu.toolbox.agent.AgentOverlays
import com.xjtu.toolbox.agent.AgentScreen
import com.xjtu.toolbox.agent.AndroidAgentHost
import com.xjtu.toolbox.agent.AndroidPidaiHero
import com.xjtu.toolbox.agent.AndroidPokeWhisper
import com.xjtu.toolbox.agent.PidaiAppearancePanel
import com.xjtu.toolbox.agent.rememberAndroidAgentImagePicker
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellPageInsets
import com.xjtu.toolbox.ui.components.BackButton
import com.xjtu.toolbox.ui.glass.LocalOnGlassBar
import com.xjtu.toolbox.ui.glass.LocalTabRoot
import com.xjtu.toolbox.ui.glass.glassBarColor
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.zyxf.ZyxfBrowseScreen
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/*
 * 底栏那几格的页，在两种宿主里画：挂在底栏上（MainScreen，顶栏由主界面画），
 * 或被用户移出底栏后作为普通页压栈（下面的 *RoutePage，自带顶栏和返回键）。
 */

/**
 * 子页挂到底栏上。子页原本是全屏页：顶栏自带状态栏留白、底部自己让导航条。
 * 这里统一让出底栏占的高度，再把壳已经垫过的 inset 标成已消费，页面里的 windowInsetsPadding 不会再加一遍；
 * 无限下拉的列表也就压不到底栏下面（代价是内容不从玻璃底栏下穿过）。顶部：壳不画主顶栏、
 * 也不给这一格做跟随位移（`ShellTab.drawsOwnTopBar`），玻璃风格下页面从屏幕顶边画起、自己让状态栏。
 *
 * 要登录站点的页先过 [TabLoginGate]：压栈时这一步由 AppRouter 在跳转前做，放在底栏上没有跳转这一步，
 * 拿不到会话时页面会自己退回（WithSite / AwaitSite），所以先在这里登好再画。
 */
@Composable
internal fun RouteTabPage(
    route: AppRoute,
    insets: ShellPageInsets,
    glassStyle: Boolean,
    loginState: AppLoginState,
    content: @Composable () -> Unit,
) {
    // 壳的内容区：横向 inset 两种风格都垫了；顶部只有经典风格垫（玻璃风格铺到状态栏下）；
    // 底部由底栏或 bottomBarReserve 盖住
    val handled = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(if (glassStyle) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        .union(WindowInsets.navigationBars)
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = insets.bottomBarReserve)
            .consumeWindowInsets(handled),
    ) {
        CompositionLocalProvider(LocalTabRoot provides true) {
            val type = route.loginType
            if (type == null) content() else TabLoginGate(type, BottomBarTabs.label(route), loginState, content)
        }
    }
}

/** 底栏上的子页要用的站点会话：进来时自动登一次，失败就停在这一页说明原因、可重试，不把自己弹掉。 */
@Composable
private fun TabLoginGate(type: LoginType, title: String, loginState: AppLoginState, content: @Composable () -> Unit) {
    val manager = loginState.sessionManager
    var ready by remember(manager) { mutableStateOf(manager?.getSiteOrNull(type.siteKey())?.hasLogin == true) }
    if (ready) {
        content()
        return
    }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(manager, attempt) {
        failure = null
        if (manager == null || !loginState.hasCredentials) {
            failure = "请先在「我的」里登录校园账号"
            return@LaunchedEffect
        }
        failure = try {
            // 用户点开了这一格，算前台等待：豁免站点失败冷却
            val site = withTimeoutOrNull(TAB_LOGIN_TIMEOUT_MS) { manager.ensureSite(type, userInitiated = true) }
            if (site != null) {
                ready = true
                null
            } else {
                "${type.label}连接超时"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FriendlyError.of(e, "连接${type.label}")
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
        val message = failure
        if (message == null) {
            MorphingLoader(size = 40.dp)
            Text("正在连接${type.label}…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            Text(message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
            if (loginState.hasCredentials) TextButton(text = "重试", onClick = { attempt++ })
        }
    }
}

/** 与 AppRouter 自动登录同一档：多跳 OAuth 的站点也够用。 */
private const val TAB_LOGIN_TIMEOUT_MS = 120_000L

/** 屁岱页（两种宿主共用）：Android 这一端的端装配与形象那几块。 */
@Composable
internal fun AndroidAgentScreen(
    loginState: AppLoginState,
    onNavigate: (AppRoute) -> Unit,
    extraBottomPadding: Dp,
    hostBottomPadding: Dp,
    contentTopPadding: Dp,
    scrollBehavior: ScrollBehavior,
    onTitleChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onActionsChange: ((@Composable RowScope.() -> Unit)?) -> Unit,
    onNavIconChange: ((@Composable () -> Unit)?) -> Unit,
    overlays: AgentOverlays,
) {
    val context = LocalContext.current
    AgentScreen(
        // 端装配与端上资源：`:core` 的屁岱只认这条缝（见 AgentHost）
        host = remember { AndroidAgentHost(context, loginState) },
        // 系统相册挑图（`:app` 的适配器）：`:core` 拿不到 ActivityResultLauncher
        imagePicker = rememberAndroidAgentImagePicker(),
        // 屁岱形象那三块（首屏问候头、戳一下的回应气泡、形象选择）认 `ProactiveBubbleHost` / 形象设置 / `PidaiPokes`
        hero = { title, facts, now -> AndroidPidaiHero(title, facts, now) },
        pokeWhisper = { AndroidPokeWhisper(it) },
        appearanceSection = { PidaiAppearancePanel() },
        extraBottomPadding = extraBottomPadding,
        hostBottomPadding = hostBottomPadding,
        scrollBehavior = scrollBehavior,
        onTitleChange = onTitleChange,
        onSubtitleChange = onSubtitleChange,
        onActionsChange = onActionsChange,
        onNavIconChange = onNavIconChange,
        overlays = overlays,
        onNavigate = onNavigate,
        contentTopPadding = contentTopPadding,
    )
}

/** 移出底栏的屁岱：自带小标题顶栏，屏送上来的导航钮排在返回键后面。 */
@Composable
internal fun AgentRoutePage(loginState: AppLoginState, onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    var title by remember { mutableStateOf("屁岱") }
    var subtitle by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var navIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val overlays = remember { AgentOverlays() }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Box(Modifier.fillMaxSize()) {
    StandalonePage(
        topBar = { glass ->
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Row {
                        BackButton(onBack)
                        navIcon?.invoke()
                    }
                },
                actions = { actions?.invoke(this) },
            )
        },
    ) { topPadding, bottomPadding ->
        AndroidAgentScreen(
            loginState = loginState,
            onNavigate = onNavigate,
            extraBottomPadding = 0.dp,
            hostBottomPadding = bottomPadding,
            contentTopPadding = topPadding,
            scrollBehavior = scrollBehavior,
            onTitleChange = { title = it },
            onSubtitleChange = { subtitle = it },
            onActionsChange = { actions = it },
            onNavIconChange = { navIcon = it },
            overlays = overlays,
        )
    }
    overlays.Content()
    }
}

/** 移出底栏的日程：副标题、按钮、标签行照样由日程页送上来，挂到自己的顶栏上。 */
@Composable
internal fun ScheduleRoutePage(loginState: AppLoginState, onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    var subtitle by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
    var bottomContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    StandalonePage(
        topBar = { glass ->
            TopAppBar(
                title = "日程",
                largeTitle = "日程",
                subtitle = subtitle,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton(onBack) },
                actions = { actions?.invoke(this) },
                bottomContent = {
                    // 标签行、周标题栏和顶栏是一整块玻璃
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) { bottomContent?.invoke() }
                },
            )
        },
    ) { topPadding, _ ->
        CoursesTab(
            loginState = loginState,
            onNavigate = onNavigate,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = 0.dp,
            contentTopPadding = topPadding,
            onSubtitleChange = { subtitle = it },
            onActionsChange = { actions = it },
            onBottomContentChange = { bottomContent = it },
        )
    }
}

/** 移出底栏的学辅资料站。 */
@Composable
internal fun ZyxfRoutePage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    StandalonePage(
        topBar = { glass ->
            TopAppBar(
                title = ZYXF_TITLE,
                largeTitle = ZYXF_TITLE,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { topPadding, _ ->
        ZyxfBrowseScreen(
            contentPadding = PaddingValues(0.dp),
            scrollBehavior = scrollBehavior,
            contentTopPadding = topPadding,
        )
    }
}

internal const val ZYXF_TITLE = "仲英学辅资料站"

/**
 * 压栈页的骨架：玻璃顶栏 + 内容区（玻璃风格铺到顶栏下，顶部留白交给页面自己放进列表）。
 * 内容区已垫过的 inset 标成已消费，页面里再按导航条留白不会重复。
 */
@Composable
private fun StandalonePage(
    topBar: @Composable (glass: LayerBackdrop?) -> Unit,
    content: @Composable (topPadding: Dp, bottomPadding: Dp) -> Unit,
) {
    val glass = rememberPageGlass()
    Scaffold(topBar = { topBar(glass) }) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .consumeWindowInsets(padding)
                .glassSource(glass),
        ) {
            content(glassTop, padding.calculateBottomPadding())
        }
    }
}
