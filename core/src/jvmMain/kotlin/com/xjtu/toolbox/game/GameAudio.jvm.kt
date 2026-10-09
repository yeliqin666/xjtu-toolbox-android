package com.xjtu.toolbox.game

/**
 * jvm / 桌面端：不做音效（共享 UI 还没接桌面音频后端），空实现即可，UI 无需降级。
 */
private object SilentGameAudio : GameAudio {
    override fun prepare() {}
    override fun play(sfx: Sfx, volume: Float, rate: Float): Int = 0
    override fun stop(stream: Int) {}
}

actual fun gameAudio(): GameAudio = SilentGameAudio
