package com.xjtu.toolbox.platform

/** 二维码的数据掩码与掩码评分。 */
internal object QrMaskUtil {

    private const val N1 = 3
    private const val N2 = 3
    private const val N3 = 40
    private const val N4 = 10

    /** 第 [maskPattern] 种掩码在 (x, y) 这一格要不要翻转（zxing 的 `getDataMaskBit`）。 */
    fun getDataMaskBit(maskPattern: Int, x: Int, y: Int): Boolean {
        val intermediate: Int
        val temp: Int
        when (maskPattern) {
            0 -> intermediate = (y + x) and 0x1
            1 -> intermediate = y and 0x1
            2 -> intermediate = x % 3
            3 -> intermediate = (y + x) % 3
            4 -> intermediate = ((y / 2) + (x / 3)) and 0x1
            5 -> {
                temp = y * x
                intermediate = (temp and 0x1) + (temp % 3)
            }
            6 -> {
                temp = y * x
                intermediate = ((temp and 0x1) + (temp % 3)) and 0x1
            }
            7 -> {
                temp = y * x
                intermediate = ((temp % 3) + ((y + x) and 0x1)) and 0x1
            }
            else -> throw IllegalArgumentException("掩码编号越界：$maskPattern")
        }
        return intermediate == 0
    }

    /** 四条规则的总罚分（zxing 的 `Encoder.calculateMaskPenalty`）。 */
    fun calculateMaskPenalty(matrix: QrByteMatrix): Int =
        applyMaskPenaltyRule1(matrix) +
            applyMaskPenaltyRule2(matrix) +
            applyMaskPenaltyRule3(matrix) +
            applyMaskPenaltyRule4(matrix)

    /** 规则 1：同色连续 5 格起罚，横向与纵向各算一遍。 */
    private fun applyMaskPenaltyRule1(matrix: QrByteMatrix): Int =
        applyMaskPenaltyRule1Internal(matrix, true) + applyMaskPenaltyRule1Internal(matrix, false)

    /** 规则 2：2×2 同色块。 */
    private fun applyMaskPenaltyRule2(matrix: QrByteMatrix): Int {
        var penalty = 0
        for (y in 0 until matrix.height - 1) {
            for (x in 0 until matrix.width - 1) {
                val value = matrix.get(x, y)
                if (value == matrix.get(x + 1, y) &&
                    value == matrix.get(x, y + 1) &&
                    value == matrix.get(x + 1, y + 1)
                ) {
                    penalty++
                }
            }
        }
        return N2 * penalty
    }

    /** 规则 3：1:1:3:1:1:4 那种「像定位图案」的串（黑起或白起都算）。 */
    private fun applyMaskPenaltyRule3(matrix: QrByteMatrix): Int {
        var numPenalties = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (x + 6 < matrix.width &&
                    matrix.get(x, y) == 1 &&
                    matrix.get(x + 1, y) == 0 &&
                    matrix.get(x + 2, y) == 1 &&
                    matrix.get(x + 3, y) == 1 &&
                    matrix.get(x + 4, y) == 1 &&
                    matrix.get(x + 5, y) == 0 &&
                    matrix.get(x + 6, y) == 1 &&
                    (isWhiteHorizontal(matrix, y, x - 4, x) || isWhiteHorizontal(matrix, y, x + 7, x + 11))
                ) {
                    numPenalties++
                }
                if (y + 6 < matrix.height &&
                    matrix.get(x, y) == 1 &&
                    matrix.get(x, y + 1) == 0 &&
                    matrix.get(x, y + 2) == 1 &&
                    matrix.get(x, y + 3) == 1 &&
                    matrix.get(x, y + 4) == 1 &&
                    matrix.get(x, y + 5) == 0 &&
                    matrix.get(x, y + 6) == 1 &&
                    (isWhiteVertical(matrix, x, y - 4, y) || isWhiteVertical(matrix, x, y + 7, y + 11))
                ) {
                    numPenalties++
                }
            }
        }
        return numPenalties * N3
    }

    private fun isWhiteHorizontal(matrix: QrByteMatrix, y: Int, from: Int, to: Int): Boolean {
        if (from < 0 || matrix.width < to) return false
        for (i in from until to) if (matrix.get(i, y) == 1) return false
        return true
    }

    private fun isWhiteVertical(matrix: QrByteMatrix, x: Int, from: Int, to: Int): Boolean {
        if (from < 0 || matrix.height < to) return false
        for (i in from until to) if (matrix.get(x, i) == 1) return false
        return true
    }

    /** 规则 4：黑格比例离 50% 每 5% 罚 10 分。 */
    private fun applyMaskPenaltyRule4(matrix: QrByteMatrix): Int {
        var numDarkCells = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) if (matrix.get(x, y) == 1) numDarkCells++
        }
        val numTotalCells = matrix.height * matrix.width
        val fivePercentVariances =
            kotlin.math.abs(numDarkCells * 2 - numTotalCells) * 10 / numTotalCells
        return fivePercentVariances * N4
    }

    private fun applyMaskPenaltyRule1Internal(matrix: QrByteMatrix, isHorizontal: Boolean): Int {
        var penalty = 0
        val iLimit = if (isHorizontal) matrix.height else matrix.width
        val jLimit = if (isHorizontal) matrix.width else matrix.height
        for (i in 0 until iLimit) {
            var numSameBitCells = 0
            var prevBit = -1
            for (j in 0 until jLimit) {
                val bit = if (isHorizontal) matrix.get(j, i) else matrix.get(i, j)
                if (bit == prevBit) {
                    numSameBitCells++
                } else {
                    if (numSameBitCells >= 5) penalty += N1 + (numSameBitCells - 5)
                    numSameBitCells = 1
                    prevBit = bit
                }
            }
            if (numSameBitCells >= 5) penalty += N1 + (numSameBitCells - 5)
        }
        return penalty
    }
}
