package com.xjtu.toolbox.html.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import okhttp3.Call
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 嵌入式图片渲染器。
 * 支持通过 [callFactory] 自动带上站点的 CookieJar 与网络拦截器（解决思源学堂防盗链/登录态凭据问题）。
 */
@Composable
fun HtmlImage(
    url: String,
    alt: String? = null,
    callFactory: Call.Factory? = null,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    if (url.isBlank()) return

    val context = LocalContext.current
    val imageLoader = remember(context, callFactory) {
        if (callFactory != null) {
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = callFactory))
                }
                .crossfade(true)
                .build()
        } else {
            null
        }
    }

    val placeholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderColor)
    ) {
        val request = remember(url, context) {
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build()
        }

        if (imageLoader != null) {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = alt ?: "图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = alt ?: "图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
