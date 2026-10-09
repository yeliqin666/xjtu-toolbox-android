package com.xjtu.toolbox.game

import com.xjtu.toolbox.platform.keyValueStore

/**
 * 小游戏的本地记录：最高分与胜负战绩。
 *
 * 从 :app 的 `game/GameStore.kt` 搬进 commonMain：存储从 `SharedPreferences("games")`
 * 换成 [keyValueStore] —— Android actual 仍是同名同文件的 SharedPreferences，键名与语义逐个
 * 保留，行为逐字一致；jvm 走内存、Web 走 localStorage。
 *
 * 全部记录**不上传、不进任何网络请求**——这些数字只是给自己看的，没有排行榜，
 * 也就没有理由离开这台设备。
 *
 * key 由各游戏自己定，约定见下面的常量：分数类用 `best_<game>`，
 * 对弈类按难度分别计 `<game>_win_<difficulty>` / `<game>_loss_<difficulty>`，
 * 免得「简单档赢一百盘」和「地狱档赢一盘」混成同一个数。
 *
 * ⚠️ 与搬迁前的 API 差异只有一处：不再需要传 `Context`（这正是它能进 commonMain 的原因）。
 * 调用点从 `GameStore.bestScore(context, id)` 改成 `GameStore.bestScore(id)`。
 */
object GameStore {

    private const val PREFS = "games"

    private fun store() = keyValueStore(PREFS)

    // ── 分数类（2048、合成西交大…）──

    fun bestScore(game: String): Int = store().getInt("best_$game", 0)

    /** 只增不减：传进来的分数低于已有记录时什么都不做，调用方不必自己比。 */
    fun submitScore(game: String, score: Int) {
        val s = store()
        if (score > s.getInt("best_$game", 0)) {
            s.putInt("best_$game", score)
        }
    }

    // ── 对弈类（五子棋、围棋、象棋）──
    //
    // difficulty 对人机模式是难度档（easy / hard / hell），
    // 同屏双人传 "local"——它也是一种「对手」，战绩分开记才有意义。

    fun wins(game: String, difficulty: String): Int = store().getInt("${game}_win_$difficulty", 0)

    fun losses(game: String, difficulty: String): Int = store().getInt("${game}_loss_$difficulty", 0)

    fun draws(game: String, difficulty: String): Int = store().getInt("${game}_draw_$difficulty", 0)

    fun recordResult(game: String, difficulty: String, result: GameResult) {
        val suffix = when (result) {
            GameResult.WIN -> "win"
            GameResult.LOSS -> "loss"
            GameResult.DRAW -> "draw"
        }
        val key = "${game}_${suffix}_$difficulty"
        val s = store()
        s.putInt(key, s.getInt(key, 0) + 1)
    }

    // ── 小游戏自己的杂项偏好（跳一跳的皮肤、音效开关…）──
    // 原本调用方直接拿 `GameStore.prefs(context)` 读写；commonMain 不暴露 SharedPreferences，
    // 所以收成几个按 key 的窄接口，落在同一份 "games" 存储里。

    fun getString(key: String): String? = store().getString(key)

    fun putString(key: String, value: String?) = store().putString(key, value)

    fun getBoolean(key: String, default: Boolean): Boolean = store().getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) = store().putBoolean(key, value)
}

enum class GameResult { WIN, LOSS, DRAW }

/**
 * 游戏标识。合集页、记录 key、路由后缀共用同一份，避免三处各写一个字符串然后对不上。
 */
object GameIds {
    const val MERGE = "merge"       // 合成西交大
    const val G2048 = "2048"        // GPA 2048
    const val BLOCKS = "blocks"     // 方块，记录再按玩法分
    const val HOP = "hop"           // 跳一跳
    const val GOMOKU = "gomoku"     // 五子棋
    const val GO = "go"             // 围棋
    const val XIANGQI = "xiangqi"   // 象棋
}
