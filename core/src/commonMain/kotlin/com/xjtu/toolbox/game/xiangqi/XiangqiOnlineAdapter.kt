package com.xjtu.toolbox.game.xiangqi

import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.game.net.OnlineRuleAdapter
import com.xjtu.toolbox.game.xiangqi.engine.Side
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiGame
import com.xjtu.toolbox.game.xiangqi.rules.Position

/**
 * 象棋接入联机模块。着法是"从一点走到另一点"，编码成 "fx,fy-tx,ty"。
 *
 * [mover] 传 [Side.RED] / [Side.BLACK] 的 ordinal，合法性直接委托给
 * [XiangqiGame.move]——它内部已经调用 `Rule.isLegalMove` 排掉了送将，联机这边
 * 不用重复实现一遍。
 */
class XiangqiOnlineAdapter : OnlineRuleAdapter<XiangqiGame> {
    override val kind: GameKind = GameKind.XIANGQI

    override fun encodeMove(move: OnlineMove): String {
        require(move is OnlineMove.Step) { "象棋只有「走子」这一种着法" }
        return "${move.fromRow},${move.fromCol}-${move.toRow},${move.toCol}"
    }

    override fun decodeMove(code: String): OnlineMove? {
        val parts = code.split("-")
        if (parts.size != 2) return null
        val from = parts[0].split(",")
        val to = parts[1].split(",")
        if (from.size != 2 || to.size != 2) return null
        val fx = from[0].toIntOrNull() ?: return null
        val fy = from[1].toIntOrNull() ?: return null
        val tx = to[0].toIntOrNull() ?: return null
        val ty = to[1].toIntOrNull() ?: return null
        return OnlineMove.Step(fx, fy, tx, ty)
    }

    override fun applyIfLegal(state: XiangqiGame, move: OnlineMove, mover: Int): Boolean {
        if (move !is OnlineMove.Step) return false
        val side = if (mover == Side.RED.ordinal) Side.RED else Side.BLACK
        if (state.sideToMove != side) return false
        return state.move(Position(move.fromRow, move.fromCol), Position(move.toRow, move.toCol))
    }
}
