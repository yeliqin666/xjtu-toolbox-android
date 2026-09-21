package com.xjtu.toolbox.game.xiangqi

import com.xjtu.toolbox.game.xiangqi.rules.Board
import com.xjtu.toolbox.game.xiangqi.rules.Move
import com.xjtu.toolbox.game.xiangqi.rules.Piece
import com.xjtu.toolbox.game.xiangqi.rules.Position
import com.xjtu.toolbox.game.xiangqi.rules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎的走法生成与终局判定。
 *
 * 坐标约定跟 Board 一致：x 从左到右 0..8，y 从上到下 0..9，
 * 也就是黑方在 y=0 一侧、红方在 y=9 一侧。残局一律 clear() 之后手摆，
 * 免得默认开局里的闲子把结论搅浑。
 */
class XiangqiRuleTest {

    private fun board(vararg pieces: Triple<Int, Int, Int>): Board {
        val b = Board()
        b.clear()
        pieces.forEach { (x, y, p) -> b.setPieceByPosition(x, y, p) }
        return b
    }

    private fun targets(b: Board, x: Int, y: Int): Set<Pair<Int, Int>> =
        Rule.PossibleToPositions(b.getPieceByPosition(x, y), x, y, b).map { it.x to it.y }.toSet()

    // ── 各兵种走法 ──

    @Test
    fun `车走直线且被己方子挡住`() {
        val b = board(
            Triple(0, 9, Piece.WJU),
            Triple(4, 9, Piece.WSHUAI),
            Triple(3, 0, Piece.BJIANG),
        )
        val t = targets(b, 0, 9)
        assertTrue(t.contains(0 to 0))
        assertTrue(t.contains(3 to 9))
        // 己方帅站在 (4,9)，车既吃不掉它也过不去
        assertFalse(t.contains(4 to 9))
        assertFalse(t.contains(5 to 9))
        assertEquals(12, t.size)
    }

    @Test
    fun `马走日且蹩马腿`() {
        val free = board(Triple(4, 5, Piece.WMA))
        assertEquals(8, targets(free, 4, 5).size)

        // (4,4) 正好是往上两路的马腿
        val blocked = board(Triple(4, 5, Piece.WMA), Triple(4, 4, Piece.BZU))
        val t = targets(blocked, 4, 5)
        assertFalse(t.contains(3 to 3))
        assertFalse(t.contains(5 to 3))
        assertTrue(t.contains(3 to 7))
        assertTrue(t.contains(5 to 7))
    }

    @Test
    fun `相走田字且塞象眼`() {
        val free = board(Triple(2, 9, Piece.WXIANG))
        assertEquals(setOf(4 to 7, 0 to 7), targets(free, 2, 9))

        val blocked = board(Triple(2, 9, Piece.WXIANG), Triple(3, 8, Piece.BZU))
        assertEquals(setOf(0 to 7), targets(blocked, 2, 9))
    }

    @Test
    fun `相不能过河`() {
        // (2,5) 在红方最靠河的一行，往前一步就出界到黑方半场
        val b = board(Triple(2, 5, Piece.WXIANG))
        val t = targets(b, 2, 5)
        assertTrue(t.contains(4 to 7))
        assertTrue(t.contains(0 to 7))
        assertFalse(t.contains(4 to 3))
        assertFalse(t.contains(0 to 3))
    }

    @Test
    fun `炮必须隔一个子才能吃子`() {
        val b = board(
            Triple(4, 9, Piece.WPAO),
            Triple(4, 5, Piece.BZU),   // 炮架
            Triple(4, 0, Piece.BJU),   // 目标
            Triple(3, 9, Piece.WSHUAI),
            Triple(3, 0, Piece.BJIANG),
        )
        val t = targets(b, 4, 9)
        assertTrue(t.contains(4 to 0))
        // 紧挨着的子不能直接吃：中间没有炮架
        assertFalse(t.contains(4 to 5))
        // 没子的格子照走不误
        assertTrue(t.contains(4 to 6))
    }

    @Test
    fun `仕只能在九宫走斜线`() {
        val center = board(Triple(4, 8, Piece.WSHI))
        assertEquals(setOf(3 to 7, 5 to 7, 3 to 9, 5 to 9), targets(center, 4, 8))

        val corner = board(Triple(3, 9, Piece.WSHI))
        assertEquals(setOf(4 to 8), targets(corner, 3, 9))
    }

    @Test
    fun `帅只能在九宫走直线`() {
        // 故意不摆黑将：照面规则单独有用例，这里只看九宫内的走法范围
        val b = board(Triple(4, 9, Piece.WSHUAI))
        assertEquals(setOf(4 to 8, 3 to 9, 5 to 9), targets(b, 4, 9))
    }

    @Test
    fun `兵过河前只能直走过河后可以平移`() {
        val home = board(Triple(4, 6, Piece.WBING))
        assertEquals(setOf(4 to 5), targets(home, 4, 6))

        val crossed = board(Triple(4, 4, Piece.WBING))
        assertEquals(setOf(3 to 4, 5 to 4, 4 to 3), targets(crossed, 4, 4))
        // 过河兵也不能后退
        assertFalse(targets(crossed, 4, 4).contains(4 to 5))
    }

    @Test
    fun `卒的方向与兵相反`() {
        val home = board(Triple(4, 3, Piece.BZU))
        assertEquals(setOf(4 to 4), targets(home, 4, 3))

        val crossed = board(Triple(4, 5, Piece.BZU))
        assertEquals(setOf(3 to 5, 5 to 5, 4 to 6), targets(crossed, 4, 5))
    }

    // ── 将帅照面 ──

    @Test
    fun `将帅照面非法`() {
        val facing = board(Triple(4, 9, Piece.WSHUAI), Triple(4, 0, Piece.BJIANG))
        assertTrue(Rule.isKingFaceToFace(facing))

        val blocked = board(
            Triple(4, 9, Piece.WSHUAI),
            Triple(4, 0, Piece.BJIANG),
            Triple(4, 5, Piece.BZU),
        )
        assertFalse(Rule.isKingFaceToFace(blocked))

        // 帅不能主动平到对脸的那一路
        val b = board(Triple(3, 9, Piece.WSHUAI), Triple(4, 0, Piece.BJIANG))
        assertFalse(targets(b, 3, 9).contains(4 to 9))
    }

    @Test
    fun `挪开挡子导致照面也算送将`() {
        // 帅和将同在第 4 路，中间只隔着一个红兵；这个兵一平开，两王对脸
        val b = board(
            Triple(4, 9, Piece.WSHUAI),
            Triple(4, 0, Piece.BJIANG),
            Triple(4, 4, Piece.WBING),
        )
        val move = Move(Position(4, 4), Position(3, 4))
        assertTrue("平兵本身符合过河兵的走法", Rule.isValidMove(move, b))
        assertFalse("但走完两王对脸，不合法", Rule.isLegalMove(Piece.WSHUAI, move, b))
    }

    // ── 送将 ──

    @Test
    fun `被牵制的子不能离线送将`() {
        val b = board(
            Triple(4, 9, Piece.WSHUAI),
            Triple(2, 9, Piece.WJU),   // 被牵制
            Triple(0, 9, Piece.BJU),   // 牵制者
            Triple(3, 0, Piece.BJIANG),
        )
        val move = Move(Position(2, 9), Position(2, 5))
        assertTrue(Rule.isValidMove(move, b))
        assertFalse(Rule.isLegalMove(Piece.WSHUAI, move, b))
        // 沿着同一条线吃掉牵制者是允许的
        assertTrue(Rule.isLegalMove(Piece.WSHUAI, Move(Position(2, 9), Position(0, 9)), b))
    }

    // ── 将死 ──

    @Test
    fun `双车封两行构成将死`() {
        val b = checkmateBoard()
        assertTrue(Rule.isInCheck(Piece.BJIANG, b))
        assertTrue(Rule.isCheckmate(Piece.BJIANG, b))
        assertFalse(Rule.hasAnyLegalMove(Piece.BJIANG, b))
    }

    // ── 困毙（本仓库补的规则）──

    @Test
    fun `无子可动且未被将军是困毙`() {
        val b = stalemateBoard()
        assertFalse("困毙的前提是没被将军", Rule.isInCheck(Piece.BJIANG, b))
        assertFalse(Rule.hasAnyLegalMove(Piece.BJIANG, b))
        assertTrue(Rule.isStalemate(Piece.BJIANG, b))
        assertFalse(Rule.isCheckmate(Piece.BJIANG, b))
    }

    @Test
    fun `还有子能动就不是困毙`() {
        // 在困毙局面上补一个能往前拱的黑卒
        val b = stalemateBoard()
        b.setPieceByPosition(0, 4, Piece.BZU)
        assertTrue(Rule.hasAnyLegalMove(Piece.BJIANG, b))
        assertFalse(Rule.isStalemate(Piece.BJIANG, b))
    }

    @Test
    fun `被将军且无路可走是将死不是困毙`() {
        val b = checkmateBoard()
        assertFalse(Rule.isStalemate(Piece.BJIANG, b))
        assertTrue(Rule.isCheckmate(Piece.BJIANG, b))
    }

    @Test
    fun `红方也能被困毙`() {
        // 对称验证一次，确认 isStalemate 不是只对黑方写对了
        val b = board(
            Triple(4, 9, Piece.WSHUAI),
            Triple(3, 8, Piece.BZU),
            Triple(5, 8, Piece.BZU),
            Triple(5, 0, Piece.BJIANG),
        )
        assertFalse(Rule.isInCheck(Piece.WSHUAI, b))
        assertTrue(Rule.isStalemate(Piece.WSHUAI, b))
    }

    /** 黑将被双车分别封住第 0、1 行，动不了也解不了将。 */
    private fun checkmateBoard(): Board = board(
        Triple(4, 0, Piece.BJIANG),
        Triple(0, 0, Piece.WJU),
        Triple(0, 1, Piece.WJU),
        Triple(3, 9, Piece.WSHUAI),
    )

    /**
     * 黑将在 (4,0)，两个过河红兵分别盯住 (3,0)/(5,0)/(4,1)，但都不攻击 (4,0) 本身；
     * 红帅摆在第 5 路避开照面。于是黑方没被将军，却一步也走不了——标准的困毙。
     */
    private fun stalemateBoard(): Board = board(
        Triple(4, 0, Piece.BJIANG),
        Triple(3, 1, Piece.WBING),
        Triple(5, 1, Piece.WBING),
        Triple(5, 9, Piece.WSHUAI),
    )
}
