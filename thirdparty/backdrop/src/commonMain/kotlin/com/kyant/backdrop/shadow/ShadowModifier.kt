package com.kyant.backdrop.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.blur
import kotlin.math.ceil

internal class ShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> Shadow?
) : ModifierNodeElement<ShadowNode>() {

    override fun create(): ShadowNode {
        return ShadowNode(shapeProvider, shadow)
    }

    override fun update(node: ShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class ShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> Shadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private val paint = Paint()

    /** 上一次设进 [paint] 的模糊半径：半径没变就别重建 `BlurMaskFilter`（见 HighlightModifier 同名注释）。 */
    private var appliedBlurRadius = Float.NaN

    /**
     * [岱宗盒子 vendor 改动] 上一次录进 [shadowLayer] 时的全部输入。没变就只把旧层画上去：这一层是离屏的，
     * 每重录一次系统就要把带模糊的阴影整块重新栅格化；玻璃滚动、被采样时整块重画，上游每次都重录（Kyant #126 第 1 条）。
     */
    private var recordedKey: ShadowRecordKey? = null

    override fun ContentDrawScope.draw() {
        val shadow = shadow() ?: return drawContent()

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val radius = shadow.radius.toPx()
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()
            val key = ShadowRecordKey(
                size, density.density, layoutDirection, shapeProvider.innerShape,
                shadow.radius, shadow.offset, shadow.color,
            )
            if (key != recordedKey) {
                val shadowSize = IntSize(
                    ceil(size.width + radius * 4f + offsetX).toInt(),
                    ceil(size.height + radius * 4f + offsetY).toInt()
                )
                val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)

                configurePaint(shadow)

                shadowLayer.record(shadowSize) {
                    translate(radius * 2f + offsetX, radius * 2f + offsetY) {
                        val canvas = drawContext.canvas
                        canvas.drawOutline(outline, paint)
                        canvas.translate(-offsetX, -offsetY)
                        canvas.drawOutline(outline, ShadowMaskPaint)
                        canvas.translate(offsetX, offsetY)
                    }
                }
                recordedKey = key
            }
            shadowLayer.alpha = shadow.alpha
            shadowLayer.blendMode = shadow.blendMode

            translate(-radius * 2f, -radius * 2f) {
                drawLayer(shadowLayer)
            }
        }

        drawContent()
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer =
            graphicsContext.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
        }
        recordedKey = null
    }

    private fun DrawScope.configurePaint(shadow: Shadow) {
        paint.color = shadow.color
        val blurRadius = shadow.radius.toPx()
        if (blurRadius != appliedBlurRadius) {
            appliedBlurRadius = blurRadius
            paint.blur(blurRadius)
        }
    }
}

/** 决定阴影层内容的全部输入；透明度与混合模式是图层属性，不进键。 */
private data class ShadowRecordKey(
    val size: androidx.compose.ui.geometry.Size,
    val density: Float,
    val layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    val shape: androidx.compose.ui.graphics.Shape,
    val radius: androidx.compose.ui.unit.Dp,
    val offset: androidx.compose.ui.unit.DpOffset,
    val color: androidx.compose.ui.graphics.Color,
)

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
