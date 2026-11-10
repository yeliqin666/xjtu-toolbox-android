package com.xjtu.toolbox.ui.glass.coui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class ShadowProjection(
    val data: ShadowMeshData?,
    val offset: ShadowPoint,
    val fast: Boolean,
    val blur: Float,
    val scale: Float = 1f,
    val stroke: Float = 0f,
)

/** 对应 SkShadowUtils 的 factory; 坐标先规范化再量化, 不能量化后再抵消平移。 */
internal fun floatingShadowGeometry(width: Float, height: Float, sx: Float, sy: Float,
    tx: Float, ty: Float, elevation: Float, lightY: Float, lightZ: Float, lightRadius: Float,
    transparent: Boolean, oval: Boolean, spot: Boolean,
    radius: Float = min(width, height) * 0.5f, clockwise: Boolean = false): ShadowProjection {
    val lightX = tx + sx * width * 0.5f
    val deviceLightY = ty + sy * lightY
    val ratio = (elevation / (lightZ - elevation)).coerceIn(0f, 0.95f)
    val scale = (lightZ / (lightZ - elevation)).coerceIn(1f, 1.95f)
    val blur = if (spot) lightRadius * ratio else min(elevation * 0.5f, 150f)
    val reciprocal = 1f + max(elevation / 128f, 0f)
    val fast = isFastShadowGeometry(width, height, sx, sy, oval)
    if (fast) {
        val outset = blur / sx
        var left = 0f
        var top = 0f
        var right = width
        var bottom = height
        var shadowRadius = radius
        var inset = blur
        if (spot) {
            val dx = -ratio * lightX + scale * tx
            val dy = -ratio * deviceLightY + scale * ty
            left = (dx - tx) / sx
            top = (dy - ty) / sy
            right = width * scale + left
            bottom = height * scale + top
            shadowRadius *= scale
            if (transparent) inset = outset + right - left else {
                val dr = shadowRadius - radius
                val a = ShadowPoint(left + dr, top + dr)
                val b = ShadowPoint(right - width - dr, bottom - height - dr)
                inset = outset + max(outset, sqrt(max(a.lengthSquared(), b.lengthSquared())) + dr)
            }
        } else if (transparent) {
            inset = width + outset * 2f
        }
        val data = fastShadowMesh(oval, (left - outset) * sx, (top - outset) * sy,
            (right + outset) * sx, (bottom + outset) * sy, (shadowRadius + outset) * sx,
            if (spot) blur * 2f else blur * reciprocal, inset * sx)
        return ShadowProjection(data, ShadowPoint(0f, 0f), true, 0f)
    }

    val center = ShadowPoint(sx * width * 0.5f + tx, sy * height * 0.5f + ty)
    val offset = ShadowPoint(-ratio * (lightX - center.x), -ratio * (deviceLightY - center.y))
    val fill = transparent || abs(offset.x) > width * sx * 0.5f || abs(offset.y) > height * sy * 0.5f
    val noUmbra = !fill && offset.length() * scale + scale < blur
    val partial = spot && !fill && !noUmbra
    val mx = if (partial) tx else 0f
    val my = if (partial) ty else 0f
    val lx = if (partial) lightX else sx * width * 0.5f
    val ly = if (partial) deviceLightY else sy * height * 0.5f
    val tessellator = ShadowTessellator()
    fun clip(p: ShadowPoint) = ShadowPoint(p.x * sx + mx, p.y * sy + my)
    fun project(p: ShadowPoint): ShadowPoint {
        val v = clip(p)
        return if (spot) ShadowPoint(v.x * scale - ratio * lx, v.y * scale - ratio * ly) else v
    }
    var previous = ShadowPoint(0f, height * 0.5f)
    fun line(x: Float, y: Float) {
        previous = ShadowPoint(x, y)
        if (spot) tessellator.addClip(clip(previous))
        tessellator.line(project(previous))
    }
    fun conic(cx: Float, cy: Float, x: Float, y: Float) {
        val control = ShadowPoint(cx, cy)
        val end = ShadowPoint(x, y)
        if (spot) {
            val mid = (clip(previous) * 0.25f + clip(control) * (ConicWeight * 0.5f) + clip(end) * 0.25f) *
                (1f / (0.5f + 0.5f * ConicWeight))
            tessellator.addClip(mid)
            tessellator.addClip(clip(end))
        }
        tessellator.conic(project(previous), project(control), project(end), ConicWeight)
        previous = end
    }
    if (oval) {
        conic(0f, 0f, width * 0.5f, 0f)
        conic(width, 0f, width, height * 0.5f)
        conic(width, height, width * 0.5f, height)
        conic(0f, height, 0f, height * 0.5f)
    } else if (clockwise) {
        // Outline::getPath 的普通 RRect 为 CW/start6, 不沿用 fullsmooth 的 CCW/start7。
        previous = ShadowPoint(0f, height - radius)
        line(0f, radius)
        conic(0f, 0f, radius, 0f)
        line(width - radius, 0f)
        conic(width, 0f, width, radius)
        line(width, height - radius)
        conic(width, height, width - radius, height)
        line(radius, height)
        conic(0f, height, 0f, height - radius)
    } else {
        previous = ShadowPoint(0f, radius)
        line(0f, height - radius)
        conic(0f, height, radius, height)
        line(width - radius, height)
        conic(width, height, width, height - radius)
        line(width, radius)
        conic(width, 0f, width - radius, 0f)
        line(radius, 0f)
        conic(0f, 0f, 0f, radius)
    }
    tessellator.finish()
    val triangles = tessellator.mesh(if (spot) blur else blur * reciprocal - blur, blur,
        if (spot) fill else transparent, spot, radius)
    val data = triangles?.let {
        ShadowMeshData(FloatArray(it.positions.size * 5) { index ->
            val vertex = index / 5
            when (index % 5) {
                0 -> it.positions[vertex].x
                1 -> it.positions[vertex].y
                4 -> it.alpha[vertex] / 255f
                else -> 0f
            }
        }, ShortArray(it.indices.size) { index -> it.indices[index].toShort() })
    }
    val translation = if (!spot) ShadowPoint(0f, 0f)
        else if (partial) ShadowPoint(-tx, -ty) else offset
    // 失败才采用原厂 blur 公式; 成功空网格保持为空。
    return ShadowProjection(data, translation, false,
        if (spot) blur else 0.5f * blur * reciprocal,
        if (spot) scale else 1f, if (spot) 0f else 0.5f * (blur - 0.5f * blur * reciprocal))
}

private const val ConicWeight = 0.70710677f

internal fun isFastShadowGeometry(width: Float, height: Float, sx: Float, sy: Float, oval: Boolean): Boolean =
    sx > NearlyZero && abs(sx - sy) <= NearlyZero && (!oval || abs(width - height) <= NearlyZero)
