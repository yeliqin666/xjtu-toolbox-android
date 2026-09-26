package com.xjtu.toolbox.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.xjtu.toolbox.agent.AgentThinkingHost
import com.xjtu.toolbox.agent.BubbleArrowSide
import com.xjtu.toolbox.agent.PidaiNavButton
import com.xjtu.toolbox.agent.ProactiveBubbleHost
import com.xjtu.toolbox.agent.ProactiveMessage
import com.xjtu.toolbox.agent.pidaiNavAppearance
import com.xjtu.toolbox.ui.glass.GlassBottomTabs
import com.xjtu.toolbox.ui.glass.GlassTab
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBarItemColors
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.round

/** 底栏 / 侧栏共用的状态：选中哪个 tab、「我的」角标要用的登录状态和账号数。 */
internal class MainNavState(
    val selected: BottomTab,
    val isLoggedIn: Boolean,
    val accountCount: Int,
    val onSelect: (BottomTab) -> Unit,
    /** 点屁岱：既切 tab，也负责戳一下冒闲话气泡，所以和普通 tab 分开。 */
    val onPidaiTap: () -> Unit,
)

/**
 * 底栏里的屁岱：它和其他四个一样是 0 级页、一样切 selectedTab，只是**长得不一样**——
 * 渲染成会眨眼、会思考、能换皮肤的机器人，而不是灰度线性图标 + 文字。
 */
@Composable
private fun PidaiTabButton(
    nav: MainNavState,
    selected: Boolean,
    diameter: Dp,
    paper: Color,
    modifier: Modifier = Modifier,
    liftUp: Dp = 0.dp,
) {
    val style = pidaiNavAppearance()
    PidaiNavButton(
        onClick = nav.onPidaiTap,
        excited = ProactiveBubbleHost.message != null,
        thinking = AgentThinkingHost.isThinking,
        selected = selected,
        diameter = diameter,
        liftUp = liftUp,
        paper = paper,
        ink = style.ink,
        shape = style.shape,
        skin = style.skin,
        modifier = modifier,
    )
}

/** 经典底栏（界面风格选「经典」时，手机上用）。5 个 tab，屁岱在正中。 */
@Composable
internal fun ClassicBottomBar(
    nav: MainNavState,
    colors: NavigationBarItemColors,
    bubble: @Composable () -> Unit,
) {
    Column {
        bubble()
        NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
            BottomTab.entries.forEach { tab ->
                if (tab == BottomTab.PIDAI) {
                    PidaiTabButton(
                        nav,
                        selected = nav.selected == tab,
                        diameter = 38.dp,
                        liftUp = 8.dp,
                        paper = MiuixTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    NavigationBarItem(
                        selected = nav.selected == tab,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        colors = colors,
                        badge = bottomTabBadge(tab, nav),
                    )
                }
            }
        }
    }
}

/**
 * 手机竖屏的玻璃胶囊底栏，可以拖动切 tab。
 *
 * @param iconBox 各格图标的光学尺寸不同（见 [BottomTab.iconSize]），统一放进这么高的框里居中，
 *   五格的「图标 + 文字」总高才一致，文字基线不会错开。
 * @param exportedBackdrop 底栏把自己导出成一层，屁岱气泡的尖角伸到底栏上时采的是「页面 + 底栏」合起来的样子。
 */
@Composable
internal fun GlassBottomBar(
    nav: MainNavState,
    selectedColor: Color,
    unselectedColor: Color,
    iconBox: Dp,
    backdrop: LayerBackdrop,
    exportedBackdrop: LayerBackdrop,
    bottomPadding: Dp,
    bubble: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPadding),
    ) {
        bubble()
        GlassBottomTabs(
            tabs = BottomTab.entries.map { tab ->
                // 屁岱那一格免染色：GlassBottomTabs 会把整排内容再画一遍做强调色，
                // 屁岱是会动的机器人，不能被复制出第二份（plan2 §9.3A）
                GlassTab(key = tab, tintExempt = tab == BottomTab.PIDAI) { selected ->
                    if (tab == BottomTab.PIDAI) {
                        // paper 是「挖空」，眼睛处直接透出后面的玻璃（PR O 第 1 步）
                        PidaiTabButton(nav, selected, diameter = 40.dp, paper = MiuixTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        val tint = if (selected) selectedColor else unselectedColor
                        Box(Modifier.height(iconBox), contentAlignment = Alignment.Center) {
                            Box {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(tab.iconSize))
                                // 角标照常画，不用玻璃（底栏里不能再嵌 layerBackdrop，§9.4）
                                bottomTabBadge(tab, nav)?.let { badge ->
                                    Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp)) { badge() }
                                }
                            }
                        }
                        Text(tab.label, fontSize = 11.sp, maxLines = 1, color = tint)
                    }
                }
            },
            selectedIndex = nav.selected.ordinal,
            // 拖到屁岱松手也要走 onPidaiTap
            onTabSelected = { index ->
                val tab = BottomTab.entries[index]
                if (tab == BottomTab.PIDAI) nav.onPidaiTap() else nav.onSelect(tab)
            },
            backdrop = backdrop,
            exportedBackdrop = exportedBackdrop,
            glass = true,
        )
    }
}

/** 平板竖屏的悬浮胶囊（miuix FloatingNavigationBar）。 */
@Composable
internal fun FloatingBottomBar(
    nav: MainNavState,
    colors: NavigationBarItemColors,
    offsetY: Dp,
    bubble: @Composable () -> Unit,
) {
    Column(Modifier.offset(y = offsetY)) {
        bubble()
        FloatingNavigationBar(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clip(RoundedCornerShape(50)),
        ) {
            BottomTab.entries.forEach { tab ->
                if (tab == BottomTab.PIDAI) {
                    PidaiTabButton(nav, nav.selected == tab, diameter = 40.dp, paper = MiuixTheme.colorScheme.surfaceContainerHigh)
                } else {
                    FloatingNavigationBarItem(
                        selected = nav.selected == tab,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        colors = colors,
                        badge = bottomTabBadge(tab, nav),
                    )
                }
            }
        }
    }
}

/**
 * 宽屏侧栏。整体在 Scaffold **之外**，只受窗口约束，顶栏怎么折叠都跟它无关（见 MainScreen 注释）。
 *
 * miuix 0.9.3 起 NavigationRail 去掉了 mode 参数，改为传 state 获得可展开侧栏：
 * 收起态为图标+小字，展开态为「图标 + 文字」横向排布，顶部自带展开/收起按钮。
 * 它自己处理状态栏 / 侧边导航条 / 起始侧刘海的 inset（defaultWindowInsetsPadding），
 * 外面不要再补 padding。
 *
 * @param railState 提到 MainScreen：右边内容区要知道侧栏往哪个宽度走，动画期间按终点宽度排版（见 [railSettledWidth]）。
 * @param onPidaiBoundsChange 屁岱这一格内容的根坐标，宽屏气泡靠它定位（见 [RailProactiveBubble]）。
 */
@Composable
internal fun MainNavigationRail(
    nav: MainNavState,
    railState: NavigationRailState,
    onPidaiBoundsChange: (Rect) -> Unit,
) {
    // 侧栏里的文字固定行高。miuix 的格子文字字号跟着展开进度从 12sp 插值到 16sp，格子高度又按文字
    // 高度算；main 样式没设行高，文字高度随字号走、按整像素取整。收起弹簧最后那段慢尾巴里字号
    // 只变零点几 sp，文字高度却会在某一帧跳 1px，下面每一格都跟着被推一下——就是收窄最后一刻
    // 「所有按钮文字抖一下」。行高钉死以后，格子高度不再随字号变。
    val railTextStyles = MiuixTheme.textStyles.let { ts ->
        ts.copy(
            main = ts.main.copy(
                lineHeight = 16.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
        )
    }
    MiuixTheme(textStyles = railTextStyles) {
        NavigationRail(
            color = MiuixTheme.colorScheme.surface,
            state = railState,
            expandContentDescription = "展开导航栏",
            collapseContentDescription = "收起导航栏",
        ) {
            BottomTab.entries.forEach { tab ->
                if (tab == BottomTab.PIDAI) {
                    RailPidaiItem(nav, railState, onPidaiBoundsChange)
                } else {
                    NavigationRailItem(
                        selected = nav.selected == tab,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        badge = bottomTabBadge(tab, nav),
                    )
                }
            }
        }
    }
}

/**
 * 侧栏里的屁岱：也是那颗机器人，不退化成灰度线性图标——否则宽屏用户看到的是另一个应用。
 *
 * 展开 / 收起要和邻居一起平滑地挪。miuix 的 NavigationRailItem 跟着侧栏内部的一条 0..1 弹簧进度
 * 逐帧插值位置，那个进度（LocalNavigationRailExpandInfo）是 internal 的，拿不到；这里用同一条弹簧
 * （阻尼 1、响应 0.35s）自己算一份，几何也照它的公式：图标从「收起时居中」插值到「展开时左侧
 * 12 + 14dp」，文字跟着淡入。
 */
@Composable
private fun RailPidaiItem(
    nav: MainNavState,
    railState: NavigationRailState,
    onPidaiBoundsChange: (Rect) -> Unit,
) {
    val railProgress by animateFloatAsState(
        targetValue = if (railState.isExpanded) 1f else 0f,
        // 收尾阈值也对齐 miuix 的 RailExpandSpring（0.001）：默认 0.01 会比邻居早停一小截
        animationSpec = spring(dampingRatio = 1f, stiffness = 322f, visibilityThreshold = 0.001f),
        label = "pidaiRailProgress",
    )
    // 普通数组而不是 State：layout 里写、onGloballyPositioned 里读，都在布局阶段，不需要触发重组
    val contentRight = remember { floatArrayOf(0f) }
    Layout(
        modifier = Modifier
            // 气泡锚在这一格「内容」的右端：收起时是屁岱图标的右边，展开时是「屁岱」二字的右边。
            // 锚在按钮上，展开时气泡压在字上；锚在整行上，气泡又飘到侧栏外面、离屁岱太远。
            .onGloballyPositioned {
                val b = it.boundsInRoot()
                onPidaiBoundsChange(b.copy(right = b.left + contentRight[0]))
            }
            .fillMaxWidth()
            // 与 NavigationRailDefaults.ItemVerticalPadding 对齐，保证和邻居是同一套等距节奏
            .padding(vertical = 12.dp)
            .clickable(onClick = nav.onPidaiTap),
        content = {
            PidaiTabButton(nav, nav.selected == BottomTab.PIDAI, diameter = 40.dp, paper = MiuixTheme.colorScheme.surface)
            // ExpandedLabelFontSize，与邻居的展开态对齐；收起时透明，不占位置
            Text(
                BottomTab.PIDAI.label,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = railProgress },
            )
        },
    ) { measurables, constraints ->
        val f = railProgress.coerceIn(0f, 1f)
        val icon = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // 文字从 70dp 起，右边留出和左边对称的 26dp
        val labelMax = (constraints.maxWidth - 70.dp.roundToPx() - 26.dp.roundToPx()).coerceAtLeast(0)
        val label = measurables[1].measure(Constraints(maxWidth = labelMax))
        // 和上下几项按「中心」对齐，而不是按左边缘：屁岱直径 40dp，比 miuix 的图标（28dp）大一圈。
        // - 图标中心：收起时在 80dp 宽的侧栏里居中，中心在 40dp；展开时图标左边在 12 + 14 = 26dp，
        //   中心也是 26 + 28 / 2 = 40dp。两态重合，所以屁岱展开、收起都不用动；
        // - 文字：邻居的文字起点是 26 + 28 + 16 = 70dp，屁岱照这个位置放。
        val iconX = (40.dp.toPx() - icon.width / 2f).toInt()
        val labelX = 70.dp.roundToPx()
        val height = maxOf(icon.height, label.height)
        // 内容右端给气泡定位用：收起时是图标右边，展开时是文字右边，按展开进度过渡
        val iconRight = (iconX + icon.width).toFloat()
        contentRight[0] = lerp(iconRight, maxOf(iconRight, (labelX + label.width).toFloat()), f)
        layout(constraints.maxWidth, height) {
            icon.placeRelative(iconX, (height - icon.height) / 2)
            if (f > 0f) label.placeRelative(labelX, (height - label.height) / 2)
        }
    }
}

/**
 * 宽屏侧栏屁岱旁边的主动气泡，尖角朝左指着屁岱。
 *
 * 不能放进 NavigationRail：它内部是一个 verticalScroll 的 Column，向右溢出的气泡会被裁掉，
 * 所以做成与侧栏并列的覆盖层，按按钮的根坐标定位。锚点、覆盖层原点都用函数传进来，只在这里读：
 * 屁岱在侧栏展开 / 收起时逐帧移动，若在 MainScreen 的组合阶段读，整个 MainScreen 每帧重组。
 */
@Composable
internal fun RailProactiveBubble(
    anchor: () -> Rect?,
    overlayOrigin: () -> Offset,
    bubbleView: @Composable (ProactiveMessage, BubbleArrowSide, Dp) -> Unit,
) {
    val bubbleMsg = ProactiveBubbleHost.message ?: return
    val rect = anchor() ?: return
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val bubbleMax = with(density) { (windowWidthPx - rect.right).toDp() - 24.dp }.coerceIn(180.dp, 360.dp)
    val gapPx = with(density) { 8.dp.roundToPx() }
    Box(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
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
        bubbleView(bubbleMsg, BubbleArrowSide.Start, bubbleMax)
    }
}

/**
 * 侧栏动画期间让内容区按终点宽度排版一次，之后只平移、裁剪，不再逐帧重新测量。
 *
 * 动画第一帧记下「现在的宽度 + 侧栏还要变化的量」作为终点宽度，整个动画都用它测量孩子；
 * 自己对外仍报外面给的宽度（Row 照常把它排在侧栏右边），多出来的部分由外层 clipToBounds 裁掉，
 * 少的那一截在右边、会随侧栏走完被填满。动画一停立刻回到按实际宽度测量。
 * 进度只在布局阶段读，不引起重组。
 */
@Composable
internal fun Modifier.railSettledWidth(railState: NavigationRailState, progress: State<Float>): Modifier {
    // [0] 终点宽度（px），[1] 它对应的目标状态（1 展开 / 0 收起 / -1 无）；布局阶段读写，不用 State
    val held = remember { IntArray(2).also { it[1] = -1 } }
    return this.layout { measurable, constraints ->
        val target = if (railState.isExpanded) 1 else 0
        val p = progress.value
        val animating = abs(p - target) > 0.001f && constraints.hasBoundedWidth
        val width = if (animating) {
            if (held[1] != target) {
                // 侧栏当前宽度与终点宽度之差 = (p - target) × (展开宽 - 收起宽)
                val remaining = round((p - target) * (240.dp - 80.dp).toPx()).toInt()
                held[0] = (constraints.maxWidth + remaining).coerceAtLeast(0)
                held[1] = target
            }
            held[0]
        } else {
            held[1] = -1
            constraints.maxWidth
        }
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

/** 「我的」上的角标：多账号时显示账号数，未登录时显示一个红点。 */
private fun bottomTabBadge(tab: BottomTab, nav: MainNavState): (@Composable () -> Unit)? {
    if (tab != BottomTab.PROFILE) return null
    return when {
        nav.accountCount > 1 -> {
            { Badge { Text(nav.accountCount.coerceAtMost(99).toString()) } }
        }
        !nav.isLoggedIn -> {
            { Badge() }
        }
        else -> null
    }
}
