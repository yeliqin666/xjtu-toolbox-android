package com.xjtu.toolbox.ui.glass.coui

import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import org.json.JSONArray
import org.json.JSONObject

/** 临时绘制取证入口; 由 App 仅在 canary 启用, 所有操作在主线程执行。 */
object CouiRenderDiagnostics {
    private class Source(val id: String, val backdrop: CouiBackdrop, val redraw: () -> Unit)

    private var reporter: ((String) -> Unit)? = null
    private var nextId = 0L
    private val sources = linkedMapOf<CouiBackdrop, Source>()
    private val probes = linkedMapOf<RenderProbeNode, String>()
    private val events = ArrayDeque<String>()

    val enabled: Boolean get() = reporter != null

    fun enable(reporter: (String) -> Unit) {
        this.reporter = reporter
        event("enable")
    }

    fun event(message: String) {
        if (!enabled) return
        val entry = "${SystemClock.uptimeMillis()} $message"
        if (events.size == 64) events.removeFirst()
        events.addLast(entry)
        reporter?.invoke(entry)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("uptimeMs", SystemClock.uptimeMillis())
        .put("sources", JSONArray().apply {
            sources.values.forEach { source ->
                put(source.backdrop.diagnosticState().put("id", source.id))
            }
        })
        .put("probes", JSONArray().apply {
            probes.forEach { (node, id) -> put(node.snapshot().put("id", id)) }
        })
        .put("events", JSONArray(events.toList()))

    fun sourceIds(target: String): List<String> = selectedSources(target).map { it.id }

    suspend fun captureSource(id: String): ImageBitmap = selectedSources(id).single().backdrop.diagnosticCapture()

    fun redraw(target: String) {
        val selectedSources = sources.values.filter { target == it.id || target == it.backdrop.diagnosticName }
        val selectedProbes = probes.keys.filter { matches(it.name, target) }
        require(selectedSources.isNotEmpty() || selectedProbes.isNotEmpty()) { "没有匹配的绘制目标: $target" }
        selectedSources.forEach { it.redraw() }
        selectedProbes.forEach { it.invalidateDraw() }
        event("redraw target=$target sources=${selectedSources.size} probes=${selectedProbes.size}")
    }

    fun recreateSource(id: String) {
        val source = selectedSources(id).single()
        event("recreate source=${source.id} name=${source.backdrop.diagnosticName}")
        source.backdrop.diagnosticRecreate()
    }

    fun setBottomHidden(hidden: Boolean) {
        val selected = probes.keys.filter { it.name == "bottom-bar" }
        require(selected.isNotEmpty()) { "当前没有底栏绘制节点" }
        selected.forEach {
            it.hidden = hidden
            it.invalidateDraw()
        }
        event("bottom hidden=$hidden probes=${selected.size}")
    }

    fun reset() {
        probes.keys.filter { it.hidden }.forEach {
            it.hidden = false
            it.invalidateDraw()
        }
        event("reset")
    }

    internal fun attachSource(backdrop: CouiBackdrop, redraw: () -> Unit) {
        if (!enabled) return
        val source = Source("source-${++nextId}", backdrop, redraw)
        sources[backdrop] = source
        event("source.attach id=${source.id} name=${backdrop.diagnosticName}")
    }

    internal fun detachSource(backdrop: CouiBackdrop) {
        val source = sources.remove(backdrop) ?: return
        event("source.detach id=${source.id} name=${backdrop.diagnosticName}")
    }

    internal fun attachProbe(node: RenderProbeNode) {
        probes[node] = "probe-${++nextId}"
        event("probe.attach id=${probes[node]} name=${node.name}")
    }

    internal fun detachProbe(node: RenderProbeNode) {
        val id = probes.remove(node) ?: return
        event("probe.detach id=$id name=${node.name}")
    }

    private fun selectedSources(target: String): List<Source> {
        val selected = sources.values.filter {
            target == "all" || target == it.id || target == it.backdrop.diagnosticName
        }
        require(selected.isNotEmpty()) { "没有匹配的采样层: $target" }
        return selected
    }

    private fun matches(name: String, target: String): Boolean = name == target || name.startsWith("$target/")
}

/** 位于裁剪层内的节点用于定点重绘; bottom-bar 额外支持暂停绘制, 不改布局或点击。 */
fun Modifier.renderDiagnosticProbe(name: String?): Modifier =
    if (name == null || !CouiRenderDiagnostics.enabled) this else then(RenderProbeElement(name))

private data class RenderProbeElement(val name: String) : ModifierNodeElement<RenderProbeNode>() {
    override fun create() = RenderProbeNode(name)
    override fun update(node: RenderProbeNode) {
        node.name = name
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "renderDiagnosticProbe"
        properties["target"] = this@RenderProbeElement.name
    }
}

internal class RenderProbeNode(var name: String) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var drawCount = 0L
    private var lastDrawMs = 0L
    var hidden = false

    override fun onAttach() {
        CouiRenderDiagnostics.attachProbe(this)
    }

    override fun onDetach() {
        CouiRenderDiagnostics.detachProbe(this)
        coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        drawCount++
        lastDrawMs = SystemClock.uptimeMillis()
        if (!hidden) drawContent()
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("name", name)
        .put("drawCount", drawCount)
        .put("lastDrawMs", lastDrawMs)
        .put("hidden", hidden)
        .put("coordinates", diagnosticCoordinates(coordinates))
}

internal fun diagnosticCoordinates(coordinates: LayoutCoordinates?): Any {
    if (coordinates?.isAttached != true) return JSONObject.NULL
    val origin = coordinates.localToScreen(Offset.Zero)
    return JSONObject()
        .put("width", coordinates.size.width)
        .put("height", coordinates.size.height)
        .put("screenX", if (origin.x.isFinite()) origin.x.toDouble() else JSONObject.NULL)
        .put("screenY", if (origin.y.isFinite()) origin.y.toDouble() else JSONObject.NULL)
}
