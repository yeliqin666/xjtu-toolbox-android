package com.xjtu.toolbox.game.go

import com.xjtu.toolbox.game.net.OnlineMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoOnlineAdapterTest {

    private val adapter = GoOnlineAdapter()

    @Test
    fun `落子着法编码解码往返`() {
        val move = OnlineMove.Place(2, 3)
        assertEquals(move, adapter.decodeMove(adapter.encodeMove(move)))
    }

    @Test
    fun `虚手着法编码解码往返`() {
        assertEquals(OnlineMove.Pass, adapter.decodeMove(adapter.encodeMove(OnlineMove.Pass)))
        assertEquals("pass", adapter.encodeMove(OnlineMove.Pass))
    }

    @Test
    fun `解不了的着法文本返回 null`() {
        assertNull(adapter.decodeMove("乱七八糟"))
    }

    @Test
    fun `合法落子会真的落到棋盘上`() {
        val board = GoBoard(9)
        assertTrue(adapter.applyIfLegal(board, OnlineMove.Place(4, 4), Stone.BLACK.ordinal))
        assertEquals(Stone.BLACK, board.stoneAt(4, 4))
    }

    @Test
    fun `自杀这种非法着法被拒`() {
        val board = GoBoard(9)
        // 围死角上一个点，让黑棋在这个点自杀
        adapter.applyIfLegal(board, OnlineMove.Place(1, 0), Stone.WHITE.ordinal)
        adapter.applyIfLegal(board, OnlineMove.Place(0, 1), Stone.WHITE.ordinal)
        val before = board.snapshotCells()
        assertFalse(adapter.applyIfLegal(board, OnlineMove.Place(0, 0), Stone.BLACK.ordinal))
        assertEquals(before, board.snapshotCells())
    }

    @Test
    fun `虚手永远合法`() {
        val board = GoBoard(9)
        assertTrue(adapter.applyIfLegal(board, OnlineMove.Pass, Stone.BLACK.ordinal))
        assertEquals(1, board.consecutivePasses)
    }
}
