package com.kyant.backdrop.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.SharedRuntimeShaders
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.blur
import com.kyant.backdrop.internal.clipOutline
import com.kyant.backdrop.internal.setRuntimeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.ceil

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: () -> Highlight?
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight)
    }

    override fun update(node: HighlightNode) {
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "highlight"
        properties["shapeProvider"] = shapeProvider
        properties["highlight"] = highlight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HighlightElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (highlight != other.highlight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        return result
    }
}

/** 决定高光层内容的全部输入（见 [HighlightNode.recordedKey]）。 */
private data class HighlightRecordKey(
    val size: Size,
    val density: Float,
    val layoutDirection: LayoutDirection,
    val shape: Shape,
    val width: Dp,
    val blurRadius: Dp,
    val style: HighlightStyle,
)

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: () -> Highlight?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private var prevStyle: HighlightStyle? = null

    /**
     * [岱宗盒子 vendor 改动] 上一次录进 [highlightLayer] 时的全部输入。没变就只把旧层画上去：
     * 玻璃一滚动、一被采样就整块重画，上游每次都要重录这一圈带模糊的描边（Kyant #126 第 1 条）。
     * 透明度与混合模式是图层属性，不进键。
     */
    private var recordedKey: HighlightRecordKey? = null

    /**
     * 上一次设进 [paint] 的模糊半径。[Paint.blur] 每次都会 new 一个 `BlurMaskFilter`（原生对象，
     * 建它要现算核表），而半径在一帧之内几乎从不变——卡片多的时候这里是纯粹的垃圾产出，
     * 半径没变就别重设（[岱宗盒子 vendor 改动]）。
     */
    private var appliedBlurRadius = Float.NaN

    override fun ContentDrawScope.draw() {
        val highlight = highlight()
        if (highlight == null || highlight.width.value <= 0f) {
            return drawContent()
        }

        drawContent()

        val highlightLayer = highlightLayer
        if (highlightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection
            val shape = shapeProvider.shape
            // 键里放真正的形状：外层包装对象终身不变，圆角动画时换的是里面这个
            val key = HighlightRecordKey(
                size, density.density, layoutDirection, shapeProvider.innerShape,
                highlight.width, highlight.blurRadius, highlight.style,
            )

            if (key != recordedKey) {
                val safeSize =
                    IntSize(
                        ceil(size.width).toInt() + 2,
                        ceil(size.height).toInt() + 2
                    )

                val outline = shape.createOutline(size, layoutDirection, density)
                val clipPath =
                    if (outline is Outline.Rounded) {
                        clipPath ?: Path().also { clipPath = it }
                    } else {
                        null
                    }

                configurePaint(highlight)

                highlightLayer.record(safeSize) {
                    translate(1f, 1f) {
                        val canvas = drawContext.canvas
                        canvas.save()
                        canvas.clipOutline(outline, clipPath)
                        canvas.drawOutline(outline, paint)
                        canvas.restore()
                    }
                }
                recordedKey = key
            }
            highlightLayer.alpha = highlight.alpha
            highlightLayer.blendMode = highlight.style.blendMode

            translate(-1f, -1f) {
                drawLayer(highlightLayer)
            }
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            highlightLayer = null
        }
        clipPath = null
        recordedKey = null
        prevStyle = null
    }

    private fun DrawScope.configurePaint(highlight: Highlight) {
        paint.color = highlight.style.color
        paint.strokeWidth = ceil(highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)) * 2f
        val blurRadius = highlight.blurRadius.toPx()
        if (blurRadius != appliedBlurRadius) {
            appliedBlurRadius = blurRadius
            paint.blur(blurRadius)
        }
        if (isRuntimeShaderSupported()) {
            val shader =
                with(highlight.style) {
                    createShader(
                        shape = shapeProvider.shape,
                        runtimeShaderCache = SharedRuntimeShaders
                    )
                }
            paint.setRuntimeShader(shader)
        }
    }
}
