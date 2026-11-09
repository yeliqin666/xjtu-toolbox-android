package com.xjtu.toolbox.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.movedFrom
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 顶栏的无边界渐变模糊（useplanes 的 progressive blur）：七段半径递增的模糊，每段用一条渐变遮罩
 * 只留一截，顶边最强、底边归零，再盖一层从顶到底淡出的表面色。只画不量。
 *
 * 开销都花在模糊上，所以每段只渲染自己露出来的那一截，半径够大的段再降采样（糊完放大看不出差别），
 * 七段平铺在一个节点里，不互相嵌套离屏。采样位置和内容不变时 HWUI 直接复用结果。
 *
 * 采样前先垫一层不透明表面色，否则模糊把透明当黑色，字周围会出脏黑边。
 */
fun Modifier.progressiveBlurBar(backdrop: Backdrop, tint: Color, titled: Boolean = true): Modifier =
    this.clipToBounds() then ProgressiveBlurElement(backdrop, tint, titled)

private data class ProgressiveBlurElement(
    val backdrop: Backdrop,
    val tint: Color,
    val titled: Boolean,
) : ModifierNodeElement<ProgressiveBlurNode>() {
    override fun create() = ProgressiveBlurNode(backdrop, tint, titled)

    override fun update(node: ProgressiveBlurNode) {
        node.backdrop = backdrop
        node.tint = tint
        node.titled = titled
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "progressiveBlurBar"
    }
}

private class ProgressiveBlurNode(
    var backdrop: Backdrop,
    var tint: Color,
    var titled: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var layers: List<GraphicsLayer> = emptyList()
    private var coordinates: LayoutCoordinates? = null
    private var lastSampleOffset: Offset? = null

    override fun onAttach() {
        if (!isRenderEffectSupported()) return
        val context = requireGraphicsContext()
        val density = requireDensity()
        layers = BlurBands.map { band ->
            context.createGraphicsLayer().apply {
                val radius = with(density) { band.radius.dp.toPx() }
                val blur = radius / scaleFor(radius)
                renderEffect = BlurEffect(blur, blur, TileMode.Decal)
            }
        }
    }

    override fun onDetach() {
        val context = requireGraphicsContext()
        layers.forEach(context::releaseGraphicsLayer)
        layers = emptyList()
        coordinates = null
        lastSampleOffset = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        this.coordinates = coordinates
        // 父级整体缩放、位移时这里每帧回调；相对采样源没动就不重录
        val offset = backdrop.sampleOffset(coordinates)
        val previous = lastSampleOffset
        if (previous == null || offset.movedFrom(previous)) {
            lastSampleOffset = offset
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val coords = coordinates
        if (coords != null && layers.isNotEmpty() && size.height > 0f) drawBands(coords)
        drawRect(washBrush())
        drawContent()
    }

    private fun ContentDrawScope.drawBands(coords: LayoutCoordinates) {
        val full = size
        val density = this
        val source = backdrop
        val opaque = tint.copy(alpha = 1f)
        BlurBands.forEachIndexed { index, band ->
            val radius = band.radius.dp.toPx()
            val scale = scaleFor(radius)
            // 模糊的拖尾约 1.8 个半径，多渲染这一段，段边缘才不被切齐
            val tail = radius * 1.8f
            val top = max(0f, floor(band.start * full.height - tail))
            val bottom = min(full.height, ceil(band.end * full.height + tail))
            if (bottom <= top) return@forEachIndexed
            val layerSize = IntSize(
                ceil(full.width / scale).toInt().coerceAtLeast(1),
                ceil((bottom - top) / scale).toInt().coerceAtLeast(1),
            )
            val layer = layers[index]
            layer.record(layerSize) {
                val recordScope = this
                withTransform({
                    scale(1f / scale, 1f / scale, pivot = Offset.Zero)
                    translate(0f, -top)
                }) {
                    drawRect(opaque, size = full)
                    with(source) { recordScope.drawBackdrop(density, coords, null) }
                    drawRect(
                        band.mask(full.height),
                        size = full,
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            withTransform({
                translate(0f, top)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawLayer(layer)
            }
        }
    }

    /** 模糊之上、标题之下，到底边淡到 0；有标题的上半段多压一点表面色，保证字清楚。 */
    private fun washBrush(): Brush =
        if (titled) {
            Brush.verticalGradient(
                0f to tint.copy(alpha = (tint.alpha + 0.2f).coerceAtMost(1f)),
                0.7f to tint,
                1f to tint.copy(alpha = 0f),
            )
        } else {
            Brush.verticalGradient(0f to tint, 1f to tint.copy(alpha = 0f))
        }
}

/** 半径（px）对应的降采样倍数：糊完的细节已经比放大后的像素粗。 */
private fun scaleFor(radiusPx: Float): Float = when {
    radiusPx < 6f -> 1f
    radiusPx < 18f -> 2f
    else -> 4f
}

/** 一段模糊：[radius] 单位 dp，[stops] 是「位置（0 顶 → 1 底）→ 不透明度」。 */
private class BlurBand(val radius: Float, private val stops: List<Pair<Float, Float>>) {
    val start: Float = stops.first().first
    val end: Float = stops.last().first

    /** 渐变写死在整条带的坐标里：这一段的图层只有一截，不能按图层尺寸算。 */
    fun mask(height: Float): Brush = Brush.verticalGradient(
        *stops.map { (position, alpha) -> position to Color.Black.copy(alpha = alpha) }.toTypedArray(),
        startY = 0f,
        endY = height,
    )
}

/** 七段半径（useplanes 默认 ramp × 1.5），从底边到顶边。 */
private val BlurRadii = listOf(0.75f, 1.5f, 3f, 4.5f, 7.5f, 12f, 18f)

/** 段与段错开 12.5%，每段是「透明 → 不透明 → 不透明 → 透明」的一条带。 */
private const val BLUR_STEP = 0.125f

private val BlurBands: List<BlurBand> = BlurRadii.mapIndexed { index, radius ->
    BlurBand(
        radius,
        listOf(
            (1f - (index + 3) * BLUR_STEP) to 0f,
            (1f - (index + 2) * BLUR_STEP) to 1f,
            (1f - (index + 1) * BLUR_STEP) to 1f,
            (1f - index * BLUR_STEP) to 0f,
        ).clipToUnitRange(),
    )
}

/** 把越出 [0, 1] 的渐变端点裁掉，并在边界上按原斜率补一个端点。 */
private fun List<Pair<Float, Float>>.clipToUnitRange(): List<Pair<Float, Float>> {
    fun alphaAt(position: Float): Float {
        val before = lastOrNull { it.first <= position } ?: first()
        val after = firstOrNull { it.first >= position } ?: last()
        if (before.first == after.first) return after.second
        val t = (position - before.first) / (after.first - before.first)
        return before.second + (after.second - before.second) * t
    }

    val visible = filter { it.first in 0f..1f }.toMutableList()
    if (first().first > 0f) visible.add(0, 0f to alphaAt(0f))
    if (last().first < 1f) visible.add(1f to alphaAt(1f))
    return visible.sortedBy { it.first }
}
