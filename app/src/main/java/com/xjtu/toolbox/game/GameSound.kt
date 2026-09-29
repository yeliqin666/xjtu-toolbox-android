package com.xjtu.toolbox.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** 小游戏共用的卡通音效。全部现场合成，不带素材文件。 */
enum class Sfx {
    BOING, POP, BLOOP, SLIDE_UP, SLIDE_DOWN, BONK, COIN, SAD_TROMBONE, TADA, SQUEAK, SWISH, KNOCK, CHIME, CHOIR, UH_OH, TICK,
}

/**
 * 首次用到时把每个音效合成成 WAV 写进缓存，交给 SoundPool 播放。全应用一份，不释放。
 * 开关存在 [GameStore] 里，所有小游戏共用。
 */
object GameSound {
    /** 改了合成参数就加一，旧缓存文件不再被读到。 */
    private const val VERSION = 1
    private const val PREF = "game_sound"

    private lateinit var app: Context
    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var enabledState by mutableStateOf(true)

    /** 所有小游戏共用的开关，改了就存盘；界面读它会随之重组。 */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            if (::app.isInitialized) GameStore.prefs(app).edit().putBoolean(PREF, value).apply()
        }

    /** 应用启动时调用，只记下 context 和开关，不合成。 */
    fun init(context: Context) {
        app = context.applicationContext
        enabledState = GameStore.prefs(app).getBoolean(PREF, true)
    }

    /** 进小游戏大厅时调用，提前把声音备好；没调过的话第一次播放时补上（那一声会错过）。 */
    @Synchronized
    fun prepare() {
        if (pool != null || !::app.isInitialized) return
        val p = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = p
        val dir = File(app.cacheDir, "game_sound").apply { mkdirs() }
        scope.launch {
            Sfx.entries.forEach { sfx ->
                val f = File(dir, "$VERSION-${sfx.name.lowercase()}.wav")
                if (!f.exists()) runCatching { Synth.writeWav(f, Synth.make(sfx)) }
                if (f.exists()) ids[sfx] = p.load(f.path, 1)
            }
        }
    }

    /** 返回流 id，给 [stop] 用；没开音效或还没加载好时返回 0。[rate] 变调，0.5～2。 */
    fun play(sfx: Sfx, volume: Float = 1f, rate: Float = 1f): Int {
        if (!enabled) return 0
        val p = pool ?: run { prepare(); return 0 }
        val id = ids[sfx] ?: return 0
        return p.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun stop(stream: Int) {
        if (stream != 0) pool?.stop(stream)
    }

    /** 大调音阶上第 [step] 级（从 0 起）的变调倍率，一个八度封顶，连击升调用。 */
    fun scale(step: Int): Float = SCALE[step.coerceIn(0, SCALE.lastIndex)]

    private val SCALE = floatArrayOf(1f, 9 / 8f, 5 / 4f, 4 / 3f, 3 / 2f, 5 / 3f, 15 / 8f, 2f)
}

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
        fun tri(f: Double) = 4 * kotlin.math.abs((next(f) / (2 * PI)) % 1.0 - 0.5) - 1
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

    fun writeWav(file: File, samples: FloatArray) {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            data[2 * i] = v.toByte()
            data[2 * i + 1] = (v shr 8).toByte()
        }
        val tmp = File(file.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            fun int(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.write("RIFF".toByteArray()); int(36 + data.size); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); int(16); short(1); short(1); int(RATE); int(RATE * 2); short(2); short(16)
            f.write("data".toByteArray()); int(data.size); f.write(data)
        }
        tmp.renameTo(file)
    }
}
