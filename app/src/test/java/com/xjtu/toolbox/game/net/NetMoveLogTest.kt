package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetMoveLogTest {

    @Test
    fun `按序记录并能查询`() {
        val log = NetMoveLog()
        log.record(1, "7,7")
        log.record(2, "7,8")
        assertEquals(2, log.count)
        assertEquals("7,7", log.at(1))
        assertEquals("7,8", log.at(2))
    }

    @Test
    fun `序号不连续直接拒绝`() {
        val log = NetMoveLog()
        log.record(1, "7,7")
        assertThrows(IllegalArgumentException::class.java) {
            log.record(3, "7,9") // 跳过了 2
        }
    }

    @Test
    fun `断线重连补发缺失的着法`() {
        val log = NetMoveLog()
        log.record(1, "a")
        log.record(2, "b")
        log.record(3, "c")

        // 对方说它只确认到第 1 步，应该把 2、3 补发过去
        val missing = log.sinceExclusive(1)
        assertEquals(listOf(2 to "b", 3 to "c"), missing)

        // 对方已经全部确认，不用补发
        assertEquals(emptyList<Pair<Int, String>>(), log.sinceExclusive(3))

        // 全新对局（ackSeq=0）要把全部着法都发过去
        assertEquals(listOf(1 to "a", 2 to "b", 3 to "c"), log.sinceExclusive(0))
    }
}
