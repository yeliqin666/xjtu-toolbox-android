package com.xjtu.toolbox.agent.bot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** bloub 引擎的数学底座，逐行移植自 src/bot/math.ts，数值语义保持一致。 */

const val TAU: Double = PI * 2

fun clamp01(v: Double): Double = v.coerceIn(0.0, 1.0)

fun clamp(v: Double, lo: Double, hi: Double): Double = v.coerceIn(lo, hi)

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/**
 * 视频实测：过渡是无过冲的 ease-out。唯一的弹簧效果是局部的（通知点的 pop、
 * 睁眼），直接写在对应状态里。
 */
object Easings {
    fun easeOutCubic(t: Double): Double = 1.0 - (1.0 - t).pow(3)
    fun easeInOutCubic(t: Double): Double =
        if (t < 0.5) 4.0 * t.pow(3) else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
    fun easeOutQuint(t: Double): Double = 1.0 - (1.0 - t).pow(5)
}

/** 一维周期噪声：在 period 上无缝循环，用于视线的漂移。 */
fun loopNoise(t: Double, period: Double, seed: Double = 0.0): Double {
    val p = (t / period) * TAU
    return (
        0.55 * sin(p + seed) +
            0.3 * sin(2 * p + seed * 1.7 + 1.1) +
            0.15 * sin(3 * p + seed * 2.3 + 2.4)
        )
}

/** 确定性 PRNG（mulberry32）：每次构建产生同一条序列，保证动画可复现。 */
fun createRng(seed: Int): () -> Double {
    var a = seed.toUInt()
    return {
        a = a + 0x6d2b79f5u
        var t = (a xor (a shr 15)) * (1u or a)
        t = (t xor (t shr 7)) * (t or 61u)
        (t xor (t shr 14)).toDouble() / 4294967296.0
    }
}

private val degToRad = PI / 180.0

/** 角度转弧度。 */
fun Double.deg2rad(): Double = this * degToRad
