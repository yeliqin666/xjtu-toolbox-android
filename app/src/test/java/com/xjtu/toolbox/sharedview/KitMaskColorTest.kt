package com.xjtu.toolbox.sharedview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底板填充色（`sharedView.color`）的取色规则：取源元素自己的底色——
 * 卡片取卡片背景色（如 `#F5F6F7`），常用功能图标取图标那块浅色底（如 `#FBE1DE`）。
 */
class KitMaskColorTest {

    private val pageBg = 0xFFE9EAF7.toInt() // 卡片圆角外透出来的页面底色，不该被采到
    private val cardBg = 0xFFF5F6F7.toInt()
    private val iconBg = 0xFFFBE1DE.toInt()
    private val bellRed = 0xFFE5483F.toInt()
    private val textDark = 0xFF1A1A1A.toInt()
    private val transparent = 0x00000000

    /** 造一张源元素截图：圆角矩形内是元素底色，中间是内容，圆角外是页面底色。 */
    private fun element(
        width: Int,
        height: Int,
        radius: Float,
        fill: Int,
        center: IntRange? = null,
        centerColor: Int = textDark,
    ): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        when {
            !insideRoundedRect(x, y, width, height, 0f, radius) -> pageBg
            center != null && x in center && y in center -> centerColor
            else -> fill
        }
    }

    private fun colorOf(pixels: IntArray, width: Int, height: Int, radius: Float): Int =
        KitMaskColor.backgroundOf(width, height, radius) { x, y -> pixels[y * width + x] }

    @Test
    fun `卡片取卡片自己的背景色，不取内容也不取圆角外的页面底色`() {
        val pixels = element(120, 120, radius = 30f, fill = cardBg, center = 40..79)
        assertEquals(cardBg, colorOf(pixels, 120, 120, 30f))
    }

    @Test
    fun `常用功能图标取图标那块浅色底，不取字形或页面底色`() {
        // 图标是圆角方块：中间是红色铃铛，四周是浅粉底。
        val pixels = element(90, 90, radius = 28f, fill = iconBg, center = 30..59, centerColor = bellRed)
        assertEquals(iconBg, colorOf(pixels, 90, 90, 28f))
    }

    @Test
    fun `深色模式取深色底`() {
        val darkBg = 0xFF242424.toInt()
        val pixels = element(120, 120, radius = 30f, fill = darkBg, center = 40..79)
        assertEquals(darkBg, colorOf(pixels, 120, 120, 30f))
    }

    @Test
    fun `描边、投影之类的少数极值会被去极值平均挡掉`() {
        val width = 120
        val height = 120
        val pixels = element(width, height, radius = 30f, fill = cardBg, center = 40..79)
        // 在环带能采到的直边上抹一条深色描边（少量极值，采样点在奇数行/列）。
        for (x in 11 until 110 step 2) {
            pixels[3 * width + x] = 0xFF666666.toInt()
            pixels[(height - 5) * width + x] = 0xFF666666.toInt()
        }
        assertEquals(cardBg, colorOf(pixels, width, height, 30f))
    }

    @Test
    fun `全透明像素跳过，取不到则返回 0`() {
        val empty = IntArray(100) { transparent }
        assertEquals(0, colorOf(empty, 10, 10, 0f))
    }

    @Test
    fun `元素太小、环形采样带不存在时返回 0，交给 ROM 兜底`() {
        assertEquals(0, colorOf(IntArray(16) { cardBg }, 4, 4, 0f))
    }

    @Test
    fun `去极值平均：两端各扔两成后逐通道求均值且结果不透明`() {
        val samples = IntArray(5) { 0x80101010.toInt() } + IntArray(5) { 0x80F0F0F0.toInt() }
        val color = KitMaskColor.trimmedMeanColorOf(samples)
        assertEquals(0xFF, (color ushr 24) and 0xFF)
        assertEquals(0x80, (color ushr 16) and 0xFF)
        assertEquals(0x80, (color ushr 8) and 0xFF)
        assertEquals(0x80, color and 0xFF)
    }

    /** 和 KitMaskColor.insideRoundedRect 同一条规则（测试里独立实现，避免自证）。 */
    private fun insideRoundedRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        inset: Float,
        radius: Float,
    ): Boolean {
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        if (x < left || x >= right || y < top || y >= bottom) return false
        val r = (radius - inset).coerceAtLeast(0f)
        if (r <= 0f) return true
        val cx = x.toFloat().coerceIn(left + r, right - r)
        val cy = y.toFloat().coerceIn(top + r, bottom - r)
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }
}
