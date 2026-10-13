package com.xjtu.toolbox.agent.bot.cast

import com.xjtu.toolbox.agent.bot.NOTIF_BLUE
import com.xjtu.toolbox.agent.bot.clamp01
import com.xjtu.toolbox.agent.bot.createRng
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/* 角色共用的小工具：都是纯函数，时间单位秒。 */

/** 线性映射到 0..1 并截断。 */
fun ramp(t: Double, a: Double, b: Double): Double = clamp01((t - a) / (b - a))

/** 平滑阶跃。 */
fun ss(x: Double): Double {
    val t = clamp01(x)
    return t * t * (3 - 2 * t)
}

/** a..b 淡入、c..d 淡出的窗口，平滑。 */
fun window(t: Double, a: Double, b: Double, c: Double, d: Double): Double =
    ss(ramp(t, a, b)) * (1 - ss(ramp(t, c, d)))

/** 0 → 1 → 0 的拱形，u 取 0..1。 */
fun bump(u: Double): Double = sin(PI * clamp01(u))

/** 阻尼正弦：t=0 时为 0，先冲出去再衰减。 */
fun wobble(t: Double, freq: Double, damp: Double): Double =
    if (t < 0) 0.0 else exp(-damp * t) * sin(freq * t)

fun mix(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** 提醒态的蓝点颜色，与经典屁岱的通知点同色。 */
const val ALERT_BLUE: Long = NOTIF_BLUE

/**
 * 偶发小动作的时刻表：间隔在 [minGap, maxGap] 之间随机，预先抽好、确定无状态。
 * 超过 [SPAN] 秒循环，底栏常驻多久都有。
 */
class Beats(seed: Int, minGap: Double, maxGap: Double, first: Double = minGap) {
    private val times: DoubleArray = run {
        val rng = createRng(seed)
        val out = ArrayList<Double>()
        var t = first
        while (t < SPAN) {
            out.add(t)
            t += minGap + rng() * (maxGap - minGap)
        }
        out.toDoubleArray()
    }

    /** 距最近一次触发过了多久；还没触发过返回很大的数。 */
    fun since(now: Double): Double {
        val t = now % SPAN
        var lo = 0
        var hi = times.size - 1
        if (hi < 0 || t < times[0]) return 1e9
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= t) lo = mid else hi = mid - 1
        }
        return t - times[lo]
    }

    /** 最近一次触发是第几次：给每次触发换个方向、位置用。 */
    fun index(now: Double): Int {
        val t = now % SPAN
        var i = 0
        while (i < times.size && times[i] <= t) i++
        return i - 1
    }

    private companion object {
        const val SPAN = 600.0
    }
}
