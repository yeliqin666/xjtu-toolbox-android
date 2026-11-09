package com.kyant.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import kotlin.math.abs

interface Backdrop {

    val isCoordinatesDependent: Boolean

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )

    /**
     * [岱宗盒子 vendor 扩展] 玻璃（[coordinates]）相对采样源的位置：它不变，采样到的内容就不变，不用重录。
     * 整页一起缩放、位移时全局位置每帧都变，相对位置却没动，所以不能按全局位置判断。
     */
    fun sampleOffset(coordinates: LayoutCoordinates): Offset = coordinates.positionInWindow()
}

/** 两次采样位置是否算动过：变换矩阵求逆有浮点抖动，不足半像素不算。 */
fun Offset.movedFrom(previous: Offset): Boolean =
    abs(x - previous.x) >= 0.5f || abs(y - previous.y) >= 0.5f
