package com.xjtu.toolbox.venue

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 滑块验证码用的**程序生成夹具图**（`:testkit`，不是任何真人截图）。
 *
 * ## 图长什么样
 *
 * 背景 260x160：平灰（200），左上偏上有一个「缺口」—— 一块 32x32 的深灰（120）区域，
 * 里面再放两个更暗（60）的小块（12x12 方 + 12x6 横条）作为**内部纹理**。滑块 50x50：
 * 透明边 9px，不透明内容与缺口内容逐像素一致。于是「把滑块拖到缺口」= 纹理完全重合。
 *
 * ## 为什么背景图也发 PNG（而不是真站点用的 JPEG）
 *
 * 识别器只吃解码后的字节（`BitmapFactory`/`ImageIO` 都是按文件头嗅探格式），
 * 传输格式不改语义。夹具用 **lossless 的 PNG** 是为了让下面的期望值是**精确**的
 * （JPEG 是有损的，会把边缘磨糊，期望值就得按实际编码再跑一遍才拿得到）。
 *
 * ## 期望值从哪来（先钉住再搬的口径）
 *
 * 搬迁前先在独立的 numpy/纯 Python 复刻里按 `VenueCaptchaSolver` 的算法（alpha 轮廓 →
 * 二值 Sobel → NCC）在这两张图上跑过一遍，数值与下列坐标一致；`:data` 的
 * `VenueCaptchaSolverJvmTest` 把它们钉成断言。关键几何：
 *
 *  - 滑块 alpha bbox 是 `[9,41)`² ⇒ `left = 9`；
 *  - 缺口左边缘在背景 x = [GAP_X] = 150；
 *  - 识别器的 `bestX` = 模板左边缘对齐处 = 150，`moveX = bestX - left = 141`，260 坐标系
 *    下 `targetX` 就是 141（背景宽正好 260）。
 */
object VenueCaptchaFixture {

    // ── 几何（与 `VenueFakeUpstream` 的验证码字段对齐：260x160 / 50x50）──

    const val BG_WIDTH = 260
    const val BG_HEIGHT = 160
    const val SLIDER_SIZE = 50

    /** 缺口边长（= 滑块不透明内容边长）。 */
    const val HOLE_SIZE = 32

    /** 滑块透明边宽度（⇒ alpha bbox 的 left/top = [MARGIN]）。 */
    const val MARGIN = 9

    /** 缺口左边缘在背景里的 x。 */
    const val GAP_X = 150

    /** 识别器推导的提交位移（260 坐标系）：`GAP_X - MARGIN`。 */
    const val EXPECTED_SOLVE_TARGET_X = 141

    /** 允许的拖动误差（±3，260 坐标系）—— 夹具判定「拖对了」用。 */
    const val BOOKING_X_TOLERANCE = 3

    private const val FLAT = 200
    private const val HOLE = 120
    private const val DARK = 60

    /** 缺口/滑块内容里的小纹理：洞内相对坐标 (lx, ly) 是否该画成 [DARK]。 */
    private fun patternAt(lx: Int, ly: Int): Boolean =
        (lx in 10 until 22 && ly in 10 until 22) || (lx in 10 until 22 && ly in 2 until 8)

    private fun buildBackground(): BufferedImage {
        val img = BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until BG_HEIGHT) for (x in 0 until BG_WIDTH) img.setRGB(x, y, rgb(FLAT))
        for (ly in 0 until HOLE_SIZE) for (lx in 0 until HOLE_SIZE) {
            val color = if (patternAt(lx, ly)) DARK else HOLE
            img.setRGB(GAP_X + lx, MARGIN + ly, rgb(color))
        }
        return img
    }

    private fun buildSlider(): BufferedImage {
        // ARGB：透明边 + 不透明内容（alpha 255）
        val img = BufferedImage(SLIDER_SIZE, SLIDER_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (ly in 0 until HOLE_SIZE) for (lx in 0 until HOLE_SIZE) {
            val color = if (patternAt(lx, ly)) DARK else HOLE
            img.setRGB(MARGIN + lx, MARGIN + ly, argb(255, color))
        }
        return img
    }

    private fun encodePng(build: () -> BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(build(), "png", out)
        return out.toByteArray()
    }

    /** 背景图 data URI（`CaptchaData.backgroundImage` 的形状）。 */
    fun backgroundDataUri(): String =
        "data:image/png;base64," + Base64.getEncoder().encodeToString(encodePng(::buildBackground))

    /** 滑块图 data URI（`CaptchaData.sliderImage` 的形状）。 */
    fun sliderDataUri(): String =
        "data:image/png;base64," + Base64.getEncoder().encodeToString(encodePng(::buildSlider))

    private fun rgb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun argb(a: Int, v: Int): Int = (a shl 24) or (v shl 16) or (v shl 8) or v
}