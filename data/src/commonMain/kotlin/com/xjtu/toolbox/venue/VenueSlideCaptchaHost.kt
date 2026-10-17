package com.xjtu.toolbox.venue

import androidx.compose.runtime.Composable

/** [SlideCaptchaHost] 的共享实现（`:data`） */
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