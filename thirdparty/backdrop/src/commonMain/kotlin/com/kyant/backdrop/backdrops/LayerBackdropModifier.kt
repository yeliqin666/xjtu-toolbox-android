package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer

/**
 * 把这一棵子树「录一层」当别人的采样源。
 *
 * [drawOnce]（默认开）是岱宗盒子的 vendor 扩展：内容树**只渲染一遍**——先录进采样层，再把这一层
 * 本身画到屏幕上。上游的写法是 `drawContent()` + 再录一遍，同一棵内容树渲染两次；这些层还是嵌套的
 * （壳的内容层 → 首页的内容层 → 卡片自己的采样层），首页最坏是 4 遍。传 false 可退回上游行为。
 */
fun Modifier.layerBackdrop(backdrop: LayerBackdrop, drawOnce: Boolean = true): Modifier =
    this then LayerBackdropElement(backdrop, drawOnce)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val drawOnce: Boolean
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, drawOnce)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.drawOnce = drawOnce
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
        properties["drawOnce"] = drawOnce
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (drawOnce != other.drawOnce) return false

        return true
    }

    override fun hashCode(): Int {
        return 31 * backdrop.hashCode() + drawOnce.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var drawOnce: Boolean
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        // [岱宗盒子 vendor 改动] 内容只渲染一遍。
        //
        // 上游：
        //     drawContent()                                    // ① 上屏
        //     recordLayer(backdrop.graphicsLayer) { onDraw }    // ② 再画一遍进采样层
        // 默认的 `onDraw` 就是 `drawContent()`，①② 逐像素相同，① 是纯浪费。
        // 而这一层是**嵌套**的：壳的内容层里套着首页的内容层，首页里又有每张卡片自己的采样层，
        // 于是「渲染两遍」会按层数相乘——首页滚动时卡片那套 blur + 折射要真跑 4 遍。
        //
        // 改法：先录进采样层，再把**同一层**画到屏幕上。采样与上屏共用一次渲染，不额外占显存
        // （这一层本来就存在，只是以前只当采样源）。
        //
        // 只有「采样内容 = 内容本身」时才敢这么做：自定义过 `onDraw` 的层，采样源和屏幕内容
        // 本来就不是一回事，照旧走上游那两条。
        if (drawOnce && backdrop.drawsContent) {
            recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
            drawLayer(backdrop.graphicsLayer)
        } else {
            drawContent()
            recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
