package com.xjtu.toolbox.agent.bot

import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 一条轮廓 = 径向轮廓 r(theta) 加一个姿态。
 *
 * 所有形状都按同样数量的角度采样：任意两个形状的点一一对应，形变于是退化为
 * 半径的线性插值——这正是 bloub 不需要 path 形变库的原因。移植自 src/bot/shape.ts，
 * 只保留本项目用到的部分（圆 + 胶囊 + 通用轮廓工具）。
 */

const val PROFILE_SAMPLES = 64

/** 屏幕系：x 向右，y 向下；theta = 0 指向右，随 y 向下顺时针增长。 */
class Silhouette(
    val radii: DoubleArray,
    var rot: Double = 0.0,
    var cx: Double = 0.0,
    var cy: Double = 0.0,
    var sx: Double = 1.0,
    var sy: Double = 1.0,
)

private val ANGLES = DoubleArray(PROFILE_SAMPLES) { it.toDouble() / PROFILE_SAMPLES * TAU }
private val COS = DoubleArray(PROFILE_SAMPLES) { kotlin.math.cos(ANGLES[it]) }
private val SIN = DoubleArray(PROFILE_SAMPLES) { kotlin.math.sin(ANGLES[it]) }

/** 完美圆：中性基座（点、泡、淡出目标）。 */
fun circle(radius: Double, rot: Double = 0.0, cx: Double = 0.0, cy: Double = 0.0, sx: Double = 1.0, sy: Double = 1.0): Silhouette =
    Silhouette(DoubleArray(PROFILE_SAMPLES) { radius }, rot, cx, cy, sx, sy)

class Point(var x: Double, var y: Double)

/** 插值两条轮廓。[out] 被复用以避免每帧分配。 */
fun blendSilhouette(a: Silhouette, b: Silhouette, t: Double, out: Silhouette): Silhouette {
    for (i in 0 until PROFILE_SAMPLES) {
        out.radii[i] = lerp(a.radii[i], b.radii[i], t)
    }
    // 旋转走最短路径：避免 +170° -> -170° 时多转一圈。
    var dRot = b.rot - a.rot
    while (dRot > PI) dRot -= TAU
    while (dRot < -PI) dRot += TAU
    out.rot = a.rot + dRot * t
    out.cx = lerp(a.cx, b.cx, t)
    out.cy = lerp(a.cy, b.cy, t)
    out.sx = lerp(a.sx, b.sx, t)
    out.sy = lerp(a.sy, b.sy, t)
    return out
}

/** 把轮廓投影成屏幕点。[scale] 是静息球半径在 viewBox 中的单位数。 */
fun toPoints(s: Silhouette, scale: Double, out: Array<Point>): Array<Point> {
    val cr = kotlin.math.cos(s.rot)
    val sr = kotlin.math.sin(s.rot)
    for (i in 0 until PROFILE_SAMPLES) {
        val r = s.radii[i]
        val x = r * COS[i]
        val y = r * SIN[i]
        // 先旋转，再屏幕系 squash，最后平移
        val rx = x * cr - y * sr
        val ry = x * sr + y * cr
        val p = out[i]
        p.x = (rx * s.sx + s.cx) * scale
        p.y = (ry * s.sy + s.cy) * scale
    }
    return out
}

private val PI = kotlin.math.PI

/**
 * 闭合折线 -> Catmull-Rom 三次曲线。
 *
 * 64 个点时中心差分切线已经足够：轮廓平滑到像素级，生成的路径也短。
 */
fun closedPath(pts: Array<Point>): Path {
    val n = pts.size
    if (n < 3) return Path()
    val path = Path()
    path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
    val tension = 1.0 / 6.0
    for (i in 0 until n) {
        val p0 = pts[(i - 1 + n) % n]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % n]
        val p3 = pts[(i + 2) % n]
        val c1x = p1.x + (p2.x - p0.x) * tension
        val c1y = p1.y + (p2.y - p0.y) * tension
        val c2x = p2.x - (p3.x - p1.x) * tension
        val c2y = p2.y - (p3.y - p1.y) * tension
        path.cubicTo(
            c1x.toFloat(), c1y.toFloat(),
            c2x.toFloat(), c2y.toFloat(),
            p2.x.toFloat(), p2.y.toFloat(),
        )
    }
    path.close()
    return path
}

/**
 * 轮廓在任意方向上的半径，由相邻两个采样点插值。
 *
 * 用于把「贴在身体上」的东西（眼睛、通知点）在轮廓不再是圆时重新贴回真实边缘。
 */
fun radiusAtAngle(radii: DoubleArray, angle: Double): Double {
    val n = radii.size
    val t = (((angle / TAU) % 1.0) + 1.0) % 1.0 * n
    val i = kotlin.math.floor(t).toInt()
    return lerp(radii[i % n], radii[(i + 1) % n], t - i)
}

/**
 * 胶囊（stadium）的眼睛路径，构造时直接套用仿射变换。
 *
 * 刻意不用 Path.addRoundRect + Path.transform：本项目的 Compose 版本上，
 * addRoundRect 生成垃圾坐标、transform 是 no-op（均已在设备上实测），
 * 而 moveTo/cubicTo/lineTo 工作正常（身体轮廓就用它们画的）。
 *
 * [m00] [m01] [m02] 是仿射矩阵的行优先前两行：x' = m00*x + m01*y + m02。
 */
fun transformedCapsulePath(
    hw: Double,
    hh: Double,
    m00: Double, m01: Double, m02: Double,
    m10: Double, m11: Double, m12: Double,
): Path {
    val hwf = maxOf(hw, 0.01)
    val hhf = maxOf(hh, 0.01)
    val r = minOf(hwf, hhf)
    val kappa = 0.5522847498307936 * r
    // 局部坐标的 8 个锚点：A(-hw+r,-hh) B(hw-r,-hh) C(hw,-hh+r) D(hw,hh-r)
    //                    E(hw-r,hh) F(-hw+r,hh) G(-hw,hh-r) H(-hw,-hh+r)
    val ax0 = -hwf + r; val ay0 = -hhf
    val bx0 = hwf - r;  val by0 = -hhf
    val cx0 = hwf;      val cy0 = -hhf + r
    val dx0 = hwf;      val dy0 = hhf - r
    val ex0 = hwf - r;  val ey0 = hhf
    val fx0 = -hwf + r; val fy0 = hhf
    val gx0 = -hwf;     val gy0 = hhf - r
    val hx0 = -hwf;     val hy0 = -hhf + r

    fun px(x: Double, y: Double): Float = (m00 * x + m01 * y + m02).toFloat()
    fun py(x: Double, y: Double): Float = (m10 * x + m11 * y + m12).toFloat()

    val path = Path()
    path.moveTo(px(ax0, ay0), py(ax0, ay0))
    // 顶边 A -> B
    path.lineTo(px(bx0, by0), py(bx0, by0))
    // 右上角 B -> C：切向 B 沿 +x，C 沿 +y
    path.cubicTo(
        px(bx0 + kappa, by0), py(bx0 + kappa, by0),
        px(cx0, cy0 - kappa), py(cx0, cy0 - kappa),
        px(cx0, cy0), py(cx0, cy0),
    )
    // 右边 C -> D
    path.lineTo(px(dx0, dy0), py(dx0, dy0))
    // 右下角 D -> E：切向 D 沿 +y，E 沿 -x
    path.cubicTo(
        px(dx0, dy0 + kappa), py(dx0, dy0 + kappa),
        px(ex0 + kappa, ey0), py(ex0 + kappa, ey0),
        px(ex0, ey0), py(ex0, ey0),
    )
    // 底边 E -> F
    path.lineTo(px(fx0, fy0), py(fx0, fy0))
    // 左下角 F -> G：切向 F 沿 -x，G 沿 -y
    path.cubicTo(
        px(fx0 - kappa, fy0), py(fx0 - kappa, fy0),
        px(gx0, gy0 + kappa), py(gx0, gy0 + kappa),
        px(gx0, gy0), py(gx0, gy0),
    )
    // 左边 G -> H
    path.lineTo(px(hx0, hy0), py(hx0, hy0))
    // 左上角 H -> A：切向 H 沿 -y，A 沿 +x
    path.cubicTo(
        px(hx0, hy0 - kappa), py(hx0, hy0 - kappa),
        px(ax0 - kappa, ay0), py(ax0 - kappa, ay0),
        px(ax0, ay0), py(ax0, ay0),
    )
    path.close()
    return path
}

/* ------------------------------------------------- 解析式形状（供形象选择） */

/**
 * 用户可选形状的几何底座，移植自 bloub 的 `shape.ts`。
 *
 * 与上方的动画轮廓不同，这些形状**不是**从参考视频实测的：它们是按原定制器
 * 网格解析构造出来的用户选择（见 BotSkins）。
 */

/** 一个圆盘，供「圆的并集」「两圆凸包」两个构造器描述几何。 */
class Disc(val x: Double, val y: Double, val r: Double)

/**
 * 把最大半径归一到 [max]，让所有形状在眼里「一样大」。
 *
 * 必须归一：不同构造器给出的半径尺度不同，直接并排会让三角明显比圆大。
 */
fun normalizeRadii(radii: DoubleArray, max: Double = 1.0): DoubleArray {
    val peak = radii.maxOrNull() ?: return radii
    if (peak <= 0.0) return radii
    val k = max / peak
    return DoubleArray(radii.size) { radii[it] * k }
}

/** 超椭圆 |x/sx|^n + |y/sy|^n = 1；n = 2 是椭圆，n ≈ 4 是方圆（squircle）。 */
fun superellipseProfile(n: Double, sx: Double = 1.0, sy: Double = 1.0): DoubleArray =
    DoubleArray(PROFILE_SAMPLES) { i ->
        val c = abs(COS[i] / sx).pow(n)
        val s = abs(SIN[i] / sy).pow(n)
        (c + s).pow(-1.0 / n)
    }

/**
 * 圆的并集的径向轮廓：r(theta) = 该方向上射线与各圆交点里最远的一个。
 *
 * 只要原点在并集内就是精确的——这正是「云朵」能长出几个鼓包而不需要路径布尔的原因。
 */
fun unionOfCirclesProfile(circles: List<Disc>): DoubleArray =
    DoubleArray(PROFILE_SAMPLES) { i ->
        val dx = COS[i]
        val dy = SIN[i]
        var best = 0.0
        for (c in circles) {
            val b = dx * c.x + dy * c.y
            val disc = b * b - (c.x * c.x + c.y * c.y - c.r * c.r)
            if (disc < 0.0) continue
            val t = b + kotlin.math.sqrt(disc)
            if (t > best) best = t
        }
        best
    }

/**
 * 任意多边形 -> 径向轮廓，从 [cx], [cy] 处射线求交。
 *
 * 用于表达不成 r(theta) 的图形；只在加载时算一次，绝不进渲染循环。
 */
fun profileFromPolygon(poly: List<Point>, cx: Double, cy: Double): DoubleArray {
    val radii = DoubleArray(PROFILE_SAMPLES)
    val n = poly.size
    for (k in 0 until PROFILE_SAMPLES) {
        val dx = COS[k]
        val dy = SIN[k]
        var best = 0.0
        for (i in 0 until n) {
            val a = poly[i]
            val b = poly[(i + 1) % n]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-9) continue
            val px = a.x - cx
            val py = a.y - cy
            val t = (px * ey - py * ex) / den // 沿射线的距离
            val u = (px * dy - py * dx) / den // 落在线段上的位置
            if (t > best && u >= 0.0 && u <= 1.0) best = t
        }
        radii[k] = best
    }
    return radii
}

/** 两圆的外公切线凸包：描述「胶囊」，也是竖排 "!" 的锥形杆。 */
fun hullOfCircles(
    x1: Double, y1: Double, r1: Double,
    x2: Double, y2: Double, r2: Double,
    steps: Int = 96,
): List<Point> {
    val dx = x2 - x1
    val dy = y2 - y1
    val dist = hypot(dx, dy).coerceAtLeast(1e-6)
    val base = atan2(dy, dx)
    val spread = acos(((r1 - r2) / dist).coerceIn(-1.0, 1.0))
    val pts = ArrayList<Point>(steps + 2)
    // 大圆的弧
    for (i in 0..steps / 2) {
        val a = base + spread + (TAU - 2 * spread) * i / (steps / 2)
        pts.add(Point(x1 + kotlin.math.cos(a) * r1, y1 + kotlin.math.sin(a) * r1))
    }
    // 小圆的弧
    for (i in 0..steps / 2) {
        val a = base - spread + (2 * spread) * i / (steps / 2)
        pts.add(Point(x2 + kotlin.math.cos(a) * r2, y2 + kotlin.math.sin(a) * r2))
    }
    return pts
}

/**
 * 圆角多边形，通过「与圆盘的 Minkowski 和」构造：每条边外推 [rc]，
 * 每个顶点变成一个半径 [rc] 的圆弧。故顶点要放在目标半径**减去** rc 处。
 * 期望顺时针多边形（屏幕系，y 向下）。
 */
private fun roundedPolygon(verts: List<Point>, rc: Double, arcSteps: Int = 10): List<Point> {
    val n = verts.size
    val out = ArrayList<Point>(n * (arcSteps + 1))
    fun normal(a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).coerceAtLeast(1e-6)
        // 顺时针 + y 向下：外法线是 (dy, -dx)
        return atan2(-dx / len, dy / len)
    }
    for (i in 0 until n) {
        val prev = verts[(i - 1 + n) % n]
        val cur = verts[i]
        val next = verts[(i + 1) % n]
        val a0 = normal(prev, cur)
        val a1 = normal(cur, next)
        var d = a1 - a0
        while (d > PI) d -= TAU
        while (d < -PI) d += TAU
        for (k in 0..arcSteps) {
            val a = a0 + d * k / arcSteps
            out.add(Point(cur.x + kotlin.math.cos(a) * rc, cur.y + kotlin.math.sin(a) * rc))
        }
    }
    return out
}

/** 圆角正多边形，内接于 [radius]。 */
fun regularPolygonProfile(
    sides: Int,
    radius: Double,
    rc: Double,
    rotationDeg: Double = 0.0,
): DoubleArray {
    val rot = rotationDeg.deg2rad()
    val verts = List(sides) { i ->
        val a = rot + i.toDouble() / sides * TAU
        Point(kotlin.math.cos(a) * (radius - rc), kotlin.math.sin(a) * (radius - rc))
    }
    return profileFromPolygon(roundedPolygon(verts, rc), 0.0, 0.0)
}
