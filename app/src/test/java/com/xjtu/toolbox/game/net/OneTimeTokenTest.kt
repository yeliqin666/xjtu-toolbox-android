package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeTokenTest {

    @Test
    fun `第一次消费成功，之后恒为 false`() {
        val token = OneTimeToken()
        assertFalse(token.isConsumed())
        assertTrue(token.consume())
        assertTrue(token.isConsumed())
        // 第三台设备（甚至是同一台再连一次）拿同一份码再来，一律拒绝
        assertFalse(token.consume())
    }

    @Test
    fun `生成的口令长度固定且不含易混字符`() {
        repeat(20) {
            val t = OneTimeToken.generate()
            // 长度即熵：12 位 × 32 字符表 ≈ 60 bit，抓到握手的人做离线爆破才不划算
            assertEquals(12, t.length)
            assertTrue(t.none { c -> c in "0O1I" })
        }
    }
}
