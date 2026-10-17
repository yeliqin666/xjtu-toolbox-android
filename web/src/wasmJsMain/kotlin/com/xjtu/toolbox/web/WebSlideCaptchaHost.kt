package com.xjtu.toolbox.web

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.xjtu.toolbox.platform.decodeImageFull
import com.xjtu.toolbox.venue.CaptchaData
import com.xjtu.toolbox.venue.SlideCaptchaHost
import com.xjtu.toolbox.venue.SliderResult
import com.xjtu.toolbox.venue.SolvedCaptcha
import com.xjtu.toolbox.venue.TrackPoint
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 场馆滑块验证码的 Web（Kotlin/Wasm）宿主 —— serve 模式下场馆下单要的就是它。 */
object WebSlideCaptchaHost : SlideCaptchaHost {

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

    /** 浏览器里没有识别器 ⇒ null。 */
    override suspend fun solve(data: CaptchaData, shownAtMillis: Long): SolvedCaptcha? = null
}

/** 浏览器里的滑块面板。 */
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
    // pointerInput(Unit) 的手势协程跨重组存活 ⇒ 必须调最新回调，否则旧闭包会拿着上一张验证码的 id 提交
    val currentOnSlideComplete by rememberUpdatedState(onSlideComplete)

    val bgBitmap = remember(backgroundImageBase64) { decodeBase64Image(backgroundImageBase64) }
    val sliderBitmap = remember(sliderImageBase64) { decodeBase64Image(sliderImageBase64) }

    if (bgBitmap == null || sliderBitmap == null) {
        Text("验证码加载失败", color = MiuixTheme.colorScheme.error)
        return
    }

    val density = LocalDensity.current

    // 服务端尺寸只在位图不可用时顶上（下面布局都拿 bgW 做除数）
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
    // 模拟用户看到验证码后到开始拖动的延迟 (800-1500ms) —— 与两端同一个区间
    val captchaViewDelay = remember { (800..1500).random().toLong() }

    Column(
        modifier = Modifier.width(displayWidthDp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 拼图区域：背景 + 滑块（与其余两端同布局）
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

        // 滑动条：拖动距离 → 260 坐标系里的 x 事件（记成与其余两端同协议的 track）
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
                                dragStartTime = nowMillis()
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
                                        t = captchaViewDelay + (nowMillis() - dragStartTime),
                                    ),
                                )
                            },
                            onDragEnd = {
                                isDragging = false
                                val end = nowMillis()
                                if (dragStartTime == 0L) dragStartTime = end
                                currentOnSlideComplete(
                                    encodeWebSlideResult(
                                        serverSliderHeight = serverSliderHeight,
                                        offsetXPx = offsetX,
                                        cumulativeYPx = cumulativeY,
                                        displayWidthPx = displayWidthPx,
                                        dragStartTime = dragStartTime,
                                        dragEndTime = end,
                                        trackPoints = trackPoints.toList(),
                                    ),
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

/** 把一次手动拖动的原始状态合成服务端 [SliderResult]。 */
internal fun encodeWebSlideResult(
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
    // up 点时刻与其余两端同一个公式：验证码出现延迟 + 真实拖动时长
    val upT = (points.firstOrNull()?.t ?: 0L) + (dragEndTime - dragStartTime).coerceAtLeast(0L)
    points += TrackPoint(displayX, displayY, "up", upT)
    return SliderResult(
        bgImageWidth = serverBgWidth,
        bgImageHeight = 0,
        sliderImageWidth = 0,
        sliderImageHeight = serverSliderHeight,
        startSlidingTime = isoInstant(dragStartTime),
        entSlidingTime = isoInstant(dragEndTime),
        trackList = points,
    )
}

/** `java.time.Instant.ofEpochMilli(ms)` → ISO_INSTANT 那个形状（wasm 上没有 `java.time`）。 */
private fun isoInstant(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis).toString()

/** 浏览器里的「现在」（与 `:core` 其余地方同一句 `Clock.System.now()`）。 */
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 解码 data URI 的 base64 图片（wasm 侧 = skiko，走 `:core` 的缝）。 */
private fun decodeBase64Image(dataUri: String): ImageBitmap? = runCatching {
    decodeImageFull(Base64.Default.decode(dataUri.substringAfter("base64,")))
}.getOrNull()
