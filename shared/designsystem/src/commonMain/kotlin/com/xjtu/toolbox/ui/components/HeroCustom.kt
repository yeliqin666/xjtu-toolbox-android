package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.decodeImageFull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 自定义背景裁出来固定按头牌的宽高比，卡片再用 Crop 吃掉不同屏幕的细微差异。 */
internal const val HERO_CROP_ASPECT = 2.5f

/**
 * 已解码的背景图，只留最近一张。
 *
 * 键用数据串的哈希：整串 base64 可能有几百 KB，每次组合都全串比较不划算，
 * 哈希碰撞的代价只是「多信一次缓存、显示旧图」，而这里同一时刻只有一张图在轮换。
 * 不放进 Compose 状态：它的作用是省一次解码，读它的地方（[HeroCustomBackground] 里那个
 * `remember(哈希)` 的初值）本来就会随 imageData 重组。
 */
private object HeroImageCache {
    private var key = 0
    private var bitmap: ImageBitmap? = null

    operator fun get(data: String): ImageBitmap? = if (data.isNotEmpty() && data.hashCode() == key) bitmap else null

    fun put(data: String, value: ImageBitmap) {
        key = data.hashCode()
        bitmap = value
    }
}

/**
 * 背景图的低清模糊版，给首页小组件当磨砂底：逐级减半缩到 [FROST_WIDTH] 宽，绘制时双线性放大，
 * 观感接近大半径模糊。只在后台算一次；滚动时每块小组件只是贴自己那一小块，不跑任何实时模糊。
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun rememberFrostedImage(imageData: String): ImageBitmap? {
    var frosted by remember(imageData.hashCode()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageData.hashCode()) {
        if (imageData.isEmpty()) return@LaunchedEffect
        frosted = withContext(Dispatchers.Default) {
            val full = HeroImageCache[imageData]
                ?: runCatching { decodeImageFull(Base64.Default.decode(imageData)) }.getOrNull()
            full?.let(::frost)
        }
    }
    return frosted
}

private fun frost(source: ImageBitmap): ImageBitmap {
    var current = source
    // 每次只减半：一步缩到几十像素会跳采样出噪点，逐级减半相当于一路做盒式平均
    while (current.width > FROST_WIDTH * 2) {
        val next = ImageBitmap(current.width / 2, max(1, current.height / 2))
        GraphicsCanvas(next).drawImageRect(
            current,
            dstSize = IntSize(next.width, next.height),
            paint = androidx.compose.ui.graphics.Paint().apply { filterQuality = FilterQuality.Low },
        )
        current = next
    }
    return current
}

private const val FROST_WIDTH = 40

/** 背景图的亮度滤镜，和 [HeroCustomBackground] 同一算法；100 = 原样。 */
fun heroBrightnessFilter(brightness: Int): ColorFilter? =
    if (brightness >= 100) null
    else (brightness / 100f).let { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(it, it, it, 1f) }) }

/**
 * 已解码的背景图，只留最近一张。
 *
 * 键用数据串的哈希：整串 base64 可能有几百 KB，每次组合都全串比较不划算，
 * 哈希碰撞的代价只是「多信一次缓存、显示旧图」，而这里同一时刻只有一张图在轮换。
 * 不放进 Compose 状态：它的作用是省一次解码，读它的地方（[HeroCustomBackground] 里那个
 * `remember(哈希)` 的初值）本来就会随 imageData 重组。
 */
private object HeroImageCache {
    private var key = 0
    private var bitmap: ImageBitmap? = null

    operator fun get(data: String): ImageBitmap? = if (data.isNotEmpty() && data.hashCode() == key) bitmap else null

    fun put(data: String, value: ImageBitmap) {
        key = data.hashCode()
        bitmap = value
    }
}

/** 自定义背景上承托白字的暗色罩。 */
val HeroCustomScrim: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.30f),
    0.5f to Color.Black.copy(alpha = 0.16f),
    1f to Color.Black.copy(alpha = 0.44f),
)

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun HeroCustomBackground(
    imageData: String,
    modifier: Modifier = Modifier,
    blur: Int = 0,
    brightness: Int = 100,
) {
    // 解码放到后台：base64 解开 + 整图解码有几十毫秒，以前是在组合期同步做的，
    // 于是「进首页 / 开抽屉看到头卡」那一帧直接被它拖住（壁纸越大越明显）。
    // 缓存按数据串的哈希记最后一张：首页背景层与头卡可能同时用同一张图，
    // 返回上一页再进来也不该重解一次（命中缓存时首帧就有图，不闪）。
    // key 用哈希而不是数据串本身：几百 KB 的 base64 当 remember key，每次重组都要全串
    // 比较一次（JVM 的 String.hashCode 算过一次就缓存，equals 没有）。碰撞只是「不重解、
    // 显示上一张」，与缓存的判据完全一致。
    //
    // 这里是 `remember(哈希)` + `LaunchedEffect(哈希)`，**不是 `produceState(key1 = 哈希)`**：
    // produceState 里面那层 state 是 `remember { mutableStateOf(initialValue) }`（不带 key），
    // key 变了只重启 effect、旧值还留在里面，于是 `value != null` 的守卫会让新图永远不解码——
    // 表现就是「换完壁纸没反应，重启应用才生效」（2026-11 实测，改法的原因就这一条）。
    var decoded by remember(imageData.hashCode()) { mutableStateOf(HeroImageCache[imageData]) }
    LaunchedEffect(imageData.hashCode()) {
        if (imageData.isEmpty() || decoded != null) return@LaunchedEffect
        decoded = withContext(Dispatchers.Default) {
            runCatching { decodeImageFull(Base64.Default.decode(imageData)) }.getOrNull()
        }
        decoded?.let { HeroImageCache.put(imageData, it) }
    }
    // 委托属性不会被智能转换，先取出来再用
    val image = decoded
    // 未命中缓存时先画占位渐变、图到了再淡入，避免「渐变 → 图片」硬切一下
    val fade by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(180),
        label = "heroImageIn",
    )
    if (image != null) {
        val colorFilter = remember(brightness) { heroBrightnessFilter(brightness) }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade }
                .then(
                    if (blur > 0) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded)
                    else Modifier
                ),
        )
    } else {
        Box(
            modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF2B2F36), Color(0xFF4A5361), Color(0xFF1C2026))),
            ),
        )
    }
}

/** 裁剪界面：固定宽高比的取景框，拖动平移、双指或滑块缩放。 */
@Composable
fun ImageCropContent(
    image: ImageBitmap,
    busy: Boolean,
    aspect: Float = HERO_CROP_ASPECT,
    circular: Boolean = false,
    maxOutput: Int = 1440,
    onCancel: () -> Unit,
    onCrop: (ImageBitmap) -> Unit,
) {
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier
                    .width(minOf(maxWidth, 340.dp * aspect))
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
            ) {
                val vw = constraints.maxWidth.toFloat()
                val vh = constraints.maxHeight.toFloat()
                Canvas(
                    Modifier
                        .matchParentSize()
                        .onSizeChanged {
                            viewport = Size(it.width.toFloat(), it.height.toFloat())
                            offset = clampCropOffset(offset, image, viewport, zoom)
                        }
                        .pointerInput(image, vw, vh) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                val center = Offset(vw / 2f, vh / 2f)
                                val nextOffset = (offset + center - centroid) * (nextZoom / zoom) + centroid - center + pan
                                zoom = nextZoom
                                offset = clampCropOffset(nextOffset, image, Size(vw, vh), nextZoom)
                            }
                        }
                        .pointerInput(image, vw, vh) {
                            detectTapGestures(onDoubleTap = { tap ->
                                val target = if (zoom > 1.05f) 1f else 2.5f
                                val center = Offset(vw / 2f, vh / 2f)
                                offset = clampCropOffset((offset + center - tap) * (target / zoom) + tap - center, image, viewport, target)
                                zoom = target
                            })
                        },
                ) {
                    drawCropCover(image, zoom, offset)
                    if (circular) {
                        val inset = 1.dp.toPx()
                        val bounds = Rect(inset, inset, size.width - inset, size.height - inset)
                        drawPath(Path().apply {
                            addRect(Rect(Offset.Zero, size))
                            addOval(bounds)
                            fillType = PathFillType.EvenOdd
                        }, Color.Black.copy(alpha = 0.55f))
                        drawCircle(Color.White, radius = size.minDimension / 2f - inset, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "拖动调整位置，双指或滑块缩放",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Slider(
            value = zoom,
            onValueChange = {
                val nextZoom = it.coerceIn(1f, 6f)
                zoom = nextZoom
                offset = clampCropOffset(offset, image, viewport, nextZoom)
            },
            valueRange = 1f..6f,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
            TextButton(
                text = if (busy) "处理中…" else "确定",
                enabled = !busy && viewport.width > 0f && viewport.height > 0f,
                onClick = { onCrop(cropImageSelection(image, viewport, zoom, offset, maxOutput)) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

internal fun clampCropOffset(offset: Offset, image: ImageBitmap, viewport: Size, zoom: Float): Offset {
    if (viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
    val scale = cropCoverScale(image, viewport) * zoom
    val maxX = ((image.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((image.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun cropCoverScale(image: ImageBitmap, viewport: Size): Float =
    max(viewport.width / image.width, viewport.height / image.height)

private fun DrawScope.drawCropCover(image: ImageBitmap, zoom: Float, offset: Offset) {
    val scale = cropCoverScale(image, size) * zoom
    val width = image.width * scale
    val height = image.height * scale
    drawImage(
        image = image,
        dstOffset = IntOffset(
            ((size.width - width) / 2f + offset.x).roundToInt(),
            ((size.height - height) / 2f + offset.y).roundToInt(),
        ),
        dstSize = IntSize(width.roundToInt(), height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

internal fun cropImageSelection(image: ImageBitmap, viewport: Size, zoom: Float, offset: Offset, maxOutput: Int = 1440): ImageBitmap {
    val scale = cropCoverScale(image, viewport) * zoom
    val visibleW = (viewport.width / scale).coerceAtMost(image.width.toFloat())
    val visibleH = (viewport.height / scale).coerceAtMost(image.height.toFloat())
    val centerX = image.width / 2f - offset.x / scale
    val centerY = image.height / 2f - offset.y / scale
    val srcX = (centerX - visibleW / 2f).coerceIn(0f, (image.width - visibleW).coerceAtLeast(0f))
    val srcY = (centerY - visibleH / 2f).coerceIn(0f, (image.height - visibleH).coerceAtLeast(0f))
    val srcW = visibleW.roundToInt().coerceIn(1, image.width)
    val srcH = visibleH.roundToInt().coerceIn(1, image.height)

    val ratio = viewport.width / viewport.height
    val targetW = (maxOutput * minOf(ratio, 1f)).roundToInt().coerceAtLeast(1)
    val targetH = (targetW / ratio).roundToInt().coerceAtLeast(1)
    val target = ImageBitmap(targetW, targetH)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(target),
        size = Size(targetW.toFloat(), targetH.toFloat()),
    ) {
        drawImage(
            image = image,
            srcOffset = IntOffset(
                srcX.roundToInt().coerceIn(0, image.width - srcW),
                srcY.roundToInt().coerceIn(0, image.height - srcH),
            ),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(targetW, targetH),
            filterQuality = FilterQuality.High,
        )
    }
    return target
}
