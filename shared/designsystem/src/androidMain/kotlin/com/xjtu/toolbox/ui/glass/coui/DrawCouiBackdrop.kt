package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** 当前背景的离屏参数, size 不包含 padding, 单位为设备像素。 */
class CouiBackdropEffectScope internal constructor(internal val recordingDensity: Density) : Density by recordingDensity {
    var size: Size = Size.Zero
        internal set
    var padding: Float = 0f
}

/** 背景采样、录制和内容层序；子类决定外扩与怎样画采样。各节点独立拥有可变资源。 */
internal abstract class DrawCouiBackdropNode(var backdrop: CouiBackdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null
    private var coordinates: LayoutCoordinates? = null
    private var effectScope: CouiBackdropEffectScope? = null

    override fun onAttach() { layer = requireGraphicsContext().createGraphicsLayer() }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        coordinates = null
        effectScope = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        invalidateDraw()
    }

    protected abstract fun configureEffects(scope: CouiBackdropEffectScope)

    protected abstract fun drawSource(scope: DrawScope, source: DrawScope.() -> Unit)

    override fun ContentDrawScope.draw() {
        val target = coordinates
        if (target != null && target.isAttached && size.width > 0f && size.height > 0f) {
            val scope = effectScope?.takeIf { it.density == density && it.fontScale == fontScale }
                ?: CouiBackdropEffectScope(Density(density, fontScale)).also { effectScope = it }
            scope.size = size
            scope.padding = 0f
            configureEffects(scope)
            val padding = ceil(scope.padding).toInt()
            val targetLayer = checkNotNull(layer)
            targetLayer.topLeft = IntOffset(-padding, -padding)
            targetLayer.record(scope.recordingDensity, layoutDirection,
                IntSize(size.width.toInt() + 2 * padding, size.height.toInt() + 2 * padding)) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawSource(this) { backdrop.draw(this, target) }
                }
            }
            drawLayer(targetLayer)
        }
        drawContent()
    }
}
