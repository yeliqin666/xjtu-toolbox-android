package com.xjtu.toolbox.game.board

import com.xjtu.toolbox.game.GameStore

/**
 * 一局成绩「还挂在账上」的原因。都只在**这局超过开局前本地最高分**时才会被记下来——
 * 没破纪录的一局本来也不配上榜，没必要留账。
 */
enum class RunEnd {
    /** 玩家自己点了退出 / 重新开始。 */
    ABANDONED,

    /** 没走正常流程就没了：杀进程、崩溃、WebView 渲染进程被回收。 */
    INTERRUPTED,

    /** 走到结算了，但玩家在「要不要上传」那张卡上直接退了页面，成绩还没有去处。 */
    ENDED,

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
 * 一局的完整生命周期（改之前先读，顺序错了就会丢账）：
 * ```
 * resume        进页面：拍基线（这局开始前的本地最高分），捞上次残留的账
 * progress      实时分数：只有破纪录才写盘，供进程被杀后恢复
 * abandon       退出 / 重开：这局破纪录就先问一句
 * end           走到结算：补记最终分数、只打「已结束」的标（账先留着）
 * settle        成绩有去处了（不够格 / 玩家不上传 / 提交成功）：划账
 * keepUnsent    没传成功：留一笔等下次
 * reopen        结算后又撤销回未结束（2048 的撤销一步）：这一局继续，并补记当前分数
 * ```
 *
 * **`end` 与 `settle` 分开是必须的**。结算要先拉一次榜才知道够不够格、再等一次提交，
 * 这中间进程随时可能没；以前在拉榜前就把账划了，正好把这半局的分数丢在最需要它的时刻。
 *
 * 盘上只存分数和基线，不存名字——名字仍由 [LeaderboardStore] 管，这里不碰身份。
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
        // 结算后撤销回来的这一局，盘上可能躺着它撤销前的更高分：问就按那笔问，
        // 别拿撤销后的分数把它盖掉（玩家确实打到过那个分）
        val best = maxOf(score, parse(game)?.score ?: 0)
        save(game, best, base, RunEnd.ABANDONED)
        persisted[game] = maxOf(persisted[game] ?: 0, best)
        return RunOffer(game, best, RunEnd.ABANDONED)
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

    /**
     * 结算之后玩家又撤销回未结束（2048 的「撤销一步」）：把这一局接回来。
     *
     * [score] 是撤销之后盘面上的分数，在这里**当场补记一笔**——不能指望后面还会有人来叫
     * [progress]：分没变的时候，页面上「分数变了」那个 effect 根本不会再跑。
     * 基线保持开局那个值，不用 [restart]——那会把基线抬到刚结束的分数，这局后面再破纪录也不记账了。
     */
    fun reopen(game: String, score: Int) {
        ended[game] = false
        val base = baseOf(game)
        // 盘上要是还留着更高的一笔（上一局没传成功的），别覆盖它
        if (score <= base || score <= (parse(game)?.score ?: 0)) return
        save(game, score, base, RunEnd.INTERRUPTED)
        persisted[game] = score
    }

    /**
     * 走到结算了。这里**不当场划账**：够不够格要先拉一次榜，够格还要等玩家回答或一次提交，
     * 账得留到成绩真有去处（[settle]）为止。
     *
     * 同时把最终分数补记一笔：最后一分常常是跟「结束」同一帧产生的，各游戏都在 `isOver` 时
     * 跳过 [progress]，那一分就从没落过盘；玩家在询问卡上直接退出去的话，下次只能捞到旧分数。
     * 盘上已经躺着更高的一局（或上一局没传上去的账）就不动它。
     */
    fun end(game: String, score: Int) {
        ended[game] = true
        if (parse(game)?.let { it.score > score } == true) return
        val base = baseOf(game)
        if (score <= base) return
        save(game, score, base, RunEnd.ENDED)
        // 限流的水位得跟着走：撤销回来接着打时，[progress] 才不会拿更低的分数盖掉这一笔
        persisted[game] = maxOf(persisted[game] ?: 0, score)
    }

    /**
     * 这笔账有去处了：划掉。不够格、玩家选了不上传、提交成功，都走这里。
     *
     * [score] 传本局分数。**盘上那笔比它高就不动**——那已经不是这笔账了：后台提交不等结果，
     * 玩家可能已经重开并打出更高的一局，旧提交的返回不能把它抹掉。
     */
    fun settle(game: String, score: Int) {
        val record = parse(game) ?: return
        if (record.score > score) return
        write(key(game), null)
    }

    /** 结算 / 补传没成功：记一笔等下次，别让分数静悄悄没了。同样不覆盖更高的一笔。 */
    fun keepUnsent(game: String, score: Int) {
        if (score <= 0) return
        val record = parse(game)
        if (record == null) {
            // 盘上没有账，说明这一局连开局那关都没过（结算时拉榜失败也可能走到这里）：
            // 没超过开局最高分的一局不配补传，别拿它去骚扰玩家
            if (score <= baseOf(game)) return
            save(game, score, 0, RunEnd.UNSENT)
            return
        }
        if (record.score > score) return
        save(game, score, record.base, RunEnd.UNSENT)
    }

    /** 盘上那笔账；分数没超过当时的基线就当没有（顺手清掉）。 */
    fun pending(game: String): RunOffer? {
        val record = parse(game)
        if (record == null) {
            if (read(key(game)) != null) write(key(game), null)
            return null
        }
        if (record.end != RunEnd.UNSENT && record.score <= record.base) {
            write(key(game), null)
            return null
        }
        return RunOffer(game, record.score, record.end)
    }

    /** 盘上那笔账，只读不动盘。格式坏的当没有。 */
    private fun parse(game: String): Record? {
        val parts = read(key(game))?.split('|') ?: return null
        val score = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val base = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(2)?.let { name -> RunEnd.entries.firstOrNull { it.name == name } } ?: return null
        return Record(score, base, end)
    }

    private fun baseOf(game: String): Int = baseline.getOrPut(game) { bestScore(game) }

    private fun save(game: String, score: Int, base: Int, end: RunEnd) {
        write(key(game), "$score|$base|${end.name}")
    }

    private fun key(game: String) = "run_pending_$game"

    /** 盘上那笔账的样子。`base` 只在内部用来判断这笔账还作不作数，不往外传。 */
    private data class Record(val score: Int, val base: Int, val end: RunEnd)
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

    fun reopen(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.reopen(game, score)
    }

    fun end(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.end(game, score)
    }

    fun settle(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.settle(game, score)
    }

    fun keepUnsent(game: String, score: Int) {
        if (Leaderboard.enabled) ledger.keepUnsent(game, score)
    }

    fun pending(game: String): RunOffer? = if (Leaderboard.enabled) ledger.pending(game) else null
}
