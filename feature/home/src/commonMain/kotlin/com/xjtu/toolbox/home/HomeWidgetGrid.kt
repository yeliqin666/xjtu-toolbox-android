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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
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
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.settings.HomeLayoutRevision
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.components.encodeHeroImage
import com.xjtu.toolbox.ui.components.rememberHeroImagePicker
import com.xjtu.toolbox.ui.theme.Editorial
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 小组件模式的首页功能区：四列正方格，每个功能可选 1×1 / 2×1 / 1×2 / 2×2，各尺寸放各自真实的内容。
 * 长按进入编辑：再长按一格才能拖（普通上下滑照常滚动），右下角换尺寸、左上角移除、末尾「添加」找回。
 * 材质二选一：液态玻璃（[wall] 是整页壁纸的采样层）或白色奶满（[wall] 为 null）。
 */
@Composable
internal fun HomeWidgetGrid(
    rows: List<HomeServiceRow>,
    features: (String) -> List<String>,
    wall: Backdrop?,
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
    fun save(next: List<HomeWidgetSpec>) {
        widgets = next
        store.homeWidgets = HomeWidgetLayout.encode(next)
    }
    val glass = wall != null

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            EditToolbar(
                store = store,
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
            features = features,
            editing = editing,
            wall = wall,
            ink = if (glass) ink else MiuixTheme.colorScheme.onSurface,
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
                        WidgetIcon(r, 32.dp, glass = false)
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

/** 编辑态顶上那一排：材质、壁纸、复原、完成。材质和壁纸改完通知整页重读（壁纸铺在整页背后）。 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun EditToolbar(store: SettingsStore, ink: Color, onReset: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val glass = store.homeWidgetMaterial == SettingsStore.WIDGET_MATERIAL_GLASS
    val customWall = store.homeWallpaperImage.isNotEmpty()
    val pickImage = rememberHeroImagePicker { bytes ->
        scope.launch {
            val encoded = withContext(Dispatchers.Default) {
                decodeImageSize(bytes)?.let { decodeImage(bytes, it, maxDim = 1600) }?.let { encodeHeroImage(it) }
            }
            if (encoded != null) {
                store.homeWallpaperImage = Base64.Default.encode(encoded)
                HomeLayoutRevision.bump()
            }
        }
    }
    val muted = ink.copy(alpha = 0.7f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilterChip(selected = glass, onClick = {
                store.homeWidgetMaterial = SettingsStore.WIDGET_MATERIAL_GLASS; HomeLayoutRevision.bump()
            }, label = "液态玻璃")
            AppFilterChip(selected = !glass, onClick = {
                store.homeWidgetMaterial = SettingsStore.WIDGET_MATERIAL_PAPER; HomeLayoutRevision.bump()
            }, label = "白色奶满")
            Spacer(Modifier.weight(1f))
            Text("复原", fontSize = 13.sp, color = muted,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(horizontal = 6.dp, vertical = 4.dp))
            Text("完成", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        if (glass) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("壁纸", fontSize = 13.sp, color = muted)
                AppFilterChip(selected = !customWall, onClick = {
                    store.homeWallpaperImage = ""; HomeLayoutRevision.bump()
                }, label = "主题渐变")
                AppFilterChip(selected = customWall, onClick = pickImage, label = if (customWall) "换一张图" else "选图片")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("长按一格可拖动排序", fontSize = 11.sp, color = muted)
    }
}

/**
 * 整页壁纸：没选图时是主题色铺开的大渐变（很浅，正文和底栏照常清楚）；选了图就铺图片，
 * 顶部和底部各压一段暗色，刊头白字和底栏都看得清。
 */
@Composable
internal fun HomeWallpaper(imageData: String, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier) {
        if (imageData.isNotEmpty()) {
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
            val warm = if (dark) Color(0xFF6B3F8F) else Color(0xFFFFC7B2)
            val cool = if (dark) Color(0xFF1E4D6B) else Color(0xFFB9E3F2)
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(
                        listOf(
                            lerp(surface, primary, if (dark) 0.30f else 0.22f),
                            lerp(surface, warm, if (dark) 0.30f else 0.38f),
                            lerp(surface, cool, if (dark) 0.28f else 0.42f),
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
    features: (String) -> List<String>,
    editing: Boolean,
    wall: Backdrop?,
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
        val shown = widgets.filter { it.routeId in byId }
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
                    val raw = (dragOrigin + dragDelta).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                    val pos = remember { Animatable(target, IntOffset.VectorConverter) }
                    LaunchedEffect(target, isDragged) {
                        if (isDragged) return@LaunchedEffect
                        if (pos.value != target) pos.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                    }
                    val width by animateDpAsState(w, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "w")
                    val heightAnim by animateDpAsState(h, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "h")
                    val lift by animateFloatAsState(if (isDragged) 1.05f else 1f, label = "lift")
                    val wobble = if (editing && !isDragged) rememberWobble(index) else 0f

                    Box(
                        Modifier
                            .offset { if (isDragged) raw else pos.value }
                            .zIndex(if (isDragged) 2f else 0f)
                            .size(width, heightAnim)
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
                                features = features(row.key),
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
                                onResize = {
                                    onReorder(widgets.map { if (it.routeId == row.key) it.copy(size = it.size.next()) else it })
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

/** 一格小组件：底（玻璃或纸面）+ 按尺寸排的内容，尺寸变化时内容淡入淡出。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WidgetTile(
    row: HomeServiceRow,
    size: WidgetSize,
    features: List<String>,
    wall: Backdrop?,
    ink: Color,
    editing: Boolean,
    paymentCode: suspend () -> String?,
    onLink: (String) -> Unit,
    onLongClick: () -> Unit,
) {
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(TILE_RADIUS)
    val surface = if (wall != null) Modifier.glassTile(wall, ink) else Modifier.paperTile()
    // 非状态字段：拖动/缩放/滚动时 onGloballyPositioned 每帧回调，写 State 会把每个格子每帧重组一次。
    val tileBounds = remember { com.xjtu.toolbox.nav.CardBoundsRef() }
    Box(
        Modifier
            .fillMaxSize()
            .expandOriginSource(origin)
            .onGloballyPositioned { tileBounds.value = it.boundsInWindow() }
            .then(surface)
            .clip(shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                enabled = !editing,
                onClick = {
                    // 先登记 Compose 起点：KIT 走独立 Activity 时会被 KitOneTake 清掉，回落时正好用它做放大转场。
                    origin.arm(row.key, TILE_RADIUS, density, null)
                    val seamless = row.onSeamlessClick
                    if (seamless != null && !tileBounds.value.isEmpty) {
                        seamless(SeamlessCardSource(tileBounds.value, TILE_RADIUS))
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
                PaymentCodeContent(row, ink, wall != null, live = !editing, paymentCode = paymentCode)
            } else {
                WidgetContent(row, s, ink, wall != null, onLink = if (editing) null else onLink)
            }
        }
    }
}

/**
 * 各尺寸的内容，按「这一格最该说什么」排：
 * - 状态是短数字（¥8.49、3 门、第 9 周）才放大成主角；是一句话（通知标题、作业名）就当正文排，不硬撑大字。
 * - 有明细时，横条放第一条，竖条放两三条，大格放三四条；带链接的明细能直接点开（通知原文、黄页拨号）。
 * - 都没有时，只放名字和一句说明，不拿页内功能名凑数。
 */
@Composable
private fun WidgetContent(row: HomeServiceRow, size: WidgetSize, ink: Color, glass: Boolean, onLink: ((String) -> Unit)?) {
    val muted = ink.copy(alpha = 0.62f)
    val stat = row.stat?.takeIf { it.isNotBlank() }
    val figure = stat != null && stat.length <= FIGURE_MAX_CHARS
    val alert = row.tone == HomeTone.ALERT
    val figureColor = when {
        alert -> if (glass) Color(0xFFFF8A80) else MiuixTheme.colorScheme.error
        glass -> ink
        else -> lerp(row.color, ink, if (LocalIsDarkTheme.current) 0.45f else 0.15f)
    }
    // 状态本身就是第一条明细时（通知：标题既是状态也是列表头）不重复
    val lines = row.lines.filter { it.text != stat }
    when (size) {
        WidgetSize.SMALL -> Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WidgetIcon(row, 30.dp, glass)
                Spacer(Modifier.height(5.dp))
                Text(row.title, fontSize = 11.sp, lineHeight = 13.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 短数字放得下就露出来，一格也有信息
                if (figure) Text(stat!!, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = figureColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (alert) Box(
                Modifier.align(Alignment.TopEnd).padding(9.dp).size(7.dp).background(Color(0xFFFF5A4E), CircleShape),
            )
        }
        WidgetSize.WIDE -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            TitleRow(row, ink, glass, iconSize = 20.dp, fontSize = 12)
            Spacer(Modifier.weight(1f))
            when {
                figure -> {
                    Text(stat!!, style = Editorial.figure.copy(fontSize = 19.sp, lineHeight = 22.sp), color = figureColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (row.statDetail ?: lines.firstOrNull()?.text)?.let {
                        Text(it, fontSize = 10.5.sp, lineHeight = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                stat != null -> Text(stat, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                    color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.isNotEmpty() -> LineList(lines.take(2), ink, onLink, fontSize = 11.5f, rules = false)
                else -> Text(row.subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        WidgetSize.TALL -> Column(Modifier.fillMaxSize().padding(11.dp)) {
            WidgetIcon(row, 26.dp, glass)
            Spacer(Modifier.height(6.dp))
            Text(row.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            if (stat != null && !figure) {
                Text(stat, fontSize = 11.5.sp, lineHeight = 15.sp, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            if (lines.isNotEmpty()) LineList(lines.take(if (figure) 2 else 3), ink, onLink, fontSize = 10.5f, rules = false)
            else if (stat == null) Text(row.subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (figure) {
                Text(stat!!, style = Editorial.figure.copy(fontSize = 17.sp, lineHeight = 20.sp), color = figureColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.statDetail?.let { Text(it, fontSize = 9.5.sp, lineHeight = 12.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        WidgetSize.LARGE -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            TitleRow(row, ink, glass, iconSize = 24.dp, fontSize = 13)
            Spacer(Modifier.height(10.dp))
            when {
                figure -> {
                    Text(stat!!, style = Editorial.figure.copy(fontSize = 24.sp, lineHeight = 28.sp), color = figureColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.statDetail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 11.sp, lineHeight = 14.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 一句话的状态：明细里已经有它（通知）就直接列明细，否则当一段正文
                stat != null && lines.isEmpty() -> {
                    Text(stat, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = ink,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    row.statDetail?.let { Text(it, fontSize = 11.sp, lineHeight = 14.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                lines.isEmpty() -> Text(row.subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (lines.isNotEmpty()) {
                val shownLines = if (figure) lines.take(3) else (listOfNotNull(stat?.let { HomeLine(it, row.lines.firstOrNull { l -> l.text == it }?.link) }) + lines).take(4)
                LineList(shownLines, ink, onLink, fontSize = 11.5f, rules = true)
            }
        }
    }
}

/** 明细列表：带链接的一行可以点（编辑时不可点），大格里行间加细线。 */
@Composable
private fun LineList(lines: List<HomeLine>, ink: Color, onLink: ((String) -> Unit)?, fontSize: Float, rules: Boolean) {
    val rule = ink.copy(alpha = 0.10f)
    Column {
        lines.forEachIndexed { i, l ->
            if (rules && i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(rule))
            val link = l.link?.takeIf { onLink != null }
            Text(
                l.text,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 3.5f).sp,
                color = ink.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (link != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable { onLink!!(link) } else Modifier)
                    .padding(vertical = if (rules) 4.dp else 1.5.dp),
            )
        }
    }
}

@Composable
private fun TitleRow(row: HomeServiceRow, ink: Color, glass: Boolean, iconSize: Dp, fontSize: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WidgetIcon(row, iconSize, glass)
        Spacer(Modifier.width(7.dp))
        Text(row.title, fontSize = fontSize.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.85f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 2×2 付款码：直接画二维码，停在首页时每 30 秒自动换一张；拿不到（没登录校园卡）就退回普通内容。 */
@Composable
private fun PaymentCodeContent(row: HomeServiceRow, ink: Color, glass: Boolean, live: Boolean, paymentCode: suspend () -> String?) {
    val code by produceState<String?>(null, live) {
        while (live) {
            value = runCatching { paymentCode() }.getOrNull() ?: value
            delay(PAYMENT_REFRESH_MS)
        }
    }
    val matrix = remember(code) { code?.let { qrCodeModules(it) } }
    if (matrix == null) {
        WidgetContent(row.copy(lines = listOf(HomeLine("点开出示付款码"))), WidgetSize.LARGE, ink, glass, onLink = null)
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
private fun WidgetIcon(row: HomeServiceRow, size: Dp, glass: Boolean) {
    val dark = LocalIsDarkTheme.current
    val bg = if (glass) Color.White.copy(alpha = 0.24f) else row.color.copy(alpha = if (dark) 0.18f else 0.11f)
    val tint = if (glass) Color.White else if (dark) lerp(row.color, Color.White, 0.4f) else row.color
    Box(Modifier.size(size).background(bg, RoundedCornerShape(size * 0.32f)), contentAlignment = Alignment.Center) {
        Icon(row.icon, null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** 编辑态的角标：左上移除、右下换尺寸，弹入弹出。放在格子内侧：伸出格子的部分收不到点击。 */
@Composable
private fun BoxScope.EditBadges(
    visible: Boolean,
    onRemove: () -> Unit,
    onResize: () -> Unit,
) {
    val enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) + fadeIn()
    val exit = scaleOut() + fadeOut()
    AnimatedVisibility(visible, Modifier.align(Alignment.TopStart).padding(5.dp), enter = enter, exit = exit) {
        Badge(Icons.Filled.Remove, "移除", onRemove)
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomEnd).padding(5.dp), enter = enter, exit = exit) {
        Badge(Icons.Filled.OpenInFull, "换尺寸", onResize)
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

/** 纸面格子：与分类卡同一套白卡（深色下是卡片色），细描边。 */
@Composable
private fun Modifier.paperTile(): Modifier {
    val dark = LocalIsDarkTheme.current
    val border = MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.065f)
    return this
        .clip(RoundedCornerShape(TILE_RADIUS))
        .background(AppCardColor)
        .border(0.6.dp, border, RoundedCornerShape(TILE_RADIUS))
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val GAP = 10.dp
private val TILE_RADIUS = 20.dp
private const val ADD_KEY = "__add__"
private const val PAYMENT_REFRESH_MS = 30_000L

/** 状态不超过这么多字才当大号数字排（¥128.50、3 门新成绩），再长就是一句话。 */
private const val FIGURE_MAX_CHARS = 8
