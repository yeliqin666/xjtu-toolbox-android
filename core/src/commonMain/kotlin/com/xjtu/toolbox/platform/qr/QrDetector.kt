package com.xjtu.toolbox.platform.qr

/**
 * 三个定位图案与一个校正图案 —— zxing 3.5.4 `com.google.zxing.qrcode.detector` 的
 * `FinderPattern` / `FinderPatternInfo` / `AlignmentPattern` / `ResultPoint` 的**逐字对照**。
 *
 * `ResultPoint` 在 zxing 里是「图上一个点」的通用父类（`getX/getY` + `orderBestPatterns` 那套排序）。
 * 这里把它并成 [QrResultPoint] 一个类，`FinderPattern` / `AlignmentPattern` 各自继承它加一个
 * 「估计的模块边长」—— 与 zxing 的继承关系一致。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `ResultPoint`（`x`/`y`、`equals`/`hashCode`/`toString`、`orderBestPatterns`、`distance`、`crossProductZ`） | [QrResultPoint] | `orderBestPatterns` 那套「谁离另外两个最近就是 B，再用叉积定 A/C」逐行相同 |
 * | `FinderPattern`（`estimatedModuleSize`、`count`、`aboutEquals`、`combineEstimate`） | [QrFinderPattern] | 逐行相同 |
 * | `FinderPatternInfo`（bottomLeft/topLeft/topRight 三个具名 getter） | [QrFinderPatternInfo] | 顺带把 zxing 那个「数组下标 0/1/2 = 左下/左上/右上」的口径也搬过来 |
 * | `AlignmentPattern`（`aboutEquals`、`combineEstimate`） | [QrAlignmentPattern] | 逐行相同 |
 * | `ResultPointCallback` / `DecodeHintType.NEED_RESULT_POINT_CALLBACK` | **没搬** | zxing 用它把「找到候选点了」实时报给一个回调（给取景框画提示用）。本端没有那个 hint（见 [QrReader] 的 KDoc），所以那一整条链不存在 |
 */
internal open class QrResultPoint(val x: Float, val y: Float) {

    override fun toString(): String = "($x,$y)"

    companion object {

        /**
         * 把三个定位图案排成 A（顶上那个）、B（直角）、C（另一个）。
         *
         * 离另外两个最近的那个是 B（直角顶点），另外两个先用叉积判断要不要换 —— 见 zxing 的注释。
         */
        fun orderBestPatterns(patterns: Array<QrResultPoint>) {
            // 先量三个点两两之间的距离
            val zeroOneDistance = distance(patterns[0], patterns[1])
            val oneTwoDistance = distance(patterns[1], patterns[2])
            val zeroTwoDistance = distance(patterns[0], patterns[2])
            val pointA: QrResultPoint
            val pointB: QrResultPoint
            val pointC: QrResultPoint
            // 离另外两个最近的那个是 B；A 与 C 先猜
            if (oneTwoDistance >= zeroOneDistance && oneTwoDistance >= zeroTwoDistance) {
                pointB = patterns[0]
                pointA = patterns[1]
                pointC = patterns[2]
            } else if (zeroTwoDistance >= oneTwoDistance && zeroTwoDistance >= zeroOneDistance) {
                pointB = patterns[1]
                pointA = patterns[0]
                pointC = patterns[2]
            } else {
                pointB = patterns[2]
                pointA = patterns[0]
                pointC = patterns[1]
            }
            // 用叉积判断 A 与 C 有没有弄反：问的是 BC × BA 的 z 分量是不是正的，
            // 那正是我们要的 A、B、C 排列。负的话就是反了，把 A 与 C 换一下。
            if (crossProductZ(pointA, pointB, pointC) < 0.0f) {
                val temp = pointA
                val a = pointC
                patterns[0] = a
                patterns[1] = pointB
                patterns[2] = temp
            } else {
                patterns[0] = pointA
                patterns[1] = pointB
                patterns[2] = pointC
            }
        }

        fun distance(pattern1: QrResultPoint, pattern2: QrResultPoint): Float =
            QrMath.distance(pattern1.x, pattern1.y, pattern2.x, pattern2.y)

        private fun crossProductZ(pointA: QrResultPoint, pointB: QrResultPoint, pointC: QrResultPoint): Float {
            val bX = pointB.x
            val bY = pointB.y
            return ((pointC.x - bX) * (pointA.y - bY)) - ((pointC.y - bY) * (pointA.x - bX))
        }
    }
}

/** 一个候选/确认的定位图案。见 [QrResultPoint] 的对照表。 */
internal class QrFinderPattern(
    posX: Float,
    posY: Float,
    val estimatedModuleSize: Float,
    val count: Int = 1,
) : QrResultPoint(posX, posY) {

    /** 位置与模块大小都差不多 ⇒ 是同一个定位图案（`count` 就是它被看到几次）。 */
    fun aboutEquals(moduleSize: Float, i: Float, j: Float): Boolean {
        if (kotlin.math.abs(i - y) <= moduleSize && kotlin.math.abs(j - x) <= moduleSize) {
            val moduleSizeDiff = kotlin.math.abs(moduleSize - estimatedModuleSize)
            return moduleSizeDiff <= 1.0f || moduleSizeDiff <= estimatedModuleSize
        }
        return false
    }

    /** 把这次的观察与已有那个合并（按 `count` 加权平均）。 */
    fun combineEstimate(i: Float, j: Float, newModuleSize: Float): QrFinderPattern {
        val combinedCount = count + 1
        val combinedX = (count * x + j) / combinedCount
        val combinedY = (count * y + i) / combinedCount
        val combinedModuleSize = (count * estimatedModuleSize + newModuleSize) / combinedCount
        return QrFinderPattern(combinedX, combinedY, combinedModuleSize, combinedCount)
    }
}

/**
 * 三个定位图案，且**已经排好序**（下标 0/1/2 = 左下/左上/右上）。见 [QrResultPoint] 的对照表。
 */
internal class QrFinderPatternInfo(patternCenters: Array<QrResultPoint>) {

    val bottomLeft: QrResultPoint = patternCenters[0]
    val topLeft: QrResultPoint = patternCenters[1]
    val topRight: QrResultPoint = patternCenters[2]
}

/** 一个候选/确认的校正图案。见 [QrResultPoint] 的对照表。 */
internal class QrAlignmentPattern(
    posX: Float,
    posY: Float,
    val estimatedModuleSize: Float,
) : QrResultPoint(posX, posY) {

    fun aboutEquals(moduleSize: Float, i: Float, j: Float): Boolean {
        if (kotlin.math.abs(i - y) <= moduleSize && kotlin.math.abs(j - x) <= moduleSize) {
            val moduleSizeDiff = kotlin.math.abs(moduleSize - estimatedModuleSize)
            return moduleSizeDiff <= 1.0f || moduleSizeDiff <= estimatedModuleSize
        }
        return false
    }

    fun combineEstimate(i: Float, j: Float, newModuleSize: Float): QrAlignmentPattern {
        val combinedX = (x + j) / 2.0f
        val combinedY = (y + i) / 2.0f
        val combinedModuleSize = (estimatedModuleSize + newModuleSize) / 2.0f
        return QrAlignmentPattern(combinedX, combinedY, combinedModuleSize)
    }
}

/**
 * 在一行一行的黑白游程里找那三个定位图案（1:1:3:1:1）—— zxing 3.5.4 的
 * `FinderPatternFinder` 的**逐字对照**。
 *
 * 这是整条链里最长的一段：按 [MIN_SKIP] 跳行扫、遇到 1:1:3:1:1 的游程就上下左右各交叉验证一次
 * （`crossCheckVertical` / `crossCheckHorizontal` / `crossCheckDiagonal`），确认过的点攒起来
 * （同一位置再看到就 `combineEstimate` 把 `count` 加一），最后从候选里挑「三个模块大小接近、
 * 又最接近等腰直角三角形」的那一组（`selectBestPatterns`）。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `find(hints)` 里那个 `tryHarder` | [find] 的参数 `tryHarder` | 唯一的 hint 消费（`iSkip` 那一档）|
 * | `MIN_SKIP = 3` / `MAX_MODULES = 97` / `CENTER_QUORUM = 2` | 同名常量 | `MAX_MODULES` 那句注释照抄：跑得动到 20 版 |
 * | `handlePossibleCenter` / `findRowSkip` / `haveMultiplyConfirmedCenters` / `selectBestPatterns` / `foundPatternCross` / `foundPatternDiagonal` / `centerFromEnd` / `doClearCounts` / `doShiftCounts2` / `EstimatedModuleComparator` | 同名 | 逐行相同，含 `selectBestPatterns` 里那段手写排序与「|c²-2b²|+|c²-2a²| 越小越像等腰直角」那条判据 |
 * | `resultPointCallback` | **没搬** | 见 [QrResultPoint] 的对照表 |
 * | `clearCounts` / `shiftCounts2`（`@Deprecated` 的两个壳） | **没搬** | 它们只是转调 `doClearCounts` / `doShiftCounts2`，没有人调 |
 */
internal class QrFinderPatternFinder(private val image: QrBitMatrix) {

    private val possibleCenters = mutableListOf<QrFinderPattern>()
    private var hasSkipped = false
    private val crossCheckStateCount = IntArray(5)

    fun find(tryHarder: Boolean): QrFinderPatternInfo {
        val maxI = image.height
        val maxJ = image.width
        // 我们在找黑/白/黑/白/黑 的 1:1:3:1:1 游程；这里数的是已经看到几段
        // 假定支持的最大版本占图高的 1/4，再算上中心那块是 3 个模块宽 —— 这给出中心可能的最小像素数，
        // 于是可以按这个间隔跳行。开 TRY_HARDER 时不跳，什么密度的版本都找。
        var iSkip = (3 * maxI) / (4 * MAX_MODULES)
        if (iSkip < MIN_SKIP || tryHarder) iSkip = MIN_SKIP

        var done = false
        val stateCount = IntArray(5)
        var i = iSkip - 1
        while (i < maxI && !done) {
            // 读一行的黑/白值
            doClearCounts(stateCount)
            var currentState = 0
            var j = 0
            while (j < maxJ) {
                if (image.get(j, i)) {
                    // 黑像素
                    if ((currentState and 1) == 1) currentState++ // 刚才在数白
                    stateCount[currentState]++
                } else {
                    // 白像素
                    if ((currentState and 1) == 0) {
                        // 刚才在数黑
                        if (currentState == 4) {
                            // 中了？
                            if (foundPatternCross(stateCount)) {
                                val confirmed = handlePossibleCenter(stateCount, i, j)
                                if (confirmed) {
                                    // 改成隔行看：每行都看太贵，识别率也没好到哪去
                                    iSkip = 2
                                    if (hasSkipped) {
                                        done = haveMultiplyConfirmedCenters()
                                    } else {
                                        val rowSkip = findRowSkip()
                                        if (rowSkip > stateCount[2]) {
                                            // 从「已经确认的中心」那一行跳到「第三个未确认中心」的上沿，
                                            // 但往回退一点，好把定位图案中心那一整段都看到。
                                            // 跳 rowSkip 行，但要退回 stateCount[2]（刚看到的那个中心的大小），
                                            // 还要退回 iSkip（循环末尾会再加回来）
                                            i += rowSkip - stateCount[2] - iSkip
                                            j = maxJ - 1
                                        }
                                    }
                                } else {
                                    doShiftCounts2(stateCount)
                                    currentState = 3
                                    j++
                                    continue
                                }
                                // 清干净重新找
                                currentState = 0
                                doClearCounts(stateCount)
                            } else {
                                // 不是，游程往回挪两格
                                doShiftCounts2(stateCount)
                                currentState = 3
                            }
                        } else {
                            stateCount[++currentState]++
                        }
                    } else {
                        // 刚才在数白
                        stateCount[currentState]++
                    }
                }
                j++
            }
            if (foundPatternCross(stateCount)) {
                val confirmed = handlePossibleCenter(stateCount, i, maxJ)
                if (confirmed) {
                    iSkip = stateCount[0]
                    if (hasSkipped) done = haveMultiplyConfirmedCenters()
                }
            }
            i += iSkip
        }
        val patternInfo = selectBestPatterns()
        QrResultPoint.orderBestPatterns(patternInfo)
        return QrFinderPatternInfo(patternInfo)
    }

    private fun doClearCounts(counts: IntArray) {
        counts.fill(0)
    }

    private fun doShiftCounts2(stateCount: IntArray) {
        stateCount[0] = stateCount[2]
        stateCount[1] = stateCount[3]
        stateCount[2] = stateCount[4]
        stateCount[3] = 1
        stateCount[4] = 0
    }

    private fun centerFromEnd(stateCount: IntArray, end: Int): Float =
        (end - stateCount[4] - stateCount[3]) - stateCount[2] / 2.0f

    /** 这一段游程像不像 1:1:3:1:1（容 50% 的偏差）。 */
    private fun foundPatternCross(stateCount: IntArray): Boolean {
        var totalModuleSize = 0
        for (i in 0 until 5) {
            val count = stateCount[i]
            if (count == 0) return false
            totalModuleSize += count
        }
        if (totalModuleSize < 7) return false
        val moduleSize = totalModuleSize / 7.0f
        val maxVariance = moduleSize / 2.0f
        return (
            kotlin.math.abs(moduleSize - stateCount[0]) < maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[1]) < maxVariance &&
                kotlin.math.abs(3.0f * moduleSize - stateCount[2]) < 3 * maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[3]) < maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[4]) < maxVariance
            )
    }

    /** 对角方向的那一版（容 75% 的偏差 —— 透视之下对角线本来就更容易歪）。 */
    private fun foundPatternDiagonal(stateCount: IntArray): Boolean {
        var totalModuleSize = 0
        for (i in 0 until 5) {
            val count = stateCount[i]
            if (count == 0) return false
            totalModuleSize += count
        }
        if (totalModuleSize < 7) return false
        val moduleSize = totalModuleSize / 7.0f
        val maxVariance = moduleSize / 1.333f
        return (
            kotlin.math.abs(moduleSize - stateCount[0]) < maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[1]) < maxVariance &&
                kotlin.math.abs(3.0f * moduleSize - stateCount[2]) < 3 * maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[3]) < maxVariance &&
                kotlin.math.abs(moduleSize - stateCount[4]) < maxVariance
            )
    }

    private fun getCrossCheckStateCount(): IntArray {
        doClearCounts(crossCheckStateCount)
        return crossCheckStateCount
    }

    /** 从候选中心往左上/右下各走一趟，看那三段是不是也成 1:1:3:1:1。 */
    private fun crossCheckDiagonal(centerI: Int, centerJ: Int): Boolean {
        val stateCount = getCrossCheckStateCount()
        // 从中心往左上数中间那块黑
        var i = 0
        while (centerI >= i && centerJ >= i && image.get(centerJ - i, centerI - i)) {
            stateCount[2]++
            i++
        }
        if (stateCount[2] == 0) return false
        // 继续往左上数白的那一圈
        while (centerI >= i && centerJ >= i && !image.get(centerJ - i, centerI - i)) {
            stateCount[1]++
            i++
        }
        if (stateCount[1] == 0) return false
        // 再数外圈的黑
        while (centerI >= i && centerJ >= i && image.get(centerJ - i, centerI - i)) {
            stateCount[0]++
            i++
        }
        if (stateCount[0] == 0) return false
        val maxI = image.height
        val maxJ = image.width
        // 再从中心往右下数
        i = 1
        while (centerI + i < maxI && centerJ + i < maxJ && image.get(centerJ + i, centerI + i)) {
            stateCount[2]++
            i++
        }
        while (centerI + i < maxI && centerJ + i < maxJ && !image.get(centerJ + i, centerI + i)) {
            stateCount[3]++
            i++
        }
        if (stateCount[3] == 0) return false
        while (centerI + i < maxI && centerJ + i < maxJ && image.get(centerJ + i, centerI + i)) {
            stateCount[4]++
            i++
        }
        if (stateCount[4] == 0) return false
        return foundPatternDiagonal(stateCount)
    }

    /** 竖直方向交叉验证；`maxCount` 是「每一段最多数多少个像素」。 */
    private fun crossCheckVertical(startI: Int, centerJ: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxI = image.height
        val stateCount = getCrossCheckStateCount()
        // 从中心往上数
        var i = startI
        while (i >= 0 && image.get(centerJ, i)) {
            stateCount[2]++
            i--
        }
        if (i < 0) return Float.NaN
        while (i >= 0 && !image.get(centerJ, i) && stateCount[1] <= maxCount) {
            stateCount[1]++
            i--
        }
        // 这一段已经太多模块、或者跑出画面了
        if (i < 0 || stateCount[1] > maxCount) return Float.NaN
        while (i >= 0 && image.get(centerJ, i) && stateCount[0] <= maxCount) {
            stateCount[0]++
            i--
        }
        if (stateCount[0] > maxCount) return Float.NaN
        // 再从中心往下数
        i = startI + 1
        while (i < maxI && image.get(centerJ, i)) {
            stateCount[2]++
            i++
        }
        if (i == maxI) return Float.NaN
        while (i < maxI && !image.get(centerJ, i) && stateCount[3] < maxCount) {
            stateCount[3]++
            i++
        }
        if (i == maxI || stateCount[3] >= maxCount) return Float.NaN
        while (i < maxI && image.get(centerJ, i) && stateCount[4] < maxCount) {
            stateCount[4]++
            i++
        }
        if (stateCount[4] >= maxCount) return Float.NaN
        // 找是找到了一个像定位图案的截面，但要是比原来那个大出 40% 以上，就当它是误报
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        if (5 * kotlin.math.abs(stateCountTotal - originalStateCountTotal) >= 2 * originalStateCountTotal) {
            return Float.NaN
        }
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, i) else Float.NaN
    }

    /** 水平方向交叉验证。 */
    private fun crossCheckHorizontal(startJ: Int, centerI: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxJ = image.width
        val stateCount = getCrossCheckStateCount()
        var j = startJ
        while (j >= 0 && image.get(j, centerI)) {
            stateCount[2]++
            j--
        }
        if (j < 0) return Float.NaN
        while (j >= 0 && !image.get(j, centerI) && stateCount[1] <= maxCount) {
            stateCount[1]++
            j--
        }
        if (j < 0 || stateCount[1] > maxCount) return Float.NaN
        while (j >= 0 && image.get(j, centerI) && stateCount[0] <= maxCount) {
            stateCount[0]++
            j--
        }
        if (stateCount[0] > maxCount) return Float.NaN
        j = startJ + 1
        while (j < maxJ && image.get(j, centerI)) {
            stateCount[2]++
            j++
        }
        if (j == maxJ) return Float.NaN
        while (j < maxJ && !image.get(j, centerI) && stateCount[3] < maxCount) {
            stateCount[3]++
            j++
        }
        if (j == maxJ || stateCount[3] >= maxCount) return Float.NaN
        while (j < maxJ && image.get(j, centerI) && stateCount[4] < maxCount) {
            stateCount[4]++
            j++
        }
        if (stateCount[4] >= maxCount) return Float.NaN
        // 同样：比原来那个大太多就是误报
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        if (5 * kotlin.math.abs(stateCountTotal - originalStateCountTotal) >= originalStateCountTotal) {
            return Float.NaN
        }
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, j) else Float.NaN
    }

    /**
     * 一行里看到一段 1:1:3:1:1 ⇒ 上下左右各验一次；三个方向都过就把它记下来（同一个位置再看到就
     * 把 `count` 加一）。
     *
     * @return 是否确认了一个中心（确认过之后外层会把跳行间隔改成 2）
     */
    private fun handlePossibleCenter(stateCount: IntArray, i: Int, j: Int): Boolean {
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2] + stateCount[3] + stateCount[4]
        var centerJ = centerFromEnd(stateCount, j)
        val centerI = crossCheckVertical(i, centerJ.toInt(), stateCount[2], stateCountTotal)
        if (!centerI.isNaN()) {
            // 再横着验一遍
            centerJ = crossCheckHorizontal(centerJ.toInt(), centerI.toInt(), stateCount[2], stateCountTotal)
            if (!centerJ.isNaN() && crossCheckDiagonal(centerI.toInt(), centerJ.toInt())) {
                val estimatedModuleSize = stateCountTotal / 7.0f
                var found = false
                for (index in possibleCenters.indices) {
                    val center = possibleCenters[index]
                    // 找位置与模块大小都差不多的那个
                    if (center.aboutEquals(estimatedModuleSize, centerI, centerJ)) {
                        possibleCenters[index] = center.combineEstimate(centerI, centerJ, estimatedModuleSize)
                        found = true
                        break
                    }
                }
                if (!found) {
                    possibleCenters.add(QrFinderPattern(centerJ, centerI, estimatedModuleSize))
                }
                return true
            }
        }
        return false
    }

    /**
     * 已经确认过两个中心之后，往下最多能跳多少行还不至于漏掉第三个：最坏情形就是那两个中心
     * 在 x/y 上的差之差。
     */
    private fun findRowSkip(): Int {
        val max = possibleCenters.size
        if (max <= 1) return 0
        var firstConfirmedCenter: QrFinderPattern? = null
        for (center in possibleCenters) {
            if (center.count >= CENTER_QUORUM) {
                if (firstConfirmedCenter == null) {
                    firstConfirmedCenter = center
                } else {
                    // 已经有两个确认的中心了
                    hasSkipped = true
                    return (
                        kotlin.math.abs(firstConfirmedCenter.x - center.x) -
                            kotlin.math.abs(firstConfirmedCenter.y - center.y)
                        ).toInt() / 2
                }
            }
        }
        return 0
    }

    private fun haveMultiplyConfirmedCenters(): Boolean {
        var confirmedCount = 0
        var totalModuleSize = 0.0f
        val max = possibleCenters.size
        for (pattern in possibleCenters) {
            if (pattern.count >= CENTER_QUORUM) {
                confirmedCount++
                totalModuleSize += pattern.estimatedModuleSize
            }
        }
        if (confirmedCount < 3) return false
        // 好了，至少有三个确认的中心；但其中可能有一个是误报，那还得接着找。
        // 判据是「估出来的模块大小差得太散」：我们拍一个「偏离平均值的总和超过总估计值 5%」就算太散。
        val average = totalModuleSize / max
        var totalDeviation = 0.0f
        for (pattern in possibleCenters) {
            totalDeviation += kotlin.math.abs(pattern.estimatedModuleSize - average)
        }
        return totalDeviation <= 0.05f * totalModuleSize
    }

    private fun squaredDistance(a: QrResultPoint, b: QrResultPoint): Double {
        val x = (a.x - b.x).toDouble()
        val y = (a.y - b.y).toDouble()
        return x * x + y * y
    }

    /** 从候选里挑出最像「三个模块大小接近、又最接近等腰直角三角形」的那三个。 */
    private fun selectBestPatterns(): Array<QrResultPoint> {
        val startSize = possibleCenters.size
        if (startSize < 3) throw QrNotFoundException() // 定位图案不够
        possibleCenters.removeAll { it.count < CENTER_QUORUM }
        possibleCenters.sortBy { it.estimatedModuleSize }
        var distortion = Double.MAX_VALUE
        val bestPatterns = arrayOfNulls<QrResultPoint>(3)
        for (i in 0 until possibleCenters.size - 2) {
            val fpi = possibleCenters[i]
            val minModuleSize = fpi.estimatedModuleSize
            for (j in i + 1 until possibleCenters.size - 1) {
                val fpj = possibleCenters[j]
                val squares0 = squaredDistance(fpi, fpj)
                for (k in j + 1 until possibleCenters.size) {
                    val fpk = possibleCenters[k]
                    val maxModuleSize = fpk.estimatedModuleSize
                    if (maxModuleSize > minModuleSize * 1.4f) continue // 模块大小差太多
                    var a = squares0
                    var b = squaredDistance(fpj, fpk)
                    var c = squaredDistance(fpi, fpk)
                    // 排成升序（zxing 就在这儿内联了）
                    if (a < b) {
                        if (b > c) {
                            if (a < c) {
                                val temp = b
                                b = c
                                c = temp
                            } else {
                                val temp = a
                                a = c
                                c = b
                                b = temp
                            }
                        }
                    } else {
                        if (b < c) {
                            if (a < c) {
                                val temp = a
                                a = b
                                b = temp
                            } else {
                                val temp = a
                                a = b
                                b = c
                                c = temp
                            }
                        } else {
                            val temp = a
                            a = c
                            c = temp
                        }
                    }
                    // a² + b² = c²（勾股），且 a = b（等腰）。
                    // 任何直角三角形都满足 c² - b² - a² = 0，所以两条腰要各查一次。
                    // |c² - 2b²| + |c² - 2a²| 越大越不像等腰直角三角形。
                    // zxing 的注释写着：下面这个式子实测更好（虽然他们已经说不清为什么了）。
                    val d = kotlin.math.abs(c - 2 * b) + kotlin.math.abs(c - 2 * a)
                    if (d < distortion) {
                        distortion = d
                        bestPatterns[0] = fpi
                        bestPatterns[1] = fpj
                        bestPatterns[2] = fpk
                    }
                }
            }
        }
        if (distortion == Double.MAX_VALUE) throw QrNotFoundException()
        @Suppress("UNCHECKED_CAST")
        return bestPatterns as Array<QrResultPoint>
    }

    companion object {

        private const val CENTER_QUORUM = 2

        /** 一个模块 1 像素 × 中心 3 个模块宽 */
        private const val MIN_SKIP = 3

        /** 手机端支持到 20 版 */
        private const val MAX_MODULES = 97
    }
}

/**
 * 在校正图案该在的地方附近找 1:1:1 的中心 —— zxing 3.5.4 的 `AlignmentPatternFinder` 的
 * **逐字对照**（含「从中间往外一行一行试」与「没确认两次就把第一个候选交出去」那两条）。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `find` / `foundPatternCross` / `centerFromEnd` / `crossCheckVertical` / `handlePossibleCenter` | 同名 | 逐行相同 |
 * | `resultPointCallback` | **没搬** | 见 [QrResultPoint] 的对照表 |
 */
internal class QrAlignmentPatternFinder(
    private val image: QrBitMatrix,
    private val startX: Int,
    private val startY: Int,
    private val width: Int,
    private val height: Int,
    private val moduleSize: Float,
) {

    private val possibleCenters = mutableListOf<QrAlignmentPattern>()
    private val crossCheckStateCount = IntArray(3)

    fun find(): QrAlignmentPattern {
        val startX = startX
        val height = height
        val maxJ = startX + width
        val middleI = startY + (height / 2)
        // 我们在找黑/白/黑 的 1:1:1；这里数的是已经看到几段
        val stateCount = IntArray(3)
        for (iGen in 0 until height) {
            // 从中间往外搜
            val i = middleI + if ((iGen and 0x01) == 0) (iGen + 1) / 2 else -((iGen + 1) / 2)
            stateCount[0] = 0
            stateCount[1] = 0
            stateCount[2] = 0
            var j = startX
            // 先把开头那些白像素烧掉：要是从一段白中间开始，那个长度没有意义 ——
            // 我们不知道它是不是往左还接着
            while (j < maxJ && !image.get(j, i)) j++
            var currentState = 0
            while (j < maxJ) {
                if (image.get(j, i)) {
                    // 黑像素
                    if (currentState == 1) {
                        // 刚才在数黑
                        stateCount[1]++
                    } else {
                        // 刚才在数白
                        if (currentState == 2) {
                            // 中了？
                            if (foundPatternCross(stateCount)) {
                                val confirmed = handlePossibleCenter(stateCount, i, j)
                                if (confirmed != null) return confirmed
                            }
                            stateCount[0] = stateCount[2]
                            stateCount[1] = 1
                            stateCount[2] = 0
                            currentState = 1
                        } else {
                            stateCount[++currentState]++
                        }
                    }
                } else {
                    // 白像素
                    if (currentState == 1) currentState++
                    stateCount[currentState]++
                }
                j++
            }
            if (foundPatternCross(stateCount)) {
                val confirmed = handlePossibleCenter(stateCount, i, maxJ)
                if (confirmed != null) return confirmed
            }
        }
        // 嗯，没看到有哪个被确认过两次。真要是猜过，就把第一个交出去。
        if (possibleCenters.isNotEmpty()) return possibleCenters[0]
        throw QrNotFoundException()
    }

    private fun centerFromEnd(stateCount: IntArray, end: Int): Float =
        (end - stateCount[2]) - stateCount[1] / 2.0f

    private fun foundPatternCross(stateCount: IntArray): Boolean {
        val maxVariance = moduleSize / 2.0f
        for (i in 0 until 3) {
            if (kotlin.math.abs(moduleSize - stateCount[i]) >= maxVariance) return false
        }
        return true
    }

    private fun crossCheckVertical(startI: Int, centerJ: Int, maxCount: Int, originalStateCountTotal: Int): Float {
        val maxI = image.height
        val stateCount = crossCheckStateCount
        stateCount[0] = 0
        stateCount[1] = 0
        stateCount[2] = 0
        // 从中心往上数
        var i = startI
        while (i >= 0 && image.get(centerJ, i) && stateCount[1] <= maxCount) {
            stateCount[1]++
            i--
        }
        // 这一段已经太多模块、或者跑出画面了
        if (i < 0 || stateCount[1] > maxCount) return Float.NaN
        while (i >= 0 && !image.get(centerJ, i) && stateCount[0] <= maxCount) {
            stateCount[0]++
            i--
        }
        if (stateCount[0] > maxCount) return Float.NaN
        // 再从中心往下数
        i = startI + 1
        while (i < maxI && image.get(centerJ, i) && stateCount[1] <= maxCount) {
            stateCount[1]++
            i++
        }
        if (i == maxI || stateCount[1] > maxCount) return Float.NaN
        while (i < maxI && !image.get(centerJ, i) && stateCount[2] <= maxCount) {
            stateCount[2]++
            i++
        }
        if (stateCount[2] > maxCount) return Float.NaN
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2]
        if (5 * kotlin.math.abs(stateCountTotal - originalStateCountTotal) >= 2 * originalStateCountTotal) {
            return Float.NaN
        }
        return if (foundPatternCross(stateCount)) centerFromEnd(stateCount, i) else Float.NaN
    }

    private fun handlePossibleCenter(stateCount: IntArray, i: Int, j: Int): QrAlignmentPattern? {
        val stateCountTotal = stateCount[0] + stateCount[1] + stateCount[2]
        val centerJ = centerFromEnd(stateCount, j)
        val centerI = crossCheckVertical(i, centerJ.toInt(), 2 * stateCount[1], stateCountTotal)
        if (!centerI.isNaN()) {
            val estimatedModuleSize = (stateCount[0] + stateCount[1] + stateCount[2]) / 3.0f
            for (center in possibleCenters) {
                // 找位置与模块大小都差不多的那个
                if (center.aboutEquals(estimatedModuleSize, centerI, centerJ)) {
                    return center.combineEstimate(centerI, centerJ, estimatedModuleSize)
                }
            }
            // 没见过，记下来
            val point = QrAlignmentPattern(centerJ, centerI, estimatedModuleSize)
            possibleCenters.add(point)
        }
        return null
    }
}

/**
 * 定位 → 网格采样 —— zxing 3.5.4 的 `Detector` 的**逐字对照**。
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `detect(hints)`（`FinderPatternFinder.find` → `processFinderPatternInfo`） | [detect] | `hints` 只用到 `TRY_HARDER`，所以直接收一个布尔 |
 * | `processFinderPatternInfo`（算模块边长 → 算维度 → 临时版本 → 找校正图案「从半径 4 一路试到 16」→ 建变换 → 采样） | 同名 | 逐行相同 |
 * | `calculateModuleSize` / `calculateModuleSizeOneWay` / `sizeOfBlackWhiteBlackRunBothWays` / `sizeOfBlackWhiteBlackRun` | 同名 | 逐行相同，含那条「中点被算了两遍、减 1」的修正 |
 * | `createTransform` / `computeDimension` | 同名 | 逐行相同（`dimension & 0x03` 那个开关也照抄） |
 * | `findAlignmentInRegion`（含「区域比 3 个模块还窄就直接抛」两条检查） | 同名 | 逐行相同 |
 * | `DetectorResult`（`bits` + `points`） | **只交 [QrDetectorResult.bits]** | 这一条缝只交文本出去，`points` 只给 `Result` 的「结果点」用（见 [QrReader] 的 KDoc） |
 */
internal class QrDetector(private val image: QrBitMatrix) {

    fun detect(tryHarder: Boolean): QrDetectorResult {
        val finder = QrFinderPatternFinder(image)
        val info = finder.find(tryHarder)
        return processFinderPatternInfo(info)
    }

    private fun processFinderPatternInfo(info: QrFinderPatternInfo): QrDetectorResult {
        val topLeft = info.topLeft
        val topRight = info.topRight
        val bottomLeft = info.bottomLeft
        val moduleSize = calculateModuleSize(topLeft, topRight, bottomLeft)
        if (moduleSize < 1.0f) throw QrNotFoundException()
        val dimension = computeDimension(topLeft, topRight, bottomLeft, moduleSize)
        val provisionalVersion = QrVersion.getProvisionalVersionForDimension(dimension)
        val modulesBetweenFpCenters = provisionalVersion.dimensionForVersion - 7
        var alignmentPattern: QrAlignmentPattern? = null
        // 1 版以上都有校正图案
        if (provisionalVersion.alignmentPatternCenters.isNotEmpty()) {
            // 先估一下「右下那个定位图案」会在哪
            val bottomRightX = topRight.x - topLeft.x + bottomLeft.x
            val bottomRightY = topRight.y - topLeft.y + bottomLeft.y
            // 校正图案比那个位置往左上近 3 个模块
            val correctionToTopLeft = 1.0f - 3.0f / modulesBetweenFpCenters
            val estAlignmentX = (topLeft.x + correctionToTopLeft * (bottomRightX - topLeft.x)).toInt()
            val estAlignmentY = (topLeft.y + correctionToTopLeft * (bottomRightY - topLeft.y)).toInt()
            // 半径一路翻倍试，挺随意的，就是别太早放弃
            var i = 4
            while (i <= 16) {
                try {
                    alignmentPattern = findAlignmentInRegion(moduleSize, estAlignmentX, estAlignmentY, i.toFloat())
                    break
                } catch (_: QrNotFoundException) {
                    // 试下一轮
                }
                i = i shl 1
            }
            // 真找不到校正图案……那就没有它照样试试
        }
        val transform = createTransform(topLeft, topRight, bottomLeft, alignmentPattern, dimension)
        val bits = QrGridSampler.sampleGrid(image, dimension, dimension, transform)
        return QrDetectorResult(bits)
    }

    private fun createTransform(
        topLeft: QrResultPoint,
        topRight: QrResultPoint,
        bottomLeft: QrResultPoint,
        alignmentPattern: QrAlignmentPattern?,
        dimension: Int,
    ): QrPerspectiveTransform {
        val dimMinusThree = dimension - 3.5f
        val bottomRightX: Float
        val bottomRightY: Float
        val sourceBottomRightX: Float
        val sourceBottomRightY: Float
        if (alignmentPattern != null) {
            bottomRightX = alignmentPattern.x
            bottomRightY = alignmentPattern.y
            sourceBottomRightX = dimMinusThree - 3.0f
            sourceBottomRightY = sourceBottomRightX
        } else {
            // 没有校正图案，就把右下那个点编出来
            bottomRightX = (topRight.x - topLeft.x) + bottomLeft.x
            bottomRightY = (topRight.y - topLeft.y) + bottomLeft.y
            sourceBottomRightX = dimMinusThree
            sourceBottomRightY = dimMinusThree
        }
        return QrPerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f, 3.5f,
            dimMinusThree, 3.5f,
            sourceBottomRightX, sourceBottomRightY,
            3.5f, dimMinusThree,
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRightX, bottomRightY,
            bottomLeft.x, bottomLeft.y,
        )
    }

    private fun computeDimension(
        topLeft: QrResultPoint,
        topRight: QrResultPoint,
        bottomLeft: QrResultPoint,
        moduleSize: Float,
    ): Int {
        val tltrCentersDimension = QrMath.round(QrResultPoint.distance(topLeft, topRight) / moduleSize)
        val tlblCentersDimension = QrMath.round(QrResultPoint.distance(topLeft, bottomLeft) / moduleSize)
        var dimension = ((tltrCentersDimension + tlblCentersDimension) / 2) + 7
        when (dimension and 0x03) { // 取模 4
            0 -> dimension++
            // 1 的话什么都不用做
            2 -> dimension--
            3 -> dimension -= 2
        }
        return dimension
    }

    /** 两边各估一次再取平均。 */
    private fun calculateModuleSize(
        topLeft: QrResultPoint,
        topRight: QrResultPoint,
        bottomLeft: QrResultPoint,
    ): Float = (
        calculateModuleSizeOneWay(topLeft, topRight) +
            calculateModuleSizeOneWay(topLeft, bottomLeft)
        ) / 2.0f

    private fun calculateModuleSizeOneWay(pattern: QrResultPoint, otherPattern: QrResultPoint): Float {
        val moduleSizeEst1 = sizeOfBlackWhiteBlackRunBothWays(
            pattern.x.toInt(), pattern.y.toInt(),
            otherPattern.x.toInt(), otherPattern.y.toInt(),
        )
        val moduleSizeEst2 = sizeOfBlackWhiteBlackRunBothWays(
            otherPattern.x.toInt(), otherPattern.y.toInt(),
            pattern.x.toInt(), pattern.y.toInt(),
        )
        if (moduleSizeEst1.isNaN()) return moduleSizeEst2 / 7.0f
        if (moduleSizeEst2.isNaN()) return moduleSizeEst1 / 7.0f
        // 取平均，再除以 7：数的是 3 个黑模块宽，两边各有 1 白 1 黑 ⇒ 和除以 14
        return (moduleSizeEst1 + moduleSizeEst2) / 14.0f
    }

    private fun sizeOfBlackWhiteBlackRunBothWays(fromX: Int, fromY: Int, toX: Int, toY: Int): Float {
        var result = sizeOfBlackWhiteBlackRun(fromX, fromY, toX, toY)
        // 再往回数一遍 —— 当然别跑出画面
        var scale = 1.0f
        var otherToX = fromX - (toX - fromX)
        if (otherToX < 0) {
            scale = fromX / (fromX - otherToX).toFloat()
            otherToX = 0
        } else if (otherToX >= image.width) {
            scale = (image.width - 1 - fromX) / (otherToX - fromX).toFloat()
            otherToX = image.width - 1
        }
        var otherToY = (fromY - (toY - fromY) * scale).toInt()
        scale = 1.0f
        if (otherToY < 0) {
            scale = fromY / (fromY - otherToY).toFloat()
            otherToY = 0
        } else if (otherToY >= image.height) {
            scale = (image.height - 1 - fromY) / (otherToY - fromY).toFloat()
            otherToY = image.height - 1
        }
        otherToX = (fromX + (otherToX - fromX) * scale).toInt()
        result += sizeOfBlackWhiteBlackRun(fromX, fromY, otherToX, otherToY)
        // 中间的像素这样被算了两遍，减 1
        return result - 1.0f
    }

    /** Bresenham 的一个温和变体（见 zxing 里那条维基链接），量「黑-白-黑」那一段的长度。 */
    private fun sizeOfBlackWhiteBlackRun(fromXIn: Int, fromYIn: Int, toXIn: Int, toYIn: Int): Float {
        var fromX = fromXIn
        var fromY = fromYIn
        var toX = toXIn
        var toY = toYIn
        val steep = kotlin.math.abs(toY - fromY) > kotlin.math.abs(toX - fromX)
        if (steep) {
            var temp = fromX
            fromX = fromY
            fromY = temp
            temp = toX
            toX = toY
            toY = temp
        }
        val dx = kotlin.math.abs(toX - fromX)
        val dy = kotlin.math.abs(toY - fromY)
        var error = -dx / 2
        val xstep = if (fromX < toX) 1 else -1
        val ystep = if (fromY < toY) 1 else -1
        // 已在黑像素里，正找白 —— 第一次还是第二次
        var state = 0
        // 一直跑到 x == toX，但不越过
        val xLimit = toX + xstep
        var x = fromX
        var y = fromY
        while (x != xLimit) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y
            // 当前像素是不是意味着我们从白走到黑（或反过来）？
            // state 0、2 时找黑，state 1 时找白；颜色不对就进下一个状态，已经在 state 2 就结束
            if ((state == 1) == image.get(realX, realY)) {
                if (state == 2) return QrMath.distance(x, y, fromX, fromY)
                state++
            }
            error += dy
            if (error > 0) {
                if (y == toY) break
                y += ystep
                error -= dx
            }
            x += xstep
        }
        // 找到了「黑白黑」；就当画面外那个像素是白的，于是最后这一点 (toX+xstep,toY) 就是正确的终点。
        // 这是个很小的近似：也许 (toX+xstep,toY+ystep) 才对。不管它。
        if (state == 2) return QrMath.distance(toX + xstep, toY, fromX, fromY)
        // 连「黑白黑」都没找到，那真是估不出来了
        return Float.NaN
    }

    private fun findAlignmentInRegion(
        overallEstModuleSize: Float,
        estAlignmentX: Int,
        estAlignmentY: Int,
        allowanceFactor: Float,
    ): QrAlignmentPattern {
        // 在校正图案该在的地方附近找（它是 3 个模块大）
        val allowance = (allowanceFactor * overallEstModuleSize).toInt()
        val alignmentAreaLeftX = maxOf(0, estAlignmentX - allowance)
        val alignmentAreaRightX = minOf(image.width - 1, estAlignmentX + allowance)
        if (alignmentAreaRightX - alignmentAreaLeftX < overallEstModuleSize * 3) throw QrNotFoundException()
        val alignmentAreaTopY = maxOf(0, estAlignmentY - allowance)
        val alignmentAreaBottomY = minOf(image.height - 1, estAlignmentY + allowance)
        if (alignmentAreaBottomY - alignmentAreaTopY < overallEstModuleSize * 3) throw QrNotFoundException()
        val alignmentFinder = QrAlignmentPatternFinder(
            image,
            alignmentAreaLeftX,
            alignmentAreaTopY,
            alignmentAreaRightX - alignmentAreaLeftX,
            alignmentAreaBottomY - alignmentAreaTopY,
            overallEstModuleSize,
        )
        return alignmentFinder.find()
    }
}

/** 一次定位的结果（zxing 的 `DetectorResult` 只留 `bits`，见 [QrDetector] 的对照表）。 */
internal class QrDetectorResult(val bits: QrBitMatrix)
