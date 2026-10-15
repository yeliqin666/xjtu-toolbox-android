package com.xjtu.toolbox.platform

/**
 * 二维码的**格子**（`-1` = 还没填、`0` = 白、`1` = 黑）。
 *
 * 与 zxing 的 `qrcode.encoder.ByteMatrix` 同一个形状：x 是列、y 是行，
 * `get(x, y)` / `set(x, y, v)` 的先后与那份 Java 源码逐字一致（那里的 `bytes[y][x]`）。
 */
internal class QrByteMatrix(val width: Int, val height: Int) {
    private val cells = Array(height) { IntArray(width) { EMPTY } }

    fun get(x: Int, y: Int): Int = cells[y][x]

    fun set(x: Int, y: Int, value: Int) {
        cells[y][x] = value
    }

    fun set(x: Int, y: Int, value: Boolean) {
        cells[y][x] = if (value) 1 else 0
    }

    fun clear(value: Int) {
        for (row in cells) row.fill(value)
    }

    companion object {
        /** zxing 的 `MatrixUtil.isEmpty(value)`：还没被任何图案写过的格子。 */
        const val EMPTY = -1
    }
}

/**
 * 二维码**框架**的排布 —— 定位图案、分隔符、校正图案、时序图案、格式信息、版本信息、数据位。
 *
 * 逐字对照 zxing 3.5.4（Apache-2.0）的 `com.google.zxing.qrcode.encoder.MatrixUtil`：
 * 每个函数名、每个坐标、每张表的取值都与那份 Java 源码一一对应（`embedBasicPatterns` /
 * `embedTypeInfo` / `maybeEmbedVersionInfo` / `embedDataBits` 四步的先后也一样）。
 * 这样做的**目的**只有一个：与 Android/jvm 那两份 zxing actual 编出**逐模块相同**的矩阵
 * （由 `:core:jvmTest` 的 `CodeMatrixParityJvmTest` 逐格钉住）。
 *
 * 两条**没有**搬过来的东西，都在调用方已经排除掉的前提下：
 * - **矩形码**（`SymbolShapeHint.FORCE_RECTANGLE`）：那条缝从来不传它（只传字符集 / 纠错等级 / 静默区）；
 * - **KANJI 模式**：只有字符集是 Shift_JIS 时才可能选到（zxing 的 `chooseMode` 判据），
 *   而这条缝恒定传 `UTF-8`（见 `QrCodec`）⇒ 那一条分支在这里够不着，不搬。
 */
internal object QrLayout {

    /** 定位图案（7×7），zxing 的 `POSITION_DETECTION_PATTERN`。 */
    private val POSITION_DETECTION_PATTERN = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
    )

    /** 校正图案（5×5），zxing 的 `POSITION_ADJUSTMENT_PATTERN`。 */
    private val POSITION_ADJUSTMENT_PATTERN = arrayOf(
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 0, 1, 0, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1),
    )

    /**
     * 校正图案中心坐标表（按版本），zxing 的 `POSITION_ADJUSTMENT_PATTERN_COORDINATE_TABLE`
     * （JISX0510:2004 附录 E 表 1）。`-1` = 这一行没有更多坐标。
     */
    private val POSITION_ADJUSTMENT_PATTERN_COORDINATE_TABLE = arrayOf(
        intArrayOf(-1, -1, -1, -1, -1, -1, -1),
        intArrayOf(6, 18, -1, -1, -1, -1, -1),
        intArrayOf(6, 22, -1, -1, -1, -1, -1),
        intArrayOf(6, 26, -1, -1, -1, -1, -1),
        intArrayOf(6, 30, -1, -1, -1, -1, -1),
        intArrayOf(6, 34, -1, -1, -1, -1, -1),
        intArrayOf(6, 22, 38, -1, -1, -1, -1),
        intArrayOf(6, 24, 42, -1, -1, -1, -1),
        intArrayOf(6, 26, 46, -1, -1, -1, -1),
        intArrayOf(6, 28, 50, -1, -1, -1, -1),
        intArrayOf(6, 30, 54, -1, -1, -1, -1),
        intArrayOf(6, 32, 58, -1, -1, -1, -1),
        intArrayOf(6, 34, 62, -1, -1, -1, -1),
        intArrayOf(6, 26, 46, 66, -1, -1, -1),
        intArrayOf(6, 26, 48, 70, -1, -1, -1),
        intArrayOf(6, 26, 50, 74, -1, -1, -1),
        intArrayOf(6, 30, 54, 78, -1, -1, -1),
        intArrayOf(6, 30, 56, 82, -1, -1, -1),
        intArrayOf(6, 30, 58, 86, -1, -1, -1),
        intArrayOf(6, 34, 62, 90, -1, -1, -1),
        intArrayOf(6, 28, 50, 72, 94, -1, -1),
        intArrayOf(6, 26, 50, 74, 98, -1, -1),
        intArrayOf(6, 30, 54, 78, 102, -1, -1),
        intArrayOf(6, 28, 54, 80, 106, -1, -1),
        intArrayOf(6, 32, 58, 84, 110, -1, -1),
        intArrayOf(6, 30, 58, 86, 114, -1, -1),
        intArrayOf(6, 34, 62, 90, 118, -1, -1),
        intArrayOf(6, 26, 50, 74, 98, 122, -1),
        intArrayOf(6, 30, 54, 78, 102, 126, -1),
        intArrayOf(6, 26, 52, 78, 104, 130, -1),
        intArrayOf(6, 30, 56, 82, 108, 134, -1),
        intArrayOf(6, 34, 60, 86, 112, 138, -1),
        intArrayOf(6, 30, 58, 86, 114, 142, -1),
        intArrayOf(6, 34, 62, 90, 118, 146, -1),
        intArrayOf(6, 30, 54, 78, 102, 126, 150),
        intArrayOf(6, 24, 50, 76, 102, 128, 154),
        intArrayOf(6, 28, 54, 80, 106, 132, 158),
        intArrayOf(6, 32, 58, 84, 110, 136, 162),
        intArrayOf(6, 26, 54, 82, 110, 138, 166),
        intArrayOf(6, 30, 58, 86, 114, 142, 170),
    )

    /** 格式信息在左上角那 15 格的坐标（x, y），zxing 的 `TYPE_INFO_COORDINATES`。 */
    private val TYPE_INFO_COORDINATES = arrayOf(
        intArrayOf(8, 0),
        intArrayOf(8, 1),
        intArrayOf(8, 2),
        intArrayOf(8, 3),
        intArrayOf(8, 4),
        intArrayOf(8, 5),
        intArrayOf(8, 7),
        intArrayOf(8, 8),
        intArrayOf(7, 8),
        intArrayOf(5, 8),
        intArrayOf(4, 8),
        intArrayOf(3, 8),
        intArrayOf(2, 8),
        intArrayOf(1, 8),
        intArrayOf(0, 8),
    )

    /** 版本信息的多项式（JISX0510:2004 附录 D），zxing 的 `VERSION_INFO_POLY`。 */
    private const val VERSION_INFO_POLY = 0x1f25

    /** 格式信息的多项式与掩码（JISX0510:2004 附录 C），zxing 的两条同名常数。 */
    private const val TYPE_INFO_POLY = 0x537
    private const val TYPE_INFO_MASK_PATTERN = 0x5412

    /**
     * 纠错等级 L 在格式信息里的两位（zxing 的 `ErrorCorrectionLevel.L.getBits()`）。
     *
     * 这一份实现只支持 L —— 那也正是三端那条缝唯一用到的等级，见 `QrVersionTable.kt` 的文件头。
     */
    private const val EC_LEVEL_L_BITS = 0x01

    /** 按 zxing 的四步铺满整张矩阵（`MatrixUtil.buildMatrix`）。 */
    fun buildMatrix(dataBits: QrBitBuffer, version: QrVersion, maskPattern: Int, matrix: QrByteMatrix) {
        matrix.clear(QrByteMatrix.EMPTY)
        embedBasicPatterns(version, matrix)
        embedTypeInfo(maskPattern, matrix)
        maybeEmbedVersionInfo(version, matrix)
        embedDataBits(dataBits, maskPattern, matrix)
    }

    // ── 基本图案 ────────────────────────────────────────────

    private fun embedBasicPatterns(version: QrVersion, matrix: QrByteMatrix) {
        embedPositionDetectionPatternsAndSeparators(matrix)
        // 左下角那个孤立的黑点（JISX0510:2004 p.46）
        matrix.set(8, matrix.height - 8, 1)
        maybeEmbedPositionAdjustmentPatterns(version, matrix)
        embedTimingPatterns(matrix)
    }

    private fun embedPositionDetectionPatternsAndSeparators(matrix: QrByteMatrix) {
        val pdpWidth = POSITION_DETECTION_PATTERN[0].size
        embedPositionDetectionPattern(0, 0, matrix)
        embedPositionDetectionPattern(matrix.width - pdpWidth, 0, matrix)
        embedPositionDetectionPattern(0, matrix.width - pdpWidth, matrix)

        val hspWidth = 8
        embedHorizontalSeparationPattern(0, hspWidth - 1, matrix)
        embedHorizontalSeparationPattern(matrix.width - hspWidth, hspWidth - 1, matrix)
        embedHorizontalSeparationPattern(0, matrix.width - hspWidth, matrix)

        val vspSize = 7
        embedVerticalSeparationPattern(vspSize, 0, matrix)
        embedVerticalSeparationPattern(matrix.height - vspSize - 1, 0, matrix)
        embedVerticalSeparationPattern(vspSize, matrix.height - vspSize, matrix)
    }

    private fun embedHorizontalSeparationPattern(xStart: Int, yStart: Int, matrix: QrByteMatrix) {
        for (x in 0 until 8) matrix.set(xStart + x, yStart, 0)
    }

    private fun embedVerticalSeparationPattern(xStart: Int, yStart: Int, matrix: QrByteMatrix) {
        for (y in 0 until 7) matrix.set(xStart, yStart + y, 0)
    }

    private fun embedPositionAdjustmentPattern(xStart: Int, yStart: Int, matrix: QrByteMatrix) {
        for (y in POSITION_ADJUSTMENT_PATTERN.indices) {
            val row = POSITION_ADJUSTMENT_PATTERN[y]
            for (x in row.indices) matrix.set(xStart + x, yStart + y, row[x])
        }
    }

    private fun embedPositionDetectionPattern(xStart: Int, yStart: Int, matrix: QrByteMatrix) {
        for (y in POSITION_DETECTION_PATTERN.indices) {
            val row = POSITION_DETECTION_PATTERN[y]
            for (x in row.indices) matrix.set(xStart + x, yStart + y, row[x])
        }
    }

    private fun maybeEmbedPositionAdjustmentPatterns(version: QrVersion, matrix: QrByteMatrix) {
        if (version.versionNumber < 2) return
        val coordinates = POSITION_ADJUSTMENT_PATTERN_COORDINATE_TABLE[version.versionNumber - 1]
        for (y in coordinates) {
            if (y < 0) continue
            for (x in coordinates) {
                // 坐标指的是图案**中心**，所以左上角要减 2；已经被写过的格子跳过
                // （三个定位图案的角上与它们重叠的校正图案不画）。
                if (x >= 0 && matrix.get(x, y) == QrByteMatrix.EMPTY) {
                    embedPositionAdjustmentPattern(x - 2, y - 2, matrix)
                }
            }
        }
    }

    private fun embedTimingPatterns(matrix: QrByteMatrix) {
        // 8 = 定位图案 7 格 + 分隔符 1 格
        for (i in 8 until matrix.width - 8) {
            val bit = (i + 1) % 2
            if (matrix.get(i, 6) == QrByteMatrix.EMPTY) matrix.set(i, 6, bit)
            if (matrix.get(6, i) == QrByteMatrix.EMPTY) matrix.set(6, i, bit)
        }
    }

    // ── 格式 / 版本信息 ─────────────────────────────────────

    private fun embedTypeInfo(maskPattern: Int, matrix: QrByteMatrix) {
        val typeInfoBits = QrBitBuffer()
        makeTypeInfoBits(maskPattern, typeInfoBits)

        for (i in 0 until typeInfoBits.size) {
            // 从最低位开始摆（zxing 的注释：LSB 在最后）
            val bit = typeInfoBits.get(typeInfoBits.size - 1 - i)
            val coordinates = TYPE_INFO_COORDINATES[i]
            matrix.set(coordinates[0], coordinates[1], bit)

            if (i < 8) {
                // 右上角
                matrix.set(matrix.width - i - 1, 8, bit)
            } else {
                // 左下角
                matrix.set(8, matrix.height - 7 + (i - 8), bit)
            }
        }
    }

    private fun maybeEmbedVersionInfo(version: QrVersion, matrix: QrByteMatrix) {
        if (version.versionNumber < 7) return
        val versionInfoBits = QrBitBuffer()
        makeVersionInfoBits(version, versionInfoBits)

        var bitIndex = 6 * 3 - 1
        for (i in 0 until 6) {
            for (j in 0 until 3) {
                val bit = versionInfoBits.get(bitIndex)
                bitIndex--
                // 左下角
                matrix.set(i, matrix.height - 11 + j, bit)
                // 右下角
                matrix.set(matrix.height - 11 + j, i, bit)
            }
        }
    }

    private fun makeTypeInfoBits(maskPattern: Int, bits: QrBitBuffer) {
        require(maskPattern in 0..7) { "掩码编号越界：$maskPattern" }
        val typeInfo = (EC_LEVEL_L_BITS shl 3) or maskPattern
        bits.appendBits(typeInfo, 5)
        bits.appendBits(calculateBchCode(typeInfo, TYPE_INFO_POLY), 10)

        val maskBits = QrBitBuffer()
        maskBits.appendBits(TYPE_INFO_MASK_PATTERN, 15)
        bits.xor(maskBits)
        require(bits.size == 15) { "格式信息应当是 15 位，实际 ${bits.size}" }
    }

    private fun makeVersionInfoBits(version: QrVersion, bits: QrBitBuffer) {
        bits.appendBits(version.versionNumber, 6)
        bits.appendBits(calculateBchCode(version.versionNumber, VERSION_INFO_POLY), 12)
        require(bits.size == 18) { "版本信息应当是 18 位，实际 ${bits.size}" }
    }

    /** zxing 的 `MatrixUtil.calculateBCHCode`：多项式除法的余数（用位运算做）。 */
    private fun calculateBchCode(value: Int, poly: Int): Int {
        require(poly != 0) { "0 多项式" }
        val msbSetInPoly = findMsbSet(poly)
        var result = value shl (msbSetInPoly - 1)
        while (findMsbSet(result) >= msbSetInPoly) {
            result = result xor (poly shl (findMsbSet(result) - msbSetInPoly))
        }
        return result
    }

    /** zxing 的 `MatrixUtil.findMSBSet`：最高置位的位序号（0 也返回 0）。 */
    private fun findMsbSet(value: Int): Int = 32 - value.countLeadingZeroBits()

    // ── 数据位 ──────────────────────────────────────────────

    private fun embedDataBits(dataBits: QrBitBuffer, maskPattern: Int, matrix: QrByteMatrix) {
        var bitIndex = 0
        var direction = -1
        var x = matrix.width - 1
        var y = matrix.height - 1
        while (x > 0) {
            // 跳过竖向的时序图案
            if (x == 6) x -= 1
            while (y >= 0 && y < matrix.height) {
                for (i in 0 until 2) {
                    val xx = x - i
                    // 已经被图案占掉的格子跳过
                    if (matrix.get(xx, y) != QrByteMatrix.EMPTY) continue
                    var bit = if (bitIndex < dataBits.size) {
                        val b = dataBits.get(bitIndex)
                        bitIndex++
                        b
                    } else {
                        // 数据用完了：剩下的格子按 8.4.9 节填 0
                        false
                    }
                    if (QrMaskUtil.getDataMaskBit(maskPattern, xx, y)) bit = !bit
                    matrix.set(xx, y, bit)
                }
                y += direction
            }
            direction = -direction
            y += direction
            x -= 2
        }
        require(bitIndex == dataBits.size) { "数据位没吃完：$bitIndex / ${dataBits.size}" }
    }
}
