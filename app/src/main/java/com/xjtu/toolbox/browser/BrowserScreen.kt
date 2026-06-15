package com.xjtu.toolbox.browser

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.auth.SiteSession
import okhttp3.OkHttpClient
import java.net.URI

private const val TAG = "BrowserScreen"

/**
 * 将 OkHttp CookieJar 中的 cookies 同步到 Android WebView CookieManager
 * 兼容 PersistentCookieJar 和 java.net.CookieManager
 */
internal fun syncCookiesToWebView(
    site: SiteSession?,
    extraDomains: List<String> = emptyList()
) {
    syncCookiesToWebView(site?.client, extraDomains)
}

internal fun syncCookiesToWebView(
    client: OkHttpClient?,
    extraDomains: List<String> = emptyList()
) {
    if (client == null) return
    val webCookieManager = android.webkit.CookieManager.getInstance()
    webCookieManager.setAcceptCookie(true)

    try {
        val jar = client.cookieJar
        if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
            // 使用 PersistentCookieJar：向常见域名查询 cookies
            val domains = (listOf(
                "login.xjtu.edu.cn", "cas.xjtu.edu.cn", "org.xjtu.edu.cn",
                "jwxt.xjtu.edu.cn", "ywtb.xjtu.edu.cn", "bkkq.xjtu.edu.cn",
                "ncard.xjtu.edu.cn", "rg.lib.xjtu.edu.cn", "jwapp.xjtu.edu.cn",
                "webvpn.xjtu.edu.cn"
            ) + extraDomains).distinct()
            var count = 0
            for (domain in domains) {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https").host(domain).build()
                val cookies = jar.loadForRequest(url)
                for (cookie in cookies) {
                    val cookieStr = buildString {
                        append("${cookie.name}=${cookie.value}")
                        append("; Domain=${cookie.domain}")
                        append("; Path=${cookie.path}")
                        if (cookie.secure) append("; Secure")
                    }
                    webCookieManager.setCookie("https://$domain/", cookieStr)
                    if (!cookie.secure) webCookieManager.setCookie("http://$domain/", cookieStr)
                    count++
                }
            }
            webCookieManager.flush()
            Log.d(TAG, "Cookie sync (PersistentCookieJar) complete, total: $count")
        } else {
            Log.d(TAG, "Cookie sync skipped: unsupported jar ${jar.javaClass.name}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Cookie sync failed", e)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "",
    site: SiteSession? = null,
    cookieClient: OkHttpClient? = null,
    extraCookieDomains: List<String> = emptyList(),
    onBack: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var editingUrl by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("浏览器") }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val initialHost = remember(initialUrl) { hostOf(initialUrl) }
    val cookieDomains = remember(initialHost, extraCookieDomains) {
        (extraCookieDomains + listOfNotNull(initialHost)).distinct()
    }

    LaunchedEffect(site, cookieClient, cookieDomains) {
        syncCookiesToWebView(site, cookieDomains)
        syncCookiesToWebView(cookieClient, cookieDomains)
    }

    Scaffold(
        topBar = {
            Column {
                SmallTopAppBar(
                    title = pageTitle,
                    color = MiuixTheme.colorScheme.surfaceVariant,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        // 后退
                        IconButton(onClick = { webViewRef?.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退")
                        }
                        // 前进
                        IconButton(onClick = { webViewRef?.goForward() }, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进")
                        }
                        // 刷新
                        IconButton(onClick = { webViewRef?.reload() }) {
                            Icon(Icons.Default.Refresh, "刷新")
                        }
                    }
                )
                // 进度条
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        height = 2.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(backgroundColor = Color.Transparent)
                    )
                }
            }
        },
        bottomBar = {
            // URL 输入栏
            Surface(
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = editingUrl,
                        onValueChange = { editingUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = "输入网址",
                        textStyle = MiuixTheme.textStyles.footnote1,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val url = normalizeUrl(editingUrl)
                                webViewRef?.loadUrl(url)
                            }
                        )
                    )
                }
            }
        }
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    settings.userAgentString = settings.userAgentString.replace(
                        Regex("wv"), ""
                    ) // 去掉 wv 标记，某些网站会拒绝 WebView

                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            url?.let {
                                val host = hostOf(it)
                                syncCookiesToWebView(site, listOfNotNull(host))
                                syncCookiesToWebView(cookieClient, listOfNotNull(host))
                                currentUrl = it
                                editingUrl = it
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                            url?.let {
                                currentUrl = it
                                editingUrl = it
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            // 拦截外部链接 scheme (tel:, mailto:, intent:)
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                return false // 正常加载
                            }
                            // 尝试用外部 intent 打开
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.toFloat()
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.let { pageTitle = it }
                        }
                    }

                    webViewRef = this

                    // 文件下载处理
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                        try {
                            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            val request = DownloadManager.Request(Uri.parse(url)).apply {
                                setMimeType(mimeType)
                                addRequestHeader("User-Agent", userAgent)
                                // 同步 WebView cookies 到下载请求
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (!cookies.isNullOrEmpty()) {
                                    addRequestHeader("Cookie", cookies)
                                }
                                setTitle(fileName)
                                setDescription("正在下载…")
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            }
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            dm.enqueue(request)
                            Toast.makeText(context, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Download failed", e)
                            // 回退：用系统浏览器打开
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    // 加载 URL
                    if (initialUrl.isNotBlank()) {
                        val normalizedInitialUrl = normalizeUrl(initialUrl)
                        Log.d(TAG, "load initialUrl=$normalizedInitialUrl")
                        loadUrl(normalizedInitialUrl)
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
    return "https://www.bing.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
}

private fun hostOf(url: String): String? =
    runCatching { URI(normalizeUrl(url)).host?.lowercase() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
