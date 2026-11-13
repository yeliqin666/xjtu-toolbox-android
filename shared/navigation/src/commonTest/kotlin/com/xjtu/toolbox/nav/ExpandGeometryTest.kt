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
                if (step < 1000) assertTrue(current.height < full.height)
                previous = current
            }
        }
    }

    @Test
    fun growthOverlapsFlight() {
        val source = sources.first()
        // 飞到中央时窗口已经明显长大（和飞行重叠），但还没铺满
        val mid = ButtonZoom.window(source, full, ButtonZoom.FLIGHT_END)
        assertTrue(abs(mid.center.x - full.width / 2f) < 1f && abs(mid.center.y - full.height / 2f) < 1f)
        assertTrue(mid.width > source.width * 2f)
        assertTrue(mid.width < full.width * 0.8f)
    }

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
