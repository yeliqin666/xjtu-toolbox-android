package com.xjtu.toolbox.game.xiangqi.rules

/**
 * 棋盘坐标：x 从左到右 0..8，y 从上到下 0..9（黑方在 y=0 一侧）。
 *
 * 从 :app 的 `rules/Position.java` 搬过来。原类只重写了 `equals`、没重写 `hashCode`
 * （JVM 上是身份哈希）—— 这里补上按值的 `hashCode`：任何依赖「身份哈希 + 值相等」的
 * 用法本来就是错的，补上只会让集合查找按预期工作，不会改掉任何一条现有判据。
 * `toString()` 逐字保持 `"(x, y)"` 的形状（`Move.toString()` 会把它带出去）。
 */
class Position(var x: Int, var y: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false
        return x == other.x && y == other.y
    }

    override fun hashCode(): Int = x * 31 + y

    override fun toString(): String = "($x, $y)"
}
