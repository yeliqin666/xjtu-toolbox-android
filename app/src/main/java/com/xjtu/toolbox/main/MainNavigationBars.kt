package com.xjtu.toolbox.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.xjtu.toolbox.agent.AgentThinkingHost
import com.xjtu.toolbox.agent.BubbleArrowSide
import com.xjtu.toolbox.agent.PidaiGlance
import com.xjtu.toolbox.agent.PidaiNavButton
import com.xjtu.toolbox.agent.ProactiveBubbleHost
import com.xjtu.toolbox.agent.ProactiveMessage
import com.xjtu.toolbox.agent.pidaiNavAppearance
import com.xjtu.toolbox.agent.rememberPidaiGaze
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabArt
import com.xjtu.toolbox.shell.ShellTabVariant
import com.xjtu.toolbox.shell.ShellTabs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Android 这一端**只剩下来**的那几件：屁岱那一格（会动的机器人 + 它认的那几样宿主面）、
 * 宽屏侧栏里它那一格、以及旁边那只气泡。
 */

/**
 * 导航栏里的屁岱：同样是一个 tab，只是画成会动的机器人。
 * 点了别的 tab 它会朝那边瞟一眼（横排栏往左右看并眨那一侧的眼，竖排侧栏 [vertical] 往上下看），
 * 平时眼睛跟着手指（[rememberPidaiGaze]）。
 *
 * @param tabs 这一端的 tab 集合（只为取序号）
 * @param current 当前页落的那一格；**null = 不属于任何一格**（底栏一格都不高亮，见 `ShellNavState.selected`）
 * @param tab 屁岱那一格
 * @param onClick 点这一格（共享壳接的是 `ShellNavState.onSelect`）
 */
@Composable
private fun PidaiTabButton(
    tabs: ShellTabs,
    current: ShellTab?,
    tab: ShellTab,
    onClick: () -> Unit,
    selected: Boolean,
    diameter: Dp,
    paper: Color,
    modifier: Modifier = Modifier,
    liftUp: Dp = 0.dp,
    vertical: Boolean = false,
) {
    val style = pidaiNavAppearance()
    var glance by remember { mutableStateOf<PidaiGlance?>(null) }
    var lastSelected by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        // current = null（当前页不属于任何一格）时没有「切到哪一格」可言 ⇒ 机器人不看那一边。
        // Android 这一端每一页都落在某一格上，走不到这一支（桌面才有那 37 条非 tab 路由）。
        val now = current
        if (now != null && now.id != lastSelected?.id && now.id != tab.id) {
            val side = if (tabs.indexOf(now.id) < tabs.indexOf(tab.id)) -1f else 1f
            glance = PidaiGlance(if (vertical) 0f else side, if (vertical) side else 0f, (glance?.serial ?: 0) + 1)
        }
        lastSelected = now
    }
    val gaze = rememberPidaiGaze()
    PidaiNavButton(
        onClick = onClick,
        excited = ProactiveBubbleHost.message != null,
        thinking = AgentThinkingHost.isThinking,
        selected = selected,
        diameter = diameter,
        liftUp = liftUp,
        paper = paper,
        ink = style.ink,
        cast = style.cast,
        shape = style.shape,
        gaze = gaze.look,
        glance = glance,
        modifier = modifier.then(gaze.track),
    )
}

/**
 * 侧栏里的屁岱。miuix 侧栏的展开进度是 internal 的，这里用同一条弹簧自己算一份，
 * 几何照它的公式，展开 / 收起时和邻居一起平滑移动。
 */
@Composable
private fun RailPidaiItem(
    tabs: ShellTabs,
    current: ShellTab?,
    tab: ShellTab,
    onClick: () -> Unit,
    expanded: Boolean,
    onPidaiBoundsChange: (Rect) -> Unit,
) {
    val railProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
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
            .clickable(onClick = onClick),
        content = {
            PidaiTabButton(
                tabs = tabs,
                current = current,
                tab = tab,
                onClick = onClick,
                selected = current?.id == tab.id,
                diameter = 40.dp,
                paper = MiuixTheme.colorScheme.surface,
                vertical = true,
            )
            Text(
                tab.label,
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

/** Android 那一份 [ShellTabArt]：屁岱那一格自己画（`PidaiNavButton`），其余格交给共享壳。 */
internal class AndroidShellTabArt(
    private val tabs: ShellTabs,
    private val pidai: ShellTab,
    private val onPidaiBoundsChange: (Rect) -> Unit,
) : ShellTabArt {

    override fun handles(tab: ShellTab): Boolean = tab.id == pidai.id

    @Composable
    override fun barTab(current: ShellTab?, tab: ShellTab, selected: Boolean, variant: ShellTabVariant) {
        PidaiTabButton(
            tabs = tabs,
            current = current,
            tab = tab,
            onClick = variant.onClick,
            selected = selected,
            diameter = variant.diameter,
            paper = variant.paper,
            // 底栏气泡按它的实际位置对准（屁岱不一定在正中）
            modifier = variant.modifier.onGloballyPositioned { onPidaiBoundsChange(it.boundsInRoot()) },
            liftUp = variant.liftUp,
            vertical = variant.vertical,
        )
    }

    @Composable
    override fun railTab(current: ShellTab?, tab: ShellTab, expanded: Boolean, variant: ShellTabVariant) {
        RailPidaiItem(
            tabs = tabs,
            current = current,
            tab = tab,
            onClick = variant.onClick,
            expanded = expanded,
            onPidaiBoundsChange = onPidaiBoundsChange,
        )
    }
}

/**
 * 底栏上方气泡的落点：零高度 layout 往上溢出绘制，出现时不撑高底栏、页面不跳。
 * [anchorX] 是屁岱那一格中心的根坐标：气泡尽量居中于它（夹在栏宽内），尖角对准它；
 * null（屁岱不在底栏上）时气泡居中、尖角朝自身中心。
 */
@Composable
internal fun BarBubbleSlot(anchorX: Float?, content: @Composable (arrowFromStart: Dp?) -> Unit) {
    val density = LocalDensity.current
    var hostLeft by remember { mutableFloatStateOf(0f) }
    var hostWidth by remember { mutableIntStateOf(0) }
    var bubbleWidth by remember { mutableIntStateOf(0) }
    fun leftOf(width: Int): Int? = anchorX?.let {
        (it - hostLeft - width / 2f).roundToInt().coerceIn(0, (hostWidth - width).coerceAtLeast(0))
    }
    val arrowFromStart = anchorX?.let { with(density) { (it - hostLeft - (leftOf(bubbleWidth) ?: 0)).toDp() } }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                hostLeft = it.positionInRoot().x
                hostWidth = it.size.width
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
            },
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    val x = leftOf(placeable.width) ?: ((constraints.maxWidth - placeable.width) / 2)
                    layout(constraints.maxWidth, placeable.height) { placeable.place(x, 0) }
                }
                .onSizeChanged { bubbleWidth = it.width },
        ) { content(arrowFromStart) }
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

