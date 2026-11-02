package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 悬浮球（也是长条顶部那枚把手）的直径。 */
private val ORB_BALL = 46.dp

/** 展开长条的宽。 */
private val ORB_RAIL_WIDTH = 64.dp

/** 展开长条里一颗功能图标的直径。 */
private val ORB_TILE = 44.dp

/** 长条最多露出的功能行数；超出的靠滚动。 */
private const val ORB_VISIBLE_ROWS = 6

/**
 * 编辑态右侧的「添加卡片」悬浮球（§7 补充）。
 *
 * 三种形态在同一棵树上切换：
 * - **球**：一枚圆形液态玻璃把手，贴在侧边；点一下展开。
 * - **长条**：把手升到最上方，下面是一列圆形功能图标（可滚动，长按拖出来放到组里）。
 * - **按住把手拖**：整条收回成那颗球跟手，松手自动吸回最近的侧边。
 *
 * 只在编辑态被挂上；挂上时先是一颗球，随即自动展开成长条（中间不停顿的展开动画）。
 */
@Composable
internal fun HomeAddTileOrb(
    faces: Map<String, HomeServiceRow>,
    catalog: List<AppService>,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
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

    // 球 ↔ 长条；刚挂上时是球，随即自动展开（播那段连续展开动画）
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expanded = true }

    // 按住把手拖动中：整条收成球跟手
    var handleDragging by remember { mutableStateOf(false) }
    // 球（或收起的把手）中心在页面里的位置（页面局部坐标）；null = 还没拖过，用默认位
    var orbCenter by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(modifier) {
        val pageW = constraints.maxWidth.toFloat()
        val pageH = constraints.maxHeight.toFloat()
        // 默认位：右侧边偏上一点
        val center = orbCenter ?: Offset(pageW - ballPx, pageH * 0.32f)

        // 球 ↔ 长条：把手拖动中也强制显示成球
        val showRail = expanded && !handleDragging
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset((center.x - ballPx / 2f).roundToInt(), (center.y - ballPx / 2f).roundToInt()) },
        ) {
            AnimatedContent(
                targetState = showRail,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "addTileOrb",
            ) { rail ->
                if (rail) {
                    OrbRail(
                        faces = faces,
                        catalog = catalog,
                        liquid = liquid,
                        backdrop = backdrop,
                        session = session,
                        onPaletteStart = onPaletteStart,
                        onPaletteDrop = onPaletteDrop,
                        onPaletteCancel = onPaletteCancel,
                        onHandleDrag = { delta ->
                            handleDragging = true
                            orbCenter = center + delta
                        },
                        onHandleDrop = {
                            handleDragging = false
                            orbCenter = snapToSide(center, pageW, pageH, ballPx)
                        },
                    )
                } else {
                    OrbHandle(
                        liquid = liquid,
                        backdrop = backdrop,
                        onTap = { expanded = true },
                        onDrag = { delta ->
                            handleDragging = true
                            orbCenter = center + delta
                        },
                        onDrop = {
                            handleDragging = false
                            orbCenter = snapToSide(center, pageW, pageH, ballPx)
                        },
                    )
                }
            }
        }
    }
}

/** 松手后把球吸回最近的侧边（左 / 右边缘），纵向夹进页面里。 */
private fun snapToSide(center: Offset, pageW: Float, pageH: Float, ballPx: Float): Offset {
    val margin = 8f
    val x = if (center.x < pageW / 2f) margin + ballPx / 2f else pageW - margin - ballPx / 2f
    val y = center.y.coerceIn(margin + ballPx / 2f, pageH - margin - ballPx / 2f)
    return Offset(x, y)
}

/** 那一枚圆形把手（球 = 它，长条最上方也是它）：液态玻璃，按住拖动、点一下展开。 */
@Composable
private fun OrbHandle(
    liquid: Boolean,
    backdrop: Backdrop?,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
) {
    Box(
        Modifier
            .size(ORB_BALL)
            .orbGlass(liquid, backdrop, CircleShape)
            .pointerInput("orbHandle") {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag == null) {
                        // 没拖起来 = 点一下：球展开成长条
                        onTap()
                        return@awaitEachGesture
                    }
                    // 拖起来了：整条收成球跟手，松手吸附侧边。finally 兜底：手势协程被
                    // 取消（重组、系统抢走）时也要归位，不能让球卡在半中央
                    try {
                        var last = drag.position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == drag.id } ?: break
                            if (!change.pressed) break
                            onDrag(change.position - last)
                            last = change.position
                            change.consume()
                        }
                    } finally {
                        onDrop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "拖动悬浮球",
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 展开的长条：顶部把手 + 一列圆形功能图标（可滚动，长按拖出来放到组里）。 */
@Composable
private fun OrbRail(
    faces: Map<String, HomeServiceRow>,
    catalog: List<AppService>,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onPaletteStart: (routeId: String, startPointer: Offset) -> Unit,
    onPaletteDrop: () -> Unit,
    onPaletteCancel: () -> Unit,
    onHandleDrag: (Offset) -> Unit,
    onHandleDrop: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 这一端打不开的功能不进面板（faces 里本来就没有它们）
    val items = remember(catalog, faces) { catalog.filter { faces.containsKey(it.route.id) } }
    Column(
        Modifier
            .width(ORB_RAIL_WIDTH)
            .orbGlass(liquid, backdrop, RoundedCornerShape(ORB_RAIL_WIDTH / 2))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 把手升到最上方：按住它整条收走（点它不做别的）
        OrbHandle(liquid = liquid, backdrop = backdrop, onTap = {}, onDrag = onHandleDrag, onDrop = onHandleDrop)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.height(ORB_TILE * ORB_VISIBLE_ROWS),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size) { index ->
                val service = items[index]
                val face = faces.getValue(service.route.id)
                OrbTileIcon(
                    face = face,
                    liquid = liquid,
                    backdrop = backdrop,
                    session = session,
                    onDragOut = { startPointer -> onPaletteStart(service.route.id, startPointer) },
                    onDrop = onPaletteDrop,
                    onCancel = onPaletteCancel,
                )
            }
        }
    }
}

/** 长条里一颗圆形功能图标：液态玻璃，长按拖出来放到组里。 */
@Composable
private fun OrbTileIcon(
    face: HomeServiceRow,
    liquid: Boolean,
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
            .orbGlass(liquid, backdrop, CircleShape)
            .pointerInput(face.key) {
                // 官方长按拖拽：onDragCancel 有兜底，手势被抢走不会卡死会话
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragOut(iconTop + offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        // 绝对合成：图标位置 + 事件的图标相对坐标，滚动/动画都污染不了
                        session.pointer = iconTop + change.position
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

/** 悬浮球与长条共用的液态玻璃（采首页背景层，和卡片同一套参数）；关液态时退回半透明底。 */
@Composable
private fun Modifier.orbGlass(liquid: Boolean, backdrop: Backdrop?, shape: Shape): Modifier {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    if (!liquid || backdrop == null) {
        return this.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.6f), shape)
    }
    val accent = MiuixTheme.colorScheme.primary
    val shadow = remember(accent, dark) { floatingGlassShadow(accent, dark) }
    return this.then(
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                padding = maxOf(padding, 10.dp.toPx())
                vibrancy()
                blur(8.dp.toPx())
                lens(9.dp.toPx(), 12.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.65f) },
            shadow = { shadow },
            onDrawSurface = { drawRect(fill) },
        ),
    )
}
