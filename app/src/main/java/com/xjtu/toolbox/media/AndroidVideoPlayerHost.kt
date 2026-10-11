package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable

/**
 * 视频播放在 **Android 侧的填法** —— `:core` 的 [VideoPlayerHost] 实现。
 *
 * 真正画画的是同目录下的 `VideoPlayer.kt`（`DirectVideoPlayerScreen`）—— 那是搬迁前就在 `:app`
 * 的那一页 Media3 / ExoPlayer 全屏播放器（横屏、双画面、倍速、源切换），**一行未改**：
 * 它本来就只能用 Android 的 `AndroidView` + `Activity.requestedOrientation` 实现，
 * 搬不进 `:core`。收成端口之后它只是从「屏上的一个裸函数槽位」变成「这个对象的一个方法」。
 */
object AndroidVideoPlayerHost : VideoPlayerHost {

    @Composable
    override fun Play(request: VideoPlayRequest, onClose: () -> Unit) {
        DirectVideoPlayerScreen(
            instructorUrl = request.instructorUrl,
            encoderUrl = request.encoderUrl,
            title = request.title,
            headers = request.headers,
            isLive = request.isLive,
            startInDual = request.startInDual,
            onBack = onClose,
        )
    }
}
