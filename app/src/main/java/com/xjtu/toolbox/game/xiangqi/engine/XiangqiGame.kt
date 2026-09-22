package com.xjtu.toolbox.game.xiangqi.engine

import com.xjtu.toolbox.game.xiangqi.rules.Board
import com.xjtu.toolbox.game.xiangqi.rules.Move
import com.xjtu.toolbox.game.xiangqi.rules.Piece
import com.xjtu.toolbox.game.xiangqi.rules.Position
import com.xjtu.toolbox.game.xiangqi.rules.Rule

/**
 * 一局象棋的全部状态。
 *
 * 这个文件替掉了上游 zfdang 的 `Game.java`：那个类把「对局状态」和「存档到 Context 的文件里」
 * 揉在一起，于是整个对局逻辑都得拖着 android.content.Context 走，JVM 单测碰不到。
 * 同屏双人不需要存档，也不需要 AI 的那套 PV/引擎回调，所以这里只留纯逻辑，一个 android.* 都不 import。
 *
 * 只有这一个可变实体，UI 层靠 [snapshot] 拿不可变快照来触发重组，而不是让 Compose 直接读可变 Board。
 */
class XiangqiGame {

    private var board = Board()

    /** 走子历史，存的是**走这步之前**的完整棋盘副本。悔棋直接换回去，比算逆操作（还要记吃了什么子）省事且不会漏。 */
    private val boardHistory = ArrayList<Board>()
    private val moveHistory = ArrayList<MoveRecord>()

    /** 历史局面计数。同一局面（含轮走方）第三次出现时按棋例裁决，见 [judgeRepetition]。 */
    private val repetition = HashMap<Long, Int>()

    /** 每一步走完后的局面哈希：第 0 项是开局局面，第 i 项是第 i 步之后。 */
    private val positionKeys = ArrayList<Long>()

    /** 每一步的性质（将 / 捉 / 闲），和 [moveHistory] 一一对应。 */
    private val moveKinds = ArrayList<MoveKind>()

    private var status: XiangqiStatus = XiangqiStatus.Playing

    init {
        reset()
    }

    /** 回到标准开局。 */
    fun reset() {
        board = Board()
        boardHistory.clear()
        moveHistory.clear()
        repetition.clear()
        positionKeys.clear()
        moveKinds.clear()
        status = XiangqiStatus.Playing
        countCurrentPosition()
    }

    val sideToMove: Side get() = if (board.bRedGo) Side.RED else Side.BLACK

    fun pieceAt(x: Int, y: Int): Int = board.getPieceByPosition(x, y)

    /** 给 UI 画高亮用：上一步的起点和终点。 */
    val lastMove: MoveRecord? get() = moveHistory.lastOrNull()

    fun canUndo(): Boolean = boardHistory.isNotEmpty()

    /**
     * 某个位置的合法落点。已经排掉送将，所以 UI 直接照着画提示点就行，
     * 不会出现「点了发现走不了」这种让人以为是 bug 的情况。
     */
    fun legalTargetsAt(x: Int, y: Int): List<Position> {
        if (status != XiangqiStatus.Playing) return emptyList()
        val piece = board.getPieceByPosition(x, y)
        if (!Piece.isValid(piece)) return emptyList()
        if (sideOf(piece) != sideToMove) return emptyList()
        val side = sideToMove.kingPiece
        return Rule.PossibleToPositions(piece, x, y, board)
            .filter { Rule.isLegalMove(side, Move(Position(x, y), it), board) }
    }

    /** 走一步。非法走法返回 false 并且什么都不改，调用方可以放心当成「这一下点空了」。 */
    fun move(from: Position, to: Position): Boolean {
        if (status != XiangqiStatus.Playing) return false
        val side = sideToMove.kingPiece
        val move = Move(from, to)
        if (!Rule.isLegalMove(side, move, board)) return false

        val piece = board.getPieceByPosition(from)
        val captured = board.getPieceByPosition(to)
        val mover = sideToMove
        val threatsBefore = chaseTargets(board, mover)
        boardHistory.add(Board(board))
        board.setPieceByPosition(to, piece)
        board.setPieceByPosition(from, Piece.EMPTY)
        board.bRedGo = !board.bRedGo
        board.rounds += 1
        moveHistory.add(MoveRecord(from, to, piece, captured))
        moveKinds.add(
            MoveKind(
                check = Rule.isInCheck(mover.opposite.kingPiece, board),
                chase = (chaseTargets(board, mover) - threatsBefore).isNotEmpty(),
            )
        )

        countCurrentPosition()
        updateStatusAfterMove()
        return true
    }

    /**
     * 悔棋：只退一步。
     *
     * 同屏双人是两个人轮流用同一块屏幕，谁刚走错谁伸手点悔棋，退一步正好把手还给他；
     * 人机模式才需要「退两步」把 AI 的应手一起吞掉。这里不做那套。
     */
    fun undo(): Boolean {
        if (boardHistory.isEmpty()) return false
        // 认输 / 提和之后悔棋，等于反悔了那个决定，把终局状态一起撤掉
        decountCurrentPosition()
        board = boardHistory.removeAt(boardHistory.size - 1)
        moveHistory.removeAt(moveHistory.size - 1)
        moveKinds.removeAt(moveKinds.size - 1)
        status = XiangqiStatus.Playing
        return true
    }

    /** 认输。认输方判负。 */
    fun resign(side: Side) {
        if (status != XiangqiStatus.Playing) return
        status = XiangqiStatus.Over(side.opposite, EndReason.RESIGN)
    }

    /** 双方同意和棋。UI 那边需要先问过另一方，这里只负责落状态。 */
    fun agreeDraw() {
        if (status != XiangqiStatus.Playing) return
        status = XiangqiStatus.Over(null, EndReason.AGREED_DRAW)
    }

    fun status(): XiangqiStatus = status

    /** 当前轮走方是否正被将军，UI 用来提示「将军！」。 */
    fun isInCheck(): Boolean = Rule.isInCheck(sideToMove.kingPiece, board)

    fun snapshot(): XiangqiSnapshot = XiangqiSnapshot(
        pieces = List(Board.BOARD_PIECE_HEIGHT) { y ->
            List(Board.BOARD_PIECE_WIDTH) { x -> board.getPieceByPosition(x, y) }
        },
        sideToMove = sideToMove,
        lastMove = lastMove,
        status = status,
        inCheck = status == XiangqiStatus.Playing && isInCheck(),
        canUndo = canUndo(),
    )

    // ── 内部 ──

    private fun zobrist(): Long = board.getZobrist(board.bRedGo)

    private fun countCurrentPosition() {
        val key = zobrist()
        repetition[key] = (repetition[key] ?: 0) + 1
        positionKeys.add(key)
    }

    private fun decountCurrentPosition() {
        positionKeys.removeAt(positionKeys.size - 1)
        val key = zobrist()
        val n = repetition[key] ?: return
        if (n <= 1) repetition.remove(key) else repetition[key] = n - 1
    }

    private fun updateStatusAfterMove() {
        val mover = if (board.bRedGo) Side.BLACK else Side.RED
        val nextSide = sideToMove
        val next = nextSide.kingPiece
        if (!Rule.hasAnyLegalMove(next, board)) {
            // 将死和困毙在中国象棋里都判轮走方负，分开记只是为了给玩家一个说得通的提示
            val reason = if (Rule.isInCheck(next, board)) EndReason.CHECKMATE else EndReason.STALEMATE
            status = XiangqiStatus.Over(mover, reason)
            return
        }
        if ((repetition[zobrist()] ?: 0) >= 3) {
            status = judgeRepetition()
            return
        }
        // 自然限着：双方各走 60 回合没有吃子，判和
        val sinceCapture = moveHistory.asReversed().indexOfFirst { Piece.isValid(it.captured) }
            .let { if (it < 0) moveHistory.size else it }
        if (sinceCapture >= MOVE_LIMIT_PLIES) {
            status = XiangqiStatus.Over(null, EndReason.MOVE_LIMIT)
        }
    }

    /**
     * 循环局面按棋例裁决（「将、捉、闲」分类）：
     * - 一方长将、另一方不是：长将方负；双方都长将：和。
     * - 都不长将时，一方长捉、另一方不是：长捉方负；双方同捉或都是闲着：和。
     *
     * 「长」指循环里这一方的每一步都是将（或都是捉，夹着将也算捉）。
     * 循环取这个局面上一次出现到现在这一段。
     */
    private fun judgeRepetition(): XiangqiStatus {
        val key = positionKeys.last()
        val prev = positionKeys.subList(0, positionKeys.size - 1).lastIndexOf(key)
        // positionKeys[i] 是第 i 步之后，所以循环里的步是 moveKinds[prev ..< 末尾]
        val cycle = moveKinds.subList(prev, moveKinds.size).asReversed()
        val lastMover = if (board.bRedGo) Side.BLACK else Side.RED
        // 倒过来数，偶数下标是刚走完的一方
        val mine = cycle.filterIndexed { i, _ -> i % 2 == 0 }
        val theirs = cycle.filterIndexed { i, _ -> i % 2 == 1 }
        fun List<MoveKind>.always(p: (MoveKind) -> Boolean) = isNotEmpty() && all(p)

        val myCheck = mine.always { it.check }
        val theirCheck = theirs.always { it.check }
        if (myCheck != theirCheck) {
            val winner = if (myCheck) lastMover.opposite else lastMover
            return XiangqiStatus.Over(winner, EndReason.PERPETUAL_CHECK)
        }
        if (!myCheck) {
            val myChase = mine.always { it.chase || it.check }
            val theirChase = theirs.always { it.chase || it.check }
            if (myChase != theirChase) {
                val winner = if (myChase) lastMover.opposite else lastMover
                return XiangqiStatus.Over(winner, EndReason.PERPETUAL_CHASE)
            }
        }
        return XiangqiStatus.Over(null, EndReason.REPETITION)
    }

    companion object {
        /** 60 回合 = 120 步。 */
        const val MOVE_LIMIT_PLIES = 120

        /**
         * [side] 此刻能「捉」的对方棋子位置。
         *
         * 捉 = 下一步能合法吃掉，且目标没有保护，或者吃它的子比它轻（马炮捉车、士象捉车马炮）。
         * 按棋例排除：将帅、兵卒去吃不算捉；没过河的兵卒被攻击不算被捉；攻击将帅是「将」不是「捉」。
         */
        internal fun chaseTargets(board: Board, side: Side): Set<Pair<Int, Int>> {
            val result = HashSet<Pair<Int, Int>>()
            val red = side == Side.RED
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                val attacker = board.getPieceByPosition(x, y)
                if (!Piece.isValid(attacker) || Piece.isRed(attacker) != red || rank(attacker) == 0) continue
                for (to in Rule.PossibleToPositions(attacker, x, y, board)) {
                    val target = board.getPieceByPosition(to)
                    if (!Piece.isValid(target) || Piece.isRed(target) == red) continue
                    if (target == Piece.WSHUAI || target == Piece.BJIANG) continue
                    if (isUncrossedPawn(target, to)) continue
                    val move = Move(Position(x, y), to)
                    if (!Rule.isLegalMove(side.kingPiece, move, board)) continue
                    if (rank(attacker) < rank(target) || !isProtected(board, move, side.opposite)) {
                        result.add(to.x to to.y)
                    }
                }
            }
            return result
        }

        /** 车 3，马炮 2，士象 1；将帅兵卒 0，表示它们去吃不算捉。过河兵卒被捉时按 1 算。 */
        private fun rank(piece: Int): Int = when (piece) {
            Piece.WJU, Piece.BJU -> 3
            Piece.WMA, Piece.BMA, Piece.WPAO, Piece.BPAO -> 2
            Piece.WSHI, Piece.BSHI, Piece.WXIANG, Piece.BXIANG -> 1
            else -> 0
        }

        /** 红方在下（y 5..9 是红方地盘）。 */
        private fun isUncrossedPawn(piece: Int, at: Position): Boolean = when (piece) {
            Piece.WBING -> at.y >= 5
            Piece.BZU -> at.y <= 4
            else -> false
        }

        /** 吃掉之后 [defender] 能不能合法吃回来。 */
        private fun isProtected(board: Board, capture: Move, defender: Side): Boolean {
            val trial = Board(board)
            trial.setPieceByPosition(capture.toPosition, board.getPieceByPosition(capture.fromPosition))
            trial.setPieceByPosition(capture.fromPosition, Piece.EMPTY)
            val dest = capture.toPosition
            val redDef = defender == Side.RED
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                val p = trial.getPieceByPosition(x, y)
                if (!Piece.isValid(p) || Piece.isRed(p) != redDef) continue
                if (Rule.PossibleToPositions(p, x, y, trial).none { it.x == dest.x && it.y == dest.y }) continue
                if (Rule.isLegalMove(defender.kingPiece, Move(Position(x, y), dest), trial)) return true
            }
            return false
        }

        fun sideOf(piece: Int): Side? = when {
            Piece.isRed(piece) -> Side.RED
            Piece.isBlack(piece) -> Side.BLACK
            else -> null
        }

        /** 只给单测 / 残局用：从 FEN 起一局。正常对局走 [reset]。 */
        fun fromFen(fen: String): XiangqiGame? {
            val game = XiangqiGame()
            val b = Board()
            b.clear()
            if (!b.restoreFromFEN(fen)) return null
            game.board = b
            game.boardHistory.clear()
            game.moveHistory.clear()
            game.repetition.clear()
            game.positionKeys.clear()
            game.moveKinds.clear()
            game.status = XiangqiStatus.Playing
            game.countCurrentPosition()
            return game
        }
    }
}

enum class Side {
    RED, BLACK;

    /** 原库用将帅的 pieceId 当阵营标记，这里顺着它，免得两套表示互相转换出错。 */
    val kingPiece: Int get() = if (this == RED) Piece.WSHUAI else Piece.BJIANG
    val opposite: Side get() = if (this == RED) BLACK else RED
}

enum class EndReason {
    CHECKMATE, STALEMATE, RESIGN, AGREED_DRAW,
    /** 循环局面，双方都没犯规（都是闲着，或者同犯），判和。 */
    REPETITION,
    /** 长将判负。 */
    PERPETUAL_CHECK,
    /** 长捉判负。 */
    PERPETUAL_CHASE,
    /** 自然限着：60 回合无吃子，判和。 */
    MOVE_LIMIT,
}

/** 一步棋按棋例的分类：将（走完对方被将军）、捉（新造出一个捉的威胁），都不是就是闲。 */
private data class MoveKind(val check: Boolean, val chase: Boolean)

sealed interface XiangqiStatus {
    data object Playing : XiangqiStatus

    /** [winner] 为 null 表示和棋。 */
    data class Over(val winner: Side?, val reason: EndReason) : XiangqiStatus
}

data class MoveRecord(
    val from: Position,
    val to: Position,
    val piece: Int,
    val captured: Int,
)

/** 给 Compose 用的不可变快照：棋盘是可变对象，直接读它不会触发重组。 */
data class XiangqiSnapshot(
    val pieces: List<List<Int>>,
    val sideToMove: Side,
    val lastMove: MoveRecord?,
    val status: XiangqiStatus,
    val inCheck: Boolean,
    val canUndo: Boolean,
)
