package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
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
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.movedFrom
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.ui.components.LocalEnterAnimations
import com.xjtu.toolbox.ui.components.rememberRevealProgress
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.rememberCampusNow
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import com.xjtu.toolbox.settings.HomeSettingsOverlay

/**
 * 网格是**正方格**：一格的高恒等于一格的宽（=「组宽 + 缝」÷ 列数），一组的高度因此是
 * `格数 × 一格边长 − 缝`。
 *
 * 这里曾经有两档单元高度——「留出文字空间」的 88dp 高长方形与「紧凑」的 46dp 方块，靠设置
 * 里一个开关切换。后来发现那等于同一页卡片有两套尺寸设计：开着像小组件，关掉就只剩图标，
 * 2×1 里连名称都塞不下。现在统一成正方格，1×1 永远是个放得下「图标 + 名称」的正方形，
 * 2×1 / 1×2 / 2×2 也都是正方形拼出来的，尺寸与内容的对应关系只有一套。
 */

/**
 * 组件之间、以及组件与组边界之间的标准缝隙。
 *
 * 这个缝隙是**渲染时**保证的：每个块都在自己的格矩形里四面各内缩一半，
 * 所以两块在格坐标上紧挨着，画出来也一定隔着一条 [HomeTileGap]，
 * 不会出现「拖到一起挤成一条线」（需求 §3.4）。
 */
internal val HomeTileGap = 8.dp

/**
 * 首页卡片的位移、尺寸、组高、编辑态和入场共用一条曲线，整块板一起动、一起停。
 */
internal const val HOME_TILE_MOTION_MS = 320
internal val HomeTileMotion = tween<Float>(HOME_TILE_MOTION_MS, easing = FastOutSlowInEasing)

/** 按下比松手快一点，曲线不变。 */
private val TilePressIn = tween<Float>(100, easing = FastOutSlowInEasing)

/** 编辑态卡片整体收到的比例，让出一圈空隙给角标。 */
private const val EDIT_TILE_SCALE = 0.95f


/** 移除角标弹出 / 收回。 */
private val BadgeEnter = scaleIn(TilePressIn, initialScale = 0.6f) + fadeIn(tween(140))
private val BadgeExit = scaleOut(tween(140)) + fadeOut(tween(120))

/**
 * 右下角缩放把手的热区边长（§15），从格子边界（含格间距）往里量。格间距那一截占掉了一部分，
 * 30dp 时卡面上实际只剩二十来 dp，很难按准；44dp 是常规触控尺寸，卡面中部仍归移动。
 */
internal val RESIZE_HANDLE_TOUCH = 44.dp

/**
 * 缩放把手的图示：一段贴着卡片圆角的弧（与卡片右下角同心，往里收 [RESIZE_GRIP_INSET]），
 * 卡片选了什么形状它就弯成什么样。方角卡片至少留 [RESIZE_GRIP_MIN_ARC] 的弧。
 */
private val RESIZE_GRIP_INSET = 5.dp
private val RESIZE_GRIP_MIN_ARC = 9.dp

/** 括线本身的粗细。 */
private val RESIZE_GRIP_STROKE = 4.5.dp

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
    /** 液态玻璃是否带折射；关掉只少一层 `lens`，模糊 / 活力 / 高光 / 投影都不动。 */
    refraction: Boolean,
    backdrop: Backdrop?,
    session: HomeDragSession,
    /** 拖动中锁定的行数；null = 跟随实时数据。 */
    budgetRows: Float?,
    /** 手指当前把这一组当成落点（且不是拖拽源组）：画接收高亮（§19）。派生量，由首页算。 */
    receiving: Boolean,
    /** 被拖组的跟手位移（含松手后的归位补偿），只在绘制阶段读，逐帧变化不触发重组；null = 这一组不在被拖。 */
    reorderLift: (() -> Float)?,
    /** 组排序中这一组的让位目标（px）：被拖组抽走后它该往哪挪。 */
    reorderShift: Float,
    onOpen: (HomeServiceRow) -> Unit,
    onStartEdit: () -> Unit,
    /**
     * 付款码取数（付款码 2×2 以上的大卡直接画二维码）。
     *
     * 这条是原「小组件」那套的能力，合并进卡片组后保留：只在用餐时段 + 页面可见时取，取不到就
     * 照常画大卡内容。默认返回 null = 这一端拿不到（桌面 / Web 就靠这个默认值什么都不画）。
     */
    paymentCode: suspend () -> String? = { null },
    /**
     * 卡片能不能糊（只有卡片吃「没有可糊的细节就不糊」这条规则；按钮照旧模糊）。
     *
     * 「没有细节」有两种：纯色 / 渐变背景（压根没有壁纸），以及壁纸自己已经被「模糊程度」糊到
     * 看不出细节（`SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS` 及以上）。两种都由调用方算成
     * 一个布尔传进来，见 [HomeTileFaceBox] 的同名参数。
     */
    blurGlass: Boolean = true,
    /** 卡片圆角（主页设置里那条滑条），实际还会被「边长 × 12」夹住，见 [HomeTileFaceBox]。 */
    cardCorner: Dp = 22.dp,
    /** 被缩放那一块此刻跟手的连续尺寸（格）。 */
    resizeSpan: (HomeTile) -> Offset? = { null },
    onEditGroup: () -> Unit,
    /** 编辑态往这一组加功能（组头的「+」、空组占位都走它）。 */
    onAddTile: () -> Unit,
    /** 添加功能的菜单，从组头的「+」展开。 */
    addTileMenu: @Composable () -> Unit,
    onDeleteGroup: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { HomeTileGap.toPx() }
    val tiles = group.tiles
    val contentRows = HomeGridMath.contentBottom(tiles).coerceAtLeast(1f)
    // 拖动中用锁定的行数，否则用实时行数；组高据此弹簧伸缩
    val rows = budgetRows ?: contentRows
    val animRows = animateFloatAsState(targetValue = rows, animationSpec = HomeTileMotion, label = "groupRows")

    // 组排序的让位：被拖组跟手（lift），其它组弹簧到让位目标；排序一结束立刻 snap 回 0——
    // 那一刻布局已按新顺序就位，偏移只有立刻归零，视觉位置才连续（§16）
    val reordering = session.mode == HomeDragMode.GROUP
    val shiftAnim = remember { Animatable(0f) }
    LaunchedEffect(reorderShift, reorderLift != null, reordering) {
        when {
            reorderLift != null -> shiftAnim.snapTo(0f)
            !reordering -> shiftAnim.snapTo(0f)
            else -> launch { shiftAnim.animateTo(reorderShift, HomeTileMotion) }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (reorderLift != null) 6f else 0f)
            .graphicsLayer {
                translationY = reorderLift?.invoke() ?: shiftAnim.value
                alpha = if (session.groupId == group.id) 0.94f else 1f
            },
    ) {
        if (editing || group.title.isNotBlank()) {
            GroupHeader(
                group = group,
                editing = editing,
                liquid = liquid,
                refraction = refraction,
                backdrop = backdrop,
                onEditGroup = onEditGroup,
                onAddTile = onAddTile,
                addTileMenu = addTileMenu,
                onDeleteGroup = onDeleteGroup,
            )
            Spacer(Modifier.height(GROUP_HEADER_HEIGHT - GROUP_HEADER_ROW))
        }

        // 一格是正方形，所以「一格边长」只能从**量到的宽度**里推：外面这层 BoxWithConstraints
        // 只负责量宽度，里面那个 Box 才按「行数 × 边长 − 缝」定高。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val colUnit = (constraints.maxWidth + gapPx) / HomeGridMath.COLUMNS
            // 正方格：一格的高 = 一格的宽（见文件头那段「网格是正方格」）
            val rowUnit = colUnit
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val height = (animRows.value * rowUnit - gapPx).roundToInt()
                            .coerceIn(constraints.minHeight, constraints.maxHeight)
                        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            ) {
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
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback(),
                                onClick = onAddTile,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "这一组还是空的，点这里添加功能",
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
                            // 真身由顶层的幽灵块画（跟手），松手时幽灵吸附到这里、占位框渐隐。
                            DropSlot(tile, colUnit, rowUnit, gapPx, settling = session.settling, cardCorner = cardCorner)
                        } else {
                            HomeTileItem(
                                tile = tile,
                                face = face,
                                editing = editing,
                                liquid = liquid,
                                refraction = refraction,
                                backdrop = backdrop,
                                blurGlass = blurGlass,
                                cardCorner = cardCorner,
                                colUnit = colUnit,
                                rowUnit = rowUnit,
                                gapPx = gapPx,
                                paymentCode = paymentCode,
                                resizeSpan = {
                                    if (session.mode == HomeDragMode.RESIZE && tile.id == dragId) resizeSpan(tile) else null
                                },
                                resizing = session.mode == HomeDragMode.RESIZE && tile.id == dragId,
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
}

/**
 * 组标题行：编辑态左侧把手（拖动上下排序，手势在 [homeDragLayer] 上）、**点组名**打开
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
    refraction: Boolean,
    backdrop: Backdrop?,
    onEditGroup: () -> Unit,
    onAddTile: () -> Unit,
    addTileMenu: @Composable () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val titleColor = CourseColors.parseHex(group.colorHex)
        ?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: ink
    // 编辑态下点组名打开「分组设置」抽屉。手势协程跨重组存活，调最新的回调
    val latestEditGroup by rememberUpdatedState(onEditGroup)
    val titleTap = if (editing) {
        Modifier.pointerInput(group.id) { detectTapGestures(onTap = { latestEditGroup() }) }
    } else {
        Modifier
    }

    // 高度固定（GROUP_HEADER_ROW）：组的总高因此是数据的纯函数，组排序的让位几何不依赖回读
    Row(
        Modifier.fillMaxWidth().height(GROUP_HEADER_ROW).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = editing,
            enter = expandHorizontally(tween(260, easing = FastOutSlowInEasing)) + fadeIn(),
            exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
        ) {
            Box(Modifier.padding(end = 4.dp).size(22.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, contentDescription = "拖动排序", tint = muted, modifier = Modifier.size(16.dp))
            }
        }
        if (group.title.isNotBlank()) {
            Text(
                group.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 必须给满 weight（不能 fill = false）：fill = false 时 Text 只量到自己的实际
                // 宽度，它那份**配给但没用掉**的空间不会还给后面的兄弟，于是右侧的删除按钮
                // 被顶到「标题 + 半个剩余宽度」那里，看着就是不居右。
                modifier = Modifier.weight(1f).then(titleTap),
            )
        } else if (editing) {
            Text(
                "未命名分组",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = muted.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f).then(titleTap),
            )
        } else {
            // 这一行只在「编辑态，或组名非空」时才会被画（见外层判断），这里是兜底：
            // 无非让一个空占位把右侧按钮顶到边上
            Spacer(Modifier.weight(1f))
        }
        AnimatedVisibility(visible = editing, enter = BadgeEnter, exit = BadgeExit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    GroupRoundButton(Icons.Default.Add, "往这一组添加功能", MiuixTheme.colorScheme.primary, liquid, backdrop, onAddTile)
                    addTileMenu()
                }
                GroupRoundButton(Icons.Default.Close, "删除这一组", MiuixTheme.colorScheme.error, liquid, backdrop, onDeleteGroup)
            }
        }
    }
}

private val ROUND_BUTTON_SIZE = 30.dp

/** 组标题行右侧的小圆钮（添加 / 删除）：玻璃（§2.4）。 */
@Composable
private fun GroupRoundButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    liquid: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    // 和卡片同一种贴图玻璃：编辑态每组两颗，滚动时不能每帧各跑一遍模糊和折射。
    // 背景上压根没有细节（纯色 / 渐变）时 `backdrop` 是 null，走下面的薄色兜底。
    val material = when {
        liquid && backdrop != null ->
            Modifier.softGlass(backdrop, ROUND_BUTTON_SIZE / 2, fill, SolidColor(Color.Transparent), dark)
        // 没开采样（背景是纯色 / 渐变）：退回半透明底，别画成一片透明
        liquid -> Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        else -> Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    }
    // 键只有文案：同一位置换成别的组时手势不重启，必须调最新的回调，否则删的是原来那一组
    val latestClick by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(ROUND_BUTTON_SIZE)
            .then(material)
            .pointerInput(label) { detectTapGestures(onTap = { latestClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * 付款码大卡上的二维码。
 *
 * 只在「用餐时段 + 这一页可见」时去取码（[isMealPaymentTime]，见 `PaymentCodeTime.kt`），取到就
 * 整块盖成二维码并每 30 秒自动刷新；取不到 / 不在时段就什么都不画，底下的大卡内容照旧——所以
 * 绝大多数时候它只花一次「现在是不是饭点」的判断，不发任何请求。
 */
@Composable
private fun PaymentCodeOverlay(fetch: suspend () -> String?, radius: Dp) {
    val now = rememberCampusNow()
    val active = LocalPageVisible.current && isMealPaymentTime(now.time)
    val code by produceState<String?>(null, active) {
        value = null
        while (active) {
            value = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 没登录 / 校园卡没登 / 网络抖一下都走这条：不画码，也不弹错
                null
            }
            delay(PAYMENT_REFRESH_MS)
        }
    }
    val matrix = remember(code, active) { code?.takeIf { active }?.let { qrCodeModules(it) } } ?: return
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(radius))
            .background(Color.White)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            CodeMatrixView(matrix = matrix, quietZoneModules = 1, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "付款码 · 自动刷新",
            fontSize = 10.sp,
            color = Color.Black.copy(alpha = 0.65f),
            maxLines = 1,
        )
    }
}

/** 付款码自动刷新的间隔（与原「小组件」那套一致）。 */
private const val PAYMENT_REFRESH_MS = 30_000L

/**
 * 卡片的点击层：整块点开 + 长按进编辑态，并把「从这一块出发」的矩形交给一镜到底。
 *
 * 和经典屏 `opensRow` 是同一套接线：`expandOriginSource` 登记起点，按下先 `arm`（Compose 回落
 * 转场用它画放大），再按宿主有没有接 [HomeServiceRow.onSeamlessClick] 决定走 KIT 独立 Activity
 * （金标联盟 SharedViewKit）还是普通跳转。宿主没接就退回 `onOpen`，桌面 / Web 与老 ROM 的行为不变。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.tileOpen(
    face: HomeServiceRow,
    radius: Dp,
    press: MutableInteractionSource,
    /** 转场里画的那张卡：实时画一张真的，内容一定在。 */
    snapshot: @Composable (Modifier) -> Unit,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
): Modifier {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val openAfterPress = rememberOpenAfterPress(press)
    return this
        .expandOriginSource(origin)
        .instantPress(press)
        .combinedClickable(
            interactionSource = press,
            indication = null,
            onClick = {
                openAfterPress {
                    origin.arm(face.key, radius, density, snapshot)
                    val seamless = face.onSeamlessClick
                    val bounds = origin.boundsInWindow()
                    if (seamless != null && bounds != null) {
                        seamless(SeamlessCardSource(bounds, radius))
                    } else {
                        onOpen()
                    }
                }
            },
            onLongClick = onStartEdit,
        )
}

/**
 * 手指一碰就算按下：滚动容器里系统要等约 100ms 才发按压，快速点一下根本看不到反馈。
 * 只旁听不消费，滚动开始（事件被消费）就松开。
 */
internal fun Modifier.instantPress(source: MutableInteractionSource): Modifier = pointerInput(source) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = PressInteraction.Press(down.position)
        source.tryEmit(press)
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
        } while (event.changes.any { it.pressed } && event.changes.none { it.isConsumed && it.positionChange() != Offset.Zero })
        source.tryEmit(PressInteraction.Release(press))
    }
}

/** 先把按下的样子演完再打开；页面盖上之后才松开，返回时卡片还在按下的状态。 */
@Composable
internal fun rememberOpenAfterPress(source: MutableInteractionSource): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    return { open ->
        if (!opening) {
            opening = true
            scope.launch {
                val hold = PressInteraction.Press(Offset.Zero)
                source.emit(hold)
                delay(PRESS_BEFORE_OPEN_MS)
                open()
                delay(300)
                source.emit(PressInteraction.Release(hold))
                opening = false
            }
        }
    }
}

private const val PRESS_BEFORE_OPEN_MS = 100L

/**
 * 落点占位框：被拖块在**实时布局**里的位置与尺寸，弹簧跟位。
 * 「松手会落在哪」在拖动全程都看得见；松手时幽灵块飞过来与它重合，预览固化成数据。
 *
 * [settling] 为真（松手结算中）时它**渐隐**：幽灵块正飞过来长成原大，落点会在这段时间里被
 * 盖住，所以淡出不会被看见；而等 `finish()` 那一刻才硬消失的话，边框会「啪」一下没掉。
 */
@Composable
private fun DropSlot(
    tile: HomeTile,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    settling: Boolean,
    /** 占位框跟着卡片圆角走，松手前看到的「空位」才和真身一个形状。 */
    cardCorner: Dp = 22.dp,
) {
    val density = LocalDensity.current
    val x by animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileMotion, label = "dropX")
    val y by animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileMotion, label = "dropY")
    val w by animateFloatAsState(targetValue = tile.w, animationSpec = HomeTileMotion, label = "dropW")
    val h by animateFloatAsState(targetValue = tile.h, animationSpec = HomeTileMotion, label = "dropH")
    // 淡出比吸附飞行稍快一点收尾：这样淡完时幽灵已经盖住它了
    val fade by animateFloatAsState(
        targetValue = if (settling) 0f else 1f,
        animationSpec = tween(320),
        label = "dropSlotFade",
    )
    val frame = cellFrame(x, y, w, h, colUnit, rowUnit, gapPx)
    // 圆角跟卡片同一条设置；上限是**短边的一半**，所以滑到顶时占位框也是圆饼
    val radius = cardCorner.coerceAtMost(
        with(density) { minOf(frame.widthPx, frame.heightPx).toDp() } / 2,
    )
    val accent = MiuixTheme.colorScheme.primary
    Box(
        Modifier
            // 占位框必须压在同组其它块**之上**：它是"松手会落到这"的唯一提示，
            // 被邻居盖住就等于没有。层级只有 2，仍然在顶层幽灵块（20）之下。
            .zIndex(2f)
            .offset { frame.leftTop }
            .size(with(density) { frame.widthPx.toDp() }, with(density) { frame.heightPx.toDp() })
            .graphicsLayer { alpha = fade }
            // 幽灵是半透明盖在它上面的，所以底色给足、边框加粗，透过幽灵也能看清这块"空位"
            .background(accent.copy(alpha = 0.20f), RoundedCornerShape(radius))
            .border(2.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(radius)),
    )
}

/**
 * 「格 → 像素」的唯一换算入口：块的左上角与尺寸都从这里出。
 *
 * 之前 DropSlot 和 HomeTileItem 各写一遍 `x * colUnit` / `w * colUnit - gap`，两处一旦
 * 有一点不一致，占位框和真身就对不上（这也是「网格不对」最容易复发的地方）。收敛成
 * 纯函数后，两者永远同一个矩形。
 *
 * 注意：块贴在格的左上角、宽高各少一个 gap，所以视觉上缝隙集中在右/下侧——这是既有
 * 视觉，保持一致，不在这次重构里改动。
 */
private class CellFrame(val leftTop: IntOffset, val widthPx: Float, val heightPx: Float)

private fun cellFrame(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
): CellFrame = CellFrame(
    leftTop = IntOffset((x * colUnit).roundToInt(), (y * rowUnit).roundToInt()),
    widthPx = (w * colUnit - gapPx).coerceAtLeast(1f),
    heightPx = (h * rowUnit - gapPx).coerceAtLeast(1f),
)

/**
 * 一块组件。位置由格矩形算出，**位置变化用弹簧动画**（§6：挤开、重排都要 Q 弹）。
 *
 * 编辑态**不挂任何拖拽手势**——移动、缩放、组排序全部由顶层的 [homeDragLayer] 按快照
 * 几何命中并接管。这里只在两种情况挂手势：
 * - 非编辑态：整块点击打开、长按进编辑态（此时 [homeDragLayer] 根本没挂载，不冲突）；
 * - 编辑态的左上角移除按钮：一个独立的点击热区（手势层对这块放行，见 `HomeBoardGeometry.hit`）。
 *
 * 尺寸与位置一律走弹簧（缩放也一样）：缩放时数据是一格一格跳的，snap 会把这些跳变原样
 * 画出来、看着一顿一顿；走弹簧边缘才连续地挪过去。
 */
@Composable
private fun HomeTileItem(
    tile: HomeTile,
    face: HomeServiceRow,
    editing: Boolean,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    colUnit: Float,
    rowUnit: Float,
    gapPx: Float,
    /** 付款码取数；null 的语义见 [HomeGroupSection] 的同名参数。 */
    paymentCode: suspend () -> String? = { null },
    /** 这张卡能不能糊；见 [HomeTileFaceBox] 的同名参数。 */
    blurGlass: Boolean = true,
    /** 卡片圆角；见 [HomeGroupSection] 的同名参数。 */
    cardCorner: Dp = 22.dp,
    /** 这一块正被缩放时手指对应的连续尺寸（格），其余时候 null。 */
    resizeSpan: () -> Offset? = { null },
    resizing: Boolean = false,
    onOpen: () -> Unit,
    onStartEdit: () -> Unit,
    onRemoveTile: (tileId: String) -> Unit,
) {
    val density = LocalDensity.current
    // 移除热区的手势按 tile.id 只起一次，回调闭包里的网格开关等会变：经 rememberUpdatedState 读最新值
    val removeTile by rememberUpdatedState(onRemoveTile)

    // 位置：跟数据弹簧（拖动中被挤开的块平滑补位）。只在 offset 的布局阶段读，补位动画不逐帧重组卡面
    val animX = animateFloatAsState(targetValue = tile.x, animationSpec = HomeTileMotion, label = "tileX")
    val animY = animateFloatAsState(targetValue = tile.y, animationSpec = HomeTileMotion, label = "tileY")

    // 尺寸：缩放中直接等于手指（不经过网格），松手再缓缓落到网格；其它情况跟数据走同一条过渡。
    val resizeSpanState = rememberUpdatedState(resizeSpan)
    val targetSpan = rememberUpdatedState(Offset(tile.w, tile.h))
    val spanAnimation = remember { Animatable(Offset(tile.w, tile.h), Offset.VectorConverter) }
    LaunchedEffect(spanAnimation) {
        snapshotFlow { resizeSpanState.value() to targetSpan.value }.collectLatest { (finger, target) ->
            if (finger != null) spanAnimation.snapTo(finger)
            else spanAnimation.animateTo(target, tween(HOME_TILE_MOTION_MS, easing = FastOutSlowInEasing))
        }
    }
    // 连续尺寸只在测量、绘制和形态阈值判断里读取，不让指针每动一像素就重组整张卡面。
    val liveSpan = remember { { resizeSpanState.value() ?: spanAnimation.value } }
    val large by remember { derivedStateOf { liveSpan().let { it.x >= 1.5f && it.y >= 1.5f } } }

    // 非编辑态：整块点击打开、长按进编辑态。编辑态这块不挂手势（交给手势层）。
    // 点击走「一镜到底」那套接线，和卡片圆角用同一个半径，转场时放大出来的形状才对得上。
    //
    // 圆角按目标短边截断，不随尺寸动画逐帧改材质参数。绘制时还会按实际大小限幅。
    val target = cellFrame(0f, 0f, tile.w, tile.h, colUnit, rowUnit, gapPx)
    val radius = cardCorner.coerceAtMost(
        with(density) { minOf(target.widthPx, target.heightPx).toDp() } / 2,
    )
    val press = remember { MutableInteractionSource() }
    val tap = if (editing) {
        Modifier
    } else {
        Modifier.tileOpen(
            face = face,
            radius = radius,
            press = press,
            snapshot = { m ->
                // 和原卡完全同一种材质，连背景采样也带上：采样按这张卡在屏幕上的实际位置取，
                // 落回原位时取到的就是原卡那一块，换手那一帧两张逐像素一致（少了采样会透出没糊的壁纸、亮度一跳）
                HomeTileFaceBox(face = face, w = tile.w, h = tile.h, liquid = liquid, refraction = refraction, backdrop = backdrop, blurGlass = blurGlass, cornerRadius = radius, modifier = m)
            },
            onOpen = onOpen,
            onStartEdit = onStartEdit,
        )
    }

    // 动效全在绘制阶段读：按下下沉、编辑态整体收一圈
    val pressed by press.collectIsPressedAsState()
    // 页面盖上时直接复原（只重画一帧）：起点按原尺寸记，缩回来落在原尺寸上，不再落定后弹一下
    val pageVisible = LocalPageVisible.current
    val pressDepth = remember { Animatable(0f) }
    LaunchedEffect(pressed, editing, pageVisible) {
        if (pageVisible) {
            val down = pressed && !editing
            pressDepth.animateTo(if (down) 1f else 0f, if (down) TilePressIn else HomeTileMotion)
        } else {
            pressDepth.snapTo(0f)
        }
    }
    val editScale = animateFloatAsState(if (editing) EDIT_TILE_SCALE else 1f, HomeTileMotion, label = "tileEdit")
    // 从上到下、从左到右依次起跳；下方未露面的行不入场，编辑中新建 / 拖回的卡片直接就位。
    val entrance = rememberRevealProgress(
        key = tile.id,
        delayMillis = (tile.y.toInt().coerceIn(0, 2) * 24 + tile.x.toInt().coerceIn(0, 3) * 12).toLong(),
        spec = HomeTileMotion,
        skip = editing || tile.y >= 3f || !LocalEnterAnimations.current,
    )

    Box(
        Modifier
            // 正在缩放的这一块压在邻居上面：它按手指走，邻居按网格让位，中途会有交叠
            .zIndex(if (resizing) 1f else 0f)
            .offset { IntOffset((animX.value * colUnit).roundToInt(), (animY.value * rowUnit).roundToInt()) }
            .layout { measurable, constraints ->
                val span = liveSpan()
                val frame = cellFrame(0f, 0f, span.x, span.y, colUnit, rowUnit, gapPx)
                val width = frame.widthPx.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
                val height = frame.heightPx.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(
                    constraints.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            // 点击层在缩放层外面：起点矩形不含按压缩放
            .then(tap)
            .graphicsLayer {
                val entry = entrance.value
                val depth = pressDepth.value
                val s = (0.86f + 0.14f * entry) * editScale.value
                scaleX = s * (1f - 0.03f * depth)
                scaleY = s * (1f - 0.03f * depth)
                translationY = 16.dp.toPx() * (1f - entry)
                alpha = entry.coerceIn(0f, 1f)
            },
    ) {
        // 柔光玻璃已没有每卡的采样层，圆角和材质可以原地更新，保住卡面组合身份。
        HomeTileFaceBox(
            face = face,
            w = tile.w,
            h = tile.h,
            liquid = liquid,
            refraction = refraction,
            backdrop = backdrop,
            blurGlass = blurGlass,
            cornerRadius = radius,
            interactive = !editing,
            modifier = Modifier.fillMaxSize(),
            liveSpan = liveSpan,
        )

        // 付款码的 2×2 大卡：用餐时段直接把码画在这块上。取不到码 / 不在时段就什么都不画，
        // 下面的卡面照旧。校园卡那块只管余额与消费，不盖码。
        if (!editing && large && face.key == AppRoute.PaymentCode.id) {
            PaymentCodeOverlay(fetch = paymentCode, radius = radius)
        }

        // 左上角：移除这一块。热区 32dp，视觉只有 18dp；手势层对这个热区放行（HomeHit.Pass）
        AnimatedVisibility(
            visible = editing,
            modifier = Modifier.align(Alignment.TopStart),
            enter = BadgeEnter,
            exit = BadgeExit,
        ) {
            val latestRemove by rememberUpdatedState(removeTile)
            Box(
                Modifier
                    .size(REMOVE_TOUCH)
                    .pointerInput(tile.id) { detectTapGestures(onTap = { latestRemove(tile.id) }) },
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
        }
        // 右下角：缩放把手的视觉（§15），只画记号、手势在手势层。标的是这一格的右下角，
        // 与卡片圆角无关，括线只内缩 [RESIZE_GRIP_INSET] 保证描边不被裁
        AnimatedVisibility(
            visible = editing,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(tween(220, delayMillis = 60)),
            exit = fadeOut(tween(120)),
        ) {
            Box(Modifier.size(RESIZE_HANDLE_TOUCH)) {
                val gripColor = MiuixTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = RESIZE_GRIP_STROKE.toPx()
                    val inset = RESIZE_GRIP_INSET.toPx()
                    // 一段贴着卡片圆角的弧：和卡片右下角同心、往里收 inset，卡片是什么圆角它就弯成什么样；
                    // 方角卡片也留一段小弧，不然只剩一个点
                    val arc = (radius.toPx() - inset).coerceAtLeast(RESIZE_GRIP_MIN_ARC.toPx())
                    val cx = size.width - inset - arc
                    val cy = size.height - inset - arc
                    drawArc(
                        color = gripColor,
                        startAngle = 6f,
                        sweepAngle = 78f,
                        useCenter = false,
                        topLeft = Offset(cx - arc, cy - arc),
                        size = Size(arc * 2, arc * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * 组件的材质与内容：尺寸决定排版（§3.3），材质由「主页卡片液态玻璃」开关决定（§5.2）。
 *
 * 排版这一套是从「小组件模式」的首页（`dev` 的 `HomeWidgetGrid`）搬过来的逐档设计：
 * 1×1 是「图标 + 名称」，横条是「图标 + 小号名称 + 主要数据 + 一行说明」，竖条是
 * 「小号名称 + 大字数据 + 说明」，大方块再压一枚水印图标、字号也大一档。各档共用同一条
 * 「大字 / 一句话」判定（见 [FIGURE_MAX_CHARS] 与 [LIST_TILES]），所以同一个功能在任何
 * 尺寸下强调的都是同一件事。
 *
 * 与那一套唯一的差别：**1×1 一律画图标**，不拿状态数字顶替（小方块上认图标比读数字快）。
 * 网格统一是正方格，1×1 永远放得下图标与名称，所以这里没有任何「窄了就只剩图标」的降级档。
 *
 * 液态玻璃直接复用 App 里已有的那套 `drawBackdrop`（底栏、玻璃顶栏用的同一个），
 * 采样源是首页自己那一层背景，所以透出来的是壁纸而不是组件自己（会成环）。
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun HomeTileFaceBox(
    face: HomeServiceRow,
    w: Float,
    h: Float,
    liquid: Boolean,
    refraction: Boolean,
    backdrop: Backdrop?,
    /**
     * 这一张卡**采不采背景**：只有「自定义图片、且没被模糊程度糊平」才值得采。
     *
     * 纯色 / 渐变背景上模糊采到的就是那片纯色，`lens` 也没东西可折，`vibrancy` 更是无事可做；
     * 壁纸糊到 5dp 以上同理（壁纸已经是一片色块）。这三种情况走 `sampling = false`：**不采样、
     * 不建离屏层、`effects` 一概不跑**，但高光与投影仍由库自己画（同一份 [Highlight] / [Shadow]），
     * 所以观感与采样版一模一样——这一档本来就是为省性能（开关在 vendored 的 
     * `thirdparty/backdrop` 里，见它的 README）。**不铺底色**：卡片完全透明，只剩轮廓高光与外面那圈投影——2026-11 维护者定「玻璃片本身该是透的，只要铺白就像蒙了一层白」，要染色的是角标（`CountBadge`），不是卡片。
     *
     * 这条只管「背景上有没有细节」——没有用户可拨的开关，渲染侧自己判断（见下）。
     */
    blurGlass: Boolean = true,
    /**
     * 卡片圆角（dp），**已由调用方按「短边的一半」截断**（见 [HomeTileItem]）。
     *
     * 之所以在这里直接收现成值：本层只看得到「占几格」，看不到一格实际多少 dp，
     * 算不出「拖到顶就是圆饼」需要的那个上限。
     */
    cornerRadius: Dp = 22.dp,
    /** 明细行能不能点：只有常规态的真身可以（编辑态、拖拽幽灵不行）。 */
    interactive: Boolean = false,
    modifier: Modifier = Modifier,
    /** 实时尺寸用于切换卡面形态；只在跨越形态阈值时重组。 */
    liveSpan: (() -> Offset)? = null,
) {
    val dark = LocalIsDarkTheme.current
    val ink = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val radius = cornerRadius
    val fill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f)
    val iconColor = if (dark) lerp(face.color, Color.White, 0.4f) else face.color
    // 液态玻璃的填充要比毛玻璃更透：折射与高光是它的主角，填浓了就只剩一张白卡。
    // **只给采样档用**：不采样那一档不铺任何底色（完全透明，只留高光 + 投影）——2026-11 维护者
    // 第三次真机反馈定下的：只要铺白，那块玻璃就「像蒙了一层白」，玻璃片本身该是透的；
    // 想要染色的是角标（`CountBadge` 用主题色半透明底），卡片不染。
    // 玻璃的暗部只应该出现在外面那圈投影里（`floatingGlassShadow`）。
    val liquidFill = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.34f)
    // 柔光：顶部一抹白光往下淡出，玻璃才有「被光照着」的厚度
    val softLight = remember(dark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.10f else 0.26f),
            0.55f to Color.White.copy(alpha = 0f),
        )
    }
    // 柔光玻璃（液态玻璃开着时）：**不走 drawBackdrop**。每张卡一个 drawBackdrop 就是一块离屏层 +
    // 一遍着色器高光 + 一个模糊投影，滚动时每帧整套重画（真机：GPU 8–10ms 超出 120Hz 的 8.3ms 预算，
    // 显存里几十块卡片大小的离屏层）。这里改成直接画：
    // - 有壁纸细节：把首页**已经糊好**的背景副本（tileBackdrop）按卡片当前位置裁成圆角贴上来，
    //   只是一次贴图，不开离屏层；
    // - 没有（纯色 / 渐变 / 壁纸已被糊平）：不贴背景，卡片是透的；
    // - 光感：顶部一层白光往下淡出 + 一圈渐变细描边；投影用系统原生阴影（不开离屏层）。
    val sample = backdrop?.takeIf { refraction && blurGlass }
    val cardShape = remember(radius) { RoundedCornerShape(radius) }
    val shadowTint = MiuixTheme.colorScheme.primary.copy(alpha = if (dark) 0.18f else 0.14f)
    // 卡片不带投影：卡面是半透明的，系统投影（.shadow）整块画在卡片底下，透过卡面能看见
    // 投影内圈那块没被压暗的直角矩形，像每张卡里垫了块白板
    val material = when {
        liquid -> Modifier
            .softGlass(sample, radius, fill = if (sample != null) liquidFill else Color.Transparent, softLight = softLight, dark = dark)
        else -> Modifier
            .squircleClip(radius)
            .background(fill)
            .squircleBorder(0.7.dp, hairline, radius)
    }

    // 明细第一条和状态是同一句（通知的状态就是最新一条标题）时，状态让给明细列表，免得一句话画两遍
    val stat = face.stat?.takeIf { s -> s.isNotBlank() && face.lines.none { it.text == s } }
    val lines = face.lines
    val onLine = face.onLink.takeIf { interactive }
    // 状态短到 8 个字以内、又不是「一句话型」的功能，才当大字数据排（¥128.50、12 周、3 门）；
    // 其余是一句话（「今日暂无空教室」），压成大字就只剩个糊字
    val figure = stat != null && stat.length <= FIGURE_MAX_CHARS && face.key !in LIST_TILES
    // 数据的颜色：只有「出事了」才换色，其余一律跟正文走——大字本身已经够抢眼了
    val statColor = when (face.tone) {
        HomeTone.ALERT -> if (dark) Color(0xFFFFB4AB) else MiuixTheme.colorScheme.error
        HomeTone.ACCENT -> face.color
        HomeTone.NORMAL, HomeTone.MUTED -> ink
    }

    Box(modifier.then(material)) {
        // 形态按**实时**格数选（§10）：拖动把手缩放时逐帧判断，不等松手才重排。
        // 判据就是「几格宽 × 几格高」，阈值取半格（1.5）：一格见方是小块，一格高是横条，
        // 一格宽是竖条，两格都到是大方块——弹簧动画经过阈值也不会来回抖。
        val shape by remember(w, h, liveSpan) {
            derivedStateOf {
                val span = liveSpan?.invoke() ?: Offset(w, h)
                when {
                    span.x < 1.5f && span.y < 1.5f -> HomeTileShape.Icon
                    span.y < 1.5f -> if (span.x >= 2.5f) HomeTileShape.WideXL else HomeTileShape.Wide
                    span.x < 1.5f -> HomeTileShape.Tall
                    span.x < 2.5f && span.y < 2.5f -> HomeTileShape.Large
                    span.y < 2.5f -> HomeTileShape.Panorama
                    span.x < 2.5f -> HomeTileShape.Tower
                    else -> HomeTileShape.Board
                }
            }
        }
        // 形态或内容变了都淡入淡出：旧层画的是当时的内容，数字刷新不会瞬间跳变
        androidx.compose.animation.core.updateTransition(shape to face, label = "tileFace").Crossfade(
            animationSpec = tween(240),
            contentKey = { (s, f) -> listOf(s, f.stat, f.statDetail, f.lines) },
        ) { (now, face) ->
            when (now) {
                // 1×1：图标在上、名称在下——正方格的一边长 ~76dp，这两行正好放得下，
                // 所以这一档不再分「有没有文字空间」，永远画名称
                HomeTileShape.Icon -> Column(
                    Modifier.fillMaxSize().padding(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ExpressiveIcon(
                        icon = face.icon,
                        color = iconColor,
                        size = (if (liveSpan == null) (minOf(w, h) * 34f).coerceIn(28f, 38f) else 34f).dp,
                        iconSize = (if (liveSpan == null) (minOf(w, h) * 19f).coerceIn(15f, 21f) else 19f).dp,
                        modifier = if (liveSpan == null) Modifier else Modifier.graphicsLayer {
                            val span = liveSpan()
                            val scale = (minOf(span.x, span.y) * 34f).coerceIn(28f, 38f) / 34f
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        face.title,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }

                // 横条（2×1 起）：图标在左，右边是「小号名称 + 主要数据 + 一行说明」
                HomeTileShape.Wide -> Row(
                    Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExpressiveIcon(icon = face.icon, color = iconColor, size = 30.dp, iconSize = 17.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            face.title,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            // 状态让给了明细（通知的状态就是最新一条）时，横条上还是给那一条，不退回静态副标题
                            face.stat?.takeIf { it.isNotBlank() } ?: lines.firstOrNull()?.text ?: face.subtitle,
                            style = if (figure) {
                                Editorial.figure.copy(fontSize = 19.sp, lineHeight = 23.sp)
                            } else {
                                MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp)
                            },
                            color = statColor,
                            maxLines = if (figure || !face.statDetail.isNullOrBlank()) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 说明只跟着大字数据走：一句话型的状态本身就是完整的一句，再加一行反而碎。
                        // 没有说明时拿明细第一条补上（课表大字「5 门课」下面就是下一节）
                        val wideDetail = face.statDetail?.takeIf { it.isNotBlank() } ?: lines.firstOrNull()?.text
                        if (figure && wideDetail != null) {
                            Text(
                                wideDetail,
                                fontSize = 9.5.sp,
                                lineHeight = 12.sp,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 竖条（1×2 起）：名称压小、数据放大，剩下的高度列明细。
                // 只有一格宽，长句（通知标题、作业名）在这里只剩省略号：没有大字数据、或明细都是长句的功能
                // 不硬塞内容，竖条当成一个大入口——图标、名称，加一枚「几条」的数
                HomeTileShape.Tall -> if (!figure && (lines.isEmpty() || lines.any { it.text.length > TALL_LINE_MAX_CHARS })) {
                    HomeTileEntry(face, iconColor, ink, muted, count = lines.size)
                } else {
                    Column(Modifier.fillMaxSize().padding(10.dp)) {
                        ExpressiveIcon(icon = face.icon, color = iconColor, size = 25.dp, iconSize = 14.dp)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            face.title,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (stat != null) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                stat,
                                style = if (figure) {
                                    Editorial.figure.copy(fontSize = 19.sp, lineHeight = 24.sp)
                                } else {
                                    MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp)
                                },
                                color = statColor,
                                maxLines = if (figure) 1 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!face.statDetail.isNullOrBlank()) {
                                Text(
                                    face.statDetail,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    color = muted,
                                    maxLines = if (figure && lines.isEmpty()) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (lines.isNotEmpty() && lines.none { it.text.length > TALL_LINE_MAX_CHARS }) {
                            Spacer(Modifier.height(6.dp))
                            TileLines(lines, ink, muted, onLine, compact = true, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 大方块（2×2 起）：右上角压一枚几乎看不见的水印图标，数据放大一档，剩下的高度列明细
                HomeTileShape.Large -> if (stat == null && lines.isEmpty()) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 42.dp, iconGlyph = 24.dp)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        Icon(
                            face.icon,
                            contentDescription = null,
                            tint = ink.copy(alpha = 0.055f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(64.dp),
                        )
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Text(
                                face.title,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (stat != null) {
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    stat,
                                    style = if (figure) {
                                        Editorial.figure.copy(fontSize = 26.sp, lineHeight = 31.sp)
                                    } else {
                                        MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 17.sp)
                                    },
                                    color = statColor,
                                    maxLines = if (figure || lines.isNotEmpty()) 1 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!face.statDetail.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        face.statDetail,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        color = muted,
                                        maxLines = if (figure || lines.isNotEmpty()) 1 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (lines.isNotEmpty()) {
                                Spacer(Modifier.height(if (stat != null) 8.dp else 6.dp))
                                TileLines(lines, ink, muted, onLine, compact = false, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // 长横条（3×1 起）·列表型（通知、作业、空教室没有大字时）：明细就是内容本身，
                // 让它占满整条宽度、一条一行，左边只留图标和名称；对半分会把每条标题都切成省略号
                HomeTileShape.WideXL -> if (!figure && lines.isNotEmpty()) Row(
                    Modifier.fillMaxSize().padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ExpressiveIcon(icon = face.icon, color = iconColor, size = 30.dp, iconSize = 17.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(face.title, fontSize = 10.sp, lineHeight = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    TileLines(lines, ink, muted, onLine, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                } else Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveIcon(icon = face.icon, color = iconColor, size = 32.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    TileHero(face, stat ?: face.subtitle.takeIf { it.isNotBlank() }, figure, statColor, muted, figureSize = 20, bodySize = 12.5f, bodyLines = 2, detailLines = 1, modifier = Modifier.weight(1f))
                    if (lines.isNotEmpty()) {
                        TileDivider(vertical = true, color = muted)
                        TileLines(lines, ink, muted, onLine, compact = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }

                // 宽卡（3×2 起）：左半边大字数据 + 说明，右半边明细带说明
                HomeTileShape.Panorama -> if (stat == null && lines.isEmpty()) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 46.dp, iconGlyph = 26.dp)
                } else {
                    Row(Modifier.fillMaxSize().padding(16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TileHeader(face, iconColor, muted)
                            Spacer(Modifier.weight(1f))
                            TileHero(face, stat, figure, statColor, muted, figureSize = 30, bodySize = 14f, bodyLines = 3, detailLines = 2)
                        }
                        if (lines.isNotEmpty()) {
                            TileDivider(vertical = true, color = muted)
                            TileLinesRich(lines, ink, muted, onLine, columns = 1, modifier = Modifier.weight(1.2f).fillMaxHeight())
                        }
                    }
                }

                // 高卡（2×3 起）：数据再大一号，下面两行一条的明细铺满剩下的高度
                HomeTileShape.Tower -> if (stat == null && lines.isEmpty()) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 52.dp, iconGlyph = 28.dp)
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        TileHeader(face, iconColor, muted)
                        Spacer(Modifier.height(12.dp))
                        TileHero(face, stat, figure, statColor, muted, figureSize = 32, bodySize = 14f, bodyLines = 3, detailLines = 2)
                        if (lines.isNotEmpty()) {
                            TileDivider(vertical = false, color = muted)
                            TileLinesRich(lines, ink, muted, onLine, columns = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 大板（3×3 起）：数据最大，明细收在内嵌底色里；四格宽时分两栏
                HomeTileShape.Board -> if (stat == null && lines.isEmpty()) {
                    HomeTileCentered(face, iconColor, ink, muted, iconSize = 60.dp, iconGlyph = 32.dp)
                } else {
                    // 按落定的格数分栏（拖动缩放时不逐帧重组）
                    val wideBoard = w >= 3.5f
                    Column(Modifier.fillMaxSize().padding(18.dp)) {
                        TileHeader(face, iconColor, muted)
                        Spacer(Modifier.height(14.dp))
                        TileHero(face, stat, figure, statColor, muted, figureSize = 40, bodySize = 15f, bodyLines = 3, detailLines = 2)
                        if (lines.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            TileLinesRich(
                                lines, ink, muted, onLine,
                                columns = if (wideBoard) 2 else 1,
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(ink.copy(alpha = 0.04f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 大卡里的明细列表（今天的课、最近几笔、常用电话……）：按剩余高度能放几行就放几行，
 * 不画半截。带链接的一行可点（编辑态与幽灵块上 [onLine] 为 null）。
 */
@Composable
private fun TileLines(
    lines: List<HomeLine>,
    ink: Color,
    muted: Color,
    onLine: ((String) -> Unit)?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val rowHeight = if (compact) 16.dp else 20.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fit = (maxHeight / rowHeight).toInt()
        Column {
            lines.take(fit).forEach { line ->
                val link = line.link
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .then(
                            if (link != null && onLine != null) {
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = SinkFeedback(),
                                        onClick = { onLine(link) },
                                    )
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(4.dp).background(muted.copy(alpha = 0.55f), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        line.text,
                        fontSize = if (compact) 10.sp else 11.5.sp,
                        lineHeight = if (compact) 13.sp else 15.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!compact && !line.detail.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(line.detail, fontSize = 10.sp, lineHeight = 13.sp, color = muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 大卡的抬头：小图标 + 名称。 */
@Composable
private fun TileHeader(face: HomeServiceRow, iconColor: Color, muted: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExpressiveIcon(icon = face.icon, color = iconColor, size = 26.dp, iconSize = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            face.title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 主数据 + 说明。短数据（[figure]）按 [figureSize] 的大字排，一句话型按 [bodySize] 正文排，
 * 和小卡同一条判据，所以同一个功能在任何尺寸下强调的都是同一件事。
 */
@Composable
private fun TileHero(
    face: HomeServiceRow,
    stat: String?,
    figure: Boolean,
    statColor: Color,
    muted: Color,
    figureSize: Int,
    bodySize: Float,
    bodyLines: Int,
    detailLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (stat != null) {
            Text(
                stat,
                style = if (figure) {
                    Editorial.figure.copy(fontSize = figureSize.sp, lineHeight = (figureSize * 1.2f).sp)
                } else {
                    MiuixTheme.textStyles.body2.copy(fontSize = bodySize.sp, lineHeight = (bodySize * 1.35f).sp)
                },
                fontWeight = if (figure) null else FontWeight.Medium,
                color = statColor,
                maxLines = if (figure) 1 else bodyLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!face.statDetail.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                face.statDetail,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = muted,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 数据区和明细区之间的一道细线。 */
@Composable
private fun TileDivider(vertical: Boolean, color: Color) {
    if (vertical) {
        Box(Modifier.padding(horizontal = 12.dp).fillMaxHeight().width(0.5.dp).background(color.copy(alpha = 0.22f)))
    } else {
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(0.5.dp).background(color.copy(alpha = 0.22f)))
    }
}

/**
 * 大卡的明细：一条两行（正文 + 说明），按剩余高度能放几行放几行，不画半截。
 * [columns] 为 2 时按行两两排开（四格宽的大板）。带链接的一条可点。
 */
@Composable
private fun TileLinesRich(
    lines: List<HomeLine>,
    ink: Color,
    muted: Color,
    onLine: ((String) -> Unit)?,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val anyDetail = lines.any { !it.detail.isNullOrBlank() }
    val rowHeight = if (anyDetail) 40.dp else 26.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rows = (maxHeight / rowHeight).toInt().coerceAtLeast(0)
        Column {
            lines.take(rows * columns).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { line ->
                        val link = line.link
                        Row(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (link != null && onLine != null) {
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = SinkFeedback(),
                                                onClick = { onLine(link) },
                                            )
                                    } else {
                                        Modifier
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(5.dp).background(muted.copy(alpha = 0.55f), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(line.text, fontSize = 12.5.sp, lineHeight = 16.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!line.detail.isNullOrBlank()) {
                                    Text(line.detail, fontSize = 10.5.sp, lineHeight = 14.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    // 最后一行不满时补位，别让单条撑满两栏
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 柔光玻璃的直接画法（不开离屏层）：圆角裁切里贴 [frost]（已糊好的背景，按这张卡此刻的位置取那一块），
 * 再铺 [fill] 与 [softLight]，最后一圈从左上亮到右下淡的细描边。
 *
 * 卡片一动（滚动、拖拽补位）背景那一块就得跟着换：位置变化时只让这一张卡**重画**（不重组、不重排），
 * 画的只是一次贴图加几笔，比 drawBackdrop 那一整套便宜得多。
 */
@Composable
private fun Modifier.softGlass(
    frost: Backdrop?,
    radius: Dp,
    fill: Color,
    softLight: Brush,
    dark: Boolean,
): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val moved = remember { mutableIntStateOf(0) }
    val lastOffset = remember { arrayOfNulls<Offset>(1) }
    val rim = remember(dark) {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.28f else 0.85f),
            0.5f to Color.White.copy(alpha = if (dark) 0.08f else 0.25f),
            1f to Color.White.copy(alpha = if (dark) 0.16f else 0.55f),
        )
    }
    return this
        .then(
            if (frost != null) {
                Modifier.onGloballyPositioned {
                    coords[0] = it
                    // 只有相对背景真的动了才重画：整页缩放时这里每帧回调，但取的那块背景没变
                    val offset = frost.sampleOffset(it)
                    val previous = lastOffset[0]
                    if (previous == null || offset.movedFrom(previous)) {
                        lastOffset[0] = offset
                        moved.intValue++
                    }
                }
            } else {
                Modifier
            },
        )
        .drawWithCache {
            val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
            val outline = RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))
            val clip = if (frost != null) Path().apply { addRoundRect(outline) } else null
            val stroke = 0.8.dp.toPx()
            val strokeStyle = Stroke(stroke)
            onDrawBehind {
                if (frost != null) {
                    moved.intValue // 位置变了就重画这一块
                    clipPath(requireNotNull(clip)) {
                        with(frost) { drawBackdrop(this@onDrawBehind, coords[0]) }
                    }
                }
                if (fill.alpha > 0f) drawRoundRect(fill, cornerRadius = CornerRadius(r))
                drawRoundRect(softLight, cornerRadius = CornerRadius(r))
                drawRoundRect(
                    brush = rim,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((r - stroke / 2f).coerceAtLeast(0f)),
                    style = strokeStyle,
                )
            }
        }
}

/**
 * 卡片内容的形态，由**实时**格数决定（见 [HomeTileFaceBox] 里的判据）。
 *
 * - [Icon] 1×1、[Wide] 2×1、[Tall] 1×2 起、[Large] 2×2：小组件模式那四档；
 * - [WideXL] 3×1 起：横条右边再列两条明细；
 * - [Panorama] 3×2 起：左边大字数据，右边明细带说明；
 * - [Tower] 2×3 起：数据再大一号，下面两行一条的明细铺满；
 * - [Board] 3×3 起：数据最大，明细在内嵌底色里，四格宽时分两栏。
 */
private enum class HomeTileShape { Icon, Wide, WideXL, Tall, Large, Panorama, Tower, Board }

/**
 * 没有状态的功能（社区、黄页这类）：居中一列「图标 + 名称 + 说明」。
 *
 * 这类卡片没数据可放大，「留白 + 居中」本身就是它的版式；字号仍按大块比小块大一号。
 */
@Composable
private fun HomeTileCentered(
    face: HomeServiceRow,
    iconColor: Color,
    ink: Color,
    muted: Color,
    iconSize: Dp,
    iconGlyph: Dp,
) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveIcon(icon = face.icon, color = iconColor, size = iconSize, iconSize = iconGlyph)
        Spacer(Modifier.height(8.dp))
        Text(
            face.title,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (face.subtitle.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                face.subtitle,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 一格宽的竖条当入口用：图标放大居中，名称在下，有明细时再给一枚「N 条」。
 * 竖条窄到放不下一句话，与其列一排省略号，不如让人一眼认出是什么、点进去看。
 */
@Composable
private fun HomeTileEntry(face: HomeServiceRow, iconColor: Color, ink: Color, muted: Color, count: Int) {
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveIcon(icon = face.icon, color = iconColor, size = 40.dp, iconSize = 22.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            face.title,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (count > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "$count 条",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = iconColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(iconColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else if (face.subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(face.subtitle, fontSize = 10.sp, lineHeight = 13.sp, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** 一格宽的竖条里一行明细最多放这么多字，再长就只剩省略号，竖条改当入口（见 [HomeTileEntry]）。 */
private const val TALL_LINE_MAX_CHARS = 6

/** 状态不超过这么多字才当大号数字排（¥128.50、12 周、3 门），再长就是一句话。 */
private const val FIGURE_MAX_CHARS = 8

/**
 * 状态天生是一句话、不适合当大字数字排的功能（空教室「今日暂无空教室」、通知「3 条未读」）。
 *
 * 名单与「小组件模式」那一套保持一致；用路由 id 而不是字符串字面量，改名时会一起变。
 */
private val LIST_TILES = setOf(
    AppRoute.Lms().id,
    AppRoute.Community.id,
    AppRoute.Games.id,
    AppRoute.Agent.id,
    AppRoute.Notification.id,
    AppRoute.YellowPage.id,
)
