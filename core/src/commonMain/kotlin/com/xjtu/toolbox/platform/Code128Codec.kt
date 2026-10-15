package com.xjtu.toolbox.platform

/**
 * Code128 条形码的**编码器**（纯 Kotlin，三端同一份）—— 与 Android / jvm 那两份 zxing actual
 * 编出**逐模块相同**的条纹阵列。
 *
 * ## 为什么要有这一份
 *
 * 与 [QrCodec] 同一个理由：那条缝的 androidMain / jvmMain 两份 actual 用的是 zxing 的
 * `Code128Writer`（`encode(text, CODE_128, 0, 1)`，不带任何 hint），而 zxing 进不了 wasmJs。
 * 浏览器那一端原先如实返回 null ⇒ 付款码屏上那条条形码画不出来。这个对象补上它，
 * **纯 Kotlin、放 commonMain**（`:web` 里一行都没有，wasmJs 的 actual 只是转调）。
 *
 * ## 搬过来的范围（与 zxing 3.5.4 的对应关系）
 *
 * | 这里 | zxing |
 * |---|---|
 * | [encode] | `Code128Writer.encode(contents, hints=null)` → `check` + `encodeFast` |
 * | [chooseCode] / [findCType] | 同名私有函数（**不带** `FORCE_CODE_SET` hint 时的那条路；`CODE128_COMPACT` 也不开，与 actual 一致） |
 * | [produceResult] | 同名函数（校验位 = Σ 模式值 × 权重，再补停止符） |
 * | 条纹渲染 | `OneDimensionalCodeWriter.renderResult(code, width=0, height=1, sidesMargin=10)`：两边各留 5 个模块的静默区、高度 1（渲染方拉伸） |
 * | 107 张条纹图案表 | `Code128Reader.CODE_PATTERNS`（标准表，逐字抄） |
 *
 * 与 [QrCodec] 一样，「逐模块相同」由 `:core:jvmTest` 的 `CodeMatrixParityJvmTest` 逐格钉住，
 * 并且用 zxing 的**解码器**把这一份的条纹扫回原文。
 */
internal object Code128Codec {

    private const val CODE_START_A = 103
    private const val CODE_START_B = 104
    private const val CODE_START_C = 105
    private const val CODE_CODE_A = 101
    private const val CODE_CODE_B = 100
    private const val CODE_CODE_C = 99
    private const val CODE_STOP = 106

    /** 输入里用来表示控制字符的四个占位符（zxing 的 `ESCAPE_FNC_1`..`4`）。 */
    private const val ESCAPE_FNC_1 = '\u00f1'
    private const val ESCAPE_FNC_2 = '\u00f2'
    private const val ESCAPE_FNC_3 = '\u00f3'
    private const val ESCAPE_FNC_4 = '\u00f4'

    private const val CODE_FNC_1 = 102
    private const val CODE_FNC_2 = 97
    private const val CODE_FNC_3 = 96
    private const val CODE_FNC_4_A = 101
    private const val CODE_FNC_4_B = 100

    /** zxing 的 `Code128Reader.CODE_PATTERNS`：每张图案是 6（停止符 7）个条/空的模块数。 */
    private val CODE_PATTERNS = arrayOf(
        intArrayOf(2, 1, 2, 2, 2, 2),
        intArrayOf(2, 2, 2, 1, 2, 2),
        intArrayOf(2, 2, 2, 2, 2, 1),
        intArrayOf(1, 2, 1, 2, 2, 3),
        intArrayOf(1, 2, 1, 3, 2, 2),
        intArrayOf(1, 3, 1, 2, 2, 2),
        intArrayOf(1, 2, 2, 2, 1, 3),
        intArrayOf(1, 2, 2, 3, 1, 2),
        intArrayOf(1, 3, 2, 2, 1, 2),
        intArrayOf(2, 2, 1, 2, 1, 3),
        intArrayOf(2, 2, 1, 3, 1, 2),
        intArrayOf(2, 3, 1, 2, 1, 2),
        intArrayOf(1, 1, 2, 2, 3, 2),
        intArrayOf(1, 2, 2, 1, 3, 2),
        intArrayOf(1, 2, 2, 2, 3, 1),
        intArrayOf(1, 1, 3, 2, 2, 2),
        intArrayOf(1, 2, 3, 1, 2, 2),
        intArrayOf(1, 2, 3, 2, 2, 1),
        intArrayOf(2, 2, 3, 2, 1, 1),
        intArrayOf(2, 2, 1, 1, 3, 2),
        intArrayOf(2, 2, 1, 2, 3, 1),
        intArrayOf(2, 1, 3, 2, 1, 2),
        intArrayOf(2, 2, 3, 1, 1, 2),
        intArrayOf(3, 1, 2, 1, 3, 1),
        intArrayOf(3, 1, 1, 2, 2, 2),
        intArrayOf(3, 2, 1, 1, 2, 2),
        intArrayOf(3, 2, 1, 2, 2, 1),
        intArrayOf(3, 1, 2, 2, 1, 2),
        intArrayOf(3, 2, 2, 1, 1, 2),
        intArrayOf(3, 2, 2, 2, 1, 1),
        intArrayOf(2, 1, 2, 1, 2, 3),
        intArrayOf(2, 1, 2, 3, 2, 1),
        intArrayOf(2, 3, 2, 1, 2, 1),
        intArrayOf(1, 1, 1, 3, 2, 3),
        intArrayOf(1, 3, 1, 1, 2, 3),
        intArrayOf(1, 3, 1, 3, 2, 1),
        intArrayOf(1, 1, 2, 3, 1, 3),
        intArrayOf(1, 3, 2, 1, 1, 3),
        intArrayOf(1, 3, 2, 3, 1, 1),
        intArrayOf(2, 1, 1, 3, 1, 3),
        intArrayOf(2, 3, 1, 1, 1, 3),
        intArrayOf(2, 3, 1, 3, 1, 1),
        intArrayOf(1, 1, 2, 1, 3, 3),
        intArrayOf(1, 1, 2, 3, 3, 1),
        intArrayOf(1, 3, 2, 1, 3, 1),
        intArrayOf(1, 1, 3, 1, 2, 3),
        intArrayOf(1, 1, 3, 3, 2, 1),
        intArrayOf(1, 3, 3, 1, 2, 1),
        intArrayOf(3, 1, 3, 1, 2, 1),
        intArrayOf(2, 1, 1, 3, 3, 1),
        intArrayOf(2, 3, 1, 1, 3, 1),
        intArrayOf(2, 1, 3, 1, 1, 3),
        intArrayOf(2, 1, 3, 3, 1, 1),
        intArrayOf(2, 1, 3, 1, 3, 1),
        intArrayOf(3, 1, 1, 1, 2, 3),
        intArrayOf(3, 1, 1, 3, 2, 1),
        intArrayOf(3, 3, 1, 1, 2, 1),
        intArrayOf(3, 1, 2, 1, 1, 3),
        intArrayOf(3, 1, 2, 3, 1, 1),
        intArrayOf(3, 3, 2, 1, 1, 1),
        intArrayOf(3, 1, 4, 1, 1, 1),
        intArrayOf(2, 2, 1, 4, 1, 1),
        intArrayOf(4, 3, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 2, 2, 4),
        intArrayOf(1, 1, 1, 4, 2, 2),
        intArrayOf(1, 2, 1, 1, 2, 4),
        intArrayOf(1, 2, 1, 4, 2, 1),
        intArrayOf(1, 4, 1, 1, 2, 2),
        intArrayOf(1, 4, 1, 2, 2, 1),
        intArrayOf(1, 1, 2, 2, 1, 4),
        intArrayOf(1, 1, 2, 4, 1, 2),
        intArrayOf(1, 2, 2, 1, 1, 4),
        intArrayOf(1, 2, 2, 4, 1, 1),
        intArrayOf(1, 4, 2, 1, 1, 2),
        intArrayOf(1, 4, 2, 2, 1, 1),
        intArrayOf(2, 4, 1, 2, 1, 1),
        intArrayOf(2, 2, 1, 1, 1, 4),
        intArrayOf(4, 1, 3, 1, 1, 1),
        intArrayOf(2, 4, 1, 1, 1, 2),
        intArrayOf(1, 3, 4, 1, 1, 1),
        intArrayOf(1, 1, 1, 2, 4, 2),
        intArrayOf(1, 2, 1, 1, 4, 2),
        intArrayOf(1, 2, 1, 2, 4, 1),
        intArrayOf(1, 1, 4, 2, 1, 2),
        intArrayOf(1, 2, 4, 1, 1, 2),
        intArrayOf(1, 2, 4, 2, 1, 1),
        intArrayOf(4, 1, 1, 2, 1, 2),
        intArrayOf(4, 2, 1, 1, 1, 2),
        intArrayOf(4, 2, 1, 2, 1, 1),
        intArrayOf(2, 1, 2, 1, 4, 1),
        intArrayOf(2, 1, 4, 1, 2, 1),
        intArrayOf(4, 1, 2, 1, 2, 1),
        intArrayOf(1, 1, 1, 1, 4, 3),
        intArrayOf(1, 1, 1, 3, 4, 1),
        intArrayOf(1, 3, 1, 1, 4, 1),
        intArrayOf(1, 1, 4, 1, 1, 3),
        intArrayOf(1, 1, 4, 3, 1, 1),
        intArrayOf(4, 1, 1, 1, 1, 3),
        intArrayOf(4, 1, 1, 3, 1, 1),
        intArrayOf(1, 1, 3, 1, 4, 1),
        intArrayOf(1, 1, 4, 1, 3, 1),
        intArrayOf(3, 1, 1, 1, 4, 1),
        intArrayOf(4, 1, 1, 1, 3, 1),
        intArrayOf(2, 1, 1, 4, 1, 2),
        intArrayOf(2, 1, 1, 2, 1, 4),
        intArrayOf(2, 1, 1, 2, 3, 2),
        intArrayOf(2, 3, 3, 1, 1, 1, 2),
    )

    /**
     * 把一段文本编成 Code128 的**条纹阵列**（宽度 = 模块数，高度 1；静默区已经含在里面）。
     *
     * 空串、或出现 ASCII 127 以上的字符（zxing 在这种输入上直接抛 `IllegalArgumentException`，
     * 那四个 FNC 占位符除外）时返回 null —— 与那两份 actual 的 `runCatching { … }.getOrNull()`
     * 同一条口径。
     */
    fun encode(text: String): CodeMatrix? = runCatching { render(encodeModules(text)) }.getOrNull()

    /** zxing 的 `Code128Writer.encodeFast`：边挑码集边攒图案，返回一条 [BooleanArray] 模块串。 */
    private fun encodeModules(contents: String): BooleanArray {
        require(contents.isNotEmpty()) { "空内容" }
        for (c in contents) {
            // 四个 FNC 占位符是允许的，其余 ASCII 127 以上的字符这条缝编不了
            //（zxing 的原话：没有完整的 Latin-1 字符集支持，做不了移位与手工换集）。
            if (c !in ESCAPE_FNC_1..ESCAPE_FNC_4 && c.code > 127) {
                throw IllegalArgumentException("输入里有非 ASCII 字符：${c.code}")
            }
        }

        val length = contents.length
        val patterns = ArrayList<IntArray>()
        var checkSum = 0
        var checkWeight = 1
        var codeSet = 0
        var position = 0

        while (position < length) {
            val newCodeSet = chooseCode(contents, position, codeSet)

            val patternIndex: Int
            if (newCodeSet == codeSet) {
                when (contents[position]) {
                    ESCAPE_FNC_1 -> patternIndex = CODE_FNC_1
                    ESCAPE_FNC_2 -> patternIndex = CODE_FNC_2
                    ESCAPE_FNC_3 -> patternIndex = CODE_FNC_3
                    ESCAPE_FNC_4 -> patternIndex = if (codeSet == CODE_CODE_A) CODE_FNC_4_A else CODE_FNC_4_B
                    else -> when (codeSet) {
                        CODE_CODE_A -> {
                            var index = contents[position].code - ' '.code
                            // 空格以下的字符在图案表里排在 '_' 之后
                            if (index < 0) index += '`'.code
                            patternIndex = index
                        }
                        CODE_CODE_B -> patternIndex = contents[position].code - ' '.code
                        else -> {
                            // code C 一次编两个数字
                            require(position + 1 != length) { "数字编码的字符数不对（code C 要成对）" }
                            patternIndex = contents.substring(position, position + 2).toInt()
                            position++
                        }
                    }
                }
                position++
            } else {
                patternIndex = if (codeSet == 0) {
                    // 还没有码集：写起始符
                    when (newCodeSet) {
                        CODE_CODE_A -> CODE_START_A
                        CODE_CODE_B -> CODE_START_B
                        else -> CODE_START_C
                    }
                } else {
                    // 已经有码集：写切换符
                    newCodeSet
                }
                codeSet = newCodeSet
            }

            patterns.add(CODE_PATTERNS[patternIndex])

            checkSum += patternIndex * checkWeight
            if (position != 0) checkWeight++
        }
        return produceResult(patterns, checkSum)
    }

    /** zxing 的 `Code128Writer.produceResult`：补校验位与停止符，再摊成模块串。 */
    private fun produceResult(patterns: MutableList<IntArray>, checkSum: Int): BooleanArray {
        val sum = checkSum % 103
        patterns.add(CODE_PATTERNS[sum])
        patterns.add(CODE_PATTERNS[CODE_STOP])

        var codeWidth = 0
        for (pattern in patterns) for (width in pattern) codeWidth += width

        val result = BooleanArray(codeWidth)
        var pos = 0
        for (pattern in patterns) {
            // 每张图案都从黑开始、逐段翻色（zxing 的 `appendPattern`，startColor=true）
            var color = true
            for (len in pattern) {
                for (j in 0 until len) result[pos++] = color
                color = !color
            }
        }
        return result
    }

    private enum class CType { UNCODABLE, ONE_DIGIT, TWO_DIGITS, FNC_1 }

    /** zxing 的 `Code128Writer.findCType`：从 [start] 往后看两个字符属于哪一档。 */
    private fun findCType(value: String, start: Int): CType {
        val last = value.length
        if (start >= last) return CType.UNCODABLE
        var c = value[start]
        if (c == ESCAPE_FNC_1) return CType.FNC_1
        if (c < '0' || c > '9') return CType.UNCODABLE
        if (start + 1 >= last) return CType.ONE_DIGIT
        c = value[start + 1]
        if (c < '0' || c > '9') return CType.ONE_DIGIT
        return CType.TWO_DIGITS
    }

    /** zxing 的 `Code128Writer.chooseCode`：这一格该用哪个码集（A / B / C）。 */
    private fun chooseCode(value: String, start: Int, oldCode: Int): Int {
        var lookahead = findCType(value, start)
        if (lookahead == CType.ONE_DIGIT) {
            return if (oldCode == CODE_CODE_A) CODE_CODE_A else CODE_CODE_B
        }
        if (lookahead == CType.UNCODABLE) {
            if (start < value.length) {
                val c = value[start]
                if (c < ' ' ||
                    (oldCode == CODE_CODE_A && (c < '`' || (c >= ESCAPE_FNC_1 && c <= ESCAPE_FNC_4)))
                ) {
                    // A 集还能接下去（ASCII 0..95 与 FNC1..FNC4）
                    return CODE_CODE_A
                }
            }
            return CODE_CODE_B
        }
        if (oldCode == CODE_CODE_A && lookahead == CType.FNC_1) return CODE_CODE_A
        if (oldCode == CODE_CODE_C) return CODE_CODE_C
        if (oldCode == CODE_CODE_B) {
            if (lookahead == CType.FNC_1) return CODE_CODE_B
            // 连着两个数字：看后面是什么再决定换不换集
            lookahead = findCType(value, start + 2)
            if (lookahead == CType.UNCODABLE || lookahead == CType.ONE_DIGIT) return CODE_CODE_B
            if (lookahead == CType.FNC_1) {
                lookahead = findCType(value, start + 3)
                return if (lookahead == CType.TWO_DIGITS) CODE_CODE_C else CODE_CODE_B
            }
            // 到这里至少有 4 个连续数字：看是「现在就换」还是「下一轮再换」
            var index = start + 4
            while (findCType(value, index).also { lookahead = it } == CType.TWO_DIGITS) index += 2
            if (lookahead == CType.ONE_DIGIT) return CODE_CODE_B // 奇数个数字：晚一步换
            return CODE_CODE_C // 偶数个数字：现在就换
        }
        // 这里 oldCode == 0：选起始码集
        if (lookahead == CType.FNC_1) lookahead = findCType(value, start + 1)
        return if (lookahead == CType.TWO_DIGITS) CODE_CODE_C else CODE_CODE_B
    }

    /**
     * zxing 的 `OneDimensionalCodeWriter.renderResult(code, width=0, height=1, sidesMargin=10)`：
     * 静默区按**一个** margin 参数算（两侧加起来 10 个模块 ⇒ 每侧 5 个），高度取 1
     *（渲染方按可用宽度拉伸，见 `PaymentCodeScreen` 那条 `FillBounds` 的口径）。
     */
    private fun render(modules: BooleanArray): CodeMatrix {
        val sidesMargin = 10
        val inputWidth = modules.size
        val fullWidth = inputWidth + sidesMargin
        val outputWidth = maxOf(0, fullWidth)
        val outputHeight = maxOf(1, 1)

        val multiple = outputWidth / fullWidth
        val leftPadding = (outputWidth - inputWidth * multiple) / 2

        val bits = BooleanArray(outputWidth * outputHeight)
        for (inputX in 0 until inputWidth) {
            if (!modules[inputX]) continue
            val start = leftPadding + inputX * multiple
            for (x in start until start + multiple) {
                for (y in 0 until outputHeight) bits[y * outputWidth + x] = true
            }
        }
        return CodeMatrix(outputWidth, outputHeight, bits)
    }
}
