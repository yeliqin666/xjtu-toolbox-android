package com.xjtu.toolbox.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 卡通音效的**纯合成器**（从 :app `GameSound.kt` 里的 `Synth` 原样搬来，只去掉写 WAV 文件
 * 那一段——那是平台 IO，留在 androidMain 的 actual 里）。全部现场合成，不带素材文件。
 *
 * 纯 Kotlin + `kotlin.math`，所以能进 commonMain，也就能在 any target 上跑与测。
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
        fun square(f: Double) = if (sin(next(f)) >= 0) 1.0 else -1.0
        fun saw(f: Double) = 2 * ((next(f) / (2 * PI)) % 1.0) - 1
    }

    private fun noise() = Random.nextDouble(-1.0, 1.0)

    /** 逐样本调 [wave]，参数是秒。 */
    private fun render(seconds: Double, wave: (Double) -> Double): FloatArray =
        FloatArray((seconds * RATE).toInt()) { i -> wave(i.toDouble() / RATE).toFloat().coerceIn(-1f, 1f) }

    fun make(sfx: Sfx): FloatArray {
        val a = Osc()
        val b = Osc()
        val c = Osc()
        val d = Osc()
        return when (sfx) {
            // 弹簧：音高带着越来越小的抖动往上走
            Sfx.BOING -> render(0.45) { t -> a.tri(120 + 180 * t + 90 * exp(-t * 7) * sin(2 * PI * 16 * t)) * exp(-t * 5) }
            // 气泡破掉：一下子从高往低掉
            Sfx.POP -> render(0.08) { t -> a.sine(180 + 900 * exp(-t * 90)) * exp(-t * 45) + if (t < 0.004) noise() * 0.4 else 0.0 }
            // 水滴：短促地往上挑
            Sfx.BLOOP -> render(0.14) { t -> a.sine(260 + 700 * (t / 0.14)) * exp(-t * 22) }
            // 滑哨：时长与跳一跳满蓄力一致
            Sfx.SLIDE_UP -> render(1.3) { t -> a.sine(300 * 3.6.pow(t / 1.3) * (1 + 0.025 * sin(2 * PI * 6 * t))) * min(1.0, t / 0.05) * 0.8 }
            Sfx.SLIDE_DOWN -> render(0.7) { t -> a.sine(1100 * (250.0 / 1100).pow(t / 0.7) * (1 + 0.03 * sin(2 * PI * 7 * t))) * (1 - t / 0.7) }
            // 敲脑袋：一声闷响叠一串金属余音
            Sfx.BONK -> render(0.35) { t ->
                a.sine(110 + 60 * exp(-t * 30)) * exp(-t * 18) * 0.9 +
                    (b.sine(1180.0) + c.sine(1630.0) * 0.7 + d.sine(2390.0) * 0.4) * exp(-t * 11) * 0.25
            }
            Sfx.COIN -> render(0.32) { t -> a.square(if (t < 0.07) 988.0 else 1319.0) * (if (t < 0.07) 1.0 else exp(-(t - 0.07) * 9)) * 0.35 }
            // 悲伤长号：哇、哇、哇、哇——
            Sfx.SAD_TROMBONE -> trombone()
            // 嗒哒！上行琶音收在高音上
            Sfx.TADA -> render(0.75) { t ->
                val i = (t / 0.09).toInt()
                val f = if (i < 3) TADA_NOTES[i] else 1046.5 * (1 + 0.01 * sin(2 * PI * 6 * t))
                a.saw(f) * (if (i < 3) 1.0 else exp(-(t - 0.27) * 3.5)) * 0.3
            }
            // 橡皮鸭：尖声往上一挑再落
            Sfx.SQUEAK -> render(0.2) { t -> a.sine(1300 + 900 * sin(PI * t / 0.2) + 60 * sin(2 * PI * 45 * t)) * sin(PI * t / 0.2) * 0.6 }
            Sfx.SWISH -> swish()
            // 木鱼：清脆短促
            Sfx.KNOCK -> render(0.07) { t -> (a.sine(820.0) + b.sine(1640.0) * 0.3) * exp(-t * 70) }
            Sfx.CHIME -> render(0.45) { t -> (a.sine(523.25) + b.sine(1046.5) * 0.35 + c.sine(1569.75) * 0.12) * min(1.0, t / 0.004) * exp(-t * 7) * 0.5 }
            // 天使合唱：大三和弦慢慢起来，上面洒点亮晶晶
            Sfx.CHOIR -> {
                val e = Osc()
                render(1.8) { t ->
                    val vib = 1 + 0.006 * sin(2 * PI * 5 * t)
                    val pad = (a.sine(261.63 * vib) + b.sine(329.63 * vib) + c.sine(392.0 * vib) + d.sine(523.25 * vib) * 0.6) / 3.6
                    val sparkle = e.sine(2093.0) * 0.15 * (0.5 + 0.5 * sin(2 * PI * 9 * t))
                    (pad + sparkle) * min(1.0, t / 0.35) * min(1.0, (1.8 - t) / 0.6)
                }
            }
            // 「哎哟」：两个音往下掉
            Sfx.UH_OH -> render(0.5) { t ->
                val f = if (t < 0.17) 440.0 else 330.0
                a.square(f * (1 + 0.02 * sin(2 * PI * 6 * t))) * (if (t < 0.17) 1.0 else exp(-(t - 0.2) * 4)) * 0.22
            }
            Sfx.TICK -> render(0.02) { t -> a.sine(2000.0) * exp(-t * 300) * 0.5 }
        }
    }

    private val TADA_NOTES = doubleArrayOf(523.25, 659.25, 783.99)

    private fun trombone(): FloatArray {
        val notes = doubleArrayOf(196.0, 185.0, 174.6, 164.8)
        val lens = doubleArrayOf(0.34, 0.34, 0.34, 1.1)
        val out = ArrayList<Float>()
        var lp = 0.0
        notes.forEachIndexed { k, f ->
            val len = lens[k]
            val osc = Osc()
            val part = render(len) { t ->
                val vib = if (k == 3) 1 + 0.035 * sin(2 * PI * 5.5 * t) * min(1.0, t / 0.3) else 1.0
                val env = min(1.0, t / 0.04) * (if (k == 3) exp(-t * 1.6) else min(1.0, (len - t) / 0.06))
                osc.saw(f * vib) * env
            }
            part.forEach { x ->
                lp += 0.12 * (x - lp) // 压掉锯齿波的刺耳高频，像闷在号里
                out += (lp * 0.9).toFloat()
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
            (hp * 2.2 * sin(PI * t / 0.16)).toFloat().coerceIn(-1f, 1f)
        }
    }
}
