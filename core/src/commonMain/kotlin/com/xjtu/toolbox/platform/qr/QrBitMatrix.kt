package com.xjtu.toolbox.platform.qr

/**
 * 一个可改的位矩阵 —— zxing 3.5.4 `com.google.zxing.common.BitMatrix` 的**逐字对照**。
 *
 * ## 为什么不能复用 `CodeMatrix`
 *
 * `CodeMatrix` 是**只读**的（一个 `BooleanArray` + 一个 `get`）：它是「编出来的码长什么样」那一侧的口径，
 * 渲染方只读。解码这一侧要的是别名物：二值化要往里**写**、`DataMask.unmaskBitMatrix` 要 `flip`、
 * `BitMatrixParser.mirror()` 要在原地交换、`Detector` 的网格采样是一格一格 `set` 出来的。所以这一份
 * 单独存在，`CodeMatrix` 一个字未改。
 *
 * ## 与 zxing 那一份的对照表（只列搬过来的那些方法）
 *
 * | zxing | 这里 | 备注 |
 * |---|---|---|
 * | `BitMatrix(int width, int height)` | `QrBitMatrix(width, height)` | 同样的 `rowSize = (width + 31) / 32` 与 `int[]` 打包 |
 * | `get/set/unset/flip(x, y)` | 同名 | 位运算逐条相同 |
 * | `flip()`（全取反）、`setRegion` | 同名 | `setRegion` 是 `Version.buildFunctionPattern` 用的 |
 * | `getWidth/getHeight/getRowSize` | `width/height/rowSize` | |
 * | `parse(boolean[][])`、`parse(String, …)`、`toString`、`equals/hashCode`、`getRow`、`getTopLeftOnBit`、`getBottomRightOnBit`、`getEnclosingRectangle`、`rotate180`、`xor` | **没搬** | 这一条解码链一个都不调：`parse` 那两条只给测试与「纯条码」分支用，`getRow`/`getTopLeftOnBit`/`getBottomRightOnBit` 只给 `QRCodeReader.extractPureBits`（`PURE_BARCODE` 那一支，本端不传那个 hint），`rotate180`/`getEnclosingRectangle` 只给 1D 与调试用 |
 *
 * ⚠️ 与 zxing 一样，`get/set/unset/flip` **不做边界检查**（越界会抛下标异常，或悄悄写到邻行）—— 调用方
 * 保证坐标在界内：`FinderPatternFinder` / `AlignmentPatternFinder` 那一圈 `while` 都带着 `>= 0` 与
 * `< maxI` 的条件，`GridSampler` 那一条有 `checkAndNudgePoints` 兜底。
 */
internal class QrBitMatrix(val width: Int, val height: Int) {

    private val rowSize: Int = (width + 31) / 32
    private val bits: IntArray = IntArray(rowSize * height)

    init {
        require(width >= 1 && height >= 1) { "两边都得大于 0：${width}x$height" }
    }

    fun get(x: Int, y: Int): Boolean = ((bits[y * rowSize + (x / 32)] ushr (x and 0x1f)) and 1) != 0

    fun set(x: Int, y: Int) {
        bits[y * rowSize + (x / 32)] = bits[y * rowSize + (x / 32)] or (1 shl (x and 0x1f))
    }

    fun unset(x: Int, y: Int) {
        bits[y * rowSize + (x / 32)] = bits[y * rowSize + (x / 32)] and (1 shl (x and 0x1f)).inv()
    }

    fun flip(x: Int, y: Int) {
        bits[y * rowSize + (x / 32)] = bits[y * rowSize + (x / 32)] xor (1 shl (x and 0x1f))
    }

    /** 全取反（zxing 的 `flip()`：`Detector` 那一侧没用，`BitMatrixParser.mirror()` 用的是两参数那个）。 */
    fun flip() {
        for (i in bits.indices) bits[i] = bits[i].inv()
    }

    fun setRegion(left: Int, top: Int, width: Int, height: Int) {
        require(!(top < 0 || left < 0)) { "left 与 top 都不能是负数" }
        require(!(height < 1 || width < 1)) { "宽高至少是 1" }
        val right = left + width
        val bottom = top + height
        require(!(bottom > this.height || right > this.width)) { "这一块放不进矩阵里" }
        for (y in top until bottom) {
            val offset = y * rowSize
            for (x in left until right) {
                bits[offset + (x / 32)] = bits[offset + (x / 32)] or (1 shl (x and 0x1f))
            }
        }
    }
}
