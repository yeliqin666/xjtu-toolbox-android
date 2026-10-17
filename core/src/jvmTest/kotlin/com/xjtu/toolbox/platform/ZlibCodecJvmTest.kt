package com.xjtu.toolbox.platform

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 纯 Kotlin 那一份 zlib（[ZlibCodec]）与 `java.util.zip` 的关系的机器可验版本。 */
class ZlibCodecJvmTest {

    // ── 方向一：纯 Kotlin 压 → java.util.zip 解 ──────────────

    @Test
    fun `纯 Kotlin 压出来的 zlib 流 java util zip 解得开`() {
        for (raw in corpus()) {
            val packed = assertNotNullBytes(ZlibCodec.deflate(raw), "纯 Kotlin 压不出：${raw.size} 字节")
            assertContentEquals(raw, jdkInflate(packed, 1 shl 20), "java.util.zip 解出来的不是原文（${raw.size} 字节）")
            // zlib 头与裁剪：头两字节是「32K 窗口 + 最高压缩档」那份（与 Deflater(BEST_COMPRESSION) 同一个头）
            assertEquals(0x78, packed[0].toInt() and 0xFF, "zlib 头的 CMF 不对")
            assertEquals(0xDA, packed[1].toInt() and 0xFF, "zlib 头的 FLG 不对")
            // 尾巴 4 字节大端 Adler-32
            val trailer = ((packed[packed.size - 4].toInt() and 0xFF) shl 24) or
                ((packed[packed.size - 3].toInt() and 0xFF) shl 16) or
                ((packed[packed.size - 2].toInt() and 0xFF) shl 8) or
                (packed[packed.size - 1].toInt() and 0xFF)
            assertEquals(adler32(raw), trailer, "流尾巴不是输入的 Adler-32（${raw.size} 字节）")
        }
    }

    // ── 方向二：java.util.zip 压 → 纯 Kotlin 解 ──────────────

    @Test
    fun `java util zip 压出来的 zlib 流这一份解得开`() {
        for (raw in corpus()) {
            val packed = jdkDeflate(raw)
            assertContentEquals(
                raw,
                ZlibCodec.inflate(packed, 1 shl 20),
                "纯 Kotlin 解出来的不是原文（${raw.size} 字节）",
            )
        }
    }

    @Test
    fun `解压器认三种块：存储块 固定 Huffman 动态 Huffman`() {
        // 压不动（随机字节）⇒ zlib 会发存储块；能压动（重复文本）⇒ 动态 Huffman；
        // 另外自己拿固定 Huffman 那一份压一段（纯 Kotlin 的实现只发这一种块）。
        val random = Random(20261015)
        val incompressible = ByteArray(4096) { random.nextInt(256).toByte() }
        val compressible = "课表网格 0000000000000000000 课表网格\n".repeat(200).encodeToByteArray()

        assertEquals(0, jdkDeflate(incompressible)[2].toInt() and 0x06, "这一份输入该走存储块（BTYPE=00）")
        assertContentEquals(
            incompressible,
            ZlibCodec.inflate(jdkDeflate(incompressible), 1 shl 20),
            "存储块没认出来",
        )
        assertEquals(0x04, jdkDeflate(compressible)[2].toInt() and 0x06, "这一份输入该走动态 Huffman（BTYPE=10）")
        assertContentEquals(
            compressible,
            ZlibCodec.inflate(jdkDeflate(compressible), 1 shl 20),
            "动态 Huffman 没认出来",
        )
        // 固定 Huffman（BTYPE=01）—— 这一份自己的输出就是它
        assertEquals(2, ZlibCodec.deflate(compressible)!![2].toInt() and 0x06, "这一份的输出该是固定 Huffman")
        assertContentEquals(compressible, jdkInflate(ZlibCodec.deflate(compressible)!!, 1 shl 20))
    }

    // ── 坏数据 ──────────────────────────────────────────────

    @Test
    fun `坏数据一律 null，与 java util zip 的判据一致`() {
        val good = jdkDeflate("5|阿离|1000000000000000000000|CS0001~高等数学".encodeToByteArray())
        val badAdler = good.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val badCmf = good.clone().also { it[0] = 0x77 }
        val badCheck = good.clone().also { it[1] = (it[1].toInt() xor 0x01).toByte() }
        // 头之后写一个「BFINAL=1 + BTYPE=11」的字节：块类型 3 不存在
        val badType = byteArrayOf(0x78, 0x9C.toByte(), 0b111.toByte(), 0, 0, 0, 0)

        for ((label, bytes) in listOf(
            "不是 zlib 流" to byteArrayOf(1, 2, 3, 4),
            "CMF 不对" to badCmf,
            "FCHECK 不对" to badCheck,
            "Adler-32 不对" to badAdler,
            "块类型 3" to badType,
        )) {
            assertNull(inflateBytes(bytes, 1 shl 20), "java.util.zip 那一份应当拒绝：$label")
            assertNull(ZlibCodec.inflate(bytes, 1 shl 20), "纯 Kotlin 那一份应当拒绝：$label")
        }
    }

    @Test
    fun `输入被截断时两边都给已经解出来的那一段 而不是 null`() {
        val raw = ("5|阿离|" + "10".repeat(38) + "|CS0001~高等数学").encodeToByteArray()
        val packed = jdkDeflate(raw)
        // 砍掉 Adler-32 四个字节（流本身完整）
        val cutTrailer = packed.copyOf(packed.size - 4)
        assertContentEquals(raw, inflateBytes(cutTrailer, 1 shl 20), "截了尾巴也该解出全文")
        assertContentEquals(raw, ZlibCodec.inflate(cutTrailer, 1 shl 20), "截了尾巴也该解出全文")
        // 砍到 deflate 数据中间：两边都只给「已经解出来的那一段」（是原文的前缀）
        val cutBody = packed.copyOf(packed.size - 8)
        val byJdk = assertNotNullBytes(inflateBytes(cutBody, 1 shl 20), "java.util.zip 该给前缀")
        val byPure = assertNotNullBytes(ZlibCodec.inflate(cutBody, 1 shl 20), "纯 Kotlin 该给前缀")
        assertTrue(
            raw.copyOfRange(0, byJdk.size).contentEquals(byJdk),
            "java.util.zip 给的应当是原文前缀（${byJdk.size} 字节）",
        )
        assertTrue(
            raw.copyOfRange(0, byPure.size).contentEquals(byPure),
            "纯 Kotlin 给的应当是原文前缀（${byPure.size} 字节）",
        )
        // 空输入与只有一个字节：两边都给空（那只是「还等着更多输入」，不是坏数据）
        assertContentEquals(ByteArray(0), inflateBytes(ByteArray(0), 1 shl 20))
        assertContentEquals(ByteArray(0), ZlibCodec.inflate(ByteArray(0), 1 shl 20))
        assertContentEquals(ByteArray(0), ZlibCodec.inflate(byteArrayOf(0x78), 1 shl 20))
    }

    @Test
    fun `输出上限这一份正好停在上限，返回的是原文前缀`() {
        val raw = ("课表网格".repeat(500)).encodeToByteArray()
        val packed = ZlibCodec.deflate(raw)!!
        val capped = assertNotNullBytes(ZlibCodec.inflate(packed, 100), "到上限也该给那一段")
        assertEquals(100, capped.size, "上限是 100 就该正好给 100 字节")
        assertContentEquals(raw.copyOfRange(0, 100), capped, "给出来的应当是原文前缀")
    }

    @Test
    fun `Adler-32 与 java util zip 的值相同`() {
        for (raw in corpus()) {
            val byJdk = Adler32().also { it.update(raw) }.value.toInt()
            assertEquals(byJdk, adler32(raw), "Adler-32 不一致（${raw.size} 字节）")
        }
    }

    // ── 夹具与工具 ──────────────────────────────────────────

    /** 输入样本：空、一字节、中文、重复文本、随机字节（压不动）、几十 KB 的长文本。 */
    private fun corpus(): List<ByteArray> {
        val random = Random(20261015)
        return listOf(
            ByteArray(0),
            byteArrayOf(0x41),
            "阿离 的课表：高等数学（一）".encodeToByteArray(),
            "课表".repeat(500).encodeToByteArray(),
            ByteArray(2048) { random.nextInt(256).toByte() },
            ("5|阿离|" + "10".repeat(38) + "|CS0001~高等数学|" + "主楼".repeat(40))
                .encodeToByteArray(),
            ("课程名称名称名称".repeat(2000)).encodeToByteArray(),
        )
    }

    /** `java.util.zip` 的 deflate，当基准。 */
    private fun jdkDeflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** `java.util.zip` 的 inflate，当基准。 */
    private fun jdkInflate(bytes: ByteArray, maxBytes: Int): ByteArray = assertNotNullBytes(
        runCatching {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (!inflater.finished() && out.size() <= maxBytes) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
            } finally {
                inflater.end()
            }
            out.toByteArray()
        }.getOrNull(),
        "java.util.zip 应当解得开",
    )

    private fun assertNotNullBytes(value: ByteArray?, message: String): ByteArray {
        assertTrue(value != null, message)
        return value!!
    }
}
