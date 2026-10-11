package com.xjtu.toolbox.venue

import androidx.compose.runtime.Composable

/**
 * 滑块验证码的**宿主端口**：把原来 `VenueScreen` 的两个槽位（画滑块的那个 Composable 与
 * 自动识别器）收进一个对象，宿主端各实现一份 —— 屏与 ViewModel 只认这一个端口。
 *
 * ## 各端实现
 *
 *  - **Android（`:app`）**：`:data` 的 `VenueSlideCaptchaHost` —— 就是搬迁前的
 *    `SliderCaptchaView` + `VenueCaptchaSolver`（Bitmap/Base64 换成 `:core` 的图片缝），
 *    行为一行不改；
 *  - **桌面（`:desktop`）**：新的 `DesktopSlideCaptchaHost`（自己画的滑块 UI + 拖动回调，
 *    识别/提交逻辑复用 `:data`）；
 *  - **只读端（Web）**：传 null —— 与 [VenueSource.canBook] = false 对齐，验证码弹窗进不去。
 *
 * ## 为什么合成一个而不是两个参数
 *
 * 两个成员总是成对出现（有滑块 UI 的端就有自动识别，反之亦然），合成一个端口后
 * 「哪一端有这条路径」只有一个开关，不会出现「UI 有了、识别没有」这种半截状态。
 */
interface SlideCaptchaHost {

    /**
     * 画滑块验证码视图（屏上验证码弹窗里的那一段 UI）：从 [CaptchaData] 里取 base64 图
     * 与尺寸，拖动完成时产出 [SliderResult]。与搬迁前 `VenueScreen` 的 `captchaView` 槽位
     * 同一个语义。
     */
    @Composable
    fun CaptchaView(data: CaptchaData, onSolved: (SliderResult) -> Unit)

    /**
     * 自动识别：[CaptchaData] + 「验证码什么时候出现在屏幕上的」，返回**盖好时刻**的
     * [SolvedCaptcha]；null = 识别失败/置信度不足 → 停在手动滑动那一档。与搬迁前
     * `VenueScreen` 的 `solveCaptcha` 槽位同一个语义（等待松手的编排在 [VenueViewModel] 里，
     * 本端口只出结果）。
     */
    suspend fun solve(data: CaptchaData, shownAtMillis: Long): SolvedCaptcha?
}