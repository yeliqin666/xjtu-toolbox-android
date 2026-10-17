package com.xjtu.toolbox.venue

import androidx.compose.runtime.Composable

/** 滑块验证码的宿主端口：画滑块的 Composable 与自动识别器。 */
interface SlideCaptchaHost {

    /** 画滑块验证码视图（屏上验证码弹窗里的那一段 UI）：从 [CaptchaData] 里取 base64 图与尺寸，拖动完成时产出 [SliderResult]。 */
    @Composable
    fun CaptchaView(data: CaptchaData, onSolved: (SliderResult) -> Unit)

    /** 自动识别：[CaptchaData] + 「验证码什么时候出现在屏幕上的」，返回盖好时刻的 [SolvedCaptcha]。 */
    suspend fun solve(data: CaptchaData, shownAtMillis: Long): SolvedCaptcha?
}