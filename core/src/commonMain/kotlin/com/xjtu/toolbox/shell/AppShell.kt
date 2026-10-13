package com.xjtu.toolbox.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.ui.currentWindowSize
import com.xjtu.toolbox.ui.glass.followTopBar
import com.xjtu.toolbox.ui.glass.rememberStableTopPadding
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.platform.BackHandler
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **共享壳**：三端同壳的那一层 —— 顶栏 + 当前页 + 底栏/侧栏 + 壳级插槽。
 *
 * ## 它管什么（共享）
 *
 * 1. **形态**：屏形状 × 界面风格 → 底栏画哪一种（[ShellForm]，判据见 [shellForm]）；
 * 2. **占位**：底栏占掉多少、内容要让开多少、玻璃风格下内容要不要铺到顶栏下面（[ShellLayout]）；
 * 3. **当前页那一层**：只画当前这一页，还是把进过的 tab 留在组合里（[ShellTabHost]，见 [ShellPageHost]）；
 * 4. **底栏 / 侧栏本身**：三块底栏与侧栏（[ClassicBottomBar] / [GlassBottomBar] /
 *    [FloatingBottomBar] / [ShellNavigationRail]）；
 * 5. **页脚横幅**：恢复登录中（[ShellRestoreBanner]），它压在底栏上方，位置只有壳知道。
 *
 * ## 各端注入什么
 *
 * | 参数 | `:app`（Android） | `:desktop` / `:web` |
 * |---|---|---|
 * | [page] | 五格各自的屏 + 各自的宿主面（`HomeTab` / `AgentScreen` / `ScheduleScreen` / …） | 路由表里那一屏（43 条分支） |
 * | [pageHost] | [ShellPageHost.VISITED_TABS]（切回来不重新取数） | [ShellPageHost.CURRENT_ONLY]（导航模型是路由） |
 * | [topBar] | `MainTopBar`（带 Android 的扫一扫图标与每 tab 表头槽位） | null：这一端每一屏自己画头部 |
 * | [contentWindowInsets] | 有状态栏 / 小白条，要按宽窄两套写法 | 没有系统栏，给 `WindowInsets(0)` |
 * | [bottomInset] | 读 `WindowInsets.navigationBars` | 0 |
 * | [glassStyle] | 设置里的「界面风格」位 | 本端自己的外观设置 |
 * | [tabArt] | `AndroidShellTabArt`（屁岱那一格画机器人） | 不注入（[ShellTabArt.None]） |
 * | [barBubble] / [railOverlay] | 屁岱的主动提醒气泡 | null |
 * | [overlays] | 搜索 / 扫一扫（盖满整屏） | 本端有的浮层 |
 * | [sheets] | 反馈弹窗 / 密码失效弹窗 | 本端有的弹层 |
 * | [onBack] | 双击退出 + 关浮层 | 本端的返回（桌面没有返回栈，传 null） |
 * | [snackbarHost] | `SnackbarHost(router.messages)` | 本端的消息条 |
 * | [rootModifier] | 喂屁岱手指位置 + 记覆盖层原点 | 空 |
 *
 * ## 哪些是各端的裁剪权利
 *
 * **裁剪的是集合与页面，不是壳**：哪几格（[ShellTabs]）、每格画什么（[page]）、顶栏有没有
 * （[topBar]）、有没有某一档弹层（可空插槽）——这些都是端上的权利；但**底栏怎么画、当前页怎么摆
 * 是共享的**，任何端都不许再各写一套壳（那正是这一阶段要终止的事）。
 *
 * ## 主题包裹不在这一层
 *
 * Android 的主题在 `MainActivity` 里套（要读用户的外观设置并管系统栏），桌面在
 * `ToolboxDesktopApp` 里套 —— 壳**不**再包一层：多包一层会把 [XJTUToolBoxTheme]
 * [com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme] 的参数重置成默认值（深色覆盖就丢了）。
 *
 * @param contentWindowInsets miuix Scaffold 的内容窗口 inset（系统栏 / 刘海）。这是**窗口 chrome**
 *   那一档：Android 的写法（宽屏吃掉起始侧、手机把内容铺到屏幕底边）只对 Android 有意义，
 *   所以它由端上算好传进来，壳不猜。
 * @param bottomInset 系统导航条那一格的高度（量不到就传 0）。桌面 / web 没有系统栏。
 * @param restoring 正在恢复登录（横幅的开关）。⚠️ 见 [ShellRestoreBanner]：**别在外面加 if**。
 */
@Composable
fun AppShell(
    nav: ShellNavState,
    page: @Composable (ShellPage, ShellPageInsets) -> Unit,
    pageHost: ShellPageHost = ShellPageHost.VISITED_TABS,
    contentWindowInsets: WindowInsets,
    glassStyle: Boolean,
    bottomInset: Dp,
    tabArt: ShellTabArt = ShellTabArt.None,
    rootModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    topBar: (@Composable (ShellTab?, ShellHandles) -> Unit)? = null,
    barBubble: (@Composable (ShellHandles) -> Unit)? = null,
    railOverlay: (@Composable (ShellHandles) -> Unit)? = null,
    overlays: (@Composable (ShellHandles) -> Unit)? = null,
    sheets: (@Composable () -> Unit)? = null,
    restoring: Boolean = false,
    restoreStep: String = "",
) {
    val isWide = isWideLayout()
    val form = shellForm(isWide, currentWindowSize(), glassStyle)
    val layout = ShellLayout(form, glassStyle, bottomInset)

    // 返回键：只有接了这一档的端才注册（`:app` 的双击退出与关浮层都在这条回调里）
    if (onBack != null) BackHandler(onBack = onBack)

    // 选中 / 未选中只靠颜色区分（两态共用同一枚图标）
    val navSelectedContent = MiuixTheme.colorScheme.primary
    val navUnselectedContent = MiuixTheme.colorScheme.onSurfaceContainer
    val navItemColors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = navSelectedContent,
        unselectedContentColor = navUnselectedContent,
    )
    // 图标统一放进同样高的框里，五格文字基线才对齐
    val navIconBox = remember(nav.tabs) { nav.tabs.tabs.maxOf { it.iconSize } }

    // 玻璃采样源只录 tab 内容区；录整个 Scaffold 会让底栏采样自己，RenderThread 直接崩
    val contentBackdrop = rememberLayerBackdrop()
    // 底栏导出成一层，屁岱气泡尖角伸到底栏上时采的是「页面 + 底栏」
    val barBackdrop = rememberLayerBackdrop()
    val handles = ShellHandles(
        contentBackdrop = contentBackdrop,
        barBackdrop = barBackdrop,
        combinedBackdrop = rememberCombinedBackdrop(contentBackdrop, barBackdrop),
        glassBarPresent = layout.glassBarPresent,
    )

    // 端上那只气泡：底栏版和侧栏版共用同一份，各自只管定位
    val bubble: @Composable () -> Unit = { barBubble?.invoke(handles) }

    Box(Modifier.fillMaxSize().then(rootModifier)) {
        val railState = rememberNavigationRailState()
        // 与 miuix 侧栏内部同一条弹簧，用来判断侧栏是否还在动
        val railProgress = animateFloatAsState(
            targetValue = if (railState.isExpanded) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = 322f,
                visibilityThreshold = 0.001f,
            ),
            label = "railProgress",
        )
        // Row 包 Scaffold：侧栏放在 Scaffold 外面，才不会随各 tab 顶栏高度变化上下跳
        Row(Modifier.fillMaxSize()) {
            if (isWide) {
                ShellNavigationRail(nav, railState, tabArt)
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isWide) Modifier.clipToBounds() else Modifier)
                    .then(if (isWide) Modifier.railSettledWidth(railState, railProgress) else Modifier),
                contentWindowInsets = contentWindowInsets,
                snackbarHost = snackbarHost,
                topBar = { topBar?.invoke(nav.selected, handles) },
                bottomBar = {
                    if (!isWide && !glassStyle) ClassicBottomBar(nav, navItemColors, bubble, tabArt)
                },
                floatingToolbar = {
                    when (form) {
                        ShellForm.GLASS_BAR -> GlassBottomBar(
                            nav = nav,
                            selectedColor = navSelectedContent,
                            unselectedColor = navUnselectedContent,
                            iconBox = navIconBox,
                            backdrop = contentBackdrop,
                            exportedBackdrop = barBackdrop,
                            bottomPadding = layout.glassBarSlotBottomPadding,
                            bubble = bubble,
                            tabArt = tabArt,
                        )

                        ShellForm.FLOATING_PILL -> FloatingBottomBar(
                            nav = nav,
                            colors = navItemColors,
                            offsetY = layout.bottomBarSlotOffsetY,
                            bubble = bubble,
                            tabArt = tabArt,
                        )

                        ShellForm.CLASSIC_BAR, ShellForm.RAIL -> Unit
                    }
                },
            ) { padding ->
                // 玻璃风格下内容铺到顶栏下面，顶部留白交给各 tab 的滚动内容。顶栏高度折叠时每帧在变，
                // 各 tab 只拿稳定的最大值，差额由 followTopBar 在布局阶段补，避免每帧重组
                val stableTopBar = rememberStableTopPadding(padding)
                val tabTopPadding = if (glassStyle) stableTopBar.value else 0.dp
                val layoutDirection = LocalLayoutDirection.current
                val insets = ShellPageInsets(
                    contentTopPadding = tabTopPadding,
                    bottomBarReserve = layout.bottomBarReserve,
                    scaffoldBottomPadding = padding.calculateBottomPadding(),
                )
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
                    ShellTabHost(
                        nav = nav,
                        pageHost = pageHost,
                        modifier = if (glassStyle) Modifier.layerBackdrop(contentBackdrop) else Modifier,
                        tabModifier = {
                            if (glassStyle) Modifier.followTopBar({ stableTopBar.value }, padding) else Modifier
                        },
                    ) { p ->
                        page(p, insets)
                    }

                    ShellRestoreBanner(
                        visible = restoring,
                        step = restoreStep,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = layout.bottomBarReserve),
                    )

                    sheets?.invoke()
                }
            }
        }

        if (isWide) railOverlay?.invoke(handles)

        // 盖满整屏的浮层（搜索、扫一扫）放在最外层，不放进 Scaffold 的内容区：
        // 放在内容区里时，Scaffold 把顶栏、底栏画在内容之上，状态栏和小白条那两条被主界面盖住。
        overlays?.invoke(handles)
    }
}

/**
 * 共享壳算给当前页的那几格留白 —— 页自己拿去补（壳不知道页面内部怎么排版）。
 *
 * 三格全部是搬迁前 `MainScreen` 里算完直接塞给各 tab 的那三个值，名字也照着叫。
 */
class ShellPageInsets(
    /** 玻璃风格下顶栏（折叠时会变矮）稳定最大高度；经典风格恒为 0。 */
    val contentTopPadding: Dp,
    /** 浮在上面的底栏一共占掉多少（滚动内容末尾要留出来）。见 [ShellLayout.bottomBarReserve]。 */
    val bottomBarReserve: Dp,
    /** Scaffold 那一层给的底部 padding（钉在底边的输入栏要让开）。 */
    val scaffoldBottomPadding: Dp,
)

/**
 * 共享壳交给端上插槽的那几样把手。
 *
 * 为什么要有它：玻璃顶栏与浮层**必须采到内容区那一层**（`:app` 的 `MainTopBar(backdrop = ...)`
 * 与搜索浮层都这样），而那一层只有壳建得出来；屁岱的气泡还要「页面 + 底栏」的合成层
 * （尖角伸到底栏上时采的是两份）。与其让端上自己再建一层（那就不是同一个采样源了），
 * 不如把壳手里那几层交出去。
 */
class ShellHandles(
    /** 内容区那一层（壳在 tab 内容根上挂了 `Modifier.layerBackdrop`）。 */
    val contentBackdrop: LayerBackdrop,
    /** 底栏导出的一层。 */
    val barBackdrop: LayerBackdrop,
    /** [contentBackdrop] 与 [barBackdrop] 的合成层（kyant 的 `rememberCombinedBackdrop` 给的就是它）。 */
    val combinedBackdrop: Backdrop,
    /** 玻璃胶囊底栏在场（决定气泡尖角朝下时该采哪一层）。 */
    val glassBarPresent: Boolean,
)
