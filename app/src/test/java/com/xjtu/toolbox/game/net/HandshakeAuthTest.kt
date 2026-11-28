package com.xjtu.toolbox.game.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ByteArray.hex(): String = joinToString("") { b ->
    val v = b.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
}

/**
 * SHA-256 / HMAC 是手写的（commonMain 没有平台摘要可用），所以必须拿标准向量钉住——
 * 这类代码写错了不会崩，只会算出一个"看起来像哈希"的错值，而两端用的是同一份实现，
 * 握手照样能跑通，谁也不会发现。
 */
class HandshakeAuthTest {

    @Test
    fun `SHA-256 对上 FIPS 180-4 的标准向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hash(ByteArray(0)).hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hash("abc".encodeToByteArray()).hex(),
        )
        // 跨过 64 字节块边界的一条（55 / 56 / 64 字节三个位置最容易把补位写错）
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hash(ByteArray(1_000_000) { 'a'.code.toByte() }).hex(),
        )
    }

    @Test
    fun `HMAC-SHA256 对上 RFC 4231 的标准向量`() {
        // 用例 1：20 字节 0x0b 作密钥
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacSha256.mac(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex(),
        )
        // 用例 2：短密钥、普通文本——握手用的就是这个形状（口令当密钥）
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacSha256.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex(),
        )
        // 用例 6：密钥比块长（64 字节）还长，要先哈希再用
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            HmacSha256.mac(
                ByteArray(131) { 0xaa.toByte() },
                "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            ).hex(),
        )
    }

    @Test
    fun `每段证明都绑死对方的 nonce，换个 nonce 就不是同一个证明`() {
        val token = "ABCDEFGHJKLM"
        val g1 = HandshakeAuth.newNonce()
        val g2 = HandshakeAuth.newNonce()
        val h1 = HandshakeAuth.newNonce()
        val h2 = HandshakeAuth.newNonce()
        assertNotEquals(g1, g2)
        assertNotEquals(HandshakeAuth.challengeProof(token, g1, h1), HandshakeAuth.challengeProof(token, g2, h1))
        assertNotEquals(HandshakeAuth.challengeProof(token, g1, h1), HandshakeAuth.challengeProof(token, g1, h2))
        // 加入方的应答必须绑房主的 nonce：这是"抓包重放"那条洞的堵点
        assertNotEquals(
            HandshakeAuth.authProof(token, g1, h1, "gomoku"),
            HandshakeAuth.authProof(token, g1, h2, "gomoku"),
        )
        // 同理，同一个 nonce 换个顺序也不能算出同一个值
        assertNotEquals(
            HandshakeAuth.challengeProof(token, g1, h1),
            HandshakeAuth.challengeProof(token, h1, g1),
        )
    }

    @Test
    fun `对局参数进了房主的证明，改一个字段就对不上`() {
        val token = "ABCDEFGHJKLM"
        val g = HandshakeAuth.newNonce()
        val h = HandshakeAuth.newNonce()
        val base = HandshakeAuth.ackProof(token, g, h, "{\"size\":19}", true)
        assertTrue(HandshakeAuth.sameProof(base, HandshakeAuth.ackProof(token, g, h, "{\"size\":19}", true)))
        // 中继能原样转发证明，但改参数就再也算不出同一个值——加入方会当场断开
        assertNotEquals(base, HandshakeAuth.ackProof(token, g, h, "{\"size\":9}", true))
        assertNotEquals(base, HandshakeAuth.ackProof(token, g, h, "{\"size\":19}", false))
        assertNotEquals(base, HandshakeAuth.ackProof(token, g, h, null, true))
        // null 和空串是不同的参数，编码上不能撞在一起
        assertNotEquals(
            HandshakeAuth.ackProof(token, g, h, null, true),
            HandshakeAuth.ackProof(token, g, h, "", true),
        )
    }

    @Test
    fun `口令不对算出来的证明一定不对`() {
        val g = HandshakeAuth.newNonce()
        val h = HandshakeAuth.newNonce()
        val right = HandshakeAuth.authProof("RIGHT-TOKEN", g, h, "gomoku")
        assertTrue(HandshakeAuth.sameProof(right, right))
        assertFalse(HandshakeAuth.sameProof(right, HandshakeAuth.authProof("WRONG-TOKEN", g, h, "gomoku")))
        assertFalse(HandshakeAuth.sameProof(right, null))
        assertFalse(HandshakeAuth.sameProof(right, right.dropLast(1))) // 长度不同直接否掉
        // 同长度、只差最后一位：换成的那个字符必须和原来不同，否则有 1/16 的概率"改成自己"
        val other = if (right.last() == '0') "1" else "0"
        assertFalse(HandshakeAuth.sameProof(right, right.dropLast(1) + other))
    }

    @Test
    fun `生成的 nonce 是 32 位十六进制且各不相同`() {
        val set = HashSet<String>()
        repeat(50) {
            val n = HandshakeAuth.newNonce()
            assertEquals(32, n.length)
            assertTrue(n.all { c -> c in "0123456789abcdef" })
            set.add(n)
        }
        assertEquals(50, set.size)
    }
}
