package com.xjtu.toolbox.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片里一行文字占多高。
 *
 * 格子是 dp、文字是 sp：一格见方的边长由屏宽除四得来（手机上 ~76dp），系统字号调到 1.25x 以上，
 * 13sp 的行就要 16.25dp，而明细行高、图标槽一直按固定 dp 排，字就被卡片边缘切掉（#622）。
 * 所以凡是「先按文字定高、再按高排内容」的地方都从这儿取高度，不再写死 dp。
 *
 * 1x 下 [row] 与原先写死的常量完全相等（13sp + 3dp = 16dp、15sp + 5dp = 20dp），
 * 所以正常字号下的排版一个像素都不变。
 */
internal object HomeTileType {

    /** 一行文字的高度：行高 sp × 系统字号倍率（1x 时 13sp 就是 13dp，1.5x 时是 19.5dp）。 */
    fun line(lineHeightSp: Float, fontScale: Float): Dp = (lineHeightSp * fontScale).dp

    /** 一行明细的高度：文字行高加固定留白，随字号一起长。 */
    fun row(lineHeightSp: Float, paddingDp: Float, fontScale: Float): Dp =
        line(lineHeightSp, fontScale) + paddingDp.dp
}
