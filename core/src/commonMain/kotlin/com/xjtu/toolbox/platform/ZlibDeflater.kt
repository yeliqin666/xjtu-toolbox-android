package com.xjtu.toolbox.platform

/** zlib 流的压缩那一半（纯 Kotlin）—— 与 [ZlibCodec.inflate] 配套，只给浏览器那一端用。 */
internal class ZlibDeflater(private val raw: ByteArray) {

    fun run(): ByteArray {
        val writer = ZlibBitWriter()
        writer.writeBits(1, 1) // BFINAL
        writer.writeBits(1, 2) // BTYPE = 01（固定 Huffman）

        if (raw.isEmpty()) {
            writeSymbol(writer, END_OF_BLOCK)
        } else {
            deflateLz77(writer)
            writeSymbol(writer, END_OF_BLOCK)
        }

        val body = writer.finish()
        val adler = adler32(raw)
        val out = ByteArray(2 + body.size + 4)
        out[0] = ZLIB_HEADER_LEVEL_9[0]
        out[1] = ZLIB_HEADER_LEVEL_9[1]
        body.copyInto(out, 2)
        out[out.size - 4] = (adler ushr 24).toByte()
        out[out.size - 3] = (adler ushr 16).toByte()
        out[out.size - 2] = (adler ushr 8).toByte()
        out[out.size - 1] = adler.toByte()
        return out
    }

    private fun deflateLz77(writer: ZlibBitWriter) {
        val head = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(raw.size) { -1 }
        var position = 0
        while (position < raw.size) {
            var bestLength = 0
            var bestDistance = 0
            if (position + MIN_MATCH <= raw.size) {
                val limit = minOf(MAX_MATCH, raw.size - position)
                var candidate = head[hash(position)]
                var chain = MAX_CHAIN
                while (candidate >= 0 && chain > 0 && position - candidate <= MAX_DISTANCE) {
                    var length = 0
                    while (length < limit && raw[candidate + length] == raw[position + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = position - candidate
                        if (length == limit) break
                    }
                    candidate = previous[candidate]
                    chain--
                }
            }

            if (bestLength >= MIN_MATCH) {
                writeLengthDistance(writer, bestLength, bestDistance)
                // 匹配吃掉的那几格也要进哈希表，否则后面的匹配质量会掉下来
                var i = position
                val end = position + bestLength
                while (i < end) {
                    if (i + MIN_MATCH <= raw.size) {
                        val h = hash(i)
                        previous[i] = head[h]
                        head[h] = i
                    }
                    i++
                }
                position = end
            } else {
                writeSymbol(writer, raw[position].toInt() and 0xFF)
                if (position + MIN_MATCH <= raw.size) {
                    val h = hash(position)
                    previous[position] = head[h]
                    head[h] = position
                }
                position++
            }
        }
    }

    /** 3 字节前缀 → 哈希槽（乘一个奇数再取高位，标准的 LZ77 做法）。 */
    private fun hash(position: Int): Int {
        val value = ((raw[position].toInt() and 0xFF) shl 16) or
            ((raw[position + 1].toInt() and 0xFF) shl 8) or
            (raw[position + 2].toInt() and 0xFF)
        return ((value * 0x9E3779B1.toInt()) ushr (32 - HASH_BITS)) and (HASH_SIZE - 1)
    }

    /** 固定 Huffman 的字面/长度码：0-143 → 8 位、144-255 → 9 位、256-279 → 7 位、280-287 → 8 位（RFC 1951 §3.2.6）。 */
    private fun writeSymbol(writer: ZlibBitWriter, symbol: Int) {
        when {
            symbol <= 143 -> writer.writeCode(0x30 + symbol, 8)
            symbol <= 255 -> writer.writeCode(0x190 + (symbol - 144), 9)
            symbol <= 279 -> writer.writeCode(symbol - 256, 7)
            else -> writer.writeCode(0xC0 + (symbol - 280), 8)
        }
    }

    /** 长度那一对（长度 + 距离）：长度先查 [LENGTH_CODE_BY_LENGTH] 拿到长度码，附加位用低位在前写。 */
    private fun writeLengthDistance(writer: ZlibBitWriter, length: Int, distance: Int) {
        val lengthIndex = LENGTH_CODE_BY_LENGTH[length]
        require(lengthIndex >= 0) { "长度 $length 没有对应的长度码" }
        writeSymbol(writer, 257 + lengthIndex)
        val lengthExtra = LENGTH_EXTRA[lengthIndex]
        if (lengthExtra > 0) writer.writeBits(length - LENGTH_BASE[lengthIndex], lengthExtra)

        var distanceIndex = DIST_BASE.size - 1
        while (distanceIndex > 0 && DIST_BASE[distanceIndex] > distance) distanceIndex--
        writer.writeCode(distanceIndex, 5)
        val distanceExtra = DIST_EXTRA[distanceIndex]
        if (distanceExtra > 0) writer.writeBits(distance - DIST_BASE[distanceIndex], distanceExtra)
    }

    private companion object {
        /** 最短的匹配长度（deflate 的最小长度码就是 3）。 */
        const val MIN_MATCH = 3

        /** 最长的匹配长度（RFC 1951 的长度码 285 就是 258）。 */
        const val MAX_MATCH = 258

        /** 最大回溯距离（32K 窗口；分享码的明文远小于它）。 */
        const val MAX_DISTANCE = 32768

        /** 哈希链最长走多少步。 */
        const val MAX_CHAIN = 64

        const val HASH_BITS = 15
        const val HASH_SIZE = 1 shl HASH_BITS

        /** deflate 的块结束符。 */
        const val END_OF_BLOCK = 256

        /** zlib 头：32K 窗口（CINFO=7）+ 最高压缩档（FLEVEL=3）—— 与 `Deflater(BEST_COMPRESSION)` 同一个头。 */
        val ZLIB_HEADER_LEVEL_9 = byteArrayOf(0x78, 0xDA.toByte())
    }
}

/** 写 deflate 的位流。 */
private class ZlibBitWriter {

    private var buffer = ByteArray(256)
    private var size = 0
    private var bitBuffer = 0
    private var bitCount = 0

    /** 低位在前地写 [count] 位（块头与附加位用这个）。 */
    fun writeBits(value: Int, count: Int) {
        var remaining = value
        for (i in 0 until count) {
            bitBuffer = bitBuffer or ((remaining and 1) shl bitCount)
            remaining = remaining ushr 1
            bitCount++
            if (bitCount == 8) flushByte()
        }
    }

    /** 高位在前地写 [bits] 位的 Huffman 码字（码字用这个）。 */
    fun writeCode(code: Int, bits: Int) {
        for (i in bits - 1 downTo 0) {
            bitBuffer = bitBuffer or (((code ushr i) and 1) shl bitCount)
            bitCount++
            if (bitCount == 8) flushByte()
        }
    }

    fun finish(): ByteArray {
        if (bitCount > 0) flushByte()
        return buffer.copyOf(size)
    }

    private fun flushByte() {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = bitBuffer.toByte()
        bitBuffer = 0
        bitCount = 0
    }
}
