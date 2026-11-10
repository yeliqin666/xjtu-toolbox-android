package com.xjtu.toolbox.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.GLASS_BAR_HEIGHT
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.platform.smallestScreenWidthDp
import com.xjtu.toolbox.ui.WindowSize
import com.xjtu.toolbox.ui.currentWindowSize
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.platform.BackHandler
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 共享壳：三端同壳的那一层 —— 顶栏 + 当前页 + 底栏/侧栏 + 壳级插槽。 */
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
    // 手机按 Compact 算：窗口宽度会在横屏应用切回、强制旋转、小窗时短暂超过 600dp，
    // 只看窗口宽度会让玻璃底栏一会儿变成不透明的悬浮胶囊、一会儿又变回来
    val windowSize = if (smallestScreenWidthDp() < 600) WindowSize.Compact else currentWindowSize()
    val form = shellForm(isWide, windowSize, glassStyle)
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

    val tabZoom = rememberShellTabZoom(nav, enabled = form == ShellForm.GLASS_BAR)
    // 底栏与页面容器在窗口里的位置：只在绘制阶段读，不用 state
    val barGeometry = remember { BarGeometry() }

    // 玻璃采样源只录 tab 内容区；录整个 Scaffold 会让底栏采样自己，RenderThread 直接崩
    val contentBackdrop = rememberLayerBackdrop(cache = glassStyle)
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
                bottomBar = {
                    if (!isWide && !glassStyle) ClassicBottomBar(nav, navItemColors, bubble, tabArt)
                },
                floatingToolbar = {
                    when (form) {
                        // 量出底栏在窗口里的实际下沿：切页放大 / 收回的起止点按它算，不靠留白公式推
                        ShellForm.GLASS_BAR -> Box(Modifier.onGloballyPositioned { barGeometry.barBottom = it.boundsInWindow().bottom }) {
                            GlassBottomBar(
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
                        }

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
                val layoutDirection = LocalLayoutDirection.current
                val statusTop = contentWindowInsets.asPaddingValues().calculateTopPadding()
                // 顶部留白归每一页（见 ShellBarPage），这一层只垫左右和底。
                // 垫了多少就声明消费多少：经典底栏下页面里的 imePadding / windowInsetsPadding 才会扣掉底栏，不把输入框多抬一截
                val handled = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(handled)
                        .consumeWindowInsets(handled)
                        .onGloballyPositioned { barGeometry.hostTop = it.positionInWindow().y },
                ) {
                    ShellTabHost(
                        nav = nav,
                        pageHost = pageHost,
                        modifier = if (glassStyle) Modifier.layerBackdrop(contentBackdrop) else Modifier,
                        zoom = tabZoom?.apply {
                            val density = LocalDensity.current
                            val edge = with(density) { 24.dp.toPx() }
                            val radius = with(density) { 24.dp.toPx() }
                            val fromBottom = with(density) {
                                (layout.glassBarSlotBottomPadding + GLASS_BAR_HEIGHT / 2 - padding.calculateBottomPadding()).toPx()
                            }
                            // 底栏中线距它下沿的距离（下沿以下是贴底留白）
                            val barCenterAboveBottom = with(density) { (layout.glassBarSlotBottomPadding + GLASS_BAR_HEIGHT / 2).toPx() }
                            origin = { index, count, size ->
                                val cx = edge + (size.width - edge * 2f) * (index + 0.5f) / count
                                // 优先用实测的底栏位置（换算到页面容器坐标）；还没量到时退回公式
                                val measured = barGeometry.barBottom
                                val cy = if (measured != null) measured - barCenterAboveBottom - barGeometry.hostTop
                                else size.height - fromBottom
                                androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius)
                            }
                        },
                    ) { p ->
                        ShellBarPage(
                            glass = glassStyle,
                            edgeToEdge = p.tab?.edgeToEdge == true,
                            statusTop = statusTop,
                            bar = if (topBar != null && p.tab?.drawsOwnTopBar != true) {
                                { backdrop ->
                                    topBar(
                                        p.tab,
                                        ShellHandles(backdrop, barBackdrop, handles.combinedBackdrop, handles.glassBarPresent),
                                    )
                                }
                            } else {
                                null
                            },
                        ) { contentTop, contentShift ->
                            page(
                                p,
                                ShellPageInsets(
                                    contentTopPadding = contentTop,
                                    contentTopShift = contentShift,
                                    bottomBarReserve = layout.bottomBarReserve,
                                ),
                            )
                        }
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

/** 共享壳算给当前页的那几格留白 —— 页自己拿去补（壳不知道页面内部怎么排版）。 */
class ShellPageInsets(
    /** 玻璃风格下这一页顶栏的稳定最大高度（没有顶栏时是状态栏高度）；经典风格恒为 0。 */
    val contentTopPadding: Dp,
    /** 浮在上面的底栏一共占掉多少（滚动内容末尾要留出来）。见 [ShellLayout.bottomBarReserve]。 */
    val bottomBarReserve: Dp,
    /**
     * 壳为了对齐 [contentTopPadding] 把这一页整体上移的量（稳定值 − 当前实际顶栏高度）。
     * 页面里贴着屏幕顶边画的装饰（比如首页的渐进模糊带）要自己补回这段，不然会被顶出屏幕。
     * 只在布局阶段读。
     */
    val contentTopShift: () -> Dp = { 0.dp },
)

/** 共享壳交给端上插槽的那几样把手。 */
class ShellHandles(
    /** 采样层：交给顶栏时是这一页自己的内容层，交给其它插槽时是整个 tab 区。 */
    val contentBackdrop: LayerBackdrop,
    /** 底栏导出的一层。 */
    val barBackdrop: LayerBackdrop,
    /** [contentBackdrop] 与 [barBackdrop] 的合成层（kyant 的 `rememberCombinedBackdrop` 给的就是它）。 */
    val combinedBackdrop: Backdrop,
    /** 玻璃胶囊底栏在场（决定气泡尖角朝下时该采哪一层）。 */
    val glassBarPresent: Boolean,
)

/** 底栏下沿与页面容器顶边在窗口里的位置（px），切页放大 / 收回按它找按钮。 */
private class BarGeometry {
    var barBottom: Float? = null
    var hostTop: Float = 0f
}
