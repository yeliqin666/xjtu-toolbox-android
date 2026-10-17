package com.xjtu.toolbox.platform.qr

/** 按位读一段字节流 —— zxing 3.5.4 `com.google.zxing.common.BitSource` 的移植。 */
internal class QrBitSource(private val bytes: ByteArray) {

    private var byteOffset = 0
    private var bitOffset = 0

    /** 还要多少位可读（读到头就是 0 或负数）。 */
    fun available(): Int = 8 * (bytes.size - byteOffset) - bitOffset

    fun readBits(numBits: Int): Int {
        require(numBits in 1..32 && numBits <= available()) { "要读的位数不合法：$numBits" }
        var remaining = numBits
        var result = 0
        // 先把当前这个字节里剩下的位读完
        if (bitOffset > 0) {
            val bitsLeft = 8 - bitOffset
            val toRead = minOf(remaining, bitsLeft)
            val bitsToNotRead = bitsLeft - toRead
            val mask = (0xFF shr (8 - toRead)) shl bitsToNotRead
            result = ((bytes[byteOffset].toInt() and mask) shr bitsToNotRead)
            remaining -= toRead
            bitOffset += toRead
            if (bitOffset == 8) {
                bitOffset = 0
                byteOffset++
            }
        }
        // 再按整字节读
        if (remaining > 0) {
            while (remaining >= 8) {
                result = (result shl 8) or (bytes[byteOffset].toInt() and 0xFF)
                byteOffset++
                remaining -= 8
            }
            // 最后剩的那个半个字节
            if (remaining > 0) {
                val bitsToNotRead = 8 - remaining
                val mask = (0xFF shr bitsToNotRead) shl bitsToNotRead
                result = (result shl remaining) or ((bytes[byteOffset].toInt() and mask) shr bitsToNotRead)
                bitOffset += remaining
            }
        }
        return result
    }
}
