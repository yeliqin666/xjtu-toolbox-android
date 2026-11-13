package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** bounds 是未缩放的绘制坐标, 位移已计入 bounds, 缩放围绕中心。cornerRadius 使用像素, null 表示短边的一半。 */
data class CouiFloatingSurface(
    val bounds: Rect,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val cornerWeight: Float = 1f,
    val alpha: Float = 1f,
    val visible: Boolean = true,
    val cornerRadius: Float? = null,
    /** 内容层（图标）的不透明度；null 时跟 [alpha] 一起淡。集成侧扩展：顶栏按钮只淡玻璃不淡图标。 */
    val contentAlpha: Float? = null,
) {
    init {
        require(cornerRadius == null || (cornerRadius.isFinite() && cornerRadius >= 0f)) {
            "浮动表面的圆角半径必须是有限的非负值"
        }
    }

    internal val resolvedCornerRadius: Float
        get() {
            val limit = (min(bounds.width, bounds.height) * 0.5f).coerceAtLeast(0f)
            return cornerRadius?.coerceAtMost(limit) ?: limit
        }
}

// Native 先按 index 排 influence, 再将当前主体插入自身槽位; 两侧都使用 index 2。
internal fun floatingBallSlots(subject: Int, indices: List<Int>): List<Int> =
    indices.indices.filter { it != subject }.sortedBy { indices[it] }.toMutableList().apply {
        add(indices[subject] - 1, subject)
    }

internal fun CouiFloatingSurface.ball(height: Float, origin: Offset): FloatArray = floatArrayOf(
    bounds.center.x - origin.x, height - bounds.center.y + origin.y,
    bounds.width * 0.5f, bounds.height * 0.5f,
    resolvedCornerRadius, cornerWeight, 1f / scaleX, 1f / scaleY,
)

internal fun CouiFloatingSurface.mapRect(rect: Rect): Rect = Rect(
    bounds.center.x + (rect.left - bounds.width * 0.5f) * scaleX,
    bounds.center.y + (rect.top - bounds.height * 0.5f) * scaleY,
    bounds.center.x + (rect.right - bounds.width * 0.5f) * scaleX,
    bounds.center.y + (rect.bottom - bounds.height * 0.5f) * scaleY,
)

// forceDraw 先扩本地 rect, 再映射至 surface; extension 已是 dimensionPixelSize。
internal fun CouiFloatingSurface.materialRect(extension: Int): Rect {
    val x = if (extension > 0) extension + 51f else 1f
    val y = if (extension > 0) 11f else 1f
    return mapRect(Rect(-x, -y, bounds.width + x, bounds.height + y))
}

// EndReorderBarrier 以相邻 caster 的 Z 差分组, 不是与组首 Z 比较。
internal fun floatingDrawGroups(elevations: List<Float>): List<List<Int>> {
    val groups = mutableListOf<MutableList<Int>>()
    var previous = 0f
    elevations.indices.sortedBy { elevations[it] }.forEach { index ->
        val z = elevations[index]
        if (groups.isEmpty() || z - previous >= 0.1f) groups.add(mutableListOf())
        groups.last().add(index)
        previous = z
    }
    return groups
}

// FillShapeCtx / FillShadowShapeCtx 与 cornerType=4 的 12 / 24 槽位保持一致。
internal fun floatingShapeContext(halfX: Float, halfY: Float, weight: Float, radius: Float): FloatArray {
    val smaller = min(halfX, halfY)
    val larger = max(halfX, halfY)
    val xBand = smaller / 80f * 70f
    return floatArrayOf(
        halfX, halfY, weight, radius, if (halfX < halfY) 1f else 0f,
        larger, smaller, max(larger - smaller, 0f), smaller / 80f * 26.6f,
        xBand, smaller / 80f * 60f, max(xBand, 4f),
    )
}

internal fun floatingShadowShapeContext(ball: FloatArray, shadow: FloatArray): FloatArray = FloatArray(24).apply {
    floatingShapeContext(shadow[16], shadow[17], ball[5], shadow[18]).copyInto(this)
    floatingShapeContext(ball[2], ball[3], ball[5], shadow[20]).copyInto(this, 12)
}

// BuildPreCmpArray 的水平连接分支, 与 Shader 的 12 -> 13 -> 23 顺序配套。
internal fun floatingPair(a: FloatArray, b: FloatArray, extension: Float): FloatArray {
    val result = FloatArray(24)
    if (a[2] < 1f || a[3] < 1f || b[2] < 1f || b[3] < 1f) return result
    val ax = a[2] / a[6]
    val ay = a[3] / a[7]
    val bx = b[2] / b[6]
    val by = b[3] / b[7]
    val left = min(a[0] - ax, b[0] - bx)
    val right = max(a[0] + ax, b[0] + bx)
    val bottom = min(a[1] - ay, b[1] - by)
    val top = max(a[1] + ay, b[1] + by)
    val halfX = (right - left) * 0.5f
    val halfY = (top - bottom) * 0.5f
    val rawOverlap = 2f * (ax + bx - halfX)
    val sumCorner = max(20f, a[4] + b[4])
    val maxCorner = max(a[4], b[4])
    val edgeA = smoothStep(maxCorner - sumCorner, maxCorner, rawOverlap)
    val edgeB = 1f - smoothStep(0f, sumCorner * 0.5f, halfX - max(ax, bx))
    val perpendicularDiff = abs(ay - by)
    val edgeMix = smoothStep(0f, sumCorner * 0.5f, perpendicularDiff)
    val overlap = rawOverlap - 2f / min(a[6], b[6])
    val space = max(-overlap, 0f)
    val extensionSum = extension * 2f
    val active = smoothStep(extensionSum + 5f, extensionSum, space)
    if (active <= 0f) return result
    val shapeChange = active * smoothStep(min(ax, bx), -extensionSum, overlap)
    val diff = overlap + extensionSum * shapeChange
    val perpendicular = min(ay, by)
    val axisInset = min(ax, bx)
    val aLeft = a[0] - (ax - axisInset)
    val aRight = a[0] + (ax - axisInset)
    val bLeft = b[0] - (bx - axisInset)
    val bRight = b[0] + (bx - axisInset)
    val intersects = bLeft <= aRight && aLeft <= bRight &&
        b[1] - by <= a[1] + ay && a[1] - ay <= b[1] + by
    if (intersects) {
        result[2] = 1f
    } else {
        result[0] = (min(aRight, bRight) + max(aLeft, bLeft)) * 0.5f
        result[1] = (min(a[1] + ay, b[1] + by) + max(a[1] - ay, b[1] - by)) * 0.5f
        val gap = when {
            bLeft > aRight -> bLeft - aRight
            aLeft > bRight -> bRight - aLeft
            else -> 0f
        }
        result[2] = if (abs(gap) <= 0.0001f) 0f else sign(gap)
    }
    result[4] = (left + right) * 0.5f
    result[5] = (bottom + top) * 0.5f
    result[6] = halfX
    result[7] = halfY
    result[8] = min(a[4], b[4])
    result[9] = max(extension * 2f + space * 0.5f, 0.001f)
    result[10] = max(perpendicular * 1.5f, 0.001f)
    result[11] = extension * shapeChange
    result[12] = result[9]
    result[13] = result[10]
    result[14] = result[11]
    result[15] = 1f - 0.15f * (1f - smoothStep(0f, 1f, abs(diff) / 12.5f))
    result[16] = (1f - smoothStep(0f, 5f, perpendicularDiff)) * (edgeA + (edgeB - edgeA) * edgeMix)
    result[17] = 25f * gain(smoothStep(0f, 26f, diff), 0.3f)
    result[18] = diff
    result[19] = (if (diff > 0f) 0.2f else 0f) + 0.8f * smoothStep(0f, 20f, diff)
    result[20] = shapeChange
    result[21] = 1f
    return result
}

internal fun floatingEdge(ball: FloatArray, width: Float, alpha: Float, type: Int): FloatArray {
    val halfX = ball[2]
    val halfY = ball[3]
    val perimeterHalf = max(2f * (halfX + halfY), 1f)
    val phase = (halfX / perimeterHalf * 0.5f + 0.5f) % 1f
    val circle = type == 2
    val referenceWidth = if (circle) 160f else 400f
    val referenceHeight = if (circle) 160f else 100f
    // 原厂 fade 使用映射后的主体尺寸, 周长与相位仍使用未缩放的 ball。
    val mappedWidth = halfX * 2f / ball[6]
    val mappedHeight = halfY * 2f / ball[7]
    val near = mapEdgeFade(mappedWidth, mappedHeight, if (circle) 0.06f else 0.2f,
        if (circle) 0.4f else 0.35f, referenceWidth, referenceHeight)
    val far = mapEdgeFade(mappedWidth, mappedHeight, if (circle) 0.01f else 0.15f,
        if (circle) 0.28f else 0.35f, referenceWidth, referenceHeight)
    val start = width * if (circle) 0.25f else 0.2f
    return floatArrayOf(
        1f, 1f, 1f, start, width, start, width * if (circle) 0.75f else 0.5f,
        0.7f * alpha, alpha, 0f, 0f, 0f, 0f, 0f, if (circle) 3f else 4f, 0.8f,
        perimeterHalf, phase, perimeterHalf * min(phase, 1f - phase) * 2f,
        if (phase > 0.5f) -1f else 1f,
        near.second * perimeterHalf, near.first * perimeterHalf,
        far.second * perimeterHalf, far.first * perimeterHalf,
        -halfY, halfX, perimeterHalf * 2f, width,
    )
}

internal fun floatingInnerGlow(ball: FloatArray, alpha: Float, type: Int, fadeScale: Float = 1f): FloatArray {
    val halfX = ball[2]
    val halfY = ball[3]
    val corner = ball[4]
    val circle = type == 2
    val fadeIn = (if (circle) 140f else 120f) * fadeScale
    val fadeOut = 20f * fadeScale
    val scaleX = 1f + (if (circle) 160f * 0.4f else 400f * 0.3f) / max(2f * halfX / ball[6], 1f)
    val scaleY = 1f + (if (circle) 160f * 0.35f else 100f * 0.15f) / max(2f * halfY / ball[7], 1f)
    val fadeX = halfX * scaleX
    val fadeY = halfY * scaleY
    val fadeCorner = min(max(fadeIn * 1.2f, corner), min(fadeX, fadeY))
    val outerCorner = min(max(corner, fadeOut * 1.2f), min(halfX, halfY))
    return floatArrayOf(
        1f, 1f, 1f, (if (circle) 0.4f else 0.5f) * alpha, 0f, 0f, fadeIn, 0f, 20f,
        scaleX, scaleY, 4f, 0.9f, fadeOut, 4f, 0.7f,
        fadeX, fadeY, fadeCorner, min(halfX, halfY), outerCorner,
        min(fadeCorner, min(halfX, halfY)), if (abs(outerCorner - corner) < 0.000001f) 1f else 0f,
        min(outerCorner, min(halfX, halfY)), fadeX - fadeCorner, fadeY - fadeCorner,
        halfX - outerCorner, halfY - outerCorner,
    )
}

private fun mapEdgeFade(
    width: Float, height: Float, start: Float, length: Float, referenceWidth: Float, referenceHeight: Float,
): Pair<Float, Float> {
    val perimeter = 2f * (max(width, 1f) + max(height, 1f))
    val referencePerimeter = 2f * (referenceWidth + referenceHeight)
    val referenceLength = referencePerimeter * (start + length)
    val mappedLength: Float
    val mappedStart: Float
    if (referenceLength <= referenceWidth) {
        val mappedTotal = (max(width, 1f) + referenceLength - referenceWidth) / perimeter
        mappedStart = start * mappedTotal / (start + length)
        mappedLength = length * mappedTotal / (start + length)
    } else {
        mappedLength = length * referencePerimeter / perimeter
        mappedStart = (max(width, 1f) + referenceLength - referenceWidth - length * referencePerimeter) / perimeter
    }
    return mappedStart to mappedStart + mappedLength
}

private fun smoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun gain(value: Float, exponent: Float): Float {
    val a = 0.5f * (2f * if (value < 0.5f) value else 1f - value).pow(exponent)
    return if (value < 0.5f) a else 1f - a
}
