package com.xjtu.toolbox.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 小游戏共用的卡通音效。全部现场合成，不带素材文件。 */
enum class Sfx {
    BOING, POP, BLOOP, SLIDE_UP, SLIDE_DOWN, BONK, COIN, SAD_TROMBONE, TADA, SQUEAK, SWISH, KNOCK, CHIME, CHOIR, UH_OH, TICK,
}

/** 小游戏共用的音效门面。 */
object GameSound {
    private const val PREF = "game_sound"

    // 开关值惰性读盘；没有 Android 平台的 JVM 单测里读盘必失败，回退到默认开而不是让 object 初始化崩。
    private val enabledState = mutableStateOf(true)
    private var enabledLoaded = false

    private fun ensureEnabledLoaded() {
        if (enabledLoaded) return
        enabledLoaded = true
        enabledState.value = runCatching { GameStore.getBoolean(PREF, true) }.getOrDefault(true)
    }

    /** 所有小游戏共用的开关，改了就存盘；界面读它会随之重组。 */
    var enabled: Boolean
        get() {
            ensureEnabledLoaded()
            return enabledState.value
        }
        set(value) {
            ensureEnabledLoaded()
            enabledState.value = value
            runCatching { GameStore.putBoolean(PREF, value) }
        }
    /** 进小游戏大厅时调用，提前把声音备好；没调过的话第一次播放时补上（那一声会错过）。 */
    fun prepare() {
        gameAudio().prepare()
    }

    /** 返回流 id，给 [stop] 用；没开音效或还没加载好时返回 0。[rate] 变调，0.5～2。 */
    fun play(sfx: Sfx, volume: Float = 1f, rate: Float = 1f): Int =
        if (!enabled) 0 else gameAudio().play(sfx, volume, rate)

    fun stop(stream: Int) {
        if (stream != 0) gameAudio().stop(stream)
    }

    /** 大调音阶上第 [step] 级（从 0 起）的变调倍率，一个八度封顶，连击升调用。 */
    fun scale(step: Int): Float = SCALE[step.coerceIn(0, SCALE.lastIndex)]

    private val SCALE = floatArrayOf(1f, 9 / 8f, 5 / 4f, 4 / 3f, 3 / 2f, 5 / 3f, 15 / 8f, 2f)
}
