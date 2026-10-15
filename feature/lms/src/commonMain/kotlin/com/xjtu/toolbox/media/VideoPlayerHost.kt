package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable

/** 一个视频播放请求（直播 HLS / 单机位回放 / 多机位）—— [VideoPlayerHost.Play] 的参数。 */
data class VideoPlayRequest(
    val title: String,
    val instructorUrl: String?,
    val encoderUrl: String?,
    val isLive: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val startInDual: Boolean = false,
)

/** 视频播放的宿主端口：屏只说「要播这两路流」，怎么播由各端自己填。 */
interface VideoPlayerHost {

    /** 全屏播 [request] 指的那两路流；[onClose] 是屏上的返回。 */
    @Composable
    fun Play(request: VideoPlayRequest, onClose: () -> Unit)
}
