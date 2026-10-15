package com.xjtu.toolbox.platform.qr

/** 二值化那一段—— zxing 3.5.4 `LuminanceSource` / `PlanarYUVLuminanceSource` / `Binarizer` / `GlobalHistogramBinarizer` / `HybridBinarizer` 的移植。 */
internal abstract class QrLuminanceSource(val width: Int, val height: Int) {

    /** 第 [y] 行的灰度，前 [width] 个字节有效（[row] 可复用以省分配）。 */
    abstract fun getRow(y: Int, row: ByteArray?): ByteArray

    /** 整幅灰度，行优先、每行 [width] 字节。 */
    abstract fun getMatrix(): ByteArray
}

/** 见 [QrLuminanceSource] 的对照表。 */
internal class QrPlanarYuvLuminanceSource(
    private val yuvData: ByteArray,
    private val dataWidth: Int,
    private val dataHeight: Int,
    private val left: Int,
    private val top: Int,
    width: Int,
    height: Int,
) : QrLuminanceSource(width, height) {

    init {
        require(left + width <= dataWidth && top + height <= dataHeight) {
            "裁出来的这一块放不进原始数据里"
        }
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(!(y < 0 || y >= height)) { "要的那一行不在这幅图里：$y" }
        val out = if (row == null || row.size < width) ByteArray(width) else row
        val offset = (y + top) * dataWidth + left
        yuvData.copyInto(out, 0, offset, offset + width)
        return out
    }

    override fun getMatrix(): ByteArray {
        // 整幅都要的话不必拷一份（zxing 那条捷径：调用方得自己知道要看 width*height 个字节）
        if (width == dataWidth && height == dataHeight) return yuvData
        val area = width * height
        val matrix = ByteArray(area)
        var inputOffset = top * dataWidth + left
        if (width == dataWidth) {
            yuvData.copyInto(matrix, 0, inputOffset, inputOffset + area)
            return matrix
        }
        for (y in 0 until height) {
            yuvData.copyInto(matrix, y * width, inputOffset, inputOffset + width)
            inputOffset += dataWidth
        }
        return matrix
    }
}

/** 见 [QrLuminanceSource] 的对照表。 */
internal abstract class QrBinarizer(val source: QrLuminanceSource) {

    abstract fun blackMatrix(): QrBitMatrix
}

/** 全局直方图那一版二值化：整幅一个黑点。 */
internal open class QrGlobalHistogramBinarizer(source: QrLuminanceSource) : QrBinarizer(source) {

    private var luminances: ByteArray = EMPTY

    override fun blackMatrix(): QrBitMatrix {
        val source = source
        val width = source.width
        val height = source.height
        val matrix = QrBitMatrix(width, height)
        // 抽四行做直方图 —— zxing 的注释写着：这在 blackbox 测试上比当年那条对角线采样更稳
        initArrays(width)
        for (y in 1 until 5) {
            val row = height * y / 5
            val localLuminances = source.getRow(row, luminances)
            val right = (width * 4) / 5
            for (x in width / 5 until right) {
                val pixel = localLuminances[x].toInt() and 0xff
                buckets[pixel shr LUMINANCE_SHIFT]++
            }
        }
        val blackPoint = estimateBlackPoint(buckets)
        // 黑色点估出来之前不读整幅 —— 虽然这样那四行会读两遍，但「尽早失败」比那点开销重要
        val localLuminances = source.getMatrix()
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                val pixel = localLuminances[offset + x].toInt() and 0xff
                if (pixel < blackPoint) matrix.set(x, y)
            }
        }
        return matrix
    }

    private fun initArrays(luminanceSize: Int) {
        if (luminances.size < luminanceSize) luminances = ByteArray(luminanceSize)
        buckets.fill(0)
    }

    private val buckets = IntArray(LUMINANCE_BUCKETS)

    companion object {

        private val EMPTY = ByteArray(0)
        private const val LUMINANCE_BITS = 5
        private const val LUMINANCE_SHIFT = 8 - LUMINANCE_BITS
        private const val LUMINANCE_BUCKETS = 1 shl LUMINANCE_BITS

        private fun estimateBlackPoint(buckets: IntArray): Int {
            // 找出最高的那个峰
            val numBuckets = buckets.size
            var maxBucketCount = 0
            var firstPeak = 0
            var firstPeakSize = 0
            for (x in 0 until numBuckets) {
                if (buckets[x] > firstPeakSize) {
                    firstPeak = x
                    firstPeakSize = buckets[x]
                }
                if (buckets[x] > maxBucketCount) maxBucketCount = buckets[x]
            }
            // 再找一个离它有点距离的次高峰
            var secondPeak = 0
            var secondPeakScore = 0
            for (x in 0 until numBuckets) {
                val distanceToBiggest = x - firstPeak
                // 离得越远越鼓励：乘距离的平方
                val score = buckets[x] * distanceToBiggest * distanceToBiggest
                if (score > secondPeakScore) {
                    secondPeak = x
                    secondPeakScore = score
                }
            }
            // 让 firstPeak 落在黑的那一侧
            if (firstPeak > secondPeak) {
                val temp = firstPeak
                firstPeak = secondPeak
                secondPeak = temp
            }
            // 对比度太低时选不出有意义的黑点：宁可抛出去，也别浪费时间解、更别解出一个假的
            if (secondPeak - firstPeak <= numBuckets / 16) throw QrNotFoundException()
            // 在两个峰之间找一个又低、又更靠近白峰的那个谷
            var bestValley = secondPeak - 1
            var bestValleyScore = -1
            for (x in secondPeak - 1 downTo firstPeak + 1) {
                val fromFirst = x - firstPeak
                val score = fromFirst * fromFirst * (secondPeak - x) * (maxBucketCount - buckets[x])
                if (score > bestValleyScore) {
                    bestValley = x
                    bestValleyScore = score
                }
            }
            return bestValley shl LUMINANCE_SHIFT
        }
    }
}

/** 见 [QrLuminanceSource] 的对照表。 */
internal class QrHybridBinarizer(source: QrLuminanceSource) : QrGlobalHistogramBinarizer(source) {

    private var matrix: QrBitMatrix? = null

    override fun blackMatrix(): QrBitMatrix {
        matrix?.let { return it }
        val source = source
        val width = source.width
        val height = source.height
        matrix = if (width >= MINIMUM_DIMENSION && height >= MINIMUM_DIMENSION) {
            val luminances = source.getMatrix()
            var subWidth = width shr BLOCK_SIZE_POWER
            if ((width and BLOCK_SIZE_MASK) != 0) subWidth++
            var subHeight = height shr BLOCK_SIZE_POWER
            if ((height and BLOCK_SIZE_MASK) != 0) subHeight++
            val blackPoints = calculateBlackPoints(luminances, subWidth, subHeight, width, height)
            val newMatrix = QrBitMatrix(width, height)
            calculateThresholdForBlock(luminances, subWidth, subHeight, width, height, blackPoints, newMatrix)
            newMatrix
        } else {
            // 太小的话退回全局直方图那一版
            super.blackMatrix()
        }
        return matrix!!
    }

    companion object {

        // 局部亮度用 5x5 的块算，每块 8x8 像素 —— 所以这是两个方向上能接受的最小尺寸
        private const val BLOCK_SIZE_POWER = 3
        private const val BLOCK_SIZE = 1 shl BLOCK_SIZE_POWER
        private const val BLOCK_SIZE_MASK = BLOCK_SIZE - 1
        private const val MINIMUM_DIMENSION = BLOCK_SIZE * 5
        private const val MIN_DYNAMIC_RANGE = 24

        private fun calculateThresholdForBlock(
            luminances: ByteArray,
            subWidth: Int,
            subHeight: Int,
            width: Int,
            height: Int,
            blackPoints: Array<IntArray>,
            matrix: QrBitMatrix,
        ) {
            val maxYOffset = height - BLOCK_SIZE
            val maxXOffset = width - BLOCK_SIZE
            for (y in 0 until subHeight) {
                var yoffset = y shl BLOCK_SIZE_POWER
                if (yoffset > maxYOffset) yoffset = maxYOffset
                val top = cap(y, subHeight - 3)
                for (x in 0 until subWidth) {
                    var xoffset = x shl BLOCK_SIZE_POWER
                    if (xoffset > maxXOffset) xoffset = maxXOffset
                    val left = cap(x, subWidth - 3)
                    var sum = 0
                    for (z in -2..2) {
                        val blackRow = blackPoints[top + z]
                        sum += blackRow[left - 2] + blackRow[left - 1] + blackRow[left] +
                            blackRow[left + 1] + blackRow[left + 2]
                    }
                    val average = sum / 25
                    thresholdBlock(luminances, xoffset, yoffset, average, width, matrix)
                }
            }
        }

        private fun cap(value: Int, max: Int): Int = if (value < 2) 2 else minOf(value, max)

        private fun thresholdBlock(
            luminances: ByteArray,
            xoffset: Int,
            yoffset: Int,
            threshold: Int,
            stride: Int,
            matrix: QrBitMatrix,
        ) {
            var offset = yoffset * stride + xoffset
            for (y in 0 until BLOCK_SIZE) {
                for (x in 0 until BLOCK_SIZE) {
                    // 这里要 `<=`：阈值是 0 的时候，全黑（0）的像素也得算黑
                    if ((luminances[offset + x].toInt() and 0xFF) <= threshold) {
                        matrix.set(xoffset + x, yoffset + y)
                    }
                }
                offset += stride
            }
        }

        private fun calculateBlackPoints(
            luminances: ByteArray,
            subWidth: Int,
            subHeight: Int,
            width: Int,
            height: Int,
        ): Array<IntArray> {
            val maxYOffset = height - BLOCK_SIZE
            val maxXOffset = width - BLOCK_SIZE
            val blackPoints = Array(subHeight) { IntArray(subWidth) }
            for (y in 0 until subHeight) {
                var yoffset = y shl BLOCK_SIZE_POWER
                if (yoffset > maxYOffset) yoffset = maxYOffset
                for (x in 0 until subWidth) {
                    var xoffset = x shl BLOCK_SIZE_POWER
                    if (xoffset > maxXOffset) xoffset = maxXOffset
                    var sum = 0
                    var min = 0xFF
                    var max = 0
                    var offset = yoffset * width + xoffset
                    var yy = 0
                    while (yy < BLOCK_SIZE) {
                        for (xx in 0 until BLOCK_SIZE) {
                            val pixel = luminances[offset + xx].toInt() and 0xFF
                            sum += pixel
                            // 还在找对比度
                            if (pixel < min) min = pixel
                            if (pixel > max) max = pixel
                        }
                        // 动态范围够了就把剩下的行快速加完
                        if (max - min > MIN_DYNAMIC_RANGE) {
                            yy++
                            offset += width
                            while (yy < BLOCK_SIZE) {
                                for (xx in 0 until BLOCK_SIZE) {
                                    sum += luminances[offset + xx].toInt() and 0xFF
                                }
                                yy++
                                offset += width
                            }
                        } else {
                            yy++
                            offset += width
                        }
                    }
                    // 默认估的就是块里的平均值
                    var average = sum shr (BLOCK_SIZE_POWER * 2)
                    if (max - min <= MIN_DYNAMIC_RANGE) {
                        // 块内对比度太低时不用平均值（会从噪声里劈出黑白），默认当亮背景，取最小值的一半。
                        average = min / 2
                        if (y > 0 && x > 0) {
                            val averageNeighborBlackPoint =
                                (blackPoints[y - 1][x] + (2 * blackPoints[y][x - 1]) + blackPoints[y - 1][x - 1]) / 4
                            if (min < averageNeighborBlackPoint) average = averageNeighborBlackPoint
                        }
                    }
                    blackPoints[y][x] = average
                }
            }
            return blackPoints
        }
    }
}
