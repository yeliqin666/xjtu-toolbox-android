package com.xjtu.toolbox.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock

/**
 * 一个区域的平面图图片。
 *
 * 学校给的是「底图 + 每种状态一张整图」：已预约、使用中、中途离开的座位各自从对应那张图上
 * 裁自己那一块贴上去，和网页版 `/seatui` 一个画法。坐标都是底图原始像素（[origWidth]×[origHeight]），
 * 解码时为省内存做过降采样，画的时候按各图自己的比例换算。
 *
 * 从 `:app` 搬进 `:core` 时这个类一个字没改：它本来就是纯数据 + 纯算术。
 * 唯一离开的是「怎么把一张 JPEG 变成像素」——那是平台缝（`platform/ImageDecode.kt`），
 * Android 是 `BitmapFactory`、桌面/Web 是 skiko；「底图字节从哪儿来」留在了取数端口
 *（`LibrarySource.planBase` / `planTiles`，Android 那份还带着磁盘缓存）。
 */
class PlanImages(
    val base: ImageBitmap,
    val origWidth: Int,
    val origHeight: Int,
    /** 状态码 → 该状态的整图；缺了的状态退回纯色半透明覆盖。 */
    val tiles: Map<Int, ImageBitmap>,
) {
    /** 补上状态图（底图不重新解码）。 */
    fun withTiles(bytes: Map<Int, ByteArray>): PlanImages =
        PlanImages(base, origWidth, origHeight, tiles + decodeTiles(bytes, origWidth, origHeight))
}

private const val BASE_MAX_DIM = 2048
/** 状态图只用来裁小块，精度要求低，再压一半。 */
private const val TILE_MAX_DIM = 1024

/**
 * 解码平面图。底图失败返回 null（没底图就没法画）。
 *
 * 解码交给平台缝 [decodeImage]（Android = 全部用 `RGB_565`：JPEG 没有透明通道，内存砍半；
 * 一张 2048 的底图约 8MB），尺寸交给 [decodeImageSize]（只读文件头，不解像素）——
 * 尺寸必须在解码前拿到：它既用来判「这张图能不能用」，也是 [PlanImages.origWidth] 的来源。
 * 没有图片端点的端（Web，见 [LibrarySource.hasSeatPlan]）根本不会调到这里。
 */
fun decodePlanImages(base: ByteArray, tiles: Map<Int, ByteArray>): PlanImages? {
    val size = decodeImageSize(base) ?: return null
    val baseBmp = decodeImage(base, size, BASE_MAX_DIM) ?: return null
    return PlanImages(baseBmp, size.width, size.height, decodeTiles(tiles, size.width, size.height))
}

private fun decodeTiles(tiles: Map<Int, ByteArray>, origW: Int, origH: Int): Map<Int, ImageBitmap> =
    tiles.mapNotNull { (status, bytes) ->
        val size = decodeImageSize(bytes) ?: return@mapNotNull null
        // 尺寸对不上底图的状态图（比例不同）不能按比例裁，宁可不用
        val sameAspect = abs(size.width.toFloat() / size.height - origW.toFloat() / origH) < 0.02f
        if (!sameAspect) return@mapNotNull null
        decodeImage(bytes, size, TILE_MAX_DIM)?.let { status to it }
    }.toMap()

/**
 * 平面图的缩放与平移：屏幕 = 图像像素 × [scale] + offset。整层图和区域座位图共用这一份。
 *
 * 进来时和双击复位时框住 [reset] 给的那块（home）；最多能缩到整张图放下或 home 放下（取小的），
 * 最多放大到 home 的 8 倍。平移范围是「整张图 ∪ home」，所以区域框超出图片时也拖得到。
 */
@Stable
private class PlanTransform {
    var viewport by mutableStateOf(IntSize.Zero)
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    private var homeScale = 1f
    private var minScale = 1f
    // 是 state：绘制里 ready 为假就提前返回、读不到 scale/offset，得靠它在 reset 后触发重绘
    private var home by mutableStateOf(Rect.Zero)
    private var extent = Rect.Zero

    val zoomedIn get() = scale > homeScale * 1.02f
    val ready get() = viewport.width > 0 && viewport.height > 0 && home.width > 0f

    fun reset(imageW: Int, imageH: Int, home: Rect) {
        if (viewport.width == 0 || viewport.height == 0 || home.width <= 0f || home.height <= 0f) return
        this.home = home
        extent = Rect(min(0f, home.left), min(0f, home.top), max(imageW.toFloat(), home.right), max(imageH.toFloat(), home.bottom))
        homeScale = min(viewport.width / home.width, viewport.height / home.height)
        minScale = min(homeScale, min(viewport.width / extent.width, viewport.height / extent.height))
        scale = homeScale
        offsetX = viewport.width / 2f - home.center.x * homeScale
        offsetY = viewport.height / 2f - home.center.y * homeScale
    }

    fun toImage(p: Offset) = Offset((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    fun clamp() {
        fun axis(offset: Float, lo: Float, hi: Float, view: Int): Float {
            val len = (hi - lo) * scale
            return if (len <= view) (view - len) / 2f - lo * scale
            else offset.coerceIn(view - hi * scale, -lo * scale)
        }
        offsetX = axis(offsetX, extent.left, extent.right, viewport.width)
        offsetY = axis(offsetY, extent.top, extent.bottom, viewport.height)
    }

    /** 以屏幕点 [c] 为中心缩放到 [target]。 */
    fun zoomAround(target: Float, c: Offset) {
        val to = target.coerceIn(minScale, homeScale * 8f)
        offsetX = c.x - (c.x - offsetX) * (to / scale)
        offsetY = c.y - (c.y - offsetY) * (to / scale)
        scale = to
        clamp()
    }

    /** 双击：放大着就回到 home，没放大就在 [c] 处放大。 */
    suspend fun toggleZoom(c: Offset) {
        val fromS = scale
        val fromX = offsetX
        val fromY = offsetY
        val toS: Float
        val toX: Float
        val toY: Float
        if (scale > homeScale * 1.3f) {
            toS = homeScale
            toX = viewport.width / 2f - home.center.x * homeScale
            toY = viewport.height / 2f - home.center.y * homeScale
        } else {
            toS = (scale * 2.5f).coerceAtMost(homeScale * 8f)
            toX = c.x - (c.x - offsetX) * (toS / scale)
            toY = c.y - (c.y - offsetY) * (toS / scale)
        }
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = spring(stiffness = 500f)) { v, _ ->
            scale = fromS + (toS - fromS) * v
            offsetX = fromX + (toX - fromX) * v
            offsetY = fromY + (toY - fromY) * v
        }
        clamp()
    }
}

/**
 * 平面图的手势，按「嵌在可滚动页面里」设计：
 * - 没放大时单指拖动**不吃**，交给外层列表滚动——否则手指一落到图上整页就滚不动了；
 * - 双指捏合随时缩放，放大之后单指才拖图；
 * - 单击交给 [onTap]，双击在该处放大 / 复位，按住不动交给 [onLongPress]。
 */
private fun Modifier.planGestures(
    t: PlanTransform,
    key: Any?,
    onLongPress: ((Offset) -> Unit)? = null,
    onTap: (Offset) -> Unit,
): Modifier = pointerInput(key) {
    val slop = 8.dp.toPx()
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    var lastTapAt = 0L
    var lastTapPos = Offset.Zero
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = false
            var multi = false
            var longPressed = false
            var travel = Offset.Zero
            val longPress = onLongPress?.let { cb ->
                launch {
                    kotlinx.coroutines.delay(longPressMs)
                    if (!moved && !multi) { longPressed = true; cb(down.position) }
                }
            }
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) multi = true
                if (multi) {
                    val c = event.calculateCentroid(useCurrent = true)
                    if (c != Offset.Unspecified) t.zoomAround(t.scale * event.calculateZoom(), c)
                    val pan = event.calculatePan()
                    t.offsetX += pan.x
                    t.offsetY += pan.y
                    t.clamp()
                    moved = true
                    event.changes.forEach { it.consume() }
                } else {
                    val change = event.changes.firstOrNull() ?: break
                    travel += change.positionChange()
                    if (hypot(travel.x, travel.y) > slop) moved = true
                    if (t.zoomedIn && moved) {
                        val d = change.positionChange()
                        t.offsetX += d.x
                        t.offsetY += d.y
                        t.clamp()
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
            longPress?.cancel()
            if (moved || multi || longPressed) return@awaitEachGesture
            // 双击的判据是「两次点按间隔 < 300ms」：用 kotlin.time.Clock 而不是 System.currentTimeMillis
            //（:core 的公共代码里没有 java.lang.System；两者都是墙钟毫秒）
            val now = Clock.System.now().toEpochMilliseconds()
            val p = down.position
            if (now - lastTapAt < 300 && hypot(p.x - lastTapPos.x, p.y - lastTapPos.y) < slop * 4) {
                lastTapAt = 0L
                launch { t.toggleZoom(p) }
            } else {
                lastTapAt = now
                lastTapPos = p
                onTap(p)
            }
        }
    }
}

/** 深色模式下把学校的白底图压暗一些，直接铺在深色页面里太刺眼。 */
@Composable
private fun rememberPlanDim(): ColorFilter? {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    return remember(dark) { if (!dark) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.78f, 0.78f, 0.8f, 1f) }) }
}

/**
 * 可缩放拖动的座位平面图。单击选座（不直接预约，手机上座位只有几毫米，点错是常态），
 * 选中的座位由调用方持有，下方信息条里再点「预约」才真正发请求。
 * 长按已选中的座位切换收藏；长按没选中的只是选中，免得误收藏。
 */
@Composable
private fun SeatPlanView(
    layout: SeatLayout,
    images: PlanImages,
    selectedSeatId: String?,
    favorites: Set<String>,
    onSelect: (PlanSeat?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 手势协程只在换图时重启，里面要读最新的选中和回调
    val currentSelected by androidx.compose.runtime.rememberUpdatedState(selectedSeatId)
    val toggleFavorite by androidx.compose.runtime.rememberUpdatedState(onToggleFavorite)
    val primary = MiuixTheme.colorScheme.primary
    val favColor = MiuixTheme.colorScheme.primaryVariant
    val density = LocalDensity.current
    val dim = rememberPlanDim()
    val t = remember { PlanTransform() }
    // 换区域或视口变了：重新框住整张图
    LaunchedEffect(images, t.viewport) {
        t.reset(images.origWidth, images.origHeight, Rect(0f, 0f, images.origWidth.toFloat(), images.origHeight.toFloat()))
    }

    fun seatAt(p: Offset): PlanSeat? {
        val (ix, iy) = t.toImage(p)
        layout.seats.firstOrNull { ix >= it.left && ix <= it.right && iy >= it.top && iy <= it.bottom }?.let { return it }
        // 没正中也找最近的：手指比座位粗，差一点就落空太挫败。容差按屏幕 18dp 折算。
        val tol = with(density) { 18.dp.toPx() } / t.scale
        return layout.seats
            .map { it to hypot(ix - (it.left + it.width / 2), iy - (it.top + it.height / 2)) }
            .filter { (seat, d) -> d <= tol + max(seat.width, seat.height) / 2 }
            .minByOrNull { it.second }?.first
    }

    Canvas(
        modifier
            .onSizeChanged { t.viewport = it }
            .planGestures(
                t, images to layout,
                onLongPress = { p ->
                    seatAt(p)?.let { seat ->
                        if (seat.seatId == currentSelected) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            toggleFavorite(seat.seatId)
                        } else onSelect(seat)
                    }
                },
            ) { onSelect(seatAt(it)) },
    ) {
        if (!t.ready) return@Canvas
        val s = t.scale
        withTransform({
            translate(t.offsetX, t.offsetY)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawImage(
                images.base,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(images.base.width, images.base.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(images.origWidth, images.origHeight),
                colorFilter = dim,
                filterQuality = FilterQuality.Medium,
            )
            for (seat in layout.seats) {
                val topLeft = Offset(seat.left, seat.top)
                val size = Size(seat.width, seat.height)
                if (seat.available) {
                    // 底图上空座本来就是绿色，不再叠色；收藏的空座描一圈，好在一片绿里找到它
                    if (seat.seatId in favorites) drawRect(favColor, topLeft, size, style = Stroke(2.dp.toPx() / s))
                    continue
                }
                val tile = images.tiles[seat.status]
                if (tile != null) {
                    val k = tile.width.toFloat() / images.origWidth
                    val sx = (seat.left * k).toInt().coerceIn(0, tile.width - 1)
                    val sy = (seat.top * k).toInt().coerceIn(0, tile.height - 1)
                    val sw = (seat.width * k).toInt().coerceIn(1, tile.width - sx)
                    val sh = (seat.height * k).toInt().coerceIn(1, tile.height - sy)
                    drawImage(
                        tile,
                        srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                        dstOffset = IntOffset(seat.left.toInt(), seat.top.toInt()),
                        dstSize = IntSize(seat.width.toInt().coerceAtLeast(1), seat.height.toInt().coerceAtLeast(1)),
                        colorFilter = dim,
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawRect(fallbackColor(seat.status).copy(alpha = 0.4f), topLeft, size)
                }
            }
            layout.seats.firstOrNull { it.seatId == selectedSeatId }?.let { seat ->
                val pad = 2.dp.toPx() / s
                drawRect(
                    primary,
                    Offset(seat.left - pad, seat.top - pad),
                    Size(seat.width + pad * 2, seat.height + pad * 2),
                    style = Stroke(2.5.dp.toPx() / s),
                )
            }
        }
    }
}

private fun fallbackColor(status: Int): Color = when (status) {
    PlanSeat.BOOKED -> Color(0xFFF2A33A)
    PlanSeat.INSIDE -> Color(0xFFE5534B)
    PlanSeat.LEAVE -> Color(0xFF4F8EF7)
    else -> Color(0xFF9AA0A6)
}

private fun planStatusLabel(status: Int): String = when (status) {
    PlanSeat.FREE -> "空闲"
    PlanSeat.BOOKED -> "已被预约"
    PlanSeat.INSIDE -> "使用中"
    PlanSeat.LEAVE -> "暂离"
    PlanSeat.CANCELLED -> "不可用"
    else -> "不可预约"
}

/**
 * 平面图卡片：整层图（点区域切区域）、区域座位图、选中信息条三块同宽，合在一张卡里。
 * 三块高度合计不超过 [maxHeight]：手机上把头部卡片滚走后正好一屏；平板横屏在右栏占满。
 *
 * @param floor 这一层的整张图和全部矩形（含楼梯、出口）；默认视野按全部矩形框，免得裁掉东西
 * @param pickableAreas 其中能点的区域（开放的）
 * @param freeText 当前区域的空闲统计，如「空闲 23 / 120」，放在信息条的区域名后面
 * @param canBook 本端能不能预约（见 [LibrarySource.canBook]）。false 时选中座位也不出「预约」按钮
 *   —— 只读端连这一档 UI 都不出现（[LibrarySource.hasSeatPlan]），这里是第二层兜底
 * @param landscapeLock 全屏看座位图时的方向锁定槽位：Android = 把 Activity 转横屏、离开时恢复原来的
 *   方向（`:app` 注入的就是搬迁前那段 `DisposableEffect`）；Web = null（浏览器没有屏幕方向这回事）
 * @param fullscreenDialogProperties 全屏那个 `Dialog` 的窗口属性：**由宿主给**，因为 Android 的
 *   `DialogProperties` 多一个 `decorFitsSystemWindows`（桌面/浏览器那一档没有这个参数，写死在 `:core` 就编不过），
 *   而搬迁前那行写的就是 `usePlatformDefaultWidth = false, decorFitsSystemWindows = false`，Android 侧照旧
 */
@Composable
fun SeatPlanPanel(
    floor: Pair<SeatLayout, PlanImages>?,
    pickableAreas: Set<String>,
    selectedArea: String,
    areaName: String,
    freeText: String?,
    onPickArea: (String) -> Unit,
    layout: SeatLayout?,
    images: PlanImages?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onShowList: () -> Unit,
    maxHeight: Dp,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    isBooking: Boolean,
    onBook: (String) -> Unit,
    canBook: Boolean = true,
    landscapeLock: (@Composable (Boolean) -> Unit)? = null,
    fullscreenDialogProperties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    modifier: Modifier = Modifier,
) {
    // 按区域记选中：刷新座位状态后选中不丢，状态按新数据重新取
    var selected by remember(selectedArea) { mutableStateOf<String?>(null) }
    val selectedSeat = layout?.seats?.firstOrNull { it.seatId == selected }
    val floorBounds = remember(floor) { floor?.first?.seats?.takeIf { it.isNotEmpty() }?.let(::paddedBounds) }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(com.xjtu.toolbox.ui.components.AppCardColor)
    ) {
        val width = maxWidth
        val barHeight = 58.dp
        val floorHeight = floorBounds?.let { (width * (it.height / it.width)).coerceIn(110.dp, 190.dp) } ?: 0.dp
        val mapBudget = (maxHeight - floorHeight - barHeight).coerceAtLeast(200.dp)
        Column(Modifier.fillMaxWidth()) {
            if (floor != null && floorBounds != null) FloorPlanView(
                layout = floor.first,
                images = floor.second,
                bounds = floorBounds,
                pickable = pickableAreas,
                selectedArea = selectedArea,
                onPick = onPickArea,
                modifier = Modifier.fillMaxWidth().height(floorHeight),
            )
            when {
                error != null -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("重试") }
                        Button(onClick = onShowList) { Text("看列表") }
                    }
                }
                layout == null || images == null -> com.xjtu.toolbox.ui.components.LoadingState(
                    message = "正在加载平面图…",
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
                else -> {
                    // 框高按图的比例，太高的图封顶
                    val mapHeight = (width * (images.origHeight.toFloat() / images.origWidth)).coerceAtMost(mapBudget)
                    var fullscreen by remember { mutableStateOf(false) }
                    // 卡片里和全屏里是同一套：图 + 信息条，选中座位共用
                    @Composable
                    fun MapAndBar(mapModifier: Modifier, barOnlyForSeat: Boolean = false) {
                        SeatPlanView(
                            layout = layout,
                            images = images,
                            selectedSeatId = selected,
                            favorites = favorites,
                            onSelect = { selected = it?.seatId },
                            onToggleFavorite = onToggleFavorite,
                            modifier = mapModifier.clipToBounds().background(Color(0xFF8A6A45)),
                        )
                        // 全屏只看图，选了座才出预约条
                        if (!barOnlyForSeat || selectedSeat != null) PlanInfoBar(
                            areaName = areaName,
                            freeText = freeText,
                            seat = selectedSeat,
                            favorite = selectedSeat?.seatId in favorites,
                            canBook = canBook,
                            enabled = !isBooking && !loading,
                            isBooking = isBooking,
                            onBook = onBook,
                            modifier = Modifier.height(barHeight),
                        )
                    }
                    androidx.compose.foundation.layout.Box {
                        Column { MapAndBar(Modifier.fillMaxWidth().height(mapHeight)) }
                        PlanCornerButton(Icons.Default.Fullscreen, "全屏", Modifier.align(Alignment.TopEnd)) { fullscreen = true }
                    }
                    if (fullscreen) PlanFullscreen(
                        landscape = images.origWidth > images.origHeight,
                        onClose = { fullscreen = false },
                        landscapeLock = landscapeLock,
                        properties = fullscreenDialogProperties,
                    ) {
                        MapAndBar(Modifier.fillMaxWidth().weight(1f), barOnlyForSeat = true)
                    }
                }
            }
        }
    }
}

/** 叠在图角上的小圆按钮。 */
@Composable
private fun PlanCornerButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier
            .padding(8.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * 全屏看座位图：宽图转横屏，关掉时恢复原来的方向。
 *
 * 「转屏幕方向」是宿主能力（Android 的 `Activity.requestedOrientation`），`androidx.activity`
 * 的 `LocalActivity` 在 `:core` 里不存在 ⇒ 走 [landscapeLock] 槽位，由 `:app` 注入搬迁前那段
 * `DisposableEffect`（连「进全屏前是什么方向」都记在那边，离开组合时按原值恢复）。
 * 传 null 的语义是「本端没有屏幕方向这回事」（浏览器），不是"调用会抛"。
 * [properties] 由宿主给（见 [SeatPlanPanel] 的 `fullscreenDialogProperties`）。
 */
@Composable
private fun PlanFullscreen(
    landscape: Boolean,
    onClose: () -> Unit,
    landscapeLock: (@Composable (Boolean) -> Unit)?,
    properties: DialogProperties,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lock = landscapeLock
    if (lock != null) lock(landscape)
    Dialog(onDismissRequest = onClose, properties = properties) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(com.xjtu.toolbox.ui.components.AppCardColor).systemBarsPadding(),
        ) {
            Column(Modifier.fillMaxSize(), content = content)
            PlanCornerButton(Icons.Default.FullscreenExit, "退出全屏", Modifier.align(Alignment.TopEnd), onClose)
        }
    }
}

/**
 * 没选座时给区域名、空闲数和操作提示；选中后给座位状态和预约按钮。
 *
 * @param canBook 本端能不能预约（见 [LibrarySource.canBook]）：false 时连按钮都不画 ——
 *   只读端不留一个点了会失败的入口（而 [LibrarySource.hasSeatPlan] = false 时这整个平面图都不出现，
 *   这里是第二层兜底）
 * @param enabled 现在能不能按：不在提交中、平面图不在加载中（搬迁前那个 `canBook` 的语义）
 */
@Composable
private fun PlanInfoBar(
    areaName: String,
    freeText: String?,
    seat: PlanSeat?,
    favorite: Boolean,
    canBook: Boolean,
    enabled: Boolean,
    isBooking: Boolean,
    onBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (seat == null) {
                Text(
                    areaName + (freeText?.let { " · $it" } ?: ""),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            } else {
                Text(seat.seatId + if (favorite) " ★" else "", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                Text(
                    planStatusLabel(seat.status),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (seat.available) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        // 不可约的座位不给按钮：深色下禁用态和正常态差不多，看着像能点。
        // 本端不能预约（canBook = false）时同样一个按钮都不画。
        if (seat != null && seat.available && canBook) {
            Button(
                onClick = { onBook(seat.seatId) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColorsPrimary(),
                insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(if (isBooking) "提交中…" else "预约", color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

/** 矩形合起来的外接框，四周留 6% 的边，看得出是在楼里哪个位置。 */
private fun paddedBounds(areas: List<PlanSeat>): Rect {
    val l = areas.minOf { it.left }
    val t = areas.minOf { it.top }
    val r = areas.maxOf { it.right }
    val b = areas.maxOf { it.bottom }
    val pad = max(r - l, b - t) * 0.06f
    return Rect(l - pad, t - pad, r + pad, b + pad)
}

/**
 * 整层的平面图：学校 `/qseatuist?sp=楼层码` 给的是这一层每个矩形（区域、楼梯、出口），底图是 `楼层码.jpg`。
 * 点哪个区域就进哪个区域，比一排区域名好认得多——同学记的是「靠东边那片」，不是区域全名。
 * 学校的楼层图四周大片留白，进来时框住全部矩形那一块，捏合 / 双击随意缩放。
 */
@Composable
private fun FloorPlanView(
    layout: SeatLayout,
    images: PlanImages,
    bounds: Rect,
    pickable: Set<String>,
    selectedArea: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current
    val dim = rememberPlanDim()
    val t = remember { PlanTransform() }
    LaunchedEffect(images, bounds, t.viewport) { t.reset(images.origWidth, images.origHeight, bounds) }
    val areas = remember(layout, pickable) { layout.seats.filter { it.seatId in pickable } }

    fun areaAt(p: Offset): PlanSeat? {
        val (ix, iy) = t.toImage(p)
        fun dist(a: PlanSeat) = hypot(ix - (a.left + a.width / 2), iy - (a.top + a.height / 2))
        // 区域框之间有缝，点在缝里就取最近的那个
        return areas.firstOrNull { ix >= it.left && ix <= it.right && iy >= it.top && iy <= it.bottom }
            ?: areas.minByOrNull(::dist)?.takeIf { dist(it) < max(it.width, it.height) / 2 + with(density) { 24.dp.toPx() } / t.scale }
    }

    Canvas(
        modifier
            .onSizeChanged { t.viewport = it }
            .clipToBounds()
            .planGestures(t, layout to images) { p -> areaAt(p)?.let { onPick(it.seatId) } },
    ) {
        // 超出图片的部分铺白，和学校楼层图的白底接上
        drawRect(Color.White, colorFilter = dim)
        if (!t.ready) return@Canvas
        val s = t.scale
        withTransform({
            translate(t.offsetX, t.offsetY)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawImage(
                images.base,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(images.base.width, images.base.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(images.origWidth, images.origHeight),
                colorFilter = dim,
                filterQuality = FilterQuality.Medium,
            )
            // 能点的区域描一道淡边，看得出哪些块能进
            for (a in areas) drawRect(primary, Offset(a.left, a.top), Size(a.width, a.height), alpha = 0.35f, style = Stroke(1.dp.toPx() / s))
            areas.firstOrNull { it.seatId == selectedArea }?.let { a ->
                drawRect(primary.copy(alpha = 0.22f), Offset(a.left, a.top), Size(a.width, a.height))
                drawRect(primary, Offset(a.left, a.top), Size(a.width, a.height), style = Stroke(2.dp.toPx() / s))
            }
        }
    }
}
