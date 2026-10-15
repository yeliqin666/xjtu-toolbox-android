package com.xjtu.toolbox.platform

/**
 * **zlib 格式的压缩 / 解压**（纯 Kotlin，三端同一份）—— 浏览器端靠它把分享码那条链走通。
 *
 * ## 这个格式到底是什么（先纠一处旧说法）
 *
 * 分享码那条链一直是「压缩 → URL-safe Base64」，而那里的「压缩」**不是**原始 deflate：它是
 * `new java.util.zip.Deflater(Deflater.BEST_COMPRESSION)` 产出的 **zlib 流** ——
 * 两字节头（`0x78 0xDA` = 32K 窗口 + 最高压缩档）+ deflate 数据 + 4 字节大端 Adler-32。
 * 实测（`:core:jvmTest` 的 `ShareCodeInteropJvmTest` 与 `ZlibCodecJvmTest` 都钉着这条）：
 *
 * ```
 * zlib level 9: 78 DA 33 AD 79 39 63 FF ... 00 E0 63 1E F3   ← 尾 4 字节 = adler32(E0631EF3)
 * ```
 *
 * `platform/Deflate.kt` 的 KDoc 原先写「原始 deflate，无 zlib 头 / 无 gzip 头」，**那句是错的**
 * （默认构造器就是带 zlib 头的那一种；也正因为如此，喂给它一串随便的字节会得到
 * `DataFormatException: incorrect header check`，而不是「解出一堆乱码」）。这一份实现按**真实格式**
 * 写：解压时认 zlib 头与 Adler-32，压缩时照样加上。旧说法这一次一并改正（`Deflate.kt` 那份 KDoc）。
 *
 * ## 为什么要有这一份
 *
 * `platform/Deflate.kt` 那条缝的 androidMain / jvmMain 两份 actual 用的是 `java.util.zip`
 * （`Deflater` / `Inflater`），`:core` 的 commonMain 里没有 ⇒ wasmJs 那一端原先**如实返回 null**。
 * 这一份就是补上浏览器那一端：**纯 Kotlin、放 commonMain**，不是在 `:web` 里另写一份。
 *
 * ## 与 `java.util.zip` 的关系（逐条对齐的地方与**不**对齐的地方）
 *
 * | 这一格 | 这一份 | `java.util.zip` | 是否一致 |
 * |---|---|---|---|
 * | 格式 | zlib（头 + deflate + Adler-32） | 同 | **一致**（所以两端互相解得开） |
 * | 压缩**字节** | 固定 Huffman（`BTYPE=01`）+ 贪心 LZ77 | 动态 Huffman（`BTYPE=10`）+ zlib 的惰性匹配 | **不一致**（同输入压出来的字节不同；压缩率略低） |
 * | 解压能力 | 存储块 / 固定 Huffman / 动态 Huffman 三种块都认 | 同 | **一致** |
 * | 头部不对 / 数据损坏 | null | `DataFormatException`（由 `runCatching` 变成 null） | 一致 |
 * | 输入被截断 | 返回已经解出来的那一段 | 同（`inflate` 返回 0 且 `needsInput` 就停） | 一致 |
 * | 输出上限 [inflate] 的 `maxBytes` | 到上限**正好**停下 | 按 4KB 分块，最多**多解** 4095 字节 | 更严（两边都会被上层按字段形状拒掉） |
 *
 * 「压缩字节不同」这件事为什么不算互认失败：分享码的语义是**两端都能解开同一份档案**，
 * 而不是「同一份档案压出来的字节必须一样」。所以 ① 那边（矩阵）要求逐模块相同，
 * ② 这边要求的是**互相解得开** —— 两个方向都在 `ShareCodeInteropJvmTest` 里钉住了。
 *
 * ## 算法出处
 *
 * 压缩与解压的格式、块类型、长度/距离表、固定 Huffman 的码长、Adler-32 都来自 RFC 1950 / 1951
 * （deflate 规范）；「按码长正整数字典序解码」用的是规范描述的那套经典做法（先按栈式累加码值，
 * 再与每个长度的首码比较）。没有引任何第三方压缩库（树外依赖只多了一份标准表的常数）。
 */
internal object ZlibCodec {

    /**
     * 压一段字节（zlib 流）。与 `java.util.zip.Deflater(Deflater.BEST_COMPRESSION)` 同一种封装、
     * 但**压缩出来的字节不一定相同**（见文件头那张表）。
     *
     * @return null = 压缩过程中出错（正常输入不会走到；与两份 actual 的 `runCatching` 同一条口径）
     */
    fun deflate(raw: ByteArray): ByteArray? = runCatching { ZlibDeflater(raw).run() }.getOrNull()

    /**
     * 解压一段 zlib 流。
     *
     * @param maxBytes 输出上限：分享码来自别人，别让一段几百字节的输入膨胀成几十兆。
     *   达到上限就停下并返回已经解出来的那一部分（与 `java.util.zip` 那条循环的判据同义，
     *   只是这里**正好**停在上限，不像它按 4KB 分块最多多解 4KB）。
     * @return null = 头部不对 / 数据损坏（与 `DataFormatException` 同一档），或输出为 null 以外的错误
     */
    fun inflate(bytes: ByteArray, maxBytes: Int): ByteArray? = runCatching {
        ZlibInflater(bytes, maxBytes).run()
    }.getOrNull()
}

/** Adler-32（RFC 1950 §9）：zlib 流的尾巴。与 `java.util.zip.Adler32` 同一个值。 */
internal fun adler32(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
    val mod = 65521
    var a = 1
    var b = 0
    var i = from
    while (i < to) {
        // 分块大小 2000：a 最大 65520 + 2000×255 = 575520，b 在这一块里最多再加
        // 2000×575520 ≈ 1.15e9 < Int.MAX（zlib 用的是 5552，那个数只在 64 位累加下安全）
        val end = minOf(i + 2000, to)
        while (i < end) {
            a += bytes[i].toInt() and 0xFF
            b += a
            i++
        }
        a %= mod
        b %= mod
    }
    return (b shl 16) or a
}

/**
 * 解压一个 zlib 流（RFC 1951 的三种块 + RFC 1950 的头与尾巴）。
 *
 * 输入被截断时**不**当错误：返回已经解出来的那一段（与 `java.util.zip.Inflater` 的行为一致，
 * 也是那条缝 KDoc 里「达到上限就停下并返回已经解出来的那一部分」的同一条口径）。真正的
 * 结构错误（头部、Huffman 表、块类型、距离越界、Adler-32 对不上）一律抛异常，由 [ZlibCodec.inflate]
 * 变成 null。
 */
private class ZlibInflater(private val input: ByteArray, private val maxBytes: Int) : DeflateBitSource {

    private var pos: Int
    private var bitBuffer = 0
    private var bitCount = 0
    private var out = ByteArray(minOf(maxOf(maxBytes, 64), 1 shl 16))
    private var outSize = 0
    private var truncated = false

    init {
        // zlib 头（RFC 1950 §2.2）：CM 必须是 8（deflate），(CMF<<8|FLG) 必须能被 31 整除。
        if (input.size < 2) {
            // 连头都不够：`java.util.zip` 在这一档返回空（它是「还等着更多输入」，不是「坏了」）
            truncated = true
            pos = input.size
        } else {
            val cmf = input[0].toInt() and 0xFF
            val flg = input[1].toInt() and 0xFF
            require(cmf and 0x0F == 8) { "不是 deflate（CM=${cmf and 0x0F}）" }
            require(cmf ushr 4 <= 7) { "窗口太大（CINFO=${cmf ushr 4}）" }
            require((cmf shl 8 or flg) % 31 == 0) { "zlib 头部校验位不对" }
            require(flg and 0x20 == 0) { "带预置字典的流不支持" }
            pos = 2
        }
    }

    fun run(): ByteArray = try {
        decodeStream()
    } catch (_: TruncatedInput) {
        // 输入被截断不是错误（与 `java.util.zip` 同一条口径）：把已经解出来的那一段交出去
        takeOutput()
    }

    private fun decodeStream(): ByteArray {
        var final = false
        while (!final && !truncated) {
            final = readBits(1) == 1
            when (readBits(2)) {
                0 -> readStoredBlock()
                1 -> inflateBlock(FIXED_LITERAL, FIXED_DISTANCE)
                2 -> {
                    val (literal, distance) = readDynamicTables()
                    inflateBlock(literal, distance)
                }
                else -> throw IllegalStateException("块类型 3 不存在")
            }
        }
        // 尾巴：流完整时才校验 Adler-32（截断/被上限截住的都不校验，与 `java.util.zip` 一致）。
        // 先对齐到字节边界：deflate 的最后一个块会停在半个字节上，剩下那几位是补位。
        if (!final || truncated) return takeOutput()
        alignToByte()
        if (remaining() >= 4) {
            val expected = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
            require(expected == adler32(out, 0, outSize)) { "Adler-32 对不上" }
        }
        return takeOutput()
    }

    private fun takeOutput(): ByteArray = out.copyOf(outSize)

    // ── 三种块 ──────────────────────────────────────────────

    private fun readStoredBlock() {
        alignToByte()
        val len = readByte() or (readByte() shl 8)
        val nlen = readByte() or (readByte() shl 8)
        require(len xor 0xFFFF == nlen) { "存储块长度字段对不上" }
        for (i in 0 until len) append(readByte())
    }

    private fun inflateBlock(literal: Huffman, distance: Huffman) {
        while (!truncated) {
            val symbol = literal.decode(this)
            when {
                symbol < 256 -> append(symbol)
                symbol == 256 -> return
                else -> {
                    val lengthIndex = symbol - 257
                    require(lengthIndex < LENGTH_BASE.size) { "长度码越界：$symbol" }
                    val length = LENGTH_BASE[lengthIndex] + readBits(LENGTH_EXTRA[lengthIndex])
                    val distIndex = distance.decode(this)
                    require(distIndex < DIST_BASE.size) { "距离码越界：$distIndex" }
                    val back = DIST_BASE[distIndex] + readBits(DIST_EXTRA[distIndex])
                    require(back <= outSize) { "距离指到了输出之前：$back > $outSize" }
                    // 逐字节复制：deflate 允许「重叠复制」（距离小于长度），所以不能整体 copy
                    var i = 0
                    while (i < length) {
                        if (outSize >= maxBytes) {
                            truncated = true
                            return
                        }
                        append(out[outSize - back].toInt() and 0xFF)
                        i++
                    }
                }
            }
            if (outSize >= maxBytes) {
                truncated = true
                return
            }
        }
    }

    /** 读动态 Huffman 的两张表（RFC 1951 §3.2.7）。 */
    private fun readDynamicTables(): Pair<Huffman, Huffman> {
        val hlit = readBits(5) + 257
        val hdist = readBits(5) + 1
        val hclen = readBits(4) + 4
        require(hlit <= 286) { "字面码表太长：$hlit" }
        require(hdist <= 30) { "距离码表太长：$hdist" }

        val codeLengthLengths = IntArray(19)
        for (i in 0 until hclen) codeLengthLengths[CODE_LENGTH_ORDER[i]] = readBits(3)
        val codeLengthCode = Huffman(codeLengthLengths, 19)

        // 码长表本身也是 Huffman 编的：16 = 重复上一个 3~6 次，17/18 = 补 0 3~10 / 11~138 次
        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            val symbol = codeLengthCode.decode(this)
            when {
                symbol < 16 -> lengths[i++] = symbol
                symbol == 16 -> {
                    require(i > 0) { "重复上一次码长，可是前面没有码长" }
                    val copyCount = 3 + readBits(2)
                    val previous = lengths[i - 1]
                    repeat(copyCount) { if (i < lengths.size) lengths[i++] = previous }
                }
                symbol == 17 -> {
                    val zeroCount = 3 + readBits(3)
                    repeat(zeroCount) { if (i < lengths.size) lengths[i++] = 0 }
                }
                symbol == 18 -> {
                    val zeroCount = 11 + readBits(7)
                    repeat(zeroCount) { if (i < lengths.size) lengths[i++] = 0 }
                }
                else -> throw IllegalStateException("码长符号越界：$symbol")
            }
        }
        require(lengths[256] != 0) { "没有块结束符（256）的码长" }
        return Huffman(lengths.copyOfRange(0, hlit), hlit) to
            Huffman(lengths.copyOfRange(hlit, hlit + hdist), hdist)
    }

    // ── 位读取（deflate 的位序：**低位在前**） ──────────────

    override fun nextBit(): Int {
        if (bitCount == 0) {
            if (pos >= input.size) {
                truncated = true
                throw TruncatedInput
            }
            bitBuffer = input[pos++].toInt() and 0xFF
            bitCount = 8
        }
        val bit = bitBuffer and 1
        bitBuffer = bitBuffer ushr 1
        bitCount--
        return bit
    }

    /** 读 [count] 位，低位在前。输入不够时置 [truncated] 并抛出去（由调用方在循环里收手）。 */
    private fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) result = result or (nextBit() shl i)
        return result
    }

    private fun alignToByte() {
        bitCount = 0
    }

    private fun readByte(): Int {
        if (bitCount != 0) throw IllegalStateException("读字节前没有对齐")
        if (pos >= input.size) {
            truncated = true
            throw TruncatedInput
        }
        return input[pos++].toInt() and 0xFF
    }

    private fun remaining(): Int = input.size - pos

    // ── 输出 ────────────────────────────────────────────────

    private fun append(byte: Int) {
        if (outSize >= maxBytes) {
            truncated = true
            return
        }
        if (outSize == out.size) out = out.copyOf(minOf(out.size * 2, maxOf(maxBytes, out.size + 1)))
        out[outSize++] = byte.toByte()
    }

    /**
     * 输入被截断的信号（换成「返回已经解出来的那一段」，不是错误）。
     *
     * 刻意**不**覆盖 `fillInStackTrace`（JVM 上有这个成员，wasm 上没有 —— 实测 wasm 目标直接报
     * 「overrides nothing」）。这条信号每读一位都可能抛，代价是有的，但它只在**被截断的**输入上出现，
     * 正常分享码一次也不会抛。
     */
    private object TruncatedInput : RuntimeException()

    private companion object {
        /**
         * RFC 1951 §3.2.6 的固定 Huffman 表：字面/长度码的码长是 0-143→8、144-255→9、256-279→7、280-287→8，
         * 距离码是 32 个 5 位的码。两张表都在流里**不出现**（规范定死的），所以直接建好放在这里。
         */
        val FIXED_LITERAL = Huffman(
            IntArray(288) { i ->
                when {
                    i <= 143 -> 8
                    i <= 255 -> 9
                    i <= 279 -> 7
                    else -> 8
                }
            },
            288,
        )

        /** 固定 Huffman 的距离表（见 [FIXED_LITERAL]）。 */
        val FIXED_DISTANCE = Huffman(IntArray(32) { 5 }, 32)

        /** 码长表的码长按这个顺序写在流里（RFC 1951 §3.2.7）。 */
        val CODE_LENGTH_ORDER = intArrayOf(
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
        )
    }
}

/** RFC 1951 的长度码基值与附加位数（码 257..285）。 */
internal val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
)

internal val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
)

/** 长度 → 长度码下标（调用方再加 257）。按 RFC 1951 §3.2.5 铺开：每个长度码覆盖一段连续的长度。 */
internal val LENGTH_CODE_BY_LENGTH = IntArray(259) { -1 }.also { table ->
    for (i in LENGTH_BASE.indices) {
        val last = LENGTH_BASE[i] + (1 shl LENGTH_EXTRA[i]) - 1
        for (length in LENGTH_BASE[i]..last) table[length] = i
    }
}

/** RFC 1951 的距离码基值与附加位数（码 0..29）。 */
internal val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
    257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)

internal val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
    7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)

/**
 * 一张 Huffman 表（按码长表建出来），解码时用「按码长累加码值」的经典做法。
 *
 * 为什么不用查表（快表）：这里解的是几百字节到几十 KB 的分享码，逐位走那 15 层循环的开销可以忽略；
 * 少一张表就少一处可能与规范不一致的地方。
 */
internal class Huffman(lengths: IntArray, private val numSymbols: Int) {

    private val counts = IntArray(MAX_BITS + 1)
    private val symbols: IntArray

    init {
        require(lengths.size == numSymbols) { "码长表长度不对：${lengths.size} != $numSymbols" }
        for (i in 0 until numSymbols) {
            require(lengths[i] <= MAX_BITS) { "码长超过 $MAX_BITS：${lengths[i]}" }
            counts[lengths[i]]++
        }
        counts[0] = 0

        // 码长表必须既不为过满（Kraft 不等式 ≤ 1）也不缺额（除了「只有一个码」那种特殊情况）。
        var left = 1
        var maxLength = 0
        for (len in 1..MAX_BITS) {
            left = left shl 1
            left -= counts[len]
            if (counts[len] > 0) maxLength = len
            require(left >= 0) { "码长表过满" }
        }
        // 不完整的表只在「只有一个 1 位的码」那一档合法（deflate 规定只有一个距离码时也这么编，
        // 见 RFC 1951 §3.2.7 末尾）；其余情况说明这段流不是真 deflate。
        require(left == 0 || maxLength == 1) { "码长表不完整（还有 $left 个空位）" }

        val offsets = IntArray(MAX_BITS + 2)
        var total = 0
        for (len in 1..MAX_BITS) {
            offsets[len] = total
            total += counts[len]
        }
        symbols = IntArray(total)
        val next = offsets.copyOf()
        for (symbol in 0 until numSymbols) {
            val len = lengths[symbol]
            if (len != 0) symbols[next[len]++] = symbol
        }
    }

    /** 解一个符号；位不够时抛 [ZlibInflater] 里的截断信号（由调用方收手）。 */
    fun decode(inflater: DeflateBitSource): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = code or inflater.nextBit()
            val count = counts[len]
            if (code - count < first) return symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw IllegalStateException("Huffman 码解不出来")
    }

    private companion object {
        /** deflate 的码长上限（RFC 1951 §3.2.7）。 */
        const val MAX_BITS = 15
    }
}

/** 让 [Huffman] 能一位一位地读源码（由 zlib 解压器实现）。 */
internal interface DeflateBitSource {
    /** 读一位（deflate 的位序：**低位在前**）。 */
    fun nextBit(): Int
}
