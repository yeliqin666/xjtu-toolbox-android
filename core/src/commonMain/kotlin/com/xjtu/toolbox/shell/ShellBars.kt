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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.GlassBottomTabs
import com.xjtu.toolbox.ui.glass.GlassTab
import com.xjtu.toolbox.ui.glass.LocalCouiGlassBarBackdrop
import com.xjtu.toolbox.ui.glass.couiGlassBarMaterialEnabled
import com.xjtu.toolbox.ui.glass.couiGlassBarSupported
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
    accessory: (@Composable (ShellBarAccessoryForm) -> Unit)? = null,
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
            // 经典栏是铺满的一条：附件就是最右边那一格
            if (accessory != null) {
                Box(Modifier.align(Alignment.CenterVertically)) { accessory(ShellBarAccessoryForm.CLASSIC) }
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
    bottomPadding: Dp,
    bubble: @Composable () -> Unit,
    tabArt: ShellTabArt = ShellTabArt.None,
    accessory: (@Composable (ShellBarAccessoryForm) -> Unit)? = null,
) {
    // COUI 路径：底栏本体是 COUI NavigationBar 的移植，自带 16dp 外边距与 tappableElement
    // 导航条适配，外层不能再加本机的 20dp 侧距和 bottomPadding；只补 miuix floatingToolbar
    // 槽位私有的 4dp（FloatingToolbarSpacing），让胶囊下沿与 COUI 自己的 Scaffold 栏位同高。
    val couiBar = couiGlassBarMaterialEnabled && LocalCouiGlassBarBackdrop.current != null && couiGlassBarSupported
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (couiBar) Modifier else Modifier.padding(horizontal = 20.dp).padding(bottom = bottomPadding)),
    ) {
        Box(Modifier.then(if (couiBar) Modifier.padding(horizontal = 20.dp) else Modifier)) { bubble() }
        // 附件（小岱球）贴在胶囊右边、和胶囊同一条中线；COUI 胶囊自带 16dp 外边距，右侧再补同样的边
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        GlassBottomTabs(
            tabs = nav.tabs.tabs.map { tab ->
                // 自绘那一格（屁岱）免染色：强调色是把整排再画一遍，机器人不能画出第二份
                GlassTab(key = tab, tintExempt = tabArt.handles(tab), title = tab.label, icon = tab.icon) { selected ->
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
                        // 文字对齐 COUI 底栏：10sp / 行高 12sp / Medium
                        Text(
                            tab.label,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = tint,
                        )
                    }
                }
            },
            // -1（当前页不属于任何一格）会让玻璃底栏一格都不高亮 —— 见 GlassBottomTabs 那个参数
            selectedIndex = nav.tabs.indexOf(nav.selected?.id),
            onTabSelected = { index ->
                nav.onSelect(nav.tabs[index])
            },
            modifier = Modifier.weight(1f).then(if (couiBar) Modifier.offset(y = MIUIX_FLOATING_TOOLBAR_SPACING) else Modifier),
        )
        if (accessory != null) {
            Box(
                Modifier
                    .then(if (couiBar) Modifier.offset(y = MIUIX_FLOATING_TOOLBAR_SPACING).padding(end = 16.dp) else Modifier.padding(start = 12.dp)),
            ) { accessory(ShellBarAccessoryForm.GLASS) }
        }
        }
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
    accessory: (@Composable (ShellBarAccessoryForm) -> Unit)? = null,
) {
    Column(Modifier.offset(y = offsetY)) {
        bubble()
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
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
        if (accessory != null) {
            Box(Modifier.padding(start = 12.dp)) { accessory(ShellBarAccessoryForm.FLOATING) }
        }
        }
    }
}

/**
 * 侧栏标签的行高。钉死是因为标签字号随展开进度插值（miuix 的 12sp → 16sp），行高要是跟着字号走，
 * 条目高度里带着的那个标签高度就逐帧按整像素跳，所有格子跟着抖一下。
 *
 * 钉死的值必须比最大字号宽出一截：标签展开到底就是 16sp，行盒等于 em 盒的话，CJK 的
 * ascent + descent（约 1.2em）上下各被切掉一点，中文看起来发挤。22sp ≈ 16sp 的 1.35 倍。
 *
 * 收起态标签字号 12sp、也吃这条行高，所以每个格子比原来高 6dp 左右；展开态条目高度取
 * `max(图标 28dp, 标签)`，这一档仍由图标定，高度不变。
 */
private val RAIL_LABEL_LINE_HEIGHT = 22.sp

/** 宽屏可展开侧栏。 */
@Composable
fun ShellNavigationRail(
    nav: ShellNavState,
    railState: NavigationRailState,
    tabArt: ShellTabArt = ShellTabArt.None,
    accessory: (@Composable (ShellBarAccessoryForm) -> Unit)? = null,
) {
    // 缓存住：TextStyles 里是 14 个 MutableState，`copy` 一次就是 14 个新 state、一个新实例。
    // 不只是每次重组白分配——嵌套的 MiuixTheme 拿到的参数每次都「变了」，它内部会把 14 个字段
    // 全量同步一遍（updateTextStylesFrom）。缓存之后参数不变，那层整块能跳过。
    val base = MiuixTheme.textStyles
    val railTextStyles = remember(base) {
        base.copy(
            main = base.main.copy(
                lineHeight = RAIL_LABEL_LINE_HEIGHT,
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
            accessory?.invoke(ShellBarAccessoryForm.RAIL)
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
