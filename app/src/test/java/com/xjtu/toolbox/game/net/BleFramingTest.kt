package com.xjtu.toolbox.game.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BleFramingTest {

    @Test
    fun `短消息只切一片`() {
        val payload = "hi".toByteArray()
        val fragments = BleFraming.fragment(msgId = 1, payload = payload, mtu = 20)
        assertTrue(fragments.size == 1)
        val out = BleReassembler().feed(fragments[0])
        assertArrayEquals(payload, out)
    }

    @Test
    fun `长消息按序喂入能完整重组`() {
        val payload = ByteArray(500) { it.toByte() }
        val fragments = BleFraming.fragment(msgId = 5, payload = payload, mtu = 23)
        val reassembler = BleReassembler()
        var result: ByteArray? = null
        for (f in fragments) {
            val r = reassembler.feed(f)
            if (r != null) result = r
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun `乱序喂入也能完整重组`() {
        val payload = "五子棋、围棋、象棋共用一个联机模块，这句话得长到能切好几片才测得出乱序问题".toByteArray()
        val fragments = BleFraming.fragment(msgId = 9, payload = payload, mtu = 24).shuffled(Random(42))
        val reassembler = BleReassembler()
        var result: ByteArray? = null
        for (f in fragments) {
            val r = reassembler.feed(f)
            if (r != null) result = r
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun `缺片时不会拼出错误结果，只会一直返回 null`() {
        val payload = ByteArray(200) { it.toByte() }
        val fragments = BleFraming.fragment(msgId = 3, payload = payload, mtu = 23)
        val reassembler = BleReassembler()
        // 故意扔掉最后一片
        for (f in fragments.dropLast(1)) {
            assertNull(reassembler.feed(f))
        }
    }

    @Test
    fun `超过 255 片应当报错而不是悄悄截断`() {
        val maxChunk = 23 - BleFraming.HEADER_SIZE
        val limit = maxChunk * BleFraming.MAX_FRAGMENTS
        // 刚好装得下：切满 255 片，一片不少。
        assertEquals(BleFraming.MAX_FRAGMENTS, BleFraming.fragment(1, ByteArray(limit), mtu = 23).size)
        // 多一个字节就该报错。以前这里 coerceIn(1, 255)，后面那截被静默丢掉，
        // 对方拼出一条缺尾巴的报文、解码失败后当噪声忽略，表现成"对方忽然不理我"。
        val error = runCatching { BleFraming.fragment(1, ByteArray(limit + 1), mtu = 23) }.exceptionOrNull()
        assertTrue("超限应当抛 IllegalArgumentException，实际是 $error", error is IllegalArgumentException)
    }

    @Test
    fun `不同消息的分片互不干扰`() {
        val a = "AAAA".toByteArray()
        val b = "BBBBBBBB".toByteArray()
        val fragmentsA = BleFraming.fragment(1, a, mtu = 6)
        val fragmentsB = BleFraming.fragment(2, b, mtu = 6)
        val reassembler = BleReassembler()
        var resultA: ByteArray? = null
        var resultB: ByteArray? = null
        // 交替喂：A 的第一片、B 的第一片、A 的第二片……长度不一样时用两个下标各走各的。
        val interleaved = mutableListOf<ByteArray>()
        val maxLen = maxOf(fragmentsA.size, fragmentsB.size)
        for (i in 0 until maxLen) {
            fragmentsA.getOrNull(i)?.let { interleaved.add(it) }
            fragmentsB.getOrNull(i)?.let { interleaved.add(it) }
        }
        for (f in interleaved) {
            val r = reassembler.feed(f)
            if (r == null) continue
            if (r.contentEquals(a)) resultA = r
            if (r.contentEquals(b)) resultB = r
        }
        assertArrayEquals(a, resultA)
        assertArrayEquals(b, resultB)
    }
}
