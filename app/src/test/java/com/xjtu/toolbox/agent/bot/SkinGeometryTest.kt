package com.xjtu.toolbox.agent.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SkinGeometryTest {
    @Test
    fun `直线与曲线都被升成三次段`() {
        val outline = SkinPathParser.parse("M 0 0 L 1 0 C 1 1 0 1 0 0 Z")
        val sub = outline.subpaths.single()
        assertTrue(sub.closed)
        assertEquals(2, sub.segments)
        // L 1 0 的控制点落在线段的三分点上
        assertEquals(1.0 / 3.0, sub.pts[2], 1e-12)
        assertEquals(0.0, sub.pts[3], 1e-12)
    }

    @Test
    fun `相对指令与隐式重复参数按 SVG 语义解析`() {
        val absolute = SkinPathParser.parse("M 0 0 L 1 0 L 1 1")
        val relative = SkinPathParser.parse("m 0 0 l 1 0 0 1")
        assertEquals(absolute.signature, relative.signature)
        absolute.subpaths.single().pts.forEachIndexed { i, v ->
            assertEquals(v, relative.subpaths.single().pts[i], 1e-12)
        }
    }

    @Test
    fun `圆环用两条子路径加 evenodd 表达，不再被填平`() {
        val ring = SkinPathParser.parse(
            "M -1 0 A 1 1 0 1 0 1 0 A 1 1 0 1 0 -1 0 Z M -0.5 0 A 0.5 0.5 0 1 0 0.5 0 A 0.5 0.5 0 1 0 -0.5 0 Z"
        )
        assertEquals(2, ring.subpaths.size)
        val outer = ring.subpaths[0]
        // 弧线终点应当准确落回起点
        assertEquals(-1.0, outer.pts[outer.pts.size - 2], 1e-9)
        assertEquals(0.0, outer.pts[outer.pts.size - 1], 1e-9)
    }

    @Test
    fun `弧线采样点落在圆上`() {
        val arc = SkinPathParser.parse("M 1 0 A 1 1 0 0 1 -1 0")
        arc.subpaths.single().pts.let { pts ->
            var i = 0
            while (i < pts.size) {
                // 控制点会略微超出圆，端点必须正好在圆上
                if (i == 0 || i == pts.size - 2) assertEquals(1.0, hypot(pts[i], pts[i + 1]), 1e-9)
                i += 2
            }
        }
    }

    @Test
    fun `结构相同才逐点插值，不同则交给调用方淡入淡出`() {
        val a = SkinPathParser.parse("M 0 0 L 2 0 L 2 2 Z")
        val b = SkinPathParser.parse("M 0 0 L 4 0 L 4 4 Z")
        val mid = lerpOutline(a, b, 0.5)
        assertNotNull(mid)
        assertEquals(3.0, mid!!.subpaths.single().pts[8], 1e-12)
        assertNull(lerpOutline(a, SkinPathParser.parse("M 0 0 L 1 1"), 0.5))
    }

    @Test
    fun `径向轮廓换成三次曲线后，先插值半径与先取曲线结果一致`() {
        val low = DoubleArray(8) { 1.0 }
        val high = DoubleArray(8) { if (it % 2 == 0) 1.4 else 0.6 }
        val direct = outlineFromRadii(DoubleArray(8) { (low[it] + high[it]) / 2.0 })
        val blended = lerpOutline(outlineFromRadii(low), outlineFromRadii(high), 0.5)
        assertNotNull(blended)
        direct.subpaths.single().pts.forEachIndexed { i, v ->
            assertEquals(v, blended!!.subpaths.single().pts[i], 1e-12)
        }
    }

    @Test
    fun `v1 姿态是先旋转再在屏幕轴压扁`() {
        val m = SkinTransform.ofRadialV1(cx = 0.0, cy = 0.0, sx = 2.0, sy = 1.0, rot = Math.PI / 2)
        // (1,0) 旋转 90° 到 (0,1)，再按屏幕轴压扁只影响 y
        assertEquals(0.0, m.a * 1.0 + m.c * 0.0 + m.e, 1e-12)
        assertEquals(1.0, m.b * 1.0 + m.d * 0.0 + m.f, 1e-12)
    }

    @Test
    fun `pivot 决定旋转中心`() {
        val m = SkinTransform.of(cx = 0.0, cy = 0.0, sx = 1.0, sy = 1.0, rot = Math.PI, pivotX = 1.0, pivotY = 0.0)
        val x = m.a * 0.0 + m.c * 0.0 + m.e
        val y = m.b * 0.0 + m.d * 0.0 + m.f
        assertEquals(2.0, x, 1e-12)
        assertEquals(0.0, y, 1e-12)
    }

    @Test
    fun `不支持的指令直接报错而不是画错`() {
        val thrown = runCatching { SkinPathParser.parse("M 0 0 B 1 1") }.exceptionOrNull()
        assertTrue(thrown is SkinPathSyntaxException)
        assertTrue(runCatching { SkinPathParser.parse("L 1 1") }.exceptionOrNull() is SkinPathSyntaxException)
    }

    @Test
    fun `折线保持开口，多边形自动闭合`() {
        val open = outlineFromPoints(listOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0), closed = false)
        val closed = outlineFromPoints(listOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0), closed = true)
        assertTrue(!open.subpaths.single().closed)
        assertTrue(closed.subpaths.single().closed)
        assertEquals(2, open.subpaths.single().segments)
        assertEquals(3, closed.subpaths.single().segments)
    }

    @Test
    fun `椭圆包围盒等于半轴`() {
        val b = outlineFromEllipse(0.0, 0.0, 2.0, 1.0).bounds()
        assertTrue(abs(b[0] + 2.0) < 1e-9 && abs(b[2] - 2.0) < 1e-9)
        assertTrue(abs(b[1] + 1.0) < 1e-9 && abs(b[3] - 1.0) < 1e-9)
    }
}
