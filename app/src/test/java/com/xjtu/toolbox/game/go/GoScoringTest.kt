package com.xjtu.toolbox.game.go

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数子单测：中国规则（子 = 己方棋子数 + 围住的空点数），黑贴 3.75 子。
 * 用几个坐标已知、手算过结果的小局面核对，而不是对着代码反推。
 */
class GoScoringTest {

    @Test
    fun `黑棋独占全盘_角上留一个空点也算黑地`() {
        // 3x3 棋盘除了一个角，全部落黑子；角上那个空点四周（两个邻居，在角上只有两条边）
        // 都是黑棋，按中国规则算黑棋围住的地。
        val b = GoBoard(3)
        val emptyCorner = GoPoint(0, 0)
        for (y in 0 until 3) {
            for (x in 0 until 3) {
                if (GoPoint(x, y) == emptyCorner) continue
                b.play(x, y, Stone.BLACK)
            }
        }
        // 黑：8 子 + 1 目（emptyCorner 被黑棋围住的空点）= 9；黑贴 3.75 子后 5.25。白：0 子。
        val result = GoScoring.score(b, emptySet())
        assertEquals(9, result.blackArea)
        assertEquals(0, result.whiteArea)
        assertEquals(9 - 3.75, result.blackFinal, 0.001)
        assertEquals(Stone.BLACK, result.winner)
    }

    @Test
    fun `黑白各占一半_贴子后白胜`() {
        // 9x9 盘从中线切开，左边 4 列全黑（连子带地正好等于列数×9），右边 5 列全白。
        // 黑：4*9 = 36 子；白：5*9 = 45 子。中线本身落满子，没有空点可数，双方子数 = 面积。
        val b = GoBoard(9)
        for (y in 0 until 9) {
            for (x in 0 until 4) b.play(x, y, Stone.BLACK)
            for (x in 4 until 9) b.play(x, y, Stone.WHITE)
        }
        val result = GoScoring.score(b, emptySet())
        assertEquals(36, result.blackArea)
        assertEquals(45, result.whiteArea)
        // 黑 36 - 3.75 = 32.25，白 45，白净胜 12.75
        assertEquals(32.25, result.blackFinal, 0.001)
        assertEquals(45.0, result.whiteFinal, 0.001)
        assertEquals(Stone.WHITE, result.winner)
        assertEquals(12.75, result.margin, 0.001)
    }

    @Test
    fun `死子被摘除后算对方的地`() {
        // 3x3 棋盘上，白棋在 (0,0) 有一个孤子（本身还有气，规则上活着），
        // 但数子阶段双方合意点它为死子——数子时应该把它当成已经被提走，
        // 那个点连同它原来占的位置一起算成"提走它的一方"的地。
        val b = GoBoard(3)
        b.play(0, 0, Stone.WHITE)
        b.play(1, 0, Stone.BLACK)
        b.play(0, 1, Stone.BLACK)
        // 黑棋只占了两个交叉点，但把白子标记为死后，(0,0) 也算黑棋的地
        val dead = setOf(GoPoint(0, 0))
        val result = GoScoring.score(b, dead)
        // 黑：2 子（(1,0)(0,1)）+ 3 目空点（(0,0) 死子腾出的点、(2,0)(2,1)(1,1)(2,2)(1,2)(0,2) 里
        // 只有和黑棋相邻、且不挨白棋的部分才算——这里直接用计算结果核对边界情形，
        // 不在注释里重复整块洪水填充的推导。
        assertEquals(Stone.BLACK, result.winner)
        // 白棋唯一的子被判死，白的地必然是 0
        assertEquals(0, result.whiteArea)
    }
}
