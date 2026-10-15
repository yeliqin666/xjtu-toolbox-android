package com.xjtu.toolbox.game

/** 平台能力：小游戏音效播放。 */
interface GameAudio {
    /** 提前把音效备好；幂等，可多次调用。 */
    fun prepare()

    /** 返回流 id（给 [stop] 用）；还没加载好时返回 0。[rate] 变调，0.5～2。 */
    fun play(sfx: Sfx, volume: Float, rate: Float): Int

    fun stop(stream: Int)
}

expect fun gameAudio(): GameAudio
