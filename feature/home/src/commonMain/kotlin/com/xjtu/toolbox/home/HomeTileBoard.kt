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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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
internal val RESIZE_HANDLE_TOUCH = 30.dp

/** 左上角移除按钮的热区边长：视觉只有 18dp，热区放大到 32dp，点偏一点也点得到。 */
internal val REMOVE_TOUCH = 32.dp

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
 * **渲染的输入是实时布局**（拖拽中 = 首页每帧按快照重算的那份，见 [HomeScreen]）：
 * - 被拖的那一块不画真身，改画一个弹簧跟随数据位置的**落点占位框**（[DropSlot]）；
 *   跟手的是首页顶层那一只幽灵块（[HomeDragGhost]）。
 * - 其余块弹簧补位到各自的数据格。
 *
 * [budgetRows] 是拖动中这一组的**行数锁**（= 起手快照的行数）：有了它，组高在整场拖拽里
 * 恒定，于是「命中用的几何」和「画出来的几何」永远严格相等——这是落点不飘的物理保证
 * （详见 [HomeLayout.moveTile] 的 rowBudget 说明）。非拖动时为 null，组高跟随实时数据。
 *
 * 组排序（拖标题行左侧的把手）走的是另一套：被拖组跟手浮起（[reorderLift]）、其它组按
 * 「越过中线」纯函数让位（[reorderShift]），松手才落盘。那套几何也是纯函数、按快照算，
 * 与卡片拖拽共用同一个 [HomeBoardGeometry]，坐标一律内容坐标（见 [HomeDragSession]）。
 */
@Composable
internal fun HomeGroupSection(
    group: HomeGroup,
    faces: Map<String, HomeServiceRow>,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /** 拖动中锁定的行数；null = 跟随实时数据。 */
    budgetRows: Float?,
    /** 手指当前把这一组当成落点（且不是拖拽源组）：画接收高亮（§19）。派生量，由首页算。 */
    receiving: Boolean,
    /** 被拖组的跟手位移（含松手后的归位补偿）；null = 这一组不在被拖。 */
    reorderLift: Float?,
    /** 组排序中这一组的让位目标（px）：被拖组抽走后它该往哪挪。 */
    reorderShift: Float,
    onOpen: (HomeServiceRow) -> Unit,
    onStartEdit: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = HomeCellHeight,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val tiles = group.tiles
    val contentRows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)
    // 拖动中用锁定的行数，否则用实时行数；组高据此弹簧伸缩
    val rows = budgetRows ?: contentRows
    val animRows by animateFloatAsState(targetValue = rows, animationSpec = HomeTileSpring, label = "groupRows")

    // 组排序的让位：被拖组跟手（lift），其它组弹簧到让位目标；排序一结束立刻 snap 回 0——
    // 那一刻布局已按新顺序就位，偏移只有立刻归零，视觉位置才连续（§16）
    val reordering = session.mode == HomeDragMode.GROUP
    val shiftAnim = remember { Animatable(0f) }
    LaunchedEffect(reorderShift, reorderLift != null, reordering) {
        when {
            reorderLift != null -> shiftAnim.snapTo(0f)
            !reordering -> shiftAnim.snapTo(0f)
            else -> launch { shiftAnim.animateTo(reorderShift, HomeTileSpring) }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (reorderLift != null) 6f else 0f)
            .graphicsLayer {
                translationY = reorderLift ?: shiftAnim.value
                alpha = if (session.groupId == group.id) 0.94f else 1f
            },
    ) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                liquid = liquid,
                backdrop = backdrop,
                onEditGroup = onEditGroup,
                onDeleteGroup = onDeleteGroup,
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

            val dragId = session.tileId
            // 拖动中与**结算中**都画占位框：松手后幽灵从手指位置飞向落点，落点这一块先留成
            // 占位框；幽灵飞到与它重合才 finish() 切回真实块——外观一样，切换无感。
            // 只判 draggingTile 的话，结算期它会立刻变成落点处的真实块，和还在飞的幽灵重影。
            val ghosted = session.draggingTile || session.settling
            tiles.forEach { tile ->
                val face = faces[tile.routeId] ?: return@forEach
                key(tile.id) {
                    if (ghosted && tile.id == dragId) {
                        // 被拖块：不画真身，画一个弹簧跟随数据位置的落点占位框。
                        // 真身由顶层的幽灵块画（跟手），松手时幽灵吸附到这里、占位框消失。
                        DropSlot(tile, colUnit, rowUnit, gapPx)
                    } else {
                        HomeTileItem(
                            tile = tile,
                            face = face,
                            editing = editing,
                            liquid = liquid,
                            backdrop = backdrop,
                            session = session,
                            colUnit = colUnit,
                            rowUnit = rowUnit,
                            gapPx = gapPx,
                            cellHeight = cellHeight,
                            onOpen = { onOpen(face) },
                            onStartEdit = onStartEdit,
                            onRemoveTile = onRemoveTile,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 组标题行：编辑态左侧把手（拖动上下排序，手势在 [HomeDragLayer] 上）、**点组名**打开
 * 「分组设置」抽屉，右侧一个删除按钮。
 *
 * 把手只画记号、不挂手势：命中与拖动全在手势层按快照几何判定，这里不再回读把手位置
 * （旧实现登记 `handleTops` 再拿它合成手指 y，那正是坐标被污染的一处）。
 */
@Composable
private fun GroupHeader(
    group: HomeGroup,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
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
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
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
 * 落点占位框：被拖块在**实时布局**里的位置与尺寸，弹簧跟位。
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
 * 一块组件。位置由格矩形算出，**位置变化用弹簧动画**（§6：挤开、重排都要 Q 弹）。
 *
 * 编辑态**不挂任何拖拽手势**——移动、缩放、组排序全部由顶层的 [HomeDragLayer] 按快照
 * 几何命中并接管。这里只在两种情况挂手势：
 * - 非编辑态：整块点击打开、长按进编辑态（此时 [HomeDragLayer] 根本没挂载，不冲突）；
 * - 编辑态的左上角移除按钮：一个独立的点击热区（手势层对这块放行，见 `HomeBoardGeometry.hit`）。
 *
 * 缩放中被缩的那一块尺寸跟手（snap，不做动画），其余块尺寸/位置变化走弹簧。
 */
@Composable
private fun HomeTileItem(
    tile: HomeTile,
    face: HomeServiceRow,
    editing: Boolean,
    liquid: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    cellHeight: Dp,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
) {
    val density = LocalDensity.current

    // 位置：跟数据弹簧（拖动中被挤开的块平滑补位）
    val animX by animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileSpring, label = "tileX")
    val animY by animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileSpring, label = "tileY")

    // 尺寸：缩放中被缩的那块跟手（snap），其余尺寸变化走弹簧
    val resizing = session.mode == HomeDragMode.RESIZE && session.tileId == tile.id
    val animW by animateFloatAsState(
        targetValue = tile.w,
        animationSpec = if (resizing) snap() else HomeTileSpring,
        label = "tileW",
    )
    val animH by animateFloatAsState(
        targetValue = tile.h,
        animationSpec = if (resizing) snap() else HomeTileSpring,
        label = "tileH",
    )

    val widthPx = (animW * colUnit - gapPx).coerceAtLeast(1f)
    val heightPx = (animH * rowUnit - gapPx).coerceAtLeast(1f)

    // 非编辑态：整块点击打开、长按进编辑态。编辑态这块不挂手势（交给手势层）。
    val tap = if (editing) {
        Modifier
    } else {
        Modifier.pointerInput(tile.id) {
            detectTapGestures(onTap = { onOpen() }, onLongPress = { onStartEdit() })
        }
    }

    Box(
        Modifier
            .offset { IntOffset((animX * colUnit).roundToInt(), (animY * rowUnit).roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .then(tap),
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

        if (editing) {
            // 左上角：移除这一块。热区 32dp，视觉只有 18dp，所以点偏一点也点得到。
            // 手势层对这个热区放行（HomeHit.Pass），所以这里的点击照常生效。
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(REMOVE_TOUCH)
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
            // 把手只画记号、不接手势：命中与拖动全在手势层。
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
                    ExpressiveIcon(icon = face.icon, color = face.color, size = 30.dp, iconSize = 17.dp)
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
