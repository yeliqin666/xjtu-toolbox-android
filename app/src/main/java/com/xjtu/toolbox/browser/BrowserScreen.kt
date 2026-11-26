@file:OptIn(ExperimentalLayoutApi::class)

package com.xjtu.toolbox.browser


import com.xjtu.toolbox.network.APP_UA
import com.xjtu.toolbox.util.redactUrl
import com.xjtu.toolbox.util.recoverFromRendererGone
import com.xjtu.toolbox.util.releaseSafely
import com.xjtu.toolbox.webvpn.WebVpnUtil
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import kotlinx.serialization.json.jsonPrimitive
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
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.zyxf.ZyxfDownloader
import com.xjtu.toolbox.zyxf.isCmsOneShotDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
                "lg.xjtu.edu.cn", "lms.xjtu.edu.cn", "lms-h5.xjtu.edu.cn", "webvpn.xjtu.edu.cn",
                // 学堂（含 h5 作业提交页）的统一认证经 identity1 中转，它自己的 SSO 会话 cookie 也要带上
                "identity1.xjtu.edu.cn",
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
    /** 「更多」里的「问小岱」。 */
    onAskAgent: ((com.xjtu.toolbox.agent.AgentForward) -> Unit)? = null,
    onBack: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(waiting) }
    var pageTitle by remember { mutableStateOf("浏览器") }
    var progress by remember { mutableFloatStateOf(if (waiting) 10f else 0f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // 桌面版：只改写 viewport、不换 UA（统一认证登录态绑定 UA）
    var desktop by remember { mutableStateOf(DESKTOP_HOSTS.any { hostOf(initialUrl)?.endsWith(it) == true }) }
    val desktopState = rememberUpdatedState(desktop)
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

    /**
     * 网页里 `<input type="file">` 的唯一入口是 [WebChromeClient.onShowFileChooser]。不实现它，
     * 点击「选择文件」一点反应都没有——思源学堂交作业上传附件就卡在这一步（本 App 不自己实现提交，
     * 只打开官方 h5 页面，所以那一页功能必须完整）。
     *
     * 回调要一直留到用户选完文件才回填，所以先挂在 Compose 状态上。用 MutableState 而不是局部变量，
     * 是为了让 ActivityResult 回调、WebView 内部（factory 里只创建一次）读到的是同一个格子。
     */
    val pendingFilePick = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFilePick.value ?: return@rememberLauncherForActivityResult
        pendingFilePick.value = null
        val data = result.data
        val clip = data?.clipData
        val single = data?.data
        val picked: Array<Uri>? = when {
            result.resultCode != android.app.Activity.RESULT_OK -> null
            // 多选走 clipData、单选走 data；两者都可能给，先看 clipData
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            single != null -> arrayOf(single)
            else -> null
        }
        // 取消也必须回一次 null：WebView 内部在等这个回调，漏掉这一页的「选择文件」之后会彻底没反应
        callback.onReceiveValue(picked)
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

    // 返回：在网页里后退（跳过登录中转页），退到头就关掉浏览器。系统返回键 / 手势走这里，
    // 网页自己的「返回」按钮（history.back() / history.go(-n)）经 [WebBackBridge] 也走这里。
    val onBackState = rememberUpdatedState(onBack)
    val navigateBack: (Int) -> Unit = remember {
        { requested ->
            val web = webViewRef
            val steps = web?.let { if (requested <= 1) backStepsSkippingAuth(it) else backStepsOrNull(it, requested) }
            if (web != null && steps != null) web.goBackOrForward(-steps) else onBackState.value()
        }
    }
    BackHandler { navigateBack(1) }

    Scaffold(
        topBar = {
            // 微信内置浏览器那样：关闭 · 标题和域名 · 更多。不提供地址栏：要打开网址走全局搜索
            Surface(color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CouiToolbarActionButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        run {
                            val host = remember(currentUrl) { displayHost(currentUrl) }
                            Column(
                                Modifier.weight(1f),
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
                        Box {
                            com.xjtu.toolbox.ui.glass.CouiIconMenu(
                                expanded = menuOpen,
                                onExpandedChange = { menuOpen = it },
                                icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "更多") },
                            ) {
                                val isWeb = currentUrl.startsWith("http")
                                // 点任一项都由菜单自己先收起，再走动作
                                option("刷新", icon = Icons.Default.Refresh) { webViewRef?.reload() }
                                option(
                                    if (desktop) "切回手机版" else "桌面版",
                                    icon = Icons.Default.DesktopWindows,
                                ) {
                                    desktop = !desktop
                                    // 关掉要重载才能拿回页面原来的 viewport
                                    webViewRef?.let { if (desktop) it.evaluateJavascript(DESKTOP_VIEWPORT_JS, null) else it.reload() }
                                }
                                if (isWeb) {
                                    // 分享：纯文字「网页标题 + 链接」，走系统分享面板
                                    option("分享", icon = Icons.Default.Share) { shareWebPage(context, pageTitle, currentUrl) }
                                    if (onAskAgent != null) option("问小岱", icon = Icons.Default.SmartToy) {
                                        val title = pageTitle
                                        val link = shareableUrl(currentUrl, WebVpnUtil)
                                        val needsLogin = WebVpnUtil.isWebVpnUrl(currentUrl)
                                        val send = { text: String -> onAskAgent(com.xjtu.toolbox.agent.AgentForward.webPage(title, link, text, needsLogin)) }
                                        // 带上页面上显示的文字：链接要登录或走 WebVPN 时小岱自己打不开。
                                        // innerText 不含输入框里填的内容（密码之类）
                                        webViewRef?.evaluateJavascript(PAGE_TEXT_JS) { raw ->
                                            send(runCatching { com.xjtu.toolbox.util.AppJson.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(""))
                                        } ?: send("")
                                    }
                                    // 校内站在校外打不开：换成网关地址重开，网关会话由网页自己走统一认证
                                    if (!WebVpnUtil.isWebVpnUrl(currentUrl)) option("通过 WebVPN 打开", icon = Icons.Default.VpnKey) {
                                        webViewRef?.loadUrl(WebVpnUtil.toVpnUrl(currentUrl))
                                    }
                                    option("在外部浏览器打开", icon = Icons.AutoMirrored.Filled.OpenInNew) {
                                        runCatching {
                                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(shareableUrl(currentUrl, WebVpnUtil))))
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

                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean =
                            view.recoverFromRendererGone {
                                webViewRef = null
                                android.widget.Toast.makeText(context, "网页崩溃了，已关闭", android.widget.Toast.LENGTH_SHORT).show()
                                onBack()
                            }

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
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            view?.evaluateJavascript(WebBackBridge.HOOK_JS, null)
                            url?.let { currentUrl = it }
                            view?.let { WebViewNightMode.apply(it, darkState.value) }
                            if (desktopState.value && url != null && !isAuthHop(url)) view?.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
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
                            if (request.isForMainFrame && url.toHttpUrlOrNull()?.let(WebVpnUtil::isLoginLanding) == true &&
                                webVpnAutoLogins < 2
                            ) {
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
                            openExternalScheme(context, url, view)
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
                                    when {
                                        isCmsOneShotDownload(u) -> {
                                            val name = URLUtil.guessFileName(u, null, null)
                                            saveHttpDownload.value(u, name, parent.settings.userAgentString)
                                        }
                                        u.startsWith("http://") || u.startsWith("https://") -> parent.loadUrl(u)
                                        // loadUrl 不经过 shouldOverrideUrlLoading，自定义 scheme 在这里直接交给系统
                                        else -> openExternalScheme(context, u, parent)
                                    }
                                    tmp.destroy()
                                    return true
                                }
                            }
                            transport.webView = tmp
                            resultMsg.sendToTarget()
                            return true
                        }

                        /** 网页自己调 `window.close()`（「关闭」「完成」一类按钮）：照着关掉浏览器。 */
                        override fun onCloseWindow(window: WebView?) {
                            if (window === webViewRef) onBackState.value()
                        }

                        /**
                         * 网页点了 `<input type="file">`。不覆写这一条，WebView 默认什么都不做，
                         * 页面上看起来就是「按钮坏了」。
                         */
                        override fun onShowFileChooser(
                            view: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            val callback = filePathCallback ?: return false
                            // 上一回没接完的先作废，否则那一页会一直等上一次的结果
                            pendingFilePick.value?.onReceiveValue(null)
                            pendingFilePick.value = callback
                            // 选整个文件夹（webkitdirectory）：系统选择器给不出网页能用的一组文件，说清楚
                            if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_FOLDER) {
                                pendingFilePick.value = null
                                callback.onReceiveValue(null)
                                Toast.makeText(context, "这个网页想选整个文件夹，暂不支持", Toast.LENGTH_SHORT).show()
                                return true
                            }
                            if (runCatching { filePicker.launch(webFileChooserIntent(fileChooserParams)) }.isFailure) {
                                pendingFilePick.value = null
                                callback.onReceiveValue(null)
                                Toast.makeText(context, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }
                    }

                    webViewRef = this
                    addJavascriptInterface(WebBackBridge(this, navigateBack), WebBackBridge.NAME)

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
                // 关页面时还挂着一个没回来的选择器回调就作废它，别留悬空引用
                pendingFilePick.value?.onReceiveValue(null)
                pendingFilePick.value = null
                view.releaseSafely(listOf(WebBackBridge.NAME))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        )
    }
}

/** 默认按桌面版打开的站点：电子凭证平台的表单只按桌面宽度排，手机宽度下文字挤成竖排。 */
private val DESKTOP_HOSTS = listOf("dzpz.xjtu.edu.cn")

/** 把 viewport 改成 1280 宽；配合 useWideViewPort / loadWithOverviewMode 缩到整屏，可双指放大。 */
private const val DESKTOP_VIEWPORT_JS = """(function(){var m=document.querySelector('meta[name=viewport]');
if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
m.content='width=1280';})()"""

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

/** 网页要退 [requested] 步：历史里够退就照退，不够（直接打开的那一页上点了网页的返回）返回 null，由调用方关掉浏览器。 */
private fun backStepsOrNull(web: WebView, requested: Int): Int? =
    requested.takeIf { web.copyBackForwardList().currentIndex - it >= 0 }

/**
 * 接住网页自己的「返回」。
 *
 * 学堂 h5 这类单页应用左上角的返回调的是 `history.back()`；从 App 直接打开某一页（比如作业提交页）时
 * 前面没有历史，浏览器内核什么都不做，用户就卡在那一页、只能点顶栏的关闭。这里把
 * `history.back()` / `history.go(-n)` 换成问原生要不要退：有历史就照常退（同文档的 pushState 历史也在
 * WebView 的后退列表里，退回去一样触发 popstate），没有就关掉浏览器——和系统返回键同一条规则。
 *
 * 这个接口对浏览器里打开的任何网页都可见，所以只给「退几步」这一个能力，不传回任何数据。
 * `@JavascriptInterface` 跑在 JS 线程上，要 post 回主线程再碰 WebView 和 Compose 状态。
 */
internal class WebBackBridge(
    private val web: WebView,
    private val navigateBack: (Int) -> Unit,
) {
    @JavascriptInterface
    fun back(steps: Int) {
        val n = steps.coerceIn(1, MAX_STEPS)
        web.post { navigateBack(n) }
    }

    companion object {
        const val NAME = "XjtuToolboxBack"
        private const val MAX_STEPS = 50

        /** 每页加载完挂一次；同一文档里重复执行不会重复包装。 */
        const val HOOK_JS = """(function(){var b=window.XjtuToolboxBack,h=window.history;
if(!b||!h||h.__xtbBack)return;h.__xtbBack=true;var go=h.go.bind(h);
h.back=function(){b.back(1);};
h.go=function(n){n=Number(n)||0;if(n<0)b.back(-n);else go(n);};})()"""
    }
}

/**
 * 登录链上的一跳：统一认证、开放平台 OAuth、各站自己的 CAS / OAuth 入口和带一次性凭据的回调。
 * 后退跳过它们：退到带 `ticket` / `code` 的回调上会把用过的凭据再交一次，站点只会报错。
 */
internal fun isAuthHop(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    val params = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }
    return host == "login.xjtu.edu.cn" || host == "cas.xjtu.edu.cn" ||
        "ticket" in params || ("code" in params && "state" in params) ||
        AUTH_PATH.containsMatchIn(uri.rawPath.orEmpty().lowercase())
}

/** `/cas/login`、`/openplatform/oauth/`、`xjtuCASLogin`、`OAuthPage`、`cas-entrance.jsp`、`/web/cas/` 这类路径。 */
private val AUTH_PATH = Regex("""login|oauth|/cas([/_.-]|$)|/sso([/_.-]|$)""")

/**
 * 网页要拉起别的 App（`intent://`、`weixin://`、`alipays://` 这类支付或登录回调）。
 * `intent://` 按 Intent URI 解析，没装对应 App 时退回它给的 `browser_fallback_url`；
 * 只放行可浏览的隐式 Intent，网页不能借此指定组件拉起本机任意页面。
 */
private fun openExternalScheme(context: android.content.Context, url: String, web: WebView?) {
    val intent = runCatching {
        if (url.startsWith("intent:", ignoreCase = true)) android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
        else android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
    }.getOrNull() ?: return
    intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        val fallback = intent.getStringExtra("browser_fallback_url")
        if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) web?.loadUrl(fallback)
        else Toast.makeText(context, "没有可以打开这个链接的应用", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
    }
}

/** 顶栏第二行显示的域名：WebVPN 代理地址换回原站域名，去掉 www.。 */
private fun displayHost(url: String): String {
    val real = shareableUrl(url, WebVpnUtil)
    return runCatching { URI(real).host.orEmpty() }.getOrDefault("").removePrefix("www.")
}

/**
 * 把网页的 `<input type="file" accept=... multiple>` 折成系统选择器认得的 intent。
 *
 * `accept` 里既有 MIME（`application/pdf`）也有扩展名（`.pdf`），思源学堂的作业提交页就是混着写的。
 * 扩展名原样塞进 `EXTRA_MIME_TYPES` 会让选择器一个文件都不显示，所以统一折成 MIME；
 * 折不出来就退回不限类型——让用户至少能选到，比点了没反应好。
 */
private fun webFileChooserIntent(params: WebChromeClient.FileChooserParams?): android.content.Intent {
    val mimes = acceptTypesToMimeTypes(params?.acceptTypes)
    return android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE)
        // 只有一个类型时用 type，多个类型用 type=*/* + EXTRA_MIME_TYPES，系统文件选择器认这套
        type = if (mimes.size == 1) mimes[0] else "*/*"
        if (mimes.size > 1) putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimes)
        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
}

/**
 * `accept` 属性 → 一组 MIME；空或通配符一律当「不限类型」。
 *
 * [mimeOfExtension] 默认走 [MimeTypeMap]；留成参数是为了单测能在纯 JVM 上验折算规则
 * （`MimeTypeMap` 在单元测试里拿不到真实现）。
 */
internal fun acceptTypesToMimeTypes(
    acceptTypes: Array<String>?,
    mimeOfExtension: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): Array<String> {
    if (acceptTypes.isNullOrEmpty()) return arrayOf("*/*")
    val mimes = linkedSetOf<String>()
    for (raw in acceptTypes) {
        val type = raw.trim().lowercase()
        when {
            type.isEmpty() -> Unit
            type == "*/*" || type == "*" -> return arrayOf("*/*")
            // `image/*` 这类通配 MIME 系统选择器直接认，原样留着
            type.contains('/') -> mimes += type
            // 扩展名：`.pdf` → `application/pdf`
            type.startsWith(".") ->
                mimeOfExtension(type.substring(1))?.let { mimes += it }
        }
    }
    return if (mimes.isEmpty()) arrayOf("*/*") else mimes.toTypedArray()
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

/**
 * 把当前网页分享出去：纯文字，第一行网页标题，第二行链接。
 *
 * 分享 WebVPN 代理出来的地址时换回原始地址：`webvpn.xjtu.edu.cn/https/7772…` 这种链接
 * 对方没有网关会话打不开，也看不出是哪个网站。原始地址在校外打不开，至少看得懂。
 * 标题是 WebView 还没拿到时的占位「浏览器」，或者就是网址本身时，只发链接。
 */
internal fun shareWebPage(context: android.content.Context, title: String, url: String) {
    val link = shareableUrl(url, WebVpnUtil)
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

/** 读当前页显示的文字。多取一些留给 [com.xjtu.toolbox.agent.AgentForward.webPage] 判断是否截断。 */
private const val PAGE_TEXT_JS = "document.body ? document.body.innerText.slice(0, 20000) : ''"
