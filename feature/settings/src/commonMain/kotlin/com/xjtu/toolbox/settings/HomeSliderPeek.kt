package com.xjtu.toolbox.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页设置抽屉里「拖背景滑条时只留滑条」（#433）。
 *
 * 照 HyperOS 控制中心调亮度的做法：手指一拖背景那两条（模糊程度、背景不透明度），抽屉底色、
 * 遮罩、标题和其它行都收起来，只剩手上这根滑条和它上方一枚小字数值，好直接看主页背景的变化；
 * 松手立刻复原。
 *
 * 滑条本身**不换组件、不挪位置**：手势还在原来那根 `SliderPreference` 上，换掉它拖动就断了。
 * 所以「收起」是画法上的：整块内容照常排版、照常接收手势，只在绘制阶段裁成那根滑条的胶囊
 * （[drawOnlyActiveSlider]）；数值小字另画在内容上面（[HomeSliderPeekLabel]）。
 *
 * 拖动中首页要跟着变才看得见效果，可连续值平时只在松手 / 关抽屉时通知首页（逐帧通知会整页重组），
 * 这里折中按 [LIVE_INTERVAL_MS] 节流通知一次。
 */
@Stable
internal class HomeSliderPeek {
    /** 正在拖的那条的标题；null = 没在拖。 */
    var owner by mutableStateOf<String?>(null)
        private set
    /** 正在拖的那条此刻的取值文案（由那一行在组合时同步过来）。 */
    var valueText by mutableStateOf("")

    val active: Boolean get() = owner != null

    /** 每条可收起的滑条在滑条正上方放了一个 0 高的锚点，记的就是它的坐标。 */
    private val anchors = HashMap<String, LayoutCoordinates>()
    /** 被裁剪的那一层（抽屉的滚动内容）与数值小字所在容器的坐标。 */
    var content: LayoutCoordinates? = null
    var container: LayoutCoordinates? = null
    private var lastLive = TimeSource.Monotonic.markNow()

    fun anchor(title: String, coords: LayoutCoordinates) {
        anchors[title] = coords
    }

    fun start(title: String, text: String) {
        valueText = text
        owner = title
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

    /** 正在拖的那根滑条在 [space] 坐标系里的矩形；拿不到时 null。 */
    fun sliderRect(space: LayoutCoordinates?, sliderHeightPx: Float): Rect? {
        val key = owner ?: return null
        val anchor = anchors[key]?.takeIf { it.isAttached } ?: return null
        val base = space?.takeIf { it.isAttached } ?: return null
        val top = base.localBoundingBoxOf(anchor, clipBounds = false)
        return Rect(top.left, top.top, top.right, top.top + sliderHeightPx)
    }

    companion object {
        const val LIVE_INTERVAL_MS = 90L
    }
}

internal val LocalHomeSliderPeek = compositionLocalOf<HomeSliderPeek?> { null }

/** 拖动中只画正在拖的那根滑条（胶囊形，和 MIUIX 滑条同形），其余照常排版但不画。 */
internal fun ContentDrawScope.drawOnlyActiveSlider(peek: HomeSliderPeek) {
    if (!peek.active) {
        drawContent()
        return
    }
    val rect = peek.sliderRect(peek.content, SliderDefaults.MinHeight.toPx()) ?: return
    val radius = rect.height / 2f
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius, radius))) }
    clipPath(path) { this@drawOnlyActiveSlider.drawContent() }
}

/** 滑条正上方的数值小字；底色半透明，压在任何壁纸上都读得清。 */
@Composable
internal fun HomeSliderPeekLabel(peek: HomeSliderPeek) {
    val density = LocalDensity.current
    val rect = peek.sliderRect(peek.container, with(density) { SliderDefaults.MinHeight.toPx() }) ?: return
    val labelHeight = 24.dp
    val gap = 8.dp
    Box(
        Modifier
            .offset {
                IntOffset(rect.left.roundToInt(), (rect.top - (labelHeight + gap).toPx()).roundToInt())
            }
            .width(with(density) { rect.width.toDp() })
            .height(labelHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            peek.valueText,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.86f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}