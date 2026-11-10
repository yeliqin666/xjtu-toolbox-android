package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * COUI 的胶囊轮廓：Oplus `cornerType=4 / weight=1` 的 sdCapsule 零等值线。
 *
 * 轮廓本身是超椭圆化的胶囊（两端曲率连续），和 `RoundedCornerShape` 的半圆略有不同。
 * 之所以继承 [CornerBasedShape] 而不是直接实现 `Shape`：kyant 的 `lens()`（折射）只认
 * `CornerBasedShape` / `RoundedRectangularShape` 来算圆角半径，直接给 `Outline.Generic`
 * 的 `Shape` 会被 `throwUnsupportedSDFException()` 拒掉。四角按 50% 报上去（56dp 高时
 * 正好 28dp），折射 shader 的几何与这里的轮廓差不到一像素。
 *
 * 移植自 COUI-Kit `com.itos.coui.shape.CouiCapsuleShape`，只把 `android.util.LruCache`
 * 换成 common 侧的简单缓存。
 */
object GlassCapsuleShape : CornerBasedShape(
    topStart = CornerSize(50),
    topEnd = CornerSize(50),
    bottomEnd = CornerSize(50),
    bottomStart = CornerSize(50),
) {

    // 只共享按精确像素尺寸缓存的只读采样点，Path 始终归各调用方独立持有。
    private const val MAX_CACHED = 16
    private val quarterContours = object : LinkedHashMap<Size, List<Offset>>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Size, List<Offset>>?): Boolean =
            size > MAX_CACHED
    }

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val path = Path()
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(path)
        val quarter = quarterContours[size] ?: buildQuarterContour(size).also { quarterContours[size] = it }
        if (quarter.isEmpty()) return Outline.Generic(path)
        val halfX = size.width * 0.5
        val halfY = size.height * 0.5
        path.moveTo((halfX + quarter.first().x).toFloat(), halfY.toFloat())
        repeat(4) { quadrant ->
            quarter.indices.forEach { i ->
                val p = quarter[if (quadrant % 2 == 0) i else quarter.lastIndex - i]
                val x = if (quadrant == 1 || quadrant == 2) -p.x else p.x
                val y = if (quadrant >= 2) -p.y else p.y
                path.lineTo((halfX + x).toFloat(), (halfY + y).toFloat())
            }
        }
        path.close()
        return Outline.Generic(path)
    }

    /** 形状是固定的胶囊，四角不可调，`copy` 直接返回自身。 */
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = this

    private fun buildQuarterContour(size: Size): List<Offset> {
        val halfX = size.width * 0.5
        val halfY = size.height * 0.5
        if (capsuleSdf(0.0, 0.0, halfX, halfY) >= 0.0) return emptyList()
        fun point(angle: Double): Offset {
            val x = cos(angle)
            val y = sin(angle)
            var inner = 0.0
            var outer = hypot(halfX, halfY)
            repeat(32) {
                val radius = (inner + outer) * 0.5
                if (capsuleSdf(radius * x, radius * y, halfX, halfY) < 0.0) inner = radius else outer = radius
            }
            val radius = (inner + outer) * 0.5
            return Offset((radius * x).toFloat(), (radius * y).toFloat())
        }
        val quarter = mutableListOf(point(0.0))
        fun append(start: Double, a: Offset, end: Double, b: Offset, depth: Int) {
            val middle = (start + end) * 0.5
            val p = point(middle)
            val error = hypot((p.x - (a.x + b.x) * 0.5f).toDouble(), (p.y - (a.y + b.y) * 0.5f).toDouble())
            if (error > 0.025 && depth < 16) {
                append(start, a, middle, p, depth + 1)
                append(middle, p, end, b, depth + 1)
            } else {
                quarter.add(b)
            }
        }
        append(0.0, quarter.first(), PI * 0.5, point(PI * 0.5), 0)
        return quarter
    }

    private fun capsuleSdf(x: Double, y: Double, halfX: Double, halfY: Double): Double {
        val vertical = halfX < halfY
        val qx = hypot(if (vertical) y else x, 1.0)
        val qy = hypot(if (vertical) x else y, 1.0)
        val radius = if (vertical) halfX else halfY
        val a = abs(halfX - halfY)
        val s = qx - a
        val hard = max(s, 0.0)
        val k = radius / 80.0 * 26.6
        val soft = 0.5 * (s + sqrt(s * s + k * k))
        val wx = 1.0 - smoothStep(0.0, max(radius / 80.0 * 70.0, 0.001), abs(s))
        val wy = smoothStep(radius - max(radius / 80.0 * 60.0, 0.001), radius, qy)
        return hypot(hard + (soft - hard) * wx * wy, qy) - radius
    }

    private fun smoothStep(start: Double, end: Double, value: Double): Double {
        val t = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
