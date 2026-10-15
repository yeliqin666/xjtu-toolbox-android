package com.xjtu.toolbox.game.net

/**
 * 联机对弈的通用着法表示。
 *
 * 三种棋的着法形状不一样——五子棋/围棋是"落一子"（围棋还多一个"虚手"），
 * 象棋是"从一点走到另一点"——联机模块本身不关心棋盘，只认这三种形状之一，
 * 具体这一步是什么、合不合法，全部丢给 [OnlineRuleAdapter] 的实现去判断。
 */
sealed class OnlineMove {
    data class Place(val row: Int, val col: Int) : OnlineMove()
    data class Step(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int) : OnlineMove()
    object Pass : OnlineMove()
}

/**
 * 每种棋接入联机只需要实现这一个接口：把 [OnlineMove] 编码成协议里能搬运的一行文本、
 * 反过来解析，以及在本地棋盘上校验并应用这一步。
 *
 * 之所以是「校验 + 应用」而不是单纯的只读判断：[GomokuBoard.place] / [GoBoard.play] /
 * [XiangqiGame.move] 这三个引擎本来就是"一次调用完成判断和落子"的原子操作（同屏双人模式
 * 已经这么用了一年），额外剥出一个不落子的纯校验函数等于给三套已经在跑的规则引擎重开一份
 * 不落子的影子实现——收益（联机这一处用）配不上风险（两套逻辑可能不同步）。所以这里如实
 * 反映引擎的真实形状：[applyIfLegal] 非法时保证 [state] 分毫不动，合法时已经落子完毕，
 * 调用方不需要再调用第二个函数。
 *
 * 双方各自持有同一棋种的 [OnlineRuleAdapter]，收到对方发来的着法后本地重放一遍——
 * 这就是 plan.md §4.9 要求的「双方各自用同一规则引擎校验每一步」。
 */
interface OnlineRuleAdapter<S> {
    val kind: GameKind

    /** 着法编码为协议里的一段文本（不含分隔符/换行）。 */
    fun encodeMove(move: OnlineMove): String

    /** 反向解析；格式坏了（不是本棋种认识的着法）返回 null。 */
    fun decodeMove(code: String): OnlineMove?

    /**
     * 校验 [move] 对 [mover] 是否合法，合法则立即应用到 [state] 上。
     * @param mover 己方/对方的标识，含义由各实现自己定义（比如围棋用 [Stone] 的 ordinal）。
     * @return 是否合法（等价于"是否已经应用成功"）。
     */
    fun applyIfLegal(state: S, move: OnlineMove, mover: Int): Boolean
}
