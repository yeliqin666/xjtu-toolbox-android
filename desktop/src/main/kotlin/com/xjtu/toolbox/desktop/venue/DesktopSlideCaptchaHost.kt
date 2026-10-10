package com.xjtu.toolbox.desktop.venue

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.decodeImageFull
import com.xjtu.toolbox.venue.CaptchaData
import com.xjtu.toolbox.venue.SlideCaptchaHost
import com.xjtu.toolbox.venue.SliderResult
import com.xjtu.toolbox.venue.SolvedCaptcha
import com.xjtu.toolbox.venue.TrackPoint
import com.xjtu.toolbox.venue.VenueSlideCaptchaHost
import java.util.Base64
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "DesktopSlideCaptchaHost"

/**
 * 场馆滑块验证码的**桌面宿主**（`:desktop`，Stage B 新写的实现）：
 *
 *  - [CaptchaView]：自己的滑块 UI —— 图片用 `:core` 的 [decodeImageFull]（桌面侧是 skiko）
 *    解码、拖动手势产出一条与 Android 同协议（260 坐标系 + down/move/up + ISO 时刻）的
 *    [SliderResult]；**不搬 Android 的 Bitmap 渲染**；
 *  - [solve]：识别/盖章/等待编排在 `:data`（共享），这里整段委托 [VenueSlideCaptchaHost]。
 */
object DesktopSlideCaptchaHost : SlideCaptchaHost {

    @Composable
    override fun CaptchaView(data: CaptchaData, onSolved: (SliderResult) -> Unit) {
        SliderCaptchaPanel(
            backgroundImageBase64 = data.backgroundImage,
            sliderImageBase64 = data.sliderImage,
            bgOriginalWidth = data.bgWidth,
            bgOriginalHeight = data.bgHeight,
            sliderOriginalWidth = data.sliderWidth,
            sliderOriginalHeight = data.sliderHeight,
            onSlideComplete = onSolved,
        )
    }

    override suspend fun solve(data: CaptchaData, shownAtMillis: Long): SolvedCaptcha? =
        // 识别器与提交逻辑在 `:data`（共享）：桌面宿主直接复用同一份解算与盖章。
        VenueSlideCaptchaHost.solve(data, shownAtMillis)
}

/**
 * 桌面端的滑块面板：与 `:data` 的 `SliderCaptchaView` 同协议（260 显示坐标系、track
 * 的 down/move/up、ISO_INSTANT 两个时刻），但是**新写的宿主实现** —— 除了 Compose 的
 * `ImageBitmap` 之外不走任何 Android 渲染路径。
 */
@Composable
private fun SliderCaptchaPanel(
    backgroundImageBase64: String,
    sliderImageBase64: String,
    bgOriginalWidth: Int,
    bgOriginalHeight: Int,
    sliderOriginalWidth: Int,
    sliderOriginalHeight: Int,
    onSlideComplete: (SliderResult) -> Unit,
) {
    val currentOnSlideComplete by rememberUpdatedState(onSlideComplete)

    val bgBitmap = remember(backgroundImageBase64) { decodeBase64Image(backgroundImageBase64) }
    val sliderBitmap = remember(sliderImageBase64) { decodeBase64Image(sliderImageBase64) }

    if (bgBitmap == null || sliderBitmap == null) {
        Text("验证码加载失败", color = MiuixTheme.colorScheme.error)
        return
    }

    val density = LocalDensity.current

    // 尺寸兜底与 `:data` 那份同一条口径：服务端字段只在位图不可用时顶上（除数不能是 0）。
    val bgW = bgOriginalWidth.takeIf { it > 0 } ?: bgBitmap.width
    val bgH = bgOriginalHeight.takeIf { it > 0 } ?: bgBitmap.height
    val slW = sliderOriginalWidth.takeIf { it > 0 } ?: sliderBitmap.width
    val slH = sliderOriginalHeight.takeIf { it > 0 } ?: sliderBitmap.height
    if (bgW <= 0 || bgH <= 0) {
        Text("验证码尺寸异常", color = MiuixTheme.colorScheme.error)
        return
    }

    val displayWidthDp = 260.dp
    val displayHeightDp = with(density) { (displayWidthDp.toPx() * bgH / bgW).toDp() }
    val displayWidthPx = with(density) { displayWidthDp.toPx() }
    val sliderDisplayWidthPx = slW * displayWidthPx / bgW
    val sliderDisplayWidthDp = with(density) { sliderDisplayWidthPx.toDp() }
    val maxSlideX = displayWidthPx - sliderDisplayWidthPx

    // 服务器期望的显示坐标系参数（与 `:data` 那份、与网页端一致：260-based）
    val serverBgWidth = 260
    val serverSliderHeight = (slH * 260.0 / bgW).roundToInt()

    var offsetX by remember { mutableFloatStateOf(0f) }
    var cumulativeY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val trackPoints = remember { mutableListOf<TrackPoint>() }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    val captchaViewDelay = remember { (800..1500).random().toLong() }

    Column(
        modifier = Modifier.width(displayWidthDp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 拼图区域：背景 + 滑块（与 Android 那份同布局）
        Box(
            modifier = Modifier
                .width(displayWidthDp)
                .height(displayHeightDp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            Image(
                bitmap = bgBitmap,
                contentDescription = "验证码背景",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Image(
                bitmap = sliderBitmap,
                contentDescription = "滑块",
                modifier = Modifier
                    .size(sliderDisplayWidthDp, displayHeightDp)
                    .offset { IntOffset(offsetX.roundToInt(), 0) },
                contentScale = ContentScale.FillBounds,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 滑动条：拖动距离 → x 坐标事件（记录成与 Android 同协议的 track）
        Box(
            modifier = Modifier
                .width(displayWidthDp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (!isDragging && offsetX == 0f) {
                Text(
                    "向右拖动滑块完成验证",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .size(40.dp)
                    .shadow(2.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragStartTime = System.currentTimeMillis()
                                cumulativeY = 0f
                                trackPoints.clear()
                                trackPoints.add(TrackPoint(0, 0, "down", captchaViewDelay))
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxSlideX)
                                cumulativeY += dragAmount.y
                                trackPoints.add(
                                    TrackPoint(
                                        x = (offsetX * serverBgWidth / displayWidthPx).roundToInt(),
                                        y = (cumulativeY * serverBgWidth / displayWidthPx).roundToInt(),
                                        type = "move",
                                        t = captchaViewDelay + (System.currentTimeMillis() - dragStartTime),
                                    )
                                )
                            },
                            onDragEnd = {
                                isDragging = false
                                val end = System.currentTimeMillis()
                                if (dragStartTime == 0L) dragStartTime = end
                                currentOnSlideComplete(
                                    encodeDesktopSlideResult(
                                        serverSliderHeight = serverSliderHeight,
                                        offsetXPx = offsetX,
                                        cumulativeYPx = cumulativeY,
                                        displayWidthPx = displayWidthPx,
                                        dragStartTime = dragStartTime,
                                        dragEndTime = end,
                                        trackPoints = trackPoints.toList(),
                                    )
                                )
                            },
                            onDragCancel = {
                                isDragging = false
                                offsetX = 0f
                                trackPoints.clear()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("→", color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.body1)
            }
        }
    }
}

/**
 * 把一次手动拖动的原始状态合成服务端 [SliderResult] —— 与 `:data` 的 `SliderCaptchaView` 的
 * `onDragEnd` 同一个协议：260 坐标系、up 点带位移、两个时刻是 ISO_INSTANT 的真实时刻。
 * 单独抽出来是为了让 :desktop 的测试能**不点开窗口**就钉住这条编码路径。
 *
 * @param trackPoints 已按 260 坐标系记录好的 down/move 点（不含 up 点）。
 */
internal fun encodeDesktopSlideResult(
    serverSliderHeight: Int,
    offsetXPx: Float,
    cumulativeYPx: Float,
    displayWidthPx: Float,
    dragStartTime: Long,
    dragEndTime: Long,
    trackPoints: List<TrackPoint>,
): SliderResult {
    val serverBgWidth = 260
    val displayX = (offsetXPx * serverBgWidth / displayWidthPx).roundToInt()
    val displayY = (cumulativeYPx * serverBgWidth / displayWidthPx).roundToInt()
    val points = trackPoints.toMutableList()
    // up 点时刻与 `:data` 那份同一个公式：验证码出现延迟 + 真实拖动时长
    val upT = (points.firstOrNull()?.t ?: 0L) + (dragEndTime - dragStartTime).coerceAtLeast(0L)
    points += TrackPoint(displayX, displayY, "up", upT)
    val fmt = java.time.format.DateTimeFormatter.ISO_INSTANT
    return SliderResult(
        bgImageWidth = serverBgWidth,
        bgImageHeight = 0,
        sliderImageWidth = 0,
        sliderImageHeight = serverSliderHeight,
        startSlidingTime = fmt.format(java.time.Instant.ofEpochMilli(dragStartTime)),
        entSlidingTime = fmt.format(java.time.Instant.ofEpochMilli(dragEndTime)),
        trackList = points,
    )
}

/** 解码 data URI base64 图片（桌面侧 = skiko，走 `:core` 的缝）。 */
private fun decodeBase64Image(dataUri: String): ImageBitmap? {
    return try {
        val base64Str = dataUri.substringAfter("base64,")
        val bytes = Base64.getDecoder().decode(base64Str)
        decodeImageFull(bytes)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode image", e)
        null
    }
}