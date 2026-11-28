package com.xjtu.toolbox.home

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 卡片里文字高度的换算规则（#622）。
 *
 * 关键就两条：1x 下必须和原来写死的 dp 一模一样，之后每放大一档都要跟着长——
 * 行高一旦比字矮，卡面就会把字切掉。
 */
class HomeTileTypeTest {

    @Test
    fun 默认字号下行高与原先写死的常量一致() {
        assertEquals(16.dp, HomeTileType.row(13f, 3f, 1f))
        assertEquals(20.dp, HomeTileType.row(15f, 5f, 1f))
        // 一条两行的明细（正文 16sp + 说明 14sp）在 1x 下正好是原来的 40dp
        assertEquals(40.dp, HomeTileType.line(16f, 1f) + HomeTileType.line(14f, 1f) + 10.dp)
        // 不画说明时是原来的 26dp
        assertEquals(26.dp, HomeTileType.row(16f, 10f, 1f))
    }

    @Test
    fun 行高跟着系统字号一起长() {
        assertEquals(19.25.dp, HomeTileType.row(13f, 3f, 1.25f))
        assertEquals(22.5.dp, HomeTileType.row(13f, 3f, 1.5f))
        assertEquals(29.dp, HomeTileType.row(13f, 3f, 2f))
    }

    @Test
    fun 任何字号下行高都高于一行文字() {
        // 行高小于文字本身就会被卡片边缘切掉，这正是 #622 的来由
        for (scale in listOf(1f, 1.15f, 1.25f, 1.3f, 1.5f, 2f)) {
            assertTrue(
                HomeTileType.row(13f, 3f, scale) > HomeTileType.line(13f, scale),
                "fontScale=$scale 时行高没有容下一行字",
            )
            assertTrue(
                HomeTileType.row(15f, 5f, scale) > HomeTileType.line(15f, scale),
                "fontScale=$scale 时行高没有容下一行字",
            )
        }
    }
}
