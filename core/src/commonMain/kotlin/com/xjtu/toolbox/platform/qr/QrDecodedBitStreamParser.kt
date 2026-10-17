package com.xjtu.toolbox.platform.qr

/**
 * 把解出来的一串码字按**分段模式**拼成文本 —— zxing 3.5.4
 * `com.google.zxing.qrcode.decoder.DecodedBitStreamParser` 的**逐字对照**。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `TERMINATOR` / `FNC1_FIRST_POSITION` / `FNC1_SECOND_POSITION` / `STRUCTURED_APPEND` / `ECI` | 同名分支 | 逐条相同，含 FNC1 那两条对 `%`/`%%` 的改写（6.4.8.1 / 6.4.8.2） |
 * | `NUMERIC`（10 位三个数字、7 位两个、4 位一个） | 同名 | 逐条相同 |
 * | `ALPHANUMERIC`（11 位两个字符、6 位一个、那 45 个字符表） | 同名 | 逐条相同 |
 * | `BYTE`（`currentCharacterSetECI` 有就用它，没有就 [QrCharset.guessCharset]） | 同名 | 见 [QrCharset] 的边界表：解不出来的编码**整体解码失败** |
 * | `KANJI` / `HANZI` 两个数据段 | **认得出、解不出来** | ⚠️ 如实差异：那两个要 Shift_JIS / GB2312 码表（commonMain 没有）。分支留着、先按同样的位数把模式与字符数读掉，再抛 `FormatException` —— 与「这一段本实现解不了」是同一件事，而不是静默当成空串 |
 * | `KANJI` / `HANZI` 两个数据段 | **解出来**（[decodeKanjiSegment] / [decodeHanziSegment]） | 逐字相同：KANJI 用 Shift_JIS（zxing 那份是 `StringUtils.SHIFT_JIS_CHARSET`＝`Charset.forName("SJIS")`，就是 JDK 的 Shift_JIS）、HANZI 用 GB2312（`StringUtils.GB2312_CHARSET`），两张码表都在 [QrCjkTables] |
 * | `hints`（`CHARACTER_SET` / `ASSUMED_CHARSET` 那两张表） | **没搬** | 唯一的消费方（`:app` 的 `QrFrameDecoder`）只传 `POSSIBLE_FORMATS` + `TRY_HARDER`，从没传过字符集 hint |
 */
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
                            // 子集指示 = 1 就是 GB2312 那一档（别的子集按 zxing 的口径：什么都不加）
                            decodeHanziSegment(bits, result, countHanzi)
                        }
                    }
                    else -> {
                        // 「普通」模式：先读字符数（它的位数随版本档变）
                        val count = bits.readBits(mode.characterCountBits(version))
                        when (mode) {
                            QrMode.NUMERIC -> decodeNumericSegment(bits, result, count)
                            QrMode.ALPHANUMERIC -> decodeAlphanumericSegment(bits, result, count, fc1InEffect)
                            QrMode.BYTE -> decodeByteSegment(bits, result, count, currentCharacterSetEci)
                            // 13 位一个字符 ⇒ Shift_JIS 的两个字节（见 decodeKanjiSegment）
                            QrMode.KANJI -> decodeKanjiSegment(bits, result, count)
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

    /**
     * KANJI 段：13 位一个字符，先拼回 Shift_JIS 的两个字节，再整段按 Shift_JIS 解 —— zxing
     * `DecodedBitStreamParser.decodeKanjiSegment` 的逐字对照（连 `0x8140` / `0xC140` 两个偏移都一样）。
     */
    private fun decodeKanjiSegment(bits: QrBitSource, result: StringBuilder, count: Int) {
        // 别去读比手上还多的位
        if (count * 13 > bits.available()) throw QrFormatException()
        val buffer = ByteArray(2 * count)
        var offset = 0
        var remaining = count
        while (remaining > 0) {
            val twoBytes = bits.readBits(13)
            var assembledTwoBytes = ((twoBytes / 0xC0) shl 8) or (twoBytes % 0xC0)
            assembledTwoBytes += if (assembledTwoBytes < 0x1F00) 0x8140 else 0xC140
            buffer[offset] = ((assembledTwoBytes shr 8) and 0xFF).toByte()
            buffer[offset + 1] = (assembledTwoBytes and 0xFF).toByte()
            offset += 2
            remaining--
        }
        result.append(QrCharset.decode("Shift_JIS", buffer) ?: throw QrFormatException())
    }

    /**
     * HANZI 段：13 位一个字符，先拼回 GB2312 的两个字节，再整段按 GB2312 解 —— zxing
     * `DecodedBitStreamParser.decodeHanziSegment` 的逐字对照（两个偏移 `0xA1A1` / `0xA6A1` 与它一样）。
     */
    private fun decodeHanziSegment(bits: QrBitSource, result: StringBuilder, count: Int) {
        if (count * 13 > bits.available()) throw QrFormatException()
        val buffer = ByteArray(2 * count)
        var offset = 0
        var remaining = count
        while (remaining > 0) {
            val twoBytes = bits.readBits(13)
            var assembledTwoBytes = ((twoBytes / 0x60) shl 8) or (twoBytes % 0x60)
            assembledTwoBytes += if (assembledTwoBytes < 0xA00) 0xA1A1 else 0xA6A1
            buffer[offset] = ((assembledTwoBytes shr 8) and 0xFF).toByte()
            buffer[offset + 1] = (assembledTwoBytes and 0xFF).toByte()
            offset += 2
            remaining--
        }
        result.append(QrCharset.decode("GB2312", buffer) ?: throw QrFormatException())
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

/**
 * 码字 → 文本：拆块、纠错、再交给 [QrDecodedBitStreamParser] —— zxing 3.5.4
 * `com.google.zxing.qrcode.decoder.Decoder` 的**逐字对照**。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `decode(BitMatrix, hints)`（含「常规读失败就试一次镜像读」那一段） | [QrDecoder.decode] | 逐条相同：`remask` → `setMirror(true)` → 先读版本再读格式 → `mirror()` → 重解；失败时抛**第一次**那个异常 |
 * | `decode(BitMatrixParser)` 私有那一层（读版本/格式、拆块、逐块纠错、拼回数据字节） | 同名 | 逐行相同，`errorsCorrected` 是各块之和 |
 * | `correctErrors` | 同名 | `ReedSolomonException` ⇒ `ChecksumException` |
 * | `QRCodeDecoderMetaData`（镜像标志，给 `ResultPoint` 用的） | **没搬** | 这一条缝只交文本出去，没有 `ResultPoint` 要修正（见 [QrReader] 的 KDoc） |
 * | `DecoderResult`（`rawBytes` / `byteSegments` / `ecLevel` / `structuredAppend*` / `symbologyModifier`） | **没搬** | 同上 |
 * | `decode(boolean[][] image, hints)` | **没搬** | 那是给「已经是纯黑白矩阵」的调用方用的（`BitMatrix.parse`），这一端从像素来 |
 */
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
