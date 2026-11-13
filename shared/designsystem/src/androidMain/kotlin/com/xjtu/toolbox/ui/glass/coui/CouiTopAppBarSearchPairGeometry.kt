package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/** 绘制、内容放置和命中共用同一帧几何，避免融合重叠区的输入错位。 */
internal data class CouiTopAppBarSearchPairGeometry(
    val inputBounds: Rect,
    val actionBounds: Rect,
    val actionScale: Float,
    val actionAlpha: Float,
)

internal fun topAppBarSearchPairGeometry(
    width: Float,
    height: Float,
    actionWidth: Float,
    gap: Float,
    progress: Float,
    rtl: Boolean,
): CouiTopAppBarSearchPairGeometry {
    val fraction = progress.coerceIn(0f, 1f)
    val inputWidth = (width - (actionWidth + gap) * fraction).roundToInt().coerceAtLeast(1).toFloat()
    // 第二主体从搜索圆内向逻辑末端分离；同一进度反向时沿原路径融合收回。
    val actionCenter = width - height * .5f + (height - actionWidth) * .5f * fraction
    val actionLeft = actionCenter - actionWidth * .5f
    val inputLeft = if (rtl) width - inputWidth else 0f
    val mirroredActionLeft = if (rtl) width - actionLeft - actionWidth else actionLeft
    return CouiTopAppBarSearchPairGeometry(
        inputBounds = Rect(inputLeft, 0f, inputLeft + inputWidth, height),
        actionBounds = Rect(mirroredActionLeft, 0f, mirroredActionLeft + actionWidth, height),
        actionScale = .5f + .5f * fraction,
        actionAlpha = (fraction / .2f).coerceIn(0f, 1f),
    )
}
