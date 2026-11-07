package com.kyant.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.ShadowElement
import kotlin.math.roundToInt

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

/** 关掉采样时用的空 effects：effects 只对采样到的内容有意义，不采样时一概不跑。 */
private val NoEffects: BackdropEffectScope.() -> Unit = {}

// [岱宗盒子 vendor 扩展] `sampling = false` 的含义：
//   · 不录那层渲染采样源的离屏层（`drawBackdropLayer` 整段跳过）；
//   · 不执行 `effects`（不建 blur / lens / vibrancy 的 renderEffect）；
//   · 内容不再被压进 `CompositingStrategy.Offscreen`。
// 其余顺序一律照旧：onDrawBehind → onDrawSurface → drawContent → onDrawFront，高光（Highlight）
// 与投影（Shadow / InnerShadow）用的还是上游那两份绘制代码，所以观感与 `sampling = true` 一模一样。
// 用在「背景上没有可采细节」那一档（纯色 / 渐变，或壁纸已被模糊糊平）；有细节时照旧传 true。

/** 采样层允许的最小缩小倍数（再小就看得出糊了）。 */
private const val MIN_DOWNSAMPLE = 0.25f

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    sampling: Boolean = true,
    /**
     * [岱宗盒子 vendor 扩展] 采样层的降采样倍数：1 = 全分辨率，0.5 = 录一层一半大小的采样结果
     * 再放大画回去（像素数降到 1/4）。
     *
     * 只在「谁看都看不出来」的地方开：要模糊的那一层本来就被糊掉了细节，缩小一半再放大只在
     * 极低模糊半径下才看得出来。而 `blur()` / `lens()` 的参数是按 dp 写的，缩放坐标系之后
     * 外观尺寸不变（见 `BackdropEffectScopeImpl.update`）。
     */
    downsample: Float = 1f,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                sampling = sampling,
                downsample = downsample,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    sampling: Boolean = true,
    /** [岱宗盒子 vendor 扩展]，含义见 [drawPlainBackdrop]。 */
    downsample: Float = 1f,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                sampling = sampling,
                downsample = downsample,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val sampling: Boolean,
    val downsample: Float,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            sampling = sampling,
            downsample = downsample,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.sampling = sampling
        node.downsample = downsample
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["sampling"] = sampling
        properties["downsample"] = downsample
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (sampling != other.sampling) return false
        if (downsample != other.downsample) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + sampling.hashCode()
        result = 31 * result + downsample.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var sampling: Boolean,
    var downsample: Float,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        // [vendor] 不采样时不必把内容压进离屏层：那层只为采样结果与 renderEffect 服务
        if (sampling) {
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        }
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private var padding by mutableFloatStateOf(0f)

    /**
     * 采样层实际用的缩小倍数（1 = 全分辨率）。和 [padding] 一样由 [updateEffects] 写进来：
     * 它们都是「按缩小后的坐标系算出来的像素值」，绘制阶段必须和录采样层时用的是同一套。
     */
    private var sampleScale by mutableFloatStateOf(1f)

    /** 这一帧该按多大比例录采样层。不采样时不录层，一律当 1（绘制路径也不会用到它）。 */
    private val currentSampleScale: Float
        get() = if (sampling) downsample.coerceIn(MIN_DOWNSAMPLE, 1f) else 1f

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding
        val scale = sampleScale

        canvas.save()
        // 先平移再缩放：下面的内容一律按「原尺寸坐标」画，落到层里时整体小了 scale 倍
        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        onDrawBackdrop {
            with(backdrop) {
                drawBackdrop(
                    density = effectScope,
                    coordinates = layoutCoordinates,
                    layerBlock = layerBlock
                )
            }
        }
        canvas.restore()
    }

    private val drawBackdropLayer: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null) {
            val scale = sampleScale
            val padding = padding
            // padding 已经是缩小后坐标系里的像素；层的左上角在「放大回去之后」要正好落在 -padding
            val pad = padding.roundToInt()

            recordLayer(
                layer,
                size = IntSize(
                    (size.width * scale).roundToInt() + pad * 2,
                    (size.height * scale).roundToInt() + pad * 2
                ),
                block = recordBackdropBlock
            )

            layer.topLeft =
                if (pad != 0) IntOffset(-pad, -pad)
                else IntOffset.Zero
            if (scale == 1f) {
                drawLayer(layer)
            } else {
                // 降采样录的那一层放大画回去（双线性插值）；像素数只有 1/scale²
                withTransform({ scale(1f / scale, 1f / scale, pivot = Offset.Zero) }) {
                    drawLayer(layer)
                }
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this, currentSampleScale)) {
            updateEffects()
        }

        onDrawBehind?.invoke(this)
        // [vendor] 不采样：整段「录一张采样结果、再把那张画上去」都跳过
        if (sampling) drawBackdropLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            recordLayer(layer) {
                onDrawBehind?.invoke(this)
                if (sampling) drawBackdropLayer()
                onDrawSurface?.invoke(this)
                onDrawFront?.invoke(this)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects() }
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        // [vendor] 不采样时 effects 一律不跑：blur / lens / vibrancy 都是对采样结果的处理，
        // 采样源是空的时候既看不出效果、又要白付几趟 GPU 处理
        effectScope.apply(if (sampling) effects else NoEffects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        // 这两个都是「缩小后坐标系」里的像素值，录层与画层必须用同一份
        padding = effectScope.padding
        sampleScale = currentSampleScale
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        observeEffects()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
    }
}
