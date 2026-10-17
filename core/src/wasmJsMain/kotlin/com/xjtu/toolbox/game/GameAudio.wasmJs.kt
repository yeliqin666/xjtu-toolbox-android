package com.xjtu.toolbox.game

/** Web（Kotlin/Wasm）端：暂不做音效。 */
private object SilentWebGameAudio : GameAudio {
    override fun prepare() {}
    override fun play(sfx: Sfx, volume: Float, rate: Float): Int = 0
    override fun stop(stream: Int) {}
}

actual fun gameAudio(): GameAudio = SilentWebGameAudio
