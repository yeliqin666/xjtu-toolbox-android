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
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.lms.LmsDownloadStore
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.zyxf.ZyxfDownloader
import com.xjtu.toolbox.zyxf.isCmsOneShotDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URI

private const val TAG = "BrowserScreen"

/**
 * 将 OkHttp CookieJar 中的 cookies 同步到 Android WebView CookieManager
 * 兼容 PersistentCookieJar 和 java.net.CookieManager
 */
/** Set-Cookie 的 Expires 要求 RFC 1123 GMT 格式。 */
private fun httpDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        .format(java.util.Date(epochMillis))

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
                "lms.xjtu.edu.cn", "webvpn.xjtu.edu.cn"
            ) + extraDomains).distinct()
            var count = 0
            for (domain in domains) {
                // 用 loadAllForHost 而不是 loadForRequest：后者会按请求路径过滤，而这里只能
                // 构造一个路径为 "/" 的 URL，结果是所有 Path 更深的 cookie（WebVPN 网关大量
                // 使用）被静默丢弃——注入看着成功，实际残缺。详见 PersistentCookieJar 里的说明。
                val cookies = jar.loadAllForHost(domain)
                for (cookie in cookies) {
                    val cookieStr = buildString {
                        append("${cookie.name}=${cookie.value}")
                        append("; Domain=${cookie.domain}")
                        append("; Path=${cookie.path}")
                        // 带上到期时间，否则注进 WebView 会退化成会话 cookie，进程一死就没了
                        if (cookie.persistent) {
                            append("; Expires=${httpDate(cookie.expiresAt)}")
                        }
                        if (cookie.secure) append("; Secure")
                    }
                    webCookieManager.setCookie("https://$domain/", cookieStr)
                    if (!cookie.secure) webCookieManager.setCookie("http://$domain/", cookieStr)
                    count++
                }
            }
            webCookieManager.flush()
            Log.d(TAG, "Cookie sync complete, total: $count")
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
    val isDark = LocalIsDarkTheme.current
    val darkState = rememberUpdatedState(isDark)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUrlState = rememberUpdatedState(currentUrl)

    val saveHttpDownload = rememberUpdatedState { url: String, fallbackName: String, userAgent: String? ->
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@rememberUpdatedState
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val referer = currentUrlState.value.ifBlank { url }
        scope.launch {
            Toast.makeText(context, "开始下载 ${fallbackName.ifBlank { "文件" }}", Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.IO) {
                ZyxfDownloader.download(
                    context = context,
                    url = url,
                    fallbackName = fallbackName,
                    userAgent = userAgent,
                    cookie = cookie,
                    referer = referer,
                    category = LmsDownloadStore.CATEGORY_OTHER,
                )
            }
            Toast.makeText(
                context,
                if (ok != null) "已保存 $ok" else "下载失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(isDark, webViewRef) {
        webViewRef?.let { WebViewNightMode.apply(it, isDark) }
    }

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
                    color = MiuixTheme.colorScheme.surface,
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
                    // 在 UA 末尾追加 XJTU-WX-MP 标识，方便服务端识别来自本 App
                    settings.userAgentString = settings.userAgentString + " XJTU-WX-MP/1.0"
                    // 教务处附件是 target="_blank"。不开的话点击会被吞掉；开了必须自己接 onCreateWindow。
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            url?.let {
                                // 微信小程序入口页 servicewechat.com 在 WebView 里无法真正打开，提示用户去外部浏览器
                                if (it.contains("servicewechat.com")) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "小程序链接请用外部浏览器打开",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
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
                            view?.let { WebViewNightMode.apply(it, darkState.value) }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // 验证码一次性地址：拦在 WebView 发出 GET 之前，只由 App 请求一次。
                            if (isCmsOneShotDownload(url)) {
                                val name = URLUtil.guessFileName(url, null, null)
                                saveHttpDownload.value(url, name, view?.settings?.userAgentString)
                                return true
                            }
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                return false
                            }
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

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val parent = view ?: return false
                            val tmp = WebView(parent.context)
                            tmp.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    req: WebResourceRequest?
                                ): Boolean {
                                    val u = req?.url?.toString() ?: return true
                                    if (isCmsOneShotDownload(u)) {
                                        val name = URLUtil.guessFileName(u, null, null)
                                        saveHttpDownload.value(u, name, parent.settings.userAgentString)
                                    } else {
                                        parent.loadUrl(u)
                                    }
                                    tmp.destroy()
                                    return true
                                }
                            }
                            transport.webView = tmp
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webViewRef = this

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        if (url.startsWith("blob:")) {
                            Toast.makeText(context, "无法保存此链接，请用系统浏览器打开", Toast.LENGTH_SHORT).show()
                            return@setDownloadListener
                        }
                        val name = runCatching {
                            URLUtil.guessFileName(url, contentDisposition, mimeType)
                        }.getOrNull().orEmpty()
                        saveHttpDownload.value(url, name, userAgent)
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
