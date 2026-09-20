package com.xjtu.toolbox.game.xiangqi

import com.xjtu.toolbox.game.xiangqi.engine.EndReason
import com.xjtu.toolbox.game.xiangqi.engine.Side
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiGame
import com.xjtu.toolbox.game.xiangqi.engine.XiangqiStatus
import com.xjtu.toolbox.game.xiangqi.rules.Piece
import com.xjtu.toolbox.game.xiangqi.rules.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对局封装层：轮走、悔棋、认输提和，以及三次重复局面判和。 */
class XiangqiGameTest {

    private fun pos(x: Int, y: Int) = Position(x, y)

    @Test
    fun `开局红先且黑子点不动`() {
        val g = XiangqiGame()
        assertEquals(Side.RED, g.sideToMove)
        assertTrue(g.legalTargetsAt(1, 0).isEmpty())
        assertTrue(g.legalTargetsAt(1, 9).isNotEmpty())
        assertFalse(g.move(pos(1, 0), pos(2, 2)))
    }

    @Test
    fun `走子之后轮到对方并记下上一步`() {
        val g = XiangqiGame()
        assertTrue(g.move(pos(1, 9), pos(2, 7)))
        assertEquals(Side.BLACK, g.sideToMove)
        assertEquals(Piece.WMA, g.pieceAt(2, 7))
        assertEquals(Piece.EMPTY, g.pieceAt(1, 9))
        assertEquals(pos(2, 7), g.lastMove?.to)
    }

    @Test
    fun `悔棋退一步并把手还给刚走的那方`() {
        val g = XiangqiGame()
        assertFalse(g.canUndo())
        g.move(pos(1, 9), pos(2, 7))
        assertTrue(g.canUndo())
        assertTrue(g.undo())
        assertEquals(Side.RED, g.sideToMove)
        assertEquals(Piece.WMA, g.pieceAt(1, 9))
        assertEquals(Piece.EMPTY, g.pieceAt(2, 7))
        assertFalse(g.canUndo())
    }

    @Test
    fun `认输的一方判负`() {
        val g = XiangqiGame()
        g.resign(Side.RED)
        assertEquals(XiangqiStatus.Over(Side.BLACK, EndReason.RESIGN), g.status())
        // 结束之后不能再走子
        assertFalse(g.move(pos(1, 9), pos(2, 7)))
    }

    @Test
    fun `提和落成和棋`() {
        val g = XiangqiGame()
        g.agreeDraw()
        assertEquals(XiangqiStatus.Over(null, EndReason.AGREED_DRAW), g.status())
    }

    @Test
    fun `同一局面出现三次判和`() {
        val g = XiangqiGame()
        // 双方各把一只马来回跳，两轮之后开局局面第三次出现
        repeat(2) {
            assertTrue(g.move(pos(1, 9), pos(2, 7)))
            assertTrue(g.move(pos(1, 0), pos(2, 2)))
            assertTrue(g.move(pos(2, 7), pos(1, 9)))
            assertTrue(g.move(pos(2, 2), pos(1, 0)))
        }
        assertEquals(XiangqiStatus.Over(null, EndReason.REPETITION), g.status())
    }

    @Test
    fun `只重复两次还不判和`() {
        val g = XiangqiGame()
        g.move(pos(1, 9), pos(2, 7))
        g.move(pos(1, 0), pos(2, 2))
        g.move(pos(2, 7), pos(1, 9))
        g.move(pos(2, 2), pos(1, 0))
        assertEquals(XiangqiStatus.Playing, g.status())
    }

    /** 黑将 (4,0)，红车封住第 1 行，另一只车从第 5 行直落第 0 行成杀。 */
    private val mateInOne = "4k4/R8/9/9/9/8R/9/9/9/3K5 w"

    @Test
    fun `没成杀的一步之后对局继续`() {
        val g = XiangqiGame.fromFen(mateInOne)!!
        assertTrue(g.move(pos(3, 9), pos(3, 8)))
        assertEquals(XiangqiStatus.Playing, g.status())
        assertEquals(Side.BLACK, g.sideToMove)
    }

    @Test
    fun `将死判红方胜`() {
        val g = XiangqiGame.fromFen(mateInOne)!!
        assertTrue(g.move(pos(8, 5), pos(8, 0)))
        assertEquals(XiangqiStatus.Over(Side.RED, EndReason.CHECKMATE), g.status())
    }

    @Test
    fun `困毙同样判轮走方负`() {
        // 红兵拱到 (5,1) 之后，黑将三个去处全被两个兵盯住，却没有一处是将军 —— 困毙
        val g = XiangqiGame.fromFen("4k4/3P5/5P3/9/9/9/9/9/9/5K3 w")!!
        assertTrue(g.move(pos(5, 2), pos(5, 1)))
        assertEquals(XiangqiStatus.Over(Side.RED, EndReason.STALEMATE), g.status())
    }
}
