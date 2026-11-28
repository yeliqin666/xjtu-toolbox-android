package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.game.GameStore

/**
 * 一局成绩「没走到结算」的原因。三种都只在**这局超过开局前本地最高分**时才会被记下来——
 * 没破纪录的一局本来也不配上榜，没必要留账。
 */
enum class RunEnd {
    /** 玩家自己点了退出 / 重新开始。 */
    ABANDONED,

    /** 没走正常流程就没了：杀进程、崩溃、WebView 渲染进程被回收。 */
    INTERRUPTED,

    /** 结算时报过，但网络 / 服务端没接住，等下次补传。 */
    UNSENT,
}

/** 一条待处理的一局：分数、没走完的原因。 */
data class RunOffer(val game: String, val score: Int, val end: RunEnd)

/**
 * 「一局还没上报就被撂下」的账本。
 *
 * 修的是这么一类丢分（#602）：全服榜只在一局**正常结束**时上报，玩家中途退出、重开、
 * 切后台被杀，四万分就只留在本地最高分里，榜上永远看不见。
 *
 * 三件事分开做：
 * 1. [resume] 进页面时拍一个**基线**（这局开始前的本地最高分）。本地最高分是随打随写的，
 *    拿它当基准没意义——必须留下开局那一刻的值，才判得出「这局破没破纪录」。
 * 2. [progress] 每破一次纪录就往盘上记一笔 [RunEnd.INTERRUPTED]。进程被杀时来不及跑任何回调，
 *    只能靠平时记的这笔账，下次进同一个游戏才捞得回来。
 * 3. [abandon] 玩家主动退出 / 重开时问一句要不要传；[finish] 正常结算时把账划掉。
 *
 * 只存分数和基线，不存名字——名字仍由 [LeaderboardStore] 管，这里不碰身份。
 */
class RunLedger(
    private val bestScore: (String) -> Int,
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {

    /** 这一局开始前的本地最高分。改动只在开局 / 重开时发生。 */
    private val baseline = mutableMapOf<String, Int>()

    /** 已经落过盘的分数，用来给 [progress] 限流：只在刷新纪录时才写盘。 */
    private val persisted = mutableMapOf<String, Int>()

    /** 这一局是不是已经正常结束了。结束之后再按退出，不该再问一遍「要不要上传」。 */
    private val ended = mutableMapOf<String, Boolean>()

    /** 进页面：重新拍基线，并把上次残留的、值得问一句的成绩捞出来（不删，等玩家答完再清）。 */
    fun resume(game: String): RunOffer? {
        baseline[game] = bestScore(game)
        persisted[game] = 0
        ended[game] = false
        return pending(game)
    }

    /** 实时分数。破纪录就落一笔 [RunEnd.INTERRUPTED]，供进程被杀后恢复。 */
    fun progress(game: String, score: Int) {
        if (score <= 0 || ended[game] == true) return
        val base = baseOf(game)
        if (score <= base || score <= (persisted[game] ?: 0)) return
        persisted[game] = score
        save(game, score, base, RunEnd.INTERRUPTED)
    }

    /** 退出 / 重开前：这局真破了纪录才返回一条待确认，否则返回 null（那就什么也别问）。 */
    fun abandon(game: String, score: Int): RunOffer? {
        // 已经结算过的一局，结算页上的「退出」就是退出，不该再来一次
        if (ended[game] == true) return null
        val base = baseOf(game)
        if (score <= 0 || score <= base) return null
        save(game, score, base, RunEnd.ABANDONED)
        return RunOffer(game, score, RunEnd.ABANDONED)
    }

    /**
     * 重开：基线抬到当前本地最高分（这一局无论传没传，本地最高分都已经算进去了），
     * 限流计数与「已结束」标记清零。**不动盘上那笔账**——上传失败留下的 [RunEnd.UNSENT] 还得等下次补。
     */
    fun restart(game: String, previous: Int = 0) {
        baseline[game] = maxOf(bestScore(game), previous)
        persisted[game] = 0
        ended[game] = false
    }

    /** 正常结算：这一局的账清了（真要没传成功，[keepUnsent] 会立刻重新记一笔）。 */
    fun finish(game: String) {
        ended[game] = true
        write(key(game), null)
        persisted[game] = 0
    }

    /** 结算 / 补传没成功：记一笔等下次，别让分数静悄悄没了。 */
    fun keepUnsent(game: String, score: Int) {
        if (score <= 0) return
        save(game, score, 0, RunEnd.UNSENT)
    }

    /** 玩家答完了（传了 / 不传了）：清掉盘上那笔账，但不动「这一局结束没结束」——那由 [finish] 说了算。 */
    fun clear(game: String) {
        write(key(game), null)
        persisted[game] = 0
    }

    /** 盘上那笔账；分数没超过当时的基线就当没有（顺手清掉）。 */
    fun pending(game: String): RunOffer? {
        val raw = read(key(game)) ?: return null
        val parts = raw.split('|')
        val score = parts.getOrNull(0)?.toIntOrNull()
        val base = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(2)?.let { name -> RunEnd.entries.firstOrNull { it.name == name } }
        if (score == null || end == null) {
            write(key(game), null)
            return null
        }
        if (end != RunEnd.UNSENT && score <= base) {
            write(key(game), null)
            return null
        }
        return RunOffer(game, score, end)
    }

    private fun baseOf(game: String): Int = baseline.getOrPut(game) { bestScore(game) }

    private fun save(game: String, score: Int, base: Int, end: RunEnd) {
        write(key(game), "$score|$base|${end.name}")
    }

    private fun key(game: String) = "run_pending_$game"
}

/**
 * [RunLedger] 接到本地存储上的唯一实例。存的是分数与基线，和 `games` 那份键值存储同一处，
 * 换账号时跟着 [GameStore] 一起清。
 *
 * 榜单没配（[Leaderboard.enabled] 为 false）时整块关闭：不写盘、不弹窗。
 */
object LeaderboardRun {

    private val ledger = RunLedger(
        bestScore = GameStore::bestScore,
        read = GameStore::getString,
        write = GameStore::putString,
    )

    fun resume(game: String): RunOffer? = if (Leaderboard.enabled) ledger.resume(game) else null

    fun progress(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.progress(game, score)
    }

    fun abandon(game: String, score: Int): RunOffer? =
        if (Leaderboard.enabled) ledger.abandon(game, score) else null

    fun restart(game: String, previous: Int = 0) {
        if (Leaderboard.enabled) ledger.restart(game, previous)
    }

    fun finish(game: String) {
        if (Leaderboard.enabled) ledger.finish(game)
    }

    fun keepUnsent(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.keepUnsent(game, score)
    }

    fun clear(game: String) {
        if (Leaderboard.enabled) ledger.clear(game)
    }
}
