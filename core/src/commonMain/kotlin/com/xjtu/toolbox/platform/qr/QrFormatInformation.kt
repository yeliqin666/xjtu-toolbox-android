package com.xjtu.toolbox.platform.qr

/** 格式信息与数据掩码 —— zxing 3.5.4 `com.google.zxing.qrcode.decoder` 的 `FormatInformation` / `DataMask` 的移植。 */
internal class QrFormatInformation private constructor(formatInfo: Int) {

    val errorCorrectionLevel: QrErrorCorrectionLevel = QrErrorCorrectionLevel.forBits((formatInfo shr 3) and 0x03)

    /** 底下三位 */
    val dataMask: Int = formatInfo and 0x07

    companion object {

        private const val FORMAT_INFO_MASK_QR = 0x5412

        private val FORMAT_INFO_DECODE_LOOKUP = arrayOf(
            intArrayOf(0x5412, 0x00),
            intArrayOf(0x5125, 0x01),
            intArrayOf(0x5E7C, 0x02),
            intArrayOf(0x5B4B, 0x03),
            intArrayOf(0x45F9, 0x04),
            intArrayOf(0x40CE, 0x05),
            intArrayOf(0x4F97, 0x06),
            intArrayOf(0x4AA0, 0x07),
            intArrayOf(0x77C4, 0x08),
            intArrayOf(0x72F3, 0x09),
            intArrayOf(0x7DAA, 0x0A),
            intArrayOf(0x789D, 0x0B),
            intArrayOf(0x662F, 0x0C),
            intArrayOf(0x6318, 0x0D),
            intArrayOf(0x6C41, 0x0E),
            intArrayOf(0x6976, 0x0F),
            intArrayOf(0x1689, 0x10),
            intArrayOf(0x13BE, 0x11),
            intArrayOf(0x1CE7, 0x12),
            intArrayOf(0x19D0, 0x13),
            intArrayOf(0x0762, 0x14),
            intArrayOf(0x0255, 0x15),
            intArrayOf(0x0D0C, 0x16),
            intArrayOf(0x083B, 0x17),
            intArrayOf(0x355F, 0x18),
            intArrayOf(0x3068, 0x19),
            intArrayOf(0x3F31, 0x1A),
            intArrayOf(0x3A06, 0x1B),
            intArrayOf(0x24B4, 0x1C),
            intArrayOf(0x2183, 0x1D),
            intArrayOf(0x2EDA, 0x1E),
            intArrayOf(0x2BED, 0x1F),
        )

        fun numBitsDiffering(a: Int, b: Int): Int = (a xor b).countOneBits()

        /** 两份格式信息位里挑一个读得出来的；读不出来返回 null（调用方抛 `FormatException`）。 */
        fun decodeFormatInformation(maskedFormatInfo1: Int, maskedFormatInfo2: Int): QrFormatInformation? {
            doDecodeFormatInformation(maskedFormatInfo1, maskedFormatInfo2)?.let { return it }
            // 本该返回 null 的，但有些二维码显然没有掩这一层 —— 那就把那一层掩回去再试一次
            return doDecodeFormatInformation(
                maskedFormatInfo1 xor FORMAT_INFO_MASK_QR,
                maskedFormatInfo2 xor FORMAT_INFO_MASK_QR,
            )
        }

        private fun doDecodeFormatInformation(
            maskedFormatInfo1: Int,
            maskedFormatInfo2: Int,
        ): QrFormatInformation? {
            // 在表里找「不同的位数最少」的那一条
            var bestDifference = Int.MAX_VALUE
            var bestFormatInfo = 0
            for (decodeInfo in FORMAT_INFO_DECODE_LOOKUP) {
                val targetInfo = decodeInfo[0]
                // 正好一样就直接收工
                if (targetInfo == maskedFormatInfo1 || targetInfo == maskedFormatInfo2) {
                    return QrFormatInformation(decodeInfo[1])
                }
                var bitsDifference = numBitsDiffering(maskedFormatInfo1, targetInfo)
                if (bitsDifference < bestDifference) {
                    bestFormatInfo = decodeInfo[1]
                    bestDifference = bitsDifference
                }
                if (maskedFormatInfo1 != maskedFormatInfo2) {
                    // 另一个也试一下
                    bitsDifference = numBitsDiffering(maskedFormatInfo2, targetInfo)
                    if (bitsDifference < bestDifference) {
                        bestFormatInfo = decodeInfo[1]
                        bestDifference = bitsDifference
                    }
                }
            }
            // 32 个被掩过的码之间的汉明距离天生是 7，所以不同位数 <= 3 就算找到了
            if (bestDifference <= 3) return QrFormatInformation(bestFormatInfo)
            return null
        }
    }
}

/** 八种数据掩码（见 ISO 18004:2006 6.8.1）。 */
internal enum class QrDataMask {

    DATA_MASK_000 {
        override fun isMasked(i: Int, j: Int): Boolean = ((i + j) and 0x01) == 0
    },
    DATA_MASK_001 {
        override fun isMasked(i: Int, j: Int): Boolean = (i and 0x01) == 0
    },
    DATA_MASK_010 {
        override fun isMasked(i: Int, j: Int): Boolean = j % 3 == 0
    },
    DATA_MASK_011 {
        override fun isMasked(i: Int, j: Int): Boolean = (i + j) % 3 == 0
    },
    DATA_MASK_100 {
        override fun isMasked(i: Int, j: Int): Boolean = (((i / 2) + (j / 3)) and 0x01) == 0
    },
    DATA_MASK_101 {
        override fun isMasked(i: Int, j: Int): Boolean = (i * j) % 6 == 0
    },
    DATA_MASK_110 {
        override fun isMasked(i: Int, j: Int): Boolean = ((i * j) % 6) < 3
    },
    DATA_MASK_111 {
        override fun isMasked(i: Int, j: Int): Boolean = ((i + j + ((i * j) % 3)) and 0x01) == 0
    },
    ;

    abstract fun isMasked(i: Int, j: Int): Boolean

    /** 把该翻的格子翻回来（原地改）。 */
    fun unmaskBitMatrix(bits: QrBitMatrix, dimension: Int) {
        for (i in 0 until dimension) {
            for (j in 0 until dimension) {
                if (isMasked(i, j)) bits.flip(j, i)
            }
        }
    }
}
