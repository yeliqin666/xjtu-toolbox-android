package com.xjtu.toolbox.game.merge

import com.xjtu.toolbox.ui.components.BackButton
import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.util.releaseSafely
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.InputStream

// 游戏本体资源的根目录，只有这个前缀下的文件允许被 WebView 加载。
private const val ASSET_ROOT = "games/merge/"
private const val ENTRY_URL = "file:///android_asset/${ASSET_ROOT}index.html"

/**
 * 「合成西交大」小游戏的 WebView 壳。
 *
 * 游戏本体（HTML/JS/Matter.js）在 assets/games/merge/ 下，纯本地资源，
 * 全程不联网——WebView 里唯一允许通过的请求就是 file:///android_asset/games/merge/
 * 前缀下的文件，其余一律在 shouldInterceptRequest 里拦掉。
 *
 * 关于「用什么拦截非 assets 请求」：规格建议用 androidx.webkit 的
 * WebViewAssetLoader，但这个库当前不在项目依赖里，而按本次改动的范围约束
 * 又不允许碰 build.gradle.kts / libs.versions.toml。所以这里改用等价的
 * 土办法——直接用 `file:///android_asset/` 加载页面（Android 对这个路径的
 * 访问不受 allowFileAccess 影响，官方文档明确写了 assets/资源不受该开关限制），
 * 并在 shouldInterceptRequest 里白名单校验每个子请求都落在游戏目录下，
 * 效果和 WebViewAssetLoader 一致：既能离线加载，又能挡掉任何跑偏的请求。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MergeGameScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // 页面不可见时暂停 JS 侧的物理循环，避免在后台空跑耗电。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewRef.value?.evaluateJavascript("window.pauseGame && window.pauseGame();", null)
                Lifecycle.Event.ON_RESUME -> webViewRef.value?.evaluateJavascript("window.resumeGame && window.resumeGame();", null)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "合成西交大",
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = {
                    BackButton(onBack)
                }
            )
        }
    ) { padding ->
        // 深浅色经 URL 参数交给页面：WebView 的 prefers-color-scheme 不一定跟 App 主题走。
        // 底色和页面顶部渐变一致，加载那一下不白闪。
        val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(if (dark) 0xFF0E141D.toInt() else 0xFFEAF1FF.toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true // 本地最高分存在 localStorage 里
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false

                    // JS -> Kotlin 分数回传：游戏结束这一刻发生一次，事件驱动，
                    // 比 evaluateJavascript 轮询查状态更直接也更省资源。
                    addJavascriptInterface(MergeGameBridge(ctx), "AndroidGameBridge")

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (!url.startsWith("file:///android_asset/$ASSET_ROOT")) {
                                // 非游戏目录下的一切请求（理论上不会有，因为页面没有外链）一律拦掉。
                                return blockedResponse()
                            }
                            return null // 交给 WebView 默认的 android_asset 加载逻辑
                        }
                    }

                    webViewRef.value = this
                    loadUrl(if (dark) "$ENTRY_URL?dark=1" else ENTRY_URL)
                }
            },
            onRelease = { view ->
                if (webViewRef.value === view) webViewRef.value = null
                view.releaseSafely(listOf("AndroidGameBridge"))
            },
            // 以前没套 padding，画面从屏幕顶端画起，上面一截压在顶栏底下
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/** 一个空的 404 响应，用来挡掉不在白名单里的请求。 */
private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), EmptyInputStream)

private object EmptyInputStream : InputStream() {
    override fun read(): Int = -1
}

private class MergeGameBridge(private val context: Context) {
    @JavascriptInterface
    fun submitScore(score: Int) {
        if (score <= 0) return
        GameStore.submitScore(context, GameIds.MERGE, score)
    }

    @JavascriptInterface
    fun bestScore(): Int = GameStore.bestScore(context, GameIds.MERGE)
}
