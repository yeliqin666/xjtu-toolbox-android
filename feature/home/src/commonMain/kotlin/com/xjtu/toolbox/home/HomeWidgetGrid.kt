package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.settings.HomeLayoutRevision
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppColorPicker
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.floatingGlassShadow
import com.xjtu.toolbox.ui.rememberCampusNow
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 小组件模式的首页功能区：按组排，每组四列正方格，组内按顺序自动排紧凑；每个功能可选
 * 1×1 / 2×1 / 1×2 / 2×2，各尺寸放各自真实的内容。
 * 长按进入编辑：再长按一格才能拖（普通上下滑照常滚动），拖到别的组就挪过去；拖右下角缩放、
 * 左上角移除、组末「添加」找回，点组名改名、改色、调顺序。
 */
@Composable
internal fun HomeWidgetGrid(
    rows: List<HomeServiceRow>,
    /** 没编辑过时的默认分组。 */
    defaults: List<HomeWidgetGroup>,
    /** 不画的组（「显示常用功能」关掉时的常用功能组）。 */
    hiddenGroups: Set<String>,
    surface: WidgetSurface,
    ink: Color,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 首页的滚动状态：拖到屏幕边缘时自动滚动用；null = 不自动滚。 */
    scroll: ScrollState? = null,
    /** 编辑态工具条上的「主页设置」。 */
    onOpenSettings: (() -> Unit)? = null,
) {
    val store = remember { SettingsStore() }
    val byId = rows.associateBy { it.key }
    val revision = HomeLayoutRevision.current
    var groups by remember(revision, byId.keys, defaults) {
        mutableStateOf(store.homeWidgets?.let { HomeWidgetLayout.decodeGroups(it, byId.keys) } ?: defaults)
    }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing || adding != null) {
        if (adding != null) adding = null else editing = false
    }
    fun save(next: List<HomeWidgetGroup>) {
        groups = next
        store.homeWidgets = HomeWidgetLayout.encodeGroups(next)
    }

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            EditToolbar(
                ink = ink,
                onReset = {
                    store.homeWidgets = null
                    groups = defaults
                },
                onSettings = onOpenSettings,
                onDone = { editing = false },
            )
        }
        WidgetCanvas(
            groups = groups,
            hidden = hiddenGroups,
            byId = byId,
            editing = editing,
            surface = surface,
            ink = ink,
            paymentCode = paymentCode,
            onLink = onLink,
            onEdit = { editing = true },
            onChange = ::save,
            onAdd = { adding = it },
            onEditGroup = { editingGroup = it },
            scroll = scroll,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        AnimatedVisibility(visible = editing, enter = fadeIn(), exit = fadeOut()) {
            AddGroupRow(ink) {
                val id = "custom-" + kotlin.random.Random.nextInt(100000, 999999)
                save(groups + HomeWidgetGroup(id, "新分组"))
                editingGroup = id
            }
        }
    }

    adding?.let { groupId ->
        val group = groups.firstOrNull { it.id == groupId }
        val hidden = rows.filter { r -> group?.widgets?.none { it.routeId == r.key } != false }
        WindowDialog(show = true, title = "添加小组件", onDismissRequest = { adding = null }) {
            Column(Modifier.fillMaxWidth()) {
                // weight：平板横屏弹窗被限高时先压列表，关闭按钮不被裁掉
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (hidden.isEmpty()) {
                        Text("所有功能都已经在这一组里了", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    hidden.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                val size = if (groupId == HomeWidgetLayout.QUICK_GROUP || r.stat == null) WidgetSize.SMALL else WidgetSize.WIDE
                                save(groups.map { if (it.id == groupId) it.copy(widgets = it.widgets + HomeWidgetSpec(r.key, size)) else it })
                                adding = null
                            }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WidgetIcon(r, 32.dp, MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MiuixTheme.textStyles.body1)
                                Text(r.subtitle, style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Filled.Add, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(text = "关闭", onClick = { adding = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    editingGroup?.let { id ->
        val index = groups.indexOfFirst { it.id == id }
        val group = groups.getOrNull(index) ?: return@let
        GroupEditDialog(
            group = group,
            canMoveUp = index > 0,
            canMoveDown = index < groups.lastIndex,
            onDismiss = { editingGroup = null },
            onConfirm = { title, hex ->
                save(groups.map { if (it.id == id) it.copy(title = title, colorHex = hex) else it })
                editingGroup = null
            },
            onMove = { delta ->
                save(groups.toMutableList().apply { add((index + delta).coerceIn(0, lastIndex), removeAt(index)) })
            },
            onDelete = {
                save(groups.filterNot { it.id == id })
                editingGroup = null
            },
        )
    }
}

@Composable
private fun EditToolbar(ink: Color, onReset: () -> Unit, onSettings: (() -> Unit)?, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("拖右下角缩放 · 长按拖动排序", fontSize = 11.sp, color = ink.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            Text("复原", fontSize = 13.sp, color = ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(8.dp))
            onSettings?.let {
                Text("设置", fontSize = 13.sp, color = ink,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = it).padding(8.dp))
            }
            Text("完成", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(8.dp))
        }
    }
}

/**
 * 整页壁纸：没选图时是主题色铺开的大渐变（很浅，正文和底栏照常清楚）；选了图就铺图片，
 * 顶部和底部各压一段暗色，刊头白字和底栏都看得清。
 */
@Composable
internal fun HomeWallpaper(mode: String, imageData: String, modifier: Modifier = Modifier, blur: Int = 0, brightness: Int = 100) {
    val dark = LocalIsDarkTheme.current
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier) {
        if (mode == "solid") {
            Box(Modifier.matchParentSize().background(lerp(surface, primary, if (dark) 0.10f else 0.06f)))
        } else if (imageData.isNotEmpty()) {
            HeroCustomBackground(imageData = imageData, modifier = Modifier.matchParentSize(), blur = blur, brightness = brightness)
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.3f to Color.Black.copy(alpha = 0.12f),
                        0.75f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                ),
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(
                        listOf(
                            lerp(surface, primary, if (dark) 0.30f else 0.22f),
                            lerp(surface, primary, if (dark) 0.18f else 0.10f),
                            lerp(surface, primary, if (dark) 0.08f else 0.04f),
                            surface,
                        ),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
            )
        }
    }
}

/**
 * 小组件的底。滚动时谁都不跑实时模糊，只有用户自己打开的液态玻璃例外：
 * - [Plain]：纯色背景上用实心卡片，不做玻璃；
 * - [Tint]：渐变背景上半透明底直接透出渐变（渐变本身就柔和，模糊它看不出差别）；
 * - [Frost]：图片背景上贴预先模糊好的低清图，按小组件在页面上的位置取对应那一块；
 * - [Liquid]：液态玻璃，实时采样壁纸层，带折射与高光。
 */
@Immutable
internal sealed interface WidgetSurface {
    data object Plain : WidgetSurface
    data object Tint : WidgetSurface
    // 按值比较：首页每次重组都会新建一份，引用不同但内容相同时格子要能跳过重组
    @Immutable data class Frost(val image: ImageBitmap, val filter: ColorFilter?, val wall: WallFrame) : WidgetSurface
    @Immutable data class Liquid(val wall: Backdrop) : WidgetSurface
}

/** 壁纸在根坐标里的位置与尺寸，[WidgetSurface.Frost] 按它找每块小组件底下对应的那一块。 */
@Stable
internal class WallFrame {
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
}

/** 画布上排好的一块：[key] = `组id/路由id`（同一功能可以出现在几个组里），添加格是 `ADD_KEY/组id`。 */
private class CanvasItem(val key: String, val group: String, val placed: PlacedWidget, val top: Float) {
    val isAdd get() = placed.spec.routeId == ADD_KEY
}

/** 画布本体：所有组排在一张画布上，算格子尺寸、排位，并管拖动（跨组拖动也在这一层）。 */
@Composable
private fun WidgetCanvas(
    groups: List<HomeWidgetGroup>,
    hidden: Set<String>,
    byId: Map<String, HomeServiceRow>,
    editing: Boolean,
    surface: WidgetSurface,
    ink: Color,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    onEdit: () -> Unit,
    onChange: (List<HomeWidgetGroup>) -> Unit,
    onAdd: (String) -> Unit,
    onEditGroup: (String) -> Unit,
    scroll: ScrollState?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = HomeWidgetLayout.COLUMNS
        val cell = (maxWidth - GAP * (cols - 1)) / cols
        val density = LocalDensity.current
        val step = with(density) { (cell + GAP).toPx() }
        val gapPx = with(density) { GAP.toPx() }
        val headerPx = with(density) { GROUP_HEADER.toPx() }
        val groupGapPx = with(density) { GROUP_GAP.toPx() }
        var resizing by remember { mutableStateOf<String?>(null) }
        var resizeSize by remember { mutableStateOf(WidgetSize.SMALL) }
        var resizeOrigin by remember { mutableStateOf(IntOffset.Zero) }
        var resizeStart by remember { mutableStateOf(Offset.Zero) }
        var resizeDelta by remember { mutableStateOf(Offset.Zero) }

        // 逐组排：标题行（编辑态或有标题时）+ 组内自动排的格子；编辑时每组末尾多一个「添加」格
        val visible = groups.filter { g -> g.id !in hidden && (editing || g.widgets.any { it.routeId in byId }) }
        val headers = mutableListOf<Pair<HomeWidgetGroup, Float>>()
        val items = mutableListOf<CanvasItem>()
        var y = 0f
        visible.forEach { g ->
            if (editing || g.title.isNotBlank()) {
                headers += g to y
                y += headerPx
            }
            val shown = g.widgets.filter { it.routeId in byId }.map {
                if ("${g.id}/${it.routeId}" == resizing) it.copy(size = resizeSize) else it
            }
            val placed = HomeWidgetLayout.pack(if (editing) shown + HomeWidgetSpec(ADD_KEY, WidgetSize.SMALL) else shown)
            val top = y
            placed.forEach { p ->
                items += CanvasItem(if (p.spec.routeId == ADD_KEY) "$ADD_KEY/${g.id}" else "${g.id}/${p.spec.routeId}", g.id, p, top)
            }
            val rowCount = HomeWidgetLayout.rowCount(placed)
            if (rowCount > 0) y += rowCount * step - gapPx
            y += groupGapPx
        }
        val totalPx = (y - groupGapPx).coerceAtLeast(0f)
        val height = animateDpAsState(
            with(density) { totalPx.toDp() },
            spring(stiffness = Spring.StiffnessMediumLow), label = "widgetGridHeight",
        )
        var dragging by remember { mutableStateOf<String?>(null) }
        // 手指记在根坐标里：被拖的格子 = 手指 − 网格原点 − 抓取点。页面自动滚动时网格原点在变，
        // 格子自然跟住手指；也不逐次累加位移，免得缩放、摆动这些视觉变换把位移放大或吃掉
        var fingerRoot by remember { mutableStateOf(Offset.Zero) }
        var grab by remember { mutableStateOf(Offset.Zero) }
        var gridRoot by remember { mutableStateOf(Offset.Zero) }
        var dragSize by remember { mutableStateOf(Offset.Zero) }
        // 上一次换位的目标：换完它挪走、别的格子滑到手指下时不会立刻再换，避免来回抖
        var lastHover by remember { mutableStateOf<String?>(null) }
        val haptic = LocalHapticFeedback.current
        // 手势只在进入编辑时装一次，里面读到的分组和排位得是最新的
        val latestGroups by rememberUpdatedState(groups)
        val latestItems by rememberUpdatedState(items)
        val dragPos = { fingerRoot - gridRoot - grab }

        fun rectOf(item: CanvasItem) = Rect(
            Offset(item.placed.col * step, item.top + item.placed.row * step),
            Size(item.placed.spec.size.cols * step - gapPx, item.placed.spec.size.rows * step - gapPx),
        )

        fun routeOf(key: String) = key.substringAfter('/')

        fun updateHover() {
            val id = dragging ?: return
            val center = dragPos() + dragSize / 2f
            val hover = latestItems.firstOrNull { it.key != id && rectOf(it).contains(center) }
            if (hover == null) { lastHover = null; return }
            if (hover.key == lastHover) return
            lastHover = hover.key
            val from = id.substringBefore('/')
            val route = routeOf(id)
            val target = latestGroups.firstOrNull { it.id == hover.group } ?: return
            val toIndex = if (hover.isAdd) target.widgets.size else target.widgets.indexOfFirst { it.routeId == hover.placed.spec.routeId }
            if (toIndex < 0) return
            val next = HomeWidgetLayout.move(latestGroups, from, route, hover.group, toIndex)
            if (next != latestGroups) {
                onChange(next)
                dragging = "${hover.group}/$route"
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        fun resizeTo(key: String, size: WidgetSize) {
            val group = key.substringBefore('/')
            val route = routeOf(key)
            onChange(latestGroups.map { g ->
                if (g.id != group) g else g.copy(widgets = g.widgets.map { if (it.routeId == route) it.copy(size = size) else it })
            })
        }

        // 拖到屏幕上下边缘时自动滚动：越靠边越快
        val windowHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toFloat()
        LaunchedEffect(dragging != null, scroll) {
            val page = scroll ?: return@LaunchedEffect
            if (dragging == null) return@LaunchedEffect
            val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
            val maxStep = with(density) { AUTO_SCROLL_MAX_STEP.toPx() }
            while (true) {
                androidx.compose.runtime.withFrameNanos { }
                val fy = fingerRoot.y
                val delta = when {
                    fy < edge -> -(1f - (fy / edge).coerceIn(0f, 1f)) * maxStep
                    fy > windowHeight - edge -> ((fy - (windowHeight - edge)) / edge).coerceIn(0f, 1f) * maxStep
                    else -> 0f
                }
                if (delta != 0f && page.scrollBy(delta) != 0f) updateHover()
            }
        }

        // 编辑态一个手势层管所有格子：落在某格右下角把手热区 → 越过触摸斜坡就缩放；
        // 其余位置要长按才拖，普通上下滑照常交给页面滚动
        val gestures = if (editing) Modifier.pointerInput(cell) {
            val handle = RESIZE_HANDLE_TOUCH.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val hit = latestItems.lastOrNull { !it.isAdd && rectOf(it).contains(down.position) }
                    ?: return@awaitEachGesture
                val rect = rectOf(hit)
                if (down.position.x >= rect.right - handle && down.position.y >= rect.bottom - handle) {
                    val start = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                    resizing = hit.key
                    resizeSize = hit.placed.spec.size
                    resizeOrigin = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
                    resizeStart = Offset(rect.width, rect.height)
                    resizeDelta = Offset.Zero
                    val done = drag(start.id) { c ->
                        resizeDelta += c.position - c.previousPosition
                        val next = WidgetSize.fromExtent((resizeStart.x + resizeDelta.x + gapPx) / step,
                            (resizeStart.y + resizeDelta.y + gapPx) / step)
                        if (next != resizeSize) {
                            resizeSize = next
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        c.consume()
                    }
                    if (done) resizeTo(hit.key, resizeSize)
                    resizing = null
                } else {
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    grab = press.position - rect.topLeft
                    dragSize = Offset(rect.width, rect.height)
                    fingerRoot = gridRoot + press.position
                    lastHover = null
                    dragging = hit.key
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag(press.id) { c ->
                        fingerRoot = gridRoot + c.position
                        updateHover()
                        c.consume()
                    }
                    dragging = null
                }
            }
        } else Modifier

        Box(
            Modifier.fillMaxWidth()
                // 高度动画只在布局阶段读，不让整张画布跟着逐帧重组
                .layout { measurable, constraints ->
                    val h = height.value.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .onGloballyPositioned { gridRoot = it.positionInRoot() }
                .then(gestures),
        ) {
            headers.forEach { (g, top) ->
                key("header/${g.id}") {
                    Box(
                        Modifier.offset { IntOffset(0, top.roundToInt()) }.fillMaxWidth().height(GROUP_HEADER)
                            .enterOnce(visible.indexOf(g) + 1),
                    ) {
                        GroupHeader(g, editing, ink, onEdit = { onEditGroup(g.id) })
                    }
                }
            }
            items.forEach { item ->
                val p = item.placed
                key(item.key) {
                    val target = IntOffset((p.col * step).roundToInt(), (item.top + p.row * step).roundToInt())
                    val w = cell * p.spec.size.cols + GAP * (p.spec.size.cols - 1)
                    val h = cell * p.spec.size.rows + GAP * (p.spec.size.rows - 1)
                    val isDragged = dragging == item.key
                    val isResizing = resizing == item.key
                    val pos = remember { Animatable(target, IntOffset.VectorConverter) }
                    // 拖动中每帧把位置同步给 pos，松手后从手指处弹回格子
                    LaunchedEffect(target, isDragged) {
                        if (isDragged) {
                            snapshotFlow { dragPos() }.collect { pos.snapTo(IntOffset(it.x.roundToInt(), it.y.roundToInt())) }
                        } else if (pos.value != target) {
                            pos.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    // 动画值和缩放手势都只在布局 / 绘制阶段读：读在组合里，每一帧都会让整张画布重组
                    val width = animateDpAsState(w, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "w")
                    val heightAnim = animateDpAsState(h, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "h")
                    val lift by animateFloatAsState(if (isDragged) 1.05f else 1f, label = "lift")
                    val shake = rememberShake(editing && !isDragged && !isResizing && !item.isAdd, item.key)

                    Box(
                        Modifier
                            .offset { if (isResizing) resizeOrigin else pos.value }
                            .zIndex(if (isDragged || isResizing) 2f else 0f)
                            .layout { measurable, _ ->
                                val (wPx, hPx) = if (isResizing) {
                                    val min = cell.toPx()
                                    val max = (cell * 2 + GAP).toPx()
                                    (resizeStart.x + resizeDelta.x).coerceIn(min, max).roundToInt() to
                                        (resizeStart.y + resizeDelta.y).coerceIn(min, max).roundToInt()
                                } else {
                                    width.value.roundToPx() to heightAnim.value.roundToPx()
                                }
                                val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                                layout(wPx, hPx) { placeable.place(0, 0) }
                            }
                            // 分组错峰入场；摆动只改绘制层，不触发重组
                            .enterOnce(visible.indexOfFirst { it.id == item.group } + 1)
                            .graphicsLayer { scaleX = lift; scaleY = lift; rotationZ = shake() },
                    ) {
                        if (item.isAdd) {
                            AddTile(ink) { onAdd(item.group) }
                        } else {
                            val row = byId.getValue(p.spec.routeId)
                            WidgetTile(
                                row = row,
                                size = p.spec.size,
                                surface = surface,
                                ink = ink,
                                editing = editing,
                                paymentCode = paymentCode,
                                onLink = onLink,
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() },
                            )
                            EditBadges(
                                visible = editing && !isDragged,
                                onRemove = {
                                    onChange(latestGroups.map { g ->
                                        if (g.id == item.group) g.copy(widgets = g.widgets.filterNot { it.routeId == row.key }) else g
                                    })
                                },
                                // 把手只画记号，手势在网格那一层按热区判定；读屏可直接切尺寸
                                resizeModifier = Modifier.semantics {
                                    customActions = WidgetSize.entries.map { size ->
                                        CustomAccessibilityAction(size.key) { resizeTo(item.key, size); true }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组标题：常规态只有标题；编辑态点一下打开分组设置，没标题的显示占位。 */
@Composable
private fun GroupHeader(group: HomeWidgetGroup, editing: Boolean, ink: Color, onEdit: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    val color = CourseColors.parseHex(group.colorHex)?.let { if (dark) lerp(it, Color.White, 0.25f) else it } ?: ink
    Row(
        Modifier.fillMaxSize()
            .then(if (editing) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "分组设置", onClick = onEdit) else Modifier)
            .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            group.title.ifBlank { "未命名分组" },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (group.title.isBlank()) ink.copy(alpha = 0.45f) else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editing) Icon(Icons.Filled.Edit, "分组设置", tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
    }
}

/** 分组设置：标题、标题颜色（色板 + Hex）、上下移动、删除。 */
@Composable
private fun GroupEditDialog(
    group: HomeWidgetGroup,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, colorHex: String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(group.id) { mutableStateOf(group.title) }
    // 关 = 跟随主题（存空串），开 = 自己挑一个色
    var addColor by remember(group.id) { mutableStateOf(group.colorHex.isNotEmpty()) }
    val defaultTint = MiuixTheme.colorScheme.primary
    var picked by remember(group.id) { mutableStateOf(CourseColors.parseHex(group.colorHex) ?: defaultTint) }
    // Hex 输入框是草稿：解析得出来才同步给 picked，敲到一半的无效值只拦住「确定」
    var hex by remember(group.id) { mutableStateOf(CourseColors.toHex(picked)) }
    var confirmDelete by remember(group.id) { mutableStateOf(false) }
    WindowDialog(show = true, title = "分组设置", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(20) },
                    label = "分组标题（留空则不显示）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义标题颜色", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = addColor, onCheckedChange = { on ->
                        addColor = on
                        if (on) hex = CourseColors.toHex(picked)
                    })
                }
                if (addColor) {
                    Spacer(Modifier.height(10.dp))
                    AppColorPicker(
                        color = picked,
                        onColorChanged = { picked = it; hex = CourseColors.toHex(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = hex,
                        onValueChange = { text -> hex = text; CourseColors.parseHex(text)?.let { picked = it } },
                        label = "Hex 色值（#RRGGBB）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (confirmDelete) {
                Text("这一组和里面的 ${group.widgets.size} 个小组件会一起删掉，可在编辑态点「复原」找回默认布局。",
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { confirmDelete = false }, modifier = Modifier.weight(1f))
                    TextButton(text = "删除", onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "上移", onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.weight(1f))
                    TextButton(text = "下移", onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.weight(1f))
                    TextButton(text = "删除", onClick = { confirmDelete = true }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(title.trim(), if (addColor) CourseColors.toHex(picked) else "") },
                    enabled = !addColor || CourseColors.parseHex(hex) != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 编辑态最下面的「新建分组」。 */
@Composable
private fun AddGroupRow(ink: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(TILE_RADIUS))
            .border(1.2.dp, ink.copy(alpha = 0.3f), RoundedCornerShape(TILE_RADIUS))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, tint = ink.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("新建分组", fontSize = 14.sp, color = ink.copy(alpha = 0.85f))
    }
}

/**
 * 编辑态的慢摆：2.4 秒一周、±0.9°，相位按 id 错开，各格朝不同方向。
 * 只在 [active] 时挂无限动画；退出编辑直接归零，不做复位动画。返回值在绘制阶段读。
 */
@Composable
private fun rememberShake(active: Boolean, id: String): () -> Float {
    if (!active) return { 0f }
    val phase = remember(id) { ((id.hashCode() % 360 + 360) % 360) / 360f * 2f * kotlin.math.PI.toFloat() }
    val angle by rememberInfiniteTransition(label = "shake").animateFloat(
        initialValue = 0f, targetValue = 2f * kotlin.math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(SHAKE_PERIOD_MS, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shakeAngle",
    )
    return { kotlin.math.sin(angle + phase) * SHAKE_DEGREES }
}

/** 尺寸变化时重排内容，文字和背景共用同一套对比度规则。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WidgetTile(
    row: HomeServiceRow,
    size: WidgetSize,
    surface: WidgetSurface,
    ink: Color,
    editing: Boolean,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    onLongClick: () -> Unit,
) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .expandOriginSource(origin)
            .widgetSurface(surface, ink)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = !editing,
                onClick = {
                    // 先登记 Compose 起点：KIT 走独立 Activity 时会被 KitOneTake 清掉，回落时正好用它做放大转场。
                    origin.arm(row.key, TILE_RADIUS, density, null)
                    val seamless = row.onSeamlessClick
                    val bounds = origin.boundsInWindow()
                    if (seamless != null && bounds != null) {
                        seamless(SeamlessCardSource(bounds, TILE_RADIUS))
                    } else {
                        row.onClick()
                    }
                },
                onLongClick = onLongClick,
                onLongClickLabel = "编辑小组件",
            ),
    ) {
        Crossfade(size, animationSpec = tween(220), label = "widgetContent") { s ->
            if (row.key == AppRoute.PaymentCode.id && s == WidgetSize.LARGE) {
                PaymentCodeContent(row, ink, live = !editing, paymentCode = paymentCode)
            } else {
                WidgetContent(row, s, ink, onLink = if (editing) null else onLink)
            }
        }
    }
}

@Composable
private fun WidgetContent(row: HomeServiceRow, size: WidgetSize, ink: Color, onLink: ((String) -> Unit)?) {
    if (size == WidgetSize.TALL || size == WidgetSize.LARGE) {
        if (row.stat.isNullOrBlank() && row.lines.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                WidgetIcon(row, if (size == WidgetSize.LARGE) 42.dp else 30.dp, ink)
                Spacer(Modifier.height(8.dp))
                Text(row.title, fontSize = 11.sp, lineHeight = 14.sp, color = ink, maxLines = 2)
                Spacer(Modifier.height(5.dp))
                Text(row.subtitle, fontSize = 10.sp, lineHeight = 14.sp, color = ink.copy(alpha = 0.65f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            return
        }
        if (row.key == AppRoute.Games.id && row.lines.isNotEmpty()) {
            GamesWidgetContent(row, size, ink, onLink)
            return
        }
        if (row.key == AppRoute.Fitness.id && row.stat != null) {
            FitnessWidgetContent(row, size, ink, onLink)
            return
        }
    }
    val muted = ink.copy(alpha = 0.68f)
    val stat = row.stat?.takeIf { it.isNotBlank() }
    val figure = stat != null && stat.length <= FIGURE_MAX_CHARS && row.key !in LIST_WIDGETS
    val figureColor = when {
        row.tone == HomeTone.ALERT -> if (ink.luminanceApprox() > 0.5f) Color(0xFFFFB4AB) else MiuixTheme.colorScheme.error
        else -> ink
    }
    val lines = row.lines.distinctBy { it.text }
    val actions = row.key == AppRoute.Agent.id
    when (size) {
        WidgetSize.SMALL -> Column(
            Modifier.fillMaxSize().padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (figure) {
                Text(stat!!, style = Editorial.figure.copy(fontSize = 19.sp, lineHeight = 23.sp), color = figureColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else WidgetIcon(row, 34.dp, ink)
            Spacer(Modifier.height(4.dp))
            Text(row.title, fontSize = 10.5.sp, lineHeight = 13.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WidgetSize.WIDE -> Row(
            Modifier.fillMaxSize().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WidgetIcon(row, 30.dp, ink)
            Column(Modifier.weight(1f)) {
                Text(row.title, fontSize = 10.sp, lineHeight = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(stat ?: lines.firstOrNull()?.text ?: row.subtitle,
                    style = if (figure) Editorial.figure.copy(fontSize = 19.sp, lineHeight = 23.sp)
                        else MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = figureColor, maxLines = if (figure || lines.firstOrNull()?.detail != null) 1 else 2, overflow = TextOverflow.Ellipsis)
                (if (figure) row.statDetail else lines.firstOrNull()?.detail)?.let {
                    Text(it, fontSize = 9.5.sp, lineHeight = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        WidgetSize.TALL, WidgetSize.LARGE -> {
            val large = size == WidgetSize.LARGE
            Box(Modifier.fillMaxSize()) {
                if (large) Icon(row.icon, null, tint = ink.copy(alpha = 0.055f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(64.dp))
                Column(Modifier.fillMaxSize().padding(if (large) 14.dp else 10.dp)) {
                    if (row.key == AppRoute.SchoolCalendar.id) {
                        Box(Modifier.width(26.dp).height(3.dp).background(figureColor, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.height(7.dp))
                    } else if (!large) {
                        WidgetIcon(row, 25.dp, ink)
                        Spacer(Modifier.height(7.dp))
                    }
                    Text(if (row.key == AppRoute.CampusCard.id) "校园卡 · 余额" else row.title,
                        fontSize = 11.sp, lineHeight = 14.sp, color = muted, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(7.dp))
                    if (!figure && large) row.statDetail?.let {
                        Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                    }
                    if (figure) {
                        Text(stat, style = Editorial.figure.copy(fontSize = if (large) 26.sp else 19.sp,
                            lineHeight = if (large) 31.sp else 24.sp), color = figureColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.statDetail?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 10.5.sp, lineHeight = 14.sp, color = muted, maxLines = if (large) 1 else 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                    val detailLines = when {
                        lines.isNotEmpty() -> lines
                        !figure && stat != null -> listOf(HomeLine(stat))
                        !figure -> listOf(HomeLine(row.subtitle))
                        else -> emptyList()
                    }
                    if (detailLines.isNotEmpty()) {
                        FittedLines(detailLines, ink, onLink, actions, timeline = row.key == AppRoute.Schedule.id,
                            wrap = !large || row.key == AppRoute.Community.id,
                            modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentCodeContent(row: HomeServiceRow, ink: Color, live: Boolean, paymentCode: suspend () -> String?) {
    val now = rememberCampusNow()
    val active = live && LocalPageVisible.current && isMealPaymentTime(now.time)
    val code by produceState<String?>(null, active) {
        value = null
        while (active) {
            value = try { paymentCode() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            delay(PAYMENT_REFRESH_MS)
        }
    }
    val matrix = remember(code, active) { code?.takeIf { active }?.let { qrCodeModules(it) } }
    if (matrix == null) {
        WidgetContent(row.copy(lines = listOf(HomeLine("点按出示付款码"))), WidgetSize.LARGE, ink, onLink = null)
        return
    }
    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            CodeMatrixView(matrix, quietZoneModules = 1, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(5.dp))
        Text("付款码 · 自动刷新", fontSize = 10.sp, color = ink.copy(alpha = 0.65f), maxLines = 1)
    }
}

@Composable
private fun WidgetIcon(row: HomeServiceRow, size: Dp, ink: Color) {
    val dark = LocalIsDarkTheme.current
    val lightInk = ink.luminanceApprox() > 0.5f
    val bg = if (lightInk) Color.White.copy(alpha = 0.20f) else row.color.copy(alpha = 0.14f)
    val tint = if (lightInk) Color.White else if (dark) lerp(row.color, Color.White, 0.35f) else row.color
    Box(Modifier.size(size).background(bg, RoundedCornerShape(size * 0.32f)), contentAlignment = Alignment.Center) {
        Icon(row.icon, null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** 角标位于卡片内侧，保证拖动触点可达。 */
@Composable
private fun BoxScope.EditBadges(
    visible: Boolean,
    onRemove: () -> Unit,
    resizeModifier: Modifier,
) {
    val enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) + fadeIn()
    val exit = scaleOut() + fadeOut()
    AnimatedVisibility(visible, Modifier.align(Alignment.TopStart).padding(5.dp), enter = enter, exit = exit) {
        Badge(Icons.Filled.Remove, "移除", onRemove)
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
        // 两道斜杠像「可拉伸」的角标；热区（RESIZE_HANDLE_TOUCH）由网格手势层判定
        val grip = MiuixTheme.colorScheme.primary
        Box(resizeModifier.size(RESIZE_HANDLE_TOUCH).semantics { contentDescription = "拖动调整大小" }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(13.dp)) {
                val side = size.width
                val stroke = side * 0.16f
                drawLine(grip, Offset(side * 0.22f, side * 0.95f), Offset(side * 0.95f, side * 0.22f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(grip, Offset(side * 0.22f, side * 0.48f), Offset(side * 0.48f, side * 0.22f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(Color(0xF02B2B2E))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}

/** 编辑态每组末尾的「添加」格：描边加号；拖着别的格子落在这里 = 挪到这一组末尾。 */
@Composable
private fun AddTile(ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(TILE_RADIUS))
            .border(1.2.dp, ink.copy(alpha = 0.35f), RoundedCornerShape(TILE_RADIUS))
            .clickable(onClickLabel = "添加小组件", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, "添加小组件", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
    }
}

/** 按 [surface] 画小组件的底并裁成圆角；底色按文字颜色反推（白字压暗、黑字提亮），对比度有保证。 */
@Composable
private fun Modifier.widgetSurface(surface: WidgetSurface, ink: Color): Modifier {
    val shape = RoundedCornerShape(TILE_RADIUS)
    val dark = LocalIsDarkTheme.current
    val lightInk = ink.luminanceApprox() > 0.5f
    val tint = if (lightInk) Color.Black.copy(alpha = 0.24f) else Color.White.copy(alpha = if (dark) 0.10f else 0.55f)
    val edge = if (lightInk || dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.7f)
    return when (surface) {
        WidgetSurface.Plain -> this
            .then(if (dark) Modifier else Modifier.appCardShadow(shape = shape))
            .clip(shape)
            .background(AppCardColor)
            .border(0.6.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.065f), shape)
        WidgetSurface.Tint -> this.clip(shape).background(tint).border(0.7.dp, edge, shape)
        is WidgetSurface.Frost -> this.clip(shape).then(FrostElement(surface, tint)).border(0.7.dp, edge, shape)
        is WidgetSurface.Liquid -> {
            val accent = MiuixTheme.colorScheme.primary
            val shadow = remember(accent, dark) { floatingGlassShadow(accent, dark) }
            this then remember(surface.wall, tint, shadow) {
                Modifier.drawBackdrop(
                    backdrop = surface.wall,
                    shape = { shape },
                    effects = {
                        // 采样范围往外扩一圈，折射在轮廓边上也有真实内容可混
                        padding = maxOf(padding, 12.dp.toPx())
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.65f) },
                    shadow = { shadow },
                    onDrawSurface = { drawRect(tint) },
                )
            } then Modifier.clip(shape)
        }
    }
}

private data class FrostElement(val frost: WidgetSurface.Frost, val tint: Color) : ModifierNodeElement<FrostNode>() {
    override fun create() = FrostNode(frost, tint)
    override fun update(node: FrostNode) {
        node.frost = frost
        node.tint = tint
        node.invalidateDraw()
    }
}

/**
 * 磨砂底：贴低清模糊壁纸里正好在这块底下的那一片。位置一变就在布局回调里直接重画，
 * 和这一帧的滚动同步；不经快照状态，不会晚一帧（底图跟着漂），也不会每帧多写几十次状态。
 */
private class FrostNode(var frost: WidgetSurface.Frost, var tint: Color) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var root = Offset.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val now = coordinates.positionInRoot()
        if (now != root) {
            root = now
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val image = frost.image
        val wall = frost.wall.size
        if (wall.width > 0 && wall.height > 0) {
            // 和壁纸同一种 Crop 取景，再平移到这块小组件的位置
            val scale = max(wall.width.toFloat() / image.width, wall.height.toFloat() / image.height)
            val dx = (wall.width - image.width * scale) / 2f + frost.wall.origin.x - root.x
            val dy = (wall.height - image.height * scale) / 2f + frost.wall.origin.y - root.y
            drawImage(
                image,
                dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
                dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
                colorFilter = frost.filter,
                filterQuality = FilterQuality.Low,
            )
        }
        drawRect(tint)
        drawContent()
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val GAP = 10.dp
private val GROUP_HEADER = 38.dp
private val GROUP_GAP = 18.dp
private val RESIZE_HANDLE_TOUCH = 44.dp
private val AUTO_SCROLL_EDGE = 104.dp
private val AUTO_SCROLL_MAX_STEP = 14.dp
private const val SHAKE_PERIOD_MS = 2400
private const val SHAKE_DEGREES = 0.9f
private val TILE_RADIUS = 20.dp
private const val ADD_KEY = "__add__"
private const val PAYMENT_REFRESH_MS = 30_000L

/** 状态不超过这么多字才当大号数字排（¥128.50、3 门新成绩），再长就是一句话。 */
private const val FIGURE_MAX_CHARS = 8

private val LIST_WIDGETS = setOf(AppRoute.EmptyRoom.id, AppRoute.Lms().id, AppRoute.Community.id, AppRoute.Games.id, AppRoute.Agent.id, AppRoute.Notification.id, AppRoute.YellowPage.id)
