package com.xjtu.toolbox.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassDragMathTest {

    @Test
    fun `拖动距离换算成格数并夹在合法范围内`() {
        // 5 格，单格 100px：往右拖 250px，从第 0 格应该走到 2.5 格
        assertEquals(2.5f, GlassDragMath.dragTargetValue(0f, 250f, 100f, 5), 0.0001f)
        // 拖过头也要夹住，不能超过最后一格
        assertEquals(4f, GlassDragMath.dragTargetValue(0f, 10000f, 100f, 5), 0.0001f)
        // 反方向拖到负的也要夹在 0
        assertEquals(0f, GlassDragMath.dragTargetValue(0f, -10000f, 100f, 5), 0.0001f)
    }

    @Test
    fun `还没测出布局宽度时原样返回，不产生 NaN 或除零`() {
        assertEquals(1.5f, GlassDragMath.dragTargetValue(1.5f, 50f, 0f, 5), 0.0001f)
    }

    @Test
    fun `松手时吸附到最近的整数格`() {
        assertEquals(3, GlassDragMath.snappedIndex(2.6f, 5))
        assertEquals(2, GlassDragMath.snappedIndex(2.4f, 5))
        assertEquals(3, GlassDragMath.snappedIndex(2.5f, 5)) // Math.round 对 .5 是向上取整
    }

    @Test
    fun `吸附结果不会越界`() {
        assertEquals(0, GlassDragMath.snappedIndex(-3f, 5))
        assertEquals(4, GlassDragMath.snappedIndex(99f, 5))
    }
}
