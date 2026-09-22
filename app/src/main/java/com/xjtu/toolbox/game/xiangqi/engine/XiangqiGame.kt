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

    /**
     * 历史局面计数，用于三次重复局面判和。
     *
     * 注意：这是**简化规则**，不是中国象棋完整棋例里的长将 / 长捉判负。
     * 完整棋例要分辨「将、捉、闲」并判长将一方负，实现代价远大于同屏双人自娱自乐的收益；
     * 这里退一步：同一局面（含轮走方）出现三次就判和，至少保证长将不会让对局永远走不完。
     */
    private val repetition = HashMap<Long, Int>()

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
        boardHistory.add(Board(board))
        board.setPieceByPosition(to, piece)
        board.setPieceByPosition(from, Piece.EMPTY)
        board.bRedGo = !board.bRedGo
        board.rounds += 1
        moveHistory.add(MoveRecord(from, to, piece, captured))

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
    }

    private fun decountCurrentPosition() {
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
            status = XiangqiStatus.Over(null, EndReason.REPETITION)
        }
    }

    companion object {
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

enum class EndReason { CHECKMATE, STALEMATE, RESIGN, AGREED_DRAW, REPETITION }

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
