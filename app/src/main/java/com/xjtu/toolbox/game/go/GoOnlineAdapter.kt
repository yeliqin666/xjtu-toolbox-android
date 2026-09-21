package com.xjtu.toolbox.game.go

import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.game.net.OnlineRuleAdapter

/**
 * 围棋接入联机模块。着法有两种：落子 "x,y"、虚手 "pass"。
 *
 * [mover] 传 [Stone.BLACK] / [Stone.WHITE] 的 ordinal。
 *
 * 数子阶段"点哪块棋是死子"是同屏双人才有的手动标记，联机对局不同步这一步——见
 * [applyIfLegal] 的注释。这是本 PR 相对 plan.md 的一处明确简化，已经在最终报告里说明。
 */
class GoOnlineAdapter : OnlineRuleAdapter<GoBoard> {
    override val kind: GameKind = GameKind.GO

    override fun encodeMove(move: OnlineMove): String = when (move) {
        is OnlineMove.Place -> "${move.row},${move.col}"
        OnlineMove.Pass -> "pass"
        is OnlineMove.Step -> error("围棋没有「走子」这种着法")
    }

    override fun decodeMove(code: String): OnlineMove? {
        if (code == "pass") return OnlineMove.Pass
        val parts = code.split(",")
        if (parts.size != 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        return OnlineMove.Place(x, y)
    }

    private fun stoneOf(mover: Int): Stone = if (mover == Stone.BLACK.ordinal) Stone.BLACK else Stone.WHITE

    /**
     * [GoBoard.play] 本身就是"判断 + 落子"的原子操作，虚手 [GoBoard.pass] 永远成功
     * （规则上任何时候都能选择不下）。数子阶段的死子标记不经过这个函数——联机对局里
     * 两边棋盘状态保证完全一致，终局时改用"全部按活子处理"的确定性数子（见
     * [GoScoring]），不需要人工标记，也就不需要在协议里再加一种消息类型来同步它。
     */
    override fun applyIfLegal(state: GoBoard, move: OnlineMove, mover: Int): Boolean {
        val color = stoneOf(mover)
        return when (move) {
            is OnlineMove.Place -> {
                if (!state.inBounds(move.row, move.col)) return false
                state.play(move.row, move.col, color) is PlayResult.Success
            }
            OnlineMove.Pass -> {
                state.pass()
                true
            }
            is OnlineMove.Step -> false
        }
    }
}
