package com.xjtu.toolbox.platform.qr

/** 把解出来的一串码字按分段模式拼成文本。 */
internal object QrDecodedBitStreamParser {

    private val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:".toCharArray()

    private const val GB2312_SUBSET = 1

    fun decode(bytes: ByteArray, version: QrVersion): String {
        val bits = QrBitSource(bytes)
        val result = StringBuilder(50)
        var currentCharacterSetEci: String? = null
        var fc1InEffect = false
        var mode: QrMode
        try {
            do {
                // 还有一段就读一段……
                mode = if (bits.available() < 4) {
                    // 行吧，就当读完了。规范上这里本该有一个 TERMINATOR
                    QrMode.TERMINATOR
                } else {
                    QrMode.forBits(bits.readBits(4)) // 模式占 4 位
                }
                when (mode) {
                    QrMode.TERMINATOR -> Unit
                    QrMode.FNC1_FIRST_POSITION -> {
                        // FNC1 我们做的不多，只按规范把解出来的结果改一点
                        fc1InEffect = true
                    }
                    QrMode.FNC1_SECOND_POSITION -> {
                        fc1InEffect = true
                    }
                    QrMode.STRUCTURED_APPEND -> {
                        if (bits.available() < 16) throw QrFormatException()
                        // 序号与校验是给上层用的，这里读掉就丢
                        bits.readBits(8)
                        bits.readBits(8)
                    }
                    QrMode.ECI -> {
                        // ECI 不按字符数走
                        val value = parseEciValue(bits)
                        currentCharacterSetEci = QrCharset.eciNameFor(value) ?: throw QrFormatException()
                    }
                    QrMode.HANZI -> {
                        // 汉字模式不带字符数那一档在前面，它的子集指示紧跟在模式指示之后
                        val subset = bits.readBits(4)
                        val countHanzi = bits.readBits(mode.characterCountBits(version))
                        if (subset == GB2312_SUBSET) {
                            // ⚠️ GB2312 码表在 commonMain 里没有 ⇒ 这一路解不出来（见文件头）
                            throw QrFormatException()
                        }
                    }
                    else -> {
                        // 「普通」模式：先读字符数（它的位数随版本档变）
                        val count = bits.readBits(mode.characterCountBits(version))
                        when (mode) {
                            QrMode.NUMERIC -> decodeNumericSegment(bits, result, count)
                            QrMode.ALPHANUMERIC -> decodeAlphanumericSegment(bits, result, count, fc1InEffect)
                            QrMode.BYTE -> decodeByteSegment(bits, result, count, currentCharacterSetEci)
                            QrMode.KANJI -> {
                                // ⚠️ Shift_JIS 码表在 commonMain 里没有 ⇒ 这一路解不出来（见文件头）
                                throw QrFormatException()
                            }
                            else -> throw QrFormatException()
                        }
                    }
                }
            } while (mode != QrMode.TERMINATOR)
        } catch (_: IllegalArgumentException) {
            // 从 readBits() 里出来的
            throw QrFormatException()
        }
        return result.toString()
    }

    private fun decodeByteSegment(
        bits: QrBitSource,
        result: StringBuilder,
        count: Int,
        currentCharacterSetEci: String?,
    ) {
        // 别去读比手上还多的位
        if (8 * count > bits.available()) throw QrFormatException()
        val readBytes = ByteArray(count)
        for (i in 0 until count) readBytes[i] = bits.readBits(8).toByte()
        // 规范对这一段没写清楚（见 zxing 的注释：6.4.5 没说要按哪个编码解）——
        // ISO-8859-1 与 Shift_JIS 都见过。有 ECI 就按它，没有就只能猜。
        val encoding = currentCharacterSetEci ?: QrCharset.guessCharset(readBytes)
        result.append(QrCharset.decode(encoding, readBytes) ?: throw QrFormatException())
    }

    private fun toAlphaNumericChar(value: Int): Char {
        if (value >= ALPHANUMERIC_CHARS.size) throw QrFormatException()
        return ALPHANUMERIC_CHARS[value]
    }

    private fun decodeAlphanumericSegment(
        bits: QrBitSource,
        result: StringBuilder,
        countIn: Int,
        fc1InEffect: Boolean,
    ) {
        var count = countIn
        // 一次读两个字符
        val start = result.length
        while (count > 1) {
            if (bits.available() < 11) throw QrFormatException()
            val nextTwoCharsBits = bits.readBits(11)
            result.append(toAlphaNumericChar(nextTwoCharsBits / 45))
            result.append(toAlphaNumericChar(nextTwoCharsBits % 45))
            count -= 2
        }
        if (count == 1) {
            // 特殊情形：只剩一个字符
            if (bits.available() < 6) throw QrFormatException()
            result.append(toAlphaNumericChar(bits.readBits(6)))
        }
        // 见规范 6.4.8.1 / 6.4.8.2
        if (fc1InEffect) {
            // 在 FNC1 模式下要把结果再捋一遍：
            var i = start
            while (i < result.length) {
                if (result[i] == '%') {
                    if (i < result.length - 1 && result[i + 1] == '%') {
                        // %% 渲染成一个 %
                        result.deleteAt(i + 1)
                    } else {
                        // 字母数字模式下 % 要换成 FNC1 的分隔符 0x1D
                        result[i] = 0x1D.toChar()
                    }
                }
                i++
            }
        }
    }

    private fun decodeNumericSegment(bits: QrBitSource, result: StringBuilder, countIn: Int) {
        var count = countIn
        // 一次读三个数字
        while (count >= 3) {
            // 三个数字占 10 位
            if (bits.available() < 10) throw QrFormatException()
            val threeDigitsBits = bits.readBits(10)
            if (threeDigitsBits >= 1000) throw QrFormatException()
            result.append(toAlphaNumericChar(threeDigitsBits / 100))
            result.append(toAlphaNumericChar((threeDigitsBits / 10) % 10))
            result.append(toAlphaNumericChar(threeDigitsBits % 10))
            count -= 3
        }
        if (count == 2) {
            // 剩两个数字，占 7 位
            if (bits.available() < 7) throw QrFormatException()
            val twoDigitsBits = bits.readBits(7)
            if (twoDigitsBits >= 100) throw QrFormatException()
            result.append(toAlphaNumericChar(twoDigitsBits / 10))
            result.append(toAlphaNumericChar(twoDigitsBits % 10))
        } else if (count == 1) {
            // 剩一个数字
            if (bits.available() < 4) throw QrFormatException()
            val digitBits = bits.readBits(4)
            if (digitBits >= 10) throw QrFormatException()
            result.append(toAlphaNumericChar(digitBits))
        }
    }

    private fun parseEciValue(bits: QrBitSource): Int {
        val firstByte = bits.readBits(8)
        if ((firstByte and 0x80) == 0) return firstByte and 0x7F // 一个字节
        if ((firstByte and 0xC0) == 0x80) {
            // 两个字节
            val secondByte = bits.readBits(8)
            return ((firstByte and 0x3F) shl 8) or secondByte
        }
        if ((firstByte and 0xE0) == 0xC0) {
            // 三个字节
            val secondThirdBytes = bits.readBits(16)
            return ((firstByte and 0x1F) shl 16) or secondThirdBytes
        }
        throw QrFormatException()
    }
}

/** 码字 → 文本：拆块、纠错、再交给 [QrDecodedBitStreamParser]。 */
internal class QrDecoder {

    private val rsDecoder = QrReedSolomonDecoder(QrGenericGf.QR_CODE_FIELD_256)

    /** 解出文本；解不出来抛 [QrFormatException] / [QrChecksumException]。 */
    fun decode(bits: QrBitMatrix): String {
        val parser = QrBitMatrixParser(bits)
        var formatException: QrFormatException? = null
        var checksumException: QrChecksumException? = null
        try {
            return decode(parser)
        } catch (e: QrFormatException) {
            formatException = e
        } catch (e: QrChecksumException) {
            checksumException = e
        }
        try {
            // 把位矩阵还原回去
            parser.remask()
            // 接下来要按**镜像**读一次版本与格式信息
            parser.setMirror(true)
            // 先把版本读掉
            parser.readVersion()
            // 再把格式信息读掉
            parser.readFormatInformation()
            // 摆成镜像的样子
            parser.mirror()
            return decode(parser)
        } catch (_: QrFormatException) {
            // 抛第一次读的那个异常
            throw formatException ?: checksumException!!
        } catch (_: QrChecksumException) {
            throw formatException ?: checksumException!!
        }
    }

    private fun decode(parser: QrBitMatrixParser): String {
        val version = parser.readVersion()
        val ecLevel = parser.readFormatInformation().errorCorrectionLevel
        // 读码字
        val codewords = parser.readCodewords()
        // 按块拆开
        val dataBlocks = QrDataBlock.getDataBlocks(codewords, version, ecLevel)
        // 数一数总共有多少数据字节
        var totalBytes = 0
        for (dataBlock in dataBlocks) totalBytes += dataBlock.numDataCodewords
        val resultBytes = ByteArray(totalBytes)
        var resultOffset = 0
        // 逐块纠错，再把数据拼成一串
        for (dataBlock in dataBlocks) {
            val codewordBytes = dataBlock.codewords
            val numDataCodewords = dataBlock.numDataCodewords
            correctErrors(codewordBytes, numDataCodewords)
            for (i in 0 until numDataCodewords) {
                resultBytes[resultOffset++] = codewordBytes[i]
            }
        }
        // 把这串字节解成内容
        return QrDecodedBitStreamParser.decode(resultBytes, version)
    }

    private fun correctErrors(codewordBytes: ByteArray, numDataCodewords: Int) {
        val numCodewords = codewordBytes.size
        // 先化成 int 数组
        val codewordsInts = IntArray(numCodewords)
        for (i in 0 until numCodewords) codewordsInts[i] = codewordBytes[i].toInt() and 0xFF
        try {
            rsDecoder.decodeWithEcCount(codewordsInts, codewordBytes.size - numDataCodewords)
        } catch (_: QrReedSolomonException) {
            throw QrChecksumException()
        }
        // 拷回来 —— 只关心数据那几位（纠错字里的错不管）
        for (i in 0 until numDataCodewords) {
            codewordBytes[i] = codewordsInts[i].toByte()
        }
    }
}
