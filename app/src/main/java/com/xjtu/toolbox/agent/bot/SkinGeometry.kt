package com.xjtu.toolbox.agent.bot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 自由矢量几何：导入皮肤不再被 64 射线的星形轮廓限制。
 *
 * 一条轮廓 = 任意条子路径；每条子路径 = 起点 + 若干三次贝塞尔段 + 闭合标记。
 * 直线、二次曲线、圆弧在解析时统一升成三次段，于是「结构相同」的两条轮廓可以
 * 逐控制点线性插值——凹形、月牙、圆环、断开的碎片都能表达，插值代价不变。
 *
 * 纯 Kotlin，不引用 Compose 或 Android 类型：JVM 单元测试可以直接跑。
 */

/** 起点 + n 段三次曲线，打平成 `[x0,y0, c1x,c1y,c2x,c2y,x1,y1, ...]`。 */
class SkinSubpath(val pts: DoubleArray, val closed: Boolean) {
    init {
        require(pts.size >= 8 && (pts.size - 2) % 6 == 0) { "子路径至少需要一段三次曲线" }
    }

    val segments: Int get() = (pts.size - 2) / 6
}

class SkinOutline(val subpaths: List<SkinSubpath>) {
    /** 插值兼容性指纹：子路径数量、各自段数与闭合状态一致才能逐点插值。 */
    val signature: String = subpaths.joinToString("|") { "${it.segments}${if (it.closed) "c" else "o"}" }

    val isEmpty: Boolean get() = subpaths.isEmpty()

    /** 控制点包围盒；用于图片装框和预览缩放，不要求是精确的曲线包围盒。 */
    fun bounds(): DoubleArray {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        subpaths.forEach { sub ->
            var i = 0
            while (i < sub.pts.size) {
                if (sub.pts[i] < minX) minX = sub.pts[i]
                if (sub.pts[i + 1] < minY) minY = sub.pts[i + 1]
                if (sub.pts[i] > maxX) maxX = sub.pts[i]
                if (sub.pts[i + 1] > maxY) maxY = sub.pts[i + 1]
                i += 2
            }
        }
        if (minX > maxX) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(minX, minY, maxX, maxY)
    }
}

/**
 * 把仿射直接算进控制点。
 *
 * 仿射变换保持三次贝塞尔，所以逐控制点变换与"变换整条曲线"完全等价。绘制层因此
 * 不必把矩阵交给画布——几何全程留在纯 Kotlin 里，可以在 JVM 单测里断言到小数点后。
 */
fun SkinOutline.transformedBy(m: SkinTransform): SkinOutline = SkinOutline(
    subpaths.map { sub ->
        val pts = DoubleArray(sub.pts.size)
        var i = 0
        while (i < sub.pts.size) {
            val x = sub.pts[i]
            val y = sub.pts[i + 1]
            pts[i] = m.mapX(x, y)
            pts[i + 1] = m.mapY(x, y)
            i += 2
        }
        SkinSubpath(pts, sub.closed)
    }
)

/** 结构相同则逐控制点插值；不同返回 null，调用方改用交叉淡入淡出。 */
fun lerpOutline(a: SkinOutline, b: SkinOutline, t: Double): SkinOutline? {
    if (a.signature != b.signature) return null
    if (t <= 0.0) return a
    if (t >= 1.0) return b
    return SkinOutline(
        a.subpaths.mapIndexed { index, sa ->
            val sb = b.subpaths[index]
            SkinSubpath(DoubleArray(sa.pts.size) { i -> sa.pts[i] + (sb.pts[i] - sa.pts[i]) * t }, sa.closed)
        }
    )
}

/** 仿射变换：x' = a·x + c·y + e，y' = b·x + d·y + f。 */
class SkinTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun scaled(k: Double) = SkinTransform(a * k, b * k, c * k, d * k, e * k, f * k)

    /** 复合：先应用 [inner]，再应用自己。 */
    fun times(inner: SkinTransform) = SkinTransform(
        a * inner.a + c * inner.b,
        b * inner.a + d * inner.b,
        a * inner.c + c * inner.d,
        b * inner.c + d * inner.d,
        a * inner.e + c * inner.f + e,
        b * inner.e + d * inner.f + f,
    )

    fun mapX(x: Double, y: Double): Double = a * x + c * y + e

    fun mapY(x: Double, y: Double): Double = b * x + d * y + f

    /** 面积缩放的几何平均，用于把描边宽度换算到变换后的空间。 */
    val lineScale: Double
        get() {
            val v = sqrt(abs(a * d - b * c))
            return if (v.isFinite() && v > 1e-9) v else 1.0
        }

    companion object {
        val IDENTITY = SkinTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** 先绕 pivot 缩放、再绕 pivot 旋转，最后平移 (cx, cy)。 */
        fun of(
            cx: Double,
            cy: Double,
            sx: Double,
            sy: Double,
            rot: Double,
            pivotX: Double = 0.0,
            pivotY: Double = 0.0,
        ): SkinTransform {
            val co = cos(rot)
            val si = sin(rot)
            val a = co * sx
            val b = si * sx
            val c = -si * sy
            val d = co * sy
            return SkinTransform(
                a, b, c, d,
                pivotX + cx - (a * pivotX + c * pivotY),
                pivotY + cy - (b * pivotX + d * pivotY),
            )
        }

        /**
         * v1 径向皮肤的姿态：先旋转，再在屏幕轴上压扁，最后平移。
         * 与 [toPoints] 同序，导入旧皮肤的画面不会因为换表示而改变。
         */
        fun ofRadialV1(cx: Double, cy: Double, sx: Double, sy: Double, rot: Double): SkinTransform {
            val co = cos(rot)
            val si = sin(rot)
            return SkinTransform(sx * co, sy * si, -sx * si, sy * co, cx, cy)
        }
    }
}

/** 累积三次段的构造器；直线与二次曲线都被升成三次，保证插值结构统一。 */
class SkinPathBuilder {
    private val done = ArrayList<SkinSubpath>()
    private var cur: ArrayList<Double>? = null
    private var startX = 0.0
    private var startY = 0.0
    var x = 0.0
        private set
    var y = 0.0
        private set

    fun moveTo(px: Double, py: Double) {
        flush(false)
        cur = arrayListOf(px, py)
        startX = px
        startY = py
        x = px
        y = py
    }

    fun lineTo(px: Double, py: Double) {
        cubicTo(
            x + (px - x) / 3.0, y + (py - y) / 3.0,
            x + (px - x) * 2.0 / 3.0, y + (py - y) * 2.0 / 3.0,
            px, py,
        )
    }

    fun quadTo(qx: Double, qy: Double, px: Double, py: Double) {
        cubicTo(
            x + (qx - x) * 2.0 / 3.0, y + (qy - y) * 2.0 / 3.0,
            px + (qx - px) * 2.0 / 3.0, py + (qy - py) * 2.0 / 3.0,
            px, py,
        )
    }

    fun cubicTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, px: Double, py: Double) {
        val target = cur ?: arrayListOf(x, y).also {
            cur = it
            startX = x
            startY = y
        }
        target.add(c1x); target.add(c1y)
        target.add(c2x); target.add(c2y)
        target.add(px); target.add(py)
        x = px
        y = py
    }

    /** SVG 的 A 指令：端点参数化圆弧，按 90° 上限切成三次段。 */
    fun arcTo(rxIn: Double, ryIn: Double, rotDeg: Double, largeArc: Boolean, sweep: Boolean, px: Double, py: Double) {
        val x1 = x
        val y1 = y
        if (rxIn == 0.0 || ryIn == 0.0 || (x1 == px && y1 == py)) {
            lineTo(px, py)
            return
        }
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        val phi = rotDeg / 180.0 * PI
        val co = cos(phi)
        val si = sin(phi)
        val dx2 = (x1 - px) / 2.0
        val dy2 = (y1 - py) / 2.0
        val x1p = co * dx2 + si * dy2
        val y1p = -si * dx2 + co * dy2
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1.0) {
            val k = sqrt(lambda)
            rx *= k
            ry *= k
        }
        val denom = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var factor = if (denom <= 0.0) 0.0 else (rx * rx * ry * ry - denom) / denom
        if (factor < 0.0) factor = 0.0
        var coef = sqrt(factor)
        if (largeArc == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = co * cxp - si * cyp + (x1 + px) / 2.0
        val cy = si * cxp + co * cyp + (y1 + py) / 2.0
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val theta1 = angleBetween(1.0, 0.0, ux, uy)
        var delta = angleBetween(ux, uy, vx, vy)
        if (!sweep && delta > 0.0) delta -= 2.0 * PI
        if (sweep && delta < 0.0) delta += 2.0 * PI
        val steps = maxOf(1, ceil(abs(delta) / (PI / 2.0) - 1e-9).toInt())
        val step = delta / steps
        val alpha = 4.0 / 3.0 * tan(step / 4.0)
        var th = theta1
        for (i in 0 until steps) {
            val next = th + step
            val cosA = cos(th); val sinA = sin(th)
            val cosB = cos(next); val sinB = sin(next)
            val ax = cx + rx * cosA * co - ry * sinA * si
            val ay = cy + rx * cosA * si + ry * sinA * co
            val bx = cx + rx * cosB * co - ry * sinB * si
            val by = cy + rx * cosB * si + ry * sinB * co
            val dax = -rx * sinA * co - ry * cosA * si
            val day = -rx * sinA * si + ry * cosA * co
            val dbx = -rx * sinB * co - ry * cosB * si
            val dby = -rx * sinB * si + ry * cosB * co
            x = ax
            y = ay
            cubicTo(ax + alpha * dax, ay + alpha * day, bx - alpha * dbx, by - alpha * dby, bx, by)
            th = next
        }
        x = px
        y = py
    }

    fun close() {
        val target = cur ?: return
        if (hypot(x - startX, y - startY) > 1e-9) lineTo(startX, startY)
        if (target.size >= 8) done.add(SkinSubpath(target.toDoubleArray(), true))
        cur = null
        x = startX
        y = startY
    }

    fun build(): SkinOutline {
        flush(false)
        return SkinOutline(done.toList())
    }

    private fun flush(closed: Boolean) {
        val target = cur ?: return
        if (target.size >= 8) done.add(SkinSubpath(target.toDoubleArray(), closed))
        cur = null
    }

    private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val n = hypot(ux, uy) * hypot(vx, vy)
        if (n == 0.0) return 0.0
        val cosine = ((ux * vx + uy * vy) / n).coerceIn(-1.0, 1.0)
        val s = sign(ux * vy - uy * vx)
        return (if (s == 0.0) 1.0 else s) * acos(cosine)
    }
}

class SkinPathSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * SVG path `d` 的解析：支持 M L H V C S Q T A Z 及其相对形式。
 * 不支持的指令直接报错，而不是悄悄画错。
 */
object SkinPathParser {
    private const val MAX_SEGMENTS = 20_000

    fun parse(d: String): SkinOutline {
        val b = SkinPathBuilder()
        val scanner = Scanner(d)
        var command = ' '
        var lastCubicCx = 0.0
        var lastCubicCy = 0.0
        var lastQuadCx = 0.0
        var lastQuadCy = 0.0
        var lastWasCubic = false
        var lastWasQuad = false
        var issued = 0
        var started = false
        while (true) {
            val token = scanner.peekCommand()
            if (token != null) {
                command = token
                scanner.skip()
                if (command.uppercaseChar() != 'Z' && !scanner.hasNumber()) {
                    throw SkinPathSyntaxException("path 指令 $command 缺少参数")
                }
            } else if (!scanner.hasNumber()) {
                break
            } else if (command == ' ') {
                throw SkinPathSyntaxException("path 必须以指令字母开头")
            } else if (command == 'M') {
                command = 'L'
            } else if (command == 'm') {
                command = 'l'
            }
            val rel = command.isLowerCase()
            val ox = if (rel) b.x else 0.0
            val oy = if (rel) b.y else 0.0
            when (command.uppercaseChar()) {
                'M' -> {
                    b.moveTo(ox + scanner.number(), oy + scanner.number())
                    started = true
                }
                'L' -> {
                    requireStarted(started)
                    b.lineTo(ox + scanner.number(), oy + scanner.number())
                }
                'H' -> {
                    requireStarted(started)
                    b.lineTo(ox + scanner.number(), b.y)
                }
                'V' -> {
                    requireStarted(started)
                    b.lineTo(b.x, oy + scanner.number())
                }
                'C' -> {
                    requireStarted(started)
                    val c1x = ox + scanner.number(); val c1y = oy + scanner.number()
                    val c2x = ox + scanner.number(); val c2y = oy + scanner.number()
                    b.cubicTo(c1x, c1y, c2x, c2y, ox + scanner.number(), oy + scanner.number())
                    lastCubicCx = c2x; lastCubicCy = c2y
                }
                'S' -> {
                    requireStarted(started)
                    val c1x = if (lastWasCubic) 2 * b.x - lastCubicCx else b.x
                    val c1y = if (lastWasCubic) 2 * b.y - lastCubicCy else b.y
                    val c2x = ox + scanner.number(); val c2y = oy + scanner.number()
                    b.cubicTo(c1x, c1y, c2x, c2y, ox + scanner.number(), oy + scanner.number())
                    lastCubicCx = c2x; lastCubicCy = c2y
                }
                'Q' -> {
                    requireStarted(started)
                    val qx = ox + scanner.number(); val qy = oy + scanner.number()
                    b.quadTo(qx, qy, ox + scanner.number(), oy + scanner.number())
                    lastQuadCx = qx; lastQuadCy = qy
                }
                'T' -> {
                    requireStarted(started)
                    val qx = if (lastWasQuad) 2 * b.x - lastQuadCx else b.x
                    val qy = if (lastWasQuad) 2 * b.y - lastQuadCy else b.y
                    b.quadTo(qx, qy, ox + scanner.number(), oy + scanner.number())
                    lastQuadCx = qx; lastQuadCy = qy
                }
                'A' -> {
                    requireStarted(started)
                    val rx = scanner.number(); val ry = scanner.number(); val rot = scanner.number()
                    val large = scanner.flag(); val sweep = scanner.flag()
                    b.arcTo(rx, ry, rot, large, sweep, ox + scanner.number(), oy + scanner.number())
                }
                'Z' -> b.close()
                else -> throw SkinPathSyntaxException("不支持的 path 指令 $command")
            }
            val upper = command.uppercaseChar()
            lastWasCubic = upper == 'C' || upper == 'S'
            lastWasQuad = upper == 'Q' || upper == 'T'
            if (++issued > MAX_SEGMENTS) throw SkinPathSyntaxException("path 指令过多")
        }
        val outline = b.build()
        if (outline.isEmpty) throw SkinPathSyntaxException("path 没有可绘制的段")
        return outline
    }

    private fun requireStarted(started: Boolean) {
        if (!started) throw SkinPathSyntaxException("path 必须先用 M 指定起点")
    }

    private class Scanner(private val s: String) {
        private var i = 0

        private fun skipSeparators() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun peekCommand(): Char? {
            skipSeparators()
            if (i >= s.length) return null
            val c = s[i]
            if (!c.isLetter()) return null
            if (c.uppercaseChar() !in "MLHVCSQTAZ") throw SkinPathSyntaxException("不支持的 path 指令 $c")
            return c
        }

        fun skip() {
            i++
        }

        fun hasNumber(): Boolean {
            skipSeparators()
            if (i >= s.length) return false
            val c = s[i]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }

        fun number(): Double {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val mark = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                if (i < s.length && s[i].isDigit()) {
                    while (i < s.length && s[i].isDigit()) i++
                } else {
                    i = mark
                }
            }
            val text = s.substring(start, i)
            val value = text.toDoubleOrNull() ?: throw SkinPathSyntaxException("path 中的数字无效：$text")
            if (!value.isFinite()) throw SkinPathSyntaxException("path 中的数字必须有限")
            return value
        }

        /** SVG 弧线的 0/1 标志位可以不带分隔符地黏在一起。 */
        fun flag(): Boolean {
            skipSeparators()
            if (i >= s.length) throw SkinPathSyntaxException("弧线缺少标志位")
            val c = s[i]
            if (c != '0' && c != '1') throw SkinPathSyntaxException("弧线标志位必须是 0 或 1")
            i++
            return c == '1'
        }
    }
}

/** 折线/多边形；[closed] 决定是否闭合填充。 */
fun outlineFromPoints(points: List<Double>, closed: Boolean): SkinOutline {
    require(points.size >= 4 && points.size % 2 == 0) { "points 至少需要两个点" }
    val b = SkinPathBuilder()
    b.moveTo(points[0], points[1])
    var i = 2
    while (i < points.size) {
        b.lineTo(points[i], points[i + 1])
        i += 2
    }
    if (closed) b.close()
    return b.build()
}

private const val KAPPA = 0.5522847498307936

fun outlineFromEllipse(cx: Double, cy: Double, rx: Double, ry: Double): SkinOutline {
    val b = SkinPathBuilder()
    b.moveTo(cx + rx, cy)
    b.cubicTo(cx + rx, cy + ry * KAPPA, cx + rx * KAPPA, cy + ry, cx, cy + ry)
    b.cubicTo(cx - rx * KAPPA, cy + ry, cx - rx, cy + ry * KAPPA, cx - rx, cy)
    b.cubicTo(cx - rx, cy - ry * KAPPA, cx - rx * KAPPA, cy - ry, cx, cy - ry)
    b.cubicTo(cx + rx * KAPPA, cy - ry, cx + rx, cy - ry * KAPPA, cx + rx, cy)
    b.close()
    return b.build()
}

fun outlineFromRect(x: Double, y: Double, w: Double, h: Double, radius: Double): SkinOutline {
    val r = radius.coerceIn(0.0, minOf(abs(w), abs(h)) / 2.0)
    val b = SkinPathBuilder()
    if (r <= 0.0) {
        b.moveTo(x, y)
        b.lineTo(x + w, y)
        b.lineTo(x + w, y + h)
        b.lineTo(x, y + h)
        b.close()
        return b.build()
    }
    val k = r * KAPPA
    b.moveTo(x + r, y)
    b.lineTo(x + w - r, y)
    b.cubicTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
    b.lineTo(x + w, y + h - r)
    b.cubicTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
    b.lineTo(x + r, y + h)
    b.cubicTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
    b.lineTo(x, y + r)
    b.cubicTo(x, y + r - k, x + r - k, y, x + r, y)
    b.close()
    return b.build()
}

/**
 * 旧的 64 射线径向轮廓 -> 三次曲线。
 *
 * 用的是 [closedPath] 同一条中心差分 Catmull-Rom：控制点是采样点的线性组合，
 * 所以「先插值半径再取曲线」和「先取曲线再插值控制点」结果完全一致，v1 皮肤的
 * 播放效果不因为换表示而改变。
 */
fun outlineFromRadii(radii: DoubleArray): SkinOutline {
    val n = radii.size
    require(n >= 3) { "径向轮廓至少需要 3 个采样" }
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    for (i in 0 until n) {
        val angle = i.toDouble() / n * 2.0 * PI
        xs[i] = radii[i] * cos(angle)
        ys[i] = radii[i] * sin(angle)
    }
    val tension = 1.0 / 6.0
    val pts = DoubleArray(2 + n * 6)
    pts[0] = xs[0]
    pts[1] = ys[0]
    for (i in 0 until n) {
        val p0 = (i - 1 + n) % n
        val p2 = (i + 1) % n
        val p3 = (i + 2) % n
        val base = 2 + i * 6
        pts[base] = xs[i] + (xs[p2] - xs[p0]) * tension
        pts[base + 1] = ys[i] + (ys[p2] - ys[p0]) * tension
        pts[base + 2] = xs[p2] - (xs[p3] - xs[i]) * tension
        pts[base + 3] = ys[p2] - (ys[p3] - ys[i]) * tension
        pts[base + 4] = xs[p2]
        pts[base + 5] = ys[p2]
    }
    return SkinOutline(listOf(SkinSubpath(pts, true)))
}
