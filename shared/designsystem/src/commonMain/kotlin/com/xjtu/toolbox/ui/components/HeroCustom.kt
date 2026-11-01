package com.xjtu.toolbox.ui.components

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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 自定义背景裁出来固定按头牌的宽高比，卡片再用 Crop 吃掉不同屏幕的细微差异。 */
internal const val HERO_CROP_ASPECT = 2.5f

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
    val image = remember(imageData) {
        if (imageData.isEmpty()) {
            null
        } else {
            runCatching { decodeImageFull(Base64.Default.decode(imageData)) }.getOrNull()
        }
    }
    if (image != null) {
        val colorFilter = remember(brightness) {
            if (brightness >= 100) null else {
                val scale = brightness / 100f
                ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToScale(scale, scale, scale, 1f) }
                )
            }
        }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = modifier
                .fillMaxSize()
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
