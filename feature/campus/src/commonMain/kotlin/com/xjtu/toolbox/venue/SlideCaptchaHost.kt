package com.xjtu.toolbox.venue

import androidx.compose.runtime.Composable

/** 滑块验证码的宿主端口：画滑块的 Composable，由用户手动拖动。 */
interface SlideCaptchaHost {

    /** 画滑块验证码视图（屏上验证码弹窗里的那一段 UI）：从 [CaptchaData] 里取 base64 图与尺寸，拖动完成时产出 [SliderResult]。 */
    @Composable
    fun CaptchaView(data: CaptchaData, onSolved: (SliderResult) -> Unit)
}