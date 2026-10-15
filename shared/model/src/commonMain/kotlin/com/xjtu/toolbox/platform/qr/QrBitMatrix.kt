package com.xjtu.toolbox.platform.qr

/** 一个可改的位矩阵 —— zxing 3.5.4 `com.google.zxing.common.BitMatrix` 的移植。 */
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
