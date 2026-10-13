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
import com.xjtu.toolbox.shell.ShellTab
import com.xjtu.toolbox.shell.ShellTabArt
import com.xjtu.toolbox.shell.ShellTabVariant
import com.xjtu.toolbox.shell.ShellTabs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/*
 * Android 这一端**只剩下来**的那几件：屁岱那一格（会动的机器人 + 它认的那几样宿主面）、
 * 宽屏侧栏里它那一格、以及旁边那只气泡。
 *
 * 底栏 / 侧栏的画法已经搬进 `:core` 的 `com.xjtu.toolbox.shell`（`ShellBars.kt`），
 * 这里只是把「自绘那一格」接到那条缝上（`ShellTabArt`）。行为逐字未改：几何、动画、
 * 瞟眼判据、气泡锚点全是原来那几行。
 */

/**
 * 主界面上手指最近按在哪（根坐标），屁岱拿来看。不是 State：只在它自己的帧循环里读，
 * 手指动不引起重组。松手后再看 [LINGER_MS] 才回正。
 */
internal object PidaiTouch {
    private const val LINGER_MS = 900L
    private var x = 0f
    private var y = 0f
    private var down = false
    private var upAt = Long.MIN_VALUE / 2

    fun point(): Offset? =
        if (down || android.os.SystemClock.uptimeMillis() - upAt < LINGER_MS) Offset(x, y) else null

    fun record(p: Offset, pressed: Boolean) {
        x = p.x
        y = p.y
        if (pressed) down = true
        else if (down) { down = false; upAt = android.os.SystemClock.uptimeMillis() }
    }
}

/** 挂在主界面最外层，只看不拦：Initial 阶段读位置，不消费事件。 */
internal fun Modifier.feedPidaiTouch(origin: () -> Offset): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
            PidaiTouch.record(change.position + origin(), change.pressed)
        }
    }
}

/**
 * 导航栏里的屁岱：同样是一个 tab，只是画成会动的机器人。
 * 点了别的 tab 它会朝那边瞟一眼（横排栏往左右看并眨那一侧的眼，竖排侧栏 [vertical] 往上下看）；
 * 在别的 tab 上看手指按的地方，在自己那页只看底栏附近的手指（拖底栏）。
 *
 * 搬迁前这一格的参数是「整个底栏状态」（`MainNavState`）；现在底栏状态在共享壳里
 * （`ShellNavState`），所以这里改成只收它真读的四样：当前选中的那一格、它自己那一格、
 * 那一格的序号（瞟眼往哪边看）、以及点击。**判据与画法一个字没改。**
 *
 * @param tabs 这一端的 tab 集合（只为取序号：原来用的是 `BottomTab.ordinal`）
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
    // 自己的中心（根坐标），布局时写、帧循环里读
    val center = remember { floatArrayOf(Float.NaN, 0f) }
    val reach = with(LocalDensity.current) { 160.dp.toPx() }
    val onOwnTab = current?.id == tab.id
    val look = look@{
        val p = PidaiTouch.point() ?: return@look null
        if (center[0].isNaN()) return@look null
        val dx = p.x - center[0]
        val dy = p.y - center[1]
        if (onOwnTab && abs(dy) > reach / 2) return@look null
        // 近处按比例，远处只取方向
        val k = 1f / maxOf(kotlin.math.hypot(dx, dy), reach)
        Offset(dx * k, dy * k)
    }
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
        gaze = look,
        glance = glance,
        modifier = modifier.onGloballyPositioned {
            val c = it.boundsInRoot().center
            center[0] = c.x
            center[1] = c.y
        },
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

/**
 * Android 那一份 [ShellTabArt]：屁岱那一格自己画（`PidaiNavButton`），其余格交给共享壳。
 *
 * 「哪一格自绘」按 id 认（`BottomTab.PIDAI.name`）；`onPidaiBoundsChange` 是宽屏气泡的锚点，
 * 由主界面传进来（它才知道覆盖层原点）。
 */
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
            modifier = variant.modifier,
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

