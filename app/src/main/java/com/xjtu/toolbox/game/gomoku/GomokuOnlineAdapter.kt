package com.xjtu.toolbox.game.gomoku

import com.xjtu.toolbox.game.net.GameKind
import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.game.net.OnlineRuleAdapter

/**
 * 五子棋接入联机模块：着法就是一个坐标，编码成 "行,列"；合法性直接委托给
 * [GomokuBoard.place]——它本来就是"占用/越界返回 false，否则落子"的原子操作。
 *
 * [mover] 直接传 [GOMOKU_XJTU] / [GOMOKU_SJTU]，跟同屏双人、人机模式用的是同一套常量，
 * 不需要额外映射。
 */
class GomokuOnlineAdapter : OnlineRuleAdapter<GomokuBoard> {
    override val kind: GameKind = GameKind.GOMOKU

    override fun encodeMove(move: OnlineMove): String {
        require(move is OnlineMove.Place) { "五子棋只有落子这一种着法" }
        return "${move.row},${move.col}"
    }

    override fun decodeMove(code: String): OnlineMove? {
        val parts = code.split(",")
        if (parts.size != 2) return null
        val row = parts[0].toIntOrNull() ?: return null
        val col = parts[1].toIntOrNull() ?: return null
        return OnlineMove.Place(row, col)
    }

    override fun applyIfLegal(state: GomokuBoard, move: OnlineMove, mover: Int): Boolean {
        if (move !is OnlineMove.Place) return false
        if (!state.inBounds(move.row, move.col)) return false
        return state.place(move.row, move.col, mover)
    }
}
