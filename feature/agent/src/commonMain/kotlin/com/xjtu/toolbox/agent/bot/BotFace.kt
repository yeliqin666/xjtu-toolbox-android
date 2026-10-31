package com.xjtu.toolbox.agent.bot

import kotlin.math.cos
import kotlin.math.sin

/**
 * 眼睛是画在球面上的，不是平铺的。
 *
 * 常量全部来自对参考视频的逐帧拟合（残差 ~1px / 半径 190px），不要凭感觉改。
 * 移植自 src/bot/face.ts；圆形身体下眼睛偏移恒为零（eyefit 表的结论），
 * 因此整个 eyefit 求解器不需要移植。
 */

/** 两眼在球面上的半张角，度（总分离约 31°）。 */
const val EYE_SPLIT = 15.46
/** 静息眼睛大小，单位为球半径。 */
const val EYE_W = 0.186
const val EYE_H = 0.412

/** 静息头部朝向。 */
data class HeadGaze(val yaw: Double, val pitch: Double, val roll: Double)

val REST_GAZE = HeadGaze(yaw = 28.49, pitch = 28.62, roll = -13.0)

class EyePose(
    val x: Double,
    val y: Double,
    /** 切向 2x2 矩阵的列分量，含义同 SVG matrix(a,b,c,d) */
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    /** 法线 z 分量：>0 = 正面可见 */
    val depth: Double,
)

private fun spin(u: DoubleArray, v: DoubleArray, angle: Double): Pair<DoubleArray, DoubleArray> {
    val c = cos(angle)
    val s = sin(angle)
    return doubleArrayOf(
        u[0] * c + v[0] * s, u[1] * c + v[1] * s, u[2] * c + v[2] * s
    ) to doubleArrayOf(
        v[0] * c - u[0] * s, v[1] * c - u[1] * s, v[2] * c - u[2] * s
    )
}

/**
 * 头部朝向 -> 两只眼睛的位置与切向标架。
 * 屏幕系：x 向右，y 向下，z 朝观察者。索引 0 = 内侧眼，1 = 外侧眼。
 */
fun eyePoses(gaze: HeadGaze, scale: Double, split: Double = EYE_SPLIT): Array<EyePose> {
    var f = doubleArrayOf(0.0, 0.0, 1.0)
    var right = doubleArrayOf(1.0, 0.0, 0.0)
    var down = doubleArrayOf(0.0, 1.0, 0.0)

    // yaw：forward 向 right 倒
    spin(f, right, gaze.yaw.deg2rad()).let { f = it.first; right = it.second }
    // pitch：forward 向上倒（与 down 相反）
    spin(down, f, gaze.pitch.deg2rad()).let { down = it.first; f = it.second }
    // roll：头在自己的平面内倾斜
    spin(right, down, gaze.roll.deg2rad()).let { right = it.first; down = it.second }

    fun build(side: Int): EyePose {
        val (ef, er) = spin(f, right, (split * side).deg2rad())
        return EyePose(
            x = ef[0] * scale,
            y = ef[1] * scale,
            a = er[0], b = er[1],
            c = down[0], d = down[1],
            depth = ef[2],
        )
    }
    return arrayOf(build(-1), build(1))
}

/**
 * 静息时的生命感：视线慢漂移、扫视、眨眼。
 *
 * 是时间的纯函数（无内部状态），因此暂停、恢复、跳到任意时刻给出的图像一致。
 * 数值都是「偏移量」，叠加到当前状态的姿态上。
 */
data class Liveliness(
    val dYaw: Double,
    val dPitch: Double,
    val dRoll: Double,
    /** 1 = 睁眼，0 = 闭眼（屏幕系竖直压扁） */
    val lid: Double,
    val driftX: Double,
    val driftY: Double,
    val breath: Double,
)

private val BLINK_RNG = createRng(0x5eed)

/** 预抽定的眨眼时刻表：确定且无状态。 */
private val BLINKS: DoubleArray = run {
    val out = ArrayList<Double>()
    var t = 1.4
    while (t < 900.0) {
        out.add(t)
        // 两次眨眼间隔 1.9–4.6s，偶尔一个双眨
        t += 1.9 + BLINK_RNG() * 2.7
        if (BLINK_RNG() < 0.18) {
            out.add(t)
            t += 0.24
        }
    }
    out.toDoubleArray()
}

/** 实测：10fps 下 1–2 帧。 */
private const val BLINK_DUR = 0.18

private fun blinkLid(t: Double): Double {
    for (start in BLINKS) {
        if (t < start) break
        val k = (t - start) / BLINK_DUR
        if (k >= 0 && k <= 1) {
            // 闭合快，重新睁开稍慢
            return if (k < 0.45) 1.0 - k / 0.45 else (k - 0.45) / 0.55
        }
    }
    return 1.0
}

fun liveliness(t: Double, wander: Double = 1.0, blink: Boolean = true): Liveliness = Liveliness(
    dYaw = (loopNoise(t, 11.3, 0.4) * 5.5 + loopNoise(t, 3.7, 2.1) * 1.6) * wander,
    dPitch = (loopNoise(t, 9.1, 1.3) * 4.2 + loopNoise(t, 4.3, 0.7) * 1.3) * wander,
    dRoll = loopNoise(t, 13.7, 3.2) * 2.2 * wander,
    lid = if (blink) blinkLid(t) else 1.0,
    // 静息时视频几乎不动（中心 ±0.003）：只保留不让画面完全凝固的最小量。
    driftX = loopNoise(t, 7.9, 1.9) * 0.006,
    driftY = loopNoise(t, 5.3, 0.3) * 0.007,
    // 宽度恒定，只有高度轻微呼吸。
    breath = 1.0 + sin((t / 3.4) * PI2) * 0.005,
)

private const val PI2 = kotlin.math.PI * 2

/**
 * 眨眼是屏幕系里绕眼睛中心的竖直压扁（实测：bbox 宽度不变，高度掉到 ~0.35），
 * 不是沿胶囊自身轴的收缩。所以它作用在切向矩阵之后，只影响 y 输出。
 */
fun blinkScale(lid: Double): Double = 0.06 + 0.94 * clamp01(lid)
