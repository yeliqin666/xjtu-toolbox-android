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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
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
 * 一格的基准高度。组件的实际高度 = 格数 ×（这个高度 + [HomeTileGap]）− [HomeTileGap]；
 * 1 格高就是「纯图标模式」那一档，62dp 放得下一个 30dp 的图标。
 */
internal val HomeCellHeight = 62.dp

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

/** 一个卡片组渲染出来的位置与单位换算（**页面局部坐标**），拖拽落点用它把手指坐标换回格坐标。 */
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
    fun toCellX(pagePx: Float): Float = (pagePx - left) / colUnit
    fun toCellY(pagePx: Float): Float = (pagePx - top) / rowUnit

    fun contains(point: Offset): Boolean =
        point.x >= left && point.x <= left + width && point.y >= top && point.y <= top + height
}

/**
 * 编辑态的拖拽 / 缩放 / 组排序会话。
 *
 * **坐标全程只用一套——页面根节点的局部坐标**：每个块、每个组的 `boundsInRoot` 都先减去
 * [pageOrigin] 再存，手指位置也在同一套里。这样幽灵块、落点、跟手、滚动之间不用做任何
 * 坐标系换算，也就不会「错开一像素就整体跳走」。手势只往这里写「手指在哪」，落点由首页算。
 */
@Stable
internal class HomeDragSession {

    /** 正在拖动的那一块；null = 没在拖。 */
    var tileId by mutableStateOf<String?>(null)
    var routeId by mutableStateOf<String?>(null)
    var fromGroupId by mutableStateOf<String?>(null)

    /** 被拖这一块的尺寸（格），画幽灵块用。 */
    var sizeCells by mutableStateOf(Offset(2f, 1f))

    /** 手指当前位置（页面局部坐标）：判定落进哪个组、边缘自动滚动都按它算。 */
    var pointer by mutableStateOf(Offset.Zero)

    /** 按下时手指落在块内的位置（像素）：松手时从 pointer 减掉它，得到块左上角那一格。 */
    var grab by mutableStateOf(Offset.Zero)

    /**
     * 幽灵块左上角（页面局部坐标）。起手 = 这一块**真被画在哪**（[tileOrigins] 登记的视觉
     * 位置），之后只累加手指位移——不靠「组坐标 + 格数」反算，那个式子依赖多套坐标系对齐，
     * 错开一点就整块跳走（§14）。
     */
    var ghostOrigin by mutableStateOf(Offset.Zero)

    /** 页面根节点在窗口里的位置（`boundsInRoot`）：所有 `boundsInRoot` 先减它再存，统一坐标。 */
    var pageOrigin by mutableStateOf(Offset.Zero)

    /** 每一块**视觉上**的左上角（页面局部坐标）：`onGloballyPositioned` 逐块登记。 */
    val tileOrigins = mutableStateMapOf<String, Offset>()

    /** 每个卡片组网格的位置与换算（页面局部坐标）；各自在 `onGloballyPositioned` 里登记。 */
    val boards = mutableStateMapOf<String, HomeBoardMetrics>()

    /** 正在缩放的那一块与它的实时尺寸：跟手反馈，松手才落盘。 */
    var resizingId by mutableStateOf<String?>(null)
    var liveSize by mutableStateOf<Offset?>(null)

    /** 正在拖动排序的那一组。 */
    var reorderGroupId by mutableStateOf<String?>(null)
        private set

    /** 手指当前的页面局部 y：跟手与交换判定都按它算。 */
    var reorderY by mutableStateOf(0f)
        private set

    /** 起手那一刻手指的页面局部 y。 */
    var reorderStartY by mutableStateOf(0f)
        private set

    /** 实时交换已累计的布局位移：被拖组每换过一组，它的布局位置就跳过那一组的高度。 */
    var reorderShiftAcc by mutableStateOf(0f)
        private set

    /** 把手自己的顶边（页面局部坐标）：`onDragStart` 的 down 是相对把手的，要换成页面局部。 */
    val reorderHandleTops = mutableStateMapOf<String, Float>()

    /** 每一整组（标题行 + 网格）的顶边与高度（页面局部坐标）：组排序交换邻居时用。 */
    val groupTops = mutableStateMapOf<String, Float>()
    val groupHeights = mutableStateMapOf<String, Float>()

    val dragging: Boolean get() = tileId != null
    val reordering: Boolean get() = reorderGroupId != null

    /**
     * 被拖那一组相对其「当前布局位置」的跟手位移：纯手指位移（[reorderY] − [reorderStartY]）
     * 扣掉实时交换已经吃掉的那几段布局高度（[reorderShiftAcc]），这样交换瞬间视觉不跳（§16）。
     */
    val reorderShift: Float get() = reorderY - reorderStartY - reorderShiftAcc

    fun beginDrag(tile: HomeTile, groupId: String, board: HomeBoardMetrics, local: Offset) {
        tileId = tile.id
        routeId = tile.routeId
        fromGroupId = groupId
        sizeCells = Offset(tile.w, tile.h)
        grab = local
        // 起手位置优先用这一块的视觉位置（tileOrigins，含滚动 / 动画 / 壳的偏移）；
        // 还没登记到（第一帧就按住）才退回「组坐标 + 格数」反算
        ghostOrigin = tileOrigins[tile.id] ?: Offset(
            board.left + tile.x * board.colUnit,
            board.top + tile.y * board.rowUnit,
        )
        pointer = ghostOrigin + local
    }

    fun dragBy(delta: Offset) {
        pointer += delta
        ghostOrigin += delta
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

    fun beginReorder(groupId: String, pointerPageY: Float) {
        reorderGroupId = groupId
        reorderStartY = pointerPageY
        reorderY = pointerPageY
        reorderShiftAcc = 0f
    }

    fun reorderBy(dy: Float) {
        reorderY += dy
    }

    /** 被拖组实时换过一组：把那一组的高度（含组间距）记进已交换的位移里。 */
    fun absorbReorderShift(groupHeightWithGap: Float) {
        reorderShiftAcc += groupHeightWithGap
    }

    fun endReorder() {
        reorderGroupId = null
        reorderShiftAcc = 0f
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
    onDropDrag: () -> Unit,
    onDropResize: (tileId: String, w: Float, h: Float) -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    onDeleteGroup: () -> Unit,
    onReorderMove: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = HomeCellHeight,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val tiles = group.tiles
    val rows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)

    // 被拖排序的这一组跟手浮起（§16）：只动绘制层，实时交换由首页在拖动中做。
    // 跟手期间它的 bounds 会带上这段位移，但交换判定只看**其它组**（它们不动），不受影响
    val reordering = session.reorderGroupId == group.id
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                // 整组（标题行 + 网格）的顶边与高度，组排序交换邻居时用
                val top = coords.boundsInRoot().top - session.pageOrigin.y
                val h = coords.size.height.toFloat()
                if (session.groupTops[group.id] != top) session.groupTops[group.id] = top
                if (session.groupHeights[group.id] != h) session.groupHeights[group.id] = h
            }
            .zIndex(if (reordering) 6f else 0f)
            .graphicsLayer {
                translationY = if (reordering) session.reorderShift else 0f
                alpha = if (reordering) 0.94f else 1f
            },
    ) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                liquid = liquid,
                backdrop = backdrop,
                session = session,
                onStartEdit = onStartEdit,
                onEditGroup = onEditGroup,
                onDeleteGroup = onDeleteGroup,
                onReorderMove = onReorderMove,
            )
            Spacer(Modifier.height(8.dp))
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (rows * (cellHeightPx + gapPx) - gapPx).toDp() })
                .onGloballyPositioned { coords ->
                    // 统一页面局部坐标：先减 pageOrigin 再存
                    val topLeft = coords.boundsInRoot().topLeft - session.pageOrigin
                    val metrics = HomeBoardMetrics(
                        left = topLeft.x,
                        top = topLeft.y,
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

            // 跨组拖动时，手指正压着的那一组给一圈接收高亮（§19）：
            // 只有「别的组」才亮，这样「松手会落到哪」在按下那一刻就能看出来
            val receiving = session.dragging &&
                session.fromGroupId != null &&
                session.fromGroupId != group.id &&
                session.boards[group.id]?.contains(session.pointer) == true
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

            if (tiles.isEmpty()) {
                if (editing) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(cellHeight)
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

/** 组标题行：编辑态左侧按住排序，**点组名**打开「分组设置」抽屉，右侧一个删除按钮。 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onReorderMove: () -> Unit,
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

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Box(
                Modifier
                    .size(22.dp)
                    .onGloballyPositioned { coords ->
                        val top = coords.boundsInRoot().top - session.pageOrigin.y
                        if (session.reorderHandleTops[group.id] != top) {
                            session.reorderHandleTops[group.id] = top
                        }
                    }
                    .pointerInput(group.id) {
                        detectDragGestures(
                            onDragStart = { down ->
                                onStartEdit()
                                // down 是相对这枚把手的坐标，要换成页面局部才是手指的真实位置
                                val top = session.reorderHandleTops[group.id] ?: return@detectDragGestures
                                session.beginReorder(group.id, top + down.y)
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                session.reorderBy(delta.y)
                                // 实时交换：手指越过相邻组中线就立刻换序，被拖组始终堆叠不留空
                                onReorderMove()
                            },
                            // 顺序在拖动中已实时换好，松手只结束会话
                            onDragEnd = { session.endReorder() },
                            onDragCancel = { session.endReorder() },
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

    // 移动 / 缩放共用一个手势层，挂在 graphicsLayer **之前**（lifted 的缩小画在
    // graphicsLayer 里）：手势拿到的位移才不会被视觉变换反向放大。按下时按「是否落在
    // 右下角把手热区」分流成缩放 / 移动；drag 循环自己写、不依赖 drag() 的返回值——
    // 只要手指正常抬起就一定落位，不会因为「手势被判成取消」而把改动丢掉（§14/§15）。
    val gesture = when {
        editing -> Modifier.pointerInput(tile.id, groupId, colUnit, rowUnit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 落在右下角把手热区里，这次手势就是缩放；否则是移动（§15）
                val handlePx = RESIZE_HANDLE_TOUCH.toPx()
                val resize = down.position.x >= size.width - handlePx &&
                    down.position.y >= size.height - handlePx
                val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (dragStart == null) return@awaitEachGesture
                if (resize) {
                    session.beginResize(tile.id, Offset(tile.w, tile.h))
                } else {
                    val board = session.boards[groupId]
                    if (board == null) return@awaitEachGesture
                    // 抓取点用「越过触摸斜坡之后」的位置（dragStart），不是按下点：
                    // 斜坡那一段手指已经挪开了一截，用按下点会让幽灵块全程落后一个斜坡
                    session.beginDrag(tile, groupId, board, dragStart.position)
                }
                // 手动 drag 循环：逐帧累加位移，跟到抬起（落位）或取消（丢弃）为止
                var dropped = false
                var last = dragStart.position
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == dragStart.id } ?: break
                    if (!change.pressed) {
                        dropped = true
                        break
                    }
                    val delta = change.position - last
                    last = change.position
                    if (delta != Offset.Zero) {
                        if (resize) {
                            val current = session.liveSize ?: Offset(tile.w, tile.h)
                            session.liveSize = Offset(
                                (current.x + delta.x / colUnit)
                                    .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                                (current.y + delta.y / rowUnit)
                                    .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                            )
                        } else {
                            session.dragBy(delta)
                        }
                    }
                    change.consume()
                }
                if (resize) {
                    val size = session.liveSize ?: Offset(tile.w, tile.h)
                    session.endResize()
                    if (dropped) onDropResize(tile.id, size.x, size.y)
                } else {
                    if (dropped) onDropDrag()
                    session.endDrag()
                }
            }
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
                // 这一块的视觉左上角（页面局部坐标）：幽灵块起手就压在它上面，
                // 不靠「组坐标 + 格数」反算，所以不会和真块错开（§14）
                val topLeft = coords.boundsInRoot().topLeft - session.pageOrigin
                if (session.tileOrigins[tile.id] != topLeft) session.tileOrigins[tile.id] = topLeft
            }
            .then(gesture)
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
            modifier = Modifier.fillMaxSize(),
        )

        if (editing) {
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
            // 热区在外层那一层手势里按右下角判定，这里只画记号、不接手势——把手的视觉跟着
            // 右下角动，手势坐标却始终锚在不动的外层上（见上面 gesture 的说明）
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
        // 阈值用内容模型推：1 格高只放得下图标 → 纯图标；1.6 格以下竖排会挤成两行字 →
        // 改成横排；再高才回到「图标 / 名称 / 状态」三段。
        val mode = when {
            w < 1.2f || h < 1.2f -> 0
            h < 1.6f -> 1
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
