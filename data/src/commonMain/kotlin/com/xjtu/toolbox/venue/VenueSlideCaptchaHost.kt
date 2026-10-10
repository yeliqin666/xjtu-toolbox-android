package com.xjtu.toolbox.venue

import androidx.compose.runtime.Composable

/**
 * [SlideCaptchaHost] 的**共享实现**（`:data`）：画滑块用搬进来的 [SliderCaptchaView]，
 * 自动识别用 [VenueCaptchaSolver] —— 就是搬迁前 `:app` 在 `AppNavHost` 里注入的那两个
 * lambda 的原样合成，行为一行未改。
 *
 * Android（`:app`）直接引用这一份。桌面端要自己画滑块 UI（MUST：桌面滑块是新写的宿主实现），
 * 但它的 [SlideCaptchaHost.solve] 可以委托这里的识别路径 —— 「识别器与提交逻辑在 `:data`（共享）」。
 */
object VenueSlideCaptchaHost : SlideCaptchaHost {

    @Composable
    override fun CaptchaView(data: CaptchaData, onSolved: (SliderResult) -> Unit) {
        SliderCaptchaView(
            backgroundImageBase64 = data.backgroundImage,
            sliderImageBase64 = data.sliderImage,
            bgOriginalWidth = data.bgWidth,
            bgOriginalHeight = data.bgHeight,
            sliderOriginalWidth = data.sliderWidth,
            sliderOriginalHeight = data.sliderHeight,
            onSlideComplete = onSolved,
        )
    }

    override suspend fun solve(data: CaptchaData, shownAtMillis: Long): SolvedCaptcha? =
        VenueCaptchaSolver.solve(data)?.let { solved ->
            SolvedCaptcha(
                sliderResult = VenueCaptchaSolver.stamp(solved.sliderResult, shownAtMillis),
                releaseAfterMillis = VenueCaptchaSolver.releaseAt(solved.sliderResult),
            )
        }
}