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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.auth.CasSiteSession
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.browser.syncCookiesToWebView
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
    // CAS ticket 是打开移动交大的关键入口。没有 superapp 域 cookie 时直接加载 HOME 会被踢回 CAS。
    val launchUrl = run {
        val stored = site.localToken["launch_url"].orEmpty()
        Log.d(TAG, "local launch_url hasTicket=${stored.contains("ticket=")} len=${stored.length}")
        if (stored.contains("superapp.xjtu.edu.cn") && stored.contains("ticket=")) {
            stored
        } else {
            com.xjtu.toolbox.auth.SuperAppLogin.LOGIN_URL
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val cookieDomains = remember {
        listOf(
            "superapp.xjtu.edu.cn",
            "transaction.xjtu.edu.cn",
            "transaction-service.xjtu.edu.cn",
            "message-service.xjtu.edu.cn",
            "reservation.xjtu.edu.cn",
            "reservation-service.xjtu.edu.cn",
            "lms-h5.xjtu.edu.cn",
            "identity1.xjtu.edu.cn",
            "api-org.tronclass.com.cn",
            "tyxylp.xjtu.edu.cn",
            "login.xjtu.edu.cn",
            "cas.xjtu.edu.cn",
        )
    }

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
                    color = MiuixTheme.colorScheme.surfaceVariant,
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
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                syncCookiesToWebView(site, cookieDomains)
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
                            syncCookiesToWebView(site, cookieDomains + listOfNotNull(url?.hostOrNull()))
                            loading = true
                            updateNavigation(view)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "onPageFinished: $url cookies=${url?.let { CookieManager.getInstance().getCookie(it) }?.take(200)}")
                            syncCookiesToWebView(site, cookieDomains + listOfNotNull(url?.hostOrNull()))
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
                                        syncCookiesToWebView(site, cookieDomains + listOfNotNull(finalUrl.hostOrNull()))
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
                    Log.d(TAG, "loadUrl: $launchUrl")
                    loadUrl(launchUrl)
                }
            },
            update = { webView = it }
        )
    }
}

private fun String.isCasLoginUrl(): Boolean =
    startsWith("https://login.xjtu.edu.cn/cas/login", ignoreCase = true) &&
        contains("service=", ignoreCase = true)

private fun String.hostOrNull(): String? =
    runCatching { Uri.parse(this).host?.lowercase() }.getOrNull()
