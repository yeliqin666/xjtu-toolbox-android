package com.xjtu.toolbox.platform.qr

import kotlin.math.sqrt

/** 透视变换与网格采样—— zxing 3.5.4 `com.google.zxing.common.PerspectiveTransform` / `GridSampler` / `DefaultGridSampler` 的移植（后两个类合成一个对象）。 */
internal class QrPerspectiveTransform(
    private val a11: Float,
    private val a21: Float,
    private val a31: Float,
    private val a12: Float,
    private val a22: Float,
    private val a32: Float,
    private val a13: Float,
    private val a23: Float,
    private val a33: Float,
) {

    fun transformPoints(points: FloatArray) {
        val maxI = points.size - 1 // points.size 一定是偶数
        var i = 0
        while (i < maxI) {
            val x = points[i]
            val y = points[i + 1]
            val denominator = a13 * x + a23 * y + a33
            points[i] = (a11 * x + a21 * y + a31) / denominator
            points[i + 1] = (a12 * x + a22 * y + a32) / denominator
            i += 2
        }
    }

    private fun buildAdjoint(): QrPerspectiveTransform = QrPerspectiveTransform(
        a22 * a33 - a23 * a32,
        a23 * a31 - a21 * a33,
        a21 * a32 - a22 * a31,
        a13 * a32 - a12 * a33,
        a11 * a33 - a13 * a31,
        a12 * a31 - a11 * a32,
        a12 * a23 - a13 * a22,
        a13 * a21 - a11 * a23,
        a11 * a22 - a12 * a21,
    )

    private fun times(other: QrPerspectiveTransform): QrPerspectiveTransform = QrPerspectiveTransform(
        a11 * other.a11 + a21 * other.a12 + a31 * other.a13,
        a11 * other.a21 + a21 * other.a22 + a31 * other.a23,
        a11 * other.a31 + a21 * other.a32 + a31 * other.a33,
        a12 * other.a11 + a22 * other.a12 + a32 * other.a13,
        a12 * other.a21 + a22 * other.a22 + a32 * other.a23,
        a12 * other.a31 + a22 * other.a32 + a32 * other.a33,
        a13 * other.a11 + a23 * other.a12 + a33 * other.a13,
        a13 * other.a21 + a23 * other.a22 + a33 * other.a23,
        a13 * other.a31 + a23 * other.a32 + a33 * other.a33,
    )

    companion object {

        fun quadrilateralToQuadrilateral(
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            x3: Float, y3: Float,
            x0p: Float, y0p: Float,
            x1p: Float, y1p: Float,
            x2p: Float, y2p: Float,
            x3p: Float, y3p: Float,
        ): QrPerspectiveTransform {
            val qToS = quadrilateralToSquare(x0, y0, x1, y1, x2, y2, x3, y3)
            val sToQ = squareToQuadrilateral(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p)
            return sToQ.times(qToS)
        }

        private fun squareToQuadrilateral(
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            x3: Float, y3: Float,
        ): QrPerspectiveTransform {
            val dx3 = x0 - x1 + x2 - x3
            val dy3 = y0 - y1 + y2 - y3
            if (dx3 == 0.0f && dy3 == 0.0f) {
                // 仿射
                return QrPerspectiveTransform(
                    x1 - x0, x2 - x1, x0,
                    y1 - y0, y2 - y1, y0,
                    0.0f, 0.0f, 1.0f,
                )
            }
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val denominator = dx1 * dy2 - dx2 * dy1
            val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
            val a23 = (dx1 * dy3 - dx3 * dy1) / denominator
            return QrPerspectiveTransform(
                x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
                y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
                a13, a23, 1.0f,
            )
        }

        private fun quadrilateralToSquare(
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            x3: Float, y3: Float,
        ): QrPerspectiveTransform =
            // 这里那个伴随矩阵正好当逆矩阵用
            squareToQuadrilateral(x0, y0, x1, y1, x2, y2, x3, y3).buildAdjoint()
    }
}

/** 见 [QrPerspectiveTransform] 的 KDoc。 */
internal object QrGridSampler {

    fun sampleGrid(
        image: QrBitMatrix,
        dimensionX: Int,
        dimensionY: Int,
        transform: QrPerspectiveTransform,
    ): QrBitMatrix {
        if (dimensionX <= 0 || dimensionY <= 0) throw QrNotFoundException()
        val bits = QrBitMatrix(dimensionX, dimensionY)
        val points = FloatArray(2 * dimensionX)
        for (y in 0 until dimensionY) {
            val max = points.size
            val iValue = y + 0.5f
            var x = 0
            while (x < max) {
                points[x] = (x / 2).toFloat() + 0.5f
                points[x + 1] = iValue
                x += 2
            }
            transform.transformPoints(points)
            // 快速看一眼这些点是不是还在图里：只查两端就够
            checkAndNudgePoints(image, points)
            try {
                var x2 = 0
                while (x2 < max) {
                    if (image.get(points[x2].toInt(), points[x2 + 1].toInt())) {
                        bits.set(x2 / 2, y) // 偏黑的像素
                    }
                    x2 += 2
                }
            } catch (_: IndexOutOfBoundsException) {
                // 定位图案认错时变换可能退化，中间点越界抛异常；按 zxing 的做法包成 NotFound。
                throw QrNotFoundException()
            }
        }
        return bits
    }

    /** 两端各扫一遍：坐标只允许是 `-1` 或刚好等于边长（那两种情况就地拽回界内），再往外就抛 `NotFound`。 */
    fun checkAndNudgePoints(image: QrBitMatrix, points: FloatArray) {
        val width = image.width
        val height = image.height
        // 从头开始查、边走边拽，直到碰见一个不用拽的
        var nudged = true
        val maxOffset = points.size - 1 // points.size 一定是偶数
        var offset = 0
        while (offset < maxOffset && nudged) {
            val x = points[offset].toInt()
            val y = points[offset + 1].toInt()
            if (x < -1 || x > width || y < -1 || y > height) throw QrNotFoundException()
            nudged = false
            if (x == -1) {
                points[offset] = 0.0f
                nudged = true
            } else if (x == width) {
                points[offset] = (width - 1).toFloat()
                nudged = true
            }
            if (y == -1) {
                points[offset + 1] = 0.0f
                nudged = true
            } else if (y == height) {
                points[offset + 1] = (height - 1).toFloat()
                nudged = true
            }
            offset += 2
        }
        // 再从尾往前查一遍
        nudged = true
        var back = points.size - 2
        while (back >= 0 && nudged) {
            val x = points[back].toInt()
            val y = points[back + 1].toInt()
            if (x < -1 || x > width || y < -1 || y > height) throw QrNotFoundException()
            nudged = false
            if (x == -1) {
                points[back] = 0.0f
                nudged = true
            } else if (x == width) {
                points[back] = (width - 1).toFloat()
                nudged = true
            }
            if (y == -1) {
                points[back + 1] = 0.0f
                nudged = true
            } else if (y == height) {
                points[back + 1] = (height - 1).toFloat()
                nudged = true
            }
            back -= 2
        }
    }
}

/** 「这一帧里没有码」（zxing 的 `NotFoundException`）。 */
internal class QrNotFoundException : Exception()

/** 「找到了码，但版本/格式信息读不通」（zxing 的 `FormatException`）。 */
internal class QrFormatException : Exception()

/** 「纠错解不出来」（zxing 的 `ChecksumException`）。 */
internal class QrChecksumException : Exception()

/** 与 `com.google.zxing.common.detector.MathUtils` 同名的那几件（都在 [QrDetector] 那一族里用）。 */
internal object QrMath {

    fun round(d: Float): Int = (d + if (d < 0.0f) -0.5f else 0.5f).toInt()

    fun distance(aX: Float, aY: Float, bX: Float, bY: Float): Float {
        val xDiff = (aX - bX).toDouble()
        val yDiff = (aY - bY).toDouble()
        return sqrt(xDiff * xDiff + yDiff * yDiff).toFloat()
    }

    fun distance(aX: Int, aY: Int, bX: Int, bY: Int): Float {
        val xDiff = (aX - bX).toDouble()
        val yDiff = (aY - bY).toDouble()
        return sqrt(xDiff * xDiff + yDiff * yDiff).toFloat()
    }
}
