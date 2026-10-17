package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable

/** 视频播放在 Android 侧的填法 —— `:core` 的 [VideoPlayerHost] 实现。 */
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
