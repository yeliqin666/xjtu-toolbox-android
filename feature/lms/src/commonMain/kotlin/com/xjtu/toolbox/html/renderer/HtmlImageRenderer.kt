package com.xjtu.toolbox.html.renderer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.platform.decodeImageFull
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

    val placeholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderColor)
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