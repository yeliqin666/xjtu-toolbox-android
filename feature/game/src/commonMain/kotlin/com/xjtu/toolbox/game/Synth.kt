package com.xjtu.toolbox.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 卡通音效的纯合成器，全部现场合成，不带素材文件（写 WAV 在 androidMain 的 actual 里）。
 *
 * 纯 Kotlin + `kotlin.math`，所以能进 commonMain，也就能在 any target 上跑与测。
 * 每个音效合成完统一过 [finish]：首尾淡入淡出（不然起止处会「啪」一声）、峰值归一到各自的响度。
 */
internal object Synth {
    private const val RATE = 44100

    /** 一个声部：自己累加相位，频率可以逐样本变。 */
    private class Osc {
        private var ph = 0.0
        private fun next(f: Double): Double {
            ph += 2 * PI * f / RATE
            return ph
        }
        fun sine(f: Double) = sin(next(f))
        fun tri(f: Double) = 4 * abs((next(f) / (2 * PI)) % 1.0 - 0.5) - 1

        /**
         * 加法合成的铜管 / 簧片音色：前 [n] 个泛音按 1 / k^[roll] 叠加，超过 12kHz 的不叠。
         * 比裸锯齿波、方波柔和，也没有混叠出来的毛刺。[odd] 只取奇次泛音，偏空、偏木。
         */
        fun rich(f: Double, n: Int, roll: Double, odd: Boolean = false): Double {
            val p = next(f)
            var sum = 0.0
            var norm = 0.0
            var k = 1
            while (k <= n && k * f < 12000) {
                val w = 1 / k.toDouble().pow(roll)
                sum += w * sin(k * p)
                norm += w
                k += if (odd) 2 else 1
            }
            return sum / norm
        }
    }

    private fun noise() = Random.nextDouble(-1.0, 1.0)

    /** 逐样本调 [wave]，参数是秒。 */
    private fun render(seconds: Double, wave: (Double) -> Double): FloatArray =
        FloatArray((seconds * RATE).toInt()) { i -> wave(i.toDouble() / RATE).toFloat() }

    /** 起音 [a] 秒、之后按 [decay] 指数衰减的包络。 */
    private fun pluck(t: Double, a: Double, decay: Double) = if (t < 0) 0.0 else min(1.0, t / a) * exp(-t * decay)

    /** 首尾淡入淡出，峰值归一到 [peak]。 */
    private fun finish(x: FloatArray, peak: Float): FloatArray {
        var m = 0f
        for (v in x) m = max(m, abs(v))
        val gain = if (m > 1e-4f) peak / m else 1f
        val fadeIn = min(x.size / 4, RATE / 1000)
        val fadeOut = min(x.size / 3, RATE * 8 / 1000)
        for (i in x.indices) {
            var k = gain
            if (i < fadeIn) k *= i.toFloat() / fadeIn
            val left = x.size - 1 - i
            if (left < fadeOut) k *= left.toFloat() / fadeOut
            x[i] *= k
        }
        return x
    }

    fun make(sfx: Sfx): FloatArray {
        val a = Osc()
        val b = Osc()
        val c = Osc()
        val d = Osc()
        return when (sfx) {
            // 弹簧：音高带着越来越小的抖动往上走
            Sfx.BOING -> finish(render(0.5) { t ->
                val f = 120 + 180 * t + 90 * exp(-t * 7) * sin(2 * PI * 16 * t)
                (a.tri(f) + b.sine(f * 2) * 0.25) * exp(-t * 5.5)
            }, 0.8f)
            // 气泡破掉：一下子从高往低掉，开头带一点气声
            Sfx.POP -> finish(render(0.11) { t ->
                val f = 170 + 950 * exp(-t * 80)
                (a.sine(f) + b.sine(f * 2) * 0.2) * exp(-t * 42) + noise() * 0.35 * exp(-t * 700)
            }, 0.9f)
            // 水滴：短促地往上挑
            Sfx.BLOOP -> finish(render(0.16) { t -> a.sine(260 + 700 * (t / 0.16).pow(0.7)) * pluck(t, 0.004, 22.0) }, 0.8f)
            // 滑哨：时长与跳一跳满蓄力一致
            Sfx.SLIDE_UP -> finish(render(1.3) { t ->
                val f = 300 * 3.6.pow(t / 1.3) * (1 + 0.025 * sin(2 * PI * 6 * t))
                (a.sine(f) + b.sine(f * 2) * 0.12) * min(1.0, t / 0.05)
            }, 0.6f)
            Sfx.SLIDE_DOWN -> finish(render(0.7) { t ->
                val f = 1100 * (250.0 / 1100).pow(t / 0.7) * (1 + 0.03 * sin(2 * PI * 7 * t))
                (a.sine(f) + b.sine(f * 2) * 0.12) * (1 - t / 0.7)
            }, 0.7f)
            // 敲脑袋：一声闷响叠一串金属余音
            Sfx.BONK -> finish(render(0.4) { t ->
                a.sine(105 + 70 * exp(-t * 30)) * exp(-t * 16) * 0.9 +
                    (b.sine(1180.0) + c.sine(1630.0) * 0.7 + d.sine(2390.0) * 0.4) * exp(-t * 11) * 0.22 +
                    noise() * 0.3 * exp(-t * 500)
            }, 0.9f)
            // 金币：两个音的小铃铛，第二个音拖一点尾巴
            Sfx.COIN -> finish(render(0.42) { t ->
                val u = t - 0.07
                bell(a, b, 987.77, pluck(t, 0.002, 30.0)) + bell(c, d, 1318.5, pluck(u, 0.002, 8.0))
            }, 0.6f)
            // 悲伤长号：哇、哇、哇、哇——
            Sfx.SAD_TROMBONE -> finish(trombone(), 0.8f)
            // 嗒哒！三个上行的短音，收在一个亮堂的大三和弦上
            Sfx.TADA -> {
                val e = Osc()
                val f = Osc()
                val g = Osc()
                val arp = arrayOf(a, b, c)
                finish(render(0.95) { t ->
                    var x = 0.0
                    for (i in 0..2) x += arp[i].rich(TADA_NOTES[i], 8, 1.3) * pluck(t - i * 0.085, 0.006, 16.0)
                    val u = t - 0.27
                    if (u >= 0) {
                        val vib = 1 + 0.008 * sin(2 * PI * 6 * u) * min(1.0, u / 0.2)
                        val chord = d.rich(523.25 * vib, 8, 1.2) + e.rich(659.25 * vib, 6, 1.3) * 0.8 +
                            f.rich(783.99 * vib, 6, 1.3) * 0.8 + g.rich(1046.5 * vib, 4, 1.4) * 0.7
                        x += chord * min(1.0, u / 0.012) * exp(-u * 3.2) * 0.6
                    }
                    x
                }, 0.7f)
            }
            // 橡皮鸭：尖声往上一挑再落
            Sfx.SQUEAK -> finish(render(0.2) { t ->
                val f = 1300 + 900 * sin(PI * t / 0.2) + 60 * sin(2 * PI * 45 * t)
                (a.sine(f) + b.sine(f * 2) * 0.2) * sin(PI * t / 0.2)
            }, 0.6f)
            Sfx.SWISH -> finish(swish(), 0.8f)
            // 落子：棋子磕在木头上，一下短促的「嗒」带一点木头的空腔声
            Sfx.KNOCK -> finish(render(0.13) { t ->
                a.sine(170 + 130 * exp(-t * 180)) * exp(-t * 42) * 0.7 +
                    b.sine(640.0) * exp(-t * 60) * 0.5 +
                    c.sine(1530.0) * exp(-t * 95) * 0.22 +
                    noise() * 0.45 * exp(-t * 900)
            }, 0.9f)
            // 叮：带一个不谐和泛音才像金属
            Sfx.CHIME -> finish(render(0.7) { t ->
                (a.sine(523.25) + b.sine(1046.5) * 0.35 + c.sine(1444.2) * 0.14 * exp(-t * 6) + d.sine(1569.75) * 0.1) *
                    pluck(t, 0.004, 5.5)
            }, 0.7f)
            // 天使合唱：大三和弦慢慢起来，上面洒点亮晶晶
            Sfx.CHOIR -> {
                val e = Osc()
                finish(render(1.8) { t ->
                    val vib = 1 + 0.006 * sin(2 * PI * 5 * t)
                    val pad = (a.sine(261.63 * vib) + b.sine(329.63 * vib) + c.sine(392.0 * vib) + d.sine(523.25 * vib) * 0.6) / 3.6
                    val sparkle = e.sine(2093.0) * 0.15 * (0.5 + 0.5 * sin(2 * PI * 9 * t))
                    (pad + sparkle) * min(1.0, t / 0.35) * min(1.0, (1.8 - t) / 0.6)
                }, 0.7f)
            }
            // 「哎哟」：两个音往下掉，每个音尾巴再往下滑一点
            Sfx.UH_OH -> finish(render(0.55) { t ->
                val u = t - 0.19
                a.rich(440.0 * (1 - 0.04 * ramp(t, 0.1, 0.17)), 7, 1.2, odd = true) * min(1.0, t / 0.008) * (1 - ramp(t, 0.14, 0.17)) +
                    if (u < 0) 0.0 else b.rich(330.0 * (1 - 0.06 * ramp(u, 0.1, 0.36)), 7, 1.2, odd = true) * pluck(u, 0.008, 5.0)
            }, 0.5f)
            // 读秒 / 选中：很轻的一下
            Sfx.TICK -> finish(render(0.035) { t -> a.sine(1500.0) * exp(-t * 200) + noise() * 0.3 * exp(-t * 1200) }, 0.6f)
        }
    }

    private fun ramp(t: Double, from: Double, to: Double) = ((t - from) / (to - from)).coerceIn(0.0, 1.0)

    /** 小铃铛的一个音：基音加一个八度泛音。 */
    private fun bell(a: Osc, b: Osc, f: Double, env: Double): Double =
        if (env <= 0.0) 0.0 else (a.sine(f) + b.sine(f * 2) * 0.4) * env

    private val TADA_NOTES = doubleArrayOf(392.0, 523.25, 659.25)

    private fun trombone(): FloatArray {
        val notes = doubleArrayOf(196.0, 185.0, 174.6, 164.8)
        val lens = doubleArrayOf(0.34, 0.34, 0.34, 1.1)
        val out = ArrayList<Float>()
        var lp = 0.0
        notes.forEachIndexed { k, f ->
            val len = lens[k]
            val osc = Osc()
            val last = k == 3
            val part = render(len) { t ->
                // 每个音都往下塌一点；最后一个长音带越来越大的颤音
                val bend = 1 - 0.025 * t / len
                val vib = if (last) 1 + 0.035 * sin(2 * PI * 5.5 * t) * min(1.0, t / 0.3) else 1.0
                val env = min(1.0, t / 0.04) * (if (last) exp(-t * 1.6) else min(1.0, (len - t) / 0.06))
                osc.rich(f * bend * vib, 14, 1.0) * env
            }
            part.forEachIndexed { i, x ->
                // 「哇」：号口先张开再捂上，低通跟着开合
                val t = i.toDouble() / RATE
                val open = if (last) exp(-t * 2.2) else sin(PI * t / len)
                lp += (0.06 + 0.2 * open) * (x - lp)
                out += lp.toFloat()
            }
        }
        return out.toFloatArray()
    }

    private fun swish(): FloatArray {
        val n = (0.16 * RATE).toInt()
        var lp = 0.0
        var prev = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val a = 0.05 + 0.5 * (t / 0.16)
            lp += a * (noise() - lp)
            val hp = lp - prev
            prev = lp
            (hp * sin(PI * t / 0.16)).toFloat()
        }
    }
}
