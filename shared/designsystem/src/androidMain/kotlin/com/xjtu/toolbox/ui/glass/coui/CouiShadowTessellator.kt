/* Copyright 2017 Google LLC. Skia BSD license; Kotlin translation. */
package com.xjtu.toolbox.ui.glass.coui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val NearlyZero = 1f / 4096f

internal data class ShadowPoint(val x: Float, val y: Float) {
    operator fun plus(p: ShadowPoint) = ShadowPoint(x + p.x, y + p.y)
    operator fun minus(p: ShadowPoint) = ShadowPoint(x - p.x, y - p.y)
    operator fun times(s: Float) = ShadowPoint(x * s, y * s)
    fun dot(p: ShadowPoint) = x * p.x + y * p.y
    fun cross(p: ShadowPoint) = x * p.y - y * p.x
    fun lengthSquared() = dot(this)
    fun length() = sqrt(lengthSquared())
    fun setLength(s: Float): ShadowPoint? {
        val inverse = s / length()
        val result = this * inverse
        return result.takeIf { it.x.isFinite() && it.y.isFinite() && (it.x != 0f || it.y != 0f) }
    }
}

internal data class ShadowTriangles(val positions: List<ShadowPoint>, val alpha: List<Int>, val indices: List<Int>)

internal fun duplicate(a: ShadowPoint, b: ShadowPoint) = (a - b).lengthSquared() < 1f / 256f
internal fun within(a: ShadowPoint, b: ShadowPoint, tolerance: Float) =
    abs(a.x - b.x) <= tolerance && abs(a.y - b.y) <= tolerance

internal fun lineDistanceSquared(p: ShadowPoint, a: ShadowPoint, b: ShadowPoint): Float {
    val u = b - a
    val v = p - a
    val d = u.lengthSquared()
    val projection = u.dot(v)
    if (projection <= 0f) return v.lengthSquared()
    if (projection > d) return (p - b).lengthSquared()
    return max(v.lengthSquared() - projection * projection / d, 0f)
}

internal class ShadowTessellator {
    private val polygon = ArrayList<ShadowPoint>()
    private val clip = ArrayList<ShadowPoint>()
    private var centroid = ShadowPoint(0f, 0f)
    private var area = 0f
    private var convex = true
    private var lastArea = 0f
    private var lastCross = 0f
    private var transparent = false
    private var validUmbra = true
    private var direction = 1f
    private val positions = ArrayList<ShadowPoint>()
    private val colors = ArrayList<Int>()
    private val indices = ArrayList<Int>()
    private val clipVectors = ArrayList<ShadowPoint>()
    private var firstVertex = -1
    private var firstOutset = ShadowPoint(0f, 0f)
    private var firstPoint = ShadowPoint(0f, 0f)
    private var previousOutset = ShadowPoint(0f, 0f)
    private var previousPoint = ShadowPoint(0f, 0f)
    private var previousUmbra = -1
    private var currentUmbra = 0
    private var currentClip = 0
    private var previousOutside = false
    private var firstOutside = false

    fun line(point: ShadowPoint) {
        val p = ShadowPoint(floor(point.x * 16f + 0.5f) * 0.0625f,
            floor(point.y * 16f + 0.5f) * 0.0625f)
        if (polygon.isNotEmpty() && !accumulate(polygon.last(), p)) return
        if (polygon.size > 1 && !checkConvexity(polygon[polygon.lastIndex - 1], polygon.last(), p)) {
            polygon.removeAt(polygon.lastIndex)
            if (duplicate(polygon.last(), p)) polygon.removeAt(polygon.lastIndex)
        }
        polygon.add(p)
    }

    fun finish() {
        if (polygon.size > 1 && !accumulate(polygon.last(), polygon[0])) polygon.removeAt(polygon.lastIndex)
        if (polygon.size > 2) {
            centroid = centroid * (1f / (3f * area)) + polygon[0]
            if (!checkConvexity(polygon[polygon.lastIndex - 1], polygon.last(), polygon[0])) {
                polygon[0] = polygon.last()
                polygon.removeAt(polygon.lastIndex)
            }
        }
        direction = if (area > 0f) -1f else 1f
    }

    fun addClip(point: ShadowPoint) {
        if (clip.isEmpty() || !duplicate(point, clip.last())) clip.add(point)
    }

    fun conic(p0: ShadowPoint, p1: ShadowPoint, p2: ShadowPoint, weight: Float) {
        val a = weight - 1f
        val k = a / (4f * (2f + a))
        val errorVector = ShadowPoint(k * (p0.x - 2f * p1.x + p2.x), k * (p0.y - 2f * p1.y + p2.y))
        var error = errorVector.length()
        var level = 0
        while (level < 5 && error > 0.25f) { level++; error *= 0.25f }
        fun subdivide(a: ShadowPoint, b: ShadowPoint, c: ShadowPoint, w: Float, depth: Int, last: Boolean) {
            if (depth == 0) {
                quad(a, b, if (last) p2 else c)
                return
            }
            val scale = 1f / (1f + w)
            val t0 = a * scale
            val t1 = b * (w * scale)
            val t2 = c * scale
            var q1 = t0 + t1
            var q3 = t1 + t2
            var q2 = t0 * 0.5f + t1 + t2 * 0.5f
            fun between(x: Float, y: Float, z: Float) = (x - y) * (z - y) <= 0f
            if (between(a.y, b.y, c.y)) {
                if (!between(a.y, q2.y, c.y)) q2 = q2.copy(y = if (abs(q2.y - a.y) < abs(q2.y - c.y)) a.y else c.y)
                if (!between(a.y, q1.y, q2.y)) q1 = q1.copy(y = a.y)
                if (!between(q2.y, q3.y, c.y)) q3 = q3.copy(y = c.y)
            }
            val nextW = sqrt(0.5f + w * 0.5f)
            subdivide(a, q1, q2, nextW, depth - 1, false)
            subdivide(q2, q3, c, nextW, depth - 1, last)
        }
        subdivide(p0, p1, p2, weight, level, true)
    }

    fun mesh(insetValue: Float, outset: Float, isTransparent: Boolean, doClip: Boolean,
        halfBounds: Float = Float.MAX_VALUE): ShadowTriangles? {
        transparent = isTransparent
        if (polygon.size < 3 || !area.isFinite()) return ShadowTriangles(emptyList(), emptyList(), emptyList())
        if (!convex) return concaveShadow(min(insetValue, halfBounds), outset, halfBounds)
        if (doClip) {
            currentClip = clip.lastIndex
            val first = clip[1] - clip[0]
            val initialCross = first.cross(centroid - clip[0])
            var hidden = true
            clip.indices.forEach { i ->
                val v = clip[(i + 1) % clip.size] - clip[i]
                clipVectors.add(v)
                if (i > 0 && initialCross * v.cross(centroid - clip[i]) <= 0f) hidden = false
            }
            transparent = transparent || !hidden
        }
        var minDistance = Float.POSITIVE_INFINITY
        polygon.indices.forEach { i ->
            minDistance = min(minDistance, lineDistanceSquared(centroid, polygon[i], polygon[(i + 1) % polygon.size]))
        }
        var inset = insetValue
        var umbraAlpha = 255
        var inner = polygon.toList()
        if (inset > NearlyZero) {
            if (minDistance < (inset + 0.01f) * (inset + 0.01f)) {
                val newInset = sqrt(minDistance) - 0.01f
                val ratio = (128f * (newInset / inset + 1f)).toInt()
                umbraAlpha = (255 * ratio) shr 8
                inset = newInset
            }
            val result = insetPolygon(polygon, inset)
            validUmbra = result != null
            if (result != null) inner = result
        }
        if (transparent) add(centroid, umbraAlpha)
        firstOutset = normal(polygon.last(), polygon[0], outset) ?: return null
        firstPoint = polygon.last()
        firstVertex = positions.size
        previousOutset = firstOutset
        previousPoint = firstPoint
        previousUmbra = -1
        previousUmbra = innerPoint(firstPoint, umbraAlpha, inner).first
        if (!transparent && doClip) {
            val point = clipUmbra(positions[firstVertex])
            firstOutside = point != null
            previousOutside = firstOutside
            if (point != null) add(point, umbraAlpha)
        }
        add(firstPoint + firstOutset, 0)
        edge(polygon[0], firstOutset, umbraAlpha, inner, false, doClip)
        for (i in 1..polygon.lastIndex) {
            val normal = normal(previousPoint, polygon[i], outset) ?: return null
            arc(normal, outset, true)
            edge(polygon[i], normal, umbraAlpha, inner, i == polygon.lastIndex, doClip)
        }
        val firstPenumbra = firstVertex + if (firstOutside) 2 else 1
        if (arc(firstOutset, outset, false)) triangle(firstVertex, positions.lastIndex, firstPenumbra)
        else positions[firstPenumbra] = positions.last()
        return ShadowTriangles(positions, colors, indices)
    }

    private fun accumulate(a: ShadowPoint, b: ShadowPoint): Boolean {
        if (duplicate(a, b)) return false
        val v0 = a - polygon[0]
        val v1 = b - polygon[0]
        val quad = v0.cross(v1)
        centroid += (v0 + v1) * quad
        area += quad
        if (quad * lastArea < 0f) convex = false
        if (quad != 0f) lastArea = quad
        return true
    }

    private fun checkConvexity(a: ShadowPoint, b: ShadowPoint, c: ShadowPoint): Boolean {
        val cross = (b - a).cross(c - b)
        if (abs(cross) <= NearlyZero) return false
        if (lastCross * cross < 0f) convex = false
        if (cross != 0f) lastCross = cross
        return true
    }

    private fun quad(a: ShadowPoint, b: ShadowPoint, c: ShadowPoint) {
        if (abs((b - a).cross(c - a)) <= NearlyZero) return
        // 使用原 Wang 二次曲线细分级别与精度。
        val v = ShadowPoint(a.x - 2f * b.x + c.x, a.y - 2f * b.y + c.y)
        val n4 = v.lengthSquared() * (0.25f * 5f) * (0.25f * 5f)
        var count = 1
        while (count < 1024 && count.toFloat() * count * count * count < n4) count *= 2
        fun generate(p0: ShadowPoint, p1: ShadowPoint, p2: ShadowPoint, left: Int) {
            if (left < 2 || lineDistanceSquared(p1, p0, p2) < 0.2f * 0.2f) {
                line(p2)
            } else {
                fun midpoint(x: ShadowPoint, y: ShadowPoint) = ShadowPoint(
                    ((x.x.toDouble() + y.x.toDouble()) * 0.5).toFloat(),
                    ((x.y.toDouble() + y.y.toDouble()) * 0.5).toFloat())
                val q0 = midpoint(p0, p1)
                val q1 = midpoint(p1, p2)
                val r = midpoint(q0, q1)
                generate(p0, q0, r, left / 2)
                generate(r, q1, p2, left / 2)
            }
        }
        generate(a, b, c, count)
    }

    private fun normal(a: ShadowPoint, b: ShadowPoint, length: Float) =
        ShadowPoint(a.y - b.y, b.x - a.x).setLength(direction)?.times(length)

    private fun concaveShadow(inset: Float, outset: Float, halfBounds: Float): ShadowTriangles? {
        if (!isSimple(polygon)) return null
        val inner = offsetPolygon(polygon, inset, halfBounds) ?: return null
        val outer = offsetPolygon(polygon, -outset, halfBounds) ?: return null
        if (inner.points.isEmpty() || outer.points.isEmpty()) return null
        val map = IntArray(inner.points.size)
        val ii = inner.indices.toIntArray()
        val oi = outer.indices.toIntArray()
        var outerIndex = oi.indices.minBy { oi[it] }
        var innerIndex = ii.indices.minBy { ii[it] }
        var outerMax = polygon.lastIndex
        var innerMax = polygon.lastIndex
        while (oi[outerIndex] != ii[innerIndex]) {
            if (oi[outerIndex] < ii[innerIndex]) {
                oi[outerIndex] += polygon.size
                outerMax = oi[outerIndex]
                outerIndex = (outerIndex + 1) % oi.size
            } else {
                ii[innerIndex] += polygon.size
                innerMax = ii[innerIndex]
                innerIndex = (innerIndex + 1) % ii.size
            }
        }
        add(outer.points[outerIndex], 0)
        add(inner.points[innerIndex], 255)
        var previousOuter = 0
        var previousInner = 1
        map[innerIndex] = 1
        var nextOuter = (outerIndex + 1) % oi.size
        var nextInner = (innerIndex + 1) % ii.size
        fun advanceOuter() { oi[outerIndex] += polygon.size; outerIndex = nextOuter; nextOuter = (outerIndex + 1) % oi.size }
        fun advanceInner() { ii[innerIndex] += polygon.size; innerIndex = nextInner; nextInner = (innerIndex + 1) % ii.size }
        fun quad(a: Int, b: Int, c: Int, d: Int) { triangle(a, b, c); triangle(c, b, d) }
        while (oi[nextOuter] <= outerMax || ii[nextInner] <= innerMax) {
            if (ii[nextInner] == oi[nextOuter]) {
                add(outer.points[nextOuter], 0)
                val currentOuter = positions.lastIndex
                add(inner.points[nextInner], 255)
                val currentInner = positions.lastIndex
                map[nextInner] = currentInner
                quad(previousOuter, currentOuter, previousInner, currentInner)
                previousOuter = currentOuter
                previousInner = currentInner
                advanceOuter(); advanceInner()
            }
            while (oi[nextOuter] < ii[nextInner] && oi[nextOuter] <= outerMax) {
                add(outer.points[nextOuter], 0)
                triangle(previousOuter, positions.lastIndex, previousInner)
                previousOuter = positions.lastIndex
                advanceOuter()
            }
            while (ii[nextInner] < oi[nextOuter] && ii[nextInner] <= innerMax) {
                add(inner.points[nextInner], 255)
                map[nextInner] = positions.lastIndex
                triangle(previousInner, previousOuter, positions.lastIndex)
                previousInner = positions.lastIndex
                advanceInner()
            }
        }
        add(outer.points[nextOuter], 0)
        val currentOuter = positions.lastIndex
        add(inner.points[nextInner], 255)
        map[nextInner] = positions.lastIndex
        quad(previousOuter, currentOuter, previousInner, positions.lastIndex)
        if (transparent) triangulate(inner.points, map, indices)
        return ShadowTriangles(positions, colors, indices)
    }

    private fun add(p: ShadowPoint, alpha: Int) { positions.add(p); colors.add(alpha) }
    private fun triangle(a: Int, b: Int, c: Int) { indices.add(a); indices.add(b); indices.add(c) }

    private fun closest(p: ShadowPoint, inner: List<ShadowPoint>): Int {
        var index = currentUmbra
        var distance = (p - inner[index]).lengthSquared()
        var dir = 1
        var next = (index + dir) % inner.size
        var nextDistance = (p - inner[next]).lengthSquared()
        if (nextDistance < distance) { index = next; distance = nextDistance } else dir = inner.size - 1
        next = (index + dir) % inner.size
        nextDistance = (p - inner[next]).lengthSquared()
        while (nextDistance < distance) {
            index = next
            distance = nextDistance
            next = (index + dir) % inner.size
            nextDistance = (p - inner[next]).lengthSquared()
        }
        currentUmbra = index
        return index
    }

    private fun innerPoint(p: ShadowPoint, alpha: Int, inner: List<ShadowPoint>): Pair<Int, Boolean> {
        val point = if (validUmbra) inner[closest(p, inner)] else p + (centroid - p) * 0.95f
        previousPoint = p
        if (previousUmbra == -1 || !duplicate(point, positions[previousUmbra])) {
            if (previousUmbra >= 0 && duplicate(point, positions[firstVertex])) return firstVertex to false
            val index = positions.size
            add(point, alpha)
            return index to false
        }
        return previousUmbra to true
    }

    private fun clipUmbra(p: ShadowPoint): ShadowPoint? {
        val segment = centroid - p
        val start = currentClip
        do {
            val dp = p - clip[currentClip]
            val denominator = clipVectors[currentClip].cross(segment)
            val numerator = dp.cross(segment)
            if (abs(denominator) <= NearlyZero) {
                if (abs(numerator) <= NearlyZero) return null
            } else if (numerator >= 0f && numerator <= denominator) {
                val s = dp.cross(clipVectors[currentClip])
                if (s >= 0f && s <= denominator) return p + segment * (s / denominator)
            }
            currentClip = (currentClip + 1) % clip.size
        } while (currentClip != start)
        return null
    }

    private fun edge(p: ShadowPoint, normal: ShadowPoint, alpha: Int, inner: List<ShadowPoint>, last: Boolean, doClip: Boolean) {
        val pair = if (last) { previousPoint = p; firstVertex to false } else innerPoint(p, alpha, inner)
        val current = pair.first
        val duplicate = pair.second
        val previousPenumbra = positions.size - if (duplicate || current == firstVertex) 1 else 2
        if (!duplicate) {
            if (transparent) triangle(0, previousUmbra, current)
            else if (doClip) {
                val point = if (last) null else clipUmbra(positions[current])
                val outside = if (last) firstOutside else point != null
                if (outside) {
                    if (point != null) add(point, alpha)
                    triangle(previousUmbra, current, current + 1)
                    if (previousOutside) triangle(previousUmbra, current + 1, previousUmbra + 1)
                } else if (previousOutside) triangle(previousUmbra, current, previousUmbra + 1)
                previousOutside = outside
            }
        }
        add(p + normal, 0)
        if (!duplicate) triangle(previousUmbra, previousPenumbra, current)
        triangle(previousPenumbra, positions.lastIndex, current)
        previousUmbra = current
        previousOutset = normal
    }

    private fun arc(next: ShadowPoint, offset: Float, finish: Boolean): Boolean {
        val cosine = previousOutset.dot(next)
        val sine = previousOutset.cross(next)
        val theta = atan2(sine, cosine)
        val stepsValue = abs(offset * theta * 0.25f)
        var steps = if (cosine.isFinite() && sine.isFinite() && stepsValue < 65535f) floor(stepsValue + 0.5f).toInt() else 0
        val step = if (steps > 0) theta / steps else 0f
        val s = sin(step)
        val c = cos(step)
        if (steps > 0 && (s == 0f || c == 1f)) steps = 0
        var previous = previousOutset
        repeat(max(steps - 1, 0)) {
            val normal = ShadowPoint(previous.x * c - previous.y * s, previous.y * c + previous.x * s)
            add(previousPoint + normal, 0)
            triangle(previousUmbra, positions.lastIndex, positions.lastIndex - 1)
            previous = normal
        }
        if (finish && steps > 0) {
            add(previousPoint + next, 0)
            triangle(previousUmbra, positions.lastIndex, positions.lastIndex - 1)
        }
        previousOutset = next
        return steps > 0
    }
}

private data class InsetEdge(val p: ShadowPoint, val vector: ShadowPoint, var previous: Int, var next: Int,
    var intersection: ShadowPoint = p, var t: Float = -Float.MAX_VALUE)

internal fun insetPolygon(input: List<ShadowPoint>, inset: Float): List<ShadowPoint>? {
    if (input.size < 3 || input.size > 65535 || inset < -NearlyZero || !inset.isFinite()) return null
    if (inset <= NearlyZero) return input.toList()
    val crossTolerance = NearlyZero * NearlyZero
    var area = 0f
    var v = input[1] - input[0]
    for (i in 2..input.lastIndex) { val n = input[i] - input[0]; area += v.cross(n); v = n }
    if (abs(area) <= crossTolerance) return null
    val winding = if (area > 0f) 1 else -1
    fun side(p: ShadowPoint, vector: ShadowPoint, point: ShadowPoint): Int {
        val cross = vector.cross(point - p)
        return if (abs(cross) <= crossTolerance) 0 else if (cross > 0f) 1 else -1
    }
    val edges = input.indices.map { i ->
        val previous = (i + input.size - 1) % input.size
        val next = (i + 1) % input.size
        if (side(input[previous], input[i] - input[previous], input[next]) * winding < 0) return null
        val vector = input[next] - input[i]
        val perpendicular = ShadowPoint(-vector.y, vector.x).setLength(inset * winding) ?: ShadowPoint(0f, 0f)
        InsetEdge(input[i] + perpendicular, vector, previous, next)
    }
    var head = 0
    fun remove(index: Int) {
        val edge = edges[index]
        edges[edge.previous].next = edge.next
        edges[edge.next].previous = edge.previous
        if (head == index) head = if (edge.next == index) -1 else edge.next
    }
    var current = head
    var previous = edges[current].previous
    var iterations = 0
    while (head >= 0 && previous != current) {
        if (++iterations > input.size * input.size) return null
        val a = edges[previous]
        val b = edges[current]
        val result = intersection(a.p, a.vector, b.p, b.vector)
        if (result != null) {
            if (result.second < a.t) {
                remove(previous)
                previous = a.previous
            } else if (b.t > -Float.MAX_VALUE && within(result.first, b.intersection, 1e-6f)) break
            else {
                b.intersection = result.first
                b.t = result.third
                previous = current
                current = b.next
            }
        } else {
            val side = winding * side(b.p, b.vector, a.p)
            if (side < 0 && side == winding * side(b.p, b.vector, a.p + a.vector)) {
                remove(previous)
                previous = a.previous
            } else { remove(current); current = b.next }
        }
    }
    if (head < 0) return null
    val output = ArrayList<ShadowPoint>()
    output.add(edges[head].intersection)
    current = edges[head].next
    while (current != head) {
        if (!within(edges[current].intersection, output.last(), 0.01f)) output.add(edges[current].intersection)
        current = edges[current].next
    }
    if (output.size > 1 && within(output[0], output.last(), 0.01f)) output.removeAt(output.lastIndex)
    return output.takeIf { isConvex(it) }
}

private fun intersection(p0: ShadowPoint, v0: ShadowPoint, p1: ShadowPoint, v1: ShadowPoint): Triple<ShadowPoint, Float, Float>? {
    val w = p1 - p0
    var denominator = v0.cross(v1)
    var s: Float
    var t: Float
    fun outside(n: Float, d: Float) = if (d > 0f) n < 0f || n > d else n > 0f || n < d
    val crossTolerance = NearlyZero * NearlyZero
    if (abs(denominator) <= crossTolerance) {
        if (abs(w.cross(v0)) > crossTolerance || abs(w.cross(v1)) > crossTolerance) return null
        val d0 = v0.lengthSquared()
        val d1 = v1.lengthSquared()
        if (d0 == 0f || !d0.isFinite()) {
            if (d1 == 0f || !d1.isFinite()) return if (w.lengthSquared() == 0f) Triple(p0, 0f, 0f) else null
            t = v1.dot(w * -1f)
            denominator = d1
            if (outside(t, denominator)) return null
            s = 0f
        } else {
            s = v0.dot(w)
            denominator = d0
            t = 0f
            if (outside(s, denominator)) {
                if (d1 == 0f || !d1.isFinite()) return null
                val old = s
                s = v0.dot(w + v1)
                t = denominator
                if (outside(s, denominator)) {
                    if (s * old > 0f) return null
                    s = 0f
                    t = v1.dot(w * -1f)
                    denominator = d1
                }
            }
        }
    } else {
        s = w.cross(v1)
        if (outside(s, denominator)) return null
        t = w.cross(v0)
        if (outside(t, denominator)) return null
    }
    val ss = s / denominator
    val tt = t / denominator
    return Triple(p0 + v0 * ss, ss, tt)
}

internal fun isConvex(p: List<ShadowPoint>): Boolean {
    if (p.size < 3) return false
    var lastCross = 0f
    var signX = 0
    var signY = 0
    var current = 0
    var next = 1
    var a = p[current] - p.last()
    var x = a.x
    var y = a.y
    var b = p[next] - p[current]
    repeat(p.size) {
        if (!p[it].x.isFinite() || !p[it].y.isFinite()) return false
        val cross = a.cross(b)
        if (lastCross * cross < 0f) return false
        if (cross != 0f) lastCross = cross
        if (x * b.x < 0f) signX++
        if (y * b.y < 0f) signY++
        if (signX > 2 || signY > 2) return false
        current = next
        next = (current + 1) % p.size
        if (b.x != 0f) x = b.x
        if (b.y != 0f) y = b.y
        a = b
        b = p[next] - p[current]
    }
    return true
}

internal fun polygonWinding(p: List<ShadowPoint>): Int {
    if (p.size < 3) return 0
    var area = 0f
    var v = p[1] - p[0]
    for (i in 2..p.lastIndex) { val next = p[i] - p[0]; area += v.cross(next); v = next }
    return if (abs(area) <= NearlyZero * NearlyZero) 0 else if (area > 0f) 1 else -1
}

private fun side(p: ShadowPoint, v: ShadowPoint, q: ShadowPoint): Int {
    val cross = v.cross(q - p)
    return if (abs(cross) <= NearlyZero * NearlyZero) 0 else if (cross > 0f) 1 else -1
}

private fun radial(a: ShadowPoint, b: ShadowPoint, offset: Float): Triple<Float, Float, Int>? {
    val cosine = a.dot(b)
    val sine = a.cross(b)
    if (!cosine.isFinite() || !sine.isFinite()) return null
    val theta = atan2(sine, cosine)
    val value = abs(offset * theta * .25f)
    if (value >= 65535f) return null
    val steps = floor(value + .5f).toInt()
    val delta = if (steps > 0) theta / steps else 0f
    val s = sin(delta)
    val c = cos(delta)
    if (steps > 0 && (s == 0f || c == 1f)) return null
    return Triple(s, c, steps)
}

private data class OffsetPolygon(val points: List<ShadowPoint>, val indices: List<Int>)
private class OffsetEdge(val p: ShadowPoint, val v: ShadowPoint, var index: Int, val end: Int) {
    var previous = 0
    var next = 0
    var point = p
    var t = -Float.MAX_VALUE
    fun intersection(other: OffsetEdge): Triple<ShadowPoint, Float, Float>? =
        if (end == other.index && within(p + v, other.p, NearlyZero)) Triple(p + v, 1f, 0f)
        else intersection(p, v, other.p, other.v)
    fun crossing(other: OffsetEdge): Float {
        val denominator = v.cross(other.v)
        if (abs(denominator) <= NearlyZero * NearlyZero) return Float.MAX_VALUE
        var s = (other.p - p).cross(other.v) / denominator
        s = if (s < 0f) -s else s - 1f
        return s * abs(s) * v.dot(v)
    }
}

private fun offsetPolygon(input: List<ShadowPoint>, offset: Float, halfBounds: Float): OffsetPolygon? {
    if (input.size < 3 || input.size >= 65535 || !offset.isFinite() || offset > halfBounds) return null
    if (abs(offset) <= NearlyZero) return OffsetPolygon(input.toList(), input.indices.toList())
    val winding = polygonWinding(input)
    if (winding == 0) return null
    val normals = input.indices.map { i ->
        if (!input[i].x.isFinite() || !input[i].y.isFinite()) return null
        val v = input[(i + 1) % input.size] - input[i]
        ShadowPoint(-v.y, v.x).setLength(offset * winding) ?: return null
    }
    val edges = ArrayList<OffsetEdge>()
    fun add(a: ShadowPoint, b: ShadowPoint, start: Int, end: Int) { edges.add(OffsetEdge(a, b - a, start, end)) }
    input.indices.forEach { i ->
        val previous = (i + input.size - 1) % input.size
        val next = (i + 1) % input.size
        if (side(input[previous], input[i] - input[previous], input[next]) * winding * offset < 0f) {
            val radial = radial(normals[previous], normals[i], offset) ?: return null
            var normal = normals[previous]
            repeat(max(radial.third - 1, 0)) {
                val current = ShadowPoint(normal.x * radial.second - normal.y * radial.first,
                    normal.y * radial.second + normal.x * radial.first)
                add(input[i] + normal, input[i] + current, i, i)
                normal = current
            }
            add(input[i] + normal, input[i] + normals[i], i, i)
        }
        add(input[i] + normals[i], input[next] + normals[i], i, next)
    }
    edges.indices.forEach { i -> edges[i].previous = (i + edges.size - 1) % edges.size; edges[i].next = (i + 1) % edges.size }
    var head = 0
    var count = edges.size
    fun remove(i: Int) {
        val edge = edges[i]
        edges[edge.previous].next = edge.next
        edges[edge.next].previous = edge.previous
        if (head == i) head = if (edge.next == i) -1 else edge.next
    }
    var previous = edges.lastIndex
    var current = head
    var iterations = 0L
    val maxIterations = edges.size.toLong() * edges.size
    while (head >= 0 && previous != current && count > 0) {
        if (++iterations > maxIterations) return null
        val a = edges[previous]
        val b = edges[current]
        val hit = a.intersection(b)
        if (hit != null) {
            if (hit.second < a.t) { remove(previous); previous = a.previous; count-- }
            else if (b.t > -Float.MAX_VALUE && within(hit.first, b.point, 1e-6f)) break
            else { b.point = hit.first; b.t = hit.third; b.index = a.end; previous = current; current = b.next }
        } else {
            val pp = edges[a.previous]
            val nn = edges[b.next]
            val d0 = b.crossing(pp)
            val d1 = a.crossing(nn)
            if (d0 < 0f && d1 < 0f) {
                val samePrevious = within(pp.p + pp.v, a.p, NearlyZero)
                val sameCurrent = within(b.p + b.v, nn.p, NearlyZero)
                if (sameCurrent && !samePrevious) { remove(current); current = b.next; count--; continue }
                if (samePrevious && !sameCurrent) { remove(previous); previous = a.previous; count--; continue }
            }
            if (d0 < d1) { remove(previous); previous = a.previous }
            else { remove(current); current = b.next }
            count--
        }
    }
    if (head < 0 || count == 0 || count >= 65535) return null
    val output = ArrayList<ShadowPoint>()
    val map = ArrayList<Int>()
    output.add(edges[head].point); map.add(edges[head].index)
    current = edges[head].next
    while (current != head) {
        if (!within(edges[current].point, output.last(), .01f)) { output.add(edges[current].point); map.add(edges[current].index) }
        current = edges[current].next
    }
    if (output.size > 1 && within(output[0], output.last(), .01f)) { output.removeAt(output.lastIndex); map.removeAt(map.lastIndex) }
    return if (winding * polygonWinding(output) > 0 && isSimple(output)) OffsetPolygon(output, map) else null
}

internal fun isSimple(p: List<ShadowPoint>): Boolean {
    if (p.size < 3) return false
    if (isConvex(p)) return true
    if (p.size > 2048 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    fun left(a: ShadowPoint, b: ShadowPoint) = a.x < b.x || (a.x == b.x && a.y > b.y)
    val queue = java.util.PriorityQueue<Int> { a, b -> if (left(p[a], p[b])) -1 else if (left(p[b], p[a])) 1 else 0 }
    p.indices.forEach { i ->
        if (p[(i + p.size - 1) % p.size] == p[(i + 1) % p.size]) return false
        queue.add(i)
    }
    val edges = ShadowSweep(p.size)
    while (queue.isNotEmpty()) {
        val i = queue.remove()
        val previous = (i + p.size - 1) % p.size
        val next = (i + 1) % p.size
        val prevLeft = left(p[previous], p[i])
        val nextLeft = left(p[next], p[i])
        val valid = when {
            !prevLeft && !nextLeft -> edges.insert(p[i], p[previous], i, previous) && edges.insert(p[i], p[next], i, next)
            prevLeft && nextLeft -> edges.remove(p[previous], p[i], previous, i) && edges.remove(p[next], p[i], next, i)
            prevLeft -> edges.replace(p[previous], p[i], p[next], previous, i, next)
            else -> edges.replace(p[next], p[i], p[previous], next, i, previous)
        }
        if (!valid) return false
    }
    return true
}

private class ShadowSweep(private val maximum: Int) {
    private class Edge(var p: ShadowPoint, var v: ShadowPoint, var start: Int, var end: Int) {
        val children = intArrayOf(-1, -1)
        var above = -1
        var below = -1
        var red = true
        fun intersects(q: ShadowPoint, w: ShadowPoint, index0: Int, index1: Int): Boolean {
            if (start == index0 || end == index0 || start == index1 || end == index1) return false
            val p1 = p + v
            val q1 = q + w
            return if (p.x < q.x) {
                if (q1.x < p1.x) side(p, v, q) * side(p, v, q1) < 0
                else side(p, v, q) * side(q, w, p1) > 0
            } else {
                if (p1.x < q1.x) side(q, w, p) * side(q, w, p1) < 0
                else side(q, w, p) * side(p, v, q1) > 0
            }
        }
        fun intersects(other: Edge) = intersects(other.p, other.v, other.start, other.end)
    }
    private val pool = arrayListOf(Edge(ShadowPoint(0f, 0f), ShadowPoint(0f, 0f), -1, -1).apply { red = false })
    fun insert(p: ShadowPoint, p1: ShadowPoint, a: Int, b: Int): Boolean {
        val v = p1 - p
        if (!v.x.isFinite() || !v.y.isFinite()) return false
        if (pool[0].children[1] < 0) {
            val root = allocate(p, v, a, b)
            if (root < 0) return false
            pool[0].children[1] = root; pool[root].red = false
            return true
        }
        var top = 0
        var grandparent = -1
        var parent = -1
        var current = pool[0].children[1]
        var direction = 0
        var last = 0
        var predecessor = -1
        var successor = -1
        while (true) {
            if (current < 0) {
                if (predecessor >= 0 && pool[predecessor].intersects(p, v, a, b) ||
                    successor >= 0 && pool[successor].intersects(p, v, a, b)) return false
                current = allocate(p, v, a, b)
                if (current < 0) return false
                pool[parent].children[direction] = current
                pool[current].above = predecessor; pool[current].below = successor
                if (predecessor >= 0) {
                    if (pool[predecessor].p == p && pool[predecessor].v == v) return false
                    pool[predecessor].below = current
                }
                if (successor >= 0) {
                    if (pool[successor].p == p && pool[successor].v == v) return false
                    pool[successor].above = current
                }
                if (red(parent)) {
                    val d = if (pool[top].children[1] == grandparent) 1 else 0
                    pool[top].children[d] = if (current == pool[parent].children[last]) rotate(grandparent, 1 - last)
                        else doubleRotate(grandparent, 1 - last)
                }
                break
            } else if (red(pool[current].children[0]) && red(pool[current].children[1])) {
                pool[current].red = true
                pool[pool[current].children[0]].red = false; pool[pool[current].children[1]].red = false
                if (red(parent)) {
                    val d = if (pool[top].children[1] == grandparent) 1 else 0
                    pool[top].children[d] = if (current == pool[parent].children[last]) rotate(grandparent, 1 - last)
                        else doubleRotate(grandparent, 1 - last)
                }
            }
            last = direction
            val edge = pool[current]
            val s = side(edge.p, edge.v, if (edge.start == a) p1 else p)
            if (s == 0) return false
            direction = if (s < 0) 1 else 0
            if (direction == 0) successor = current else predecessor = current
            if (grandparent >= 0) top = grandparent
            grandparent = parent; parent = current; current = edge.children[direction]
        }
        pool[pool[0].children[1]].red = false
        return true
    }
    fun replace(p0: ShadowPoint, p1: ShadowPoint, p2: ShadowPoint, a: Int, b: Int, c: Int): Boolean {
        if (pool[0].children[1] < 0) return false
        var current = 0
        var found = -1
        var direction = 1
        while (pool[current].children[direction] >= 0) {
            current = pool[current].children[direction]
            val edge = pool[current]
            if (edge.start == a && edge.end == b) { found = current; break }
            val s = side(edge.p, edge.v, if (edge.end == b) p0 else p1)
            if (s == 0) return false
            direction = if (s < 0) 1 else 0
        }
        if (found < 0) return false
        val edge = pool[found]
        val v = p2 - p1
        for (other in intArrayOf(edge.above, edge.below)) {
            if (other >= 0 && (pool[other].intersects(edge) || pool[other].intersects(p1, v, b, c))) return false
        }
        edge.p = p1; edge.v = v; edge.start = b; edge.end = c
        return true
    }
    fun remove(p0: ShadowPoint, p1: ShadowPoint, a: Int, b: Int): Boolean {
        if (pool[0].children[1] < 0) return false
        var current = 0
        var parent = -1
        var grandparent: Int
        var found = -1
        var direction = 1
        while (pool[current].children[direction] >= 0) {
            val last = direction
            grandparent = parent; parent = current; current = pool[current].children[direction]
            val edge = pool[current]
            if (edge.start == a && edge.end == b) { found = current; direction = 0 }
            else {
                val s = side(edge.p, edge.v, if (edge.end == b) p0 else p1)
                if (s == 0) return false
                direction = if (s < 0) 1 else 0
            }
            if (!red(current) && !red(edge.children[direction])) {
                if (red(edge.children[1 - direction])) {
                    val replacement = rotate(current, direction)
                    pool[parent].children[last] = replacement; parent = replacement
                } else {
                    val sibling = pool[parent].children[1 - last]
                    if (sibling >= 0) {
                        val s = pool[sibling]
                        if (!red(s.children[1 - last]) && !red(s.children[last])) {
                            pool[parent].red = false; s.red = true; edge.red = true
                        } else {
                            val d = if (pool[grandparent].children[1] == parent) 1 else 0
                            if (red(s.children[last])) pool[grandparent].children[d] = doubleRotate(parent, last)
                            else if (red(s.children[1 - last])) pool[grandparent].children[d] = rotate(parent, last)
                            edge.red = true
                            val root = pool[pool[grandparent].children[d]]
                            root.red = true
                            pool[root.children[0]].red = false; pool[root.children[1]].red = false
                        }
                    }
                }
            }
        }
        if (found >= 0) {
            val edge = pool[found]
            var predecessor = edge.above
            val successor = edge.below
            if (predecessor >= 0 && pool[predecessor].intersects(edge) ||
                successor >= 0 && pool[successor].intersects(edge)) return false
            val tail = pool[current]
            if (found != current) {
                edge.p = tail.p; edge.v = tail.v; edge.start = tail.start; edge.end = tail.end
                edge.above = tail.above; predecessor = edge.above
            } else if (successor >= 0) pool[successor].above = predecessor
            if (predecessor >= 0) pool[predecessor].below = tail.below
            pool[parent].children[if (pool[parent].children[1] == current) 1 else 0] =
                tail.children[if (tail.children[0] < 0) 1 else 0]
        }
        if (pool[0].children[1] >= 0) pool[pool[0].children[1]].red = false
        return true
    }

    private fun allocate(p: ShadowPoint, v: ShadowPoint, a: Int, b: Int): Int {
        if (pool.size > maximum) return -1
        pool.add(Edge(p, v, a, b))
        return pool.lastIndex
    }

    private fun red(i: Int) = i >= 0 && pool[i].red

    private fun rotate(i: Int, direction: Int): Int {
        val other = 1 - direction
        val replacement = pool[i].children[other]
        pool[i].children[other] = pool[replacement].children[direction]
        pool[replacement].children[direction] = i
        pool[i].red = true; pool[replacement].red = false
        return replacement
    }

    private fun doubleRotate(i: Int, direction: Int): Int {
        pool[i].children[1 - direction] = rotate(pool[i].children[1 - direction], 1 - direction)
        return rotate(i, direction)
    }
}

private fun triangulate(p: List<ShadowPoint>, map: IntArray, indices: MutableList<Int>): Boolean {
    if (p.size < 3 || p.size >= 65535) return false
    val width = p.maxOf { it.x } - p.minOf { it.x }
    val height = p.maxOf { it.y } - p.minOf { it.y }
    if (!width.isFinite() || !height.isFinite()) return false
    val horizontal = sqrt(p.size * width / height)
    if (!horizontal.isFinite()) return false
    val hCount = floor(horizontal + 0.5f).toInt().coerceIn(1, p.size)
    val vCount = p.size / hCount
    // 保留原 ReflexHash 的初始化失败边界; 小网格直接检查 reflex 列表。
    if (!((hCount - 0.001f) / width).isFinite() || !((vCount - 0.001f) / height).isFinite()) return false
    val winding = polygonWinding(p)
    if (winding == 0) return false
    val previous = IntArray(p.size) { (it + p.size - 1) % p.size }
    val next = IntArray(p.size) { (it + 1) % p.size }
    val reflex = BooleanArray(p.size) { i -> winding * (p[i] - p[previous[i]]).cross(p[next[i]] - p[i]) <= NearlyZero * NearlyZero }
    val convex = ArrayList<Int>()
    p.indices.forEach { i -> if (!reflex[i]) {
        if (reflex[previous[i]] || reflex[next[i]]) convex.add(0, i) else convex.add(i)
    } }
    fun inside(a: ShadowPoint, b: ShadowPoint, c: ShadowPoint, q: ShadowPoint): Boolean {
        val n = (b - a).cross(c - b)
        return n * (b - a).cross(q - a) >= NearlyZero && n * (c - b).cross(q - b) >= NearlyZero &&
            n * (a - c).cross(q - c) >= NearlyZero
    }
    fun reclassify(i: Int) {
        if (reflex[i] && winding * (p[i] - p[previous[i]]).cross(p[next[i]] - p[i]) > NearlyZero * NearlyZero) {
            reflex[i] = false; convex.add(i)
        }
    }
    var count = p.size
    while (count > 3) {
        val ear = convex.firstOrNull { i ->
            p.indices.none { q -> reflex[q] && q != previous[i] && q != next[i] && inside(p[previous[i]], p[i], p[next[i]], p[q]) }
        } ?: return false
        val a = previous[ear]
        val b = next[ear]
        indices.add(map[a]); indices.add(map[ear]); indices.add(map[b])
        convex.remove(ear)
        count--
        next[a] = b; reclassify(a)
        previous[b] = a; reclassify(b)
    }
    convex.forEach { indices.add(map[it]) }
    return true
}
