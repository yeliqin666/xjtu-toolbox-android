package com.xjtu.toolbox.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一格的基准高度（开着「网格下留出文字空间」时）。组件的实际高度 = 格数 ×（这个高度
 * + [HomeTileGap]）− [HomeTileGap]。
 *
 * 88dp 让 1×1 是个**高长方形**（§13）：图标在上、名称在下正好放下，而不是过去那种
 * 只塞得下图标的「胖方块」——这一档的名字就叫「在图标下留出文字空间」。
 */
internal val HomeCellHeight = 88.dp

/**
 * 关掉「网格下留出文字空间」之后的一格高度（补充 §12）。
 *
 * 单元一矮，1 格高的卡片就装不下名称与状态，自动落进纯图标形态（§13 的阈值联动）；
 * 组件的槽位是按格数存的，所以换单元高度只是换换算比例，数据不用动。
 */
internal val HomeCompactCellHeight = 46.dp

/**
 * 组件之间、以及组件与组边界之间的标准缝隙。
 *
 * 这个缝隙是**渲染时**保证的：每个块都在自己的格矩形里四面各内缩一半，
 * 所以两块在格坐标上紧挨着，画出来也一定隔着一条 [HomeTileGap]，
 * 不会出现「拖到一起挤成一条线」（需求 §3.4）。
 */
internal val HomeTileGap = 8.dp

/** 组件位置/尺寸变化的弹簧：阻尼偏低一点，才有液态玻璃那种「弹一下再停住」。 */
internal val HomeTileSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)

/** 右下角缩放把手的热区边长（§15）：贴着圆弧把手的一小块，太大容易和移动抢指针。 */
private val RESIZE_HANDLE_TOUCH = 30.dp

/** 组标题行本身的高度：固定值，组的高度因此是数据的纯函数（组排序的让位几何靠它）。 */
internal val GROUP_HEADER_ROW = 34.dp

/** 组标题行 + 它与网格之间的间距：首页算几何表用的「标题区总高」。 */
internal val GROUP_HEADER_HEIGHT = 42.dp

/**
 * 一个卡片组：标题行 + 组件网格。
 *
 * 组本身**不再是一张白卡**（需求 §2.1：取消「常用功能」的特殊样式、所有组平级），
 * 所以这里只画一行标题和一片毛玻璃组件；标题为空串时整行都不画（§2.3）。
 *
 * 渲染的输入是**预览布局**（拖拽中 = 首页实时推导的那份）：被拖块画成落点占位框、
 * 真身钉在源组起手位置画残影（手势节点因此全程存活）、其它块弹簧让位；
 * 松手把预览固化成数据，视觉上没有任何位置突变。
 */
@Composable
internal fun HomeGroupSection(
    group: HomeGroup,
    faces: Map<String, HomeServiceRow>,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    grid: Boolean,
    /** 被拖组的跟手位移（含松手后的归位补偿）；null = 这一组不在被拖。 */
    reorderLift: Float?,
    /** 组排序中这一组的让位目标（px）：被拖组抽走后它该往哪挪。 */
    reorderShift: Float,
    onOpen: (HomeServiceRow) -> Unit,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    onTileDrop: () -> Unit,
    onResizeDrop: () -> Unit,
    onReorderStart: (pointerY: Float) -> Unit,
    onReorderDrag: (pointerY: Float) -> Unit,
    onReorderDrop: () -> Unit,
    onReorderCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = HomeCellHeight,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val tiles = group.tiles
    val rows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)
    // 组高跟着预览（落点让组变高变矮、缩放推高）弹簧伸缩，下面的组平滑挪位而不是瞬移
    val animRows by animateFloatAsState(targetValue = rows, animationSpec = HomeTileSpring, label = "groupRows")

    // 组排序的让位：被拖组跟手（lift），其它组弹簧到让位目标；排序一结束立刻 snap 回 0——
    // 那一刻布局已按新顺序就位，偏移只有立刻归零，视觉位置才连续（§16）
    val shiftAnim = remember { Animatable(0f) }
    LaunchedEffect(reorderShift, reorderLift != null, session.reordering) {
        when {
            reorderLift != null -> shiftAnim.snapTo(0f)
            !session.reordering -> shiftAnim.snapTo(0f)
            else -> shiftAnim.animateTo(reorderShift, HomeTileSpring)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (reorderLift != null) 6f else 0f)
            .graphicsLayer {
                translationY = reorderLift ?: shiftAnim.value
                alpha = if (session.reorderGroupId == group.id) 0.94f else 1f
            },
    ) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                liquid = liquid,
                backdrop = backdrop,
                session = session,
                onEditGroup = onEditGroup,
                onDeleteGroup = onDeleteGroup,
                onReorderStart = onReorderStart,
                onReorderDrag = onReorderDrag,
                onReorderDrop = onReorderDrop,
                onReorderCancel = onReorderCancel,
            )
            Spacer(Modifier.height(GROUP_HEADER_HEIGHT - GROUP_HEADER_ROW))
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (animRows * (cellHeightPx + gapPx) - gapPx).toDp() }),
        ) {
            val colUnit = (constraints.maxWidth + gapPx) / HomeGridMath.COLUMNS
            val rowUnit = cellHeightPx + gapPx

            // 手指当前认定的目标组画一圈接收高亮（§19）：松手会落到哪，按下那一刻就看得见
            val receiving = session.dragging &&
                session.targetGroupId == group.id &&
                session.dragFromGroup != group.id
            if (receiving) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(
                            1.5.dp,
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
                            RoundedCornerShape(14.dp),
                        ),
                )
            }

            if (tiles.isEmpty() && editing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .squircleClip(18.dp)
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            RoundedCornerShape(18.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "这一组还是空的，从右侧悬浮条拖一个功能进来",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // 渲染每一块：被拖块在这个列表里的身份是**落点占位框**（预览位置，弹簧跟位）；
            // 它的真身（手势载体）在源组起手位置画残影，见下面那段
            tiles.forEach { tile ->
                val face = faces[tile.routeId] ?: return@forEach
                key(if (tile.id == session.dragTileId) "drop:${tile.id}" else tile.id) {
                    if (tile.id == session.dragTileId) {
                        DropSlot(tile, colUnit, rowUnit, gapPx)
                    } else {
                        HomeTileItem(
                            tile = tile,
                            face = face,
                            editing = editing,
                            liquid = liquid,
                            backdrop = backdrop,
                            session = session,
                            groupId = group.id,
                            colUnit = colUnit,
                            rowUnit = rowUnit,
                            gapPx = gapPx,
                            cellHeight = cellHeight,
                            grid = grid,
                            ghostSource = false,
                            onOpen = { onOpen(face) },
                            onStartEdit = onStartEdit,
                            onRemoveTile = onRemoveTile,
                            onTileDrop = onTileDrop,
                            onResizeDrop = onResizeDrop,
                        )
                    }
                }
            }

            // 被拖块的真身：钉在源组的起手位置画残影。它必须始终挂在**同一个父容器**里
            // （key 不变、不随预览跨组搬家），拖拽手势的 pointerInput 协程才不会被重组打断
            if (session.dragFromGroup == group.id) {
                val dragTileId = session.dragTileId
                val face = session.dragRouteId?.let { faces[it] }
                if (dragTileId != null && face != null) {
                    key(dragTileId) {
                        HomeTileItem(
                            tile = HomeTile(
                                id = dragTileId,
                                routeId = session.dragRouteId!!,
                                x = session.dragStartCell.x,
                                y = session.dragStartCell.y,
                                w = session.dragSizeCells.x,
                                h = session.dragSizeCells.y,
                            ),
                            face = face,
                            editing = editing,
                            liquid = liquid,
                            backdrop = backdrop,
                            session = session,
                            groupId = group.id,
                            colUnit = colUnit,
                            rowUnit = rowUnit,
                            gapPx = gapPx,
                            cellHeight = cellHeight,
                            grid = grid,
                            ghostSource = true,
                            onOpen = {},
                            onStartEdit = onStartEdit,
                            onRemoveTile = onRemoveTile,
                            onTileDrop = onTileDrop,
                            onResizeDrop = onResizeDrop,
                        )
                    }
                }
            }
        }
    }
}

/** 组标题行：编辑态左侧把手按住上下拖排序，**点组名**打开「分组设置」抽屉，右侧一个删除按钮。 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onReorderStart: (pointerY: Float) -> Unit,
    onReorderDrag: (pointerY: Float) -> Unit,
    onReorderDrop: () -> Unit,
    onReorderCancel: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val titleColor = CourseColors.parseHex(group.colorHex)
        ?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: ink
    // 编辑态下点组名打开「分组设置」抽屉
    val titleTap = if (editing) {
        Modifier.pointerInput(group.id) { detectTapGestures(onTap = { onEditGroup() }) }
    } else {
        Modifier
    }

    // 高度固定（GROUP_HEADER_ROW）：组的总高因此是数据的纯函数，组排序的让位几何不依赖回读
    Row(
        Modifier.fillMaxWidth().height(GROUP_HEADER_ROW).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Box(
                Modifier
                    .size(22.dp)
                    .onGloballyPositioned { coords ->
                        // 把手的页面局部顶边：绝对合成手指 y 用（滚动免疫）
                        val top = coords.boundsInRoot().top - session.pageOrigin.y
                        if (session.handleTops[group.id] != top) {
                            session.handleTops[group.id] = top
                        }
                    }
                    .pointerInput(group.id) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val top = session.handleTops[group.id] ?: return@detectDragGestures
                                onReorderStart(top + offset.y)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val top = session.handleTops[group.id] ?: return@detectDragGestures
                                onReorderDrag(top + change.position.y)
                            },
                            onDragEnd = { onReorderDrop() },
                            onDragCancel = { onReorderCancel() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.DragHandle, contentDescription = "拖动排序", tint = muted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        if (group.title.isNotBlank()) {
            Text(
                group.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).then(titleTap),
            )
        } else if (editing) {
            Text(
                "未命名分组",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = muted.copy(alpha = 0.6f),
                modifier = titleTap,
            )
        }
        Spacer(Modifier.weight(1f))
        if (editing) {
            GroupDeleteButton(liquid = liquid, backdrop = backdrop, onClick = onDeleteGroup)
        }
    }
}

/** 组标题行右侧的删除按钮：液态玻璃小圆钮（§2.4）。 */
@Composable
private fun GroupDeleteButton(liquid: Boolean, backdrop: Backdrop?, onClick: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    val material = if (liquid && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                padding = maxOf(padding, 8.dp.toPx())
                vibrancy()
                blur(6.dp.toPx())
                lens(6.dp.toPx(), 8.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = 0.6f) },
            shadow = null,
            onDrawSurface = { drawRect(fill) },
        )
    } else {
        Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    }
    Box(
        Modifier
            .size(30.dp)
            .then(material)
            .pointerInput("deleteGroup") { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "删除这一组",
            tint = MiuixTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 落点占位框：被拖块在**预览布局**里的位置与尺寸，弹簧跟位。
 * 「松手会落在哪」在拖动全程都看得见；松手时幽灵块飞过来与它重合，预览固化成数据。
 */
@Composable
private fun DropSlot(tile: HomeTile, colUnit: Float, rowUnit: Float, gapPx: Float) {
    val density = LocalDensity.current
    val x by animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileSpring, label = "dropX")
    val y by animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileSpring, label = "dropY")
    val w by animateFloatAsState(targetValue = tile.w, animationSpec = HomeTileSpring, label = "dropW")
    val h by animateFloatAsState(targetValue = tile.h, animationSpec = HomeTileSpring, label = "dropH")
    val widthPx = (w * colUnit - gapPx).coerceAtLeast(1f)
    val heightPx = (h * rowUnit - gapPx).coerceAtLeast(1f)
    val radius = (minOf(w, h) * 12f).coerceIn(10f, 22f).dp
    val accent = MiuixTheme.colorScheme.primary
    Box(
        Modifier
            .offset { IntOffset((x * colUnit).roundToInt(), (y * rowUnit).roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(radius))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(radius)),
    )
}

/**
 * 一块组件。位置由格矩形算出，**位置变化用弹簧动画**（§6：挤开、重排都要 Q 弹）；
 * 被拖起的那一块钉在起手位置画成残影（[ghostSource]），跟手的是首页那一层幽灵块。
 */
@Composable
private fun HomeTileItem(
    tile: HomeTile,
    face: HomeServiceRow,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    groupId: String,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    cellHeight: Dp,
    grid: Boolean,
    /** 残影模式：钉在起手格、淡显、不画编辑角标；手势照常挂（拖拽全程由它收指针）。 */
    ghostSource: Boolean,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    onTileDrop: () -> Unit,
    onResizeDrop: () -> Unit,
) {
    val density = LocalDensity.current

    // pointerInput 的 lambda 只在 key 变化时重启，闭包会捕获旧的 tile——
    // 经 rememberUpdatedState 读的才是这次组合的最新数据（比如缩放固化后的新宽高）
    val currentTile = rememberUpdatedState(tile)

    // 位置：残影钉在起手的数据格（节点静止，手势的绝对合成坐标才不漂）；其它块跟数据弹簧
    val cellX = if (ghostSource) session.dragStartCell.x else tile.x
    val cellY = if (ghostSource) session.dragStartCell.y else tile.y
    val animX = remember { Animatable(cellX) }
    val animY = remember { Animatable(cellY) }
    LaunchedEffect(cellX, cellY, ghostSource) {
        if (ghostSource) {
            animX.snapTo(cellX)
            animY.snapTo(cellY)
        } else {
            launch { animX.animateTo(cellX, HomeTileSpring) }
            launch { animY.animateTo(cellY, HomeTileSpring) }
        }
    }

    // 尺寸：被缩那块跟手（snap，不做动画），其余尺寸变化走弹簧
    val live = session.resizeCells.takeIf { session.resizingId == tile.id }
    val animW by animateFloatAsState(
        targetValue = live?.x ?: tile.w,
        animationSpec = if (live != null) snap() else HomeTileSpring,
        label = "tileW",
    )
    val animH by animateFloatAsState(
        targetValue = live?.y ?: tile.h,
        animationSpec = if (live != null) snap() else HomeTileSpring,
        label = "tileH",
    )

    val widthPx = (animW * colUnit - gapPx).coerceAtLeast(1f)
    val heightPx = (animH * rowUnit - gapPx).coerceAtLeast(1f)

    // 移动 / 缩放共用一层 detectDragGestures：按下位置落在右下角把手热区就是缩放，否则移动。
    // 手指位置 = 节点登记的页面局部位置 + 事件的节点相对坐标（**绝对合成**）：页面滚动、
    // 其它块的让位动画都污染不了它。onDragCancel 兜底清会话——手势被系统抢走也不会
    // 把编辑态卡死（旧实现的手写事件循环就死在这一点上）。
    val gesture = when {
        editing -> Modifier.pointerInput(tile.id, groupId, colUnit, rowUnit, grid) {
            detectDragGestures(
                onDragStart = { offset ->
                    val top = session.tileTops[tile.id] ?: return@detectDragGestures
                    val startPointer = top + offset
                    val handlePx = RESIZE_HANDLE_TOUCH.toPx()
                    if (!ghostSource && offset.x >= size.width - handlePx && offset.y >= size.height - handlePx) {
                        val t = currentTile.value
                        session.beginResize(t.id, Offset(t.w, t.h), top, startPointer, colUnit, rowUnit)
                    } else if (!ghostSource) {
                        session.beginTileDrag(currentTile.value, groupId, top, startPointer)
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val top = session.tileTops[tile.id] ?: return@detectDragGestures
                    val now = top + change.position
                    if (session.resizingId == tile.id) {
                        session.updateResize(now, top, colUnit, rowUnit, grid)
                    } else if (session.dragTileId == tile.id) {
                        session.pointer = now
                    }
                },
                onDragEnd = {
                    when {
                        session.resizingId == tile.id -> onResizeDrop()
                        session.dragTileId == tile.id -> onTileDrop()
                    }
                },
                onDragCancel = { session.abort() },
            )
        }
        else -> Modifier.pointerInput(tile.id) {
            detectTapGestures(
                onTap = { onOpen() },
                onLongPress = { onStartEdit() },
            )
        }
    }

    Box(
        Modifier
            .offset { IntOffset((animX.value * colUnit).roundToInt(), (animY.value * rowUnit).roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .onGloballyPositioned { coords ->
                // 这一块的页面局部左上角：绝对合成手指位置的节点基准。
                // 手势与登记都在 graphicsLayer 之前：回读的 bounds 不带 alpha/缩放变换
                val topLeft = coords.boundsInRoot().topLeft - session.pageOrigin
                if (session.tileTops[tile.id] != topLeft) session.tileTops[tile.id] = topLeft
            }
            .then(gesture)
            .graphicsLayer {
                alpha = if (ghostSource) 0.30f else 1f
            },
    ) {
        HomeTileFaceBox(
            face = face,
            w = animW,
            h = animH,
            liquid = liquid,
            backdrop = backdrop,
            cellHeight = cellHeight,
            modifier = Modifier.fillMaxSize(),
        )

        if (editing && !ghostSource) {
            // 左上角：移除这一块。热区 32dp，视觉只有 18dp，所以点偏一点也点得到
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(32.dp)
                    .pointerInput(tile.id) { detectTapGestures(onTap = { onRemoveTile(tile.id) }) },
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除${face.title}",
                        tint = MiuixTheme.colorScheme.surface,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            // 右下角：缩放把手的**视觉**（§15）——把右下角那一小段圆弧染成主题色当把手。
            // 热区在外层手势里按按下位置分流，这里只画记号、不接手势
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(RESIZE_HANDLE_TOUCH),
                contentAlignment = Alignment.Center,
            ) {
                val gripColor = MiuixTheme.colorScheme.primary
                Canvas(Modifier.size(20.dp)) {
                    // 以中心为圆心扫过 0°→90°（正右 → 正下），正好是右下角那段弧
                    drawArc(
                        color = gripColor,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * 组件的材质与内容：尺寸决定排版（§3.3），材质由「主页卡片液态玻璃」开关决定（§5.2）。
 *
 * 液态玻璃直接复用 App 里已有的那套 `drawBackdrop`（底栏、玻璃顶栏用的同一个），
 * 采样源是首页自己那一层背景，所以透出来的是壁纸而不是组件自己（会成环）。
 */
@Composable
internal fun HomeTileFaceBox(
    face: HomeServiceRow,
    w: Float,
    h: Float,
    liquid: Boolean,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    /** 一格的实际高度：决定 1 格高的卡片够不够装「图标 + 名称」（§13）。 */
    cellHeight: Dp = HomeCellHeight,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    // 小块的圆角要跟着缩，否则 1×1 看着像个大圆饼
    val radius = (minOf(w, h) * 12f).coerceIn(10f, 22f).dp
    val fill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f)
    val iconColor = if (dark) lerp(face.color, Color.White, 0.4f) else face.color
    // 液态玻璃的填充要比毛玻璃更透：折射与高光是它的主角，填浓了就只剩一张白卡
    val liquidFill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    // 和底栏胶囊同一套环境光阴影（remember：shadow lambda 捕获的对象变了会触发重采样）
    val accent = MiuixTheme.colorScheme.primary
    val liquidShadow = remember(accent, dark) { floatingGlassShadow(accent, dark) }

    val material = when {
        liquid && backdrop != null -> remember(backdrop, radius, liquidFill, liquidShadow) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(radius) },
                effects = {
                    // 采样范围往外扩一圈，折射与模糊在轮廓边上也有真实内容可混、不变弱
                    // （和底栏胶囊、屁岱气泡同一手法）
                    padding = maxOf(padding, 12.dp.toPx())
                    vibrancy()
                    blur(8.dp.toPx())
                    // 折射才是「液态」和「毛玻璃」的分界：边缘把背景扭出透镜的弯光，
                    // 带一点色散。带宽与位移用固定的物理宽度（和底栏一致），不随卡片
                    // 缩放——大小卡片的玻璃厚度本来就一样
                    lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = true)
                },
                // 玻璃被光打亮的那圈轮廓
                highlight = { Highlight.Default.copy(alpha = 0.65f) },
                // 卡片浮在壁纸上，该有一圈柔和的环境光投影
                shadow = { liquidShadow },
                onDrawSurface = { drawRect(liquidFill) },
            )
        }
        else -> Modifier
            .then(if (dark) Modifier else Modifier.appCardShadow(shape = RoundedCornerShape(radius)))
            .squircleClip(radius)
            .background(fill)
            .squircleBorder(0.7.dp, hairline, radius)
    }

    Box(
        modifier.then(material).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 形态按**实时**格数选（§10）：拖动把手缩放时逐帧判断，不等松手才重排。
        // 高单元格（「网格下留出文字空间」）时 1 格就装得下「图标 + 名称」，1×1 也竖排；
        // 矮单元格（紧凑）时 1 格只剩图标。明显宽扁的块横排，其余竖排（§13 的阈值联动）。
        val tallCell = cellHeight >= 76.dp
        val mode = when {
            (w < 1.2f || h < 1.2f) && !tallCell -> 0
            w > h * 1.5f && h < 1.6f -> 1
            else -> 2
        }
        Crossfade(targetState = mode, animationSpec = tween(140)) { shape ->
            when (shape) {
                // 极值形态：纯图标
                0 -> ExpressiveIcon(
                    icon = face.icon,
                    color = iconColor,
                    size = (minOf(w, h) * 30f).coerceIn(24f, 34f).dp,
                    iconSize = (minOf(w, h) * 16f).coerceIn(13f, 19f).dp,
                )
                // 长条形：图标在左、字在右
                1 -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ExpressiveIcon(icon = face.icon, color = iconColor, size = 30.dp, iconSize = 17.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            face.title,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatLine(face, muted, centered = false)
                    }
                }
                // 方块及以上：图标一行、名称与状态各一行
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ExpressiveIcon(
                        icon = face.icon,
                        color = iconColor,
                        size = (minOf(w, h) * 25f).coerceIn(30f, 40f).dp,
                        iconSize = (minOf(w, h) * 14f).coerceIn(17f, 22f).dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        face.title,
                        fontSize = 12.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    StatLine(face, muted, centered = true)
                }
            }
        }
    }
}

/** 组件上那一行小字：优先状态，没有状态就是功能说明（§3.3 大尺寸展示更多信息）。 */
@Composable
private fun StatLine(face: HomeServiceRow, muted: Color, centered: Boolean) {
    val text = face.stat ?: face.subtitle.takeIf { it.isNotBlank() } ?: return
    val color = when {
        face.stat == null || face.tone == HomeTone.MUTED -> muted
        face.tone == HomeTone.ALERT -> MiuixTheme.colorScheme.error
        face.tone == HomeTone.ACCENT -> face.color
        else -> muted
    }
    Spacer(Modifier.height(2.dp))
    Text(
        text,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
}
