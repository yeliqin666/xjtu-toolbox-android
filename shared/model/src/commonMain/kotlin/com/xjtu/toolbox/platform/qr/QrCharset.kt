package com.xjtu.toolbox.platform.qr

/** 字符集那一小片—— zxing 3.5.4 `com.google.zxing.common.CharacterSetECI` 与`StringUtils.guessCharset` 的移植，但只留下能在 commonMain 里真解出来的那几个。 */
internal object QrCharset {

    /** ECI 值 → 编码名（zxing 的 `VALUE_TO_ECI` 那张表，逐个值抄过来）。 */
    private val ECI_NAMES: Map<Int, String> = buildMap {
        put(0, "Cp437")
        put(2, "Cp437")
        put(1, "ISO-8859-1")
        put(3, "ISO-8859-1")
        put(4, "ISO-8859-2")
        put(5, "ISO-8859-3")
        put(6, "ISO-8859-4")
        put(7, "ISO-8859-5")
        put(8, "ISO-8859-6")
        put(9, "ISO-8859-7")
        put(10, "ISO-8859-8")
        put(11, "ISO-8859-9")
        put(12, "ISO-8859-10")
        put(13, "ISO-8859-11")
        put(15, "ISO-8859-13")
        put(16, "ISO-8859-14")
        put(17, "ISO-8859-15")
        put(18, "ISO-8859-16")
        put(20, "Shift_JIS")
        put(21, "windows-1250")
        put(22, "windows-1251")
        put(23, "windows-1252")
        put(24, "windows-1256")
        put(25, "UTF-16BE")
        put(26, "UTF-8")
        put(27, "US-ASCII")
        put(170, "US-ASCII")
        put(28, "Big5")
        put(29, "GB2312")
        put(30, "EUC-KR")
    }

    /** ECI 值 → 编码名（`CharacterSetECI.getCharacterSetECIByValue`）。 */
    fun eciNameFor(value: Int): String? {
        if (value < 0 || value >= 900) return null
        return ECI_NAMES[value]
    }

    /** 按编码名解一段字节；解不出来（见文件头的边界表）返回 null，由调用方抛 `FormatException`。 */
    fun decode(name: String, bytes: ByteArray): String? = when (name) {
        "ISO-8859-1" -> bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        "UTF-8", "UTF8" -> bytes.decodeToString()
        "US-ASCII", "ASCII" ->
            bytes.map { if ((it.toInt() and 0xFF) < 0x80) (it.toInt() and 0x7F).toChar() else '\uFFFD' }.joinToString("")
        "UTF-16BE", "UnicodeBig" -> decodeUtf16(bytes, bigEndian = true)
        "UTF-16" -> {
            // 有 BOM 时按 BOM 走；没有就按大端（Java 的 UTF-16 解码器也是这个规矩）
            when {
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    decodeUtf16(bytes.copyOfRange(2, bytes.size), bigEndian = true)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    decodeUtf16(bytes.copyOfRange(2, bytes.size), bigEndian = false)
                else -> decodeUtf16(bytes, bigEndian = true)
            }
        }
        else -> null
    }

    private fun decodeUtf16(bytes: ByteArray, bigEndian: Boolean): String {
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i + 1 < bytes.size) {
            val hi = bytes[i].toInt() and 0xFF
            val lo = bytes[i + 1].toInt() and 0xFF
            val unit = if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi
            sb.append(unit.toChar())
            i += 2
        }
        if (i < bytes.size) sb.append('\uFFFD') // 落单的那个字节（Java 也是替换字符）
        return sb.toString()
    }

    /** 猜一段字节是哪个编码 —— zxing 3.5.4 `StringUtils.guessCharset(bytes, null)` 的移植。 */
    fun guessCharset(bytes: ByteArray): String {
        // 先试 UTF-16：带它那个 BOM 的就算 UTF-16
        if (bytes.size > 2 &&
            ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))
        ) {
            return "UTF-16"
        }
        // 眼下只试着分开 ISO-8859-1 / UTF-8 / Shift_JIS 这三样 —— 它们是最常见的
        val length = bytes.size
        var canBeIso88591 = true
        var canBeShiftJis = true // zxing 那份是「SHIFT_JIS_CHARSET != null」；JVM 上都非 null
        var canBeUtf8 = true
        var utf8BytesLeft = 0
        var utf2BytesChars = 0
        var utf3BytesChars = 0
        var utf4BytesChars = 0
        var sjisBytesLeft = 0
        var sjisKatakanaChars = 0
        var sjisCurKatakanaWordLength = 0
        var sjisCurDoubleBytesWordLength = 0
        var sjisMaxKatakanaWordLength = 0
        var sjisMaxDoubleBytesWordLength = 0
        var isoHighOther = 0
        val utf8bom = bytes.size > 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        var i = 0
        while (i < length && (canBeIso88591 || canBeShiftJis || canBeUtf8)) {
            val value = bytes[i].toInt() and 0xFF
            // UTF-8
            if (canBeUtf8) {
                if (utf8BytesLeft > 0) {
                    if ((value and 0x80) == 0) {
                        canBeUtf8 = false
                    } else {
                        utf8BytesLeft--
                    }
                } else if ((value and 0x80) != 0) {
                    if ((value and 0x40) == 0) {
                        canBeUtf8 = false
                    } else {
                        utf8BytesLeft++
                        if ((value and 0x20) == 0) {
                            utf2BytesChars++
                        } else {
                            utf8BytesLeft++
                            if ((value and 0x10) == 0) {
                                utf3BytesChars++
                            } else {
                                utf8BytesLeft++
                                if ((value and 0x08) == 0) {
                                    utf4BytesChars++
                                } else {
                                    canBeUtf8 = false
                                }
                            }
                        }
                    }
                }
            }
            // ISO-8859-1
            if (canBeIso88591) {
                if (value > 0x7F && value < 0xA0) {
                    canBeIso88591 = false
                } else if (value > 0x9F && (value < 0xC0 || value == 0xD7 || value == 0xF7)) {
                    isoHighOther++
                }
            }
            // Shift_JIS
            if (canBeShiftJis) {
                if (sjisBytesLeft > 0) {
                    if (value < 0x40 || value == 0x7F || value > 0xFC) {
                        canBeShiftJis = false
                    } else {
                        sjisBytesLeft--
                    }
                } else if (value == 0x80 || value == 0xA0 || value > 0xEF) {
                    canBeShiftJis = false
                } else if (value > 0xA0 && value < 0xE0) {
                    sjisKatakanaChars++
                    sjisCurDoubleBytesWordLength = 0
                    sjisCurKatakanaWordLength++
                    if (sjisCurKatakanaWordLength > sjisMaxKatakanaWordLength) {
                        sjisMaxKatakanaWordLength = sjisCurKatakanaWordLength
                    }
                } else if (value > 0x7F) {
                    sjisBytesLeft++
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength++
                    if (sjisCurDoubleBytesWordLength > sjisMaxDoubleBytesWordLength) {
                        sjisMaxDoubleBytesWordLength = sjisCurDoubleBytesWordLength
                    }
                } else {
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength = 0
                }
            }
            i++
        }
        if (canBeUtf8 && utf8BytesLeft > 0) canBeUtf8 = false
        if (canBeShiftJis && sjisBytesLeft > 0) canBeShiftJis = false
        // 简单：有 BOM 或者至少有一个不是单字节的合法字符（且没有证据说它不是 UTF-8），就收工
        if (canBeUtf8 && (utf8bom || utf2BytesChars + utf3BytesChars + utf4BytesChars > 0)) return "UTF-8"
        // 简单：假定 SJIS，或者连着 >= 3 个非 ASCII 字符（且没有反证），就收工
        // （`ASSUME_SHIFT_JIS` 在这个仓里恒 false，见文件头）
        if (canBeShiftJis && (sjisMaxKatakanaWordLength >= 3 || sjisMaxDoubleBytesWordLength >= 3)) {
            return "Shift_JIS"
        }
        // SJIS / ISO-8859-1 启发式：只有两个连着的片假名，或 ≥10% 字节是「像大写」的非字母数字 Latin-1 ⇒ SJIS。
        if (canBeIso88591 && canBeShiftJis) {
            return if ((sjisMaxKatakanaWordLength == 2 && sjisKatakanaChars == 2) || isoHighOther * 10 >= length) {
                "Shift_JIS"
            } else {
                "ISO-8859-1"
            }
        }
        // 再不成，按 ISO-8859-1、Shift_JIS、UTF-8 的顺序试，最后落到平台默认编码
        if (canBeIso88591) return "ISO-8859-1"
        if (canBeShiftJis) return "Shift_JIS"
        if (canBeUtf8) return "UTF-8"
        // 实在猜不出，就按平台默认编码来（JVM 上就是 UTF-8）
        return "UTF-8"
    }
}
