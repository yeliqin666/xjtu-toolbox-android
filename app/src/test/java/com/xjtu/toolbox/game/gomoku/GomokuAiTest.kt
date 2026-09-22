package com.xjtu.toolbox.game.gomoku

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class GomokuAiTest {

    @Test
    fun `对方已成冲四时AI必须堵`() {
        // 上交（对手）靠着棋盘左边界连了 4 颗，只有右边一端能补成五——
        // 这是唯一必堵的点，不堵下一步就输。（两端都空的活四本来就没法一步堵死，不拿来测。）
        val board = GomokuBoard()
        for (col in 0..3) board.place(7, col, GOMOKU_SJTU)
        val ai = GomokuAi(Random(1))

        val move = ai.findMove(board, aiPlayer = GOMOKU_XJTU, difficulty = GomokuDifficulty.HARD)

        assertEquals(7 to 4, move)
    }

    @Test
    fun `己方可成五时AI必须直接成五`() {
        // AI（西交，充当己方）已经有 4 颗连续棋子，落在第 5 格即可成五。
        val board = GomokuBoard()
        for (col in 2..5) board.place(3, col, GOMOKU_XJTU)
        val ai = GomokuAi(Random(1))

        val move = ai.findMove(board, aiPlayer = GOMOKU_XJTU, difficulty = GomokuDifficulty.HARD)
        board.place(move.first, move.second, GOMOKU_XJTU)

        assertEquals(true, board.formsWinAt(move.first, move.second, GOMOKU_XJTU))
    }

    @Test
    fun `固定局面加固定种子下AI结果可复现`() {
        fun freshBoard(): GomokuBoard {
            val board = GomokuBoard()
            board.place(7, 7, GOMOKU_XJTU)
            board.place(7, 8, GOMOKU_SJTU)
            board.place(8, 7, GOMOKU_XJTU)
            board.place(6, 6, GOMOKU_SJTU)
            return board
        }

        val move1 = GomokuAi(Random(42)).findMove(freshBoard(), GOMOKU_XJTU, GomokuDifficulty.HARD)
        val move2 = GomokuAi(Random(42)).findMove(freshBoard(), GOMOKU_XJTU, GomokuDifficulty.HARD)

        assertEquals(move1, move2)
    }

    @Test
    fun `简单档也能在必胜点直接成五`() {
        val board = GomokuBoard()
        for (col in 2..5) board.place(3, col, GOMOKU_XJTU)
        val ai = GomokuAi(Random(7))

        val move = ai.findMove(board, aiPlayer = GOMOKU_XJTU, difficulty = GomokuDifficulty.EASY)
        board.place(move.first, move.second, GOMOKU_XJTU)

        assertEquals(true, board.formsWinAt(move.first, move.second, GOMOKU_XJTU))
    }
}
