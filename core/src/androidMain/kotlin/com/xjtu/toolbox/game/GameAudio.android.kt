package com.xjtu.toolbox.game

import android.media.AudioAttributes
import android.media.SoundPool
import com.xjtu.toolbox.platform.androidPlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 侧的真实现 —— 与搬迁前 :app `GameSound` 的播放部分**逐字一致**：
 * 首次用到时把 [Synth] 合成的 WAV 写进 `cacheDir/game_sound`，交给 `SoundPool`（最多 6 路流）播放。
 *
 * 平台句柄从 `androidPlatformContext()` 取（:app 在 `Application.onCreate` 注入），
 * 所以不再需要 `GameSound.init(context)` 这个入口。
 */
private object AndroidGameAudio : GameAudio {
    /** 改了合成参数就加一，旧缓存文件不再被读到。 */
    private const val VERSION = 1

    @Volatile
    private var pool: SoundPool? = null

    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    override fun prepare() {
        if (pool != null) return
        val app = androidPlatformContext() ?: return
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
                if (!f.exists()) runCatching { writeWav(f, Synth.make(sfx)) }
                if (f.exists()) ids[sfx] = p.load(f.path, 1)
            }
        }
    }

    override fun play(sfx: Sfx, volume: Float, rate: Float): Int {
        val p = pool ?: run { prepare(); return 0 }
        val id = ids[sfx] ?: return 0
        return p.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    override fun stop(stream: Int) {
        if (stream != 0) pool?.stop(stream)
    }

    private fun writeWav(file: File, samples: FloatArray) {
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
            f.write("fmt ".toByteArray()); int(16); short(1); short(1); int(44100); int(44100 * 2); short(2); short(16)
            f.write("data".toByteArray()); int(data.size); f.write(data)
        }
        tmp.renameTo(file)
    }
}

actual fun gameAudio(): GameAudio = AndroidGameAudio
