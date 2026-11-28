package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetProtocolTest {

    @Test
    fun `hello 消息编解码往返`() {
        val original = NetCodec.hello("TOKEN123", GameKind.GOMOKU)
        val line = NetCodec.encode(original)
        assertTrue("编码结果不能带换行", !line.contains("\n"))
        val decoded = NetCodec.decode(line)
        assertEquals(NetMsgType.HELLO, decoded?.type)
        assertEquals("TOKEN123", decoded?.token)
        assertEquals(GameKind.GOMOKU.wireId, decoded?.game)
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
    fun `hello_ack 下发对局参数且不带口令`() {
        val line = NetCodec.encode(NetCodec.helloAck(true, rule = "{\"size\":19}", hostFirst = false))
        val decoded = NetCodec.decode(line)
        assertEquals(true, decoded?.ok)
        assertEquals("{\"size\":19}", decoded?.rule)
        assertEquals(false, decoded?.hostFirst)
        // 口令只在加入方 → 房主这一个方向出现；房主回包带上它就等于把口令又发了一遍。
        assertNull(decoded?.token)
    }
}
