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
        val line = NetCodec.encode(NetCodec.hello(nonce, GameKind.GOMOKU))
        assertTrue("编码结果不能带换行", !line.contains("\n"))
        val decoded = NetCodec.decode(line)
        assertEquals(NetMsgType.HELLO, decoded?.type)
        assertEquals(nonce, decoded?.nonce)
        assertEquals(GameKind.GOMOKU.wireId, decoded?.game)
        // 第 1 步不该带任何凭据
        assertNull(decoded?.proof)
    }

    @Test
    fun `challenge 与 auth 编解码往返`() {
        val guestNonce = HandshakeAuth.newNonce()
        val hostNonce = HandshakeAuth.newNonce()
        val challenge = NetCodec.decode(
            NetCodec.encode(NetCodec.challenge(hostNonce, HandshakeAuth.challengeProof("TOKEN", guestNonce, hostNonce))),
        )
        assertEquals(NetMsgType.CHALLENGE, challenge?.type)
        assertEquals(hostNonce, challenge?.nonce)

        val auth = NetCodec.decode(
            NetCodec.encode(NetCodec.auth(HandshakeAuth.authProof("TOKEN", guestNonce, hostNonce, "gomoku"))),
        )
        assertEquals(NetMsgType.AUTH, auth?.type)
        assertEquals(64, auth?.proof?.length)
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
    fun `hello_ack 下发对局参数和它的证明`() {
        val proof = HandshakeAuth.ackProof("TOKEN123", "g", "h", "{\"size\":19}", false)
        val line = NetCodec.encode(
            NetCodec.helloAck(true, proof = proof, rule = "{\"size\":19}", hostFirst = false),
        )
        val decoded = NetCodec.decode(line)
        assertEquals(true, decoded?.ok)
        assertEquals(proof, decoded?.proof)
        assertEquals("{\"size\":19}", decoded?.rule)
        assertEquals(false, decoded?.hostFirst)
    }

    @Test
    fun `握手四条报文里都不会出现口令原文`() {
        // 这条不是"风格检查"：一旦有人把口令塞回信封里，链路上就又出现了可直接兑换的凭据，
        // 而且没有任何编译期约束能拦住他。序列化后逐个字段看一遍，成本很低。
        val token = "ABCDEFGHJKLM"
        val guestNonce = HandshakeAuth.newNonce()
        val hostNonce = HandshakeAuth.newNonce()
        val lines = listOf(
            NetCodec.encode(NetCodec.hello(guestNonce, GameKind.GO)),
            NetCodec.encode(NetCodec.challenge(hostNonce, HandshakeAuth.challengeProof(token, guestNonce, hostNonce))),
            NetCodec.encode(NetCodec.auth(HandshakeAuth.authProof(token, guestNonce, hostNonce, GameKind.GO.wireId))),
            NetCodec.encode(
                NetCodec.helloAck(
                    ok = true,
                    proof = HandshakeAuth.ackProof(token, guestNonce, hostNonce, null, true),
                    rule = null,
                    hostFirst = true,
                ),
            ),
        )
        for (line in lines) assertTrue(line, !line.contains(token))
        assertNotEquals("两端的 nonce 不能相同", guestNonce, hostNonce)
    }
}
