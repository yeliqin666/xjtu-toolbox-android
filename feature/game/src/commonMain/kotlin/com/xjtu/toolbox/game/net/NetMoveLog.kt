package com.xjtu.toolbox.game.net

/**
 * 按序号记录本局发生过的每一步（编码后的文本），断线重连时用它补发对方缺的部分。
 *
 * 序号从 1 开始，必须连续——这是保证"两边棋盘永不分叉"的前提：谁都不能跳号，
 * 跳号只可能是消息丢了或者顺序被打乱，任何一种都应该在更上层被当成异常处理掉，
 * 而不是在这里悄悄兼容。
 */
class NetMoveLog {
    private val moves = mutableListOf<String>()

    val count: Int get() = moves.size

    /** 记录第 [seq] 步；[seq] 必须等于当前已有步数 + 1，否则说明序号不连续。 */
    fun record(seq: Int, code: String) {
        require(seq == moves.size + 1) { "着法序号不连续：期望 ${moves.size + 1}，收到 $seq" }
        moves.add(code)
    }

    /** 第 [seq] 步的着法文本；不存在返回 null。 */
    fun at(seq: Int): String? = moves.getOrNull(seq - 1)

    /** 断线重连补发：对方说它已经确认到 [ackSeq]，这里返回从 ackSeq+1 开始的剩余着法。 */
    fun sinceExclusive(ackSeq: Int): List<Pair<Int, String>> {
        if (ackSeq >= moves.size) return emptyList()
        val from = ackSeq.coerceAtLeast(0)
        return (from until moves.size).map { it + 1 to moves[it] }
    }
}
