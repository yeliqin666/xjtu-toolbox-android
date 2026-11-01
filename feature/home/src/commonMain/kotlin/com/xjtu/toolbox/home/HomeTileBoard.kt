package com.xjtu.toolbox.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
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

/**
 * 编辑态抖动（§18）：跟着 App 里其它「可整理」的场景走，幅度小、周期长。
 * 2.4s 一个来回、±0.9° 看着像在呼吸；再快就变成焦躁，再大就影响阅读。
 */
private const val TILE_SHAKE_PERIOD_MS = 2400
private const val TILE_SHAKE_DEG = 0.9f

/** 右下角缩放把手的热区边长（§15）：视觉只是两条斜杠，热区给到指尖好按的大小。 */
private val RESIZE_HANDLE_TOUCH = 44.dp

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

    /** 手指当前在根坐标里的位置：判定落进哪个组、边缘自动滚动都按它算。 */
    var pointer by mutableStateOf(Offset.Zero)

    /** 按下时手指落在块内的位置（像素）：松手时减掉它，才知道把块摆到哪一格。 */
    var grab by mutableStateOf(Offset.Zero)

    /**
     * 幽灵块左上角在**页面内容坐标**里的位置。
     *
     * 这里刻意不用「手指位置 − 抓取偏移」反算：那个式子要求组坐标、抓取点、页面原点
     * 三套坐标系严格对齐，只要壳多移了一像素（上移、内边距），幽灵块就整体偏出去。
     * 改成「起手那一刻正好压在原块上，之后只累加手指位移」，跟手是构造上成立的（§14）。
     */
    var ghostOrigin by mutableStateOf(Offset.Zero)

    /** 首页根节点在窗口里的位置：组坐标和手指坐标都是根坐标，幽灵块要换回页面坐标。 */
    var pageOrigin by mutableStateOf(Offset.Zero)

    /** 正在缩放的那一块与它的实时尺寸：跟手反馈，松手才落盘。 */
    var resizingId by mutableStateOf<String?>(null)
    var liveSize by mutableStateOf<Offset?>(null)

    /** 正在拖动排序的那一组。 */
    var reorderGroupId by mutableStateOf<String?>(null)
        private set

    /** 手指当前的根坐标 y：跟手位移与插入位置都按它算。 */
    var reorderY by mutableStateOf(0f)
        private set

    /** 起手那一刻手指的根坐标 y：被拖组的跟手位移 = [reorderY] − 它。 */
    var reorderStartY by mutableStateOf(0f)
        private set

    /** 把手自己的全局顶边（根坐标）：`onDragStart` 的 down 是相对把手的，要换成根坐标。 */
    val reorderHandleTops = mutableStateMapOf<String, Float>()

    /** 每个卡片组在根坐标里的位置；各自在 `onGloballyPositioned` 里登记。 */
    val boards = mutableStateMapOf<String, HomeBoardMetrics>()

    val dragging: Boolean get() = tileId != null
    val reordering: Boolean get() = reorderGroupId != null

    /** 被拖那一组相对其布局位置的跟手位移（§16：组排序也要跟手，不是松手才跳）。 */
    val reorderShift: Float get() = reorderY - reorderStartY

    fun beginDrag(tile: HomeTile, groupId: String, board: HomeBoardMetrics, local: Offset) {
        tileId = tile.id
        routeId = tile.routeId
        fromGroupId = groupId
        sizeCells = Offset(tile.w, tile.h)
        grab = local
        // 起手位置 = 这一块真被画在哪：组的左上角（根坐标）+ 格数 × 格宽，再换到页面坐标
        ghostOrigin = Offset(
            board.left - pageOrigin.x + tile.x * board.colUnit,
            board.top - pageOrigin.y + tile.y * board.rowUnit,
        )
        pointer = ghostOrigin + pageOrigin + local
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

    fun beginReorder(groupId: String, pointerRootY: Float) {
        reorderGroupId = groupId
        reorderStartY = pointerRootY
        reorderY = pointerRootY
    }

    fun reorderBy(dy: Float) {
        reorderY += dy
    }

    fun endReorder() {
        reorderGroupId = null
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
    onReorderDrop: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = HomeCellHeight,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val tiles = group.tiles
    val rows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)

    // 被拖排序的这一组跟手浮起（§16）：只动绘制层，松手才由首页 moveGroup 落位。
    // 跟手期间它的 bounds 会带上这段位移，但插入位置只看**其它组**（它们不动），不受影响
    val reordering = session.reorderGroupId == group.id
    Column(
        modifier
            .fillMaxWidth()
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
                session = session,
                onStartEdit = onStartEdit,
                onEditGroup = onEditGroup,
                onAddTile = onAddTile,
                onReorderDrop = onReorderDrop,
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

/** 组标题行：编辑态可按住排序（拖手柄那一段），右侧是「改组名/颜色」「加功能」。 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    session: HomeDragSession,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onAddTile: () -> Unit,
    onReorderDrop: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val titleColor = CourseColors.parseHex(group.colorHex)
        ?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: ink

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Box(
                Modifier
                    .size(22.dp)
                    .onGloballyPositioned { coords ->
                        val top = coords.boundsInRoot().top
                        if (session.reorderHandleTops[group.id] != top) {
                            session.reorderHandleTops[group.id] = top
                        }
                    }
                    .pointerInput(group.id) {
                        detectDragGestures(
                            onDragStart = { down ->
                                onStartEdit()
                                // down 是相对这枚把手的坐标，要换算成根坐标才是手指的真实位置。
                                // 之前误用了「组网格的顶边 + down.y」——把手在标题行里，比网格
                                // 顶边高出一整行标题，于是按下去那一瞬间 reorderY 就偏了三十来 dp，
                                // 排序目标立刻算错、组跟着乱跳
                                val top = session.reorderHandleTops[group.id] ?: return@detectDragGestures
                                session.beginReorder(group.id, top + down.y)
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                session.reorderBy(delta.y)
                            },
                            // 排序**松手才落位**：拖动过程只有被拖组跟手与插入指示线，
                            // 列表不反复重排（§16）
                            onDragEnd = {
                                onReorderDrop()
                                session.endReorder()
                            },
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

    // 编辑态抖动：每块按 id 定一个相位（同一时刻各块朝不同方向），拖起来的那一块停住，
    // 退出编辑 shaking 直接变 false、rotationZ 立刻回到 0，不需要复位动画。
    // 只在编辑态才挂 InfiniteTransition，别让平时也白跑一个永不结束的动画。
    val shaking = editing && !lifted && session.resizingId != tile.id
    val shakePhase = remember(tile.id) {
        ((tile.id.hashCode() % 360 + 360) % 360) / 360f * 2f * PI.toFloat()
    }
    val shakeAngleState: androidx.compose.runtime.State<Float> = if (shaking) {
        rememberInfiniteTransition(label = "tileShake").animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(TILE_SHAKE_PERIOD_MS, easing = LinearEasing),
            ),
            label = "tileShakeAngle",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val shakeAngle by shakeAngleState

    // 移动 / 缩放共用一个手势层，挂在 graphicsLayer **之前**（lifted 的缩小、编辑态抖动都画在
    // graphicsLayer 里）：手势拿到的位移才不会被这些视觉变换反向放大。之前手势挂在
    // graphicsLayer 内部——被拖块一缩小（scale 0.94），位移就被逆变换放大 1/0.94，
    // 幽灵块越跑越偏离手指；缩放把手又挂在会跟着右下角移动的子节点上，位移被自己
    // 的移动吃掉一半，缩放只有手指速度的一半（§14 跟手、§15 缩放）。
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
                    val completed = drag(dragStart.id) { change ->
                        // 位移直接用两次事件的位置差（PointerInputChange 的两个公共属性），
                        // 不走需要额外 import 的扩展函数，跨端解析最稳
                        val delta = change.position - change.previousPosition
                        val current = session.liveSize ?: Offset(tile.w, tile.h)
                        session.liveSize = Offset(
                            (current.x + delta.x / colUnit)
                                .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                            (current.y + delta.y / rowUnit)
                                .coerceIn(HomeGridMath.MIN_SPAN, HomeGridMath.MAX_SPAN),
                        )
                        change.consume()
                    }
                    val size = session.liveSize ?: Offset(tile.w, tile.h)
                    session.endResize()
                    if (completed) onDropResize(tile.id, size.x, size.y)
                } else {
                    val board = session.boards[groupId]
                    if (board != null) {
                        // 抓取点用「越过触摸斜坡之后」的位置（dragStart），不是按下点：
                        // 斜坡那一段手指已经挪开了一截，用按下点会让幽灵块全程落后一个斜坡
                        session.beginDrag(tile, groupId, board, dragStart.position)
                        val completed = drag(dragStart.id) { change ->
                            session.dragBy(change.position - change.previousPosition)
                            change.consume()
                        }
                        if (completed) onDropDrag()
                        session.endDrag()
                    }
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
            .then(gesture)
            .graphicsLayer {
                // 被搬走的那一块淡下去，位置留给松手之后的挤开结果
                alpha = if (lifted) 0.28f else 1f
                scaleX = if (lifted) 0.94f else 1f
                scaleY = scaleX
                // 旋转在 graphicsLayer 里算：抖动只改绘制层，不触发重组
                rotationZ = if (shaking) sin(shakeAngle + shakePhase) * TILE_SHAKE_DEG else 0f
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
            // 右下角：缩放把手的**视觉**（§15）。热区在外层那一层手势里按右下角判定，
            // 这里只画记号、不接手势——这样把手的视觉跟着右下角动，手势坐标却始终锚在
            // 不动的外层上（见上面 gesture 的说明）
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(RESIZE_HANDLE_TOUCH),
                contentAlignment = Alignment.Center,
            ) {
                // 抓手画两条斜杠（像右下角的「可拉伸」记号），比一个实心方块更像把手
                val gripColor = MiuixTheme.colorScheme.primary
                Canvas(Modifier.size(13.dp)) {
                    val side = size.width
                    val stroke = side * 0.16f
                    drawLine(
                        color = gripColor,
                        start = Offset(side * 0.22f, side * 0.95f),
                        end = Offset(side * 0.95f, side * 0.22f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = gripColor,
                        start = Offset(side * 0.22f, side * 0.48f),
                        end = Offset(side * 0.48f, side * 0.22f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
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
