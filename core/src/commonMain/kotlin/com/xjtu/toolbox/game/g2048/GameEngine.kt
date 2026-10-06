package com.xjtu.toolbox.game.g2048

import kotlin.random.Random

/**
 * 一局游戏的完整状态：棋盘 + 累计分数 + 是否已经赢过。
 *
 * 「赢过」和「棋盘上还有没有 A+」是两回事——规格要求赢了能继续玩，
 * 所以胜利横幅只在第一次达成时弹一次，之后即使又合出一个 A+ 也不用再提。
 */
data class GameState(
    val board: Board,
    val score: Int = 0,
    val hasWon: Boolean = false,
    val isOver: Boolean = false,
)

/** 生成一局新游戏：空棋盘上先摆两个新格，和标准 2048 的开局一致。 */
fun newGame(random: Random): GameState {
    var board = Board()
    board = spawn(board, random)
    board = spawn(board, random)
    return GameState(board)
}

/**
 * UI 层的唯一入口：滑一下，算出新状态。
 *
 * 没有格子移动时（贴着边界推了个寂寞）不生成新格、不重新判负——
 * 这也是题目单测明确要求的：无效操作不该消耗玩家的运气。
 */
fun applyMove(state: GameState, direction: Direction, random: Random): GameState {
    if (state.isOver) return state
    val result = move(state.board, direction)
    if (!result.moved) return state

    val spawned = spawn(result.board, random)
    val newScore = state.score + result.scoreGained
    val justWon = !state.hasWon && spawned.isWon()
    val over = isGameOver(spawned)
    return state.copy(
        board = spawned,
        score = newScore,
        hasWon = state.hasWon || justWon,
        isOver = over,
    )
}
