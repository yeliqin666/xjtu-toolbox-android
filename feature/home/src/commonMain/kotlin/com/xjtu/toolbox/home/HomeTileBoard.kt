package com.xjtu.toolbox.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一格的基准高度。组件的实际高度 = 格数 ×（这个高度 + [HomeTileGap]）− [HomeTileGap]；
 * 1 格高就是「纯图标模式」那一档，62dp 放得下一个 30dp 的图标。
 */
internal val HomeCellHeight = 62.dp

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

/** 一个卡片组渲染出来的位置与单位换算，拖拽落点要用它把手指坐标换回格坐标。 */
internal data class HomeBoardMetrics(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 一格的宽（像素）：`(组宽 + 缝隙) / 列数`。 */
    val colUnit: Float,
    /** 一格的高（像素）：`基准高度 + 缝隙`。 */
    val rowUnit: Float,
) {
    fun toCellX(rootPx: Float): Float = (rootPx - left) / colUnit
    fun toCellY(rootPx: Float): Float = (rootPx - top) / rowUnit

    fun contains(point: Offset): Boolean =
        point.x >= left && point.x <= left + width && point.y >= top && point.y <= top + height
}

/**
 * 编辑态的拖拽 / 缩放 / 组排序会话。
 *
 * 手势只往这里写「手指在哪」，落点由首页统一算：只有首页同时握着所有组的位置，
 * 才知道这一块是不是要换组、以及被挤开的块该往本组哪个空位放。
 */
@Stable
internal class HomeDragSession {

    /** 正在拖动的那一块；null = 没在拖。 */
    var tileId by mutableStateOf<String?>(null)
    var routeId by mutableStateOf<String?>(null)
    var fromGroupId by mutableStateOf<String?>(null)

    /** 被拖这一块的尺寸（格），画幽灵块用。 */
    var sizeCells by mutableStateOf(Offset(2f, 1f))

    /** 手指当前在根坐标里的位置。 */
    var pointer by mutableStateOf(Offset.Zero)

    /** 按下时手指落在块内的位置（像素）：幽灵块按它跟手，不然会跳一下。 */
    var grab by mutableStateOf(Offset.Zero)

    /** 正在缩放的块与它的实时尺寸：跟手反馈，松手才落盘。 */
    var resizingId by mutableStateOf<String?>(null)
    var liveSize by mutableStateOf<Offset?>(null)

    /** 正在拖动排序的那一组，以及手指在根坐标里的纵向位置。 */
    var reorderGroupId by mutableStateOf<String?>(null)
    var reorderY by mutableStateOf(0f)

    /** 每个卡片组在根坐标里的位置；各自在 `onGloballyPositioned` 里登记。 */
    val boards = mutableStateMapOf<String, HomeBoardMetrics>()

    val dragging: Boolean get() = tileId != null

    fun beginDrag(tile: HomeTile, groupId: String, board: HomeBoardMetrics, local: Offset) {
        tileId = tile.id
        routeId = tile.routeId
        fromGroupId = groupId
        sizeCells = Offset(tile.w, tile.h)
        grab = local
        pointer = Offset(board.left + tile.x * board.colUnit + local.x, board.top + tile.y * board.rowUnit + local.y)
    }

    fun dragBy(delta: Offset) {
        pointer += delta
    }

    fun endDrag() {
        tileId = null
        routeId = null
        fromGroupId = null
    }

    fun beginResize(tileId: String, size: Offset) {
        resizingId = tileId
        liveSize = size
    }

    fun endResize() {
        resizingId = null
        liveSize = null
    }
}

/**
 * 一个卡片组：标题行 + 组件网格。
 *
 * 组本身**不再是一张白卡**（需求 §2.1：取消「常用功能」的特殊样式、所有组平级），
 * 所以这里只画一行标题和一片毛玻璃组件；标题为空串时整行都不画（§2.3）。
 * 拖动、缩放、挤开这些「改数据」的活儿全交给 [HomeScreen]，这里只负责画和收手势。
 */
@Composable
internal fun HomeGroupSection(
    group: HomeGroup,
    faces: Map<String, HomeServiceRow>,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onOpen: (HomeServiceRow) -> Unit,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onAddTile: () -> Unit,
    onDropDrag: () -> Unit,
    onDropResize: (tileId: String, w: Float, h: Float) -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    onReorderDrag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val cellHeightPx = with(density) { HomeCellHeight.toPx() }
    val tiles = group.tiles
    val rows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)

    Column(modifier.fillMaxWidth()) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                session = session,
                onStartEdit = onStartEdit,
                onEditGroup = onEditGroup,
                onAddTile = onAddTile,
                onReorderDrag = onReorderDrag,
            )
            Spacer(Modifier.height(8.dp))
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (rows * (cellHeightPx + gapPx) - gapPx).toDp() })
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInRoot()
                    // 宽度取布局宽度而不是 bounds：壳会把整页上移（contentTopShift），
                    // 但横向不会变，纵向用 bounds.top 就够了。
                    val metrics = HomeBoardMetrics(
                        left = bounds.left,
                        top = bounds.top,
                        width = coords.size.width.toFloat(),
                        height = coords.size.height.toFloat(),
                        colUnit = (coords.size.width + gapPx) / HomeGridMath.COLUMNS,
                        rowUnit = cellHeightPx + gapPx,
                    )
                    // 值没变就别写：这是 StateMap，每次都写会让读它的地方（幽灵块）反复重组
                    if (session.boards[group.id] != metrics) session.boards[group.id] = metrics
                },
        ) {
            val colUnit = (constraints.maxWidth + gapPx) / HomeGridMath.COLUMNS
            val rowUnit = cellHeightPx + gapPx

            if (tiles.isEmpty()) {
                if (editing) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(HomeCellHeight)
                            .squircleClip(18.dp)
                            .border(
                                1.dp,
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(18.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "这一组还是空的，点上面的 ＋ 放一个功能进来",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            tiles.forEach { tile ->
                val face = faces[tile.routeId] ?: return@forEach
                key(tile.id) {
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
                        onOpen = { onOpen(face) },
                        onStartEdit = onStartEdit,
                        onDropDrag = onDropDrag,
                        onDropResize = onDropResize,
                        onRemoveTile = onRemoveTile,
                    )
                }
            }
        }
    }
}

/** 组标题行：编辑态可按住排序（拖手柄那一段），右侧是「改组名/颜色」「加功能」。 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    session: HomeDragSession,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onAddTile: () -> Unit,
    onReorderDrag: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val titleColor = CourseColors.parseHex(group.colorHex)
        ?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: ink
    var grabbed by remember(group.id) { mutableStateOf(0f) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Box(
                Modifier
                    .size(22.dp)
                    .pointerInput(group.id) {
                        detectDragGestures(
                            onDragStart = { down ->
                                onStartEdit()
                                grabbed = session.boards[group.id]?.let { it.top + down.y } ?: 0f
                                session.reorderGroupId = group.id
                                session.reorderY = grabbed
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                grabbed += delta.y
                                session.reorderY = grabbed
                                onReorderDrag()
                            },
                            onDragEnd = { session.reorderGroupId = null },
                            onDragCancel = { session.reorderGroupId = null },
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else if (editing) {
            Text(
                "未命名分组",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = muted.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.weight(1f))
        if (editing) {
            HeaderButton(Icons.Default.MoreHoriz, "改组名与颜色", onEditGroup)
            Spacer(Modifier.width(4.dp))
            HeaderButton(Icons.Default.Add, "往这一组添加功能", onAddTile)
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(17.dp))
    }
}

/**
 * 一块组件。位置由它的格矩形算出，**位置变化用弹簧动画**（§6：挤开、重排都要 Q 弹）；
 * 拖拽中的那一块原地淡下去，跟手的是首页那一层幽灵块。
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
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onDropDrag: () -> Unit,
    onDropResize: (tileId: String, w: Float, h: Float) -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
) {
    val density = LocalDensity.current

    // 缩放中的那一块跟手（不做动画），其余尺寸变化走弹簧
    val live = session.liveSize?.takeIf { session.resizingId == tile.id }
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
    // 位置：挤开、重排之后弹过去
    val animX = remember { Animatable(tile.x) }
    val animY = remember { Animatable(tile.y) }
    // 弹簧在 animateTo 里给（Animatable 的构造器只收 visibilityThreshold，不收 spec）
    LaunchedEffect(tile.x, tile.y) {
        launch { animX.animateTo(tile.x, HomeTileSpring) }
        launch { animY.animateTo(tile.y, HomeTileSpring) }
    }

    val widthPx = (animW * colUnit - gapPx).coerceAtLeast(1f)
    val heightPx = (animH * rowUnit - gapPx).coerceAtLeast(1f)
    val lifted = session.tileId == tile.id

    Box(
        Modifier
            .offset { IntOffset((animX.value * colUnit).roundToInt(), (animY.value * rowUnit).roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .graphicsLayer {
                // 被搬走的那一块淡下去，位置留给松手之后的挤开结果
                alpha = if (lifted) 0.28f else 1f
                scaleX = if (lifted) 0.94f else 1f
                scaleY = scaleX
            },
    ) {
        HomeTileFaceBox(
            face = face,
            w = animW,
            h = animH,
            liquid = liquid,
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (editing) {
                        Modifier.pointerInput(tile.id, groupId, colUnit, rowUnit) {
                            detectDragGestures(
                                onDragStart = { down ->
                                    val board = session.boards[groupId]
                                    if (board != null) session.beginDrag(tile, groupId, board, down)
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    session.dragBy(delta)
                                },
                                onDragEnd = {
                                    onDropDrag()
                                    session.endDrag()
                                },
                                onDragCancel = { session.endDrag() },
                            )
                        }
                    } else {
                        Modifier.pointerInput(tile.id) {
                            detectTapGestures(
                                onTap = { onOpen() },
                                onLongPress = { onStartEdit() },
                            )
                        }
                    },
                ),
        )

        if (editing) {
            // 右上角：移除这一块
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    .pointerInput(tile.id) { detectTapGestures(onTap = { onRemoveTile(tile.id) }) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除${face.title}",
                    tint = MiuixTheme.colorScheme.surface,
                    modifier = Modifier.size(11.dp),
                )
            }
            // 右下角：缩放把手。1×1 那一档放不下把手，先放大再缩回去
            if (animW >= 1.4f && animH >= 1.4f) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .pointerInput(tile.id, colUnit, rowUnit) {
                            detectDragGestures(
                                onDragStart = { session.beginResize(tile.id, Offset(tile.w, tile.h)) },
                                onDrag = { change, delta ->
                                    change.consume()
                                    val current = session.liveSize ?: Offset(tile.w, tile.h)
                                    session.liveSize = Offset(
                                        (current.x + delta.x / colUnit)
                                            .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                                        (current.y + delta.y / rowUnit)
                                            .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                                    )
                                },
                                onDragEnd = {
                                    val size = session.liveSize ?: Offset(tile.w, tile.h)
                                    session.endResize()
                                    onDropResize(tile.id, size.x, size.y)
                                },
                                onDragCancel = { session.endResize() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)),
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
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    // 小块的圆角要跟着缩，否则 1×1 看着像个大圆饼
    val radius = (minOf(w, h) * 12f).coerceIn(10f, 22f).dp
    val fill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f)
    val iconColor = if (dark) lerp(face.color, Color.White, 0.4f) else face.color

    val material = when {
        liquid && backdrop != null -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(radius) },
            effects = {
                blur(8.dp.toPx())
                vibrancy()
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(fill) },
        )
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
        when {
            // 极值形态：纯图标
            w < 1.2f || h < 1.2f -> ExpressiveIcon(
                icon = face.icon,
                color = iconColor,
                size = (minOf(w, h) * 30f).coerceIn(24f, 34f).dp,
                iconSize = (minOf(w, h) * 16f).coerceIn(13f, 19f).dp,
            )
            // 长条形：图标在左、字在右
            h < 1.6f -> Row(verticalAlignment = Alignment.CenterVertically) {
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
