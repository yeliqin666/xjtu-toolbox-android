package com.xjtu.toolbox.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 一个区域的平面图图片。
 *
 * 学校给的是「底图 + 每种状态一张整图」：已预约、使用中、中途离开的座位各自从对应那张图上
 * 裁自己那一块贴上去，和网页版 `/seatui` 一个画法。坐标都是底图原始像素（[origWidth]×[origHeight]），
 * 解码时为省内存做过降采样，画的时候按各图自己的比例换算。
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

/**
 * 平面图图片的磁盘缓存。图是学校服务器上的静态文件，一张几百 KB，经 WebVPN 下得很慢；
 * 缓存 7 天，过期或读坏了再重新下。
 */
object PlanImageDiskCache {
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    suspend fun get(context: android.content.Context, name: String, download: suspend (String) -> ByteArray?): ByteArray? {
        val dir = java.io.File(context.cacheDir, "library_plan").apply { mkdirs() }
        val file = java.io.File(dir, name.replace('/', '_'))
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val bytes = download(name) ?: return null
        runCatching { file.writeBytes(bytes) }
        return bytes
    }
}

private const val BASE_MAX_DIM = 2048
/** 状态图只用来裁小块，精度要求低，再压一半。 */
private const val TILE_MAX_DIM = 1024

/**
 * 解码平面图。底图失败返回 null（没底图就没法画）。
 * 全部用 RGB_565：JPEG 没有透明通道，内存砍半；一张 2048 的底图约 8MB。
 */
fun decodePlanImages(base: ByteArray, tiles: Map<Int, ByteArray>): PlanImages? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(base, 0, base.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val baseBmp = decodeSampled(base, bounds.outWidth, bounds.outHeight, BASE_MAX_DIM) ?: return null
    return PlanImages(baseBmp.asImageBitmap(), bounds.outWidth, bounds.outHeight, decodeTiles(tiles, bounds.outWidth, bounds.outHeight))
}

private fun decodeTiles(tiles: Map<Int, ByteArray>, origW: Int, origH: Int): Map<Int, ImageBitmap> =
    tiles.mapNotNull { (status, bytes) ->
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        // 尺寸对不上底图的状态图（比例不同）不能按比例裁，宁可不用
        val sameAspect = b.outWidth > 0 && b.outHeight > 0 &&
            abs(b.outWidth.toFloat() / b.outHeight - origW.toFloat() / origH) < 0.02f
        if (!sameAspect) return@mapNotNull null
        decodeSampled(bytes, b.outWidth, b.outHeight, TILE_MAX_DIM)?.let { status to it.asImageBitmap() }
    }.toMap()

private fun decodeSampled(bytes: ByteArray, w: Int, h: Int, maxDim: Int): Bitmap? {
    var sample = 1
    while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * 可缩放拖动的座位平面图。
 *
 * 手势按「嵌在可滚动页面里」设计：
 * - 没放大时单指拖动**不吃**，交给外层列表滚动——否则手指一落到图上整页就滚不动了；
 * - 双指捏合随时缩放，放大之后单指才拖图；
 * - 单击选座（不直接预约，手机上座位只有几毫米，点错是常态），双击在该处放大 / 复位。
 *
 * 选中的座位由调用方持有，下方信息条里再点「预约」才真正发请求。
 */
@Composable
fun SeatPlanView(
    layout: SeatLayout,
    images: PlanImages,
    selectedSeatId: String?,
    favorites: Set<String>,
    onSelect: (PlanSeat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val primary = MiuixTheme.colorScheme.primary
    val favColor = MiuixTheme.colorScheme.primaryVariant
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 变换：屏幕 = 图像像素 × scale + offset
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var fitScale by remember { mutableFloatStateOf(1f) }

    fun clampOffsets(s: Float) {
        val w = images.origWidth * s
        val h = images.origHeight * s
        offsetX = if (w <= viewport.width) (viewport.width - w) / 2f
        else offsetX.coerceIn(viewport.width - w, 0f)
        offsetY = if (h <= viewport.height) (viewport.height - h) / 2f
        else offsetY.coerceIn(viewport.height - h, 0f)
    }

    /** 进来时和双击复位时的缩放：整图放下。 */
    fun homeScale(): Float {
        return fitScale
    }

    fun fit() {
        if (viewport.width == 0 || viewport.height == 0) return
        fitScale = min(viewport.width.toFloat() / images.origWidth, viewport.height.toFloat() / images.origHeight)
        val home = homeScale()
        scale = home
        // 从图的中间看起
        offsetX = (viewport.width - images.origWidth * home) / 2f
        offsetY = (viewport.height - images.origHeight * home) / 2f
        clampOffsets(home)
    }

    /** 以屏幕点 [c] 为中心缩放到 [target]。 */
    fun zoomTo(target: Float, c: Offset, animate: Boolean) {
        val from = scale
        val to = target.coerceIn(fitScale, fitScale * 10f)
        val ax = offsetX
        val ay = offsetY
        fun apply(v: Float) {
            scale = v
            offsetX = c.x - (c.x - ax) * (v / from)
            offsetY = c.y - (c.y - ay) * (v / from)
            clampOffsets(v)
        }
        if (!animate) { apply(to); return }
        scope.launch {
            androidx.compose.animation.core.animate(from, to, animationSpec = spring(stiffness = 500f)) { v, _ -> apply(v) }
        }
    }

    // 换区域或视口变了：重新适配
    LaunchedEffect(images, viewport) { fit() }

    // 右上角放大 / 缩小按钮
    val zoomCommand = LocalZoomCommand.current
    LaunchedEffect(zoomCommand) {
        if (zoomCommand.first == 0) return@LaunchedEffect
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        zoomTo(if (zoomCommand.second > 0) scale * 1.8f else scale / 1.8f, center, animate = true)
    }

    fun seatAt(p: Offset): PlanSeat? {
        val s = scale
        val ix = (p.x - offsetX) / s
        val iy = (p.y - offsetY) / s
        layout.seats.firstOrNull { ix >= it.left && ix <= it.right && iy >= it.top && iy <= it.bottom }
            ?.let { return it }
        // 没正中也找最近的：手指比座位粗，差一点就落空太挫败。容差按屏幕 18dp 折算。
        val tol = with(density) { 18.dp.toPx() } / s
        return layout.seats
            .map { it to hypot(ix - (it.left + it.width / 2), iy - (it.top + it.height / 2)) }
            .filter { (seat, d) -> d <= tol + max(seat.width, seat.height) / 2 }
            .minByOrNull { it.second }?.first
    }

    val touchSlop = with(density) { 8.dp.toPx() }
    var lastTapAt by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    // 深色模式下把整张图压暗一些：学校的底图是白底，直接铺在深色页面里太刺眼。
    val dim = remember(dark) {
        if (!dark) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.78f, 0.78f, 0.8f, 1f) })
    }

    Canvas(
        modifier
            .onSizeChanged { viewport = it }
            .pointerInput(images, layout) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var multi = false
                    var travel = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) multi = true
                        val zoomed = scale > fitScale * 1.02f
                        if (multi) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = true)
                            if (z != 1f && c != Offset.Unspecified) zoomTo(scale * z, c, animate = false)
                            offsetX += pan.x; offsetY += pan.y
                            clampOffsets(scale)
                            moved = true
                            event.changes.forEach { it.consume() }
                        } else {
                            val change = event.changes.firstOrNull() ?: break
                            travel += change.positionChange()
                            if (hypot(travel.x, travel.y) > touchSlop) moved = true
                            // 放大后单指拖图；没放大时不吃，外层列表照常滚
                            if (zoomed && moved) {
                                val d = change.positionChange()
                                offsetX += d.x; offsetY += d.y
                                clampOffsets(scale)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!moved && !multi) {
                        val now = System.currentTimeMillis()
                        val p = down.position
                        val isDouble = now - lastTapAt < 300 &&
                            hypot(p.x - lastTapPos.x, p.y - lastTapPos.y) < touchSlop * 4
                        if (isDouble) {
                            lastTapAt = 0L
                            val home = homeScale()
                            if (scale > home * 1.3f) zoomTo(home, p, animate = true)
                            else zoomTo(scale * 2.5f, p, animate = true)
                        } else {
                            lastTapAt = now
                            lastTapPos = p
                            onSelect(seatAt(p))
                        }
                    }
                }
            }
    ) {
        val s = scale
        withTransform({
            translate(offsetX, offsetY)
            scale(s, s, pivot = Offset.Zero)
        }) {
            val full = IntSize(images.origWidth, images.origHeight)
            drawImage(
                images.base,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(images.base.width, images.base.height),
                dstOffset = IntOffset.Zero,
                dstSize = full,
                colorFilter = dim,
                filterQuality = FilterQuality.Medium,
            )
            for (seat in layout.seats) {
                val topLeft = Offset(seat.left, seat.top)
                val size = Size(seat.width, seat.height)
                if (seat.available) {
                    // 空闲座位铺一层主色：底图上空座和有人的座看着差不多，缩小后更分不清
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
                val stroke = 2.5.dp.toPx() / s
                val pad = 2.dp.toPx() / s
                drawRect(
                    primary,
                    Offset(seat.left - pad, seat.top - pad),
                    Size(seat.width + pad * 2, seat.height + pad * 2),
                    style = Stroke(stroke),
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

fun planStatusLabel(status: Int): String = when (status) {
    PlanSeat.FREE -> "空闲"
    PlanSeat.BOOKED -> "已被预约"
    PlanSeat.INSIDE -> "使用中"
    PlanSeat.LEAVE -> "暂离"
    PlanSeat.CANCELLED -> "不可用"
    else -> "不可预约"
}

/**
 * 平面图面板：图 + 右上角缩放按钮 + 底部选中信息条。
 *
 * 高度由调用方给（手机上约等于一屏，把头部卡片滚走后图正好占满；平板横屏在右栏占满）。
 */
@Composable
fun SeatPlanPanel(
    layout: SeatLayout,
    images: PlanImages,
    maxHeight: Dp,
    favorites: Set<String>,
    isBooking: Boolean,
    result: BookResult?,
    onBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(layout) { mutableStateOf<String?>(null) }
    // 刷新后状态会变：选中的座位按新数据重新取
    val selectedSeat = layout.seats.firstOrNull { it.seatId == selected }
    // 缩放按钮给没法捏合的场景（平板接鼠标、单手）用：通过换 key 让图重新适配或按比例缩放
    var zoomCommand by remember { mutableStateOf(0 to 0) } // (序号, 方向)

    // 框高跟着图的比例走：学校的平面图多是宽扁的一长条，框给一整屏高只会上下空一大片。
    // 上限给信息条留出位置，保证图和「预约」按钮同屏。
    // 框高严格按图的比例：多给一截只会上下空着。太高的图（竖长的区域）封顶，给信息条留位置。
    val limit = (maxHeight - 76.dp).coerceAtLeast(240.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val mapHeight = (maxWidth * (images.origHeight.toFloat() / images.origWidth)).coerceAtMost(limit)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF8A6A45))
        ) {
            ZoomableHost(zoomCommand) {
                SeatPlanView(
                    layout = layout,
                    images = images,
                    selectedSeatId = selected,
                    favorites = favorites,
                    onSelect = { selected = it?.seatId },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(result != null) {
            val r = result ?: return@AnimatedVisibility
            Text(
                r.message,
                style = MiuixTheme.textStyles.body2,
                color = if (r.success) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (r.success) MiuixTheme.colorScheme.secondaryContainer
                        else MiuixTheme.colorScheme.errorContainer
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        // 底部信息条：没选时给操作提示，选中后给状态和预约按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(com.xjtu.toolbox.ui.components.AppCardColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedSeat == null) {
                Text(
                    "点选空闲座位 · 双指或双击放大",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedSeat.seatId + if (selectedSeat.seatId in favorites) " ★" else "",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        planStatusLabel(selectedSeat.status),
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (selectedSeat.available) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            // 缩放按钮放在信息条里而不是压在图上：图上每一块都可能是座位
            PlanRoundButton(onClick = { zoomCommand = zoomCommand.first + 1 to -1 }) {
                Icon(Icons.Default.Remove, "缩小", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(6.dp))
            PlanRoundButton(onClick = { zoomCommand = zoomCommand.first + 1 to 1 }) {
                Icon(Icons.Default.Add, "放大", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurface)
            }
            if (selectedSeat != null) {
                Spacer(Modifier.width(10.dp))
                // 不可约的座位不给按钮：深色下禁用态和正常态差不多，看着像能点
                if (selectedSeat.available) Button(
                    onClick = { onBook(selectedSeat.seatId) },
                    enabled = !isBooking,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (isBooking) "提交中…" else "预约",
                        color = MiuixTheme.colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
    }
    }
}

/**
 * 把「放大 / 缩小」按钮的命令翻译成对 [SeatPlanView] 的缩放。
 * SeatPlanView 自己持有变换，这里用一个 CompositionLocal 把命令递进去，免得把变换状态提到外面来。
 */
@Composable
private fun ZoomableHost(command: Pair<Int, Int>, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalZoomCommand provides command) { content() }
}

internal val LocalZoomCommand = androidx.compose.runtime.compositionLocalOf { 0 to 0 }

@Composable
private fun PlanRoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 整层的平面图：学校 `/qseatuist?sp=楼层码` 给的是这一层每个区域的矩形，底图是 `楼层码.jpg`。
 * 点哪个区域就进哪个区域，比一排区域名好认得多——同学记的是「靠东边那片」，不是区域全名。
 */
@Composable
fun FloorPlanView(
    layout: SeatLayout,
    images: PlanImages,
    selectedArea: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val dim = remember(dark) {
        if (!dark) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.78f, 0.78f, 0.8f, 1f) })
    }
    // 只画区域框合起来的那一块：学校的楼层图四周大片留白（兴庆二层楼只占左上三分之一），
    // 整张铺进来区域框小得点不中。四周留 8% 的边，看得出是在楼里哪个位置。
    val crop = remember(layout, images) {
        val l = layout.seats.minOf { it.left }
        val t = layout.seats.minOf { it.top }
        val r = layout.seats.maxOf { it.right }
        val b = layout.seats.maxOf { it.bottom }
        val pad = max(r - l, b - t) * 0.08f
        val cl = (l - pad).coerceAtLeast(0f)
        val ct = (t - pad).coerceAtLeast(0f)
        val cr = (r + pad).coerceAtMost(images.origWidth.toFloat())
        val cb = (b + pad).coerceAtMost(images.origHeight.toFloat())
        androidx.compose.ui.geometry.Rect(cl, ct, cr, cb)
    }
    // 竖长的一块（创新港一层）按宽铺满能占掉一整屏，把下面的座位图挤走：限高，按比例缩窄居中
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Canvas(
        Modifier
            .heightIn(max = 280.dp)
            .aspectRatio(crop.width / crop.height)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(layout, images, crop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.any { hypot(it.position.x - down.position.x, it.position.y - down.position.y) > 12f }) moved = true
                    } while (e.changes.any { it.pressed })
                    if (!moved) {
                        val k = size.width.toFloat() / crop.width
                        val ix = down.position.x / k + crop.left
                        val iy = down.position.y / k + crop.top
                        // 区域框之间有缝，点在缝里就取最近的那个
                        (layout.seats.firstOrNull { ix >= it.left && ix <= it.right && iy >= it.top && iy <= it.bottom }
                            ?: layout.seats.minByOrNull {
                                hypot(ix - (it.left + it.width / 2), iy - (it.top + it.height / 2))
                            }?.takeIf {
                                hypot(ix - (it.left + it.width / 2), iy - (it.top + it.height / 2)) <
                                    max(it.width, it.height) / 2 + 24.dp.toPx() / k
                            })?.let { onPick(it.seatId) }
                    }
                }
            }
    ) {
        val k = size.width / crop.width
        val b = images.base.width.toFloat() / images.origWidth
        drawImage(
            images.base,
            srcOffset = IntOffset((crop.left * b).toInt(), (crop.top * b).toInt()),
            srcSize = IntSize((crop.width * b).toInt().coerceAtLeast(1), (crop.height * b).toInt().coerceAtLeast(1)),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            colorFilter = dim,
            filterQuality = FilterQuality.Medium,
        )
        layout.seats.firstOrNull { it.seatId == selectedArea }?.let { a ->
            val tl = Offset((a.left - crop.left) * k, (a.top - crop.top) * k)
            val sz = Size(a.width * k, a.height * k)
            drawRect(primary.copy(alpha = 0.22f), tl, sz)
            drawRect(primary, tl, sz, style = Stroke(2.dp.toPx()))
        }
    }
    }
}

