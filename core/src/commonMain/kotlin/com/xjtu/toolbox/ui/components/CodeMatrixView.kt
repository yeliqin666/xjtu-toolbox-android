package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.platform.CodeMatrix

/**
 * 把一个 [CodeMatrix] 画成码 —— **三端同一份**，不需要任何平台的像素缓冲 API
 * （搬迁前是 `Bitmap` + `Image(asImageBitmap)` 那一套：Android 有，桌面要另写一份 skiko 的）。
 *
 * 两处刻意的对齐（见 [CodeMatrix] 的 KDoc）：
 *  - **整块白底**：码必须是深色模块落在白底上才扫得动，而卡片的底色是主题色（不是白）；
 *  - **静默区**：`quietZoneModules` 个模块的白边（二维码 1 个模块，与 `:app` 旧实现
 *    `QrBitmap` 的 `MARGIN=1` 同宽；条形码 0 —— 它的静默区已经编在条纹阵列里）。
 *
 * 格子按**整像素**取（`floor`）并居中：避免半个像素的格子把边缘糊成灰的（那种图扫得动，
 * 但没必要），末尾多出来的那一两个像素就是更大的白边。
 *
 * 原先它是 `PaymentCodeScreen` 里的一个私有函数，本轮搬到这里：匹配交友那一屏也要出二维码
 * （分享码本体），画法必须与付款码**逐像素同源**，不然两张码的白边与格子尺寸会不一样。
 */
@Composable
internal fun CodeMatrixView(
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
