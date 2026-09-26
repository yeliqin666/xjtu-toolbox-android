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

/** 导航栏里的屁岱：同样是一个 tab，只是画成会动的机器人。 */
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
 * @param iconBox 图标统一放进这么高的框里，五格文字基线才对齐
 * @param exportedBackdrop 底栏导出的一层，供屁岱气泡采样
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
                // 屁岱免染色：强调色是把整排再画一遍，机器人不能画出第二份
                GlassTab(key = tab, tintExempt = tab == BottomTab.PIDAI) { selected ->
                    if (tab == BottomTab.PIDAI) {
                        PidaiTabButton(nav, selected, diameter = 40.dp, paper = MiuixTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        val tint = if (selected) selectedColor else unselectedColor
                        Box(Modifier.height(iconBox), contentAlignment = Alignment.Center) {
                            Box {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(tab.iconSize))
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
 * 宽屏可展开侧栏。自己处理 inset，外面不要再补 padding。
 *
 * @param railState 内容区也要读它，动画期间按终点宽度排版（[railSettledWidth]）
 * @param onPidaiBoundsChange 屁岱这一格的根坐标，宽屏气泡靠它定位
 */
@Composable
internal fun MainNavigationRail(
    nav: MainNavState,
    railState: NavigationRailState,
    onPidaiBoundsChange: (Rect) -> Unit,
) {
    // 钉死行高：字号随展开进度插值时，文字高度按整像素跳变会让所有格子抖一下
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
 * 侧栏里的屁岱。miuix 侧栏的展开进度是 internal 的，这里用同一条弹簧自己算一份，
 * 几何照它的公式，展开 / 收起时和邻居一起平滑移动。
 */
@Composable
private fun RailPidaiItem(
    nav: MainNavState,
    railState: NavigationRailState,
    onPidaiBoundsChange: (Rect) -> Unit,
) {
    val railProgress by animateFloatAsState(
        targetValue = if (railState.isExpanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 322f, visibilityThreshold = 0.001f),
        label = "pidaiRailProgress",
    )
    // 布局阶段读写，不用 State
    val contentRight = remember { floatArrayOf(0f) }
    Layout(
        modifier = Modifier
            // 气泡锚在内容右端：收起时是图标右边，展开时是文字右边
            .onGloballyPositioned {
                val b = it.boundsInRoot()
                onPidaiBoundsChange(b.copy(right = b.left + contentRight[0]))
            }
            .fillMaxWidth()
            .padding(vertical = 12.dp) // = NavigationRailDefaults.ItemVerticalPadding
            .clickable(onClick = nav.onPidaiTap),
        content = {
            PidaiTabButton(nav, nav.selected == BottomTab.PIDAI, diameter = 40.dp, paper = MiuixTheme.colorScheme.surface)
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
        val labelMax = (constraints.maxWidth - 70.dp.roundToPx() - 26.dp.roundToPx()).coerceAtLeast(0)
        val label = measurables[1].measure(Constraints(maxWidth = labelMax))
        // 与邻居按中心对齐：miuix 图标中心收起、展开时都在 40dp，文字起点 70dp
        val iconX = (40.dp.toPx() - icon.width / 2f).toInt()
        val labelX = 70.dp.roundToPx()
        val height = maxOf(icon.height, label.height)
        val iconRight = (iconX + icon.width).toFloat()
        contentRight[0] = lerp(iconRight, maxOf(iconRight, (labelX + label.width).toFloat()), f)
        layout(constraints.maxWidth, height) {
            icon.placeRelative(iconX, (height - icon.height) / 2)
            if (f > 0f) label.placeRelative(labelX, (height - label.height) / 2)
        }
    }
}

/**
 * 宽屏侧栏屁岱旁的气泡。侧栏内部会裁掉溢出内容，所以做成并列的覆盖层；
 * 锚点用函数传入只在这里读，免得侧栏动画时整个 MainScreen 每帧重组。
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

/** 侧栏动画期间内容区一直按终点宽度排版（多出的由外层裁掉），不逐帧重测。 */
@Composable
internal fun Modifier.railSettledWidth(railState: NavigationRailState, progress: State<Float>): Modifier {
    // [0] 终点宽度 px，[1] 对应目标（1 展开 / 0 收起 / -1 无）
    val held = remember { IntArray(2).also { it[1] = -1 } }
    return this.layout { measurable, constraints ->
        val target = if (railState.isExpanded) 1 else 0
        val p = progress.value
        val animating = abs(p - target) > 0.001f && constraints.hasBoundedWidth
        val width = if (animating) {
            if (held[1] != target) {
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
