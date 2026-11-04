package com.xjtu.toolbox.agent.bot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 引擎接入形状选择后的形变行为校验。
 *
 * 直接用 [BotEngine.shapeAtTime] 而不是 `sample()`：后者要构造
 * `android.graphics.Path`，在 JVM 单测里是 not-mocked。而形状是否「滑过去」纯粹是
 * 半径插值的事，正是这个函数在管，测它既准确又不依赖 Android 运行时。
 */
class BotEngineShapeTest {

    private val triangle = botShapeById("triangle")!!.radii
    private val droplet = botShapeById("goutte")!!.radii
    private val circle = botShapeById("cercle")!!.radii

    @Test
    fun `未设形状时返回 null`() {
        val engine = BotEngine()
        assertEquals(null, engine.shapeAtTime(0.0))
    }

    @Test
    fun `设了形状立即读得到终态`() {
        val engine = BotEngine()
        engine.setShape(triangle, 0.0)
        assertArrayEquals(triangle, engine.shapeAtTime(0.0)!!, 1e-9)
        // 形变结束后仍是终态
        assertArrayEquals(triangle, engine.shapeAtTime(99.0)!!, 1e-9)
    }

    @Test
    fun `形状在 morph 时长内滑过去而不是瞬跳`() {
        val engine = BotEngine()
        engine.setShape(circle, 0.0)
        engine.setShape(triangle, 1.0)
        val t0 = engine.shapeAtTime(1.0)!!       // 起点 = 圆
        val mid = engine.shapeAtTime(1.0 + BotEngine.SHAPE_MORPH / 2.0)!!
        val end = engine.shapeAtTime(1.0 + BotEngine.SHAPE_MORPH)!!

        assertArrayEquals("起点应是圆", circle, t0, 1e-9)
        assertArrayEquals("终点应是三角", triangle, end, 1e-9)

        // 中间帧应严格落在两端之间（曲线单调）
        var between = 0
        for (i in 0 until PROFILE_SAMPLES) {
            val lo = minOf(circle[i], triangle[i])
            val hi = maxOf(circle[i], triangle[i])
            if (mid[i] > lo + 1e-9 && mid[i] < hi - 1e-9) between++
        }
        assertTrue("中间帧应有大量分量落在两端之间，实得 $between", between > PROFILE_SAMPLES / 2)
    }

    @Test
    fun `形变是对时间可复放的（同一时刻两次采样一致）`() {
        val engine = BotEngine()
        engine.setShape(circle, 0.0)
        engine.setShape(droplet, 2.0)
        val at = 2.0 + BotEngine.SHAPE_MORPH * 0.3
        val a = engine.shapeAtTime(at)!!
        val b = engine.shapeAtTime(at)!!
        assertArrayEquals(a, b, 0.0)
    }

    @Test
    fun `回读形变结束前的时刻仍得到中间帧而非跳变`() {
        val engine = BotEngine()
        engine.setShape(circle, 0.0)
        engine.setShape(triangle, 1.0)
        // 先前进到形变之后
        engine.shapeAtTime(10.0)
        // 再回读形变中途：必须仍是插值，说明 shapePrev 没被清掉
        val mid = engine.shapeAtTime(1.0 + BotEngine.SHAPE_MORPH * 0.5)!!
        val isTriangle = mid.all { abs(it - triangle[0]) < 1e-9 }
        assertTrue("回读应得到中间帧，不该直接是三角", !isTriangle)
    }

    @Test
    fun `换形状的起点是上一形状而非当前插值结果`() {
        // 引擎只留一格历史：中途换形状时，起点换成被离开形状的完整姿态，
        // 与状态切换的语义一致。
        val engine = BotEngine()
        engine.setShape(circle, 0.0)
        engine.setShape(triangle, 1.0)
        engine.shapeAtTime(1.0 + BotEngine.SHAPE_MORPH * 0.5) // 处在中途
        engine.setShape(droplet, 2.0)
        // 新形变的起点应是三角（上一形状的终态）
        val start = engine.shapeAtTime(2.0)!!
        assertArrayEquals("中途换形状应以完整三角为起点", triangle, start, 1e-9)
    }

    @Test
    fun `设成同一个数组引用是 no-op`() {
        val engine = BotEngine()
        engine.setShape(triangle, 0.0)
        engine.setShape(triangle, 5.0)
        // 引用相同则不重置形变起点，时间轴上仍是「早就到位」
        assertArrayEquals(triangle, engine.shapeAtTime(5.0)!!, 1e-9)
    }

    @Test
    fun `目录里的形状彼此确有差异`() {
        assertNotEquals(0.0, distance(triangle, circle), 0.01)
        assertNotEquals(0.0, distance(droplet, circle), 0.01)
        assertNotEquals(0.0, distance(droplet, triangle), 0.01)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        (0 until PROFILE_SAMPLES).sumOf { abs(a[it] - b[it]) } / PROFILE_SAMPLES
}
