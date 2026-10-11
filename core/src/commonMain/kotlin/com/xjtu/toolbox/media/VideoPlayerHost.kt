package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable

/**
 * 一个视频播放请求（直播 HLS / 单机位回放 / 多机位）—— [VideoPlayerHost.Play] 的参数。
 *
 * 搬迁前这个类型叫 `LmsVideoRequest`（写在 `:core` 的 `LmsScreen.kt` 里），屏上的槽位是一个
 * 裸的函数类型 `(@Composable (LmsVideoRequest, () -> Unit) -> Unit)?`。收成端口时把它挪到
 * 播放器自己这一侧（`media` 包）并改名 —— 它描述的是「要播什么」，与哪一屏发起无关。
 */
data class VideoPlayRequest(
    val title: String,
    val instructorUrl: String?,
    val encoderUrl: String?,
    val isLive: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val startInDual: Boolean = false,
)

/**
 * 视频播放的**宿主端口**：屏只说「要播这两路流」，怎么播由各端自己填。
 *
 * ## 各端实现
 *
 *  - **Android（`:app`）**：`AndroidVideoPlayerHost` —— 内置的 ExoPlayer 全屏页
 *    （`media/VideoPlayer.kt` 的 `DirectVideoPlayerScreen`，Media3/`AndroidView`，
 *    横屏 / 倍速 / 双画面 / 源切换那一整页**原样未改**，这一层只是把参数换个名字传进去）；
 *  - **桌面（`:desktop`）**：`DesktopVideoPlayerHost` —— **没有**内嵌播放器（Media3 是
 *    Android 专属，桌面端没有等价物），如实降级：把流地址交给系统播放器（`xdg-open`），
 *    并在窗口里画一句「交给谁了 / 播不了怎么办」。TODO 写在那份实现里。
 *
 * 不传（null）= 这一端没有播放器，屏上给如实说明页（`:core` 的 `LmsScreen` 里那份既有降级）。
 */
interface VideoPlayerHost {

    /** 全屏播 [request] 指的那两路流；[onClose] 是屏上的返回。 */
    @Composable
    fun Play(request: VideoPlayRequest, onClose: () -> Unit)
}
