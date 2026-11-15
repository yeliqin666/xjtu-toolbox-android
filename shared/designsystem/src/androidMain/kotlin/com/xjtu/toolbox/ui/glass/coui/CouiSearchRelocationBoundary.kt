package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.toSize

internal val CouiSearchRelocationBoundary: Modifier = SearchRelocationBoundary

private data object SearchRelocationBoundary : ModifierNodeElement<SearchRelocationNode>() {
    override fun create() = SearchRelocationNode()
    override fun update(node: SearchRelocationNode) = Unit
}

private class SearchRelocationNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        if (!childCoordinates.isAttached || boundsProvider() == null) return
        // 输入保留内部滚动; 祖先只需显示固定顶栏, 不能追逐展开时越界的输入坐标而翻页。
        bringIntoView {
            if (isAttached) Rect(Offset.Zero, requireLayoutCoordinates().size.toSize()) else null
        }
    }
}
