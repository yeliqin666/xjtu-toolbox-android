package com.xjtu.toolbox.game.xiangqi.rules

/**
 * 兵种常量与三个查表（字符 / 中文名 / 字符→编号）。
 *
 * 从 :app 的 `rules/Piece.java` 逐字搬过来。原文件顶部那段 GPL 头是上游 zfdang 的
 * DroidFish 衍生声明，**保留在 :app 的那份里**（这里只搬常量与查表，不搬注释头）。
 * https://www.xqbase.com/protocol/cchess_move.htm
 *
 * 红方（白方）棋子以大写字母表示，黑方以小写字母表示：
 * PABNCRK = 兵仕相马炮车帅，小写对应卒士象马炮车将。
 */
object Piece {
    const val EMPTY = 0
    const val EMPTY_CHAR = ' '

    const val WSHUAI = 1 // K, 帅
    const val WSHI = 2 // A, 仕
    const val WXIANG = 3 // B, 相
    const val WMA = 4 // N, 马
    const val WJU = 5 // R, 车
    const val WPAO = 6 // C, 炮
    const val WBING = 7 // P, 兵

    const val BJIANG = 8 // k, 将
    const val BSHI = 9 // a, 士
    const val BXIANG = 10 // b, 象
    const val BMA = 11 // n, 马
    const val BJU = 12 // r, 车
    const val BPAO = 13 // c, 炮
    const val BZU = 14 // p, 卒

    const val nPieceTypes = 15

    val pieceCharMap: Map<Int, Char> = mapOf(
        WSHUAI to 'K',
        WSHI to 'A',
        WXIANG to 'B',
        WMA to 'N',
        WJU to 'R',
        WPAO to 'C',
        WBING to 'P',

        BJIANG to 'k',
        BSHI to 'a',
        BXIANG to 'b',
        BMA to 'n',
        BJU to 'r',
        BPAO to 'c',
        BZU to 'p',
    )

    val pieceNameMap: Map<Int, Char> = mapOf(
        WSHUAI to '帅',
        WSHI to '仕',
        WXIANG to '相',
        WMA to '马',
        WJU to '车',
        WPAO to '炮',
        WBING to '兵',

        BJIANG to '将',
        BSHI to '士',
        BXIANG to '象',
        BMA to '马',
        BJU to '车',
        BPAO to '炮',
        BZU to '卒',
    )

    val pieceValueMap: Map<Char, Int> = mapOf(
        'K' to WSHUAI,
        'A' to WSHI,
        'B' to WXIANG,
        'N' to WMA,
        'R' to WJU,
        'C' to WPAO,
        'P' to WBING,

        'k' to BJIANG,
        'a' to BSHI,
        'b' to BXIANG,
        'n' to BMA,
        'r' to BJU,
        'c' to BPAO,
        'p' to BZU,
    )

    /** 是不是红子。传 [EMPTY] 时结果无意义（与 Java 版一致）。 */
    fun isRed(pType: Int): Boolean = pType <= WBING && pType >= WSHUAI

    fun isBlack(pType: Int): Boolean = pType <= BZU && pType >= BJIANG

    fun isValid(pType: Int): Boolean = pType <= BZU && pType >= WSHUAI

    fun isDiagonalPiece(pType: Int): Boolean =
        pType == WXIANG || pType == BXIANG || pType == WSHI || pType == BSHI || pType == WMA || pType == BMA

    fun swapColor(pType: Int): Int {
        if (pType == EMPTY) return EMPTY
        return if (isRed(pType)) pType + (BZU - WBING) else pType - (BZU - WBING)
    }

    /** 编号 → 字符（车/马/…）；认不出给空格。 */
    fun getCharByValue(i: Int): Char = pieceCharMap[i] ?: EMPTY_CHAR

    /** 编号 → 中文名（车/马/…）；认不出给空格。 */
    fun getNameByValue(i: Int): Char = pieceNameMap[i] ?: EMPTY_CHAR

    /** 字符 → 编号；认不出给 [EMPTY]。 */
    fun getValueByChar(b: Char): Int = pieceValueMap[b] ?: EMPTY
}
