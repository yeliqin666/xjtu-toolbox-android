package com.xjtu.toolbox.platform

/** 二维码的版本 / 纠错块表（1..40 版，只列纠错等级 L）。 */
internal class QrEcBlock(val count: Int, val dataCodewords: Int)

/** 一个二维码版本（[versionNumber] 1..40）在纠错等级 L 下的参数。 */
internal class QrVersion(
    val versionNumber: Int,
    val ecCodewordsPerBlock: Int,
    val ecBlocks: Array<QrEcBlock>,
) {

    /** 边长（模块数）：zxing 的 `Version.getDimensionForVersion()`。 */
    val dimension: Int get() = 17 + 4 * versionNumber

    /** 一共几块（zxing 的 `ECBlocks.getNumBlocks()`）。 */
    val numBlocks: Int get() = ecBlocks.sumOf { it.count }

    /** 纠错码总字数（zxing 的 `ECBlocks.getTotalECCodewords()`）。 */
    val totalEcCodewords: Int get() = numBlocks * ecCodewordsPerBlock

    /** 总码字数（zxing 的 `Version.getTotalCodewords()`）。 */
    val totalCodewords: Int
        get() = ecBlocks.sumOf { it.count * (it.dataCodewords + ecCodewordsPerBlock) }

    /** 数据码字数 = 总码字数 − 纠错码字数（zxing 在 `Encoder.encode` 里现算的那一格）。 */
    val numDataCodewords: Int get() = totalCodewords - totalEcCodewords
}

/** 见 [QrVersion] 与文件头。按版本号升序，下标 i 就是第 i+1 版。 */
internal val QR_VERSIONS: Array<QrVersion> = arrayOf(
        QrVersion(1, 7, arrayOf(QrEcBlock(1, 19))),
        QrVersion(2, 10, arrayOf(QrEcBlock(1, 34))),
        QrVersion(3, 15, arrayOf(QrEcBlock(1, 55))),
        QrVersion(4, 20, arrayOf(QrEcBlock(1, 80))),
        QrVersion(5, 26, arrayOf(QrEcBlock(1, 108))),
        QrVersion(6, 18, arrayOf(QrEcBlock(2, 68))),
        QrVersion(7, 20, arrayOf(QrEcBlock(2, 78))),
        QrVersion(8, 24, arrayOf(QrEcBlock(2, 97))),
        QrVersion(9, 30, arrayOf(QrEcBlock(2, 116))),
        QrVersion(10, 18, arrayOf(QrEcBlock(2, 68), QrEcBlock(2, 69))),
        QrVersion(11, 20, arrayOf(QrEcBlock(4, 81))),
        QrVersion(12, 24, arrayOf(QrEcBlock(2, 92), QrEcBlock(2, 93))),
        QrVersion(13, 26, arrayOf(QrEcBlock(4, 107))),
        QrVersion(14, 30, arrayOf(QrEcBlock(3, 115), QrEcBlock(1, 116))),
        QrVersion(15, 22, arrayOf(QrEcBlock(5, 87), QrEcBlock(1, 88))),
        QrVersion(16, 24, arrayOf(QrEcBlock(5, 98), QrEcBlock(1, 99))),
        QrVersion(17, 28, arrayOf(QrEcBlock(1, 107), QrEcBlock(5, 108))),
        QrVersion(18, 30, arrayOf(QrEcBlock(5, 120), QrEcBlock(1, 121))),
        QrVersion(19, 28, arrayOf(QrEcBlock(3, 113), QrEcBlock(4, 114))),
        QrVersion(20, 28, arrayOf(QrEcBlock(3, 107), QrEcBlock(5, 108))),
        QrVersion(21, 28, arrayOf(QrEcBlock(4, 116), QrEcBlock(4, 117))),
        QrVersion(22, 28, arrayOf(QrEcBlock(2, 111), QrEcBlock(7, 112))),
        QrVersion(23, 30, arrayOf(QrEcBlock(4, 121), QrEcBlock(5, 122))),
        QrVersion(24, 30, arrayOf(QrEcBlock(6, 117), QrEcBlock(4, 118))),
        QrVersion(25, 26, arrayOf(QrEcBlock(8, 106), QrEcBlock(4, 107))),
        QrVersion(26, 28, arrayOf(QrEcBlock(10, 114), QrEcBlock(2, 115))),
        QrVersion(27, 30, arrayOf(QrEcBlock(8, 122), QrEcBlock(4, 123))),
        QrVersion(28, 30, arrayOf(QrEcBlock(3, 117), QrEcBlock(10, 118))),
        QrVersion(29, 30, arrayOf(QrEcBlock(7, 116), QrEcBlock(7, 117))),
        QrVersion(30, 30, arrayOf(QrEcBlock(5, 115), QrEcBlock(10, 116))),
        QrVersion(31, 30, arrayOf(QrEcBlock(13, 115), QrEcBlock(3, 116))),
        QrVersion(32, 30, arrayOf(QrEcBlock(17, 115))),
        QrVersion(33, 30, arrayOf(QrEcBlock(17, 115), QrEcBlock(1, 116))),
        QrVersion(34, 30, arrayOf(QrEcBlock(13, 115), QrEcBlock(6, 116))),
        QrVersion(35, 30, arrayOf(QrEcBlock(12, 121), QrEcBlock(7, 122))),
        QrVersion(36, 30, arrayOf(QrEcBlock(6, 121), QrEcBlock(14, 122))),
        QrVersion(37, 30, arrayOf(QrEcBlock(17, 122), QrEcBlock(4, 123))),
        QrVersion(38, 30, arrayOf(QrEcBlock(4, 122), QrEcBlock(18, 123))),
        QrVersion(39, 30, arrayOf(QrEcBlock(20, 117), QrEcBlock(4, 118))),
        QrVersion(40, 30, arrayOf(QrEcBlock(19, 118), QrEcBlock(6, 119))),
)
