package com.xjtu.toolbox.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.keyValueStore

/**
 * 小游戏的本地记录：最高分与胜负战绩。
 *
 * 全部存在一份 `keyValueStore("games")` 里，本身**不上传、不进任何网络请求**。
 *
 * 唯一的例外是分数类游戏的**全服榜**：够上榜的成绩会由 [com.xjtu.toolbox.game.board.Leaderboard]
 * 另发一份到自建服务端，上传的只有游戏、分数、名字和一个设备级匿名 ID。这份记录仍是本地的
 * 权威——榜拉不到、网断了都不影响这里的数字，对弈类战绩则始终不出设备。
 */
object GameStore {

    private const val PREFS = "games"

    private fun store() = keyValueStore(PREFS)

    /** 战绩每变一次加一，合集页据此重读。 */
    var version by mutableIntStateOf(0)
        private set

    // ── 分数类（2048、合成西交大…）──

    fun bestScore(game: String): Int = store().getInt("best_$game", 0)

    /** 只增不减：传进来的分数低于已有记录时什么都不做，调用方不必自己比。 */
    fun submitScore(game: String, score: Int) {
        val s = store()
        if (score > s.getInt("best_$game", 0)) {
            s.putInt("best_$game", score)
            version++
        }
    }


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
        version++
    }


    fun getString(key: String): String? = store().getString(key)

    fun putString(key: String, value: String?) = store().putString(key, value)

    fun getBoolean(key: String, default: Boolean): Boolean = store().getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) = store().putBoolean(key, value)

    fun contains(key: String): Boolean = store().contains(key)

    fun remove(key: String) = store().remove(key)
}

enum class GameResult { WIN, LOSS, DRAW }

/** 游戏标识。 */
object GameIds {
    const val MERGE = "merge"       // 合成西交大
    const val G2048 = "2048"        // GPA 2048
    const val BLOCKS = "blocks"     // 方块，记录再按玩法分
    const val HOP = "hop"           // 跳一跳
    const val GOMOKU = "gomoku"     // 五子棋
    const val GO = "go"             // 围棋
    const val XIANGQI = "xiangqi"   // 象棋
}
