package com.xjtu.toolbox.ui.glass

import kotlin.math.roundToInt

/**
 * 玻璃底栏拖动手势的纯计算部分，从 [GlassBottomTabs] 里抽出来方便写 JVM 单测。
 *
 * 拖动逻辑照抄 Kyant `LiquidBottomTabs.kt`：滑块的目标位置是一个连续的浮点「格数」
 * （比如 1.5f 表示停在第 1、2 格中间），手指拖动时按位移量累加，松手时四舍五入吸附到
 * 最近的一格。
 */
internal object GlassDragMath {

    /**
     * 手指横向拖动 [dragDeltaX] 像素后，滑块目标位置（浮点格数）应该变成多少。
     *
     * @param currentTarget 当前的目标格数（拖动前）
     * @param dragDeltaX 本次手势增量事件的横向位移，像素，RTL 下调用方需要自己翻转符号
     * @param tabWidthPx 单个 tab 的宽度（像素）；为 0 时说明还没测量出布局，原样返回
     * @param tabsCount tab 总数
     */
    fun dragTargetValue(
        currentTarget: Float,
        dragDeltaX: Float,
        tabWidthPx: Float,
        tabsCount: Int,
    ): Float {
        if (tabWidthPx <= 0f || tabsCount <= 0) return currentTarget
        val next = currentTarget + dragDeltaX / tabWidthPx
        return next.coerceIn(0f, (tabsCount - 1).toFloat())
    }

    /** 松手时，把浮点目标位置吸附到最近的一格下标，并夹在合法范围内。 */
    fun snappedIndex(targetValue: Float, tabsCount: Int): Int {
        if (tabsCount <= 0) return 0
        return targetValue.roundToInt().coerceIn(0, tabsCount - 1)
    }
}
