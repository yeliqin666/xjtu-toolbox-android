package com.xjtu.toolbox.game.xiangqi.rules

import kotlin.random.Random

/**
 * 棋盘数据（本质就是 FEN 串的表示）。
 *
 * 从 :app 的 `rules/Board.java` 搬过来。`piece` 从 `int[][]` 换成 `Array<IntArray>`
 * （`Zobrist.getZobristFromBoard` 也跟着换签名），初始局面、FEN 解析、走子语义一字未改。
 * 唯一被替换的实现细节是 `Math.random()` → `kotlin.random.Random.nextDouble()`
 * （`randomizePieces` 只给调试用，两者都是「随便摆」）。
 */
class Board {
    var bRedGo: Boolean = true
    var rounds: Int = 1
    var score: Float = 0f

    // 不要直接访问 piece，用 getPieceByPosition —— (x, y) 写反太容易了
    private var piece: Array<IntArray> = defaultBoard()

    constructor() {
        bRedGo = true
        rounds = 1
    }

    constructor(b: Board) {
        this.bRedGo = b.bRedGo
        this.rounds = b.rounds
        this.score = b.score
        this.piece = Array(BOARD_PIECE_HEIGHT) { y -> b.piece[y].copyOf() }
    }

    fun clear() {
        for (y in 0 until BOARD_PIECE_HEIGHT) {
            for (x in 0 until BOARD_PIECE_WIDTH) {
                piece[y][x] = Piece.EMPTY
            }
        }
    }

    fun doMove(move: Move?): Boolean {
        if (move == null) return false

        val start = move.fromPosition
        val end = move.toPosition
        if (!isValidPosition(start) || !isValidPosition(end)) return false

        val p = getPieceByPosition(start!!)
        if (!Piece.isValid(p)) return false

        setPieceByPosition(end!!, p)
        setPieceByPosition(start, Piece.EMPTY)
        bRedGo = !bRedGo
        rounds++
        return true
    }

    fun doMoves(moves: List<Move>?): Boolean {
        if (moves == null) return false

        // 先在副本上试走，任何一步不合法就整批不动
        val clonedBoard = Board(this)
        for (move in moves) {
            if (!clonedBoard.doMove(move)) return false
        }
        for (move in moves) {
            doMove(move)
        }
        return true
    }

    fun doMoveFromString(ucciString: String): Boolean {
        val move = Move(this)
        if (!move.fromUCCIString(ucciString)) return false
        return doMove(move)
    }

    fun doMovesFromUCCIStrings(ucciStrings: List<String>?): Boolean {
        if (ucciStrings == null) return false

        val clonedBoard = Board(this)
        val moves = ArrayList<Move>()
        for (ucciString in ucciStrings) {
            val m = Move(this)
            if (!m.fromUCCIString(ucciString)) return false
            if (!clonedBoard.doMove(m)) return false
            moves.add(m)
        }
        for (move in moves) {
            doMove(move)
        }
        return true
    }

    /** 按坐标落子；位置或棋子编号非法就不动并返回 false。 */
    fun setPieceByPosition(pos: Position?, value: Int): Boolean {
        if (isValidPosition(pos)) {
            if (value == Piece.EMPTY || Piece.isValid(value)) {
                piece[pos!!.y][pos.x] = value
                return true
            }
        }
        return false
    }

    fun setPieceByPosition(x: Int, y: Int, value: Int): Boolean {
        if (x >= 0 && x < BOARD_PIECE_WIDTH && y >= 0 && y < BOARD_PIECE_HEIGHT) {
            if (value == Piece.EMPTY || Piece.isValid(value)) {
                piece[y][x] = value
                return true
            }
        }
        return false
    }

    /** 越界返回 -1（与 Java 版一致）。 */
    fun getPieceByPosition(pos: Position?): Int {
        if (isValidPosition(pos)) {
            return piece[pos!!.y][pos.x]
        }
        return -1
    }

    fun getPieceByPosition(x: Int, y: Int): Int {
        if (x >= 0 && x < BOARD_PIECE_WIDTH && y >= 0 && y < BOARD_PIECE_HEIGHT) {
            return piece[y][x]
        }
        return -1
    }

    /** https://www.xqbase.com/protocol/pgnfen2.htm · https://www.xqbase.com/protocol/cchess_fen.htm */
    fun toFENString(): String {
        var fen = ""
        for (y in 0 until BOARD_PIECE_HEIGHT) {
            var row = ""
            var zeros = 0
            for (x in 0 until BOARD_PIECE_WIDTH) {
                val p = getPieceByPosition(x, y)
                if (p != 0) {
                    if (zeros > 0) {
                        row += zeros
                        zeros = 0
                    }
                    row += Piece.getCharByValue(p)
                } else {
                    zeros++
                }
            }
            if (zeros > 0) {
                row += zeros
            }
            fen += row
            if (y < BOARD_PIECE_HEIGHT - 1) {
                fen += "/"
            }
        }
        return "$fen ${if (bRedGo) "w" else "b"} - - 0 $rounds"
    }

    fun getZobrist(redGo: Boolean): Long = Zobrist.getZobristFromBoard(piece, redGo)

    fun restoreFromFEN(fenString: String?): Boolean {
        var fenString = fenString ?: return false
        fenString = fenString.trim()

        // 补上缺的部分
        if (fenString.endsWith("w") || fenString.endsWith("b")) {
            fenString += " - - 0 1"
        }

        val parts = fenString.split(" ")
        if (parts.size != 6) {
            // 原库在这里打 android.util.Log —— 规则引擎一旦碰 android.*，JVM 单测就跑不起来，
            // 而 FEN 解析失败本来只是「返回 false 让调用方换一条路」，日志不参与判断。
            return false
        }

        val side = parts[1].lowercase()
        if (side == "w" || side == "r") {
            bRedGo = true
        } else if (side == "b") {
            bRedGo = false
        } else {
            return false
        }

        rounds = parts[5].toIntOrNull() ?: return false

        val fen = parts[0]
        var x = 0
        var y = 0
        for (i in fen.indices) {
            val c = fen[i]
            if (c == '/') {
                x = 0
                y++
            } else if (c in '0'..'9') {
                for (k in 0 until (c - '0')) {
                    piece[y][x] = 0
                    x++
                }
            } else {
                piece[y][x] = Piece.getValueByChar(c)
                x++
            }
        }
        return true
    }

    fun randomizePieces() {
        for (y in 0 until BOARD_PIECE_HEIGHT) {
            for (x in 0 until BOARD_PIECE_WIDTH) {
                piece[y][x] = (Random.nextDouble() * 14).toInt()
            }
        }
    }

    companion object {
        const val BOARD_PIECE_WIDTH = 9
        const val BOARD_PIECE_HEIGHT = 10

        fun isValidPosition(pos: Position?): Boolean {
            if (pos == null) return false
            return pos.x >= 0 && pos.x < BOARD_PIECE_WIDTH && pos.y >= 0 && pos.y < BOARD_PIECE_HEIGHT
        }

        private fun defaultBoard(): Array<IntArray> = arrayOf(
            intArrayOf(Piece.BJU, Piece.BMA, Piece.BXIANG, Piece.BSHI, Piece.BJIANG, Piece.BSHI, Piece.BXIANG, Piece.BMA, Piece.BJU),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, Piece.BPAO, 0, 0, 0, 0, 0, Piece.BPAO, 0),
            intArrayOf(Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),

            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING),
            intArrayOf(0, Piece.WPAO, 0, 0, 0, 0, 0, Piece.WPAO, 0),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(Piece.WJU, Piece.WMA, Piece.WXIANG, Piece.WSHI, Piece.WSHUAI, Piece.WSHI, Piece.WXIANG, Piece.WMA, Piece.WJU),
        )
    }
}
