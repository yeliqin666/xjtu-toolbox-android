package com.xjtu.toolbox.game

/**
 * 平台能力：**小游戏音效播放**（交接文档 §4「播放器」族的一个轻量子集）。
 *
 * Android = `SoundPool` + 把 [Synth] 合成的 WAV 写进缓存目录；jvm / Web = 空实现
 * （桌面与浏览器没有这套卡通音效，UI 无需降级）。
 *
 * 粒度是「能力」而不是「整个 GameSound」：合成与开关状态留在 commonMain，
 * 只有真正碰平台 API 的播放收进 actual。
 */
interface GameAudio {
    /** 提前把音效备好；幂等，可多次调用。 */
    fun prepare()

    /** 返回流 id（给 [stop] 用）；还没加载好时返回 0。[rate] 变调，0.5～2。 */
    fun play(sfx: Sfx, volume: Float, rate: Float): Int

    fun stop(stream: Int)
}

expect fun gameAudio(): GameAudio
