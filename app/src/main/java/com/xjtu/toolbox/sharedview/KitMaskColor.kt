package com.xjtu.toolbox.sharedview

import android.graphics.Bitmap
import kotlin.math.max

/**
 * 一镜到底「卡片外扩底板」的填充色，也就是 KIT 的 `sharedView.color`。
 *
 * 底板是比源元素大一圈的圆角矩形，动画打开 / 返回时会从卡片四周露出来，所以它要画成
 * **源元素自己的底色**：卡片取卡片背景色（当前配置下如 `#F5F6F7`），常用功能图标取图标
 * 那块浅色底（如红铃铛旁边的 `#FBE1DE`，每个图标各不相同）。取错成页面底色或卡片里的
 * 图标强调色都会露馅：前者在渐变背景上出白板，后者（不传时 ROM 自动取色）会挑中铃铛的红色。
 *
 * 做法：在源元素截图里贴着圆角矩形内边取一圈像素（避开中间的内容），去极值后求均值。
 * 圆角外透出来的页面底色、中间的图标/文字/图片都不参与，深色模式、半透明卡片也一样按
 * 点击那一刻的真实观感取，不用另开一套配置。
 *
 * 只给 `:app` 的 KIT 桥用，不往共享层带 Android 类型。
 */
internal object KitMaskColor {

    /** 采样网格边长上限：48×48 个点，够稳又够便宜。 */
    private const val GRID = 48

    /** 少于这么多个有效像素就不传色（交给 ROM 兜底），避免取到噪声。 */
    private const val MIN_SAMPLES = 8

    /** 从圆角边缘往里让开几个像素，避开描边和抗锯齿。 */
    private const val OUTER_INSET_PX = 2

    /** 取色环带的宽度（px）：卡片内边距、图标底都比这宽，不会采到内容。 */
    private const val BAND_WIDTH_PX = 6

    /** 两端各丢掉的比例：卡片投影、旁边元素只影响少数采样点，丢掉就稳。 */
    private const val TRIM = 0.2f

    /**
     * 取源元素自己的底色。
     *
     * @param cornerRadiusPx 源元素圆角（px，与传给 KIT 的 radius 同一个值）。
     * @return 不透明 ARGB；取不出来时返回 0（0 = 不写 `sharedView.color`）。
     */
    fun backgroundOf(bitmap: Bitmap, cornerRadiusPx: Float): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0
        return backgroundOf(width, height, cornerRadiusPx) { x, y -> bitmap.getPixel(x, y) }
    }

    /** [backgroundOf] 的纯函数版本：像素由 [pixelAt] 提供，便于单元测试。 */
    internal fun backgroundOf(
        width: Int,
        height: Int,
        cornerRadiusPx: Float,
        pixelAt: (Int, Int) -> Int,
    ): Int {
        if (width <= 0 || height <= 0) return 0
        val radius = cornerRadiusPx.coerceAtLeast(0f)
        val outerInset = OUTER_INSET_PX.toFloat()
        val innerInset = outerInset + BAND_WIDTH_PX
        val step = max(1, minOf(width, height) / GRID)
        val samples = IntArray((width / step + 1) * (height / step + 1))
        var count = 0
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                val inBand = insideRoundedRect(x, y, width, height, outerInset, radius) &&
                    !insideRoundedRect(x, y, width, height, innerInset, radius)
                if (inBand) {
                    val argb = pixelAt(x, y)
                    if ((argb ushr 24) and 0xFF >= 8) samples[count++] = argb
                }
                x += step
            }
            y += step
        }
        return trimmedMeanColorOf(samples, count)
    }

    /**
     * 去极值平均：按亮度排序后丢掉两端各 [TRIM] 的样本，再对剩下的逐通道求均值。
     *
     * 贴边那一圈偶尔混进描边、投影或相邻元素，丢掉两端极值就稳了。
     */
    internal fun trimmedMeanColorOf(samples: IntArray, count: Int = samples.size): Int {
        if (count < MIN_SAMPLES) return 0
        val order = (0 until count).sortedBy { luminance(samples[it]) }
        val from = (count * TRIM).toInt()
        val to = count - from
        if (to <= from) return 0
        var r = 0L
        var g = 0L
        var b = 0L
        for (i in from until to) {
            val argb = samples[order[i]]
            r += (argb ushr 16) and 0xFF
            g += (argb ushr 8) and 0xFF
            b += argb and 0xFF
        }
        val n = (to - from).toLong()
        return (0xFF shl 24) or (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (b / n).toInt()
    }

    /** 点在「外缩 [inset] 后的圆角矩形」内部吗；[radius] 是原矩形的圆角。 */
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
        // 圆角中心围成的内矩形：落在这里就是直边区域，直接算在里面。
        val p = x.toFloat()
        val q = y.toFloat()
        val cx = p.coerceIn(left + r, right - r)
        val cy = q.coerceIn(top + r, bottom - r)
        val dx = p - cx
        val dy = q - cy
        return dx * dx + dy * dy <= r * r
    }

    private fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 2126 + g * 7152 + b * 722) / 10000
    }
}
