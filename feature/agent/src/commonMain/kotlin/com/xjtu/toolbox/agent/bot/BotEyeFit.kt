package com.xjtu.toolbox.agent.bot

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 把脸贴到自定义形状上。移植自 bloub 的 `eyefit.ts`。
 *
 * 眼睛活在半径 1 的球面上，[radiusAtAngle] 按局部半径把它们回贴到真实轮廓。这个
 * 比例只放对了眼睛的**中心**，眼睛本身是有大小的：留它面前的余量被同一比例缩放，
 * 于是一个方向的轮廓一窄，眼洞就被推到边上、被身体裁出一道缺口。胶囊、三角、
 * 云朵、水滴上都会出现这个「缺口」。
 *
 * 本模块在加载时**一次**解出修正量，做成表。这是整个修正的关键，比后面的几何更
 * 重要：放进渲染循环，它就会跟着每帧都变的东西（视线漂移、表情 morph、最近边切换、
 * 最紧的眼切换）一起变，七版实现全都漏出可见的运动伪影——持续抖动、参考边翻转时
 * 26 单位的跳变。做成表后，它只在换形状时按 morph 曲线在两格之间插值，抖动在构造上
 * 就不可能发生。附带好处：求解器不再受连续性约束，可以多探几个方向覆盖视线漂移的
 * 最坏情况。
 *
 * 表在类加载时从纯数据构建，与 `BotFace` 里的眨眼时刻表同性质：确定、无状态，
 * 因此不影响 `BotEngine.sample(t)` 的纯函数性质。
 */

/** 求解器的参考半径。返回的偏移量以这个半径为单位。 */
private const val R = 100.0

/**
 * 静息生命感的振幅上限，读自 `liveliness`：`loopNoise` 的绝对值被限制在 1，
 * 所以这两个和是**精确上界**而非估计。必须覆盖它们，否则修正只对名义姿态成立、
 * 一秒后就错：7° 的偏航在半径 100 的球上会把眼睛挪动十来个单位。
 */
private const val DERIVE_YAW = 5.5 + 1.6
private const val DERIVE_PITCH = 4.2 + 1.3
/** 中心漂移，以球半径为单位。 */
private const val DERIVE_X = 0.006
private const val DERIVE_Y = 0.007

/** 一张脸：求解器只需要知道怎么摆两颗眼。 */
private class Visage(
    val gaze: HeadGaze,
    val split: Double,
    val eyes: List<EyeCfg>,
)

/**
 * 一颗待测的胶囊：其轴线段，以及在给定方向上要退让多少。
 *
 * 胶囊 = 一个线段加粗一个半径 r 的圆盘。经切向矩阵映射后，它变成一个线段加粗一个
 * **椭圆**，退让量随方向变——正是这个椭圆的支撑函数 `r * |A^T u|`。取最大奇异值
 * 会保守但错在唯一要紧的方向上，代价很大：圆上的基准余量会算成负数，修正随即失效。
 */
private class Empreinte(
    /** 中心，viewBox 单位 */
    val x: Double,
    val y: Double,
    /** 半轴向量 */
    val ax: Double,
    val ay: Double,
    /** 变换前的局部圆盘半径 */
    val r: Double,
    /** 切向矩阵的两列，用于求支撑函数 */
    val m0: Double, val m1: Double, val m2: Double, val m3: Double,
)

/** 一次校验：要放进一个轮廓里的胶囊，以及基准轮廓。 */
private class Epreuve(
    val empreintes: List<Empreinte>,
    val reference: List<Empreinte>,
    val contour: Array<Point>,
    val calContour: Array<Point>,
)

/** 静息时的中心漂移，viewBox 单位。直接加到胶囊半径上。 */
private val FLOTTEMENT: Double = hypot(DERIVE_X, DERIVE_Y) * R

/** 胶囊的印记：某个脸摆在某份轮廓上时，两颗眼的轴与支撑数据。 */
private fun empreintes(visage: Visage, sil: Silhouette, radii: DoubleArray): List<Empreinte> {
    val out = ArrayList<Empreinte>(2)
    val poses = eyePoses(visage.gaze, R, visage.split)
    for (i in 0..1) {
        val e = poses[i]
        if (e.depth <= 0.02) continue
        val cfg = visage.eyes[i]
        val phi = cfg.tilt.deg2rad()
        val cp = kotlin.math.cos(phi)
        val sp = kotlin.math.sin(phi)
        // 切向标架再复合一个眼平面内旋转，列 (m0,m1) 与 (m2,m3)
        val m0 = e.a * cp + e.c * sp
        val m1 = e.b * cp + e.d * sp
        val m2 = -e.a * sp + e.c * cp
        val m3 = -e.b * sp + e.d * cp

        val hw = max(cfg.w * R, 0.01) / 2.0
        val hh = max(cfg.h * R, 0.01) / 2.0
        val r = min(hw, hh)
        // 轴取较长的那一维
        val long = hh > hw
        val demi = if (long) hh - r else hw - r
        val fit = radiusAtAngle(radii, atan2(e.y, e.x) - sil.rot)
        out.add(
            Empreinte(
                x = e.x * fit,
                y = e.y * fit,
                ax = (if (long) m2 else m0) * demi,
                ay = (if (long) m3 else m1) * demi,
                r = r,
                m0 = m0, m1 = m1, m2 = m2, m3 = m3,
            )
        )
    }
    return out
}

/** 轮廓到线段的最短距离，以及由轮廓指向线段的向量（即脱困方向）。 */
private class Approche(val d: Double, val ux: Double, val uy: Double)

/** 同一次扫描同时给出距离与脱困方向：分开算会让这里唯一的真开销翻倍。 */
private fun approche(pts: Array<Point>, x0: Double, y0: Double, x1: Double, y1: Double): Approche {
    val sx = x1 - x0
    val sy = y1 - y0
    val len2 = sx * sx + sy * sy
    var best = Double.MAX_VALUE
    var vx = 0.0
    var vy = 0.0
    for (p in pts) {
        var t = if (len2 > 0.0) ((p.x - x0) * sx + (p.y - y0) * sy) / len2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val ex = x0 + t * sx - p.x
        val ey = y0 + t * sy - p.y
        val d2 = ex * ex + ey * ey
        if (d2 < best) {
            best = d2
            vx = ex
            vy = ey
        }
    }
    val d = kotlin.math.sqrt(best)
    return Approche(d, if (d > 1e-9) vx / d else 0.0, if (d > 1e-9) vy / d else 0.0)
}

/** 最紧的一颗胶囊的余量，与让它脱困的方向。 */
private class Pire(val marge: Double, val ux: Double, val uy: Double)

private fun pire(pts: Array<Point>, emps: List<Empreinte>, tx: Double, ty: Double): Pire {
    var marge = Double.MAX_VALUE
    var ux = 0.0
    var uy = 0.0
    for (e in emps) {
        val x = e.x + tx
        val y = e.y + ty
        val a = approche(pts, x - e.ax, y - e.ay, x + e.ax, y + e.ay)
        // 椭圆的支撑函数在脱困方向上的取值
        val rayon = e.r * hypot(e.m0 * a.ux + e.m1 * a.uy, e.m2 * a.ux + e.m3 * a.uy) + FLOTTEMENT
        if (a.d - rayon < marge) {
            marge = a.d - rayon
            ux = a.ux
            uy = a.uy
        }
    }
    return Pire(marge, ux, uy)
}

/** 探的方向数与二分步数，二者之积就是表的构建开销。 */
private const val DIRECTIONS = 12
private const val DICHOTOMIE = 8

/**
 * 让两颗眼在这个形状上「放得下」的最小平移。
 *
 * 两眼的**公共平移**，因此是一个等距变换：眼距、大小、倾角都逐像素保留，只是把
 * 整张脸在「上方没地方」的身体上压低一点——这正是手工会做的动作。分别限制每只眼
 * 会把一对眼越推越开，而缩放整张脸会明显把眼睛缩小。
 *
 * 目标是**原始轮廓**的松紧度，而不是严格的零余量：圆上外侧眼本就贴着边（半径 100
 * 时约 17.3 单位），那是有意为之，正是体积感的来源。目标还被形状中心的余量封顶，
 * 否则在扁形身体上无解。
 *
 * 用**方向搜索**而非梯度下降：要求的是范数最小的可行平移，所以沿一圈方向各二分一次。
 * 梯度下降写过，不收敛——把一对眼从一个边挪开会靠近另一个边，于是来回试探。
 */
private fun resous(epreuves: List<Epreuve>): Pair<Double, Double> {
    if (epreuves.isEmpty()) return 0.0 to 0.0

    /** 给定平移下，所有校验里最紧的余量。 */
    fun marge(tx: Double, ty: Double): Double {
        var m = Double.MAX_VALUE
        for (ep in epreuves) m = min(m, pire(ep.contour, ep.empreintes, tx, ty).marge)
        return m
    }

    // 要求的余量：原始轮廓在所有校验里能容忍的最紧值
    var requis = Double.MAX_VALUE
    for (ep in epreuves) {
        requis = min(requis, pire(ep.calContour, ep.reference, 0.0, 0.0).marge)
    }
    // 行程必须能到达身体中心：宽脸在三角上只有靠近中部才放得下
    var mx = 0.0
    var my = 0.0
    val emps = epreuves[0].empreintes
    for (e in emps) {
        mx -= e.x / emps.size
        my -= e.y / emps.size
    }
    val course = max(0.35 * R, hypot(mx, my) * 1.25)

    // 封顶：形状中心总是可达的
    requis = min(requis, marge(mx, my))

    // 已经放得下：圆，以及任何足够宽的形状
    val depart = marge(0.0, 0.0)
    if (depart >= requis && depart >= 0.0) return 0.0 to 0.0
    val cible = max(requis, 0.0)

    var meilleurX = 0.0
    var meilleurY = 0.0
    var meilleureNorme = Double.MAX_VALUE
    // 无解时的退路：探到的最松位置
    var secoursX = 0.0
    var secoursY = 0.0
    var secours = depart

    for (d in 0 until DIRECTIONS) {
        val a = d.toDouble() / DIRECTIONS * PI * 2.0
        val ux = kotlin.math.cos(a)
        val uy = kotlin.math.sin(a)
        if (marge(ux * course, uy * course) < cible) {
            // 这个方向走不通；仍记下途中最好的余量
            for (k in doubleArrayOf(0.3, 0.6, 1.0)) {
                val m = marge(ux * course * k, uy * course * k)
                if (m > secours) {
                    secours = m
                    secoursX = ux * course * k
                    secoursY = uy * course * k
                }
            }
            continue
        }
        // 该方向上可行的最短距离
        var bas = 0.0
        var haut = course
        repeat(DICHOTOMIE) {
            val mid = (bas + haut) / 2.0
            if (marge(ux * mid, uy * mid) >= cible) haut = mid else bas = mid
        }
        if (haut < meilleureNorme) {
            meilleureNorme = haut
            meilleurX = ux * haut
            meilleurY = uy * haut
        }
    }

    val x = if (meilleureNorme == Double.MAX_VALUE) secoursX else meilleurX
    val y = if (meilleureNorme == Double.MAX_VALUE) secoursY else meilleurY
    // 以**球半径**为单位返回，引擎再乘回自己的尺度
    return round6(x / R) to round6(y / R)
}

private fun round6(v: Double): Double = kotlin.math.round(v * 1_000_000.0) / 1_000_000.0

/**
 * 状态实际佩戴的脸：状态接受静息表情时用 [REST_EXPRESSION]，否则用它自带的脸。
 *
 * 必须与 `BotEngine.posed()` 的口径一致——那里 baseFace 状态会把眼睛整体换成
 * 静息表情，求解器若按状态自带的脸来算，修正就对着一个屏幕上并不存在的脸。
 */
private fun visageDe(def: StateDef, pose: Pose): Visage =
    if (def.baseFace) Visage(REST_EXPRESSION.gaze, REST_EXPRESSION.split, REST_EXPRESSION.eyes)
    else Visage(pose.gaze, pose.split, pose.eyes)

/** 状态里要采样的时刻：姿态不动就一个足够。 */
private fun dates(def: StateDef): List<Double> {
    fun signature(p: Pose): String = listOf(
        p.gaze.yaw, p.gaze.pitch, p.gaze.roll, p.split,
        p.eyes[0].w, p.eyes[0].h, p.eyes[0].open, p.eyes[0].tilt,
        p.eyes[1].w, p.eyes[1].h, p.eyes[1].open, p.eyes[1].tilt,
        p.sil.rot, p.sil.cx, p.sil.cy, p.sil.sx, p.sil.sy,
    ).joinToString(",")
    if (signature(def.pose(0.0)) == signature(def.pose(def.duration))) return listOf(0.0)
    val n = 3
    return List(n) { i -> i.toDouble() / (n - 1) * def.duration }
}

/** 某个形状、某个状态下的全部校验。 */
private fun epreuvesPour(def: StateDef, radii: DoubleArray): List<Epreuve> {
    val epreuves = ArrayList<Epreuve>()
    for (t in dates(def)) {
        val pose = def.pose(t)
        val shaped = Silhouette(
            radii,
            rot = pose.sil.rot, cx = pose.sil.cx, cy = pose.sil.cy,
            sx = pose.sil.sx, sy = pose.sil.sy,
        )
        val contour = toPoints(shaped, R, Array(PROFILE_SAMPLES) { Point(0.0, 0.0) })
        val calContour = toPoints(pose.sil, R, Array(PROFILE_SAMPLES) { Point(0.0, 0.0) })
        val v = visageDe(def, pose)
        // 漂移的四个角界定名义姿态（名义姿态是其中心），再单独测它不会改变任何余量
        for (dy in doubleArrayOf(-DERIVE_YAW, DERIVE_YAW)) {
            for (dp in doubleArrayOf(-DERIVE_PITCH, DERIVE_PITCH)) {
                val c = Visage(
                    HeadGaze(v.gaze.yaw + dy, v.gaze.pitch + dp, v.gaze.roll),
                    v.split, v.eyes,
                )
                epreuves.add(
                    Epreuve(
                        empreintes = empreintes(c, pose.sil, radii),
                        reference = empreintes(c, pose.sil, pose.sil.radii),
                        contour = contour,
                        calContour = calContour,
                    )
                )
            }
        }
    }
    return epreuves
}

/** 某个形状、某个状态下，两眼要加的偏移。 */
private fun decalagePour(def: StateDef, radii: DoubleArray): Pair<Double, Double> =
    resous(epreuvesPour(def, radii))

/**
 * 不做任何修正时，最紧的一颗眼离轮廓还有多少余量（viewBox 单位，球半径 100）。
 *
 * 负值 = 眼洞会戳出身体。供测试验收「眼睛不溢出」这个真正的不变量——修正量为零本身
 * 不是目标，眼睛放得下才是。当前表情（[REST_EXPRESSION]）的眼高较小，对目录里的每种
 * 形状、每个静息状态这个值都是正的，所以修正表全为零是**正确结果**，不是求解器失灵。
 */
internal fun eyeFitMargin(radii: DoubleArray, stateId: String): Double? {
    val def = BOT_STATES[stateId] ?: return null
    if (!def.baseBody) return null
    val epreuves = epreuvesPour(def, radii)
    if (epreuves.isEmpty()) return null
    var m = Double.MAX_VALUE
    for (ep in epreuves) m = min(m, pire(ep.contour, ep.empreintes, 0.0, 0.0).marge)
    return m
}

private val NUL = 0.0 to 0.0

/**
 * 偏移表：一份（形状，状态）。只对 `baseBody` 状态建——其余状态的轮廓就是动画本身，
 * 形状选择根本轮不到它们。按数组**引用**为键，这与引擎的约定一致（它也按引用比较
 * 形状，避免每帧重算）。
 */
private val DECALAGES: Map<DoubleArray, Map<String, Pair<Double, Double>>> =
    BOT_SHAPES.associate { forme ->
        val par = BOT_STATES.values.filter { it.baseBody }.associate { def ->
            def.id to decalagePour(def, forme.radii)
        }
        forme.radii to par
    }

/**
 * 这个形状在这个状态下要给两眼加的偏移，以球半径为单位。
 *
 * 形状不在目录里（含 null 与圆）时返回零：圆上两份轮廓相同，余量本就是要求的那个值。
 */
fun eyeOffsetFor(radii: DoubleArray?, state: String): Pair<Double, Double> {
    if (radii == null) return NUL
    val par = DECALAGES[radii] ?: return NUL
    return par[state] ?: NUL
}

private val PI = kotlin.math.PI
