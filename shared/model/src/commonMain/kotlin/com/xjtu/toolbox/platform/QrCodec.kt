package com.xjtu.toolbox.platform

/** 二维码的编码器（纯 Kotlin，三端同一份）—— 与 Android / jvm 那两份 zxing actual 编出逐模块相同的矩阵。 */
internal object QrCodec {

    /** zxing 的 `ALPHANUMERIC_TABLE`（只有 ASCII 0x00..0x5f 这一段有值）。 */
    private val ALPHANUMERIC_TABLE = intArrayOf(
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        36, -1, -1, -1, 37, 38, -1, -1, -1, -1, 39, 40, -1, 41, 42, 43,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 44, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1, -1, -1,
    )

    /** UTF-8 的 ECI 编号（zxing 的 `CharacterSetECI.UTF8.getValue()`）。 */
    private const val UTF8_ECI = 26

    /** 把一段文本编成二维码矩阵（不含静默区）。 */
    fun encode(text: String): CodeMatrix? = runCatching { encodeOrThrow(text) }.getOrNull()

    private fun encodeOrThrow(content: String): CodeMatrix {
        val mode = chooseMode(content)

        // ECI 段：只有字节模式才带（zxing 的 `if (mode == Mode.BYTE && hasEncodingHint)`，
        // 而这条缝恒定给了字符集 hint ⇒ 字节模式下一定会带）。
        val headerBits = QrBitBuffer()
        if (mode == QrMode.BYTE) {
            headerBits.appendBits(QrMode.ECI.bits, 4)
            headerBits.appendBits(UTF8_ECI, 8)
        }
        headerBits.appendBits(mode.bits, 4)

        val dataBits = QrBitBuffer()
        appendBytes(content, mode, dataBits)

        val version = recommendVersion(mode, headerBits, dataBits)

        val headerAndDataBits = QrBitBuffer()
        headerAndDataBits.appendBuffer(headerBits)
        // 长度字段里的「字数」：字节模式数的是**字节**，其余模式数的是字符数。
        val numLetters = if (mode == QrMode.BYTE) dataBits.sizeInBytes else content.length
        appendLengthInfo(numLetters, version, mode, headerAndDataBits)
        headerAndDataBits.appendBuffer(dataBits)

        val numDataBytes = version.numDataCodewords
        terminateBits(numDataBytes, headerAndDataBits)

        val finalBits = interleaveWithEcBytes(
            headerAndDataBits,
            version.totalCodewords,
            numDataBytes,
            version.numBlocks,
        )

        val matrix = QrByteMatrix(version.dimension, version.dimension)
        val maskPattern = chooseMaskPattern(finalBits, version, matrix)
        QrLayout.buildMatrix(finalBits, version, maskPattern, matrix)
        return matrix.toCodeMatrix()
    }

    /** 二维码的一个**模式**（这里只搬得到那三种：数字 / 字母数字 / 字节）。 */
    private enum class QrMode(val bits: Int, private val countBits: IntArray) {
        NUMERIC(0x01, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(0x02, intArrayOf(9, 11, 13)),
        BYTE(0x04, intArrayOf(8, 16, 16)),
        ECI(0x07, intArrayOf(0, 0, 0)),
        ;

        /** zxing 的 `Mode.getCharacterCountBits`：按版本分三段（1-9 / 10-26 / 27-40）。 */
        fun characterCountBits(version: QrVersion): Int {
            val number = version.versionNumber
            val offset = if (number <= 9) 0 else if (number <= 26) 1 else 2
            return countBits[offset]
        }
    }

    /** zxing 的 `Encoder.chooseMode`：整段只有一个模式。 */
    private fun chooseMode(content: String): QrMode {
        var hasNumeric = false
        var hasAlphanumeric = false
        for (c in content) {
            val code = c.code
            if (code in '0'.code..'9'.code) {
                hasNumeric = true
            } else if (alphanumericCode(code) != -1) {
                hasAlphanumeric = true
            } else {
                return QrMode.BYTE
            }
        }
        return when {
            hasAlphanumeric -> QrMode.ALPHANUMERIC
            hasNumeric -> QrMode.NUMERIC
            else -> QrMode.BYTE
        }
    }

    /** zxing 的 `Encoder.getAlphanumericCode`：表外的字符返回 -1。 */
    private fun alphanumericCode(code: Int): Int =
        if (code < ALPHANUMERIC_TABLE.size) ALPHANUMERIC_TABLE[code] else -1

    // ── 版本选择 ────────────────────────────────────────────

    private fun recommendVersion(
        mode: QrMode,
        headerBits: QrBitBuffer,
        dataBits: QrBitBuffer,
    ): QrVersion {
        // 长度字段占几位**取决于版本**，而版本又取决于总位数 ⇒ 先按「假定第 1 版」猜一次，
        // 再用猜出来的版本算一次（zxing 的注释自己写着「不敢保证 100% 对」，但那是同一条路）。
        val provisionalBitsNeeded =
            calculateBitsNeeded(mode, headerBits, dataBits, QR_VERSIONS[0])
        val provisionalVersion = chooseVersion(provisionalBitsNeeded)
        val bitsNeeded = calculateBitsNeeded(mode, headerBits, dataBits, provisionalVersion)
        return chooseVersion(bitsNeeded)
    }

    private fun calculateBitsNeeded(
        mode: QrMode,
        headerBits: QrBitBuffer,
        dataBits: QrBitBuffer,
        version: QrVersion,
    ): Int = headerBits.size + mode.characterCountBits(version) + dataBits.size

    private fun chooseVersion(numInputBits: Int): QrVersion =
        QR_VERSIONS.firstOrNull { willFit(numInputBits, it) }
            ?: throw IllegalArgumentException("数据装不进 40 版二维码（$numInputBits 位）")

    /** zxing 的 `Encoder.willFit`。 */
    private fun willFit(numInputBits: Int, version: QrVersion): Boolean {
        val totalInputBytes = (numInputBits + 7) / 8
        return version.numDataCodewords >= totalInputBytes
    }

    // ── 数据段 ──────────────────────────────────────────────

    private fun appendBytes(content: String, mode: QrMode, bits: QrBitBuffer) {
        when (mode) {
            QrMode.NUMERIC -> appendNumericBytes(content, bits)
            QrMode.ALPHANUMERIC -> appendAlphanumericBytes(content, bits)
            // UTF-8 字节（`content.encodeToByteArray()` 与 Java 的 `getBytes(UTF_8)` 同一结果）
            QrMode.BYTE -> content.encodeToByteArray().forEach { bits.appendBits(it.toInt() and 0xFF, 8) }
            QrMode.ECI -> throw IllegalArgumentException("ECI 不是数据模式")
        }
    }

    private fun appendNumericBytes(content: String, bits: QrBitBuffer) {
        var i = 0
        val length = content.length
        while (i < length) {
            val num1 = content[i] - '0'
            if (i + 2 < length) {
                // 三个数字挤十位
                val num2 = content[i + 1] - '0'
                val num3 = content[i + 2] - '0'
                bits.appendBits(num1 * 100 + num2 * 10 + num3, 10)
                i += 3
            } else if (i + 1 < length) {
                // 两个数字挤七位
                val num2 = content[i + 1] - '0'
                bits.appendBits(num1 * 10 + num2, 7)
                i += 2
            } else {
                bits.appendBits(num1, 4)
                i++
            }
        }
    }

    private fun appendAlphanumericBytes(content: String, bits: QrBitBuffer) {
        var i = 0
        val length = content.length
        while (i < length) {
            val code1 = alphanumericCode(content[i].code)
            require(code1 != -1) { "字母数字模式收到表外字符：${content[i]}" }
            if (i + 1 < length) {
                val code2 = alphanumericCode(content[i + 1].code)
                require(code2 != -1) { "字母数字模式收到表外字符：${content[i + 1]}" }
                bits.appendBits(code1 * 45 + code2, 11)
                i += 2
            } else {
                bits.appendBits(code1, 6)
                i++
            }
        }
    }

    private fun appendLengthInfo(
        numLetters: Int,
        version: QrVersion,
        mode: QrMode,
        bits: QrBitBuffer,
    ) {
        val numBits = mode.characterCountBits(version)
        require(numLetters < (1 shl numBits)) { "$numLetters 超过了 ${(1 shl numBits) - 1}" }
        bits.appendBits(numLetters, numBits)
    }

    /** zxing 的 `Encoder.terminateBits`（JISX0510:2004 8.4.8 / 8.4.9） */
    private fun terminateBits(numDataBytes: Int, bits: QrBitBuffer) {
        val capacity = numDataBytes * 8
        require(bits.size <= capacity) {
            "数据位超过容量：${bits.size} > $capacity"
        }
        var i = 0
        while (i < 4 && bits.size < capacity) {
            bits.appendBit(false)
            i++
        }
        val numBitsInLastByte = bits.size and 0x07
        if (numBitsInLastByte > 0) {
            for (j in numBitsInLastByte until 8) bits.appendBit(false)
        }
        val numPaddingBytes = numDataBytes - bits.sizeInBytes
        for (k in 0 until numPaddingBytes) {
            bits.appendBits(if (k and 0x01 == 0) 0xEC else 0x11, 8)
        }
        require(bits.size == capacity) { "补完不是整容量：${bits.size} != $capacity" }
    }

    /** zxing 的 `Encoder.interleaveWithECBytes`（JISX0510:2004 8.6） */
    private fun interleaveWithEcBytes(
        bits: QrBitBuffer,
        numTotalBytes: Int,
        numDataBytes: Int,
        numRSBlocks: Int,
    ): QrBitBuffer {
        require(bits.sizeInBytes == numDataBytes) {
            "数据位与数据字节数对不上：${bits.sizeInBytes} != $numDataBytes"
        }

        val blockSizes = numDataBytesAndEcBytesForBlockId(
            numTotalBytes, numDataBytes, numRSBlocks,
        )
        val dataBlocks = ArrayList<ByteArray>(numRSBlocks)
        val ecBlocks = ArrayList<ByteArray>(numRSBlocks)
        var dataBytesOffset = 0
        var maxNumDataBytes = 0
        var maxNumEcBytes = 0
        for (block in blockSizes) {
            val dataBytes = ByteArray(block.dataBytes)
            bits.toBytes(8 * dataBytesOffset, dataBytes, 0, block.dataBytes)
            val ecBytes = rsEncode(dataBytes, block.ecBytes)
            dataBlocks.add(dataBytes)
            ecBlocks.add(ecBytes)
            maxNumDataBytes = maxOf(maxNumDataBytes, dataBytes.size)
            maxNumEcBytes = maxOf(maxNumEcBytes, ecBytes.size)
            dataBytesOffset += block.dataBytes
        }
        require(numDataBytes == dataBytesOffset) { "数据字节数对不上偏移：$numDataBytes != $dataBytesOffset" }

        val result = QrBitBuffer()
        for (i in 0 until maxNumDataBytes) {
            for (block in dataBlocks) if (i < block.size) result.appendBits(block[i].toInt() and 0xFF, 8)
        }
        for (i in 0 until maxNumEcBytes) {
            for (block in ecBlocks) if (i < block.size) result.appendBits(block[i].toInt() and 0xFF, 8)
        }
        require(numTotalBytes == result.sizeInBytes) {
            "交织后的字节数对不上：${result.sizeInBytes} != $numTotalBytes"
        }
        return result
    }

    private class BlockSize(val dataBytes: Int, val ecBytes: Int)

    /** zxing 的 `Encoder.getNumDataBytesAndNumECBytesForBlockID`。 */
    private fun numDataBytesAndEcBytesForBlockId(
        numTotalBytes: Int,
        numDataBytes: Int,
        numRSBlocks: Int,
    ): List<BlockSize> {
        val numRSBlocksInGroup2 = numTotalBytes % numRSBlocks
        val numRSBlocksInGroup1 = numRSBlocks - numRSBlocksInGroup2
        val numTotalBytesInGroup1 = numTotalBytes / numRSBlocks
        val numTotalBytesInGroup2 = numTotalBytesInGroup1 + 1
        val numDataBytesInGroup1 = numDataBytes / numRSBlocks
        val numDataBytesInGroup2 = numDataBytesInGroup1 + 1
        val numEcBytesInGroup1 = numTotalBytesInGroup1 - numDataBytesInGroup1
        val numEcBytesInGroup2 = numTotalBytesInGroup2 - numDataBytesInGroup2
        require(numEcBytesInGroup1 == numEcBytesInGroup2) { "每块纠错字节数不一致" }
        require(numRSBlocks == numRSBlocksInGroup1 + numRSBlocksInGroup2) { "块数对不上" }
        require(
            numTotalBytes ==
                (numDataBytesInGroup1 + numEcBytesInGroup1) * numRSBlocksInGroup1 +
                (numDataBytesInGroup2 + numEcBytesInGroup2) * numRSBlocksInGroup2,
        ) { "总字节数对不上" }

        return List(numRSBlocks) { blockId ->
            if (blockId < numRSBlocksInGroup1) {
                BlockSize(numDataBytesInGroup1, numEcBytesInGroup1)
            } else {
                BlockSize(numDataBytesInGroup2, numEcBytesInGroup2)
            }
        }
    }

    // ── Reed-Solomon（GF(256)） ─────────────────────────────

    /** GF(256)：本原多项式 `0x011d`（`x^8 + x^4 + x^3 + x^2 + 1`）、generator base `0`。 */
    private val EXP_TABLE = IntArray(256)
    private val LOG_TABLE = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 256) {
            EXP_TABLE[i] = x
            x *= 2
            if (x >= 256) {
                x = x xor 0x011d
                x = x and 0xFF
            }
        }
        for (i in 0 until 255) LOG_TABLE[EXP_TABLE[i]] = i
    }

    private fun gfMultiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return EXP_TABLE[(LOG_TABLE[a] + LOG_TABLE[b]) % 255]
    }

    /** 生成多项式 `g(x) = (x + a^0)(x + a^1)…(x + a^(ecBytes-1))`，返回从高次到常数的系数（首项系数恒为 1）。 */
    private fun rsGeneratorPoly(ecBytes: Int): IntArray {
        var generator = intArrayOf(1)
        for (d in 0 until ecBytes) {
            val next = IntArray(generator.size + 1)
            val root = EXP_TABLE[d]
            for (i in generator.indices) {
                next[i] = next[i] xor generator[i]
                next[i + 1] = next[i + 1] xor gfMultiply(generator[i], root)
            }
            generator = next
        }
        return generator
    }

    /** 纠错码 = 「数据字节后补 [ecBytes] 个 0」除以生成多项式的余数（zxing 的 RS 编码器同一结果）。 */
    private fun rsEncode(dataBytes: ByteArray, ecBytes: Int): ByteArray {
        require(ecBytes > 0) { "没有纠错字节" }
        val generator = rsGeneratorPoly(ecBytes)
        val remainder = IntArray(ecBytes)
        for (b in dataBytes) {
            val factor = (b.toInt() and 0xFF) xor remainder[0]
            for (i in 0 until ecBytes - 1) remainder[i] = remainder[i + 1]
            remainder[ecBytes - 1] = 0
            for (i in 0 until ecBytes) remainder[i] = remainder[i] xor gfMultiply(factor, generator[i + 1])
        }
        return ByteArray(ecBytes) { remainder[it].toByte() }
    }

    // ── 掩码 ────────────────────────────────────────────────

    /** zxing 的 `Encoder.chooseMaskPattern`：八种掩码各铺一遍、罚分最小者胜（同分取小的）。 */
    private fun chooseMaskPattern(
        bits: QrBitBuffer,
        version: QrVersion,
        matrix: QrByteMatrix,
    ): Int {
        var minPenalty = Int.MAX_VALUE
        var bestMaskPattern = -1
        for (maskPattern in 0 until NUM_MASK_PATTERNS) {
            QrLayout.buildMatrix(bits, version, maskPattern, matrix)
            val penalty = QrMaskUtil.calculateMaskPenalty(matrix)
            if (penalty < minPenalty) {
                minPenalty = penalty
                bestMaskPattern = maskPattern
            }
        }
        return bestMaskPattern
    }

    /** zxing 的 `QRCode.NUM_MASK_PATTERNS`。 */
    private const val NUM_MASK_PATTERNS = 8
}

/** 格子阵列 → [CodeMatrix]（渲染方认的就是它）。 */
private fun QrByteMatrix.toCodeMatrix(): CodeMatrix {
    val bits = BooleanArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) bits[offset + x] = get(x, y) == 1
    }
    return CodeMatrix(width, height, bits)
}

/** 二维码编码里那串位 —— zxing 的 `common.BitArray` 里这一份实现用到的那几个操作。 */
internal class QrBitBuffer {

    private var bits = IntArray(64)
    var size = 0
        private set

    fun appendBit(bit: Boolean) {
        ensureCapacity(size + 1)
        if (bit) bits[size / 32] = bits[size / 32] or (1 shl (size and 0x1F))
        size++
    }

    /** 从高位到低位写 [numBits] 位（zxing 的 `appendBits`）。 */
    fun appendBits(value: Int, numBits: Int) {
        require(numBits in 0..32) { "位数越界：$numBits" }
        var nextSize = size
        ensureCapacity(nextSize + numBits)
        for (numBitsLeft in numBits - 1 downTo 0) {
            if (value and (1 shl numBitsLeft) != 0) {
                bits[nextSize / 32] = bits[nextSize / 32] or (1 shl (nextSize and 0x1F))
            }
            nextSize++
        }
        size = nextSize
    }

    fun appendBuffer(other: QrBitBuffer) {
        ensureCapacity(size + other.size)
        for (i in 0 until other.size) appendBit(other.get(i))
    }

    fun get(index: Int): Boolean = bits[index / 32] and (1 shl (index and 0x1F)) != 0

    fun xor(other: QrBitBuffer) {
        require(size == other.size) { "两份位串长度不同：$size != ${other.size}" }
        for (i in bits.indices) bits[i] = bits[i] xor other.bits[i]
    }

    /** 这一串位一共几个字节（向上取整）—— zxing 的 `getSizeInBytes`。 */
    val sizeInBytes: Int get() = (size + 7) / 8

    /** zxing 的 `BitArray.toBytes`：从 [bitOffset] 起，每 8 位按高位在前装成一个字节。 */
    fun toBytes(bitOffset: Int, out: ByteArray, offset: Int, numBytes: Int) {
        var pos = bitOffset
        for (i in 0 until numBytes) {
            var theByte = 0
            for (j in 0 until 8) {
                if (get(pos)) theByte = theByte or (1 shl (7 - j))
                pos++
            }
            out[offset + i] = theByte.toByte()
        }
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity > bits.size * 32) {
            val newBits = IntArray(maxOf(capacity, bits.size * 2) / 32 + 1)
            bits.copyInto(newBits, 0, 0, bits.size)
            bits = newBits
        }
    }
}
