package com.xjtu.toolbox.game.gomoku

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 「上交 AI」。纯 Kotlin，不碰 android 或 androidx 的任何包，好脱离设备跑 JVM 单测。
 *
 * 评估函数的思路参考了开源五子棋引擎 haslam/blackstone 的 Evaluator——按「一条线上
 * 还差几步能连成五」打分。但它的搜索层（NegamaxPlayer）绑死了仓库里另一个没有开源
 * 出来的 ThreatUtils/Move，剥离成本比重写还高，这里的 negamax + 置换表是重新写的，
 * 不能算「并入」blackstone 的代码，THIRD_PARTY_NOTICES.gomoku.md 里也不这么写。
 *
 * 三档只是「搜多深、给多久」的区别，共用同一套评估与搜索代码：
 * - 简单：不搜索，只看一步的贪心分 + 一点随机，好让新手也能赢。
 * - 困难：候选点裁剪到已有棋子周围 2 格内、按启发分取前 12 个，alpha-beta 搜 4 层。
 * - 地狱：同上但搜 6 层，另外挂一张 Zobrist 置换表省掉重复局面的计算。
 */
enum class GomokuDifficulty(val label: String, val searchDepth: Int, val timeoutMillis: Long) {
    EASY("简单", 0, 0),
    HARD("困难", 4, 1_000),
    HELL("地狱", 6, 3_000),
}

/** 搜索用完给定时间片仍未收敛时抛出，外层拿当前迭代深度里已经算出的最佳着法兜底。 */
private class GomokuSearchTimeout : RuntimeException()

class GomokuAi(private val random: Random = Random.Default) {

    /**
     * 给出 AI 的落子坐标。[deadlineNanos] 为空时不做超时检查——单测里固定局面、不
     * 依赖墙钟时间，传空才能保证「同局面同种子结果可复现」。
     */
    fun findMove(
        board: GomokuBoard,
        aiPlayer: Int,
        difficulty: GomokuDifficulty,
        deadlineNanos: Long? = null,
    ): Pair<Int, Int> {
        val human = board.opponentOf(aiPlayer)

        if (board.moveCount() == 0) {
            val mid = board.size / 2
            return mid to mid
        }

        // 己方能直接成五，不用想别的，直接下。
        winningMoveFor(board, aiPlayer)?.let { return it }

        // 对方已经能成五（活四之类的必胜威胁），必须堵在那个点上，堵不了也只能选一个。
        val mustBlock = winningMoveFor(board, human)
        if (mustBlock != null) return mustBlock

        return when (difficulty) {
            GomokuDifficulty.EASY -> easyMove(board, aiPlayer, human)
            GomokuDifficulty.HARD, GomokuDifficulty.HELL -> searchMove(board, aiPlayer, human, difficulty, deadlineNanos)
        }
    }

    /** 扫一遍空位，找出「己方落子即可连出五连」的那个点；没有就返回 null。 */
    private fun winningMoveFor(board: GomokuBoard, player: Int): Pair<Int, Int>? {
        for (r in 0 until board.size) {
            for (c in 0 until board.size) {
                if (!board.isEmpty(r, c)) continue
                board.place(r, c, player)
                val wins = board.formsWinAt(r, c, player)
                board.undoLast()
                if (wins) return r to c
            }
        }
        return null
    }

    /** 简单档：贪心只看一步，进攻分（自己连子）和防守分（堵对方）取最大，再加点随机噪声。 */
    private fun easyMove(board: GomokuBoard, ai: Int, human: Int): Pair<Int, Int> {
        val candidates = candidateMoves(board, ai)
        var best = candidates.first()
        var bestScore = Int.MIN_VALUE
        for (move in candidates) {
            val offense = pointScore(board, move.first, move.second, ai)
            val defense = pointScore(board, move.first, move.second, human)
            val noise = random.nextInt(0, 20)
            val score = max(offense, defense) + noise
            if (score > bestScore) {
                bestScore = score
                best = move
            }
        }
        return best
    }

    private fun searchMove(
        board: GomokuBoard,
        ai: Int,
        human: Int,
        difficulty: GomokuDifficulty,
        deadlineNanos: Long?,
    ): Pair<Int, Int> {
        val table = if (difficulty == GomokuDifficulty.HELL) HashMap<Long, TranspositionEntry>() else null
        var zobrist = if (table != null) ZobristTable.hashOf(board) else 0L

        var candidates = candidateMoves(board, ai)
        if (candidates.size == 1) return candidates.first()

        var bestMove = candidates.first()
        // 迭代加深：先算浅层，用浅层结果给下一层排序，超时了也有一个可用的答案。
        for (depth in 2..difficulty.searchDepth) {
            try {
                val scored = candidates.map { (r, c) ->
                    board.place(r, c, ai)
                    zobrist = zobrist xor ZobristTable.keyFor(r, c, ai)
                    val score = -negamax(
                        board, human, ai, depth - 1,
                        -SCORE_WIN, SCORE_WIN, table, zobrist, deadlineNanos,
                    )
                    zobrist = zobrist xor ZobristTable.keyFor(r, c, ai)
                    board.undoLast()
                    (r to c) to score
                }.sortedByDescending { it.second }
                bestMove = scored.first().first
                candidates = scored.map { it.first }
            } catch (_: GomokuSearchTimeout) {
                break
            }
        }
        return bestMove
    }

    private fun negamax(
        board: GomokuBoard,
        toMove: Int,
        aiPlayer: Int,
        depth: Int,
        alphaIn: Int,
        beta: Int,
        table: HashMap<Long, TranspositionEntry>?,
        zobrist: Long,
        deadlineNanos: Long?,
    ): Int {
        if (deadlineNanos != null && System.nanoTime() > deadlineNanos) throw GomokuSearchTimeout()

        table?.get(zobrist)?.let { entry ->
            if (entry.depth >= depth) return entry.score
        }

        val last = board.lastMove()
        if (last != null) {
            val lastPlayer = board.opponentOf(toMove)
            if (board.formsWinAt(last.first, last.second, lastPlayer)) {
                return -SCORE_WIN - depth
            }
        }
        if (depth == 0 || board.isFull()) {
            val score = evaluateBoard(board, toMove)
            table?.set(zobrist, TranspositionEntry(depth, score))
            return score
        }

        var alpha = alphaIn
        var best = Int.MIN_VALUE
        val moves = candidateMoves(board, toMove)
        val other = board.opponentOf(toMove)
        var z = zobrist
        for ((r, c) in moves) {
            board.place(r, c, toMove)
            z = z xor ZobristTable.keyFor(r, c, toMove)
            val score = -negamax(board, other, aiPlayer, depth - 1, -beta, -alpha, table, z, deadlineNanos)
            z = z xor ZobristTable.keyFor(r, c, toMove)
            board.undoLast()
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        table?.set(zobrist, TranspositionEntry(depth, best))
        return best
    }

    /** 候选点裁剪：只看已有棋子周围 2 格内的空位，按「己方进攻 + 对方防守」的启发分取前 12 个。 */
    private fun candidateMoves(board: GomokuBoard, forPlayer: Int): List<Pair<Int, Int>> {
        val other = board.opponentOf(forPlayer)
        val scored = mutableListOf<Triple<Int, Int, Int>>()
        for (r in 0 until board.size) {
            for (c in 0 until board.size) {
                if (!board.isEmpty(r, c)) continue
                if (!hasNeighborWithin(board, r, c, 2)) continue
                val score = pointScore(board, r, c, forPlayer) + pointScore(board, r, c, other) / 2
                scored.add(Triple(r, c, score))
            }
        }
        if (scored.isEmpty()) {
            val mid = board.size / 2
            return listOf(mid to mid)
        }
        return scored.sortedByDescending { it.third }.take(MAX_CANDIDATES).map { it.first to it.second }
    }

    private fun hasNeighborWithin(board: GomokuBoard, row: Int, col: Int, distance: Int): Boolean {
        for (dr in -distance..distance) {
            for (dc in -distance..distance) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (board.inBounds(r, c) && board.stoneAt(r, c) != GOMOKU_EMPTY) return true
            }
        }
        return false
    }

    /** 假设在 (row, col) 落下 [player]，这颗子在四个方向上能带来的棋型分——用于候选排序和简单档。 */
    private fun pointScore(board: GomokuBoard, row: Int, col: Int, player: Int): Int {
        var total = 0
        for ((dr, dc) in POINT_DIRECTIONS) {
            total += lineScore(board, row, col, dr, dc, player)
        }
        return total
    }

    private fun lineScore(board: GomokuBoard, row: Int, col: Int, dr: Int, dc: Int, player: Int): Int {
        var count = 1
        var openEnds = 0
        var r = row + dr
        var c = col + dc
        while (board.inBounds(r, c) && board.stoneAt(r, c) == player) { count++; r += dr; c += dc }
        if (board.inBounds(r, c) && board.stoneAt(r, c) == GOMOKU_EMPTY) openEnds++
        r = row - dr
        c = col - dc
        while (board.inBounds(r, c) && board.stoneAt(r, c) == player) { count++; r -= dr; c -= dc }
        if (board.inBounds(r, c) && board.stoneAt(r, c) == GOMOKU_EMPTY) openEnds++
        return patternScore(count, openEnds)
    }

    companion object {
        private const val MAX_CANDIDATES = 12
        const val SCORE_WIN = 1_000_000

        private val POINT_DIRECTIONS = arrayOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

        /** 静态局面评估：己方棋型总分减对方棋型总分，从 [forPlayer] 视角看。 */
        fun evaluateBoard(board: GomokuBoard, forPlayer: Int): Int {
            val other = board.opponentOf(forPlayer)
            return playerLineScore(board, forPlayer) - playerLineScore(board, other)
        }

        private fun playerLineScore(board: GomokuBoard, player: Int): Int {
            var total = 0
            for (r in 0 until board.size) {
                for (c in 0 until board.size) {
                    if (board.stoneAt(r, c) != player) continue
                    for ((dr, dc) in POINT_DIRECTIONS) {
                        // 只在「这条线的起点」计一次分，避免同一段连子被四个方向重复数。
                        val pr = r - dr
                        val pc = c - dc
                        if (board.inBounds(pr, pc) && board.stoneAt(pr, pc) == player) continue
                        var count = 1
                        var rr = r + dr
                        var cc = c + dc
                        while (board.inBounds(rr, cc) && board.stoneAt(rr, cc) == player) { count++; rr += dr; cc += dc }
                        var openEnds = 0
                        if (board.inBounds(pr, pc) && board.stoneAt(pr, pc) == GOMOKU_EMPTY) openEnds++
                        if (board.inBounds(rr, cc) && board.stoneAt(rr, cc) == GOMOKU_EMPTY) openEnds++
                        total += patternScore(count, openEnds)
                    }
                }
            }
            return total
        }

        /** 棋型 -> 分数表：活四 / 冲四 / 活三 / 眠三 / 活二…数值差距拉大，好让搜索优先堵必杀。 */
        private fun patternScore(length: Int, openEnds: Int): Int {
            return when {
                length >= 5 -> 100_000
                length == 4 && openEnds == 2 -> 50_000 // 活四：两头都能补成五，基本等于赢
                length == 4 && openEnds == 1 -> 5_000  // 冲四：只能堵一边
                length == 3 && openEnds == 2 -> 5_000  // 活三：不堵就会变活四
                length == 3 && openEnds == 1 -> 500    // 眠三
                length == 2 && openEnds == 2 -> 500    // 活二
                length == 2 && openEnds == 1 -> 50
                length == 1 && openEnds == 2 -> 10
                else -> 0
            }
        }
    }
}

private data class TranspositionEntry(val depth: Int, val score: Int)

/**
 * Zobrist 哈希表：每个 (格子, 落子方) 对应一个固定随机数，异或起来代表整个局面。
 * 键用固定种子生成——它只是哈希用的“盐”，不需要和调用方传进来的 [Random] 绑在一起，
 * 这样置换表在多局之间可以复用，也不影响 AI 落子结果的可复现性。
 */
private object ZobristTable {
    private const val SIZE = GOMOKU_BOARD_SIZE
    private val keys = Array(SIZE) { r -> Array(SIZE) { c -> LongArray(3) } }

    init {
        val rng = Random(0x5A5AC0DE)
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                keys[r][c][GOMOKU_XJTU] = rng.nextLong()
                keys[r][c][GOMOKU_SJTU] = rng.nextLong()
            }
        }
    }

    fun keyFor(row: Int, col: Int, player: Int): Long = keys[row][col][player]

    fun hashOf(board: GomokuBoard): Long {
        var hash = 0L
        val rows = board.snapshotRows()
        for (r in rows.indices) {
            for (c in rows[r].indices) {
                val stone = rows[r][c]
                if (stone != GOMOKU_EMPTY) hash = hash xor keyFor(r, c, stone)
            }
        }
        return hash
    }
}
