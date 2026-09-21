package com.xjtu.toolbox.game.gomoku

import com.xjtu.toolbox.game.net.OnlineMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuOnlineAdapterTest {

    private val adapter = GomokuOnlineAdapter()

    @Test
    fun `着法编码解码往返`() {
        val move = OnlineMove.Place(7, 8)
        val code = adapter.encodeMove(move)
        assertEquals(move, adapter.decodeMove(code))
    }

    @Test
    fun `解不了的着法文本返回 null`() {
        assertNull(adapter.decodeMove("不是坐标"))
        assertNull(adapter.decodeMove("7"))
        assertNull(adapter.decodeMove("7,8,9"))
    }

    @Test
    fun `合法着法应用后棋盘状态推进`() {
        val board = GomokuBoard()
        assertTrue(adapter.applyIfLegal(board, OnlineMove.Place(7, 7), GOMOKU_XJTU))
        assertEquals(GOMOKU_XJTU, board.stoneAt(7, 7))
    }

    @Test
    fun `占用格子的着法被拒`() {
        val board = GomokuBoard()
        board.place(3, 3, GOMOKU_XJTU)
        assertFalse(adapter.applyIfLegal(board, OnlineMove.Place(3, 3), GOMOKU_SJTU))
        // 非法着法不应该改动棋盘
        assertEquals(GOMOKU_XJTU, board.stoneAt(3, 3))
    }

    @Test
    fun `越界的着法被拒`() {
        val board = GomokuBoard()
        assertFalse(adapter.applyIfLegal(board, OnlineMove.Place(-1, 0), GOMOKU_XJTU))
        assertFalse(adapter.applyIfLegal(board, OnlineMove.Place(0, 999), GOMOKU_XJTU))
    }
}
