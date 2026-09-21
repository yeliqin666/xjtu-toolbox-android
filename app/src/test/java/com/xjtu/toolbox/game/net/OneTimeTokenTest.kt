package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeTokenTest {

    @Test
    fun `第一次口令对了才通过`() {
        val token = OneTimeToken("ABCD1234")
        assertFalse(token.tryConsume("不对的口令"))
        assertTrue(token.tryConsume("ABCD1234"))
    }

    @Test
    fun `一次性——用过一次之后再对的口令也不再通过`() {
        val token = OneTimeToken("ABCD1234")
        assertTrue(token.tryConsume("ABCD1234"))
        // 第三台设备（甚至是同一台再连一次）用同一份口令，一律拒绝
        assertFalse(token.tryConsume("ABCD1234"))
        assertTrue(token.isConsumed())
    }

    @Test
    fun `生成的口令长度固定且不含易混字符`() {
        repeat(20) {
            val t = OneTimeToken.generate()
            assertEquals(8, t.length)
            assertTrue(t.none { c -> c in "0O1I" })
        }
    }
}
