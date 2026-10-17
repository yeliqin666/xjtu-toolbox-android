package com.xjtu.toolbox.game

/** jvm / 桌面端：不做音效。 */
private object SilentGameAudio : GameAudio {
    override fun prepare() {}
    override fun play(sfx: Sfx, volume: Float, rate: Float): Int = 0
    override fun stop(stream: Int) {}
}

actual fun gameAudio(): GameAudio = SilentGameAudio
