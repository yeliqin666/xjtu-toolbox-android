package com.xjtu.toolbox.platform.qr

/**
 * GF(256) 上的 Reed-Solomon 解码 —— zxing 3.5.4 `com.google.zxing.common.reedsolomon`
 * 那三个类的**逐字对照**（`GenericGF` / `GenericGFPoly` / `ReedSolomonDecoder`）。
 *
 * 只搬了二维码要用的那一档：`QR_CODE_FIELD_256 = GF(2^8)`、本原多项式 `0x11D`、`generatorBase = 0`。
 * zxing 那一份里的 Aztec / DataMatrix / MaxiCode 那几个域**没搬**（这一条链一个都不碰）。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `GenericGF.QR_CODE_FIELD_256` | [QrGenericGf.QR_CODE_FIELD_256] | 指数表/对数表按同一段循环生成 |
 * | `GenericGF.multiply / inverse / exp / log / buildMonomial / addOrSubtract` | 同名 | `addOrSubtract` 就是异或 |
 * | `GenericGFPoly`（系数首位归零、`evaluateAt`、加减乘、`multiplyByMonomial`、`divide`） | [QrGenericGfPoly] | `divide` 在 RS 解码里用不到，但它与 `evaluateAt` 的分支是同一份口径，一起搬了 |
 * | `ReedSolomonDecoder.decodeWithECCount` | [QrReedSolomonDecoder.decodeWithEcCount] | 返回「纠了几个错」，与 zxing 一样 |
 * | `findErrorMagnitudes` 里那段「看着古怪」的写法 | 原样照抄 | 那是 zxing 为绕过某些 JDK 上 Hotspot 的 bug 写的（`term & 0x1` 那两行），数学上等价于 `1 ^ term`；**照抄**是为了与它逐位相同 |
 * | `ReedSolomonEncoder` | **没搬** | 编码器在 D12 ① 已经有一份纯 Kotlin 的（`QrCodec` 里的纠错块生成），不在这一批 |
 */
/** 一个有限域（见 [QrReedSolomonDecoder] 的 KDoc）。 */
internal class QrGenericGf(private val primitive: Int, private val size: Int, private val generatorBase: Int) {

    companion object {

        /** 二维码用的那个域：`x^8 + x^4 + x^3 + x^2 + 1`，生成元底数 0。 */
        val QR_CODE_FIELD_256: QrGenericGf = QrGenericGf(0b100011101, 256, 0)

        /** `a ^ b` —— 加减法在特征 2 的域上是同一件事。 */
        fun addOrSubtract(a: Int, b: Int): Int = a xor b
    }
    private val expTable: IntArray = IntArray(size)
    private val logTable: IntArray = IntArray(size)

    init {
        var x = 1
        for (i in 0 until size) {
            expTable[i] = x
            x *= 2 // 2（也就是多项式 x）是这个域的本原元
            if (x >= size) {
                x = x xor primitive
                x = x and (size - 1)
            }
        }
        for (i in 0 until size - 1) {
            logTable[expTable[i]] = i
        }
        // logTable[0] == 0，但那一位永远不该被用到
    }

    val zero: QrGenericGfPoly by lazy { QrGenericGfPoly(this, intArrayOf(0)) }
    val one: QrGenericGfPoly by lazy { QrGenericGfPoly(this, intArrayOf(1)) }

    fun buildMonomial(degree: Int, coefficient: Int): QrGenericGfPoly {
        require(degree >= 0)
        if (coefficient == 0) return zero
        val coefficients = IntArray(degree + 1)
        coefficients[0] = coefficient
        return QrGenericGfPoly(this, coefficients)
    }

    fun exp(a: Int): Int = expTable[a]

    fun log(a: Int): Int {
        require(a != 0)
        return logTable[a]
    }

    fun inverse(a: Int): Int {
        require(a != 0) { "0 没有逆元" }
        return expTable[size - logTable[a] - 1]
    }

    fun multiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[(logTable[a] + logTable[b]) % (size - 1)]
    }

    val domainSize: Int get() = size
    val base: Int get() = generatorBase
}

/** 域上的一个多项式，系数从**最高次**开始排。 */
internal class QrGenericGfPoly(private val field: QrGenericGf, coefficients: IntArray) {

    val coefficients: IntArray

    init {
        require(coefficients.isNotEmpty())
        val length = coefficients.size
        if (length > 1 && coefficients[0] == 0) {
            // 除常数多项式 "0" 之外，最高次项的系数都应当非零
            var firstNonZero = 1
            while (firstNonZero < length && coefficients[firstNonZero] == 0) firstNonZero++
            this.coefficients = if (firstNonZero == length) {
                intArrayOf(0)
            } else {
                coefficients.copyOfRange(firstNonZero, length)
            }
        } else {
            this.coefficients = coefficients
        }
    }

    val degree: Int get() = coefficients.size - 1

    val isZero: Boolean get() = coefficients[0] == 0

    fun coefficient(degree: Int): Int = coefficients[coefficients.size - 1 - degree]

    fun evaluateAt(a: Int): Int {
        if (a == 0) return coefficient(0) // 只剩 x^0 那一项
        if (a == 1) {
            // 就是所有系数之和（异或和）
            var result = 0
            for (c in coefficients) result = QrGenericGf.addOrSubtract(result, c)
            return result
        }
        var result = coefficients[0]
        for (i in 1 until coefficients.size) {
            result = QrGenericGf.addOrSubtract(field.multiply(a, result), coefficients[i])
        }
        return result
    }

    fun addOrSubtract(other: QrGenericGfPoly): QrGenericGfPoly {
        if (isZero) return other
        if (other.isZero) return this
        var smaller = this.coefficients
        var larger = other.coefficients
        if (smaller.size > larger.size) {
            val temp = smaller
            smaller = larger
            larger = temp
        }
        val sumDiff = IntArray(larger.size)
        val lengthDiff = larger.size - smaller.size
        // 只有次数高的那个多项式才有的那些高次项
        larger.copyInto(sumDiff, 0, 0, lengthDiff)
        for (i in lengthDiff until larger.size) {
            sumDiff[i] = QrGenericGf.addOrSubtract(smaller[i - lengthDiff], larger[i])
        }
        return QrGenericGfPoly(field, sumDiff)
    }

    fun multiply(other: QrGenericGfPoly): QrGenericGfPoly {
        if (isZero || other.isZero) return field.zero
        val a = this.coefficients
        val b = other.coefficients
        val product = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            val aCoeff = a[i]
            for (j in b.indices) {
                product[i + j] = QrGenericGf.addOrSubtract(product[i + j], field.multiply(aCoeff, b[j]))
            }
        }
        return QrGenericGfPoly(field, product)
    }

    fun multiply(scalar: Int): QrGenericGfPoly {
        if (scalar == 0) return field.zero
        if (scalar == 1) return this
        val product = IntArray(coefficients.size)
        for (i in coefficients.indices) product[i] = field.multiply(coefficients[i], scalar)
        return QrGenericGfPoly(field, product)
    }

    fun multiplyByMonomial(degree: Int, coefficient: Int): QrGenericGfPoly {
        require(degree >= 0)
        if (coefficient == 0) return field.zero
        val product = IntArray(coefficients.size + degree)
        for (i in coefficients.indices) product[i] = field.multiply(coefficients[i], coefficient)
        return QrGenericGfPoly(field, product)
    }

    /** 长除：返回 `[商, 余数]`（RS 解码用不到，与 [evaluateAt] 同一份口径一起搬过来）。 */
    fun divide(other: QrGenericGfPoly): Array<QrGenericGfPoly> {
        require(!other.isZero) { "除以 0" }
        var quotient = field.zero
        var remainder: QrGenericGfPoly = this
        val inverseDenominatorLeadingTerm = field.inverse(other.coefficient(other.degree))
        while (remainder.degree >= other.degree && !remainder.isZero) {
            val degreeDifference = remainder.degree - other.degree
            val scale = field.multiply(remainder.coefficient(remainder.degree), inverseDenominatorLeadingTerm)
            val term = other.multiplyByMonomial(degreeDifference, scale)
            val iterationQuotient = field.buildMonomial(degreeDifference, scale)
            quotient = quotient.addOrSubtract(iterationQuotient)
            remainder = remainder.addOrSubtract(term)
        }
        return arrayOf(quotient, remainder)
    }
}

/** 纠错解不出来（zxing 的 `ReedSolomonException`）。 */
internal class QrReedSolomonException : Exception()

/**
 * Reed-Solomon 解码（Berlekamp-Massey 走欧几里得算法那一版）——见 [QrGenericGf] 的 KDoc。
 */
internal class QrReedSolomonDecoder(private val field: QrGenericGf) {

    /** 解 [received]（原地改），`twoS` 是纠错字的个数。返回**纠正了几个错**。 */
    fun decodeWithEcCount(received: IntArray, twoS: Int): Int {
        val poly = QrGenericGfPoly(field, received)
        val syndromeCoefficients = IntArray(twoS)
        var noError = true
        for (i in 0 until twoS) {
            val eval = poly.evaluateAt(field.exp(i + field.base))
            syndromeCoefficients[syndromeCoefficients.size - 1 - i] = eval
            if (eval != 0) noError = false
        }
        if (noError) return 0

        val syndrome = QrGenericGfPoly(field, syndromeCoefficients)
        val sigmaOmega = runEuclideanAlgorithm(field.buildMonomial(twoS, 1), syndrome, twoS)
        val sigma = sigmaOmega[0]
        val omega = sigmaOmega[1]
        val errorLocations = findErrorLocations(sigma)
        val errorMagnitudes = findErrorMagnitudes(omega, errorLocations)
        for (i in errorLocations.indices) {
            val position = received.size - 1 - field.log(errorLocations[i])
            if (position < 0) throw QrReedSolomonException()
            received[position] = QrGenericGf.addOrSubtract(received[position], errorMagnitudes[i])
        }
        return errorLocations.size
    }

    private fun runEuclideanAlgorithm(aIn: QrGenericGfPoly, bIn: QrGenericGfPoly, r: Int): Array<QrGenericGfPoly> {
        // 假定 a 的次数 >= b 的次数
        var a = aIn
        var b = bIn
        if (a.degree < b.degree) {
            val temp = a
            a = b
            b = temp
        }
        var rLast = a
        var rNow = b
        var tLast = field.zero
        var t = field.one
        // 一直跑到 r 的次数小于 R/2
        while (2 * rNow.degree >= r) {
            val rLastLast = rLast
            val tLastLast = tLast
            rLast = rNow
            tLast = t
            // 用 rLast 除 rLastLast，商进 q、余数进 rNow
            if (rLast.isZero) throw QrReedSolomonException()
            rNow = rLastLast
            var q = field.zero
            val dltInverse = field.inverse(rLast.coefficient(rLast.degree))
            while (rNow.degree >= rLast.degree && !rNow.isZero) {
                val degreeDiff = rNow.degree - rLast.degree
                val scale = field.multiply(rNow.coefficient(rNow.degree), dltInverse)
                q = q.addOrSubtract(field.buildMonomial(degreeDiff, scale))
                rNow = rNow.addOrSubtract(rLast.multiplyByMonomial(degreeDiff, scale))
            }
            t = q.multiply(tLast).addOrSubtract(tLastLast)
            if (rNow.degree >= rLast.degree) {
                throw IllegalStateException("除法没能把多项式降次：r=$rNow, rLast=$rLast")
            }
        }
        val sigmaTildeAtZero = t.coefficient(0)
        if (sigmaTildeAtZero == 0) throw QrReedSolomonException()
        val inverse = field.inverse(sigmaTildeAtZero)
        val sigma = t.multiply(inverse)
        val omega = rNow.multiply(inverse)
        return arrayOf(sigma, omega)
    }

    private fun findErrorLocations(errorLocator: QrGenericGfPoly): IntArray {
        // 直接套 Chien 搜索
        val numErrors = errorLocator.degree
        if (numErrors == 1) return intArrayOf(errorLocator.coefficient(1))
        val result = IntArray(numErrors)
        var e = 0
        var i = 1
        while (i < field.domainSize && e < numErrors) {
            if (errorLocator.evaluateAt(i) == 0) {
                result[e] = field.inverse(i)
                e++
            }
            i++
        }
        if (e != numErrors) throw QrReedSolomonException()
        return result
    }

    private fun findErrorMagnitudes(errorEvaluator: QrGenericGfPoly, errorLocations: IntArray): IntArray {
        // 直接套 Forney 公式
        val s = errorLocations.size
        val result = IntArray(s)
        for (i in 0 until s) {
            val xiInverse = field.inverse(errorLocations[i])
            var denominator = 1
            for (j in 0 until s) {
                if (i != j) {
                    // 数学上就是 `1 ^ field.multiply(errorLocations[j], xiInverse)`；
                    // zxing 用下面这种「看着古怪」的写法绕开某些 JDK 上 Hotspot 的 bug —— 逐字照抄。
                    val term = field.multiply(errorLocations[j], xiInverse)
                    val termPlus1 = if ((term and 0x1) == 0) term or 1 else term and (1.inv())
                    denominator = field.multiply(denominator, termPlus1)
                }
            }
            result[i] = field.multiply(errorEvaluator.evaluateAt(xiInverse), field.inverse(denominator))
            if (field.base != 0) {
                result[i] = field.multiply(result[i], xiInverse)
            }
        }
        return result
    }
}
