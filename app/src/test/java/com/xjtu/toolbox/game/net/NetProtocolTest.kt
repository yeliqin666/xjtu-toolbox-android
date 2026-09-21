package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetProtocolTest {

    @Test
    fun `hello 消息编解码往返`() {
        val original = NetCodec.hello("TOKEN123", GameKind.GOMOKU, "{}", hostFirst = true)
        val line = NetCodec.encode(original)
        assertTrue("编码结果不能带换行", !line.contains("\n"))
        val decoded = NetCodec.decode(line)
        assertEquals(NetMsgType.HELLO, decoded?.type)
        assertEquals("TOKEN123", decoded?.token)
        assertEquals(GameKind.GOMOKU.wireId, decoded?.game)
        assertEquals(true, decoded?.hostFirst)
    }

    @Test
    fun `move 消息编解码往返`() {
        val line = NetCodec.encode(NetCodec.move(7, "7,7"))
        val decoded = NetCodec.decode(line)
        assertEquals(NetMsgType.MOVE, decoded?.type)
        assertEquals(7, decoded?.seq)
        assertEquals("7,7", decoded?.move)
    }

    @Test
    fun `解析不了的内容返回 null 而不是抛异常`() {
        assertNull(NetCodec.decode("这不是 JSON"))
        assertNull(NetCodec.decode(""))
        assertNull(NetCodec.decode("{}")) // 没有 type 字段
    }

    @Test
    fun `hello_ack 能带上 token 供房主核实`() {
        val line = NetCodec.encode(NetCodec.helloAck(true, token = "ABC"))
        val decoded = NetCodec.decode(line)
        assertEquals(true, decoded?.ok)
        assertEquals("ABC", decoded?.token)
    }
}
