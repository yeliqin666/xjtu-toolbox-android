package com.xjtu.toolbox.game.xiangqi.rules

/** 棋盘坐标：x 从左到右 0..8，y 从上到下 0..9（黑方在 y=0 一侧）。 */
class Position(var x: Int, var y: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false
        return x == other.x && y == other.y
    }

    override fun hashCode(): Int = x * 31 + y

    override fun toString(): String = "($x, $y)"
}
