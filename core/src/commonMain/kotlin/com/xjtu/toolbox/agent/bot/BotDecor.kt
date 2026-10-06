package com.xjtu.toolbox.agent.bot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 装饰元素：burst 的粒子、notify 的通知点、comet 的轨道彩带。
 * 移植自 src/bot/decor.ts 的子集（轨道环、思考点属于未启用的状态，不移植）。
 */

/** 状态声明的点：坐标为球半径单位。depth 为 -1 表示「未定义」（渲染用身体色）。 */
class DotSpec(
    val x: Double,
    val y: Double,
    val r: Double,
    val opacity: Double,
    val depth: Double = -1.0,
)

/** 通知点：贴在轮廓上，notch 是身体为它让出的凹槽半径。 */
class NotifSpec(
    val x: Double,
    val y: Double,
    val r: Double,
    val notch: Double,
)

/** 通知点蓝，逐像素实测。 */
const val NOTIF_BLUE: Long = 0xFF2496E8

/** 通知点贴在圆周上，-42° 处。 */
const val NOTIF_ANGLE = -42.0
const val NOTIF_DIST = 1.003
/** 静息半径；pop 峰值高出 14%。 */
const val NOTIF_R = 0.15
const val NOTIF_POP = 1.14
/** 凹槽边距恒定 0.054R，随身体缩放。 */
const val NOTIF_MARGIN = 0.054

/* ------------------------------------------------------- 轨道弧线（彗星） */

/**
 * 倾斜 3D 椭圆弧的声明：几何留在球半径单位，由引擎（唯一知道 viewBox 的人）
 * 光栅化。字段含义见 [arcRender]。
 */
class ArcSeed(
    /** 半长轴，球半径单位 */
    val a: Double,
    /** 扁率 b/a：实测 <= 0.45，轨道平面都是侧视的 */
    val k: Double,
    /** 长轴在屏幕上的倾角，弧度 */
    val tilt: Double,
    /** 圈/秒 */
    val speed: Double,
    val phase: Double,
    /** 实际画出的圆周比例 */
    val sweep: Double,
    val hue: Double,
    val hueSpan: Double,
    val width: Double,
    val cx: Double,
    val cy: Double,
)

/** 一条弧的渲染结果：前后两段折线（z 分量分割，前段遮住后段）加沿轨迹的渐变。 */
class ArcRender(
    val front: Path,
    val back: Path,
    /** 线宽，引擎单位 */
    val width: Double,
    val opacity: Double,
    /** 线性渐变的起止点，引擎单位 */
    val gradX1: Double, val gradY1: Double,
    val gradX2: Double, val gradY2: Double,
    /** 渐变三站：hue / hue+span/2 / hue+span */
    val colors: List<Color>,
)

/** 彩环不是平色：恒定亮度的全色相轮，沿轨迹渐变。实测 S 45-62%，L 50-67%。 */
fun wheel(hue: Double, s: Double = 0.55, l: Double = 0.62): Color {
    val h = ((hue % 360) + 360) % 360
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs(((h / 60.0) % 2) - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    return Color((r + m).toFloat(), (g + m).toFloat(), (b + m).toFloat())
}

/**
 * 把倾斜 3D 圆投影成正交视图。
 *
 * 圆活在 u（在屏幕内）与 v（扎进深度）张成的平面里。z 分量用来把轨迹切成两半：
 * 后半段先画、被身体遮住。正是这个真实的深度排序让环读起来像轨道而不是平面画。
 */
fun arcRender(seed: ArcSeed, t: Double, scale: Double, opacity: Double): ArcRender {
    val spin = seed.phase + t * seed.speed * TAU
    val cu = cos(seed.tilt)
    val su = sin(seed.tilt)
    val kz = sqrt(maxOf(0.0, 1.0 - seed.k * seed.k))

    val N = 64
    val span = seed.sweep * TAU
    val front = Path()
    val back = Path()
    var prevBehind: Boolean? = null

    for (i in 0..N) {
        val th = spin + (i.toDouble() / N) * span
        val ct = cos(th)
        val st = sin(th)
        // u = (cos tilt, sin tilt, 0)；v = (-sin tilt * k, cos tilt * k, kz)
        val x = seed.a * (ct * cu + st * -su * seed.k) + seed.cx
        val y = seed.a * (ct * su + st * cu * seed.k) + seed.cy
        val z = seed.a * st * kz

        val behind = z < 0
        val p = if (behind) back else front
        val sx = (x * scale).toFloat()
        val sy = (y * scale).toFloat()
        if (behind != prevBehind) p.moveTo(sx, sy) else p.lineTo(sx, sy)
        prevBehind = behind
    }

    val gx = cos(seed.tilt) * seed.a * scale
    val gy = sin(seed.tilt) * seed.a * scale
    return ArcRender(
        front = front,
        back = back,
        width = seed.width * scale,
        opacity = opacity,
        gradX1 = seed.cx * scale - gx,
        gradY1 = seed.cy * scale - gy,
        gradX2 = seed.cx * scale + gx,
        gradY2 = seed.cy * scale + gy,
        colors = listOf(
            wheel(seed.hue),
            wheel(seed.hue + seed.hueSpan * 0.5),
            wheel(seed.hue + seed.hueSpan),
        ),
    )
}

/* ------------------------------------------------------------------ 彗星 */

private val COMET_RNG = createRng(0xc0e7)

/**
 * 与直觉相反，点并不横穿屏幕：它留在中心，是彩带绕着它转。
 * 椭圆 a = 0.85、b = 0.15、长轴倾 +34°，4 条彩带，约 210°/s。
 */
val COMET_RIBBONS: List<ArcSeed> = List(4) { i ->
    val d = i - 1.5
    val phaseRng = COMET_RNG()
    val hueRng = COMET_RNG()
    ArcSeed(
        a = 0.85 * (1 + d * 0.03),
        // 相差 ±5% 以内的同扁率：彩带构成一束紧凑的束
        k = (0.15 / 0.85) * (1 + d * 0.16),
        tilt = (34.0).deg2rad() + d * 0.035,
        speed = 210.0 / 360.0,
        // 实测相位差：彩带间 10-20 度，不再多
        phase = -i * 0.045 + phaseRng * 0.012,
        sweep = 0.34,
        hue = i * 85.0 + hueRng * 20.0,
        hueSpan = 80.0,
        width = 0.095,
        cx = 0.0,
        cy = 0.0,
    )
}

/** 彗星点的半径，实测 0.129。 */
const val COMET_DOT = 0.129

/* ---------------------------------------------------------------- 轨道环 */

private val RING_RNG = createRng(0xa11ce)

/**
 * 6 条轨道环：半长轴 1.30-1.40（明显大于球），扁率 <= 0.45，线宽约 0.055，约 3.3 圈/s。
 * 随机数按 TS 属性求值顺序逐个抽取，保证得到与原项目完全相同的六条环。
 */
val RINGS: List<ArcSeed> = List(6) { i ->
    val r1 = RING_RNG(); val r2 = RING_RNG(); val r3 = RING_RNG()
    val r4 = RING_RNG(); val r5 = RING_RNG(); val r6 = RING_RNG()
    val r7 = RING_RNG(); val r8 = RING_RNG(); val r9 = RING_RNG()
    ArcSeed(
        a = 1.3 + r1 * 0.1,
        k = 0.05 + r2 * 0.4,
        tilt = (i / 6.0) * PI + r3 * 0.5,
        speed = 3.0 + r4 * 0.7,
        phase = r5 * TAU,
        sweep = 0.6 + r6 * 0.25,
        hue = (i * 360.0) / 6 + r7 * 30.0,
        hueSpan = 60.0 + r8 * 60.0,
        width = 0.05 + r9 * 0.012,
        cx = 0.0,
        cy = 0.1,
    )
}

private val P_RNG = createRng(0xbeef)

/** 5 颗粒子，每 0.2s 出一颗，寿命 0.55s。 */
private val PARTICLES = Array(5) { i ->
    val birth = i * 0.2
    val angle = P_RNG() * TAU
    val rho = 0.58 + P_RNG() * 0.18
    Triple(birth, angle, rho)
}

/**
 * 粒子不走直线：它们螺旋吸向中心（半径每帧 x0.75，角速度 +100°/s）并变大，
 * 最后绕到核心背后被吞掉。
 */
fun particles(t: Double, scale: Double = 1.0): List<DotSpec> {
    val out = ArrayList<DotSpec>(PARTICLES.size)
    for ((birth, angle0, rho0) in PARTICLES) {
        val u = t - birth
        if (u < 0 || u > 0.62) continue
        val rho = rho0 * 0.75.pow(u * 10)
        val a = angle0 + (u * 100 * PI) / 180.0
        out.add(
            DotSpec(
                x = cos(a) * rho * scale,
                y = sin(a) * rho * scale,
                r = (0.04 + 0.028 * clamp01(u / 0.55)) * scale,
                depth = clamp01(1.0 - rho / 0.8),
                opacity = clamp01(u / 0.06) * clamp01((0.62 - u) / 0.08),
            )
        )
    }
    return out
}

private val PI = kotlin.math.PI
