package com.xjtu.toolbox.superapp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.auth.SuperAppLogin
import com.xjtu.toolbox.browser.syncCookiesToWebView
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MobileJiaodaScreen(
    login: SuperAppLogin,
    onClose: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

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
                syncCookiesToWebView(
                    login,
                    listOf(
                        "superapp.xjtu.edu.cn",
                        "transaction-service.xjtu.edu.cn",
                        "message-service.xjtu.edu.cn",
                        "reservation-service.xjtu.edu.cn",
                        "tyxylp.xjtu.edu.cn",
                    )
                )
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
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            updateNavigation(view)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
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
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.toFloat()
                        }
                    }
                    webView = this
                    loadUrl(SuperAppLogin.LOGIN_URL)
                }
            },
            update = { webView = it }
        )
    }
}
