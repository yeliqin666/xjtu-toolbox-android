package com.xjtu.toolbox.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.components.CountBadge
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 悬浮球（球态就是这一整颗）也是长条顶部那枚把手的直径。 */
private val ORB_BALL = 46.dp

/** 长条里图标列那一条的宽（把手也钉在这一条正中）。 */
private val ORB_RAIL_WIDTH = 64.dp

/** 长条上下内边距的**下限**：玻璃比把手多让出这一圈，球态没有；圆角大时按 [knobTopPadding] 再让。 */
private val ORB_PAD = 6.dp

/** 把手与图标列表之间的空隙。 */
private val ORB_GAP = 4.dp

/** 展开长条里一颗功能图标的直径。 */
private val ORB_TILE = 44.dp

/** 长条最多露出的功能行数；超出的靠滚动。 */
private const val ORB_VISIBLE_ROWS = 6

// 长条的圆角**恒为全圆**（= 长条半宽 32dp，两端是半圆，也就是胶囊），**不吃**主页设置里那条
// 「卡片圆角」：那条滑条只管卡片自己。把手的让位量因此也成了定值（见 knobTopPadding）。

/** 把手两侧在图标列里留出的边（64 − 46 的一半）：判断圆角吃掉多少时要用它。 */
private val ORB_KNOB_SIDE = (ORB_RAIL_WIDTH - ORB_BALL) / 2

/**
 * 图标旁边那片名称栏的宽度（**外框朝远离贴边那一侧长出去的总宽**）。
 *
 * 名称栏**不是**一个开关，而是滚动列表时临时露出来的：面板里十几个功能、一屏只放得下 6 个，
 * 滚的时候光看图标认不出滚到谁了。所以只在滚动时横向拉开，其余时刻（开始拖动、点把手、
 * 点到别处）都收回去——收起时不组合那段文字，一次文字布局都不做。
 *
 * 104dp 是按最宽的一行算出来的（2026-11 加宽）：`10` 外沿安全边 + `15` 计数圆标 + `5` 圆标与字的
 * 间隙 + 名字（首页最长的四个汉字约 52dp）+ `8` 图标侧呼吸位 ≈ 90，再留十几格给五六字的名字。
 * 加宽之前是 84dp，塞进圆标之后四个字的名字就要开始省略了。
 */
private val ORB_LABEL_WIDTH = 104.dp

/**
 * 名称栏**靠外沿那一侧**的安全边（离玻璃边缘的距离）。
 *
 * 那一侧正是胶囊的圆弧，字与圆标贴上去会被弧边啃掉一角；也给「拉开帘子」留出终点，收尾时
 * 文字不会顶着玻璃边停下。图标侧另有 8dp 呼吸位（见 `OrbLabel`）。
 */
private val ORB_LABEL_EDGE_PAD = 10.dp

/** 把手 + 空隙 + 列表窗口，不含上下内边距（内边距要跟着圆角变，见 [knobTopPadding]）。 */
private val ORB_BODY = ORB_BALL + ORB_GAP + ORB_TILE * ORB_VISIBLE_ROWS

/**
 * 胶囊两端半径 [corner] 下，把手顶边至少要离玻璃顶多远，整颗把手才落在半圆里。
 *
 * 把手是 46dp 的圆、居中在 64dp 那一列里（左右各留 [ORB_KNOB_SIDE] = 9dp）。半圆把直角吃掉
 * 之后，离顶 p 处的玻璃边界已经内缩 `r − √(r² − (r−p)²)`；要求它不超过 9dp，解出
 * `p ≥ r − √(2·r·s − s²)`（s = 9dp）。
 *
 * 长条恒为全圆（r = 32dp），所以这个值现在是定值 9.75dp；留着算式是因为它说明了这个数从哪来
 * ——改长条宽度或球直径时它会自己跟着变，不用重新量一遍。
 */
private fun knobTopPadding(corner: Dp): Dp {
    val r = corner.value
    val s = ORB_KNOB_SIDE.value
    if (r <= s) return 0.dp // 直角段：两侧本来就有 9dp，吃不到把手
    val need = r - sqrt((2f * r * s - s * s).coerceAtLeast(0f))
    return need.dp.coerceAtLeast(0.dp)
}

/**
 * 贴边保留的呼吸位。**长条与球共用同一个**：贴边一律按长条那 64dp 算，所以球态看着比长条
 * 内缩一点（把手在长条里本来就离边 9dp）——这样两种形态互相切换时把手一动不动，不会跳。
 */
private val ORB_EDGE_MARGIN = 8.dp

/**
 * 编辑态右侧的「添加卡片」悬浮球（§7 补充）。
 *
 * **球和长条是同一个外框的两个尺寸**，不是两棵不同的树：
 * - **长条**：64dp 宽的液态玻璃，顶部把手 + 一列 44dp 玻璃圆钮（可滚动，长按拖出来放到组里）。
 * - **球**：同一块玻璃缩成 46dp 的圆，把手就在正中。点一下把手在两者之间过渡。
 *
 * 之所以这么做：①把手中心在两种形态下是**同一个点**，切换时它不会跳（贴边距离也就统一了）；
 * ②同一时刻只有一个带 pointerInput 的把手节点，不会出现「交叉淡入淡出那一小段两个节点抢同一
 * 下按下」；③不用 `AnimatedContent`——它给容器尺寸做动画，而这里的宽度还受名称栏控制，被它
 * 夹着走图标列就会被一起挪。
 *
 * **滚动图标列**会横向露出名字（外框朝远离贴边那侧边长，图标列原地不动）；再没有任何手势会
 * **展开**它——按住不动什么都不会发生，所以不会和「按住拖拽」打架。
 *
 * 名字栏的收起时机（都是「确认了别的意图」才收，绝不因为滚动而收）：开始拖拽（整条 / 拖出
 * 图标）的那一刻、点把手、以及在别处按下（失焦）。
 *
 * 只在编辑态被挂上；挂上时直接就是长条（编辑态一进来就要能拖图标）。
 */
@Composable
internal fun HomeAddTileOrb(
    faces: Map<String, HomeServiceRow>,
    catalog: List<AppService>,
    /**
     * 每张卡在整页上已经放了几块（路由 id → 块数）。
     *
     * 名字栏里把它显示在**图标与文字中间**（见 [OrbLabel]）：同一张卡现在允许在主页上出现
     * 多块，光看图标认不出「这个功能是不是已经摆过了、摆了几个」，加一个数就有了。
     */
    tileCounts: Map<String, Int>,
    liquid: Boolean,
    /** 液态玻璃是否带折射；见 HomeTileFaceBox 的 `refraction` 说明。 */
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /**
     * 名字栏是否露着。
     *
     * 传的是 State 而不是值：它在滚动时翻，读在**悬浮条内部**（见下面的 `labelExtra`），
     * 于是首页不必为「露出名字」重组一遍——真正需要重排的只有这条长条自己。
     * 唯一入口是滚动图标列；退出编辑态时由首页复位。
     */
    labels: MutableState<Boolean>,
    /** 长按图标拖出一个新功能：[startPointer] 是手指的页面局部坐标（首页统一换算、开预览）。 */
    onPaletteStart: (routeId: String, startPointer: Offset) -> Unit,
    /** 拖出新块的手势松手：首页把预览固化、幽灵吸附到落点。 */
    onPaletteDrop: () -> Unit,
    /** 手势被系统抢走：清会话，不留卡住的幽灵块。 */
    onPaletteCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val ballPx = with(density) { ORB_BALL.toPx() }
    val railPx = with(density) { ORB_RAIL_WIDTH.toPx() }
    val marginPx = with(density) { ORB_EDGE_MARGIN.toPx() }

    // 长条 ↔ 球。刚挂上直接是长条；点把手切换。
    var expanded by remember { mutableStateOf(true) }
    // 把手中心在页面里的位置（页面局部坐标）；null = 还没拖过，用默认位
    var orbCenter by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(modifier) {
        val pageW = constraints.maxWidth.toFloat()
        val pageH = constraints.maxHeight.toFloat()
        // 默认位：右侧贴边、偏上。x 按**长条**半宽算，与吸附同一个公式
        val center = orbCenter ?: Offset(pageW - marginPx - railPx / 2f, pageH * 0.32f)
        // 把手拖动的抓取偏移（手指 − 把手中心）：绝对跟手用，全程不变
        var handleGrab by remember { mutableStateOf<Offset?>(null) }

        // 停靠侧：决定贴哪一边、名字栏往哪一侧长
        val dockRight = center.x >= pageW / 2f

        // 形态过渡：1 = 长条，0 = 球。**只动外框**——把手中心恒为 center，过渡期间按钮不挪，
        // 拖动跟手也不受影响（拖动中本来就不会切形态）。
        val shape by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(200),
            label = "orbShape",
        )
        // 名字栏拉出来的额外宽度，只加在**远离贴边**的那一侧
        val labelExtra by animateDpAsState(
            targetValue = if (labels.value && expanded) ORB_LABEL_WIDTH else 0.dp,
            animationSpec = tween(220),
            label = "orbLabels",
        )

        // 长条**恒为全圆**：半径就取半宽（32dp），两端是半圆，跟设置里那条「卡片圆角」无关。
        // 把手照样按这个常量让位（见 knobTopPadding），所以把手永远整颗落在半圆里。
        val railCorner = ORB_RAIL_WIDTH / 2
        // 长条态要留的内边距：至少 6dp，半圆吃掉把手时按公式再让（定值 9.75dp）
        val railPad = maxOf(ORB_PAD, knobTopPadding(railCorner))
        // 动画中的内边距：球态没有内边距，靠 shape 从 0 长到 railPad
        val pad = railPad * shape

        // 图标列（连着把手那一栏）当前宽度：球态 = 球直径，长条态 = ORB_RAIL_WIDTH
        val colW = ORB_BALL + (ORB_RAIL_WIDTH - ORB_BALL) * shape
        val boxW = colW + labelExtra
        val boxH = ORB_BALL + (ORB_BODY + railPad * 2 - ORB_BALL) * shape
        // 圆角：球态是正圆（半宽），长条态是上面那个用户值；再夹一次「不能超过短边一半」，
        // 免得过渡到一半时圆角比框还大（形状会翻出去）
        val corner = (ORB_BALL / 2 + (railCorner - ORB_BALL / 2) * shape)
            .coerceAtMost(minOf(boxW, boxH) / 2)
        val glassShape: Shape = RoundedCornerShape(corner)

        val colHalfPx = with(density) { colW.toPx() } / 2f
        val boxWPx = with(density) { boxW.toPx() }
        val padPx = with(density) { pad.toPx() }
        // 落定后的内边距与整块高度：吸附用它们，不用动画中的中间值
        val railPadPx = with(density) { railPad.toPx() }
        val railHeightPx = with(density) { (ORB_BODY + railPad * 2).toPx() }
        // 玻璃顶边比把手中心高出的量：球态正好是半径，长条态再多让一圈内边距。
        // 反过来就是「把手中心在任何形态下都落在 center」——拖动锚点因此与形态无关。
        val topInsetPx = ballPx / 2f + padPx

        /**
         * 松手吸附：贴边一律按**长条**的半宽算（球态因此内缩 9dp，见 [ORB_EDGE_MARGIN]）。
         * 纵向按 [asRail] 那一形态的整块高度夹进页面里——球贴着屏幕底放时点开长条，
         * 得先把它往上挪够，否则半条会落在屏幕外。
         */
        fun snap(from: Offset, asRail: Boolean): Offset {
            val x = if (from.x < pageW / 2f) {
                marginPx + railPx / 2f
            } else {
                pageW - marginPx - railPx / 2f
            }
            val height = if (asRail) railHeightPx else ballPx
            val inset = if (asRail) ballPx / 2f + railPadPx else ballPx / 2f
            val low = marginPx + inset
            val high = (pageH - marginPx - height + inset).coerceAtLeast(low)
            return Offset(x, from.y.coerceIn(low, high))
        }

        // 卸载时清掉登记的矩形：编辑态退出后手势层不该还挡着这一块
        DisposableEffect(Unit) {
            onDispose { session.paletteRect = null }
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                // 定位锚点是**把手中心**：外框从它往远离贴边那一侧长，所以展开量不参与
                // 把手的位置（上一版按「贴边 + 内容宽度」推位置，把拖动一起推坏了）。
                .offset {
                    val left = if (dockRight) center.x + colHalfPx - boxWPx else center.x - colHalfPx
                    IntOffset(left.roundToInt(), (center.y - topInsetPx).roundToInt())
                }
                .size(boxW, boxH)
                // 玻璃先画（投影要能落在框外面，所以 clip 必须在它**里面**），
                // 再由内层 clip 裁掉动画中间溢出来的那半截列表
                .orbGlass(liquid, refraction, backdrop, glassShape)
                .clip(glassShape)
                .onGloballyPositioned { coords ->
                    // 这一层当前的视口矩形：手势层对落在这里的 down 一律放行，
                    // 不排除就会把手把上的长按/拖动抢走。
                    val bounds = coords.boundsInRoot()
                    session.paletteRect = Rect(
                        left = bounds.left - session.pageOrigin.x,
                        top = bounds.top - session.pageOrigin.y,
                        right = bounds.right - session.pageOrigin.x,
                        bottom = bounds.bottom - session.pageOrigin.y,
                    )
                },
        ) {
            Column(Modifier.padding(top = pad)) {
                // 把手钉在**停靠侧**那一栏的正中（那一栏宽度恒为 colW，球态就正好是整颗球）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (dockRight) Arrangement.End else Arrangement.Start,
                ) {
                    Box(Modifier.width(colW), contentAlignment = Alignment.Center) {
                        OrbHandle(
                            session = session,
                            // 圆角那种「平涂圆钮」只在长条态出现；球态整块玻璃就是那颗球
                            knobAlpha = shape,
                            onTap = {
                                if (expanded) {
                                    // 名字栏露着时点把手：两段动画同时跑，等于直接从
                                    // 大矩形收成球（不必先回长条再收，中间不会卡一下）
                                    labels.value = false
                                    expanded = false
                                } else {
                                    // 展开前先按长条的高度夹一下纵向位置：球贴着屏幕底时
                                    // 直接展开会有半条落到屏幕外
                                    orbCenter = snap(orbCenter ?: center, asRail = true)
                                    expanded = true
                                }
                            },
                            onDragStart = { finger ->
                                // 按住拖整条：确认开始移动的这一刻先收名字栏，再跟手
                                // （把手中心不动，所以收起不会让它在手指下跳一下）
                                labels.value = false
                                handleGrab = finger - (orbCenter ?: center)
                            },
                            onDragTo = { finger -> handleGrab?.let { orbCenter = finger - it } },
                            onDrop = {
                                handleGrab = null
                                orbCenter = snap(orbCenter ?: center, asRail = expanded)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(ORB_GAP))
                OrbTileList(
                    faces = faces,
                    catalog = catalog,
                    tileCounts = tileCounts,
                    liquid = liquid,
                    refraction = refraction,
                    backdrop = backdrop,
                    session = session,
                    dockRight = dockRight,
                    // 图标那一栏跟着形态一起变宽，图标因此始终落在把手正下方
                    colW = colW,
                    width = boxW,
                    fade = shape,
                    // 名字栏比外框晚一点收、也与形态一起淡：动画中间不会闪出一个方角
                    labelExtra = labelExtra,
                    onRevealLabels = { if (expanded) labels.value = true },
                    onCollapseLabels = { labels.value = false },
                    onPaletteStart = onPaletteStart,
                    onPaletteDrop = onPaletteDrop,
                    onPaletteCancel = onPaletteCancel,
                )
            }
        }
    }
}

/**
 * 那枚把手（球态就是整颗球）：一枚**平涂**的圆钮 + 拖拽图示，不再自带一层液态玻璃。
 *
 * 为什么不再叠玻璃：外框本身就是液态玻璃，再叠一层会在圆角处跟外框的弧「咬」在一起（看着像
 * 探出了玻璃边）；而且每多一层就是每帧多一次离屏采样。球态时外框正好是一枚玻璃圆，这一层
 * 淡出到 0，剩下的就是纯玻璃球——两种形态的观感于是连成一套。
 *
 * **跟手必须用「绝对坐标」而不是差分**。这个节点自己会跟着手指移动，如果用
 * `change.position - last` 累加：节点移动 Δ 后，下一帧的 `change.position` 就少了 Δ，
 * 于是位移被抵消一半——把手只跟一半，手指很快滑出节点范围，事件中断走 `finally`，
 * 直接吸附到边上（就是「拖到一半脱手」）。
 *
 * 改成 `节点实测原点 + 事件相对坐标` 就对了：事件坐标永远是相对**当前**布局位置的，
 * 而 [origin] 也是当前布局位置（上一帧布局结束时刷新），两者相加就是手指的真实页面坐标。
 */
@Composable
private fun OrbHandle(
    session: HomeDragSession,
    /** 平涂圆钮的浓度：长条态 1、球态 0（球态整块玻璃就是球，不需要内衬）。 */
    knobAlpha: Float,
    onTap: () -> Unit,
    /** 拖起来的瞬间：给出手指的页面局部坐标（调用方据此记抓取偏移）。 */
    onDragStart: (finger: Offset) -> Unit,
    /** 拖动中：给出**绝对**的手指页面局部坐标。 */
    onDragTo: (finger: Offset) -> Unit,
    onDrop: () -> Unit,
) {
    // 把手用**主题色**：和卡片右下角那个缩放把手同一条语汇（它也是沿圆弧染一段主题色），
    // 一眼就能认出「这块是能拖的」
    val accent = MiuixTheme.colorScheme.primary
    val dark = LocalIsDarkTheme.current
    // 把手自己的页面局部原点：随它移动，但每帧由 onGloballyPositioned 刷新
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .size(ORB_BALL)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft - session.pageOrigin }
            .pointerInput("orbHandle") {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    // 自己判 slop，不用 awaitTouchSlopOrCancellation：后者在「变更已被别人消费
                    // 过」时直接返回 null，而这条链上还有页面根的手势层与滚动列。
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!dragging && (change.position - down.position).getDistance() > slop) {
                                dragging = true
                                onDragStart(origin + change.position)
                            }
                            if (dragging) {
                                change.consume()
                                onDragTo(origin + change.position)
                            }
                        }
                    } finally {
                        // 手势被取消（重组、系统抢走）也要把抓取状态归位
                        if (dragging) onDrop()
                    }
                    // 没拖起来 = 点一下：长条与球互相切换（在 finally 之后，取消时不会走到）
                    if (!dragging) onTap()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (knobAlpha > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = knobAlpha }
                    .background(accent.copy(alpha = if (dark) 0.30f else 0.18f), CircleShape),
            )
        }
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "拖动悬浮球",
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 长条里那一列圆形功能图标（可滚动，长按拖出来放到组里）；球态时被外框裁掉、整体淡出。 */
@Composable
private fun OrbTileList(
    faces: Map<String, HomeServiceRow>,
    catalog: List<AppService>,
    /** 路由 id → 这一张卡在整页上已经放了几块（0 / 缺省 = 还没摆过）。 */
    tileCounts: Map<String, Int>,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /** 停靠在页面右侧（true）/ 左侧（false）：图标列贴哪一边、名字栏往另一边长。 */
    dockRight: Boolean,
    /** 图标那一栏的宽（跟形态过渡一起变）。 */
    colW: Dp,
    /** 整块外框的宽（= 图标栏 + 名字栏）。 */
    width: Dp,
    /** 整体透明度：球态 0（被裁掉之前先淡掉，中间不会闪出半个图标）。 */
    fade: Float,
    /** 名字栏状态（宽度由 `HomeAddTileOrb` 里那条动画统一管）。 */
    labelExtra: Dp,
    /** 列表被滚动：露出名字。 */
    onRevealLabels: () -> Unit,
    /** 开始拖拽：先收名字。 */
    onCollapseLabels: () -> Unit,
    onPaletteStart: (routeId: String, startPointer: Offset) -> Unit,
    onPaletteDrop: () -> Unit,
    onPaletteCancel: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 这一端打不开的功能不进面板（faces 里本来就没有它们）
    val items = remember(catalog, faces) { catalog.filter { faces.containsKey(it.route.id) } }
    // 名字只在真露出来时才组合：紧凑态既不量字也不画字（一次文字布局都不做）
    val showLabels = labelExtra > 0.dp

    // 「滑动列表」是露出名字的**唯一**入口：一屏只放得下 6 个，滚的时候得知道滚到谁了。
    // 这个状态一次手势只翻两次（起手 / 停手），直接当 key 读；反过来，滚动**绝不**收起它——
    // 只有「确认了别的意图」才收（见 onCollapseLabels 与页面手势层）。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onRevealLabels()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(width)
            .height(ORB_TILE * ORB_VISIBLE_ROWS)
            .graphicsLayer { alpha = fade },
    ) {
        items(items.size) { index ->
            val service = items[index]
            val face = faces.getValue(service.route.id)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (dockRight) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dockRight && showLabels) {
                    OrbLabel(face.title, labelExtra, textEnd = true, count = tileCounts[service.route.id] ?: 0)
                }
                Box(Modifier.width(colW), contentAlignment = Alignment.Center) {
                    OrbTileIcon(
                        face = face,
                        liquid = liquid,
                        refraction = refraction,
                        backdrop = backdrop,
                        session = session,
                        // 拖出图标 = 「按住再拖」：确认开始移动的这一刻先收名字栏
                        onDragOut = { startPointer ->
                            onCollapseLabels()
                            onPaletteStart(service.route.id, startPointer)
                        },
                        onDrop = onPaletteDrop,
                        onCancel = onPaletteCancel,
                    )
                }
                if (!dockRight && showLabels) {
                    OrbLabel(face.title, labelExtra, textEnd = false, count = tileCounts[service.route.id] ?: 0)
                }
            }
        }
    }
}

/**
 * 图标旁边那一行名字（外加「这张卡在主页上已经放了几块」）。
 *
 * 窗口宽度就是「名字栏已经拉出来多少」：文字按**固定宽度**排版（`requiredWidth`，不吃窗口
 * 挤压），窗口窄时只露出靠图标那一侧的一截，窗口张开时像拉帘子一样把它带出来——整个过程
 * 文字不重排，不会抖、不会闪省略号。
 *
 * [count] 排**图标与文字中间**（2026-11 按需求加）：贴边停靠时中间就是文字右侧，靠左停靠时
 * 是文字左侧，两种情况都在「靠图标那一侧」，和名字一起被帘子带出来。0 块时不画。
 *
 * 画法是一枚**主题色小圆标**（`CountBadge`：半透明底色 + 白细高光边 + 同色柔光，和首页顶栏铃铛
 * 那枚未读角标同一套配方——绝不采样背景），不是一行 `×3` 的弱化小字：那个数字是「这张卡在主页
 * 上已经有几块」的**计数**，和名字同级的信息，靠一枚钉子更一眼看得出来。
 */
@Composable
private fun OrbLabel(title: String, width: Dp, textEnd: Boolean, count: Int) {
    // 图标侧 8dp 呼吸位；**外沿那侧留 [ORB_LABEL_EDGE_PAD]**——那里是胶囊的圆弧，字与圆标
    // 贴上去会被弧边啃掉一角（2026-11 加宽名字栏时一起补的安全距离）
    val iconSidePad = Modifier.padding(
        start = if (textEnd) ORB_LABEL_EDGE_PAD else 8.dp,
        end = if (textEnd) 8.dp else ORB_LABEL_EDGE_PAD,
    )
    Box(
        Modifier.width(width).clipToBounds(),
        contentAlignment = if (textEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.requiredWidth(ORB_LABEL_WIDTH).then(iconSidePad),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!textEnd && count > 0) CountBadge(count)
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (textEnd) TextAlign.End else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            if (textEnd && count > 0) CountBadge(count)
        }
    }
}

/** 长条里一颗圆形功能图标：液态玻璃，长按拖出来放到组里。 */
@Composable
private fun OrbTileIcon(
    face: HomeServiceRow,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onDragOut: (startPointer: Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    // 图标的页面局部位置：每次回读存本地，合成手指坐标用（列表滚动后自动新鲜）
    var iconTop by remember(face.key) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .padding(vertical = 5.dp)
            .size(ORB_TILE)
            .onGloballyPositioned { coords ->
                // 换成页面局部坐标登记，拖出时从这里起算
                iconTop = coords.boundsInRoot().topLeft - session.pageOrigin
            }
            .orbGlass(liquid, refraction, backdrop, CircleShape)
            .pointerInput(face.key) {
                // 官方长按拖拽：onDragCancel 有兜底，手势被抢走不会卡死会话
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragOut(iconTop + offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        // 图标位置（视口）+ 事件的图标相对坐标 = 手指视口坐标。手势层不接管
                        // 悬浮条，所以这一路自己更新 session.pointer；落点仍是首页按快照派生的
                        session.move(iconTop + change.position)
                    },
                    onDragEnd = { onDrop() },
                    onDragCancel = { onCancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveIcon(icon = face.icon, color = face.color, size = ORB_TILE * 0.7f, iconSize = ORB_TILE * 0.42f)
    }
}

/**
 * 悬浮球与长条共用的液态玻璃；关液态时退回半透明底。
 *
 * 采样源由调用方决定（见 `HomeCardGroupScreen`）：**有壁纸时采背景层**（透出壁纸），
 * 纯色 / 渐变背景没有壁纸可采，改采**内容层**——悬浮条本来浮在卡片上面，采内容层才是
 * 「隔着玻璃看见下面的卡片」。它**不吃**卡片那条「纯色 / 渐变不模糊」的规则：按钮与悬浮条
 * 浮在内容上面，模糊的是此刻盖住的卡片，糊得动也值得糊；折射照旧由开关管。
 *
 * `backdrop == null` 只剩两种情况：液态玻璃关着，或者这一帧内容层没在录（不该发生），
 * 那时退回薄色底，至少不会画成一片透明。
 */
@Composable
private fun Modifier.orbGlass(
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    shape: Shape,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    val accent = MiuixTheme.colorScheme.primary
    val shadow = remember(accent, dark) { floatingGlassShadow(accent, dark) }
    if (!liquid) {
        return this.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.6f), shape)
    }
    if (backdrop == null) {
        // 兜底：没有采样源时用薄色 + 投影，别画成一片透明
        return this
            .appCardShadow(shape = shape)
            .background(
                if (dark) Color.Black.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.55f),
                shape,
            )
    }
    return this.then(
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                padding = maxOf(padding, 10.dp.toPx())
                vibrancy()
                // 悬浮球 / 长条浮在内容上面，模糊的是此刻盖住的卡片：照旧模糊（不吃卡片那条
                // 「纯色 / 渐变不模糊」的规则），折射也照旧由开关管
                blur(8.dp.toPx())
                if (refraction) lens(9.dp.toPx(), 12.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.65f) },
            shadow = { shadow },
            onDrawSurface = { drawRect(fill) },
        ),
    )
}
