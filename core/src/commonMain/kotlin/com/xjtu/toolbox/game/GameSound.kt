package com.xjtu.toolbox.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 小游戏共用的卡通音效。全部现场合成，不带素材文件。 */
enum class Sfx {
    BOING, POP, BLOOP, SLIDE_UP, SLIDE_DOWN, BONK, COIN, SAD_TROMBONE, TADA, SQUEAK, SWISH, KNOCK, CHIME, CHOIR, UH_OH, TICK,
}

/**
 * 小游戏共用的音效门面。从 :app 的 `game/GameSound.kt` 搬进 commonMain，并按「能力」切开：
 *
 * - **合成**（[Synth]）是纯 Kotlin，跟着进 commonMain；
 * - **播放**（SoundPool + 写 WAV 文件）是 Android 专属，收进 `gameAudio()` 这个
 *   `expect/actual`（Android = SoundPool，jvm/Web = 空实现——桌面与浏览器没有这套音效，
 *   UI 无需降级）。
 *
 * 对外 API 与搬迁前逐字一致（`play` / `stop` / `prepare` / `enabled` / `scale`），
 * 于是 6 个调用方文件（Blocks / 2048 / Hop / 五子棋 / 围棋 / 象棋 / 合成）import 一行不用改。
 * 开关仍存在 `games` 那份存储里（键 `game_sound`），与搬迁前同一份数据。
 */
object GameSound {
    private const val PREF = "game_sound"

    // 开关的持久值**惰性加载**：搬迁前由 `GameSound.init(context)` 在 Application.onCreate 里读盘；
    // 现在没有那个入口了，改成第一次访问时读。用 runCatching 是因为 :app 的纯逻辑单测
    // （GoBoardTest 等）会在没有 Android 平台的 JVM 上跑到这里——那种环境下读盘注定失败，
    // 应该回退到默认值 true（与搬迁前“未 init 时默认开”一致），而不是把整个 object 初始化撞崩。
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
