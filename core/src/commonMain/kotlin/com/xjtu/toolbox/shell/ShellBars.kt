package com.xjtu.toolbox.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.xjtu.toolbox.ui.glass.GlassBottomTabs
import com.xjtu.toolbox.ui.glass.GlassTab
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

/*
 * 三块底栏 + 一块侧栏；「屁岱那一格」的画法走 [ShellTabArt] 这条缝。
 */

/** 经典底栏（界面风格选「经典」时，手机 / 平板竖屏用）。格数与顺序由 [ShellNavState.tabs] 说。 */
@Composable
fun ClassicBottomBar(
    nav: ShellNavState,
    colors: NavigationBarItemColors,
    bubble: @Composable () -> Unit,
    tabArt: ShellTabArt = ShellTabArt.None,
) {
    Column {
        bubble()
        NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
            nav.tabs.tabs.forEach { tab ->
                if (tabArt.handles(tab)) {
                    tabArt.barTab(
                        current = nav.selected,
                        tab = tab,
                        selected = nav.selected?.id == tab.id,
                        variant = ShellTabVariant(
                            diameter = 38.dp,
                            liftUp = 8.dp,
                            paper = MiuixTheme.colorScheme.surface,
                            // 「中心那一格像普通 tab 一样占 1/5 宽」：这一格自己不是 NavigationBarItem，
                            // 宽度得由这里给（`RowScope` 的 weight 只在这里拿得到）
                            modifier = Modifier.weight(1f),
                            onClick = { nav.onSelect(tab) },
                        ),
                    )
                } else {
                    NavigationBarItem(
                        selected = nav.selected?.id == tab.id,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        colors = colors,
                        badge = nav.badge(tab),
                    )
                }
            }
        }
    }
}

/** 手机竖屏的玻璃胶囊底栏，可以拖动切 tab。 */
@Composable
fun GlassBottomBar(
    nav: ShellNavState,
    selectedColor: Color,
    unselectedColor: Color,
    iconBox: Dp,
    backdrop: LayerBackdrop,
    exportedBackdrop: LayerBackdrop,
    bottomPadding: Dp,
    bubble: @Composable () -> Unit,
    tabArt: ShellTabArt = ShellTabArt.None,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPadding),
    ) {
        bubble()
        GlassBottomTabs(
            tabs = nav.tabs.tabs.map { tab ->
                // 自绘那一格（屁岱）免染色：强调色是把整排再画一遍，机器人不能画出第二份
                GlassTab(key = tab, tintExempt = tabArt.handles(tab)) { selected ->
                    if (tabArt.handles(tab)) {
                        tabArt.barTab(
                            current = nav.selected,
                            tab = tab,
                            selected = selected,
                            variant = ShellTabVariant(
                                diameter = 40.dp,
                                paper = MiuixTheme.colorScheme.surfaceContainerHigh,
                                onClick = { nav.onSelect(tab) },
                            ),
                        )
                    } else {
                        val tint = if (selected) selectedColor else unselectedColor
                        Box(Modifier.height(iconBox), contentAlignment = Alignment.Center) {
                            Box {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(tab.iconSize))
                                nav.badge(tab)?.let { badge ->
                                    Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp)) { badge() }
                                }
                            }
                        }
                        Text(tab.label, fontSize = 11.sp, maxLines = 1, color = tint)
                    }
                }
            },
            // -1（当前页不属于任何一格）会让玻璃底栏一格都不高亮 —— 见 GlassBottomTabs 那个参数
            selectedIndex = nav.tabs.indexOf(nav.selected?.id),
            onTabSelected = { index ->
                nav.onSelect(nav.tabs[index])
            },
            backdrop = backdrop,
            exportedBackdrop = exportedBackdrop,
            glass = true,
        )
    }
}

/** 平板竖屏的悬浮胶囊（miuix FloatingNavigationBar）。 */
@Composable
fun FloatingBottomBar(
    nav: ShellNavState,
    colors: NavigationBarItemColors,
    offsetY: Dp,
    bubble: @Composable () -> Unit,
    tabArt: ShellTabArt = ShellTabArt.None,
) {
    Column(Modifier.offset(y = offsetY)) {
        bubble()
        FloatingNavigationBar(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clip(RoundedCornerShape(50)),
        ) {
            nav.tabs.tabs.forEach { tab ->
                if (tabArt.handles(tab)) {
                    tabArt.barTab(
                        current = nav.selected,
                        tab = tab,
                        selected = nav.selected?.id == tab.id,
                        variant = ShellTabVariant(
                            diameter = 40.dp,
                            paper = MiuixTheme.colorScheme.surfaceContainerHigh,
                            onClick = { nav.onSelect(tab) },
                        ),
                    )
                } else {
                    FloatingNavigationBarItem(
                        selected = nav.selected?.id == tab.id,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        colors = colors,
                        badge = nav.badge(tab),
                    )
                }
            }
        }
    }
}

/** 宽屏可展开侧栏。 */
@Composable
fun ShellNavigationRail(
    nav: ShellNavState,
    railState: NavigationRailState,
    tabArt: ShellTabArt = ShellTabArt.None,
) {
    // 钉死行高：字号随展开进度插值时，文字高度按整像素跳变会让所有格子抖一下
    val railTextStyles = MiuixTheme.textStyles.let { ts ->
        ts.copy(
            main = ts.main.copy(
                lineHeight = 16.sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
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
            nav.tabs.tabs.forEach { tab ->
                if (tabArt.handles(tab)) {
                    tabArt.railTab(
                        current = nav.selected,
                        tab = tab,
                        expanded = railState.isExpanded,
                        variant = ShellTabVariant(
                            diameter = 40.dp,
                            paper = MiuixTheme.colorScheme.surface,
                            vertical = true,
                            onClick = { nav.onSelect(tab) },
                        ),
                    )
                } else {
                    NavigationRailItem(
                        selected = nav.selected?.id == tab.id,
                        onClick = { nav.onSelect(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        badge = nav.badge(tab),
                    )
                }
            }
        }
    }
}


/** 侧栏动画期间内容区一直按终点宽度排版（多出的由外层裁掉），不逐帧重测。 */
@Composable
fun Modifier.railSettledWidth(railState: NavigationRailState, progress: State<Float>): Modifier {
    // [0] 终点宽度 px，[1] 对应目标（1 展开 / 0 收起 / -1 无）
    val held = androidx.compose.runtime.remember { IntArray(2).also { it[1] = -1 } }
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
