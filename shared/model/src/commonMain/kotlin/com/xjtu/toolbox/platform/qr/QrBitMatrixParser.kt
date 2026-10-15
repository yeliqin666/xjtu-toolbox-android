package com.xjtu.toolbox.platform.qr

/** 从采样出来的位矩阵里读出格式信息、版本信息与码字。 */
internal class QrBitMatrixParser(private val bitMatrix: QrBitMatrix) {

    private var parsedVersion: QrVersion? = null
    private var parsedFormatInfo: QrFormatInformation? = null
    private var mirror: Boolean = false

    init {
        val dimension = bitMatrix.height
        if (dimension < 21 || (dimension and 0x03) != 1) throw QrFormatException()
    }

    fun readFormatInformation(): QrFormatInformation {
        parsedFormatInfo?.let { return it }
        // 左上那一圈格式信息
        var formatInfoBits1 = 0
        for (i in 0 until 6) formatInfoBits1 = copyBit(i, 8, formatInfoBits1)
        // ……跳过时序图案里的一位……
        formatInfoBits1 = copyBit(7, 8, formatInfoBits1)
        formatInfoBits1 = copyBit(8, 8, formatInfoBits1)
        formatInfoBits1 = copyBit(8, 7, formatInfoBits1)
        // ……再跳过一位……
        for (j in 5 downTo 0) formatInfoBits1 = copyBit(8, j, formatInfoBits1)
        // 右上/左下那一圈也读一遍
        val dimension = bitMatrix.height
        var formatInfoBits2 = 0
        val jMin = dimension - 7
        for (j in dimension - 1 downTo jMin) formatInfoBits2 = copyBit(8, j, formatInfoBits2)
        for (i in dimension - 8 until dimension) formatInfoBits2 = copyBit(i, 8, formatInfoBits2)
        parsedFormatInfo = QrFormatInformation.decodeFormatInformation(formatInfoBits1, formatInfoBits2)
            ?: throw QrFormatException()
        return parsedFormatInfo!!
    }

    fun readVersion(): QrVersion {
        parsedVersion?.let { return it }
        val dimension = bitMatrix.height
        val provisionalVersion = (dimension - 17) / 4
        if (provisionalVersion <= 6) return QrVersion.getVersionForNumber(provisionalVersion)
        // 右上那份版本信息：宽 3、高 6
        var versionBits = 0
        val ijMin = dimension - 11
        for (j in 5 downTo 0) {
            for (i in dimension - 9 downTo ijMin) versionBits = copyBit(i, j, versionBits)
        }
        var theParsedVersion = QrVersion.decodeVersionInformation(versionBits)
        if (theParsedVersion != null && theParsedVersion.dimensionForVersion == dimension) {
            parsedVersion = theParsedVersion
            return theParsedVersion
        }
        // 不成，试左下那份：宽 6、高 3
        versionBits = 0
        for (i in 5 downTo 0) {
            for (j in dimension - 9 downTo ijMin) versionBits = copyBit(i, j, versionBits)
        }
        theParsedVersion = QrVersion.decodeVersionInformation(versionBits)
        if (theParsedVersion != null && theParsedVersion.dimensionForVersion == dimension) {
            parsedVersion = theParsedVersion
            return theParsedVersion
        }
        throw QrFormatException()
    }

    private fun copyBit(i: Int, j: Int, versionBits: Int): Int {
        val bit = if (mirror) bitMatrix.get(j, i) else bitMatrix.get(i, j)
        return if (bit) (versionBits shl 1) or 0x1 else versionBits shl 1
    }

    fun readCodewords(): ByteArray {
        val formatInfo = readFormatInformation()
        val version = readVersion()
        // 用格式信息里的掩码号把掩码解掉 —— 这样往下走读到的就是真数据
        val dataMask = QrDataMask.entries[formatInfo.dataMask]
        val dimension = bitMatrix.height
        dataMask.unmaskBitMatrix(bitMatrix, dimension)
        val functionPattern = version.buildFunctionPattern()
        var readingUp = true
        val result = ByteArray(version.totalCodewords)
        var resultOffset = 0
        var currentByte = 0
        var bitsRead = 0
        // 从右往左、两列一叠地读
        var j = dimension - 1
        while (j > 0) {
            if (j == 6) {
                // 整列时序图案跳过：省点时间，也让别的码走得干净些
                j--
            }
            // 一叠里：先下后上、再上后下，交替着来
            for (count in 0 until dimension) {
                val i = if (readingUp) dimension - 1 - count else count
                for (col in 0 until 2) {
                    // 被功能图案盖住的格子跳过
                    if (!functionPattern.get(j - col, i)) {
                        bitsRead++
                        currentByte = currentByte shl 1
                        if (bitMatrix.get(j - col, i)) currentByte = currentByte or 1
                        // 攒够一个字节就存出去
                        if (bitsRead == 8) {
                            result[resultOffset++] = currentByte.toByte()
                            bitsRead = 0
                            currentByte = 0
                        }
                    }
                }
            }
            readingUp = !readingUp
            j -= 2
        }
        if (resultOffset != version.totalCodewords) throw QrFormatException()
        return result
    }

    /** 把上一次 `readCodewords` 解掉的掩码**再掩回去**（镜像那一支要先还原再重读）。 */
    fun remask() {
        val formatInfo = parsedFormatInfo ?: return // 没有格式信息就没有掩码号
        val dataMask = QrDataMask.entries[formatInfo.dataMask]
        val dimension = bitMatrix.height
        dataMask.unmaskBitMatrix(bitMatrix, dimension)
    }

    fun setMirror(mirror: Boolean) {
        parsedVersion = null
        parsedFormatInfo = null
        this.mirror = mirror
    }

    /** 就地转置（只换 `x < y` 的那半边）。 */
    fun mirror() {
        for (x in 0 until bitMatrix.width) {
            for (y in x + 1 until bitMatrix.height) {
                if (bitMatrix.get(x, y) != bitMatrix.get(y, x)) {
                    bitMatrix.flip(y, x)
                    bitMatrix.flip(x, y)
                }
            }
        }
    }
}

/** 把扫出来的码字按版本+等级的块划分拆回各块 —— zxing 3.5.4 `DataBlock` 的移植。 */
internal class QrDataBlock private constructor(val numDataCodewords: Int, val codewords: ByteArray) {

    companion object {

        fun getDataBlocks(
            rawCodewords: ByteArray,
            version: QrVersion,
            ecLevel: QrErrorCorrectionLevel,
        ): Array<QrDataBlock> {
            require(rawCodewords.size == version.totalCodewords)
            // 先数出一共有多少块
            val ecBlocks = version.ecBlocksForLevel(ecLevel)
            val ecBlockArray = ecBlocks.ecBlocks
            var totalBlocks = 0
            for (ecBlock in ecBlockArray) totalBlocks += ecBlock.count
            // 再按每一块的数据码字数建出来
            val result = arrayOfNulls<QrDataBlock>(totalBlocks)
            var numResultBlocks = 0
            for (ecBlock in ecBlockArray) {
                for (i in 0 until ecBlock.count) {
                    val numDataCodewords = ecBlock.dataCodewords
                    val numBlockCodewords = ecBlocks.ecCodewordsPerBlock + numDataCodewords
                    result[numResultBlocks++] = QrDataBlock(numDataCodewords, ByteArray(numBlockCodewords))
                }
            }
            // 所有块的数据量一样多，只有最后 n 块（n 可能是 0）各多一个字节。先找它们从哪开始。
            val shorterBlocksTotalCodewords = result[0]!!.codewords.size
            var longerBlocksStartAt = result.size - 1
            while (longerBlocksStartAt >= 0) {
                val numCodewords = result[longerBlocksStartAt]!!.codewords.size
                if (numCodewords == shorterBlocksTotalCodewords) break
                longerBlocksStartAt--
            }
            longerBlocksStartAt++
            val shorterBlocksNumDataCodewords = shorterBlocksTotalCodewords - ecBlocks.ecCodewordsPerBlock
            // 先按所有块都有的那一截填
            var rawCodewordsOffset = 0
            for (i in 0 until shorterBlocksNumDataCodewords) {
                for (j in 0 until numResultBlocks) {
                    result[j]!!.codewords[i] = rawCodewords[rawCodewordsOffset++]
                }
            }
            // 把多一节的那些块的最后一节补上
            for (j in longerBlocksStartAt until numResultBlocks) {
                result[j]!!.codewords[shorterBlocksNumDataCodewords] = rawCodewords[rawCodewordsOffset++]
            }
            // 最后是各块的纠错字
            val max = result[0]!!.codewords.size
            for (i in shorterBlocksNumDataCodewords until max) {
                for (j in 0 until numResultBlocks) {
                    val iOffset = if (j < longerBlocksStartAt) i else i + 1
                    result[j]!!.codewords[iOffset] = rawCodewords[rawCodewordsOffset++]
                }
            }
            @Suppress("UNCHECKED_CAST")
            return result as Array<QrDataBlock>
        }
    }
}
