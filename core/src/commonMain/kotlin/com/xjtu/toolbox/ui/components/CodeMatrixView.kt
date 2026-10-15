package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.platform.CodeMatrix

/** 把一个 [CodeMatrix] 画成码 —— 三端同一份，不需要任何平台的像素缓冲 API 。 */
@Composable
fun CodeMatrixView(
    matrix: CodeMatrix?,
    quietZoneModules: Int,
    modifier: Modifier,
) {
    if (matrix == null) return
    Canvas(modifier) {
        drawRect(color = Color.White, topLeft = Offset.Zero, size = size)
        val cols = matrix.width + 2 * quietZoneModules
        val rows = matrix.height + 2 * quietZoneModules
        val cellW = (size.width / cols).coerceAtLeast(1f)
        val cellH = (size.height / rows).coerceAtLeast(1f)
        val originX = (size.width - cellW * cols) / 2f
        val originY = (size.height - cellH * rows) / 2f
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(
                        originX + (x + quietZoneModules) * cellW,
                        originY + (y + quietZoneModules) * cellH,
                    ),
                    size = Size(cellW, cellH),
                )
            }
        }
    }
}
