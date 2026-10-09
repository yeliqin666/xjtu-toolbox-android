package com.xjtu.toolbox.game.g2048

import kotlin.random.Random

/**
 * GPA 2048 的纯规则引擎。
 *
 * 不 import 任何 android.* / androidx.*，UI 层只管渲染这里算出来的结果——
 * 这样单测不用起 Android 环境就能把移动、合并、判负全跑一遍，也方便以后
 * 万一要挪去别的游戏合集复用。
 *
 * 格子用「等级序号」（对应 [GpaScale.LEVELS] 的下标）表示，`null` 是空格。
 * 每条边长 4，共 16 格，按行优先存成一维数组。
 */

const val BOARD_SIZE = 4

enum class Direction { LEFT, RIGHT, UP, DOWN }

/** 每次合并记一次分：新格的等级序号 × 系数，越高级的合并越值钱。 */
const val SCORE_COEFFICIENT = 10

data class Board(val cells: List<Int?> = List(BOARD_SIZE * BOARD_SIZE) { null }) {

    init {
        require(cells.size == BOARD_SIZE * BOARD_SIZE) { "棋盘必须是 4x4，实际 ${cells.size} 格" }
    }

    fun get(row: Int, col: Int): Int? = cells[row * BOARD_SIZE + col]

    fun emptyIndices(): List<Int> = cells.indices.filter { cells[it] == null }

    /** 盘面当前最高等级序号，空盘返回 null。 */
    fun highestIndex(): Int? = cells.filterNotNull().maxOrNull()

    fun isWon(): Boolean = cells.any { it == GpaScale.WIN_INDEX }
}

/** 一次移动的结果：新棋盘、是否真的发生了移动（决定要不要生成新格）、这次移动拿到的分。 */
data class MoveResult(val board: Board, val moved: Boolean, val scoreGained: Int)

/**
 * 沿 [direction] 移动并合并一次。
 *
 * 每一格在一次移动里只参与一次合并——`[1,1,1,1]` 左滑是 `[2,2,_,_]` 而不是连锁成
 * `[4,_,_,_]`，这是标准 2048 的规则，也是题目单测里明确要求的行为。
 */
fun move(board: Board, direction: Direction): MoveResult {
    val lines = extractLines(board, direction)
    var moved = false
    var scoreGained = 0
    val mergedLines = lines.map { line ->
        val (mergedLine, lineMoved, lineScore) = mergeLine(line)
        if (lineMoved) moved = true
        scoreGained += lineScore
        mergedLine
    }
    val newBoard = if (moved) buildBoard(mergedLines, direction) else board
    return MoveResult(newBoard, moved, scoreGained)
}

/**
 * 把整个棋盘按移动方向拆成 4 条「即将被压缩」的线，且每条线的第一个元素是压缩的目标端。
 * 例如向左移动时每行本身就是一条线；向右移动时把行反过来，这样通用的 [mergeLine]
 * 不用关心方向，只管「往列表头部压」。
 */
private fun extractLines(board: Board, direction: Direction): List<List<Int?>> {
    val rows = (0 until BOARD_SIZE).map { r -> (0 until BOARD_SIZE).map { c -> board.get(r, c) } }
    return when (direction) {
        Direction.LEFT -> rows
        Direction.RIGHT -> rows.map { it.reversed() }
        Direction.UP -> (0 until BOARD_SIZE).map { c -> (0 until BOARD_SIZE).map { r -> board.get(r, c) } }
        Direction.DOWN -> (0 until BOARD_SIZE).map { c -> (0 until BOARD_SIZE).map { r -> board.get(r, c) }.reversed() }
    }
}

/** 把合并后的 4 条线还原回棋盘，方向和 [extractLines] 对称。 */
private fun buildBoard(lines: List<List<Int?>>, direction: Direction): Board {
    val cells = MutableList<Int?>(BOARD_SIZE * BOARD_SIZE) { null }
    when (direction) {
        Direction.LEFT -> lines.forEachIndexed { r, line -> line.forEachIndexed { c, v -> cells[r * BOARD_SIZE + c] = v } }
        Direction.RIGHT -> lines.forEachIndexed { r, line -> line.reversed().forEachIndexed { c, v -> cells[r * BOARD_SIZE + c] = v } }
        Direction.UP -> lines.forEachIndexed { c, line -> line.forEachIndexed { r, v -> cells[r * BOARD_SIZE + c] = v } }
        Direction.DOWN -> lines.forEachIndexed { c, line -> line.reversed().forEachIndexed { r, v -> cells[r * BOARD_SIZE + c] = v } }
    }
    return Board(cells)
}

private data class LineMergeResult(val line: List<Int?>, val moved: Boolean, val scoreGained: Int)

/**
 * 单条 4 格线的压缩 + 合并，目标是把非空格全部挤到列表头部。
 * 满级（[GpaScale.WIN_INDEX]）不参与合并，两个 A+ 相遇就只是并排放着。
 */
private fun mergeLine(line: List<Int?>): LineMergeResult {
    val compact = line.filterNotNull()
    val result = mutableListOf<Int>()
    var scoreGained = 0
    var i = 0
    while (i < compact.size) {
        val current = compact[i]
        val next = compact.getOrNull(i + 1)
        if (next != null && next == current && current != GpaScale.WIN_INDEX) {
            val merged = current + 1
            result.add(merged)
            scoreGained += merged * SCORE_COEFFICIENT
            i += 2
        } else {
            result.add(current)
            i += 1
        }
    }
    val padded: List<Int?> = result + List(BOARD_SIZE - result.size) { null }
    val moved = padded != line
    return LineMergeResult(padded, moved, scoreGained)
}

/** 一次移动里某一格的去向：[from]/[to] 是一维下标；[merged] 表示它和另一格合成了。 */
data class TileMove(val from: Int, val to: Int, val merged: Boolean)

/**
 * 和 [move] 同一套规则，只是不产出新棋盘，而是记下每个非空格滑到了哪——UI 做滑动动画用。
 * 合成的两格 [TileMove.to] 相同，排在前面的那个（离目标端近的）是留下来的。
 */
fun traceMove(board: Board, direction: Direction): List<TileMove> {
    val moves = mutableListOf<TileMove>()
    for (k in 0 until BOARD_SIZE) {
        val positions = linePositions(k, direction)
        val occupied = positions.filter { board.cells[it] != null }
        var target = 0
        var i = 0
        while (i < occupied.size) {
            val current = board.cells[occupied[i]]
            val next = occupied.getOrNull(i + 1)?.let { board.cells[it] }
            if (next != null && next == current && current != GpaScale.WIN_INDEX) {
                moves += TileMove(occupied[i], positions[target], merged = true)
                moves += TileMove(occupied[i + 1], positions[target], merged = true)
                i += 2
            } else {
                moves += TileMove(occupied[i], positions[target], merged = false)
                i += 1
            }
            target++
        }
    }
    return moves
}

/** 第 [k] 条线上的格子下标，从压缩的目标端开始排，和 [extractLines] 一致。 */
private fun linePositions(k: Int, direction: Direction): List<Int> = when (direction) {
    Direction.LEFT -> (0 until BOARD_SIZE).map { c -> k * BOARD_SIZE + c }
    Direction.RIGHT -> (0 until BOARD_SIZE).map { c -> k * BOARD_SIZE + (BOARD_SIZE - 1 - c) }
    Direction.UP -> (0 until BOARD_SIZE).map { r -> r * BOARD_SIZE + k }
    Direction.DOWN -> (0 until BOARD_SIZE).map { r -> (BOARD_SIZE - 1 - r) * BOARD_SIZE + k }
}

/**
 * 在随机一个空格生成新格：90% 是一级（F），10% 是二级（D）。
 * 没有空格时原样返回——调用方应该先判断 [Board.emptyIndices] 是否为空。
 */
fun spawn(board: Board, random: Random): Board {
    val empties = board.emptyIndices()
    if (empties.isEmpty()) return board
    val index = empties[random.nextInt(empties.size)]
    val newValue = if (random.nextDouble() < GpaScale.SPAWN_INDEX_1_PROBABILITY) {
        GpaScale.SPAWN_INDEX_1
    } else {
        GpaScale.SPAWN_INDEX_0
    }
    val cells = board.cells.toMutableList()
    cells[index] = newValue
    return Board(cells)
}

/**
 * 判定「无路可走」：棋盘满了，且没有任何一对相邻同值格子还能合并
 * （满级格子彼此相邻也不算能合并，规则和 [mergeLine] 保持一致）。
 */
fun isGameOver(board: Board): Boolean {
    if (board.emptyIndices().isNotEmpty()) return false
    for (r in 0 until BOARD_SIZE) {
        for (c in 0 until BOARD_SIZE) {
            val v = board.get(r, c) ?: continue
            if (v == GpaScale.WIN_INDEX) continue
            val right = if (c + 1 < BOARD_SIZE) board.get(r, c + 1) else null
            val down = if (r + 1 < BOARD_SIZE) board.get(r + 1, c) else null
            if (right == v || down == v) return false
        }
    }
    return true
}
