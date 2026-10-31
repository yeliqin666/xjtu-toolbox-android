package com.xjtu.toolbox.desktop.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.media.VideoPlayRequest
import com.xjtu.toolbox.media.VideoPlayerHost
import java.io.Closeable
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 视频播放在 桌面侧的填法 —— `:core` 的 [VideoPlayerHost] 实现。 */
object DesktopVideoPlayerHost : VideoPlayerHost {

    @Composable
    override fun Play(request: VideoPlayRequest, onClose: () -> Unit) {
        // 系统播放器只吃 URL：要带鉴权头（LMS 的 Origin / Referer）的流得先过本机回环代理，
        // 把「带头取流」留在我们这边。headers 为空时不动地址，跟以前一样直接交上游 URL。
        val handoff = remember(request) { PlaybackHandoff(request) }
        var handedOver by remember { mutableStateOf(false) }
        var viaProxy by remember { mutableStateOf(false) }

        // 离开播放页 / 换一路流就收掉代理：不给本机留一个还开着的、带凭据的端口。
        DisposableEffect(handoff) {
            onDispose { handoff.close() }
        }

        LaunchedEffect(handoff) {
            handoff.open()
            handedOver = handoff.handedOver
            viaProxy = handoff.viaProxy
        }

        Column(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(request.title, color = MiuixTheme.colorScheme.onSurface, fontSize = 18.sp)
            Text(
                if (handedOver) "已把视频地址交给系统的默认播放器打开。"
                else "没能把地址交给系统播放器（这台机器上没有 xdg-open / open，或没有可用的关联程序）。",
                color = if (handedOver) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                fontSize = 13.sp,
            )
            request.instructorUrl?.let {
                Text("教师直播：$it", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            }
            request.encoderUrl?.let {
                Text("电脑屏幕：$it", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            }
            if (viaProxy) {
                Text(
                    "这一路流要登录态才能拉：请求头由本机回环代理（127.0.0.1）补上再转发给播放器。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            } else if (request.headers.isNotEmpty() && handedOver) {
                Text(
                    "⚠️ 本地转发没起来，已按原地址交出去 —— 要登录态的流可能会 403。",
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
            if (request.isLive) {
                Text(
                    "这是直播（HLS / m3u8）：能否播放取决于这台机器上装的播放器。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            Text(
                "桌面端没有内置播放器 —— ExoPlayer / Media3 是 Android 专属组件，skiko 这边只有绘制栈、" +
                    "没有媒体解码，共享内核里也没有等价的播放器。所以这一页只把地址交出去，不在这里画画面。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Text(
                "TODO：内嵌播放器属《桌面端移植方案》§5.4 的宿主能力族（VLCJ / JCEF 之类的外部解码栈，" +
                    "或等 skiko 侧的媒体能力）—— 本轮只降级，不假装能播。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(text = "返回", onClick = onClose, minWidth = 120.dp)
        }
    }
}

/**
 * 「把一路流交给系统播放器」这一次交接的全部状态：起代理 → 交出地址 → 收代理。
 *
 * [VideoPlayRequest.headers] 非空时（LMS 的回放要 `Origin` / `Referer`，没有就 403）才走回环代理：
 * 系统播放器不会替我们带请求头，所以让播放器连本机，由本机的 [LoopbackStreamProxy] 带着头去上游取。
 * headers 为空时行为不变 —— 直接把上游地址交出去。
 */
internal class PlaybackHandoff(private val request: VideoPlayRequest) : Closeable {

    private var proxy: LoopbackStreamProxy? = null

    /** 交给播放器的是代理地址（而不是上游原地址）。 */
    var viaProxy: Boolean = false
        private set

    /** 地址真的交出去了。 */
    var handedOver: Boolean = false
        private set

    fun open() {
        // 与 Android 那份的默认档一致：优先电脑屏幕那一路（它带自己的音轨）
        val primary = request.encoderUrl ?: request.instructorUrl ?: return
        // 重复调用（重组 / 换一路流）时先收掉上一次的代理，别留两个监听的端口。
        close()
        val target = if (request.headers.isEmpty()) primary else proxyUrl(primary) ?: primary
        viaProxy = target != primary
        handedOver = openWithSystemProgram(target)
    }

    override fun close() {
        proxy?.close()
        proxy = null
        viaProxy = false
    }

    /** 起代理、拿到这次播放专用的回环地址；起不来就 `null`（调用方退回直接交原地址）。 */
    private fun proxyUrl(primary: String): String? {
        val stream = ProxiedStream(primary, request.headers)
        val started = runCatching { LoopbackStreamProxy.start(listOf(stream)) }.getOrNull() ?: return null
        val url = started.localUrl(stream)
        if (url == null) {
            started.close()
            return null
        }
        proxy = started
        return url
    }
}

