package com.xjtu.toolbox.desktop.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 视频播放在 桌面侧的填法 —— `:core` 的 [VideoPlayerHost] 实现。 */
object DesktopVideoPlayerHost : VideoPlayerHost {

    @Composable
    override fun Play(request: VideoPlayRequest, onClose: () -> Unit) {
        // 与 Android 那份的默认档一致：优先电脑屏幕那一路（它带自己的音轨）
        val primary = request.encoderUrl ?: request.instructorUrl
        var handedOver by remember { mutableStateOf(false) }

        LaunchedEffect(primary) {
            handedOver = primary != null && openWithSystemProgram(primary)
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

