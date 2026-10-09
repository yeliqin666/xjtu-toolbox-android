package com.xjtu.toolbox.game.xiangqi.rules

/**
 * 走法生成、合法走法、将死/困毙判定。
 *
 * 从 :app 的 `rules/Rule.java` 搬过来（Java 的 `switch` 换成 `when`，`List`/`Iterator`
 * 遍历换成 `for`/`any`，其余分支、常量表、判据一字未改）。原文件顶部的改动说明保留在
 * :app 那份里，两条结论在这里同样成立：
 * 1. 没有 `android.util.Log`（三处调用本来就被注释掉了），所以规则引擎能跑 JVM 单测；
 * 2. 文件末尾那组「困毙」方法是本仓库补的，原库只做到将死。
 */
object Rule {

    private val area = arrayOf(
        intArrayOf(1, 1, 1, 2, 2, 2, 1, 1, 1),
        intArrayOf(1, 1, 1, 2, 2, 2, 1, 1, 1),
        intArrayOf(1, 1, 1, 2, 2, 2, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),

        intArrayOf(3, 3, 3, 3, 3, 3, 3, 3, 3),
        intArrayOf(3, 3, 3, 3, 3, 3, 3, 3, 3),
        intArrayOf(3, 3, 3, 4, 4, 4, 3, 3, 3),
        intArrayOf(3, 3, 3, 4, 4, 4, 3, 3, 3),
        intArrayOf(3, 3, 3, 4, 4, 4, 3, 3, 3),
    )

    private val offsetX = arrayOf(
        intArrayOf(0, 0, 1, -1), // 帅 将
        intArrayOf(1, 1, -1, -1), // 仕 士
        intArrayOf(2, 2, -2, -2), // 相 象
        intArrayOf(1, 1, -1, -1), // 象眼
        intArrayOf(1, 1, -1, -1, 2, 2, -2, -2), // 马
        intArrayOf(0, 0, 0, 0, 1, 1, -1, -1), // 蹩马腿
        intArrayOf(0), // 卒
        intArrayOf(-1, 0, 1), // 过河卒
        intArrayOf(0), // 兵
        intArrayOf(-1, 0, 1), // 过河兵
        intArrayOf(1, 1, -1, -1, 1, 1, -1, -1), // 反向蹩马腿
    )

    private val offsetY = arrayOf(
        intArrayOf(1, -1, 0, 0), // 帅 将
        intArrayOf(1, -1, 1, -1), // 仕 士
        intArrayOf(2, -2, 2, -2), // 相 象
        intArrayOf(1, -1, 1, -1), // 象眼
        intArrayOf(2, -2, 2, -2, 1, -1, 1, -1), // 马
        intArrayOf(1, -1, 1, -1, 0, 0, 0, 0), // 蹩马腿
        intArrayOf(1), // 卒
        intArrayOf(0, 1, 0), // 过河卒
        intArrayOf(-1), // 兵
        intArrayOf(0, -1, 0), // 过河兵
        intArrayOf(1, -1, 1, -1, 1, -1, 1, -1), // 反向蹩马腿
    )

    /** 在棋盘中找到将帅的位置；不在盘上返回 null。 */
    fun findJiangShuaiPos(piece: Int, board: Board): Position? {
        if (piece == Piece.WSHUAI) {
            for (y in 7..9) {
                for (x in 3..5) {
                    if (board.getPieceByPosition(x, y) == Piece.WSHUAI) {
                        return Position(x, y)
                    }
                }
            }
        } else if (piece == Piece.BJIANG) {
            for (y in 0..2) {
                for (x in 3..5) {
                    if (board.getPieceByPosition(x, y) == Piece.BJIANG) {
                        return Position(x, y)
                    }
                }
            }
        }
        return null
    }

    /** 伪合法走法（只管这个兵种能不能这么走，不管走完自己会不会被将军）。 */
    fun PossibleToPositions(pieceId: Int, fromX: Int, fromY: Int, board: Board): List<Position> {
        val ret = ArrayList<Position>()
        var num: Int
        when (pieceId) {
            Piece.BJIANG -> { // 黑将
                num = 0
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    if (InArea(toX, toY) == 2 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                        val flyPos = flyKing(Piece.BJIANG, toX, toY, board)
                        if (flyPos == null) {
                            // (toX, toY) 不会导致将帅照脸
                            ret.add(Position(toX, toY))
                        }
                    }
                }
            }

            Piece.BSHI -> { // 黑士
                num = 1
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    if (InArea(toX, toY) == 2 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                        ret.add(Position(toX, toY))
                    }
                }
            }

            Piece.BXIANG -> { // 黑象
                num = 2
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    val blockX = fromX + offsetX[num + 1][i]
                    val blockY = fromY + offsetY[num + 1][i]
                    if (InArea(toX, toY) >= 1 && InArea(toX, toY) <= 2 &&
                        !onSameSide(pieceId, board.getPieceByPosition(toX, toY)) &&
                        !Piece.isValid(board.getPieceByPosition(blockX, blockY))
                    ) {
                        ret.add(Position(toX, toY))
                    }
                }
            }

            Piece.BMA, Piece.WMA -> { // 马
                num = 4
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    val blockX = fromX + offsetX[num + 1][i]
                    val blockY = fromY + offsetY[num + 1][i]
                    if (InArea(toX, toY) != 0 &&
                        !onSameSide(pieceId, board.getPieceByPosition(toX, toY)) &&
                        !Piece.isValid(board.getPieceByPosition(blockX, blockY))
                    ) {
                        ret.add(Position(toX, toY))
                    }
                }
            }

            Piece.BJU, Piece.WJU -> { // 车
                for (i in fromY + 1 until Board.BOARD_PIECE_HEIGHT) { // 向下走
                    if (CanMove(Piece.BJU, fromX, fromY, fromX, i, board)) {
                        ret.add(Position(fromX, i))
                    } else {
                        break
                    }
                }
                for (i in fromY - 1 downTo 0) { // 向上走
                    if (CanMove(Piece.BJU, fromX, fromY, fromX, i, board)) {
                        ret.add(Position(fromX, i))
                    } else {
                        break
                    }
                }
                for (j in fromX - 1 downTo 0) { // 向左走
                    if (CanMove(Piece.BJU, fromX, fromY, j, fromY, board)) {
                        ret.add(Position(j, fromY))
                    } else {
                        break
                    }
                }
                for (j in fromX + 1 until Board.BOARD_PIECE_WIDTH) { // 向右走
                    if (CanMove(Piece.BJU, fromX, fromY, j, fromY, board)) {
                        ret.add(Position(j, fromY))
                    } else {
                        break
                    }
                }
            }

            Piece.BPAO, Piece.WPAO -> { // 炮
                for (i in fromY + 1 until Board.BOARD_PIECE_HEIGHT) { // 向下走
                    if (CanMove(Piece.BPAO, fromX, fromY, fromX, i, board)) {
                        ret.add(Position(fromX, i))
                    }
                }
                for (i in fromY - 1 downTo 0) { // 向上走
                    if (CanMove(Piece.BPAO, fromX, fromY, fromX, i, board)) {
                        ret.add(Position(fromX, i))
                    }
                }
                for (j in fromX - 1 downTo 0) { // 向左走
                    if (CanMove(Piece.BPAO, fromX, fromY, j, fromY, board)) {
                        ret.add(Position(j, fromY))
                    }
                }
                for (j in fromX + 1 until Board.BOARD_PIECE_WIDTH) { // 向右走
                    if (CanMove(Piece.BPAO, fromX, fromY, j, fromY, board)) {
                        ret.add(Position(j, fromY))
                    }
                }
            }

            Piece.BZU -> { // 黑卒
                if (InArea(fromX, fromY) == 1) {
                    num = 6
                    for (i in offsetX[num].indices) {
                        val toX = fromX + offsetX[num][i]
                        val toY = fromY + offsetY[num][i]
                        if (InArea(toX, toY) != 0 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                            ret.add(Position(toX, toY))
                        }
                    }
                } else {
                    // 过河卒
                    num = 7
                    for (i in offsetX[num].indices) {
                        val toX = fromX + offsetX[num][i]
                        val toY = fromY + offsetY[num][i]
                        if (InArea(toX, toY) != 0 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                            ret.add(Position(toX, toY))
                        }
                    }
                }
            }

            Piece.WSHUAI -> { // 红帅
                num = 0
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    if (InArea(toX, toY) == 4 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                        val flyPos = flyKing(Piece.WSHUAI, toX, toY, board)
                        if (flyPos == null) {
                            ret.add(Position(toX, toY))
                        }
                    }
                }
            }

            Piece.WSHI -> { // 红士
                num = 1
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    if (InArea(toX, toY) == 4 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                        ret.add(Position(toX, toY))
                    }
                }
            }

            Piece.WXIANG -> { // 红象
                num = 2
                for (i in offsetX[num].indices) {
                    val toX = fromX + offsetX[num][i]
                    val toY = fromY + offsetY[num][i]
                    val blockX = fromX + offsetX[num + 1][i]
                    val blockY = fromY + offsetY[num + 1][i]
                    if (InArea(toX, toY) >= 3 && InArea(toX, toY) <= 4 &&
                        !onSameSide(pieceId, board.getPieceByPosition(toX, toY)) &&
                        !Piece.isValid(board.getPieceByPosition(blockX, blockY))
                    ) {
                        ret.add(Position(toX, toY))
                    }
                }
            }

            Piece.WBING -> { // 红兵
                if (InArea(fromX, fromY) == 3) {
                    num = 8
                    for (i in offsetX[num].indices) {
                        val toX = fromX + offsetX[num][i]
                        val toY = fromY + offsetY[num][i]
                        if (InArea(toX, toY) != 0 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                            ret.add(Position(toX, toY))
                        }
                    }
                } else {
                    // 过河兵
                    num = 9
                    for (i in offsetX[num].indices) {
                        val toX = fromX + offsetX[num][i]
                        val toY = fromY + offsetY[num][i]
                        if (InArea(toX, toY) != 0 && !onSameSide(pieceId, board.getPieceByPosition(toX, toY))) {
                            ret.add(Position(toX, toY))
                        }
                    }
                }
            }
        }
        return ret
    }

    /** 检查走法是否符合走子规则（伪合法，不看送将）。 */
    fun isValidMove(move: Move?, board: Board): Boolean {
        if (move == null) return false
        val from = move.fromPosition ?: return false
        val to = move.toPosition ?: return false

        val piece = board.getPieceByPosition(from)
        val positions = PossibleToPositions(piece, from.x, from.y, board)
        return positions.any { it == to }
    }

    fun isJiangShuaiInDanger(piece: Int, pos: Position, board: Board): Boolean {
        // 马的攻击和别腿，红帅和黑将都会用到
        val num = 4
        val opBlockNum = 10 // 反向蹩马腿

        if (piece == Piece.WSHUAI) {
            val x = pos.x
            val y = pos.y

            // 被黑马攻击
            for (i in offsetX[num].indices) {
                val toX = x + offsetX[num][i]
                val toY = y + offsetY[num][i]
                val blockX = x + offsetX[opBlockNum][i]
                val blockY = y + offsetY[opBlockNum][i]
                if (InArea(toX, toY) != 0 &&
                    board.getPieceByPosition(toX, toY) == Piece.BMA &&
                    !Piece.isValid(board.getPieceByPosition(blockX, blockY))
                ) {
                    return true
                }
            }
            if (attackableByJuPao(Piece.BJU, x, y, board)) return true
            if (attackableByJuPao(Piece.BPAO, x, y, board)) return true
            if (board.getPieceByPosition(x - 1, y) == Piece.BZU ||
                board.getPieceByPosition(x + 1, y) == Piece.BZU ||
                board.getPieceByPosition(x, y - 1) == Piece.BZU
            ) {
                return true
            }
        } else if (piece == Piece.BJIANG) {
            val x = pos.x
            val y = pos.y

            // 被红马攻击
            for (i in offsetX[num].indices) {
                val toX = x + offsetX[num][i]
                val toY = y + offsetY[num][i]
                val blockX = x + offsetX[opBlockNum][i]
                val blockY = y + offsetY[opBlockNum][i]
                if (InArea(toX, toY) != 0 &&
                    board.getPieceByPosition(toX, toY) == Piece.WMA &&
                    !Piece.isValid(board.getPieceByPosition(blockX, blockY))
                ) {
                    return true
                }
            }
            if (attackableByJuPao(Piece.WJU, x, y, board)) return true
            if (attackableByJuPao(Piece.WPAO, x, y, board)) return true
            if (board.getPieceByPosition(x - 1, y) == Piece.WBING ||
                board.getPieceByPosition(x + 1, y) == Piece.WBING ||
                board.getPieceByPosition(x, y + 1) == Piece.WBING
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 将帅是否被将死（原库口径：把自己一方的每个棋子的每个落点都试一遍，全都被将军就算死）。
     *
     * 注意它要求调用方先自己确认「正在被将军」，且内部漏了将帅照面那一路 —— 新代码请用
     * [isCheckmate] / [isStalemate]（它们按 [isLegalMove] 的统一口径重判）。
     */
    fun isJiangShuaiDead(piece: Int, bosspos: Position, b: Board): Boolean {
        val board = Board(b)
        val red = piece == Piece.WSHUAI
        for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
            for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                val pieceid = board.getPieceByPosition(x, y)
                if (if (red) Piece.isRed(pieceid) else Piece.isBlack(pieceid)) {
                    val positions = PossibleToPositions(pieceid, x, y, board)
                    for (pos in positions) {
                        val tempPieceId = board.getPieceByPosition(pos)

                        // 试走
                        board.setPieceByPosition(x, y, Piece.EMPTY)
                        board.setPieceByPosition(pos, pieceid)

                        val result = if (pieceid == piece) {
                            // 将帅移动了位置
                            isJiangShuaiInDanger(piece, pos, board)
                        } else {
                            isJiangShuaiInDanger(piece, bosspos, board)
                        }

                        if (!result) return false

                        // 撤回
                        board.setPieceByPosition(x, y, pieceid)
                        board.setPieceByPosition(pos, tempPieceId)
                    }
                }
            }
        }
        return true
    }

    /** (x,y) 是否会被 piece（只能是车或炮）攻击。 */
    private fun attackableByJuPao(piece: Int, x: Int, y: Int, board: Board): Boolean {
        if (!(piece == Piece.BJU || piece == Piece.BPAO || piece == Piece.WJU || piece == Piece.WPAO)) {
            return false
        }
        val positions = PossibleToPositions(piece, x, y, board)
        return positions.any { board.getPieceByPosition(it) == piece }
    }

    /** 0 棋盘外 / 1 黑盘 / 2 黑十字 / 3 红盘 / 4 红十字 */
    private fun InArea(x: Int, y: Int): Int {
        if (x < 0 || x >= Board.BOARD_PIECE_WIDTH || y < 0 || y >= Board.BOARD_PIECE_HEIGHT) {
            return 0
        }
        return area[y][x]
    }

    private fun onSameSide(fromID: Int, toID: Int): Boolean {
        if (!Piece.isValid(toID) || !Piece.isValid(fromID)) {
            return false
        }
        return Piece.isRed(fromID) == Piece.isRed(toID)
    }

    /** 飞将：将帅同列且中间没有别的子时返回对方将帅的位置，否则 null。 */
    private fun flyKing(id: Int, fromX: Int, fromY: Int, board: Board): Position? {
        if (id == Piece.BJIANG) { // 将
            for (i in fromY + 1 until Board.BOARD_PIECE_HEIGHT) {
                val pieceid = board.getPieceByPosition(fromX, i)
                if (Piece.isValid(pieceid)) {
                    return if (pieceid == Piece.WSHUAI) {
                        Position(fromX, i)
                    } else {
                        null
                    }
                }
            }
        } else if (id == Piece.WSHUAI) { // 帅
            for (i in fromY - 1 downTo 0) {
                val pieceid = board.getPieceByPosition(fromX, i)
                if (Piece.isValid(pieceid)) {
                    return if (pieceid == Piece.BJIANG) {
                        Position(fromX, i)
                    } else {
                        null
                    }
                }
            }
        }
        return null
    }

    private fun CanMove(id: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, board: Board): Boolean {
        if (fromX < 0 || fromX >= Board.BOARD_PIECE_WIDTH || fromY < 0 || fromY >= Board.BOARD_PIECE_HEIGHT ||
            toX < 0 || toX >= Board.BOARD_PIECE_WIDTH || toY < 0 || toY >= Board.BOARD_PIECE_HEIGHT
        ) {
            return false
        }
        if (fromX == toX && fromY == toY) return false
        if (onSameSide(board.getPieceByPosition(fromX, fromY), board.getPieceByPosition(toX, toY))) {
            return false
        }

        if (id == Piece.BJU || id == Piece.WJU) { // 车
            // 已验证终点和自己非同一颜色，验证中间无子即可
            val start: Int
            val finish: Int
            if (fromX == toX) { // 进退
                if (fromY < toY) {
                    start = fromY + 1
                    finish = toY
                } else {
                    start = toY + 1
                    finish = fromY
                }
                for (i in start until finish) {
                    if (Piece.isValid(board.getPieceByPosition(fromX, i))) return false
                }
            } else { // 平移
                if (fromX < toX) {
                    start = fromX + 1
                    finish = toX
                } else {
                    start = toX + 1
                    finish = fromX
                }
                for (i in start until finish) {
                    if (Piece.isValid(board.getPieceByPosition(i, fromY))) return false
                }
            }
        } else if (id == Piece.BPAO || id == Piece.WPAO) { // 炮
            if (!Piece.isValid(board.getPieceByPosition(toX, toY))) {
                // 终点无子：属于移动，验证中间无子即可
                val start: Int
                val finish: Int
                if (fromX == toX) {
                    if (fromY < toY) {
                        start = fromY + 1
                        finish = toY
                    } else {
                        start = toY + 1
                        finish = fromY
                    }
                    for (i in start until finish) {
                        if (Piece.isValid(board.getPieceByPosition(fromX, i))) return false
                    }
                } else {
                    if (fromX < toX) {
                        start = fromX + 1
                        finish = toX
                    } else {
                        start = toX + 1
                        finish = fromX
                    }
                    for (i in start until finish) {
                        if (Piece.isValid(board.getPieceByPosition(i, fromY))) return false
                    }
                }
            } else {
                // 终点有子：属于吃子，验证中间只有一子
                val start: Int
                val finish: Int
                var count = 0
                if (fromX == toX) {
                    if (fromY < toY) {
                        start = fromY + 1
                        finish = toY
                    } else {
                        start = toY + 1
                        finish = fromY
                    }
                    for (i in start until finish) {
                        if (Piece.isValid(board.getPieceByPosition(fromX, i))) count++
                    }
                } else {
                    if (fromX < toX) {
                        start = fromX + 1
                        finish = toX
                    } else {
                        start = toX + 1
                        finish = fromX
                    }
                    for (i in start until finish) {
                        if (Piece.isValid(board.getPieceByPosition(i, fromY))) count++
                    }
                }
                if (count != 1) return false
            }
        }
        return true
    }

    // ==================== 以下为本仓库新增（原库没有困毙这一条） ====================

    /**
     * 将帅照面。原库把这条规则塞在 [PossibleToPositions] 里，只在「将帅自己动」时才查，
     * 因此「挪开中间挡着的子，把自己的将暴露给对方的帅」这种自杀走法漏掉了。
     * 这里单独提出来，任何一步走完都要过一遍。
     */
    fun isKingFaceToFace(board: Board): Boolean {
        val red = findJiangShuaiPos(Piece.WSHUAI, board)
        val black = findJiangShuaiPos(Piece.BJIANG, board)
        if (red == null || black == null) {
            // 有一方的将帅已经不在棋盘上（多半是测试用的残局），谈不上照面
            return false
        }
        if (red.x != black.x) return false
        for (y in black.y + 1 until red.y) {
            if (Piece.isValid(board.getPieceByPosition(red.x, y))) return false
        }
        return true
    }

    /**
     * side 一方此刻是否被将军。side 取 [Piece.WSHUAI]（红）或 [Piece.BJIANG]（黑）——
     * 用将帅的 pieceId 当阵营标记，是为了直接喂给原库那几个同样以 piece 区分红黑的方法。
     */
    fun isInCheck(side: Int, board: Board): Boolean {
        val pos = findJiangShuaiPos(side, board) ?: return false
        return isJiangShuaiInDanger(side, pos, board) || isKingFaceToFace(board)
    }

    /** 真·合法走法：伪合法 + 走完之后自己的将帅不处于被将军状态（不能送将，含照面）。 */
    fun isLegalMove(side: Int, move: Move?, board: Board): Boolean {
        if (move == null || !Board.isValidPosition(move.fromPosition) || !Board.isValidPosition(move.toPosition)) {
            return false
        }
        val piece = board.getPieceByPosition(move.fromPosition!!)
        if (!Piece.isValid(piece)) return false
        // 只能动自己的子
        val redSide = side == Piece.WSHUAI
        if (Piece.isRed(piece) != redSide) return false
        if (!isValidMove(move, board)) return false

        // 在副本上试走，避免污染调用方手里的棋盘
        val trial = Board(board)
        trial.setPieceByPosition(move.toPosition!!, piece)
        trial.setPieceByPosition(move.fromPosition!!, Piece.EMPTY)
        return !isInCheck(side, trial)
    }

    /** side 一方是否还存在哪怕一步合法走法。困毙和将死共用这个判断。 */
    fun hasAnyLegalMove(side: Int, board: Board): Boolean {
        val redSide = side == Piece.WSHUAI
        for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
            for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                val pieceId = board.getPieceByPosition(x, y)
                if (!Piece.isValid(pieceId) || Piece.isRed(pieceId) != redSide) continue
                val positions = PossibleToPositions(pieceId, x, y, board)
                for (to in positions) {
                    if (isLegalMove(side, Move(Position(x, y), to), board)) return true
                }
            }
        }
        return false
    }

    /** 困毙：轮到 side 走，但它没有任何合法走法，且当前并没有被将军。 */
    fun isStalemate(side: Int, board: Board): Boolean =
        !isInCheck(side, board) && !hasAnyLegalMove(side, board)

    /** 将死：被将军且无路可走。 */
    fun isCheckmate(side: Int, board: Board): Boolean =
        isInCheck(side, board) && !hasAnyLegalMove(side, board)
}
