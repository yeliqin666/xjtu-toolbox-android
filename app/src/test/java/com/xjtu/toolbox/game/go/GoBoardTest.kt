package com.xjtu.toolbox.game.go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GoBoard 规则单测：提子、禁自杀、"提子后不算自杀"、superko、连续虚手进数子阶段。
 * 都是纯 Kotlin，跑 JVM 单测不需要起模拟器。
 */
class GoBoardTest {

    @Test
    fun `单子提子`() {
        val b = GoBoard(9)
        // 白子 (2,2) 被黑棋四面围死：上下左右四口气全部落黑子，最后一手完成提子
        b.play(2, 1, Stone.BLACK)
        b.play(1, 2, Stone.BLACK)
        b.play(3, 2, Stone.BLACK)
        b.play(2, 2, Stone.WHITE)
        val result = b.play(2, 3, Stone.BLACK)
        assertTrue(result is PlayResult.Success)
        assertEquals(1, (result as PlayResult.Success).captured)
        assertEquals(Stone.EMPTY, b.stoneAt(2, 2))
    }

    @Test
    fun `整块提子`() {
        val b = GoBoard(9)
        // 白块：(2,2)-(3,2) 两连，黑棋团团围住后最后一手一次提两子
        b.play(2, 2, Stone.WHITE)
        b.play(3, 2, Stone.WHITE)
        b.play(2, 1, Stone.BLACK)
        b.play(3, 1, Stone.BLACK)
        b.play(1, 2, Stone.BLACK)
        b.play(4, 2, Stone.BLACK)
        b.play(2, 3, Stone.BLACK)
        val result = b.play(3, 3, Stone.BLACK) // 收最后一口气
        assertTrue(result is PlayResult.Success)
        assertEquals(2, (result as PlayResult.Success).captured)
        assertEquals(Stone.EMPTY, b.stoneAt(2, 2))
        assertEquals(Stone.EMPTY, b.stoneAt(3, 2))
    }

    @Test
    fun `禁止自杀`() {
        val b = GoBoard(9)
        // 在角上围一个眼：黑棋占 (1,0) (0,1)，白棋点入 (0,0) 送死
        b.play(1, 0, Stone.BLACK)
        b.play(0, 1, Stone.BLACK)
        val result = b.play(0, 0, Stone.WHITE)
        assertEquals(PlayResult.Suicide, result)
        assertEquals(Stone.EMPTY, b.stoneAt(0, 0)) // 非法手要回滚，盘面不能真的落子
    }

    @Test
    fun `提子后不算自杀`() {
        // 最容易写错的顺序敏感场景：黑棋要落子的这个点，四个邻居里有三个是白子、
        // 只有一个邻居是"即将被这手提走"的白子——落子瞬间，如果先判自己有没有气，
        // 黑子这一手看起来是 0 气（四邻居全是白），会被误判为自杀而拒绝；
        // 但正确顺序是先把没气的白块提走，提完之后黑子那个方向就空出一口气了，是合法手。
        val b = GoBoard(9)
        // 白棋单子 S 在 (3,2)，被黑棋从 (3,1)(3,3)(4,2) 三面围住，只剩 (2,2) 一口气；
        // (2,2) 的其余三个邻居 (1,2)(2,1)(2,3) 也都是白子，所以黑棋落 (2,2) 之前，
        // 这个点对黑棋来说唯一可能的气就是"提子之后腾出来的 (3,2)"。
        b.play(2, 1, Stone.WHITE)
        b.play(1, 2, Stone.WHITE)
        b.play(2, 3, Stone.WHITE)
        b.play(3, 2, Stone.WHITE)
        b.play(3, 1, Stone.BLACK)
        b.play(3, 3, Stone.BLACK)
        b.play(4, 2, Stone.BLACK)

        val captureMove = b.play(2, 2, Stone.BLACK)
        assertTrue("提子之后黑子应该有气，不该被判自杀", captureMove is PlayResult.Success)
        assertEquals(1, (captureMove as PlayResult.Success).captured)
        assertEquals(Stone.EMPTY, b.stoneAt(3, 2)) // 白子被提走
        assertEquals(Stone.BLACK, b.stoneAt(2, 2)) // 黑子落下且没有被回滚
    }

    @Test
    fun `superko禁止立即回提`() {
        // 标准单手劫：白子 (3,2) 被黑棋三面围住（(3,1)(3,3)(4,2)），第四口气在 (2,2)；
        // (2,2) 的另外三个邻居 (1,2)(2,1)(2,3) 全是白子，所以黑棋提子落下 (2,2) 之后，
        // 黑子本身只剩"刚提出来的那口气"——如果白棋立刻回提，盘面会和黑棋提子前一模一样。
        val b = GoBoard(9)
        b.play(2, 1, Stone.WHITE)
        b.play(1, 2, Stone.WHITE)
        b.play(2, 3, Stone.WHITE)
        b.play(3, 2, Stone.WHITE)
        b.play(3, 1, Stone.BLACK)
        b.play(3, 3, Stone.BLACK)
        b.play(4, 2, Stone.BLACK)

        val takeKo = b.play(2, 2, Stone.BLACK)
        assertTrue("黑棋应当能提掉 (3,2) 的白子", takeKo is PlayResult.Success)
        assertEquals(1, (takeKo as PlayResult.Success).captured)
        assertEquals(Stone.EMPTY, b.stoneAt(3, 2))

        // 白棋如果立即在 (3,2) 提回黑子 (2,2)，会复现黑棋提子之前的全局局面，必须被 superko 挡住
        val retake = b.play(3, 2, Stone.WHITE)
        assertEquals(PlayResult.Ko, retake)

        // 但劫争不是永久禁止——黑棋在别处先走一手（找"劫材"）之后，白棋再提就合法了，
        // 因为全局局面已经和最初那次不一样（多了黑棋这颗劫材子）。
        b.play(0, 0, Stone.BLACK)
        val retakeAfterKoThreat = b.play(3, 2, Stone.WHITE)
        assertTrue(retakeAfterKoThreat is PlayResult.Success)
    }

    @Test
    fun `连续虚手进入数子阶段`() {
        val state = GoGameState(9)
        assertEquals(GoPhase.PLAYING, state.phase)
        state.pass()
        assertEquals(GoPhase.PLAYING, state.phase)
        state.pass()
        assertEquals(GoPhase.SCORING, state.phase)
    }

    @Test
    fun `已被占用的点不能落子`() {
        val b = GoBoard(9)
        b.play(4, 4, Stone.BLACK)
        val result = b.play(4, 4, Stone.WHITE)
        assertEquals(PlayResult.Occupied, result)
    }

    @Test
    fun `悔棋能撤销落子和提子`() {
        val b = GoBoard(9)
        b.play(2, 1, Stone.BLACK)
        b.play(1, 2, Stone.BLACK)
        b.play(3, 2, Stone.BLACK)
        b.play(2, 2, Stone.WHITE)
        b.play(2, 3, Stone.BLACK) // 提掉白子
        assertEquals(Stone.EMPTY, b.stoneAt(2, 2))
        assertTrue(b.undo())
        assertEquals(Stone.WHITE, b.stoneAt(2, 2)) // 提子被悔棋撤销，白子应该恢复
        assertEquals(Stone.EMPTY, b.stoneAt(2, 3)) // 黑棋这一手本身也被撤销
    }

    @Test
    fun `悔棋后可以重新走到相同局面而不误判superko`() {
        val b = GoBoard(9)
        b.play(0, 0, Stone.BLACK)
        b.undo()
        assertFalse(b.canUndo())
        // 悔棋之后局面回到空盘，再落同一手必须仍然合法（不能被误判成"之前出现过"）
        val result = b.play(0, 0, Stone.BLACK)
        assertTrue(result is PlayResult.Success)
    }
}
