package com.xjtu.toolbox.platform.qr

/**
 * 版本表与纠错等级/模式 —— zxing 3.5.4 `com.google.zxing.qrcode.decoder` 的 `Version`（含
 * `ECBlocks` / `ECB`）/ `ErrorCorrectionLevel` / `Mode` 的**逐字对照**。
 *
 * ## 这些数字从哪来（别手抄，抄错一位就解不出码）
 *
 * 下面那张 `VERSIONS` 表是**从 zxing 3.5.4 的 `Version.java` 里逐条抽出来**的（40 个版本 ×
 * 4 个纠错等级 × 每级的纠错字/块划分），抽取脚本一次跑完、每一条都对着 zxing 那份源码数过一遍。
 * `QrVersion` 的 `totalCodewords` 也与 zxing 一样**由这张表算出来**（`count * (dataCodewords +
 * ecCodewordsPerBlock)` 逐块求和），不是另抄一列。
 *
 * D12 ① 那份**编码器**（`QrVersionTable.kt`）只有 **L** 那一列（`QrCodec` 出码只用 L 级）；
 * 解码这一侧必须四列都有 —— 屏对屏扫出来的码可能是 M/Q/H。两份表**互不覆盖**：编码那份是
 * `int` 数组，这份是带对齐图案中心与块划分的对象表。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `Version`（版本号、对齐图案中心、四级 `ECBlocks`、`totalCodewords`、`getDimensionForVersion`、`getProvisionalVersionForDimension`、`getVersionForNumber`、`decodeVersionInformation`、`buildFunctionPattern`） | [QrVersion] | 逐条相同 |
 * | `Version.ECBlocks` / `ECB`（`getNumBlocks`、`getTotalECCodewords` 那两条算出来的 getter 一并） | [QrEcBlocks] / [QrEcb] | |
 * | `ErrorCorrectionLevel`（`L/M/Q/H` 的序、`bits`、`forBits` 的 `{M, L, H, Q}` 查表） | [QrErrorCorrectionLevel] | **序不能改**：`ECBlocks[ecLevel.ordinal()]` 就靠它 |
 * | `Mode`（十种模式、`characterCountBitsForVersions` 三档、`forBits`、`getCharacterCountBits`） | [QrMode] | |
 */
internal class QrVersion(
    val versionNumber: Int,
    val alignmentPatternCenters: IntArray,
    vararg val ecBlocks: QrEcBlocks,
) {

    /** 与 zxing 一样从 [ecBlocks] 的第一级算出来（四级的总码字数相同）。 */
    val totalCodewords: Int = run {
        var total = 0
        val ecCodewords = ecBlocks[0].ecCodewordsPerBlock
        for (ecBlock in ecBlocks[0].ecBlocks) {
            total += ecBlock.count * (ecBlock.dataCodewords + ecCodewords)
        }
        total
    }

    val dimensionForVersion: Int get() = 17 + 4 * versionNumber

    fun ecBlocksForLevel(ecLevel: QrErrorCorrectionLevel): QrEcBlocks = ecBlocks[ecLevel.ordinal]

    /** 功能图案（定位/校正/时序/格式/版本信息占掉的那些格子）—— `BitMatrixParser` 读数据时跳过它们。 */
    fun buildFunctionPattern(): QrBitMatrix {
        val dimension = dimensionForVersion
        val bitMatrix = QrBitMatrix(dimension, dimension)
        // 左上定位图案 + 分隔带 + 格式信息
        bitMatrix.setRegion(0, 0, 9, 9)
        // 右上定位图案 + 分隔带 + 格式信息
        bitMatrix.setRegion(dimension - 8, 0, 8, 9)
        // 左下定位图案 + 分隔带 + 格式信息
        bitMatrix.setRegion(0, dimension - 8, 9, 8)
        // 校正图案
        val max = alignmentPatternCenters.size
        for (x in 0 until max) {
            val i = alignmentPatternCenters[x] - 2
            for (y in 0 until max) {
                if ((x != 0 || (y != 0 && y != max - 1)) && (x != max - 1 || y != 0)) {
                    bitMatrix.setRegion(alignmentPatternCenters[y] - 2, i, 5, 5)
                }
                // 三个定位图案附近没有校正图案
            }
        }
        // 竖直时序图案
        bitMatrix.setRegion(6, 9, 1, dimension - 17)
        // 水平时序图案
        bitMatrix.setRegion(9, 6, dimension - 17, 1)
        if (versionNumber > 6) {
            // 版本信息：右上
            bitMatrix.setRegion(dimension - 11, 0, 3, 6)
            // 版本信息：左下
            bitMatrix.setRegion(0, dimension - 11, 6, 3)
        }
        return bitMatrix
    }

    companion object {

        /** 版本 7~40 的版本信息码（BCH(18,6) 编出来的 18 位），`decodeVersionInformation` 查它。 */
        private val VERSION_DECODE_INFO = intArrayOf(
            0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6,
            0x0C762, 0x0D847, 0x0E60D, 0x0F928, 0x10B78,
            0x1145D, 0x12A17, 0x13532, 0x149A6, 0x15683,
            0x168C9, 0x177EC, 0x18EC4, 0x191E1, 0x1AFAB,
            0x1B08E, 0x1CC1A, 0x1D33F, 0x1ED75, 0x1F250,
            0x209D5, 0x216F0, 0x228BA, 0x2379F, 0x24B0B,
            0x2542E, 0x26A64, 0x27541, 0x28C69,
        )

        private val VERSIONS: Array<QrVersion> = arrayOf(
            // 版本 1
            QrVersion(
                versionNumber = 1,
                alignmentPatternCenters = intArrayOf(),
                QrEcBlocks(7, QrEcb(1, 19)), // L
                QrEcBlocks(10, QrEcb(1, 16)), // M
                QrEcBlocks(13, QrEcb(1, 13)), // Q
                QrEcBlocks(17, QrEcb(1, 9)), // H
            ),
            // 版本 2
            QrVersion(
                versionNumber = 2,
                alignmentPatternCenters = intArrayOf(6, 18),
                QrEcBlocks(10, QrEcb(1, 34)), // L
                QrEcBlocks(16, QrEcb(1, 28)), // M
                QrEcBlocks(22, QrEcb(1, 22)), // Q
                QrEcBlocks(28, QrEcb(1, 16)), // H
            ),
            // 版本 3
            QrVersion(
                versionNumber = 3,
                alignmentPatternCenters = intArrayOf(6, 22),
                QrEcBlocks(15, QrEcb(1, 55)), // L
                QrEcBlocks(26, QrEcb(1, 44)), // M
                QrEcBlocks(18, QrEcb(2, 17)), // Q
                QrEcBlocks(22, QrEcb(2, 13)), // H
            ),
            // 版本 4
            QrVersion(
                versionNumber = 4,
                alignmentPatternCenters = intArrayOf(6, 26),
                QrEcBlocks(20, QrEcb(1, 80)), // L
                QrEcBlocks(18, QrEcb(2, 32)), // M
                QrEcBlocks(26, QrEcb(2, 24)), // Q
                QrEcBlocks(16, QrEcb(4, 9)), // H
            ),
            // 版本 5
            QrVersion(
                versionNumber = 5,
                alignmentPatternCenters = intArrayOf(6, 30),
                QrEcBlocks(26, QrEcb(1, 108)), // L
                QrEcBlocks(24, QrEcb(2, 43)), // M
                QrEcBlocks(18, QrEcb(2, 15), QrEcb(2, 16)), // Q
                QrEcBlocks(22, QrEcb(2, 11), QrEcb(2, 12)), // H
            ),
            // 版本 6
            QrVersion(
                versionNumber = 6,
                alignmentPatternCenters = intArrayOf(6, 34),
                QrEcBlocks(18, QrEcb(2, 68)), // L
                QrEcBlocks(16, QrEcb(4, 27)), // M
                QrEcBlocks(24, QrEcb(4, 19)), // Q
                QrEcBlocks(28, QrEcb(4, 15)), // H
            ),
            // 版本 7
            QrVersion(
                versionNumber = 7,
                alignmentPatternCenters = intArrayOf(6, 22, 38),
                QrEcBlocks(20, QrEcb(2, 78)), // L
                QrEcBlocks(18, QrEcb(4, 31)), // M
                QrEcBlocks(18, QrEcb(2, 14), QrEcb(4, 15)), // Q
                QrEcBlocks(26, QrEcb(4, 13), QrEcb(1, 14)), // H
            ),
            // 版本 8
            QrVersion(
                versionNumber = 8,
                alignmentPatternCenters = intArrayOf(6, 24, 42),
                QrEcBlocks(24, QrEcb(2, 97)), // L
                QrEcBlocks(22, QrEcb(2, 38), QrEcb(2, 39)), // M
                QrEcBlocks(22, QrEcb(4, 18), QrEcb(2, 19)), // Q
                QrEcBlocks(26, QrEcb(4, 14), QrEcb(2, 15)), // H
            ),
            // 版本 9
            QrVersion(
                versionNumber = 9,
                alignmentPatternCenters = intArrayOf(6, 26, 46),
                QrEcBlocks(30, QrEcb(2, 116)), // L
                QrEcBlocks(22, QrEcb(3, 36), QrEcb(2, 37)), // M
                QrEcBlocks(20, QrEcb(4, 16), QrEcb(4, 17)), // Q
                QrEcBlocks(24, QrEcb(4, 12), QrEcb(4, 13)), // H
            ),
            // 版本 10
            QrVersion(
                versionNumber = 10,
                alignmentPatternCenters = intArrayOf(6, 28, 50),
                QrEcBlocks(18, QrEcb(2, 68), QrEcb(2, 69)), // L
                QrEcBlocks(26, QrEcb(4, 43), QrEcb(1, 44)), // M
                QrEcBlocks(24, QrEcb(6, 19), QrEcb(2, 20)), // Q
                QrEcBlocks(28, QrEcb(6, 15), QrEcb(2, 16)), // H
            ),
            // 版本 11
            QrVersion(
                versionNumber = 11,
                alignmentPatternCenters = intArrayOf(6, 30, 54),
                QrEcBlocks(20, QrEcb(4, 81)), // L
                QrEcBlocks(30, QrEcb(1, 50), QrEcb(4, 51)), // M
                QrEcBlocks(28, QrEcb(4, 22), QrEcb(4, 23)), // Q
                QrEcBlocks(24, QrEcb(3, 12), QrEcb(8, 13)), // H
            ),
            // 版本 12
            QrVersion(
                versionNumber = 12,
                alignmentPatternCenters = intArrayOf(6, 32, 58),
                QrEcBlocks(24, QrEcb(2, 92), QrEcb(2, 93)), // L
                QrEcBlocks(22, QrEcb(6, 36), QrEcb(2, 37)), // M
                QrEcBlocks(26, QrEcb(4, 20), QrEcb(6, 21)), // Q
                QrEcBlocks(28, QrEcb(7, 14), QrEcb(4, 15)), // H
            ),
            // 版本 13
            QrVersion(
                versionNumber = 13,
                alignmentPatternCenters = intArrayOf(6, 34, 62),
                QrEcBlocks(26, QrEcb(4, 107)), // L
                QrEcBlocks(22, QrEcb(8, 37), QrEcb(1, 38)), // M
                QrEcBlocks(24, QrEcb(8, 20), QrEcb(4, 21)), // Q
                QrEcBlocks(22, QrEcb(12, 11), QrEcb(4, 12)), // H
            ),
            // 版本 14
            QrVersion(
                versionNumber = 14,
                alignmentPatternCenters = intArrayOf(6, 26, 46, 66),
                QrEcBlocks(30, QrEcb(3, 115), QrEcb(1, 116)), // L
                QrEcBlocks(24, QrEcb(4, 40), QrEcb(5, 41)), // M
                QrEcBlocks(20, QrEcb(11, 16), QrEcb(5, 17)), // Q
                QrEcBlocks(24, QrEcb(11, 12), QrEcb(5, 13)), // H
            ),
            // 版本 15
            QrVersion(
                versionNumber = 15,
                alignmentPatternCenters = intArrayOf(6, 26, 48, 70),
                QrEcBlocks(22, QrEcb(5, 87), QrEcb(1, 88)), // L
                QrEcBlocks(24, QrEcb(5, 41), QrEcb(5, 42)), // M
                QrEcBlocks(30, QrEcb(5, 24), QrEcb(7, 25)), // Q
                QrEcBlocks(24, QrEcb(11, 12), QrEcb(7, 13)), // H
            ),
            // 版本 16
            QrVersion(
                versionNumber = 16,
                alignmentPatternCenters = intArrayOf(6, 26, 50, 74),
                QrEcBlocks(24, QrEcb(5, 98), QrEcb(1, 99)), // L
                QrEcBlocks(28, QrEcb(7, 45), QrEcb(3, 46)), // M
                QrEcBlocks(24, QrEcb(15, 19), QrEcb(2, 20)), // Q
                QrEcBlocks(30, QrEcb(3, 15), QrEcb(13, 16)), // H
            ),
            // 版本 17
            QrVersion(
                versionNumber = 17,
                alignmentPatternCenters = intArrayOf(6, 30, 54, 78),
                QrEcBlocks(28, QrEcb(1, 107), QrEcb(5, 108)), // L
                QrEcBlocks(28, QrEcb(10, 46), QrEcb(1, 47)), // M
                QrEcBlocks(28, QrEcb(1, 22), QrEcb(15, 23)), // Q
                QrEcBlocks(28, QrEcb(2, 14), QrEcb(17, 15)), // H
            ),
            // 版本 18
            QrVersion(
                versionNumber = 18,
                alignmentPatternCenters = intArrayOf(6, 30, 56, 82),
                QrEcBlocks(30, QrEcb(5, 120), QrEcb(1, 121)), // L
                QrEcBlocks(26, QrEcb(9, 43), QrEcb(4, 44)), // M
                QrEcBlocks(28, QrEcb(17, 22), QrEcb(1, 23)), // Q
                QrEcBlocks(28, QrEcb(2, 14), QrEcb(19, 15)), // H
            ),
            // 版本 19
            QrVersion(
                versionNumber = 19,
                alignmentPatternCenters = intArrayOf(6, 30, 58, 86),
                QrEcBlocks(28, QrEcb(3, 113), QrEcb(4, 114)), // L
                QrEcBlocks(26, QrEcb(3, 44), QrEcb(11, 45)), // M
                QrEcBlocks(26, QrEcb(17, 21), QrEcb(4, 22)), // Q
                QrEcBlocks(26, QrEcb(9, 13), QrEcb(16, 14)), // H
            ),
            // 版本 20
            QrVersion(
                versionNumber = 20,
                alignmentPatternCenters = intArrayOf(6, 34, 62, 90),
                QrEcBlocks(28, QrEcb(3, 107), QrEcb(5, 108)), // L
                QrEcBlocks(26, QrEcb(3, 41), QrEcb(13, 42)), // M
                QrEcBlocks(30, QrEcb(15, 24), QrEcb(5, 25)), // Q
                QrEcBlocks(28, QrEcb(15, 15), QrEcb(10, 16)), // H
            ),
            // 版本 21
            QrVersion(
                versionNumber = 21,
                alignmentPatternCenters = intArrayOf(6, 28, 50, 72, 94),
                QrEcBlocks(28, QrEcb(4, 116), QrEcb(4, 117)), // L
                QrEcBlocks(26, QrEcb(17, 42)), // M
                QrEcBlocks(28, QrEcb(17, 22), QrEcb(6, 23)), // Q
                QrEcBlocks(30, QrEcb(19, 16), QrEcb(6, 17)), // H
            ),
            // 版本 22
            QrVersion(
                versionNumber = 22,
                alignmentPatternCenters = intArrayOf(6, 26, 50, 74, 98),
                QrEcBlocks(28, QrEcb(2, 111), QrEcb(7, 112)), // L
                QrEcBlocks(28, QrEcb(17, 46)), // M
                QrEcBlocks(30, QrEcb(7, 24), QrEcb(16, 25)), // Q
                QrEcBlocks(24, QrEcb(34, 13)), // H
            ),
            // 版本 23
            QrVersion(
                versionNumber = 23,
                alignmentPatternCenters = intArrayOf(6, 30, 54, 78, 102),
                QrEcBlocks(30, QrEcb(4, 121), QrEcb(5, 122)), // L
                QrEcBlocks(28, QrEcb(4, 47), QrEcb(14, 48)), // M
                QrEcBlocks(30, QrEcb(11, 24), QrEcb(14, 25)), // Q
                QrEcBlocks(30, QrEcb(16, 15), QrEcb(14, 16)), // H
            ),
            // 版本 24
            QrVersion(
                versionNumber = 24,
                alignmentPatternCenters = intArrayOf(6, 28, 54, 80, 106),
                QrEcBlocks(30, QrEcb(6, 117), QrEcb(4, 118)), // L
                QrEcBlocks(28, QrEcb(6, 45), QrEcb(14, 46)), // M
                QrEcBlocks(30, QrEcb(11, 24), QrEcb(16, 25)), // Q
                QrEcBlocks(30, QrEcb(30, 16), QrEcb(2, 17)), // H
            ),
            // 版本 25
            QrVersion(
                versionNumber = 25,
                alignmentPatternCenters = intArrayOf(6, 32, 58, 84, 110),
                QrEcBlocks(26, QrEcb(8, 106), QrEcb(4, 107)), // L
                QrEcBlocks(28, QrEcb(8, 47), QrEcb(13, 48)), // M
                QrEcBlocks(30, QrEcb(7, 24), QrEcb(22, 25)), // Q
                QrEcBlocks(30, QrEcb(22, 15), QrEcb(13, 16)), // H
            ),
            // 版本 26
            QrVersion(
                versionNumber = 26,
                alignmentPatternCenters = intArrayOf(6, 30, 58, 86, 114),
                QrEcBlocks(28, QrEcb(10, 114), QrEcb(2, 115)), // L
                QrEcBlocks(28, QrEcb(19, 46), QrEcb(4, 47)), // M
                QrEcBlocks(28, QrEcb(28, 22), QrEcb(6, 23)), // Q
                QrEcBlocks(30, QrEcb(33, 16), QrEcb(4, 17)), // H
            ),
            // 版本 27
            QrVersion(
                versionNumber = 27,
                alignmentPatternCenters = intArrayOf(6, 34, 62, 90, 118),
                QrEcBlocks(30, QrEcb(8, 122), QrEcb(4, 123)), // L
                QrEcBlocks(28, QrEcb(22, 45), QrEcb(3, 46)), // M
                QrEcBlocks(30, QrEcb(8, 23), QrEcb(26, 24)), // Q
                QrEcBlocks(30, QrEcb(12, 15), QrEcb(28, 16)), // H
            ),
            // 版本 28
            QrVersion(
                versionNumber = 28,
                alignmentPatternCenters = intArrayOf(6, 26, 50, 74, 98, 122),
                QrEcBlocks(30, QrEcb(3, 117), QrEcb(10, 118)), // L
                QrEcBlocks(28, QrEcb(3, 45), QrEcb(23, 46)), // M
                QrEcBlocks(30, QrEcb(4, 24), QrEcb(31, 25)), // Q
                QrEcBlocks(30, QrEcb(11, 15), QrEcb(31, 16)), // H
            ),
            // 版本 29
            QrVersion(
                versionNumber = 29,
                alignmentPatternCenters = intArrayOf(6, 30, 54, 78, 102, 126),
                QrEcBlocks(30, QrEcb(7, 116), QrEcb(7, 117)), // L
                QrEcBlocks(28, QrEcb(21, 45), QrEcb(7, 46)), // M
                QrEcBlocks(30, QrEcb(1, 23), QrEcb(37, 24)), // Q
                QrEcBlocks(30, QrEcb(19, 15), QrEcb(26, 16)), // H
            ),
            // 版本 30
            QrVersion(
                versionNumber = 30,
                alignmentPatternCenters = intArrayOf(6, 26, 52, 78, 104, 130),
                QrEcBlocks(30, QrEcb(5, 115), QrEcb(10, 116)), // L
                QrEcBlocks(28, QrEcb(19, 47), QrEcb(10, 48)), // M
                QrEcBlocks(30, QrEcb(15, 24), QrEcb(25, 25)), // Q
                QrEcBlocks(30, QrEcb(23, 15), QrEcb(25, 16)), // H
            ),
            // 版本 31
            QrVersion(
                versionNumber = 31,
                alignmentPatternCenters = intArrayOf(6, 30, 56, 82, 108, 134),
                QrEcBlocks(30, QrEcb(13, 115), QrEcb(3, 116)), // L
                QrEcBlocks(28, QrEcb(2, 46), QrEcb(29, 47)), // M
                QrEcBlocks(30, QrEcb(42, 24), QrEcb(1, 25)), // Q
                QrEcBlocks(30, QrEcb(23, 15), QrEcb(28, 16)), // H
            ),
            // 版本 32
            QrVersion(
                versionNumber = 32,
                alignmentPatternCenters = intArrayOf(6, 34, 60, 86, 112, 138),
                QrEcBlocks(30, QrEcb(17, 115)), // L
                QrEcBlocks(28, QrEcb(10, 46), QrEcb(23, 47)), // M
                QrEcBlocks(30, QrEcb(10, 24), QrEcb(35, 25)), // Q
                QrEcBlocks(30, QrEcb(19, 15), QrEcb(35, 16)), // H
            ),
            // 版本 33
            QrVersion(
                versionNumber = 33,
                alignmentPatternCenters = intArrayOf(6, 30, 58, 86, 114, 142),
                QrEcBlocks(30, QrEcb(17, 115), QrEcb(1, 116)), // L
                QrEcBlocks(28, QrEcb(14, 46), QrEcb(21, 47)), // M
                QrEcBlocks(30, QrEcb(29, 24), QrEcb(19, 25)), // Q
                QrEcBlocks(30, QrEcb(11, 15), QrEcb(46, 16)), // H
            ),
            // 版本 34
            QrVersion(
                versionNumber = 34,
                alignmentPatternCenters = intArrayOf(6, 34, 62, 90, 118, 146),
                QrEcBlocks(30, QrEcb(13, 115), QrEcb(6, 116)), // L
                QrEcBlocks(28, QrEcb(14, 46), QrEcb(23, 47)), // M
                QrEcBlocks(30, QrEcb(44, 24), QrEcb(7, 25)), // Q
                QrEcBlocks(30, QrEcb(59, 16), QrEcb(1, 17)), // H
            ),
            // 版本 35
            QrVersion(
                versionNumber = 35,
                alignmentPatternCenters = intArrayOf(6, 30, 54, 78, 102, 126, 150),
                QrEcBlocks(30, QrEcb(12, 121), QrEcb(7, 122)), // L
                QrEcBlocks(28, QrEcb(12, 47), QrEcb(26, 48)), // M
                QrEcBlocks(30, QrEcb(39, 24), QrEcb(14, 25)), // Q
                QrEcBlocks(30, QrEcb(22, 15), QrEcb(41, 16)), // H
            ),
            // 版本 36
            QrVersion(
                versionNumber = 36,
                alignmentPatternCenters = intArrayOf(6, 24, 50, 76, 102, 128, 154),
                QrEcBlocks(30, QrEcb(6, 121), QrEcb(14, 122)), // L
                QrEcBlocks(28, QrEcb(6, 47), QrEcb(34, 48)), // M
                QrEcBlocks(30, QrEcb(46, 24), QrEcb(10, 25)), // Q
                QrEcBlocks(30, QrEcb(2, 15), QrEcb(64, 16)), // H
            ),
            // 版本 37
            QrVersion(
                versionNumber = 37,
                alignmentPatternCenters = intArrayOf(6, 28, 54, 80, 106, 132, 158),
                QrEcBlocks(30, QrEcb(17, 122), QrEcb(4, 123)), // L
                QrEcBlocks(28, QrEcb(29, 46), QrEcb(14, 47)), // M
                QrEcBlocks(30, QrEcb(49, 24), QrEcb(10, 25)), // Q
                QrEcBlocks(30, QrEcb(24, 15), QrEcb(46, 16)), // H
            ),
            // 版本 38
            QrVersion(
                versionNumber = 38,
                alignmentPatternCenters = intArrayOf(6, 32, 58, 84, 110, 136, 162),
                QrEcBlocks(30, QrEcb(4, 122), QrEcb(18, 123)), // L
                QrEcBlocks(28, QrEcb(13, 46), QrEcb(32, 47)), // M
                QrEcBlocks(30, QrEcb(48, 24), QrEcb(14, 25)), // Q
                QrEcBlocks(30, QrEcb(42, 15), QrEcb(32, 16)), // H
            ),
            // 版本 39
            QrVersion(
                versionNumber = 39,
                alignmentPatternCenters = intArrayOf(6, 26, 54, 82, 110, 138, 166),
                QrEcBlocks(30, QrEcb(20, 117), QrEcb(4, 118)), // L
                QrEcBlocks(28, QrEcb(40, 47), QrEcb(7, 48)), // M
                QrEcBlocks(30, QrEcb(43, 24), QrEcb(22, 25)), // Q
                QrEcBlocks(30, QrEcb(10, 15), QrEcb(67, 16)), // H
            ),
            // 版本 40
            QrVersion(
                versionNumber = 40,
                alignmentPatternCenters = intArrayOf(6, 30, 58, 86, 114, 142, 170),
                QrEcBlocks(30, QrEcb(19, 118), QrEcb(6, 119)), // L
                QrEcBlocks(28, QrEcb(18, 47), QrEcb(31, 48)), // M
                QrEcBlocks(30, QrEcb(34, 24), QrEcb(34, 25)), // Q
                QrEcBlocks(30, QrEcb(20, 15), QrEcb(61, 16)), // H
            ),
        )

        fun getVersionForNumber(versionNumber: Int): QrVersion {
            require(versionNumber in 1..40)
            return VERSIONS[versionNumber - 1]
        }

        /** 从「一边有多少个模块」反推一个**临时**版本（格式/版本信息读出来之前先用它算维度）。 */
        fun getProvisionalVersionForDimension(dimension: Int): QrVersion {
            if (dimension % 4 != 1) throw QrFormatException()
            return try {
                getVersionForNumber((dimension - 17) / 4)
            } catch (_: IllegalArgumentException) {
                throw QrFormatException()
            }
        }

        /** 版本信息那 18 位 → 版本；容 3 位错（两个版本信息码之间的汉明距离至少是 8）。 */
        fun decodeVersionInformation(versionBits: Int): QrVersion? {
            var bestDifference = Int.MAX_VALUE
            var bestVersion = 0
            for (i in VERSION_DECODE_INFO.indices) {
                val targetVersion = VERSION_DECODE_INFO[i]
                // 正好相等就收工
                if (targetVersion == versionBits) return getVersionForNumber(i + 7)
                val bitsDifference = QrFormatInformation.numBitsDiffering(versionBits, targetVersion)
                if (bitsDifference < bestDifference) {
                    bestVersion = i + 7
                    bestDifference = bitsDifference
                }
            }
            if (bestDifference <= 3) return getVersionForNumber(bestVersion)
            // 没有足够接近的就说不知道
            return null
        }
    }
}

/** 一个版本在某一个纠错等级下的块划分。 */
internal class QrEcBlocks(val ecCodewordsPerBlock: Int, vararg val ecBlocks: QrEcb) {

    val numBlocks: Int get() = ecBlocks.sumOf { it.count }

    val totalEcCodewords: Int get() = ecCodewordsPerBlock * numBlocks
}

/** 一组「若干个同样大小的块」。 */
internal class QrEcb(val count: Int, val dataCodewords: Int)

/**
 * 纠错等级。**序不能改**：[QrVersion.ecBlocksForLevel] 直接按下标取（zxing 一样）。
 */
internal enum class QrErrorCorrectionLevel(val bits: Int) {
    L(0x01),
    M(0x00),
    Q(0x03),
    H(0x02);

    companion object {

        private val FOR_BITS = arrayOf(M, L, H, Q)

        fun forBits(bits: Int): QrErrorCorrectionLevel {
            require(bits in FOR_BITS.indices)
            return FOR_BITS[bits]
        }
    }
}

/** 分段模式（`Mode`）。 */
internal enum class QrMode(private val characterCountBitsForVersions: IntArray, val bits: Int) {
    /** 其实不算一种模式 */
    TERMINATOR(intArrayOf(0, 0, 0), 0x00),
    NUMERIC(intArrayOf(10, 12, 14), 0x01),
    ALPHANUMERIC(intArrayOf(9, 11, 13), 0x02),
    /** 本实现不支持 */
    STRUCTURED_APPEND(intArrayOf(0, 0, 0), 0x03),
    BYTE(intArrayOf(8, 16, 16), 0x04),
    /** 字符数那三档对它不适用 */
    ECI(intArrayOf(0, 0, 0), 0x07),
    KANJI(intArrayOf(8, 10, 12), 0x08),
    FNC1_FIRST_POSITION(intArrayOf(0, 0, 0), 0x05),
    FNC1_SECOND_POSITION(intArrayOf(0, 0, 0), 0x09),
    /** GBT 18284-2000 里定义的，国外未必支持 */
    HANZI(intArrayOf(8, 10, 12), 0x0D),
    ;

    fun characterCountBits(version: QrVersion): Int {
        val number = version.versionNumber
        val offset = when {
            number <= 9 -> 0
            number <= 26 -> 1
            else -> 2
        }
        return characterCountBitsForVersions[offset]
    }

    companion object {

        fun forBits(bits: Int): QrMode = when (bits) {
            0x0 -> TERMINATOR
            0x1 -> NUMERIC
            0x2 -> ALPHANUMERIC
            0x3 -> STRUCTURED_APPEND
            0x4 -> BYTE
            0x5 -> FNC1_FIRST_POSITION
            0x7 -> ECI
            0x8 -> KANJI
            0x9 -> FNC1_SECOND_POSITION
            // 0xD 见 GBT 18284-2000
            0xD -> HANZI
            else -> throw IllegalArgumentException("认不出的模式：$bits")
        }
    }
}
