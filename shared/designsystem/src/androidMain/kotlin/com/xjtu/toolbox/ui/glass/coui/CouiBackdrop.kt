package com.xjtu.toolbox.ui.glass.coui

import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import org.json.JSONObject

/** 每个源只挂到一个内容节点, 该子树不得采样自身。parent 用于叠加所属窗口。 */
@Composable
fun rememberCouiBackdrop(
    parent: CouiBackdrop? = null,
    diagnosticName: String? = null,
    drawSource: ContentDrawScope.() -> Unit = { drawContent() },
): CouiBackdrop {
    var diagnosticGeneration by remember { mutableIntStateOf(0) }
    val layer = key(diagnosticGeneration) { rememberGraphicsLayer() }
    val source = rememberUpdatedState(drawSource)
    return remember(layer, parent, diagnosticName) {
        CouiBackdrop(layer, parent, diagnosticName, { diagnosticGeneration++ }) { source.value(this) }
    }
}

fun Modifier.couiBackdrop(backdrop: CouiBackdrop): Modifier = then(BackdropElement(backdrop))

/** 只持有源图层和坐标, 不持有效果、组件动画或输入状态。 */
class CouiBackdrop internal constructor(
    private val layer: GraphicsLayer,
    private val parent: CouiBackdrop?,
    internal val diagnosticName: String?,
    private val recreateForDiagnostics: () -> Unit,
    private val drawSource: ContentDrawScope.() -> Unit,
) {
    private var drawPassCount = 0L
    private var recordCallbackCount = 0L
    private var sampleCount = 0L
    private var lastRecordMs = 0L
    // 采样源默认不离屏：内容直接画上屏，采样者（顶栏、底栏）在各自那一小块里重放同一份绘制指令。
    // 离屏的话内容每变一次就要整屏栅格化一遍再贴回去，壳的源里还套着页面的源，滚动、转场时
    // 每帧多出两遍整屏填充和两张整屏纹理。只有需要隔离内容的菜单层才置 true。
    private var isolate = false
    private var activeConsumers = 0
    private val redrawSignal = mutableStateOf(Unit, neverEqualPolicy())
    // 自上次通知以来有没有跨窗口的采样者读过信号。没人读时不写：每次写都会让 Recomposer 再排一帧空帧
    private var crossWindowReader = false
    private val sourceToTarget = Matrix()
    // 首次采样可能先于源绘制, 就绪状态必须唤醒已等待的消费者。
    private var recorded by mutableStateOf(false)
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    init {
        layer.compositingStrategy = CompositingStrategy.Auto
        layer.clip = true
    }

    internal fun setIsolated(isolated: Boolean) {
        isolate = isolated
        updateCompositingStrategy()
    }

    /** 材质组合在退出动画期间仍须存在, 源及父链随该组合成对保留和释放。 */
    @Composable
    internal fun RetainWhileComposed() {
        DisposableEffect(this) {
            retain()
            onDispose { release() }
        }
    }

    private fun retain() {
        activeConsumers++
        parent?.retain()
    }

    private fun release() {
        check(activeConsumers > 0) { "背景源消费者必须成对释放" }
        activeConsumers--
        parent?.release()
    }

    private fun updateCompositingStrategy() {
        if (layer.isReleased) return
        layer.compositingStrategy = if (isolate) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }

    /** 目标 DrawScope 原点须对应 target; 扩展采样区由调用方平移。 */
    fun draw(scope: DrawScope, target: LayoutCoordinates) {
        if (CouiRenderDiagnostics.enabled) sampleCount++
        parent?.draw(scope, target)
        val source = coordinates ?: return
        if (!recorded || !source.isAttached || !target.isAttached) return
        // 同窗口沿用 RenderNode 依赖更新; 仅跨窗口订阅 PreDraw, 避免持续驱动页面重绘。
        if (source.findRootCoordinates() !== target.findRootCoordinates()) {
            crossWindowReader = true
            redrawSignal.value
        }
        val width = source.size.width.toFloat()
        val height = source.size.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val origin = target.screenToLocal(source.localToScreen(Offset.Zero))
        val right = target.screenToLocal(source.localToScreen(Offset(width, 0f)))
        val bottom = target.screenToLocal(source.localToScreen(Offset(0f, height)))
        if (!origin.isFinite || !right.isFinite || !bottom.isFinite) return
        val xAxis = (right - origin) / width
        val yAxis = (bottom - origin) / height
        // shortcut: 仅支持二维仿射; 组件引入三维翻转时需使用完整投影矩阵。
        sourceToTarget[0, 0] = xAxis.x
        sourceToTarget[0, 1] = xAxis.y
        sourceToTarget[1, 0] = yAxis.x
        sourceToTarget[1, 1] = yAxis.y
        sourceToTarget[3, 0] = origin.x
        sourceToTarget[3, 1] = origin.y
        scope.withTransform({ transform(sourceToTarget) }) { drawLayer(layer) }
    }

    internal fun recordAndDraw(scope: ContentDrawScope) {
        if (CouiRenderDiagnostics.enabled) drawPassCount++
        scope.apply {
            // 使用 DrawScope 的 record 重定向 Canvas, 补底和 drawContent 共用同一份录制。
            layer.record {
                if (CouiRenderDiagnostics.enabled) {
                    recordCallbackCount++
                    lastRecordMs = SystemClock.uptimeMillis()
                }
                drawSource(this@apply)
            }
            drawLayer(layer)
        }
        recorded = true
        invalidate()
    }

    /** 通知跨窗口的采样者重画；它重画时读信号会重新登记，同窗口的靠 RenderNode 依赖自动更新。 */
    internal fun invalidate() {
        if (!crossWindowReader) return
        crossWindowReader = false
        redrawSignal.value = Unit
    }

    internal fun detach() {
        coordinates = null
        recorded = false
        invalidate()
    }

    internal fun diagnosticState(): JSONObject = JSONObject()
        .put("name", diagnosticName ?: "unnamed")
        .put("layerId", layer.layerId)
        .put("ownerViewId", layer.ownerViewId)
        .put("parentLayerId", parent?.layer?.layerId ?: JSONObject.NULL)
        .put("recorded", recorded)
        .put("released", layer.isReleased)
        .put("width", layer.size.width)
        .put("height", layer.size.height)
        .put("alpha", layer.alpha.toDouble())
        .put("clip", layer.clip)
        .put("outline", layer.outline.toString())
        .put("scaleX", layer.scaleX.toDouble())
        .put("scaleY", layer.scaleY.toDouble())
        .put("translationX", layer.translationX.toDouble())
        .put("translationY", layer.translationY.toDouble())
        .put("blendMode", layer.blendMode.toString())
        .put("compositingStrategy", layer.compositingStrategy.toString())
        .put("isolated", isolate)
        .put("activeConsumers", activeConsumers)
        .put("drawPassCount", drawPassCount)
        .put("recordCallbackCount", recordCallbackCount)
        .put("sampleCount", sampleCount)
        .put("lastRecordMs", lastRecordMs)
        .put("coordinates", diagnosticCoordinates(coordinates))

    internal suspend fun diagnosticCapture(): ImageBitmap {
        check(recorded && !layer.isReleased) { "采样层尚未就绪或已释放: $diagnosticName" }
        return layer.toImageBitmap().also {
            check(recorded && !layer.isReleased) { "采样层在导出期间失效: $diagnosticName" }
        }
    }

    internal fun diagnosticRecreate() {
        recreateForDiagnostics()
    }
}

private data class BackdropElement(val backdrop: CouiBackdrop) : ModifierNodeElement<BackdropNode>() {
    override fun create() = BackdropNode(backdrop)

    override fun update(node: BackdropNode) {
        if (node.backdrop === backdrop) return
        val coordinates = node.backdrop.coordinates
        CouiRenderDiagnostics.detachSource(node.backdrop)
        node.backdrop.detach()
        node.backdrop = backdrop
        backdrop.coordinates = coordinates
        if (node.isAttached) CouiRenderDiagnostics.attachSource(backdrop) { node.invalidateDraw() }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "couiBackdrop"
    }
}

private class BackdropNode(var backdrop: CouiBackdrop) : Modifier.Node(), DrawModifierNode,
    GlobalPositionAwareModifierNode, ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {
    private var observer: ViewTreeObserver? = null

    override fun onAttach() {
        // Dialog 内必须从实际节点取得 View, 不使用外层 Composition 的 LocalView。
        observer = requireView().viewTreeObserver.also { it.addOnPreDrawListener(this) }
        if (CouiRenderDiagnostics.enabled) {
            observer?.addOnWindowFocusChangeListener(this)
            CouiRenderDiagnostics.attachSource(backdrop) { invalidateDraw() }
        }
    }

    override fun onDetach() {
        val current = observer?.takeIf { it.isAlive } ?: requireView().viewTreeObserver
        current.removeOnPreDrawListener(this)
        current.removeOnWindowFocusChangeListener(this)
        CouiRenderDiagnostics.detachSource(backdrop)
        observer = null
        backdrop.detach()
    }

    override fun onPreDraw(): Boolean {
        backdrop.invalidate()
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        CouiRenderDiagnostics.event("window.focus source=${backdrop.diagnosticName} focused=$hasFocus")
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() = backdrop.recordAndDraw(this)
}
