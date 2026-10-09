package com.xjtu.toolbox.game

/**
 * Web（Kotlin/Wasm）端：暂不做音效。
 *
 * 浏览器可用 Web Audio API，但需要用户手势解锁音频上下文，与「点一下就响」的交互模型要重新设计；
 * 先空实现。真要做时只改这一个 actual，`GameSound` 的调用点一行不动。
 */
private object SilentWebGameAudio : GameAudio {
    override fun prepare() {}
    override fun play(sfx: Sfx, volume: Float, rate: Float): Int = 0
    override fun stop(stream: Int) {}
}

actual fun gameAudio(): GameAudio = SilentWebGameAudio
