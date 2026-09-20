package com.xjtu.toolbox.game.gomoku

/**
 * 五子棋棋盘与规则判定，纯 Kotlin，不 import 任何 android.* / androidx.*——
 * 这样才能脱离设备直接跑 JVM 单测，AI 那边的搜索也不用背着 Compose 的包袱。
 *
 * 无禁手（自由五子棋）：不管黑白，连成五子或更长就算赢，六连也认。
 */
const val GOMOKU_BOARD_SIZE = 15

/** 落子方标识：0 表示空位，其余两个常量对应西交 / 上交。 */
const val GOMOKU_EMPTY = 0
const val GOMOKU_XJTU = 1
const val GOMOKU_SJTU = 2

/** 落子后的判定结果。ONGOING 之外的值都意味着一局已经结束。 */
enum class GomokuOutcome { ONGOING, XJTU_WIN, SJTU_WIN, DRAW }

/** 四个方向的单位向量：横、竖、两条斜线。判五连只需要这四条线，不用扫全部 8 个方向。 */
private val DIRECTIONS = arrayOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

class GomokuBoard(val size: Int = GOMOKU_BOARD_SIZE) {

    private val cells = Array(size) { IntArray(size) }
    private val moveHistory = mutableListOf<Pair<Int, Int>>()

    fun stoneAt(row: Int, col: Int): Int = cells[row][col]

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until size && col in 0 until size

    fun isEmpty(row: Int, col: Int): Boolean = inBounds(row, col) && cells[row][col] == GOMOKU_EMPTY

    fun moveCount(): Int = moveHistory.size

    fun isFull(): Boolean = moveHistory.size >= size * size

    fun lastMove(): Pair<Int, Int>? = moveHistory.lastOrNull()

    /** 落子。位置非法或已被占用时返回 false，调用方不用自己先判一遍。 */
    fun place(row: Int, col: Int, player: Int): Boolean {
        if (!isEmpty(row, col)) return false
        cells[row][col] = player
        moveHistory.add(row to col)
        return true
    }

    /** 悔棋一步，返回被撤销的坐标；没有棋可悔时返回 null。 */
    fun undoLast(): Pair<Int, Int>? {
        val last = moveHistory.removeLastOrNull() ?: return null
        cells[last.first][last.second] = GOMOKU_EMPTY
        return last
    }

    fun clear() {
        for (row in cells) row.fill(GOMOKU_EMPTY)
        moveHistory.clear()
    }

    /** 在 (row, col) 落下 [player] 之后是否形成五连及以上。只查这一颗子经过的四条线，够用也够快。 */
    fun formsWinAt(row: Int, col: Int, player: Int): Boolean = countMaxLineThrough(row, col, player) >= 5

    /** 落子后的整体局面判定：赢/输/平/继续。 */
    fun outcomeAfter(row: Int, col: Int, player: Int): GomokuOutcome {
        if (formsWinAt(row, col, player)) {
            return if (player == GOMOKU_XJTU) GomokuOutcome.XJTU_WIN else GomokuOutcome.SJTU_WIN
        }
        if (isFull()) return GomokuOutcome.DRAW
        return GomokuOutcome.ONGOING
    }

    /** 经过 (row, col) 这颗 [player] 棋子的四条线里，最长的一条连子数。 */
    private fun countMaxLineThrough(row: Int, col: Int, player: Int): Int {
        var best = 0
        for ((dr, dc) in DIRECTIONS) {
            var count = 1
            var r = row + dr
            var c = col + dc
            while (inBounds(r, c) && cells[r][c] == player) {
                count++
                r += dr
                c += dc
            }
            r = row - dr
            c = col - dc
            while (inBounds(r, c) && cells[r][c] == player) {
                count++
                r -= dr
                c -= dc
            }
            if (count > best) best = count
        }
        return best
    }

    /** 供 AI 只读遍历棋盘用，拷贝一份避免外部改到内部状态。 */
    fun snapshotRows(): List<IntArray> = cells.map { it.copyOf() }

    fun opponentOf(player: Int): Int = if (player == GOMOKU_XJTU) GOMOKU_SJTU else GOMOKU_XJTU
}
