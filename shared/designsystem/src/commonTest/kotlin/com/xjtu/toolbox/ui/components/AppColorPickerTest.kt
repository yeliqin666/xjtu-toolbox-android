package com.xjtu.toolbox.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AppColorPicker] 的纯算术部分：这两个函数决定「拖到哪儿就是哪个色」，也是 issue #311
 * 「滑动条滑动后颜色对不上」的根因所在（旧面板让 alpha 滑条分离了「看到的色」和「存下去的色」）。
 *
 * 这里不碰 Compose 的布局与手势，只钉住颜色换算本身——后者只能靠真机看。
 */
class AppColorPickerTest {

    /**
     * 按 8bit 通道比对，每通道容差 1。
     *
     * 不直接 `assertEquals(Color, Color)`：`Color` 存的是 Float，`Color.hsv` 与手写打折回来的
     * 两条路径浮点运算顺序不同，同一个 8bit 色也可能差最后一位，用 equals 会假报错。
     * 面板最终落盘走 `toArgb`，所以 8bit 一致就是真的同一个色。
     */
    private fun assertSameColor(expected: Color, actual: Color, message: String? = null) {
        val a = expected.toArgb()
        val b = actual.toArgb()
        val diffs = (0..24 step 8).map { shift ->
            abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
        }
        assertTrue(diffs.all { it <= 1 }, message ?: "${hex(a)} 与 ${hex(b)} 相差超过 1/255")
    }

    private fun hex(argb: Int): String =
        "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    @Test
    fun `产出的颜色一律不透明`() {
        // 方块四角与几条色相都过一遍：alpha 必须始终是 FF
        for (hue in listOf(0f, 30f, 60f, 120f, 200f, 260f, 300f, 359f)) {
            for (s in listOf(0f, 0.25f, 0.5f, 1f)) {
                for (v in listOf(0f, 0.25f, 0.5f, 1f)) {
                    val argb = opaqueHsv(hue, s, v).toArgb()
                    assertEquals(0xFF, (argb ushr 24) and 0xFF, "hue=$hue s=$s v=$v 的 alpha 不是 FF")
                }
            }
        }
    }

    @Test
    fun `方块四角对应白、纯色相、黑`() {
        // 左上 (s=0, v=1) 是白
        assertSameColor(Color.White, opaqueHsv(120f, 0f, 1f))
        // 右上 (s=1, v=1) 是该色相的纯色
        assertSameColor(Color(0xFF_FF0000), opaqueHsv(0f, 1f, 1f))
        assertSameColor(Color(0xFF_00FF00), opaqueHsv(120f, 1f, 1f))
        assertSameColor(Color(0xFF_0000FF), opaqueHsv(240f, 1f, 1f))
        // 下边整条 (v=0) 都是黑，不管饱和度多高
        assertSameColor(Color.Black, opaqueHsv(120f, 1f, 0f))
        assertSameColor(Color.Black, opaqueHsv(120f, 0f, 0f))
    }

    @Test
    fun `越界输入被夹住而不是抛异常`() {
        // 手势拖出方块时算出的比例可能越界；Color.hsv 对范围是 require，放任就会崩
        assertSameColor(Color(0xFF_FF0000), opaqueHsv(0f, 2f, 2f))
        assertSameColor(Color.Black, opaqueHsv(0f, -1f, -1f))
        // 色相绕一圈等价
        assertSameColor(opaqueHsv(0f, 1f, 1f), opaqueHsv(360f, 1f, 1f))
        assertSameColor(opaqueHsv(0f, 1f, 1f), opaqueHsv(-360f, 1f, 1f))
    }

    @Test
    fun `往返守恒：选定颜色折回三通道再还原还是同一个色`() {
        for (argb in listOf(
            0xFF_FF0000, 0xFF_00FF00, 0xFF_0000FF,
            0xFF_1565C0, 0xFF_808080, 0xFF_FFFFFF,
            0xFF_000000, 0xFF_2F7CFF, 0xFF_7E57C2,
        )) {
            val original = Color(argb)
            val (h, s, v) = original.hsvComponents()
            assertSameColor(original, opaqueHsv(h, s, v), "argb=${hex(argb.toInt())} 往返后变了")
        }
    }

    @Test
    fun `灰阶的色相按 0 处理且不产生 NaN`() {
        for (argb in listOf(0xFF_000000, 0xFF_FFFFFF, 0xFF_808080, 0xFF_404040)) {
            val (h, s, v) = Color(argb).hsvComponents()
            assertEquals(0f, h, "灰阶色相应为 0")
            // 纯黑 max=0 走的是「max<=0 则 0」这一支，其余灰阶是三通道相等导致 d=0，两条都该给 0
            assertEquals(0f, s, "灰阶饱和度应为 0")
            assertTrue(h.isFinite() && s.isFinite() && v.isFinite(), "出现 NaN/Inf")
        }
    }

    @Test
    fun `明度就是最大通道，饱和度为相对差`() {
        val (_, s, v) = Color(0xFF_1565C0).hsvComponents()
        // 0x15=21, 0x65=101, 0xC0=192 -> max=192/255, min=21/255
        assertEquals(192f / 255f, v, 1e-6f)
        assertEquals((192f - 21f) / 192f, s, 1e-6f)
    }
}
