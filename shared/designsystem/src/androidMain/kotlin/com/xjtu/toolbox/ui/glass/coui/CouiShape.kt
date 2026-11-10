package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

const val CouiCornerWeight = 2.5f

/** Oplus G2 的等半径 Lp 零等值线, 与菜单轮廓光共用边界。 */
@Immutable
data class CouiShape(val radius: Dp, val cornerWeight: Float = CouiCornerWeight) : Shape {
    init {
        require(radius.value.isFinite() && radius.value >= 0f) { "圆角半径必须为有限非负值" }
        require(cornerWeight.isFinite() && cornerWeight >= 2f) { "圆角权重必须为有限值且不小于 2, 与轮廓 Shader 保持一致" }
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = (radius.value * density.density).coerceIn(0f, size.minDimension.coerceAtLeast(0f) / 2f)
        if (r == 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val weight = cornerWeight.toDouble()
        val diagonal = 0.5.pow(1.0 / weight)
        // 每半角在斜率不超过 1 的区间采样, 线段相对原曲线的误差上限为 0.05px。
        val segments = ceil(sqrt(r * (weight - 1.0) * diagonal / (4.0 * 0.05))).toInt().coerceAtLeast(1)
        val halfCorner = DoubleArray(segments + 1) { index ->
            (1.0 - (diagonal * index / segments).pow(weight)).pow(1.0 / weight)
        }
        val path = Path()
        path.moveTo(r, 0f)
        repeat(4) { corner ->
            for (index in 0..segments * 2) {
                val firstHalf = index <= segments
                val halfIndex = if (firstHalf) index else segments * 2 - index
                val a = diagonal * halfIndex / segments
                val b = halfCorner[halfIndex]
                val x = (r * if (firstHalf) a else b).toFloat()
                val y = (r * if (firstHalf) b else a).toFloat()
                when (corner) {
                    0 -> path.lineTo(size.width - r + x, r - y)
                    1 -> path.lineTo(size.width - r + y, size.height - r + x)
                    2 -> path.lineTo(r - x, size.height - r + y)
                    else -> path.lineTo(r - y, r - x)
                }
            }
        }
        path.close()
        // shortcut: Path 使用公开光栅化抗锯齿, 不是厂商 fwidth 覆盖率; 可用同等 GPU 裁剪时替换。
        return Outline.Generic(path)
    }
}

/** 已知形状直接提供半径；任意 Shape 的轮廓参数仍由宿主明确给出。 */
internal fun Shape.resolveCouiCornerRadius(explicitRadius: Dp): Dp {
    val radius = if (this is CouiShape) {
        require(!explicitRadius.isSpecified || explicitRadius == this.radius) { "显式半径必须与 CouiShape 一致" }
        this.radius
    } else explicitRadius
    require(radius.value.isFinite() && radius.value >= 0f) { "自定义 Shape 必须提供有限非负的轮廓半径" }
    return radius
}
