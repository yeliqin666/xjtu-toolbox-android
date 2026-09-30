@file:OptIn(ExperimentalLayoutApi::class)

package com.xjtu.toolbox.browser

import com.xjtu.toolbox.network.APP_UA
import com.xjtu.toolbox.util.redactUrl
import com.xjtu.toolbox.util.releaseSafely
import com.xjtu.toolbox.webvpn.WebVpnUtil
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
        if (jar is com.xjtu.toolbox.network.PersistentCookieJar) {
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
    thenUrl: String = "",
    site: SiteSession? = null,
    cookieClient: OkHttpClient? = null,
    extraCookieDomains: List<String> = emptyList(),
    /** 地址还没备好（[initialUrl] 为空）：先出空白页和进度条，等 [initialUrl] 有值再加载。 */
    waiting: Boolean = false,
    onBack: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var editingUrl by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(waiting) }
    var pageTitle by remember { mutableStateOf("浏览器") }
    var progress by remember { mutableFloatStateOf(if (waiting) 10f else 0f) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
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

    // 地址后到：WebView 已建好但当时没有可加载的，这时补加载
    var initialLoaded by remember { mutableStateOf(initialUrl.isNotBlank()) }
    LaunchedEffect(initialUrl, webViewRef) {
        val web = webViewRef ?: return@LaunchedEffect
        if (initialLoaded || initialUrl.isBlank()) return@LaunchedEffect
        initialLoaded = true
        syncCookiesToWebView(site, cookieDomains)
        syncCookiesToWebView(cookieClient, cookieDomains)
        web.loadUrl(normalizeUrl(initialUrl))
    }

    // 系统返回：先收起地址栏，再在网页里后退（跳过登录中转页），退到头才关掉浏览器
    BackHandler {
        val web = webViewRef
        val steps = web?.let(::backStepsSkippingAuth)
        when {
            editing -> editing = false
            web != null && steps != null -> web.goBackOrForward(-steps)
            else -> onBack()
        }
    }
    // 键盘收起就当编辑结束，不用再按一次返回
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible) editing = false }

    Scaffold(
        topBar = {
            // 微信内置浏览器那样：关闭 · 标题和域名 · 更多。点标题原地变成地址栏
            Surface(color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        if (editing) {
                            val focus = remember { FocusRequester() }
                            var hadFocus by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            TextField(
                                value = editingUrl,
                                onValueChange = { editingUrl = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focus)
                                    // 点到网页上、焦点被抢走，也退出编辑
                                    .onFocusChanged { if (it.hasFocus) hadFocus = true else if (hadFocus) editing = false },
                                singleLine = true,
                                textStyle = MiuixTheme.textStyles.body2,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        webViewRef?.loadUrl(normalizeUrl(editingUrl))
                                        editing = false
                                    }
                                ),
                            )
                        } else {
                            val host = remember(currentUrl) { displayHost(currentUrl) }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                                        editingUrl = currentUrl
                                        editing = true
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    pageTitle,
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (host.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentUrl.startsWith("https://")) {
                                        Icon(
                                            Icons.Default.Lock, contentDescription = null,
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    Text(
                                        host,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                    )
                                    if (WebVpnUtil.isWebVpnUrl(currentUrl)) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "WebVPN",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (editing) {
                            Text(
                                "取消",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(remember { MutableInteractionSource() }, indication = null) { editing = false }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        } else Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = "更多")
                            }
                            AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val isWeb = currentUrl.startsWith("http")
                                // 点任一项都先收起菜单
                                val item = @Composable { icon: ImageVector, label: String, action: () -> Unit ->
                                    BrowserMenuItem(icon, label) { menuOpen = false; action() }
                                }
                                item(Icons.Default.Refresh, "刷新") { webViewRef?.reload() }
                                if (canGoForward) item(Icons.AutoMirrored.Filled.ArrowForward, "前进") { webViewRef?.goForward() }
                                item(Icons.Default.Edit, "输入网址") {
                                    editingUrl = currentUrl
                                    editing = true
                                }
                                if (isWeb) {
                                    item(Icons.Default.ContentCopy, "复制链接") {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("url", shareableUrl(currentUrl))))
                                            Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    // 分享：纯文字「网页标题 + 链接」，走系统分享面板
                                    item(Icons.Default.Share, "分享") { shareWebPage(context, pageTitle, currentUrl) }
                                    item(Icons.AutoMirrored.Filled.OpenInNew, "在外部浏览器打开") {
                                        runCatching {
                                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(shareableUrl(currentUrl))))
                                        }.onFailure { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }
                        }
                    }
                    // 进度条贴着顶栏下沿；不加载时留一条同高的空白，网页不会跟着上下跳
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (isLoading) {
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                height = 2.dp,
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(backgroundColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
        },
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
                    // 和 OkHttp 同一串：注进来的统一认证登录态绑定 UA，不一致就会被当成没登录
                    settings.userAgentString = APP_UA
                    // 教务处附件是 target="_blank"。不开的话点击会被吞掉；开了必须自己接 onCreateWindow。
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        /** 自动跳过 WebVPN 登录前页的次数上限：统一认证失败时网关会再把人送回来，别来回兜圈。 */
                        private var webVpnAutoLogins = 0

                        /** 还没跳去 [thenUrl]：等首个页面加载完、且已经离开登录页（登录页上要用户自己输）。 */
                        private var thenPending = thenUrl.isNotBlank()

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

                        // 单页应用改地址不触发 onPageFinished，历史变化时也更新一下
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            canGoForward = view?.canGoForward() ?: false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            canGoForward = view?.canGoForward() ?: false
                            url?.let {
                                currentUrl = it
                                editingUrl = it
                            }
                            view?.let { WebViewNightMode.apply(it, darkState.value) }
                            if (thenPending && url != null && !isAuthHop(url)) {
                                thenPending = false
                                view?.loadUrl(normalizeUrl(thenUrl))
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // WebVPN 网关的「登录前页」（/login，不带参数）只有一颗「登录」按钮，
                            // 按下去就是 /login?cas_login=true：走统一认证，拿到 ticket 后网关按事先记下的
                            // 目标地址跳回去。网关会话过期时直接替用户按下这一步，不在中间停一页。
                            if (request.isForMainFrame && isWebVpnLoginLanding(request.url) && webVpnAutoLogins < 2) {
                                webVpnAutoLogins++
                                view?.loadUrl(WebVpnUtil.WEBVPN_LOGIN_URL)
                                return true
                            }
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
                        // 第一次请求之前先把 App 的会话 cookie 注进来。上面的 LaunchedEffect 也会同步，
                        // 但它要等这一帧组合提交之后才跑，比这里的 loadUrl 晚：WebVPN 打开转换后的网址时，
                        // 第一个请求不带网关票据，被 302 到网关的登录前页，要用户再点一次「登录」
                        // （那时 onPageStarted 已经把 cookie 补进去了，所以一点就过）。
                        syncCookiesToWebView(site, cookieDomains)
                        syncCookiesToWebView(cookieClient, cookieDomains)
                        Log.d(TAG, "load initialUrl=${normalizedInitialUrl.redactUrl()}")
                        loadUrl(normalizedInitialUrl)
                    }
                }
            },
            onRelease = { view ->
                if (webViewRef === view) webViewRef = null
                view.releaseSafely()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        )
    }
}

@Composable
private fun BrowserMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    AppDropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/**
 * 返回要退几步：从当前往前找第一个不是登录中转页的历史项。统一认证、WebVPN 登录前页、带
 * CAS ticket 的回调这类页面一打开就自动跳走，退到它们身上等于马上又被送回来，所以一并跳过。
 * 前面没有正经页面时返回 null，由调用方关掉浏览器。
 */
private fun backStepsSkippingAuth(web: WebView): Int? {
    val history = web.copyBackForwardList()
    for (i in history.currentIndex - 1 downTo 0) {
        if (!isAuthHop(history.getItemAtIndex(i).url)) return history.currentIndex - i
    }
    return null
}

private fun isAuthHop(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    return host == "login.xjtu.edu.cn" || host == "cas.xjtu.edu.cn" ||
        (host == "org.xjtu.edu.cn" && uri.path.orEmpty().contains("login")) ||
        isWebVpnLoginLanding(uri) ||
        (host == "webvpn.xjtu.edu.cn" && uri.path?.trimEnd('/') == "/login") ||
        uri.getQueryParameter("ticket") != null
}

/** 顶栏第二行显示的域名：WebVPN 代理地址换回原站域名，去掉 www.。 */
private fun displayHost(url: String): String {
    val real = shareableUrl(url)
    return runCatching { URI(real).host.orEmpty() }.getOrDefault("").removePrefix("www.")
}

/** 对外（复制、分享、外部打开）用的地址：WebVPN 代理地址换回原始地址，对方没有网关会话也看得懂。 */
internal fun shareableUrl(url: String): String =
    WebVpnUtil.getOriginalUrl(url)?.takeIf { WebVpnUtil.isWebVpnUrl(url) && it.isNotBlank() } ?: url

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

/**
 * WebVPN 网关的「登录前页」：`https://webvpn.xjtu.edu.cn/login`，不带 `cas_login`。
 * 没有网关会话时访问任何代理地址都会被 302 到这里。
 */
internal fun isWebVpnLoginLanding(uri: android.net.Uri): Boolean =
    uri.host.equals("webvpn.xjtu.edu.cn", ignoreCase = true) &&
        uri.path?.trimEnd('/') == "/login" &&
        uri.getQueryParameter("cas_login") == null &&
        uri.getQueryParameter("ticket") == null

/**
 * 把当前网页分享出去：纯文字，第一行网页标题，第二行链接。
 *
 * 分享 WebVPN 代理出来的地址时换回原始地址：`webvpn.xjtu.edu.cn/https/7772…` 这种链接
 * 对方没有网关会话打不开，也看不出是哪个网站。原始地址在校外打不开，至少看得懂。
 * 标题是 WebView 还没拿到时的占位「浏览器」，或者就是网址本身时，只发链接。
 */
internal fun shareWebPage(context: android.content.Context, title: String, url: String) {
    val link = shareableUrl(url)
    val cleanTitle = title.trim().takeIf { it.isNotEmpty() && it != "浏览器" && it != url && it != link }
    val text = if (cleanTitle != null) "$cleanTitle\n$link" else link
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        cleanTitle?.let { putExtra(android.content.Intent.EXTRA_SUBJECT, it) }
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "分享网页")) }
        .onFailure { Toast.makeText(context, "没有可以分享到的应用", Toast.LENGTH_SHORT).show() }
}
