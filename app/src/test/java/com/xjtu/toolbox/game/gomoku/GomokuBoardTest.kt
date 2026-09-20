package com.xjtu.toolbox.game.gomoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuBoardTest {

    @Test
    fun `横向五连判胜`() {
        val board = GomokuBoard()
        for (col in 0..3) board.place(7, col, GOMOKU_XJTU)
        assertFalse(board.formsWinAt(7, 3, GOMOKU_XJTU))
        board.place(7, 4, GOMOKU_XJTU)
        assertTrue(board.formsWinAt(7, 4, GOMOKU_XJTU))
    }

    @Test
    fun `竖向五连判胜`() {
        val board = GomokuBoard()
        for (row in 0..4) board.place(row, 3, GOMOKU_SJTU)
        assertTrue(board.formsWinAt(4, 3, GOMOKU_SJTU))
    }

    @Test
    fun `主对角线五连判胜`() {
        val board = GomokuBoard()
        for (i in 0..4) board.place(i, i, GOMOKU_XJTU)
        assertTrue(board.formsWinAt(4, 4, GOMOKU_XJTU))
    }

    @Test
    fun `副对角线五连判胜`() {
        val board = GomokuBoard()
        for (i in 0..4) board.place(i, 4 - i, GOMOKU_SJTU)
        assertTrue(board.formsWinAt(4, 0, GOMOKU_SJTU))
    }

    @Test
    fun `六连也算赢——自由五子棋无禁手`() {
        val board = GomokuBoard()
        for (col in 0..5) board.place(7, col, GOMOKU_XJTU)
        assertTrue(board.formsWinAt(7, 5, GOMOKU_XJTU))
        assertEquals(GomokuOutcome.XJTU_WIN, board.outcomeAfter(7, 5, GOMOKU_XJTU))
    }

    @Test
    fun `未连成五子不算赢`() {
        val board = GomokuBoard()
        for (col in 0..2) board.place(7, col, GOMOKU_XJTU)
        assertFalse(board.formsWinAt(7, 2, GOMOKU_XJTU))
        assertEquals(GomokuOutcome.ONGOING, board.outcomeAfter(7, 2, GOMOKU_XJTU))
    }

    @Test
    fun `悔棋能撤回最后一步`() {
        val board = GomokuBoard()
        board.place(0, 0, GOMOKU_XJTU)
        board.place(1, 1, GOMOKU_SJTU)
        val undone = board.undoLast()
        assertEquals(1 to 1, undone)
        assertTrue(board.isEmpty(1, 1))
        assertEquals(1, board.moveCount())
    }
}
