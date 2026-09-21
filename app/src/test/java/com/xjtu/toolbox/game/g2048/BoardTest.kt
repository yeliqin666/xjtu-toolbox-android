package com.xjtu.toolbox.game.g2048

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {

    /** 4 个相同格子左滑：每格只合并一次，绝不允许 1→1.3→1.7 那样连锁吃掉一整行。 */
    @Test
    fun `一行四个相同格子左滑只两两合并一次`() {
        val row = listOf(0, 0, 0, 0) + List(12) { null }
        val board = Board(row)

        val result = move(board, Direction.LEFT)

        assertTrue(result.moved)
        val expected = listOf(1, 1, null, null) + List(12) { null }
        assertEquals(expected, result.board.cells)
    }

    /** 棋盘填满且任意相邻格都不相同：判负。 */
    @Test
    fun `棋盘填满且无相邻同值时判定无路可走`() {
        // 经典的「棋盘无法合并」构造：每行错开排列，行内、列内相邻值都不同。
        val cells = listOf(
            0, 1, 0, 1,
            1, 0, 1, 0,
            0, 1, 0, 1,
            1, 0, 1, 0,
        )
        val board = Board(cells)

        assertTrue(isGameOver(board))
    }

    @Test
    fun `棋盘填满但仍有相邻同值时不算无路可走`() {
        val cells = listOf(
            0, 1, 0, 1,
            0, 0, 1, 0, // 第 0、1 列相邻同为 0，还能合
            0, 1, 0, 1,
            1, 0, 1, 0,
        )
        val board = Board(cells)

        assertFalse(isGameOver(board))
    }

    /** 两个满级（A+）格子相遇不合并，这是题目明确要求的默认行为。 */
    @Test
    fun `两个满级格子相遇不再合并`() {
        val win = GpaScale.WIN_INDEX
        val row = listOf(win, win, null, null) + List(12) { null }
        val board = Board(row)

        val result = move(board, Direction.LEFT)

        // 两格已经贴在左边界，滑动不产生任何变化——没有合并，也没有"移动"。
        assertFalse(result.moved)
        assertEquals(listOf(win, win, null, null) + List(12) { null }, result.board.cells)
    }

    @Test
    fun `两个满级格子隔着空格相遇仍不合并只是靠拢`() {
        val win = GpaScale.WIN_INDEX
        val row = listOf(win, null, win, null) + List(12) { null }
        val board = Board(row)

        val result = move(board, Direction.LEFT)

        assertTrue(result.moved)
        assertEquals(listOf(win, win, null, null) + List(12) { null }, result.board.cells)
        assertEquals(0, result.scoreGained)
    }

    /** 固定种子下的生成序列必须可复现，方便回归测试和调试。 */
    @Test
    fun `固定种子生成序列可复现`() {
        fun spawnSequence(seed: Long): List<Int?> {
            val random = Random(seed)
            var board = Board()
            val values = mutableListOf<Int?>()
            repeat(5) {
                board = spawn(board, random)
                values.add(board.cells.filterNotNull().lastOrNull())
            }
            return values
        }

        val first = spawnSequence(42L)
        val second = spawnSequence(42L)

        assertEquals(first, second)
    }

    /** 移动后棋盘没有任何变化时，不应该生成新格——不能让贴着墙角的无效滑动也占一次运气。 */
    @Test
    fun `移动无效时不生成新格`() {
        val random = Random(1L)
        // 全部靠左贴边，再往左滑动是无效操作。
        val cells = listOf(
            0, null, null, null,
            1, null, null, null,
            2, null, null, null,
            3, null, null, null,
        )
        val state = GameState(Board(cells))

        val next = applyMove(state, Direction.LEFT, random)

        assertEquals(state.board.cells, next.board.cells)
        assertEquals(state.score, next.score)
    }
}
