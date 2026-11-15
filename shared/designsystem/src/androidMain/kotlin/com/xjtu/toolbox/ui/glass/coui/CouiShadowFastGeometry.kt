/* Copyright 2016 Google Inc. Skia BSD license; Kotlin translation. */
package com.xjtu.toolbox.ui.glass.coui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class ShadowMeshData(val vertices: FloatArray, val indices: ShortArray)

internal fun fastShadowMesh(circle: Boolean, left: Float, top: Float, right: Float, bottom: Float,
    radius: Float, blur: Float, inset: Float): ShadowMeshData {
    val half = min(right - left, bottom - top) * .5f
    val rawUmbra = if (circle) 0f else max(radius, blur)
    val inner = if (circle) radius - inset else max(inset - rawUmbra, 0f)
    val type = if (circle) { if (inner > 0f) 1 else 0 }
        else if (inset > half) 0 else if (inner > 0f) 2 else 1
    val count = if (circle) { if (type == 1) 16 else 9 } else if (type == 2) 28 else 24
    val vertices = FloatArray(count * 5)
    var cursor = 0
    fun vertex(x: Float, y: Float, u: Float, v: Float, correction: Float) {
        vertices[cursor++] = x; vertices[cursor++] = y
        vertices[cursor++] = u; vertices[cursor++] = v; vertices[cursor++] = correction
    }
    val indices: IntArray
    if (circle) {
        val cx = (left + right) * .5f
        val cy = (top + bottom) * .5f
        val halfWidth = (right - left) * .5f
        val oct = .41421356237f
        val correction = radius / blur
        val uv = arrayOf(-oct to -1f, oct to -1f, 1f to -oct, 1f to oct,
            oct to 1f, -oct to 1f, -1f to oct, -1f to -oct)
        uv.forEach { (u,v) -> vertex(cx + u * halfWidth, cy + v * halfWidth, u, v, correction) }
        if (type == 1) {
            val c = .923579533f
            val s = .382683432f
            val normalized = inner / radius
            arrayOf(-s to -c, s to -c, c to -s, c to s, s to c, -s to c, -c to s, -c to -s)
                .forEach { (u,v) -> vertex(cx + u * inner, cy + v * inner, u * normalized, v * normalized, correction) }
            indices = IntArray(48)
            repeat(8) { i ->
                val next = (i + 1) % 8
                val k = i * 6
                indices[k] = i; indices[k+1] = next; indices[k+2] = next+8
                indices[k+3] = i; indices[k+4] = next+8; indices[k+5] = i+8
            }
        } else {
            vertex(cx, cy, 0f, 0f, correction)
            indices = IntArray(24) { i -> when (i % 3) { 0 -> i / 3; 1 -> (i / 3 + 1) % 8; else -> 8 } }
        }
    } else {
        val umbra = min(rawUmbra, half)
        val xi = floatArrayOf(left + umbra, right - umbra, left + umbra, right - umbra)
        val xm = floatArrayOf(left + radius, right - radius, left + radius, right - radius)
        val xo = floatArrayOf(left, right, left, right)
        val yi = floatArrayOf(top + umbra, top + umbra, bottom - umbra, bottom - umbra)
        val ym = floatArrayOf(top + radius, top + radius, bottom - radius, bottom - radius)
        val yo = floatArrayOf(top, top, bottom, bottom)
        val outer = ShadowPoint(radius - umbra, -radius - umbra).setLength(1f) ?: ShadowPoint(0f,0f)
        val diagonal = umbra / (sqrt(2f) * (radius - umbra) - radius)
        val correction = umbra / blur
        repeat(4) { i ->
            vertex(xi[i],yi[i],0f,0f,correction)
            vertex(xo[i],yi[i],0f,-1f,correction)
            vertex(xo[i],ym[i],outer.x,outer.y,correction)
            vertex(xo[i],yo[i],diagonal,diagonal,correction)
            vertex(xm[i],yo[i],outer.x,outer.y,correction)
            vertex(xi[i],yo[i],0f,-1f,correction)
        }
        if (type == 2) {
            val distance = umbra + inner
            vertex(left+distance,top+distance,0f,0f,correction)
            vertex(right-distance,top+distance,0f,0f,correction)
            vertex(left+distance,bottom-distance,0f,0f,correction)
            vertex(right-distance,bottom-distance,0f,0f,correction)
        }
        val start = if (type == 2) 0 else 24
        val end = if (type == 0) RRectIndices.size else RRectIndices.size - 6
        indices = RRectIndices.copyOfRange(start,end)
    }
    return ShadowMeshData(vertices, ShortArray(indices.size) { indices[it].toShort() })
}

private val RRectIndices = intArrayOf(
    0,6,25,0,25,24, 6,18,27,6,27,25, 18,12,26,18,26,27, 12,0,24,12,24,26,
    0,1,2,0,2,3,0,3,4,0,4,5, 6,11,10,6,10,9,6,9,8,6,8,7,
    12,17,16,12,16,15,12,15,14,12,14,13, 18,19,20,18,20,21,18,21,22,18,22,23,
    0,5,11,0,11,6, 6,7,19,6,19,18, 18,23,17,18,17,12, 12,13,1,12,1,0,
    0,6,18,0,18,12,
)
