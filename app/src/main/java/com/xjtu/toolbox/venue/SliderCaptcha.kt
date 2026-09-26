package com.xjtu.toolbox.venue

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private const val TAG = "SliderCaptcha"

/**
 * 滑动轨迹中的单个点
 */
@Serializable
data class TrackPoint(
    val x: Int,
    val y: Int,
    val type: String,  // "down", "move", "up"
    val t: Long        // 相对时间戳（ms）
)

/** 滑动验证码结果，发给服务器验证；字段名是服务端协议的一部分（含拼写 entSlidingTime），不能改。 */
@Serializable
data class SliderResult(
    val bgImageWidth: Int,
    val bgImageHeight: Int,
    val sliderImageWidth: Int,
    val sliderImageHeight: Int,
    val startSlidingTime: String,   // ISO 8601
    val entSlidingTime: String,     // ISO 8601
    val trackList: List<TrackPoint>
) {
    fun toJson(): String = AppJson.encodeToString(this)
}

/**
 * 滑动拼图验证码组件
 *
 * @param backgroundImageBase64 背景图 data URI (data:image/jpeg;base64,...)
 * @param sliderImageBase64 滑块图 data URI (data:image/png;base64,...)
 * @param bgOriginalWidth 背景图原始宽度 (px)
 * @param bgOriginalHeight 背景图原始高度 (px)
 * @param sliderOriginalWidth 滑块原始宽度 (px)
 * @param sliderOriginalHeight 滑块原始高度 (px)
 * @param onSlideComplete 滑动完成回调，返回 SliderResult
 */
@Composable
fun SliderCaptchaView(
    backgroundImageBase64: String,
    sliderImageBase64: String,
    bgOriginalWidth: Int,
    bgOriginalHeight: Int,
    sliderOriginalWidth: Int,
    sliderOriginalHeight: Int,
    onSlideComplete: (SliderResult) -> Unit
) {
    // pointerInput(Unit) 的手势协程会跨重组存活；换一张验证码后必须调用最新回调，
    // 否则旧闭包可能拿着上一张验证码的 id 提交。
    val currentOnSlideComplete by rememberUpdatedState(onSlideComplete)

    // 解码图片
    val bgBitmap = remember(backgroundImageBase64) {
        decodeBase64Image(backgroundImageBase64)
    }
    val sliderBitmap = remember(sliderImageBase64) {
        decodeBase64Image(sliderImageBase64)
    }

    if (bgBitmap == null || sliderBitmap == null) {
        Text("验证码加载失败", color = MiuixTheme.colorScheme.error)
        return
    }

    val density = LocalDensity.current

    // 尺寸一律以**解码出来的位图**为准，服务端字段只在位图不可用时兜底。
    // 服务端偶发把 backgroundImageWidth 之类的字段给成 0 或干脆不给，而下面每一处
    // 布局尺寸都要拿 bgW 做除数——一旦为 0，displayHeightDp 会算出 Infinity.dp，
    // 整个滑块 Box 的高度非法，组件静默渲染不出来，界面上就是"点了确认预订没弹滑块"。
    // 这个坑此前被自动解题挡住了（自动路径压根不渲染滑块），去掉自动解题后才暴露。
    val bgW = bgOriginalWidth.takeIf { it > 0 } ?: bgBitmap.width
    val bgH = bgOriginalHeight.takeIf { it > 0 } ?: bgBitmap.height
    val slW = sliderOriginalWidth.takeIf { it > 0 } ?: sliderBitmap.width
    val slH = sliderOriginalHeight.takeIf { it > 0 } ?: sliderBitmap.height
    Log.d(
        TAG,
        "dims: server=(bg $bgOriginalWidth x $bgOriginalHeight, slider $sliderOriginalWidth x $sliderOriginalHeight) " +
            "bitmap=(bg ${bgBitmap.width} x ${bgBitmap.height}, slider ${sliderBitmap.width} x ${sliderBitmap.height}) " +
            "used=(bg $bgW x $bgH, slider $slW x $slH)"
    )
    if (bgW <= 0 || bgH <= 0) {
        Text("验证码尺寸异常", color = MiuixTheme.colorScheme.error)
        return
    }

    // 显示宽度 = 260dp（与网页端一致），高度按比例
    val displayWidthDp = 260.dp
    val displayHeightDp = with(density) {
        (displayWidthDp.toPx() * bgH / bgW).toDp()
    }
    val displayWidthPx = with(density) { displayWidthDp.toPx() }

    // 滑块显示尺寸（按相同比例缩放）
    val scaleRatio = displayWidthPx / bgW
    val sliderDisplayWidthPx = slW * scaleRatio
    val sliderDisplayWidthDp = with(density) { sliderDisplayWidthPx.toDp() }

    // 最大滑动距离
    val maxSlideX = displayWidthPx - sliderDisplayWidthPx

    // 服务器期望的显示坐标系参数 (260-based, 与网页 CSS px 一致)
    val serverBgWidth = 260
    val serverSliderHeight = (slH * 260.0 / bgW).roundToInt()

    // 滑动状态
    var offsetX by remember { mutableFloatStateOf(0f) }
    var cumulativeY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val trackPoints = remember { mutableListOf<TrackPoint>() }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    // 模拟用户看到验证码后到开始拖动的延迟 (800-1500ms)
    val captchaViewDelay = remember { (800..1500).random().toLong() }

    Column(
        modifier = Modifier.width(displayWidthDp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 拼图区域
        Box(
            modifier = Modifier
                .width(displayWidthDp)
                .height(displayHeightDp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            // 背景图
            Image(
                bitmap = bgBitmap.asImageBitmap(),
                contentDescription = "验证码背景",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            // 滑块
            Image(
                bitmap = sliderBitmap.asImageBitmap(),
                contentDescription = "滑块",
                modifier = Modifier
                    .size(sliderDisplayWidthDp, displayHeightDp)
                    .offset { IntOffset(offsetX.roundToInt(), 0) },
                contentScale = ContentScale.FillBounds
            )
        }

        Spacer(Modifier.height(12.dp))

        // 滑动条
        Box(
            modifier = Modifier
                .width(displayWidthDp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
        ) {
            // 提示文字
            if (!isDragging && offsetX == 0f) {
                Text(
                    "向右拖动滑块完成验证",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 滑块按钮
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .size(40.dp)
                    .shadow(2.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                dragStartTime = System.currentTimeMillis()
                                cumulativeY = 0f
                                trackPoints.clear()
                                trackPoints.add(
                                    TrackPoint(0, 0, "down", captchaViewDelay)
                                )
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val newX = (offsetX + dragAmount.x).coerceIn(0f, maxSlideX)
                                offsetX = newX
                                cumulativeY += dragAmount.y

                                // 转换为 260 显示坐标系（服务器期望的坐标）
                                val displayX = (newX * serverBgWidth / displayWidthPx).roundToInt()
                                val displayY = (cumulativeY * serverBgWidth / displayWidthPx).roundToInt()
                                trackPoints.add(
                                    TrackPoint(
                                        displayX, displayY, "move",
                                        captchaViewDelay + (System.currentTimeMillis() - dragStartTime)
                                    )
                                )
                            },
                            onDragEnd = {
                                isDragging = false
                                val displayX = (offsetX * serverBgWidth / displayWidthPx).roundToInt()
                                val displayY = (cumulativeY * serverBgWidth / displayWidthPx).roundToInt()
                                trackPoints.add(
                                    TrackPoint(
                                        displayX, displayY, "up",
                                        captchaViewDelay + (System.currentTimeMillis() - dragStartTime)
                                    )
                                )

                                val startTime = java.time.Instant.ofEpochMilli(dragStartTime)
                                val endTime = java.time.Instant.now()
                                val fmt = java.time.format.DateTimeFormatter.ISO_INSTANT

                                val result = SliderResult(
                                    bgImageWidth = serverBgWidth,
                                    bgImageHeight = 0,
                                    sliderImageWidth = 0,
                                    sliderImageHeight = serverSliderHeight,
                                    startSlidingTime = fmt.format(startTime),
                                    entSlidingTime = fmt.format(endTime),
                                    trackList = trackPoints.toList()
                                )
                                Log.d(TAG, "Slide complete: displayX=$displayX, points=${trackPoints.size}")
                                currentOnSlideComplete(result)
                            },
                            onDragCancel = {
                                isDragging = false
                                offsetX = 0f
                                trackPoints.clear()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "→",
                    color = MiuixTheme.colorScheme.onPrimary,
                    style = MiuixTheme.textStyles.body1
                )
            }
        }
    }
}

/**
 * 解码 data URI base64 图片
 */
private fun decodeBase64Image(dataUri: String): android.graphics.Bitmap? {
    return try {
        val base64Str = dataUri.substringAfter("base64,")
        val bytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode image", e)
        null
    }
}
