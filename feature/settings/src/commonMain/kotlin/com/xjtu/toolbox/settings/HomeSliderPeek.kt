package com.xjtu.toolbox.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页设置抽屉里拖背景滑条（模糊程度、背景不透明度）时，让出视线看主页背景（#433）。
 *
 * 拖动时手上这根滑条照原样画，抽屉其余部分（底色、拖动条、标题、其它行）和背后的遮罩一起
 * 淡到完全看不见，滑条上方另有一枚数值小字；松手后渐变复原。
 *
 * 滑条**不换组件、不挪位置**：手势还在原来那根 `SliderPreference` 上，换掉拖动就断了。所以全在
 * 绘制阶段做，挂在整个抽屉面板上（[peekSheetModifier]），标题和拖动条也在其中：
 * - miuix 的遮罩只能开关，淡出期间关掉它，换成自己画的一层按 [fade] 调透明度；
 * - 面板内容整体按 1 - [fade] 画淡，滑条那块胶囊另按原样画回去。
 *
 * 拖动中首页要跟着变才看得见效果，按 [LIVE_INTERVAL_MS] 节流通知首页重读。
 */
@Stable
internal class HomeSliderPeek {
    /** 正在拖的那条的标题；null = 没在拖。 */
    var owner by mutableStateOf<String?>(null)
        private set
    /** 正在拖的那条此刻的取值文案（由那一行在组合时同步过来）。 */
    var valueText by mutableStateOf("")
    /** 淡出进度：0 = 抽屉照常，1 = 只剩滑条。由 [peekSheetModifier] 动画驱动。 */
    var fade by mutableFloatStateOf(0f)

    val active: Boolean get() = owner != null

    /** 最近一次拖的那条；松手后淡回的过程里还要按它把滑条照原样画。 */
    private var shown: String? = null
    /** 每条会让出视线的滑条在滑条正上方放了一个 0 高的锚点，记的就是它的坐标。 */
    private val anchors = HashMap<String, LayoutCoordinates>()
    var sheet: LayoutCoordinates? = null
    private var lastLive = TimeSource.Monotonic.markNow()

    fun anchor(title: String, coords: LayoutCoordinates) {
        anchors[title] = coords
    }

    fun start(title: String, text: String) {
        valueText = text
        owner = title
        shown = title
        lastLive = TimeSource.Monotonic.markNow()
    }

    fun finish() {
        owner = null
    }

    /** 拖动中节流通知首页重读。 */
    fun live(notify: () -> Unit) {
        if (lastLive.elapsedNow() >= LIVE_INTERVAL_MS.milliseconds) {
            lastLive = TimeSource.Monotonic.markNow()
            notify()
        }
    }

    /** 那根滑条在抽屉面板坐标系里的矩形；拿不到时 null。 */
    fun sliderRect(sliderHeightPx: Float): Rect? {
        val anchor = anchors[shown ?: return null]?.takeIf { it.isAttached } ?: return null
        val base = sheet?.takeIf { it.isAttached } ?: return null
        // 锚点 0 高，localBoundingBoxOf 遇到零面积会直接给 Rect.Zero，所以取左上角坐标加锚点宽度
        val topLeft = base.localPositionOf(anchor, Offset.Zero)
        return Rect(topLeft.x, topLeft.y, topLeft.x + anchor.size.width, topLeft.y + sliderHeightPx)
    }

    companion object {
        const val LIVE_INTERVAL_MS = 90L
        const val FADE_MS = 220
    }
}

internal val LocalHomeSliderPeek = compositionLocalOf<HomeSliderPeek?> { null }

/** 淡出期间 miuix 自带的遮罩要关掉（由 [peekSheetModifier] 自己画的那层接替）。 */
@Composable
internal fun HomeSliderPeek.ownDimActive(): Boolean = remember(this) { derivedStateOf { fade > 0f } }.value

/**
 * 挂在 `OverlayBottomSheet` 的 modifier 上，作用于整个面板（底色、拖动条、标题、内容）。
 * 遮罩画在面板后面，超出面板铺满整个窗口。
 */
@Composable
internal fun Modifier.peekSheetModifier(peek: HomeSliderPeek): Modifier {
    val anim = remember(peek) { Animatable(0f) }
    LaunchedEffect(peek, peek.active) {
        anim.animateTo(if (peek.active) 1f else 0f, tween(HomeSliderPeek.FADE_MS, easing = FastOutSlowInEasing)) {
            peek.fade = value
        }
    }
    val dim = MiuixTheme.colorScheme.windowDimming
    val labelBg = MiuixTheme.colorScheme.surface.copy(alpha = 0.86f)
    val labelStyle = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    return onGloballyPositioned { peek.sheet = it }.drawWithContent {
        val f = peek.fade
        if (f <= 0f) {
            drawContent()
            return@drawWithContent
        }
        // 接替 miuix 的遮罩：f 刚离开 0 时与它同深，随后淡到 0
        val far = 100_000f
        drawRect(dim.copy(alpha = dim.alpha * (1f - f)), topLeft = Offset(-far, -far), size = Size(far * 2, far * 2))
        val rect = peek.sliderRect(SliderDefaults.MinHeight.toPx())
        val path = rect?.let { r ->
            Path().apply { addRoundRect(RoundRect(r, CornerRadius(r.height / 2f, r.height / 2f))) }
        }
        if (f < 1f) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = 1f - f })
                if (path != null) clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() } else drawContent()
                canvas.restore()
            }
        }
        if (rect == null || path == null) return@drawWithContent
        clipPath(path) { this@drawWithContent.drawContent() }
        // 滑条正上方的数值小字，随淡出一起出现
        val text = measurer.measure(peek.valueText, labelStyle)
        val padH = 10.dp.toPx()
        val padV = 3.dp.toPx()
        val w = text.size.width + padH * 2
        val h = text.size.height + padV * 2
        val topLeft = Offset(rect.center.x - w / 2f, rect.top - 8.dp.toPx() - h)
        drawRoundRect(labelBg.copy(alpha = labelBg.alpha * f), topLeft, Size(w, h), CornerRadius(h / 2f, h / 2f))
        drawText(text, topLeft = topLeft + Offset(padH, padV), alpha = f)
    }
}
