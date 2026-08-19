package com.xjtu.toolbox.superapp

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.auth.CasSiteSession
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "MobileJiaoda"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MobileJiaodaScreen(
    site: SiteSession,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val loginState = LocalAppLoginState.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.d(
            TAG,
            "location permission result fine=${result[Manifest.permission.ACCESS_FINE_LOCATION]} coarse=${result[Manifest.permission.ACCESS_COARSE_LOCATION]}"
        )
    }
    // 相机：页面内有扫码/核验功能。与定位分开申请，避免一次弹两个权限吓到用户；
    // 真正的授予时机在 onPermissionRequest（网页调 getUserMedia 时），这里只是提前拿到系统权限。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.d(TAG, "camera permission granted=$granted") }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }
    // superapp.xjtu.edu.cn 是纯前端 SPA，认证靠 URL 里的 ticket。入口不再预先 ensureSite，
    // 只在这里登录一次，避免先吃掉一张票再 force 登第二遍。
    var launchUrl by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(site, loginAttempt) {
        launchUrl = null
        loginError = null
        try {
            val creds = loginState.sessionManager?.credentials
                ?: error("未配置校园账号凭据")
            withContext(Dispatchers.IO) {
                if (site.currentAccessMode == com.xjtu.toolbox.auth.AccessMode.WEBVPN) {
                    loginState.sessionManager?.ensureWebVpnLogin()
                }
                site.ensureLogin(creds.first, creds.second, force = true, userInitiated = true)
            }
            val fresh = site.localToken["launch_url"].orEmpty()
            val resolved = fresh.takeIf {
                com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(it, "superapp.xjtu.edu.cn") && it.contains("ticket=")
            } ?: throw IllegalStateException("移动交大未返回可用的登录票据")
            if (com.xjtu.toolbox.util.WebVpnUtil.isWebVpnUrl(resolved)) {
                com.xjtu.toolbox.browser.syncCookiesToWebView(
                    loginState.webVpnClientOrNull,
                    listOf("webvpn.xjtu.edu.cn")
                )
            }
            launchUrl = resolved
            Log.d(TAG, "fresh launch_url hasTicket=${resolved.contains("ticket=")} viaWebVpn=${com.xjtu.toolbox.util.WebVpnUtil.isWebVpnUrl(resolved)}")
        } catch (e: Exception) {
            Log.e(TAG, "force re-login for launch_url failed", e)
            loginError = e.message?.takeIf { it.isNotBlank() } ?: "移动交大登录失败，请重试"
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            Column {
                SmallTopAppBar(
                    title = "移动交大",
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = canGoBack,
                            onClick = { webView?.goBack() }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                        }
                        IconButton(
                            enabled = canGoForward,
                            onClick = { webView?.goForward() }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                        }
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
                if (loading) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        height = androidx.compose.ui.unit.Dp.Hairline,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            backgroundColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        val currentLaunchUrl = launchUrl
        val currentError = loginError
        if (currentError != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                ErrorState(
                    message = currentError,
                    onRetry = { loginAttempt += 1 },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@Scaffold
        }
        if (currentLaunchUrl == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingState(message = "正在登录移动交大...")
            }
            return@Scaffold
        }
        // WebVPN cookie 已在上面 launchUrl 解析完成前同步好——不能放在这里，
        // 见那里的注释：此处的 LaunchedEffect 晚于 AndroidView factory 里的 loadUrl。
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setGeolocationEnabled(true)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            Log.d(TAG, "onPageStarted: $url")
                            loading = true
                            updateNavigation(view)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "onPageFinished: $url")
                            loading = false
                            updateNavigation(view)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            updateNavigation(view)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.isCasLoginUrl()) {
                                Log.d(TAG, "intercept CAS login for child service: $url")
                                view?.stopLoading()
                                scope.launch {
                                    val finalUrl = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val credentials = loginState.sessionManager?.credentials
                                                ?: error("未配置校园账号凭据")
                                            val casSite = site as? CasSiteSession
                                                ?: error("移动交大会话不支持 CAS 接力")
                                            casSite.casHandoffUrl(
                                                loginUrl = url,
                                                username = credentials.first,
                                                password = credentials.second,
                                            ).takeIf { it.isNotBlank() } ?: url
                                        }.onFailure {
                                            Log.e(TAG, "CAS child service handoff failed", it)
                                        }.getOrDefault(url)
                                    }
                                    withContext(Dispatchers.Main.immediate) {
                                        Log.d(TAG, "CAS handoff finalUrl=$finalUrl")
                                        view?.loadUrl(finalUrl)
                                    }
                                }
                                return true
                            }
                            if (url.startsWith("http://") || url.startsWith("https://")) return false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            return true
                        }

                        private fun updateNavigation(view: WebView?) {
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        /**
                         * 网页调 getUserMedia 时 WebView 会走到这里。**不实现这个回调，
                         * 网页侧的相机请求会被直接拒绝**，哪怕 App 已经拿到了系统相机权限。
                         * 只放行相机与录音，其余资源（如 PROTECTED_MEDIA_ID）一律不给。
                         */
                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            val req = request ?: return
                            val wanted = req.resources.filter {
                                it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                    it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            }.toTypedArray()
                            Log.d(TAG, "onPermissionRequest: ${req.resources.joinToString()} -> granting ${wanted.joinToString()}")
                            if (wanted.isEmpty()) {
                                req.deny()
                                return
                            }
                            val hasCamera = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasCamera &&
                                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted
                            ) {
                                // 系统权限还没拿到：先申请，本次请求只能拒绝，用户授权后重试即可
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                req.deny()
                                return
                            }
                            req.grant(wanted)
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            val fineGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            val coarseGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            val allowed = fineGranted || coarseGranted
                            Log.d(TAG, "geolocation prompt origin=$origin allowed=$allowed")
                            if (!allowed) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            }
                            callback?.invoke(origin, allowed, false)
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.toFloat()
                        }
                    }
                    webView = this
                    Log.d(TAG, "loadUrl: $currentLaunchUrl")
                    loadUrl(currentLaunchUrl)
                }
            },
            update = { webView = it }
        )
    }
}

/**
 * 子服务跳 CAS 时要拦下来自己接力。
 *
 * 走 WebVPN 时这条 URL 是 `https://webvpn.xjtu.edu.cn/https/{加密域名}/cas/login?service=...`，
 * 域名段被 AES 加密，明文 `login.xjtu.edu.cn` 根本不出现——原来的字符串前缀匹配一律落空，
 * 于是接力从不触发，WebView 就直接停在 CAS/网关登录页（真机现象：移动交大打不开、
 * 内部一半功能不可用）。这里先还原成原始 URL 再判断，两种模式都认得出来。
 */
private fun String.isCasLoginUrl(): Boolean {
    val plain = com.xjtu.toolbox.util.WebVpnUtil.getOriginalUrl(this) ?: this
    return plain.startsWith("https://login.xjtu.edu.cn/cas/login", ignoreCase = true) &&
        plain.contains("service=", ignoreCase = true)
}
