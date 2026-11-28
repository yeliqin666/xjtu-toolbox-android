package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetProtocolTest {

    @Test
    fun `hello 消息编解码往返`() {
        val nonce = HandshakeAuth.newNonce()
        val proof = HandshakeAuth.guestProof("TOKEN123", nonce)
        val line = NetCodec.encode(NetCodec.hello(nonce, proof, GameKind.GOMOKU))
        assertTrue("编码结果不能带换行", !line.contains("\n"))
        val decoded = NetCodec.decode(line)
        assertEquals(NetMsgType.HELLO, decoded?.type)
        assertEquals(nonce, decoded?.nonce)
        assertEquals(proof, decoded?.proof)
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
    fun `hello_ack 下发对局参数和房主的证明`() {
        val nonce = HandshakeAuth.newNonce()
        val proof = HandshakeAuth.hostProof("TOKEN123", "guest-nonce", nonce)
        val line = NetCodec.encode(
            NetCodec.helloAck(true, nonce = nonce, proof = proof, rule = "{\"size\":19}", hostFirst = false),
        )
        val decoded = NetCodec.decode(line)
        assertEquals(true, decoded?.ok)
        assertEquals(nonce, decoded?.nonce)
        assertEquals(proof, decoded?.proof)
        assertEquals("{\"size\":19}", decoded?.rule)
        assertEquals(false, decoded?.hostFirst)
    }

    @Test
    fun `握手报文里不会出现口令原文`() {
        // 这条不是"风格检查"：一旦有人把口令塞回信封里，链路上就又出现了可直接兑换的凭据，
        // 而且没有任何编译期约束能拦住他。序列化后逐个字段看一遍，成本很低。
        val token = "ABCDEFGHJKLM"
        val guestNonce = HandshakeAuth.newNonce()
        val hello = NetCodec.encode(NetCodec.hello(guestNonce, HandshakeAuth.guestProof(token, guestNonce), GameKind.GO))
        assertTrue(hello, !hello.contains(token))

        val hostNonce = HandshakeAuth.newNonce()
        val ack = NetCodec.encode(
            NetCodec.helloAck(true, nonce = hostNonce, proof = HandshakeAuth.hostProof(token, guestNonce, hostNonce)),
        )
        assertTrue(ack, !ack.contains(token))
        assertNotEquals("两端的 nonce 不能相同", guestNonce, hostNonce)
    }
}
