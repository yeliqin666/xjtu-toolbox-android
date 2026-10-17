package com.xjtu.toolbox.game.xiangqi.rules

import kotlin.math.abs

/** 一步棋：起点、终点、（可选的）棋盘与走子前的兵种。 */
class Move {
    var board: Board? = null

    var fromPosition: Position? = null
    var toPosition: Position? = null
    var piece: Int = 0

    var comment: String? = null
        set(value) {
            var v = value
            if (v != null) {
                v = v.trim()
                v = v.replace("&nbsp;", " ")
            }
            field = v
        }

    constructor(fromPosition: Position, toPosition: Position, board: Board) {
        this.fromPosition = fromPosition
        this.toPosition = toPosition
        this.board = board
        updatePieceInfo()
    }

    constructor(fromPosition: Position, toPosition: Position) {
        this.fromPosition = fromPosition
        this.toPosition = toPosition
    }

    constructor(board: Board) {
        this.board = Board(board)
    }


    fun setPositions(fromPosition: Position, toPosition: Position) {
        this.fromPosition = fromPosition
        this.toPosition = toPosition
        updatePieceInfo()
    }

    private fun updatePieceInfo() {
        val b = board ?: return
        val from = fromPosition ?: return
        if (toPosition == null) return
        piece = b.getPieceByPosition(from)
    }

    /** 形如 `h2e2` 的 UCCI 着法串；长度不是 4 或坐标越界就返回 false 并且不改本对象。 */
    fun fromUCCIString(ucciString: String?): Boolean {
        if (ucciString == null || ucciString.length != 4) return false

        val s = ucciString[0]
        val e = ucciString[2]
        var pos = Position(s - 'a', 9 - (ucciString[1] - '0'))
        if (!Board.isValidPosition(pos)) return false
        fromPosition = pos
        pos = Position(e - 'a', 9 - (ucciString[3] - '0'))
        if (!Board.isValidPosition(pos)) return false
        toPosition = pos

        updatePieceInfo()
        return true
    }

    /** h2e2 */
    fun getUCCIString(): String {
        val from = fromPosition!!
        val to = toPosition!!
        val s = 'a' + from.x
        val e = 'a' + to.x
        return "$s${9 - from.y}$e${9 - to.y}"
    }

    /** 从一个位置移动到另一个位置的中文描述，例如：车一进六、炮七退七、相七进九、帅四平五。 */
    fun getChsString(): String {
        val b = board ?: return "未知动作"
        val piece = b.getPieceByPosition(fromPosition!!)
        if (Piece.isValid(piece)) {
            val name = Piece.getNameByValue(piece)
            val from = fromPosition!!
            val to = toPosition!!
            var num1: String
            var action: String
            var num2: String

            if (Piece.isRed(piece)) {
                num1 = arabicToChineseMap[9 - from.x].toString()

                action = "平"
                num2 = arabicToChineseMap[abs(to.y - from.y)].toString()
                if (to.y > from.y) {
                    action = "退"
                } else if (to.y < from.y) {
                    action = "进"
                } else {
                    num2 = arabicToChineseMap[9 - to.x].toString()
                }

                if (Piece.isDiagonalPiece(piece)) {
                    num2 = arabicToChineseMap[9 - to.x].toString()
                }

                val multiple = findPiecesOnSameVLine(piece, from)
                return if (multiple == null) {
                    "$name$num1$action$num2"
                } else {
                    "$multiple$name$action$num2"
                }
            } else if (Piece.isBlack(piece)) {
                num1 = "${from.x + 1}"

                action = "平"
                num2 = "${abs(to.y - from.y)}"
                if (to.y > from.y) {
                    action = "进"
                } else if (to.y < from.y) {
                    action = "退"
                } else {
                    num2 = "${to.x + 1}"
                }

                if (Piece.isDiagonalPiece(piece)) {
                    num2 = "${to.x + 1}"
                }

                val multiple = findPiecesOnSameVLine(piece, from)
                return if (multiple == null) {
                    "$name$num1$action$num2"
                } else {
                    "$multiple$name$action$num2"
                }
            }
        }
        return "未知动作"
    }

    /** 同一纵线上的同种棋子，用「前/中/后」或「一/二/三」区分。 */
    private fun findPiecesOnSameVLine(piece: Int, pos: Position): String? {
        val b = board ?: return null
        if (piece == Piece.BJIANG || piece == Piece.BXIANG || piece == Piece.BSHI ||
            piece == Piece.WSHUAI || piece == Piece.WXIANG || piece == Piece.WSHI
        ) {
            // 仕(士)和相(象)如果在同一纵线上，不用「前」和「后」区别，因为能退的一定在前，能进的一定在后
            return null
        }

        val start: Int
        val end: Int
        val step: Int
        if (Piece.isRed(piece)) {
            start = 0
            end = 10
            step = 1
        } else {
            start = 9
            end = -1
            step = -1
        }
        var count = 0
        var index = 0
        var i = start
        while (i != end) {
            val p = b.getPieceByPosition(pos.x, i)
            if (p == piece) {
                count++
                if (pos.y == i) index = count
            }
            i += step
        }

        if (count == 1) return null

        if (piece != Piece.WBING && piece != Piece.BZU) {
            // 非兵卒，这时 count 只能为 2
            return if (index == 1) "前" else "后"
        }

        if (index == 1 && count <= 3) return "前"
        if (index == count && count <= 3) return "后"
        if (index == 2 && count == 3) return "中"
        return if (Piece.isRed(piece)) {
            "${arabicToChineseMap[index]}"
        } else {
            "$index"
        }
    }

    override fun toString(): String =
        "Move{$fromPosition => $toPosition, piece=$piece, comment='$comment'}"

    companion object {
        /** 阿拉伯数字 → 中文数字（红方着法用中文数字，黑方用阿拉伯数字）。 */
        val arabicToChineseMap: Map<Int, String> = mapOf(
            1 to "一",
            2 to "二",
            3 to "三",
            4 to "四",
            5 to "五",
            6 to "六",
            7 to "七",
            8 to "八",
            9 to "九",
        )
    }
}
