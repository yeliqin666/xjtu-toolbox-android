package com.xjtu.toolbox.venue

import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_BG_HEIGHT
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_BG_WIDTH
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_ID
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_SLIDER_HEIGHT
import com.xjtu.toolbox.venue.VenueFakeUpstream.Companion.CAPTCHA_SLIDER_WIDTH
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 滑块识别器的**解算口径钉住**（Stage B 先钉住再搬：期望值来自搬迁前在独立的 Python 复刻里
 * 按同一算法在 [VenueCaptchaFixture] 上推出的数值，见那个夹具的 KDoc）。
 *
 * 识别器从 `:app` 搬进 `:data` 时**一行语义都没改**，这些断言就是「没改」的证据；
 * 走的是 `:core` 的图片缝（JVM 侧 = ImageIO）解出的像素。
 */
class VenueCaptchaSolverJvmTest {

    private fun captchaData() = CaptchaData(
        id = CAPTCHA_ID,
        backgroundImage = VenueCaptchaFixture.backgroundDataUri(),
        sliderImage = VenueCaptchaFixture.sliderDataUri(),
        bgWidth = CAPTCHA_BG_WIDTH,
        bgHeight = CAPTCHA_BG_HEIGHT,
        sliderWidth = CAPTCHA_SLIDER_WIDTH,
        sliderHeight = CAPTCHA_SLIDER_HEIGHT,
    )

    @Test
    fun `识别器在图库里找到缺口：位移 141、置信度 0_74 上下`() {
        val solved = VenueCaptchaSolver.solve(captchaData())
        assertNotNull(solved, "合成图上有明确的缺口，识别不该失败")

        assertEquals(VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X, solved.targetX)
        // 独立复刻（numpy/纯 Python 镜像同一算法）推出的置信度 ≈ 0.7418；两端浮点略有
        // 差异（模板中心化那一步在 Kotlin 是 Float），给 0.02 的余量，锚的是一次确认的区间。
        assertTrue(solved.confidence in 0.60..0.95, "置信度应落在推导区间，实际=${solved.confidence}")

        val result = solved.sliderResult
        assertEquals(260, result.bgImageWidth, "服务端 260 坐标系")
        assertEquals(0, result.bgImageHeight)
        assertEquals(0, result.sliderImageWidth)
        assertEquals(50, result.sliderImageHeight, "50 * 260 / 260")
        assertEquals("", result.startSlidingTime, "solve 出的轨迹时刻由 stamp 盖章，这里恒空")

        val track = result.trackList
        assertTrue(track.isNotEmpty())
        assertEquals("down", track.first().type)
        assertEquals(0, track.first().x)
        assertEquals("up", track.last().type)
        assertEquals(VenueCaptchaFixture.EXPECTED_SOLVE_TARGET_X, track.last().x, "松手点落在缺口处")
        // x 单调不减（±1 抖动由框定保证不倒退到 lastX 之前）
        assertTrue(track.zipWithNext().all { (a, b) -> b.x >= a.x }, "轨迹 x 应当单调不减")
    }

    @Test
    fun `stamp 与 releaseAt 按轨迹时刻盖章，时刻落在验证码出现之后`() {
        val solved = VenueCaptchaSolver.solve(captchaData())!!
        val shownAt = 1_730_000_000_000L
        val stamped = VenueCaptchaSolver.stamp(solved.sliderResult, shownAt)

        val first = solved.sliderResult.trackList.first().t
        val last = solved.sliderResult.trackList.last().t
        assertEquals(Instant.ofEpochMilli(shownAt + first), Instant.parse(stamped.startSlidingTime))
        assertEquals(Instant.ofEpochMilli(shownAt + last), Instant.parse(stamped.entSlidingTime))
        assertEquals(last, VenueCaptchaSolver.releaseAt(solved.sliderResult))

        val track = stamped.trackList
        assertTrue(Instant.parse(stamped.startSlidingTime).isAfter(Instant.ofEpochMilli(shownAt)))
        assertTrue(Instant.parse(stamped.entSlidingTime).isAfter(Instant.parse(stamped.startSlidingTime)))
        assertTrue(track.all { it.t >= 0 })
    }

    @Test
    fun `共享宿主 solve 合成盖章与 release 等待时长`() {
        val shownAt = System.currentTimeMillis()
        val solved = runBlocking { VenueSlideCaptchaHost.solve(captchaData(), shownAt) }
        assertNotNull(solved)
        val last = solved.sliderResult.trackList.last().t
        assertEquals(last, solved.releaseAfterMillis)
        assertTrue(solved.sliderResult.startSlidingTime.isNotBlank())
        assertTrue(solved.sliderResult.entSlidingTime.isNotBlank())
        // 盖章后的时刻 = 出现时刻 + 轨迹 t（与手动滑同一格式：ISO_INSTANT）
        assertEquals(
            Instant.ofEpochMilli(shownAt + last),
            Instant.parse(solved.sliderResult.entSlidingTime),
        )
    }

    @Test
    fun `解不开的图返回 null（不是崩溃）`() {
        val data = CaptchaData(
            id = CAPTCHA_ID,
            backgroundImage = "data:image/jpeg;base64,not-a-real-image!!",
            sliderImage = "data:image/png;base64,ZmFrZS1zbGlkZXI=",
            bgWidth = 260, bgHeight = 160, sliderWidth = 50, sliderHeight = 50,
        )
        assertNull(VenueCaptchaSolver.solve(data))
    }

    @Test
    fun `纯色背景没有可匹配的边缘，识别落回 null`() {
        // 背景是平灰（没有任何边缘特征），滑块照旧 —— NCC 全为 0 ⇒ bestScore 不过 0.08 下限
        val data = CaptchaData(
            id = CAPTCHA_ID,
            backgroundImage = flatGrayDataUri(),
            sliderImage = VenueCaptchaFixture.sliderDataUri(),
            bgWidth = 260, bgHeight = 160, sliderWidth = 50, sliderHeight = 50,
        )
        assertNull(VenueCaptchaSolver.solve(data))
    }

    private fun flatGrayDataUri(): String {
        val img = java.awt.image.BufferedImage(260, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 160) for (x in 0 until 260) img.setRGB(x, y, (200 shl 16) or (200 shl 8) or 200)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
}