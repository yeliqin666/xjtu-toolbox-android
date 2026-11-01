package com.xjtu.toolbox.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.settings.HomeLayoutRevision
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 小组件模式的首页功能区：四列正方格，每个功能可选 1×1 / 2×1 / 1×2 / 2×2，各尺寸放各自真实的内容。
 * 长按进入编辑：再长按一格才能拖（普通上下滑照常滚动），拖右下角缩放、左上角移除、末尾「添加」找回。
 * 背景在设置中选择，小组件统一使用随正文配色的玻璃表面。
 */
@Composable
internal fun HomeWidgetGrid(
    rows: List<HomeServiceRow>,
    wall: Backdrop,
    ink: Color,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = remember { SettingsStore() }
    val byId = rows.associateBy { it.key }
    val revision = HomeLayoutRevision.current
    var widgets by remember(revision, byId.keys) {
        mutableStateOf(
            store.homeWidgets?.let { HomeWidgetLayout.decode(it, byId.keys) }
                ?: HomeWidgetLayout.defaults(rows.map { it.key }) { byId[it]?.stat != null },
        )
    }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = editing || adding) {
        if (adding) adding = false else editing = false
    }
    fun save(next: List<HomeWidgetSpec>) {
        widgets = next
        store.homeWidgets = HomeWidgetLayout.encode(next)
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
                    widgets = HomeWidgetLayout.defaults(rows.map { it.key }) { byId[it]?.stat != null }
                },
                onDone = { editing = false },
            )
        }
        WidgetCanvas(
            widgets = widgets,
            byId = byId,
            editing = editing,
            wall = wall,
            ink = ink,
            paymentCode = paymentCode,
            onLink = onLink,
            onEdit = { editing = true },
            onReorder = ::save,
            onAdd = { adding = true },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (adding) {
        val hidden = rows.filter { r -> widgets.none { it.routeId == r.key } }
        WindowDialog(show = true, title = "添加小组件", onDismissRequest = { adding = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (hidden.isEmpty()) {
                    Text("所有功能都已经在首页上了", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 12.dp))
                }
                hidden.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            save(widgets + HomeWidgetSpec(r.key, if (r.stat != null) WidgetSize.WIDE else WidgetSize.SMALL))
                            adding = false
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
                Spacer(Modifier.height(8.dp))
                TextButton(text = "关闭", onClick = { adding = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EditToolbar(ink: Color, onReset: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("拖右下角缩放 · 长按拖动排序", fontSize = 11.sp, color = ink.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            Text("复原", fontSize = 13.sp, color = ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(8.dp))
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
internal fun HomeWallpaper(mode: String, imageData: String, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier) {
        if (mode == "solid") {
            Box(Modifier.matchParentSize().background(lerp(surface, primary, if (dark) 0.10f else 0.06f)))
        } else if (imageData.isNotEmpty()) {
            HeroCustomBackground(imageData = imageData, modifier = Modifier.matchParentSize(), blur = 0, brightness = 100)
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

/** 网格本体：算格子尺寸、排位，并管拖动。 */
@Composable
private fun WidgetCanvas(
    widgets: List<HomeWidgetSpec>,
    byId: Map<String, HomeServiceRow>,
    editing: Boolean,
    wall: Backdrop,
    ink: Color,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    onEdit: () -> Unit,
    onReorder: (List<HomeWidgetSpec>) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = HomeWidgetLayout.COLUMNS
        val cell = (maxWidth - GAP * (cols - 1)) / cols
        var resizing by remember { mutableStateOf<String?>(null) }
        var resizeSize by remember { mutableStateOf(WidgetSize.SMALL) }
        var resizeOrigin by remember { mutableStateOf(IntOffset.Zero) }
        var resizeStart by remember { mutableStateOf(Offset.Zero) }
        var resizeDelta by remember { mutableStateOf(Offset.Zero) }
        val shown = widgets.filter { it.routeId in byId }.map {
            if (it.routeId == resizing) it.copy(size = resizeSize) else it
        }
        // 编辑时末尾多一个「添加」格，跟着一起排
        val withAdd = if (editing) shown + HomeWidgetSpec(ADD_KEY, WidgetSize.SMALL) else shown
        val placed = remember(withAdd) { HomeWidgetLayout.pack(withAdd) }
        val rowCount = HomeWidgetLayout.rowCount(placed)
        val height by animateDpAsState(
            if (rowCount == 0) 0.dp else cell * rowCount + GAP * (rowCount - 1),
            spring(stiffness = Spring.StiffnessMediumLow), label = "widgetGridHeight",
        )
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragOrigin by remember { mutableStateOf(Offset.Zero) }
        var dragDelta by remember { mutableStateOf(Offset.Zero) }
        // 上一次换位的目标：换完它挪走、别的格子滑到手指下时不会立刻再换，避免来回抖
        var lastHover by remember { mutableStateOf<String?>(null) }
        val density = LocalDensity.current
        val step = with(density) { (cell + GAP).toPx() }
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        // 拖动手势只在开始时装一次，里面读到的列表和排位得是最新的
        val latestWidgets by rememberUpdatedState(widgets)
        val latestPlaced by rememberUpdatedState(placed)

        Box(Modifier.fillMaxWidth().height(height)) {
            placed.forEachIndexed { index, p ->
                key(p.spec.routeId) {
                    val target = IntOffset((p.col * step).roundToInt(), (p.row * step).roundToInt())
                    val w = cell * p.spec.size.cols + GAP * (p.spec.size.cols - 1)
                    val h = cell * p.spec.size.rows + GAP * (p.spec.size.rows - 1)
                    val isDragged = dragging == p.spec.routeId
                    val isResizing = resizing == p.spec.routeId
                    val raw = (dragOrigin + dragDelta).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                    val pos = remember { Animatable(target, IntOffset.VectorConverter) }
                    LaunchedEffect(target, isDragged) {
                        if (isDragged) return@LaunchedEffect
                        if (pos.value != target) pos.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                    }
                    val width by animateDpAsState(w, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "w")
                    val heightAnim by animateDpAsState(h, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "h")
                    val lift by animateFloatAsState(if (isDragged) 1.05f else 1f, label = "lift")
                    val wobble = if (editing && !isDragged && !isResizing) rememberWobble(index) else 0f
                    val resizeWidth = with(density) { (resizeStart.x + resizeDelta.x).coerceIn(cell.toPx(), (cell * 2 + GAP).toPx()).toDp() }
                    val resizeHeight = with(density) { (resizeStart.y + resizeDelta.y).coerceIn(cell.toPx(), (cell * 2 + GAP).toPx()).toDp() }

                    Box(
                        Modifier
                            .offset { if (isResizing) resizeOrigin else if (isDragged) raw else pos.value }
                            .zIndex(if (isDragged || isResizing) 2f else 0f)
                            .size(if (isResizing) resizeWidth else width, if (isResizing) resizeHeight else heightAnim)
                            .graphicsLayer { scaleX = lift; scaleY = lift; rotationZ = wobble }
                            .then(
                                // 编辑态要先长按一下才拖：普通上下滑交给页面滚动
                                if (editing && p.spec.routeId != ADD_KEY) Modifier.pointerInput(p.spec.routeId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = p.spec.routeId
                                            dragOrigin = Offset(pos.value.x.toFloat(), pos.value.y.toFloat())
                                            dragDelta = Offset.Zero
                                            lastHover = null
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            val r = dragOrigin + dragDelta
                                            // 先停在手指位置，再交给上面的 LaunchedEffect 弹回格子
                                            scope.launch {
                                                pos.snapTo(IntOffset(r.x.roundToInt(), r.y.roundToInt()))
                                                dragging = null
                                            }
                                        },
                                        onDragCancel = { dragging = null },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDelta += amount
                                            val center = dragOrigin + dragDelta + Offset(width.toPx() / 2, heightAnim.toPx() / 2)
                                            val hover = latestPlaced.firstOrNull { o ->
                                                o.spec.routeId != p.spec.routeId && o.spec.routeId != ADD_KEY &&
                                                    center.x in (o.col * step)..(o.col * step + o.spec.size.cols * step) &&
                                                    center.y in (o.row * step)..(o.row * step + o.spec.size.rows * step)
                                            }
                                            if (hover == null) lastHover = null
                                            if (hover != null && hover.spec.routeId != lastHover) {
                                                lastHover = hover.spec.routeId
                                                val list = latestWidgets
                                                val from = list.indexOfFirst { it.routeId == p.spec.routeId }
                                                val to = list.indexOfFirst { it.routeId == hover.spec.routeId }
                                                if (from >= 0 && to >= 0 && from != to) {
                                                    onReorder(list.toMutableList().apply { add(to, removeAt(from)) })
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                    )
                                } else Modifier,
                            ),
                    ) {
                        if (p.spec.routeId == ADD_KEY) {
                            AddTile(ink, onAdd)
                        } else {
                            val row = byId.getValue(p.spec.routeId)
                            WidgetTile(
                                row = row,
                                size = p.spec.size,
                                wall = wall,
                                ink = ink,
                                editing = editing,
                                paymentCode = paymentCode,
                                onLink = onLink,
                                onLongClick ={ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() },
                            )
                            EditBadges(
                                visible = editing && !isDragged,
                                onRemove = { onReorder(widgets.filterNot { it.routeId == row.key }) },
                                resizeModifier = Modifier.semantics {
                                    customActions = WidgetSize.entries.map { size ->
                                        CustomAccessibilityAction(size.key) {
                                            onReorder(latestWidgets.map { if (it.routeId == row.key) it.copy(size = size) else it })
                                            true
                                        }
                                    }
                                }.pointerInput(row.key, cell) {
                                    detectDragGestures(
                                        onDragStart = {
                                            resizing = row.key
                                            resizeSize = latestWidgets.first { it.routeId == row.key }.size
                                            resizeOrigin = pos.value
                                            resizeStart = Offset(width.toPx(), heightAnim.toPx())
                                            resizeDelta = Offset.Zero
                                        },
                                        onDragCancel = { resizing = null },
                                        onDragEnd = {
                                            onReorder(latestWidgets.map { if (it.routeId == row.key) it.copy(size = resizeSize) else it })
                                            resizing = null
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            resizeDelta += amount
                                            val next = WidgetSize.fromExtent(
                                                (resizeStart.x + resizeDelta.x + GAP.toPx()) / step,
                                                (resizeStart.y + resizeDelta.y + GAP.toPx()) / step,
                                            )
                                            if (next != resizeSize) {
                                                resizeSize = next
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 编辑时的轻微摆动：每格周期略错开，不会整齐划一地晃。 */
@Composable
private fun rememberWobble(index: Int): Float {
    val t = rememberInfiniteTransition(label = "wobble")
    val angle by t.animateFloat(
        initialValue = -0.7f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(240 + (index % 4) * 30), RepeatMode.Reverse),
        label = "wobbleAngle",
    )
    return angle
}

/** 尺寸变化时重排内容，文字和背景共用同一套对比度规则。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WidgetTile(
    row: HomeServiceRow,
    size: WidgetSize,
    wall: Backdrop,
    ink: Color,
    editing: Boolean,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    onLongClick: () -> Unit,
) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(TILE_RADIUS)
    val surface = Modifier.glassTile(wall, ink)
    Box(
        Modifier
            .fillMaxSize()
            .expandOriginSource(origin)
            .then(surface)
            .clip(shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = !editing,
                onClick = {
                    origin.arm(row.key, TILE_RADIUS, density, null)
                    row.onClick()
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

/** 2×2 付款码：直接画二维码，停在首页时每 30 秒自动换一张；拿不到（没登录校园卡）就退回普通内容。 */
@Composable
private fun PaymentCodeContent(row: HomeServiceRow, ink: Color, live: Boolean, paymentCode: suspend () -> String?) {
    val code by produceState<String?>(null, live) {
        while (live) {
            value = runCatching { paymentCode() }.getOrNull()
            delay(PAYMENT_REFRESH_MS)
        }
    }
    val matrix = remember(code) { code?.let { qrCodeModules(it) } }
    if (matrix == null) {
        WidgetContent(row.copy(lines = listOf(HomeLine("点开出示付款码"))), WidgetSize.LARGE, ink, onLink = null)
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
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomEnd).padding(5.dp), enter = enter, exit = exit) {
        Box(resizeModifier.size(36.dp), contentAlignment = Alignment.BottomEnd) {
            Icon(Icons.Filled.OpenInFull, "拖动调整大小", tint = Color.White,
                modifier = Modifier.size(24.dp).background(Color(0xF02B2B2E), CircleShape).padding(5.dp))
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

/** 编辑态末尾的「添加」格：描边加号。 */
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

/**
 * 玻璃格子：采壁纸模糊，再压一层按文字颜色反推的底色——白字压暗、黑字提亮，
 * 不管壁纸多花，字和底的对比度都有保证。
 */
@Composable
private fun Modifier.glassTile(wall: Backdrop, ink: Color): Modifier {
    val lightInk = ink.luminanceApprox() > 0.5f
    val tint = if (lightInk) Color.Black.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.55f)
    val edge = if (lightInk) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.7f)
    val drawn = remember(wall, tint) {
        Modifier.drawBackdrop(
            backdrop = wall,
            shape = { RoundedCornerShape(TILE_RADIUS) },
            effects = {
                vibrancy()
                blur(16.dp.toPx(), TileMode.Decal)
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this then drawn then Modifier.border(0.7.dp, edge, RoundedCornerShape(TILE_RADIUS))
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val GAP = 10.dp
private val TILE_RADIUS = 20.dp
private const val ADD_KEY = "__add__"
private const val PAYMENT_REFRESH_MS = 30_000L

/** 状态不超过这么多字才当大号数字排（¥128.50、3 门新成绩），再长就是一句话。 */
private const val FIGURE_MAX_CHARS = 8

private val LIST_WIDGETS = setOf(AppRoute.EmptyRoom.id, AppRoute.Lms().id, AppRoute.Community.id, AppRoute.Games.id, AppRoute.Agent.id, AppRoute.Notification.id, AppRoute.YellowPage.id)
