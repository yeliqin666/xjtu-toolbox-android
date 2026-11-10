package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** 配置当前背景的离屏效果, size 不包含 padding, 单位为设备像素。 */
class CouiBackdropEffectScope internal constructor(internal val recordingDensity: Density) : Density by recordingDensity {
    var size: Size = Size.Zero
        internal set
    var padding: Float = 0f
    var renderEffect: RenderEffect? = null

    fun blur(radius: Float, edgeTreatment: TileMode = TileMode.Clamp) {
        if (radius <= 0f) return
        padding = maxOf(padding, ceil(radius))
        renderEffect = BlurEffect(renderEffect, radius, radius, edgeTreatment)
    }
}

/** 仅采样层应用效果；shape 裁剪采样、surface 与子内容。内部图层和坐标逐节点持有。 */
@Composable
fun Modifier.drawCouiBackdrop(
    backdrop: CouiBackdrop,
    shape: () -> Shape,
    effects: CouiBackdropEffectScope.() -> Unit,
    onDrawSurface: DrawScope.() -> Unit = {},
    onDrawBackdrop: (DrawScope.(DrawScope.() -> Unit) -> Unit)? = null,
): Modifier {
    return this
        .graphicsLayer {
            this.shape = shape()
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .then(DrawBackdropElement(backdrop, effects, onDrawSurface, onDrawBackdrop))
}

private data class DrawBackdropElement(
    val backdrop: CouiBackdrop,
    val effects: CouiBackdropEffectScope.() -> Unit,
    val surface: DrawScope.() -> Unit,
    val drawBackdrop: (DrawScope.(DrawScope.() -> Unit) -> Unit)?,
) : ModifierNodeElement<DrawCouiBackdropNode>() {
    override fun create() = DrawCouiBackdropNode(backdrop, effects, surface, drawBackdrop)
    override fun update(node: DrawCouiBackdropNode) {
        node.backdrop = backdrop
        node.effects = effects
        node.surface = surface
        node.drawBackdrop = drawBackdrop
    }
    override fun InspectorInfo.inspectableProperties() { name = "drawCouiBackdrop" }
}

/** 均匀与渐进背景共用采样、录制和内容层序；各节点独立拥有可变资源。 */
internal open class DrawCouiBackdropNode(
    var backdrop: CouiBackdrop,
    var effects: CouiBackdropEffectScope.() -> Unit = {},
    var surface: DrawScope.() -> Unit = {},
    var drawBackdrop: (DrawScope.(DrawScope.() -> Unit) -> Unit)? = null,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
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

    protected open fun configureEffects(scope: CouiBackdropEffectScope) { scope.effects() }

    protected open fun drawSource(scope: DrawScope, source: DrawScope.() -> Unit) {
        val custom = drawBackdrop
        if (custom != null) custom(scope, source) else source(scope)
    }

    override fun ContentDrawScope.draw() {
        val target = coordinates
        if (target != null && target.isAttached && size.width > 0f && size.height > 0f) {
            val scope = effectScope?.takeIf { it.density == density && it.fontScale == fontScale }
                ?: CouiBackdropEffectScope(Density(density, fontScale)).also { effectScope = it }
            scope.size = size
            scope.padding = 0f
            scope.renderEffect = null
            configureEffects(scope)
            val padding = ceil(scope.padding).toInt()
            val targetLayer = checkNotNull(layer)
            targetLayer.renderEffect = scope.renderEffect
            targetLayer.topLeft = IntOffset(-padding, -padding)
            targetLayer.record(scope.recordingDensity, layoutDirection,
                IntSize(size.width.toInt() + 2 * padding, size.height.toInt() + 2 * padding)) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawSource(this) { backdrop.draw(this, target) }
                }
            }
            drawLayer(targetLayer)
        }
        surface(this)
        drawContent()
    }
}
