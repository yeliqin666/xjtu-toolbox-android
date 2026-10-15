package com.xjtu.toolbox.agent.bot.cast

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 一笔的上色方式：镂空（挖掉之前画的，透出背景）或纯色。 */
enum class Tone { HOLE, COLOR }

/** 一笔：命令区间 [start, end) 加样式。对象复用，每帧覆盖。描边一律圆头圆角。 */
class SketchOp {
    var start = 0
    var end = 0
    var tone = Tone.COLOR
    var color = 0L
    var alpha = 1f
    /** 0 = 填充；> 0 = 描边线宽（已按当前变换缩放）。 */
    var stroke = 0f
}

/**
 * 一帧的绘制记录：路径命令加每笔样式。纯 Kotlin，App 里转成 Compose Path，单测里用 AWT 出图。
 *
 * 单位同经典屁岱：静息身体半径约 100，原点在画布中心，y 向下，可画范围半径约 122。
 * 每帧 [reset] 后重写，数组与笔对象都复用。镂空（[Tone.HOLE]）会挖掉它之前画的所有东西。
 */
class Sketch {
    var cmd = FloatArray(4096)
        private set
    var size = 0
        private set
    private val pool = ArrayList<SketchOp>()
    var count = 0
        private set

    fun op(i: Int): SketchOp = pool[i]

    private var pathStart = 0
    private var lastX = 0.0
    private var lastY = 0.0

    // 当前仿射：x' = a x + c y + e，y' = b x + d y + f
    private var a = 1.0
    private var b = 0.0
    private var c = 0.0
    private var d = 1.0
    private var e = 0.0
    private var f = 0.0
    private val stack = DoubleArray(6 * 32)
    private var depth = 0

    @PublishedApi internal val pts = DoubleArray(2 * 192)

    fun reset() {
        size = 0
        count = 0
        pathStart = 0
        a = 1.0; b = 0.0; c = 0.0; d = 1.0; e = 0.0; f = 0.0
        depth = 0
    }

    fun save() {
        val o = depth * 6
        stack[o] = a; stack[o + 1] = b; stack[o + 2] = c
        stack[o + 3] = d; stack[o + 4] = e; stack[o + 5] = f
        depth++
    }

    fun restore() {
        depth--
        val o = depth * 6
        a = stack[o]; b = stack[o + 1]; c = stack[o + 2]
        d = stack[o + 3]; e = stack[o + 4]; f = stack[o + 5]
    }

    inline fun group(block: Sketch.() -> Unit) {
        save()
        block()
        restore()
    }

    fun translate(x: Double, y: Double) {
        e += a * x + c * y
        f += b * x + d * y
    }

    /** 顺时针为正（y 向下）。 */
    fun rotate(deg: Double) {
        val r = deg * PI / 180.0
        val cs = cos(r)
        val sn = sin(r)
        val na = a * cs + c * sn
        val nb = b * cs + d * sn
        val nc = -a * sn + c * cs
        val nd = -b * sn + d * cs
        a = na; b = nb; c = nc; d = nd
    }

    fun scale(sx: Double, sy: Double = sx) {
        a *= sx; b *= sx
        c *= sy; d *= sy
    }

    private fun push(v: Float) {
        if (size == cmd.size) cmd = cmd.copyOf(size * 2)
        cmd[size++] = v
    }

    private fun pt(x: Double, y: Double) {
        push((a * x + c * y + e).toFloat())
        push((b * x + d * y + f).toFloat())
    }

    fun moveTo(x: Double, y: Double) {
        push(MOVE); pt(x, y)
        lastX = x; lastY = y
    }

    fun lineTo(x: Double, y: Double) {
        push(LINE); pt(x, y)
        lastX = x; lastY = y
    }

    fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        push(CUBIC); pt(x1, y1); pt(x2, y2); pt(x, y)
        lastX = x; lastY = y
    }

    fun quadTo(cx: Double, cy: Double, x: Double, y: Double) {
        cubicTo(
            lastX + (cx - lastX) * 2 / 3, lastY + (cy - lastY) * 2 / 3,
            x + (cx - x) * 2 / 3, y + (cy - y) * 2 / 3,
            x, y,
        )
    }

    fun close() = push(CLOSE)

    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) {
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        moveTo(cx + rx, cy)
        cubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
        cubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
        cubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
        cubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
        close()
    }

    fun circle(cx: Double, cy: Double, r: Double) = ellipse(cx, cy, r, r)

    /** 以 (cx, cy) 为中心的圆角矩形。 */
    fun rrect(cx: Double, cy: Double, w: Double, h: Double, r: Double) {
        val hw = w / 2
        val hh = h / 2
        val rr = min(r, min(hw, hh)).coerceAtLeast(0.0)
        val k = rr * KAPPA
        val l = cx - hw
        val t = cy - hh
        val rt = cx + hw
        val bt = cy + hh
        moveTo(l + rr, t)
        lineTo(rt - rr, t)
        cubicTo(rt - rr + k, t, rt, t + rr - k, rt, t + rr)
        lineTo(rt, bt - rr)
        cubicTo(rt, bt - rr + k, rt - rr + k, bt, rt - rr, bt)
        lineTo(l + rr, bt)
        cubicTo(l + rr - k, bt, l, bt - rr + k, l, bt - rr)
        lineTo(l, t + rr)
        cubicTo(l, t + rr - k, l + rr - k, t, l + rr, t)
        close()
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    /** 折线，[closed] 为真时闭合。 */
    fun poly(vararg xy: Double, closed: Boolean = true) {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
        if (closed) close()
    }

    /** 过 [pts] 里前 [n] 个点的 Catmull-Rom 平滑曲线。 */
    @PublishedApi internal fun smoothPts(n: Int, closed: Boolean) {
        if (n < 2) return
        moveTo(pts[0], pts[1])
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val i0 = if (closed) (i - 1 + n) % n else maxOf(i - 1, 0)
            val i2 = if (closed) (i + 1) % n else i + 1
            val i3 = if (closed) (i + 2) % n else minOf(i + 2, n - 1)
            val x1 = pts[2 * i]; val y1 = pts[2 * i + 1]
            val x2 = pts[2 * i2]; val y2 = pts[2 * i2 + 1]
            cubicTo(
                x1 + (x2 - pts[2 * i0]) / 6, y1 + (y2 - pts[2 * i0 + 1]) / 6,
                x2 - (pts[2 * i3] - x1) / 6, y2 - (pts[2 * i3 + 1] - y1) / 6,
                x2, y2,
            )
        }
        if (closed) close()
    }

    /** 径向轮廓：r(θ)，θ 从 x 正向顺时针（y 向下）。 */
    inline fun blob(cx: Double, cy: Double, n: Int = 48, r: (Double) -> Double) {
        for (i in 0 until n) {
            val th = i * TAU / n
            val rr = r(th)
            pts[2 * i] = cx + rr * cos(th)
            pts[2 * i + 1] = cy + rr * sin(th)
        }
        smoothPts(n, true)
    }

    /** 参数曲线：u 取 [0, 1)（闭合）或 [0, 1]（开放），平滑连接。 */
    inline fun trace(n: Int, closed: Boolean, fx: (Double) -> Double, fy: (Double) -> Double) {
        val den = if (closed) n.toDouble() else (n - 1).toDouble()
        for (i in 0 until n) {
            val u = i / den
            pts[2 * i] = fx(u)
            pts[2 * i + 1] = fy(u)
        }
        smoothPts(n, closed)
    }

    fun fill(tone: Tone, alpha: Double = 1.0, color: Long = 0L) = emit(0f, tone, alpha, color)

    /** 纯色填充。 */
    fun fill(color: Long, alpha: Double = 1.0) = emit(0f, Tone.COLOR, alpha, color)

    fun stroke(width: Double, tone: Tone, alpha: Double = 1.0, color: Long = 0L) =
        emit(lineWidth(width), tone, alpha, color)

    /** 纯色描边。 */
    fun stroke(width: Double, color: Long, alpha: Double = 1.0) =
        emit(lineWidth(width), Tone.COLOR, alpha, color)

    private fun lineWidth(width: Double) = (width * sqrt(abs(a * d - b * c))).toFloat()

    private fun emit(stroke: Float, tone: Tone, alpha: Double, color: Long) {
        if (size == pathStart || alpha <= 0.004) {
            pathStart = size
            return
        }
        val op = if (count < pool.size) pool[count] else SketchOp().also(pool::add)
        count++
        op.start = pathStart
        op.end = size
        op.tone = tone
        op.color = color
        op.alpha = alpha.coerceAtMost(1.0).toFloat()
        op.stroke = stroke
        pathStart = size
    }

    /** 实心圆点，一笔成型。 */
    fun dot(x: Double, y: Double, r: Double, tone: Tone = Tone.COLOR, alpha: Double = 1.0, color: Long = 0L) {
        circle(x, y, r)
        fill(tone, alpha, color)
    }

    companion object {
        const val MOVE = 0f
        const val LINE = 1f
        const val CUBIC = 2f
        const val CLOSE = 3f
        const val KAPPA = 0.5522847498307936
        const val TAU = PI * 2
    }
}
