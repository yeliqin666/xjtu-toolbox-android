package com.xjtu.toolbox.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 圆形头像裁剪。
 *
 * ### 稳健性
 * - 解码走 [ImageDecoder]：自动应用 EXIF 方向（相机直出的竖拍照片在文件里常是横的），
 *   并在**解码阶段**降采样，不把几千万像素整张读进内存。
 * - 缩放下界由"图必须盖住裁剪圆"反推而不是固定 1x，竖长图横长图都能填满。
 * - 平移边界随缩放实时收紧，圆内永远不会露白。
 * - 取像素前把裁剪矩形夹回位图范围：浮点误差下 `createBitmap` 越界会直接抛异常。
 * - 几何量由布局尺寸算出（[onSizeChanged]），**不在绘制阶段写状态**——那会引起
 *   绘制↔重组的反复触发。
 */
@Composable
fun AvatarCropDialog(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var cropping by remember(uri) { mutableStateOf(false) }

    /** 画布边长（正方形）。0 表示还没测量。 */
    var viewportPx by remember(uri) { mutableStateOf(0) }
    /** 在"刚好盖住裁剪圆"的基准之上再放大多少倍。 */
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        val bmp = withContext(Dispatchers.IO) { decodeForCrop(context, uri) }
        if (bmp == null) failed = true else source = bmp
    }

    val geo = remember(source, viewportPx) {
        val bmp = source
        if (bmp == null || viewportPx <= 0) null
        else CropGeometry.of(viewportPx.toFloat(), bmp.width, bmp.height)
    }

    BackHandler(enabled = !cropping) { onCancel() }

    OverlayDialog(
        show = true,
        title = "调整头像",
        summary = "拖动移动，双指缩放，双击放大或复位。圆圈内的部分会被保留。",
        onDismissRequest = { if (!cropping) onCancel() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    // 取景框裁剪：Canvas 默认不裁，放大后图片会画到框外、压住弹窗标题和按钮
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
                    .onSizeChanged { viewportPx = min(it.width, it.height) },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = source
                when {
                    failed -> Text(
                        "这张图片读不出来，换一张试试",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    bmp == null || geo == null -> CircularProgressIndicator()
                    else -> {
                        val image = remember(bmp) { bmp.asImageBitmap() }
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(bmp, geo) {
                                    // 以双指中点为锚缩放：手指按着的那块在缩放前后停在原处。
                                    // 以前总以图片中心为基准，想放大脸，脸却往外跑。
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                        offset = geo.clamp(
                                            geo.zoomAround(offset, scale, newScale, centroid, size.width.toFloat(), size.height.toFloat()) + pan,
                                            newScale,
                                        )
                                        scale = newScale
                                    }
                                }
                                .pointerInput(bmp, geo) {
                                    // 双击：没放大时放大到 2.5 倍（以点按处为中心），放大了就复位
                                    detectTapGestures(onDoubleTap = { tap ->
                                        val target = if (scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                                        offset = geo.clamp(
                                            geo.zoomAround(offset, scale, target, tap, size.width.toFloat(), size.height.toFloat()),
                                            target,
                                        )
                                        scale = target
                                    })
                                }
                        ) {
                            val dispW = geo.displayWidth(scale)
                            val dispH = geo.displayHeight(scale)
                            val o = geo.clamp(offset, scale)
                            val left = size.width / 2f - dispW / 2f + o.x
                            val top = size.height / 2f - dispH / 2f + o.y

                            drawImage(
                                image = image,
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                            )

                            // 圆外压暗：一条 EvenOdd 路径（整块矩形挖掉一个圆），
                            // 不用 saveLayer + BlendMode.Clear 那一套。
                            val r = geo.cropPx / 2f
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawPath(
                                Path().apply {
                                    addRect(Rect(0f, 0f, size.width, size.height))
                                    addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                                    fillType = PathFillType.EvenOdd
                                },
                                Color.Black.copy(alpha = 0.55f),
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.9f),
                                radius = r,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { if (!cropping) onCancel() },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val bmp = source ?: return@Button
                        val g = geo ?: return@Button
                        if (cropping) return@Button
                        cropping = true
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                runCatching { g.crop(bmp, scale, offset) }
                                    .onFailure { Log.w(TAG, "crop failed", it) }
                                    .getOrNull()
                            }
                            cropping = false
                            if (out != null) onConfirm(out) else onCancel()
                        }
                    },
                    enabled = source != null && geo != null && !cropping,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (cropping) "处理中…" else "使用")
                }
            }
        }
    }
}

private const val TAG = "AvatarCropper"

/** 解码上限。对 512 的输出而言再大没有意义，只会更慢更吃内存。 */
private const val DECODE_MAX_PX = 2048

/** 输出边长。头像最大也就显示一两百 dp，512 足够。 */
private const val OUTPUT_PX = 512

private const val MAX_SCALE = 6f

private const val DOUBLE_TAP_SCALE = 2.5f

/** 裁剪圆占画布的比例。留边是为了让用户看得见圈外还有什么。 */
private const val CROP_RATIO = 0.78f

/**
 * 一次裁剪会话的几何量。
 *
 * 由画布尺寸和图片尺寸唯一确定，缩放/平移是它的参数而不是成员——
 * 这样绘制、手势夹取、最终取像素三处用的是同一套算法，不会各算各的。
 */
private data class CropGeometry(
    val viewport: Float,
    val imageW: Int,
    val imageH: Int,
    /** 裁剪方框（圆的外接正方形）边长。 */
    val cropPx: Float,
    /** 让图片**短边**刚好盖住裁剪框所需的缩放；只有短边盖住了，圆内才不可能露白。 */
    val baseScale: Float,
) {
    fun displayWidth(scale: Float) = imageW * baseScale * scale
    fun displayHeight(scale: Float) = imageH * baseScale * scale

    /**
     * 把平移夹回"圆内始终有像素"的范围。
     *
     * 展示后的图必须完全盖住居中的裁剪框，于是平移量绝对值不能超过
     * （展示边长 − 裁剪边长）/ 2；两者相等时上界为 0，图被钉死居中。
     */
    fun clamp(raw: Offset, scale: Float): Offset {
        val maxX = max(0f, (displayWidth(scale) - cropPx) / 2f)
        val maxY = max(0f, (displayHeight(scale) - cropPx) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    /**
     * 以画布上的 [anchor] 为不动点，从 [from] 倍缩放到 [to] 倍后的平移量。
     * 图片中心相对画布中心的偏移是 offset；锚点相对图片中心的距离按 to/from 等比放大，
     * 反推出新的 offset，锚点下的像素就停在原处。
     */
    fun zoomAround(offset: Offset, from: Float, to: Float, anchor: Offset, width: Float, height: Float): Offset {
        if (from == to) return offset
        val a = anchor - Offset(width / 2f, height / 2f)   // 锚点相对画布中心
        return a - (a - offset) * (to / from)
    }

    /**
     * 取出圆所在的正方形区域，缩放到 [OUTPUT_PX]。
     *
     * 存的是正方形而不是抠成圆：圆角由显示侧的 `clip(CircleShape)` 负责，
     * 存带透明角的 PNG 既大又会在不做圆形裁剪的地方露出黑角。
     */
    fun crop(src: Bitmap, scale: Float, rawOffset: Offset): Bitmap {
        val o = clamp(rawOffset, scale)
        val k = baseScale * scale                       // 屏幕像素 / 图片像素
        val sizeF = cropPx / k                          // 裁剪框对应的源边长
        val leftF = imageW / 2f - o.x / k - sizeF / 2f
        val topF = imageH / 2f - o.y / k - sizeF / 2f

        // 夹回位图范围：上面的边界约束保证理论上就在范围内，但浮点误差足以让
        // createBitmap 抛 IllegalArgumentException，这一步是保险而不是修正。
        var side = sizeF.roundToInt().coerceAtLeast(1)
        side = min(side, min(src.width, src.height))
        val left = leftF.roundToInt().coerceIn(0, src.width - side)
        val top = topF.roundToInt().coerceIn(0, src.height - side)

        val square = Bitmap.createBitmap(src, left, top, side, side)
        return if (side == OUTPUT_PX) {
            square
        } else {
            Bitmap.createScaledBitmap(square, OUTPUT_PX, OUTPUT_PX, true)
                .also { if (it !== square) square.recycle() }
        }
    }

    companion object {
        fun of(viewport: Float, imageW: Int, imageH: Int): CropGeometry {
            val cropPx = viewport * CROP_RATIO
            val shortest = min(imageW, imageH).toFloat().coerceAtLeast(1f)
            return CropGeometry(
                viewport = viewport,
                imageW = imageW,
                imageH = imageH,
                cropPx = cropPx,
                baseScale = cropPx / shortest,
            )
        }
    }
}

/** 解码待裁剪的图片：EXIF 摆正 + 降采样。失败返回 null（已记日志）。 */
private fun decodeForCrop(context: Context, uri: Uri): Bitmap? = runCatching {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        // 硬件 Bitmap 读不到像素，createBitmap / compress 都会失败。
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longest = max(info.size.width, info.size.height)
        if (longest > DECODE_MAX_PX) {
            val scale = DECODE_MAX_PX.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}.onFailure { Log.w(TAG, "decode failed: $uri", it) }.getOrNull()
