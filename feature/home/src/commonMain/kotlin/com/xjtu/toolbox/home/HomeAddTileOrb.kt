package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.appCardShadow
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
 * - **球**：一枚圆形液态玻璃把手，贴在侧边；点一下展开成长条。
 * - **长条**：把手升到最上方，下面是一列圆形功能图标（可滚动，长按拖出来放到组里）；
 *   **点一下把手把它收回成球**（想让它别挡着画面时不必退出编辑态）。
 * - **按住把手拖**：整条收回成那颗球跟手，松手自动吸回最近的侧边。
 *
 * 只在编辑态被挂上；挂上时先是一颗球，随即自动展开成长条（中间不停顿的展开动画）。
 */
@Composable
internal fun HomeAddTileOrb(
    faces: Map<String, HomeServiceRow>,
    catalog: List<AppService>,
    liquid: Boolean,
    /** 液态玻璃是否带折射；见 HomeTileFaceBox 的 `refraction` 说明。 */
    refraction: Boolean,
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

    // 球（或收起的把手）中心在页面里的位置（页面局部坐标）；null = 还没拖过，用默认位
    var orbCenter by remember { mutableStateOf<Offset?>(null) }
    // 当前内容（球或长条）的实测尺寸：吸附贴边按它算，不能假定内容就是那个球
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(modifier) {
        val pageW = constraints.maxWidth.toFloat()
        val pageH = constraints.maxHeight.toFloat()
        // 默认位：右侧边偏上一点
        val center = orbCenter ?: Offset(pageW - ballPx, pageH * 0.32f)
        // 把手拖动的抓取偏移（手指 − 球心）：绝对跟手用，全程不变
        var handleGrab by remember { mutableStateOf<Offset?>(null) }

        // 球 ↔ 长条。**拖动把手时绝不能切换状态**：AnimatedContent 会把正在跑手势的那个
        // OrbHandle 节点销毁掉，手势协程跟着死，表现就是「按住把手拖不动」。
        // 拖动中整条保持原样跟着手指走（这也正是「拖动整个悬浮条」该有的行为）。
        val showRail = expanded
        // 停靠侧：只用来决定「贴哪一边」（这里不做变宽，长条宽度恒为 ORB_RAIL_WIDTH）
        val dockRight = center.x >= pageW / 2f
        // 卸载时清掉登记的矩形：编辑态退出后手势层不该还挡着这一块
        DisposableEffect(Unit) {
            onDispose { session.paletteRect = null }
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                // 定位仍然以**把手中心**为锚（拖动时就是这一条在跟手）。
                // 上一版按「贴边 + 内容宽度」推位置，把拖动也一起推坏了：这里改回原来的锚点。
                .offset {
                    IntOffset(
                        (center.x - ballPx / 2f).roundToInt(),
                        (center.y - ballPx / 2f).roundToInt(),
                    )
                }
                .onGloballyPositioned { coords ->
                    contentSize = coords.size
                    // 球或长条当前的视口矩形：手势层对落在这里的 down 一律放行，
                    // 那条长条盖在组区右缘上，不排除就会把图标们的长按手势抢走。
                    // 这一层从不做动画（拖动时整条一动不动），所以 bounds 是准的。
                    val bounds = coords.boundsInRoot()
                    session.paletteRect = Rect(
                        left = bounds.left - session.pageOrigin.x,
                        top = bounds.top - session.pageOrigin.y,
                        right = bounds.right - session.pageOrigin.x,
                        bottom = bounds.bottom - session.pageOrigin.y,
                    )
                },
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
                        refraction = refraction,
                        backdrop = backdrop,
                        session = session,
                        // 停靠侧：右停靠时图标列贴右边，左停靠贴左边
                        dockRight = dockRight,
                        onPaletteStart = onPaletteStart,
                        onPaletteDrop = onPaletteDrop,
                        onPaletteCancel = onPaletteCancel,
                        // 单击把手 = 把自己收回成那颗球（拖动仍走下面三个回调）
                        onHandleTap = { expanded = false },
                        onHandleDragStart = { finger ->
                            handleGrab = finger - (orbCenter ?: center)
                        },
                        onHandleDragTo = { finger ->
                            handleGrab?.let { orbCenter = finger - it }
                        },
                        onHandleDrop = {
                            handleGrab = null
                            orbCenter = snapToSide(orbCenter ?: center, pageW, pageH, ballPx, contentSize)
                        },
                    )
                } else {
                    OrbHandle(
                        liquid = liquid,
                        refraction = refraction,
                        backdrop = backdrop,
                        session = session,
                        onTap = { expanded = true },
                        onDragStart = { finger ->
                            handleGrab = finger - (orbCenter ?: center)
                        },
                        onDragTo = { finger -> handleGrab?.let { orbCenter = finger - it } },
                        onDrop = {
                            handleGrab = null
                            orbCenter = snapToSide(orbCenter ?: center, pageW, pageH, ballPx, contentSize)
                        },
                    )
                }
            }
        }
    }
}

/** 松手后把球吸回最近的侧边（左 / 右边缘），纵向夹进页面里。 */
private fun snapToSide(
    center: Offset,
    pageW: Float,
    pageH: Float,
    ballPx: Float,
    contentSize: IntSize,
): Offset {
    val margin = 8f
    // 定位一直是 `offset = center - ballPx/2`，这里保持同样的锚点关系
    val half = ballPx / 2f
    // 还没量到就退回球的尺寸，避免算出负数边距
    val w = if (contentSize.width > 0) contentSize.width.toFloat() else ballPx
    val h = if (contentSize.height > 0) contentSize.height.toFloat() else ballPx
    val x = if (center.x < pageW / 2f) {
        // 左贴边：内容左边距 = margin（长条是往右长的，左边贴住就放得下）
        margin + half
    } else {
        // 右贴边必须按**内容实际宽度**算：长条比球宽 18dp，仍按球算就会探出屏幕一截。
        // 目标：内容右边 = pageW - margin，而内容左 = center - half。
        pageW - margin - w + half
    }
    // 纵向同理：内容底边不要越过下边距
    val maxY = pageH - margin - h + half
    val y = center.y.coerceIn(margin + half, maxY.coerceAtLeast(margin + half))
    return Offset(x, y)
}

/**
 * 那一枚圆形把手（球 = 它，长条最上方也是它）：液态玻璃，按住拖动、点一下展开。
 *
 * **跟手必须用「绝对坐标」而不是差分**。这个节点自己会跟着手指移动（整条收成球跟手），
 * 如果用 `change.position - last` 累加：节点移动 Δ 后，下一帧的 `change.position` 就少了
 * Δ，于是位移被抵消一半——球只跟一半，手指很快滑出节点范围，事件中断走 `finally`，
 * 直接 `snapToSide` 把球吸到边上（就是「拖到一半脱手自动吸到旁边」）。
 *
 * 改成 `节点实测原点 + 事件相对坐标` 就对了：事件坐标永远是相对**当前**布局位置的，
 * 而 [origin] 也是当前布局位置（上一帧布局结束时刷新），两者相加就是手指的真实页面
 * 坐标，与节点自己怎么移动无关。
 */
@Composable
private fun OrbHandle(
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onTap: () -> Unit,
    /** 拖起来的瞬间：给出手指的页面局部坐标（调用方据此记抓取偏移）。 */
    onDragStart: (finger: Offset) -> Unit,
    /** 拖动中：给出**绝对**的手指页面局部坐标。 */
    onDragTo: (finger: Offset) -> Unit,
    onDrop: () -> Unit,
) {
    // 把手自己的页面局部原点：随球移动，但每帧由 onGloballyPositioned 刷新
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .size(ORB_BALL)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft - session.pageOrigin }
            .orbGlass(liquid, refraction, backdrop, CircleShape)
            .pointerInput("orbHandle") {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    // 自己判 slop，不用 awaitTouchSlopOrCancellation：后者在「变更已被别人消费
                    // 过」时直接返回 null，而这条链上还有页面根 Box 的手势层与滚动列，谁先消费
                    // 一下都可能把把手误判成点击。自己判就只看位移，不受消费标记影响。
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
                    // 没拖起来 = 点一下：球展开成长条（在 finally 之后，取消时不会走到）
                    if (!dragging) onTap()
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
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /** 停靠在页面右侧（true）/ 左侧（false）：图标列贴哪一边。 */
    dockRight: Boolean,
    onPaletteStart: (routeId: String, startPointer: Offset) -> Unit,
    onPaletteDrop: () -> Unit,
    onPaletteCancel: () -> Unit,
    /** 单击把手：把长条收回成球。 */
    onHandleTap: () -> Unit,
    onHandleDragStart: (Offset) -> Unit,
    onHandleDragTo: (Offset) -> Unit,
    onHandleDrop: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 这一端打不开的功能不进面板（faces 里本来就没有它们）
    val items = remember(catalog, faces) { catalog.filter { faces.containsKey(it.route.id) } }

    Column(
        Modifier
            .width(ORB_RAIL_WIDTH)
            // 外框：一条液态玻璃胶囊（和改造前一致）
            .orbGlass(liquid, refraction, backdrop, RoundedCornerShape(ORB_RAIL_WIDTH / 2))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 把手钉在**停靠侧**那一栏（宽度恒为 ORB_RAIL_WIDTH、内部居中）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (dockRight) Arrangement.End else Arrangement.Start,
        ) {
            Box(Modifier.width(ORB_RAIL_WIDTH), contentAlignment = Alignment.Center) {
                OrbHandle(
                    liquid = liquid,
                    refraction = refraction,
                    backdrop = backdrop,
                    session = session,
                    onTap = onHandleTap,
                    onDragStart = onHandleDragStart,
                    onDragTo = onHandleDragTo,
                    onDrop = onHandleDrop,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.height(ORB_TILE * ORB_VISIBLE_ROWS),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size) { index ->
                val service = items[index]
                val face = faces.getValue(service.route.id)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OrbTileIcon(
                        face = face,
                        liquid = liquid,
                        refraction = refraction,
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
