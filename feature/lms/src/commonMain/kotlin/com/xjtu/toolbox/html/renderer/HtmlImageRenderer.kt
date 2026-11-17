package com.xjtu.toolbox.html.renderer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjtu.toolbox.platform.decodeImageFull
import com.xjtu.toolbox.ui.components.ZoomableBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 取一张图的字节；取不到返回 null。带不带登录态由调用方决定（dev 上是 Coil 的 `callFactory`）。 */
typealias HtmlImageLoader = suspend (url: String) -> ByteArray?

/** 补全相对地址：`//host/x` 补协议，`/x` 补 [baseUrl] 的站点根；其余原样。 */
internal fun resolveHtmlUrl(url: String, baseUrl: String?): String {
    val u = url.trim()
    return when {
        u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") && baseUrl != null -> baseUrl.trimEnd('/') + u
        else -> u
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
}

/**
 * 嵌入式图片渲染器。
 * 字节由 [imageLoader] 取（思源学堂的图要带站点登录态，见 `LmsSource.imageBytes`），
 * 解码走各端的 [decodeImageFull]；加载中给一块占位，失败给一行说明文字。
 *
 * **不反色**：学堂富文本里作业截图、板书照片、转成图片的公式混在一起，按 `invert(100%)
 * hue-rotate(180deg)` 整层翻一遍会把照片变成负片，所以图片一律原样显示（见 `HtmlStyle` 类注释）。
 * 代价是白底黑字的公式图在深色页面上仍是一块白 —— 但看不清的东西可以点开看：点一下开全屏，
 * 双指缩放 / 双击放大，左上角关闭按钮或返回键退出（[HtmlImageViewer]）。
 */
@Composable
fun HtmlImage(
    url: String,
    alt: String? = null,
    imageLoader: HtmlImageLoader? = null,
    baseUrl: String? = null,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    if (url.isBlank()) return
    val resolved = resolveHtmlUrl(url, baseUrl)
    var viewing by remember(resolved) { mutableStateOf(false) }

    val state by produceState<ImageState>(ImageState.Loading, resolved, imageLoader) {
        value = if (imageLoader == null) {
            ImageState.Failed
        } else {
            runCatching { imageLoader(resolved) }.getOrNull()
                ?.let { decodeImageFull(it) }
                ?.let { ImageState.Ready(it) }
                ?: ImageState.Failed
        }
    }

    if (viewing) {
        // 委托属性拿不到 smart cast，先落成本地 val 再判
        val ready = state as? ImageState.Ready
        if (ready != null) HtmlImageViewer(ready.bitmap, alt, onDismiss = { viewing = false })
    }

    // 占位底色始终跟随主题（图还没到时别闪一块纯白）
    val placeholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderColor)
            // 只有真拿到位图才让点：加载中和失败状态点开是个空的全屏，等于许一个点了不会动的响应
            .then(if (state is ImageState.Ready) Modifier.clickable { viewing = true } else Modifier)
    ) {
        when (val s = state) {
            is ImageState.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = alt ?: "图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            ImageState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp))
            ImageState.Failed -> Text(
                text = "🖼 " + (alt ?: "图片加载失败"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 富文本图片的全屏查看：黑底、双指缩放 / 拖动、双击在 1 倍和 2.5 倍之间切换，
 * 没放大时单击关闭，左上角有关闭按钮。缩放层用 [ZoomableBox]（校历原图、社区帖子图共用同一套）。
 *
 * 直接用已经解好的 [bitmap]，不再按 URL 重取一次：正文里的图已经在内存里，重取既慢又多一次
 * 带登录态的请求；[decodeImageFull] 给的就是全尺寸位图，放大看细节已经够。
 */
@Composable
private fun HtmlImageViewer(bitmap: ImageBitmap, alt: String?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableBox(Modifier.fillMaxSize(), key = bitmap, onTap = onDismiss) { zoom ->
                Image(
                    bitmap = bitmap,
                    contentDescription = alt ?: "图片",
                    contentScale = ContentScale.Fit,
                    modifier = zoom,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
