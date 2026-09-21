package com.xjtu.toolbox.game.xiangqi

import com.xjtu.toolbox.game.net.OnlineMove
import com.xjtu.toolbox.game.xiangqi.engine.Side
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiGame
import com.xjtu.toolbox.game.xiangqi.rules.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiangqiOnlineAdapterTest {

    private val adapter = XiangqiOnlineAdapter()

    @Test
    fun `着法编码解码往返`() {
        val move = OnlineMove.Step(1, 9, 2, 7)
        assertEquals(move, adapter.decodeMove(adapter.encodeMove(move)))
    }

    @Test
    fun `解不了的着法文本返回 null`() {
        assertNull(adapter.decodeMove("1,9"))
        assertNull(adapter.decodeMove("乱七八糟"))
    }

    @Test
    fun `合法的开局马步会真的走子`() {
        val game = XiangqiGame()
        val move = OnlineMove.Step(1, 9, 2, 7)
        assertTrue(adapter.applyIfLegal(game, move, Side.RED.ordinal))
        assertEquals(Piece.WMA, game.pieceAt(2, 7))
        assertEquals(Side.BLACK, game.sideToMove)
    }

    @Test
    fun `该红方走棋时黑方发来的着法被拒`() {
        val game = XiangqiGame()
        // 轮到红方，却拿黑方的棋子（mover=BLACK）尝试走子
        val move = OnlineMove.Step(1, 0, 2, 2)
        assertFalse(adapter.applyIfLegal(game, move, Side.BLACK.ordinal))
    }

    @Test
    fun `走不通的棋步（马腿蹩住）被拒`() {
        val game = XiangqiGame()
        // 红方左马正常能走到 (2,7)，但直接尝试跳到不合法的位置应该被拒
        val illegal = OnlineMove.Step(1, 9, 1, 7)
        assertFalse(adapter.applyIfLegal(game, illegal, Side.RED.ordinal))
    }
}
