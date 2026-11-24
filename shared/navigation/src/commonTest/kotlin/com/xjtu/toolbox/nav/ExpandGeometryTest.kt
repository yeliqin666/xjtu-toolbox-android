package com.xjtu.toolbox.nav

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpandGeometryTest {
    private val full = Size(1080f, 2400f)
    private val sources = listOf(
        Rect(32f, 240f, 252f, 460f),
        Rect(720f, 1700f, 1040f, 2160f),
        Rect(48f, 800f, 1032f, 1040f),
    )

    @Test
    fun endpointsAndImmediateExpansion() {
        sources.forEach { source ->
            assertEquals(source, ButtonZoom.window(source, full, 0f))
            assertEquals(Rect(0f, 0f, full.width, full.height), ButtonZoom.window(source, full, 1f))
            val origin = ExpandOrigins.Origin(source, 24f)
            assertEquals(ButtonZoom.window(source, full, 0.5f), ExpandOrigins.windowOf(origin, 0.5f, full).bounds)
            assertTrue(ExpandOrigins.windowOf(origin, 0.01f, full).bounds.width > source.width)
        }
    }

    @Test
    fun growthAndTravelStayMonotonic() {
        sources.forEach { source ->
            var previous = source
            for (step in 1..1000) {
                val current = ButtonZoom.window(source, full, step / 1000f)
                assertTrue(current.width >= previous.width - 0.001f)
                assertTrue(current.height >= previous.height - 0.001f)
                assertTrue(current.width <= full.width + 0.001f)
                assertTrue(current.height <= full.height + 0.001f)
                assertTrue(abs(current.center.x - full.width / 2f) <= abs(previous.center.x - full.width / 2f) + 0.001f)
                assertTrue(abs(current.center.y - full.height / 2f) <= abs(previous.center.y - full.height / 2f) + 0.001f)
                // 放大收尾是软着陆，最后几帧的差值低于 float 精度，只查 95% 之前
                if (step < 950) assertTrue(current.height < full.height)
                previous = current
            }
        }
    }

    @Test
    fun growthOverlapsFlight() {
        val source = sources.first()
        // 飞到中央时放大已过大半，但还没铺满
        val mid = ButtonZoom.window(source, full, ButtonZoom.FLIGHT_END)
        assertTrue(abs(mid.center.x - full.width / 2f) < 1f && abs(mid.center.y - full.height / 2f) < 1f)
        assertTrue(mid.width > lerpWidth(source, 0.75f))
        assertTrue(mid.width < full.width * 0.95f)
    }

    @Test
    fun growthIsFrontLoaded() {
        // 前四分之一进度就完成三成以上的放大，不把放大压到后半程
        sources.forEach { source ->
            assertTrue(ButtonZoom.window(source, full, 0.25f).width >= lerpWidth(source, 0.3f))
        }
    }

    @Test
    fun crossfadeFollowsWindowSize() {
        // 面板与内容的交叉跟着窗口大小走：窗口还大时面板不能盖上来，窗口还小时内容不能露出来
        val source = sources.first()
        for (step in 0..1000) {
            val u = step / 1000f
            val grown = (ButtonZoom.window(source, full, u).width - source.width) / (full.width - source.width)
            if (grown > 0.46f) assertEquals(0f, ButtonZoom.coverAlpha(u), 0.001f)
            if (grown < 0.08f) assertEquals(0f, ButtonZoom.contentAlpha(u), 0.001f)
        }
    }

    @Test
    fun cardHandoffFollowsWindowSize() {
        // 卡片起点：页面与卡片快照的交接按窗口大小定时，返回时窗口还大就不能换成卡片
        val source = sources.first()
        for (step in 0..1000) {
            val u = step / 1000f
            val grown = (ButtonZoom.window(source, full, u).width - source.width) / (full.width - source.width)
            if (grown > 0.9f) assertEquals(1f, ExpandOrigins.pageContentAlpha(u, popping = true), 0.001f)
            if (grown < 0.42f) assertEquals(0f, ExpandOrigins.pageContentAlpha(u, popping = true), 0.001f)
            if (grown > 0.55f) assertEquals(1f, ExpandOrigins.pageContentAlpha(u), 0.001f)
            if (grown < 0.14f) assertEquals(0f, ExpandOrigins.pageContentAlpha(u), 0.001f)
        }
    }

    private fun lerpWidth(source: Rect, fraction: Float) = source.width + (full.width - source.width) * fraction

    @Test
    fun noVelocityBreakAtPhaseBoundaries() {
        val source = sources.first()
        val delta = 0.001f
        for (phase in listOf(ButtonZoom.GROW_START_H, ButtonZoom.GROW_START, ButtonZoom.FLIGHT_END)) {
            val before = ButtonZoom.window(source, full, phase - delta)
            val middle = ButtonZoom.window(source, full, phase)
            val after = ButtonZoom.window(source, full, phase + delta)
            assertTrue(abs((after.width - middle.width) - (middle.width - before.width)) / (delta * full.width) < 0.02f)
            assertTrue(abs((after.height - middle.height) - (middle.height - before.height)) / (delta * full.height) < 0.02f)
            assertTrue(abs((after.center.y - middle.center.y) - (middle.center.y - before.center.y)) / (delta * full.height) < 0.02f)
        }
    }
}
