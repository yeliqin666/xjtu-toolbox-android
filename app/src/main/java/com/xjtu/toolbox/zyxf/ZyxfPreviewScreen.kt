package com.xjtu.toolbox.zyxf

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.util.releaseSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 和资料站前端一致：超过 20MB 在手机上先提醒一句。 */
private const val LARGE_FILE_BYTES = 20L * 1024 * 1024

/**
 * 资料预览。
 *
 * doc / ppt / xls / pdf 的**解析和渲染完全借用阿里云 IMM WebOffice**——资料站自己
 * 也是这么做的（见其前端 `Preview/OfficeViewer.jsx`）。安卓没有原生 Office 渲染器，
 * 这一层注定跑在 WebView 里，所以不自己造，只把壳换成我们的。
 *
 * 没有去嵌资料站的页面本身：它的预览是 BrowsePage 里的一个弹层，没有可直达的路由，
 * 靠模拟点击既脆弱、又会把整套 SPA（侧边栏、知识图谱、聊天）一起拖进来。
 *
 * 版式照搬它那个浮层的结构（`Preview/index.jsx`）：整屏浮层 + 一行紧凑标题栏
 * （文件名单行省略、下载、关闭），正文占满剩余空间。**加载态由 Compose 画**，
 * 不在壳页里放占位块——之前那版把 `正在加载预览…` 写死在 HTML 里并留了 40vh 的内边距，
 * SDK 渲染出来之后它还杵在上面，把正文顶下去一大截。
 */
/**
 * 窄屏的文件预览：接近全高的底部弹窗。
 *
 * 标题就是文件名，下载放在标题右边，关掉靠下拉、点外面或返回——不再有自己那条
 * 「文件名 / 下载 / ×」标题栏。以前是全屏 Dialog 加这条栏，Dialog 打开时状态栏内边距
 * 晚一帧才到位，这条栏会跳一下，看着就是「顶栏闪了」。
 *
 * [file] 为 null 时弹窗收起。弹窗要一直留在组合里、由 show 从 false 变 true 来打开
 * （miuix 弹窗的约定，条件式创建会没有打开动画）；收起动画期间保留上一次的文件，内容不会先塌掉。
 * 预览正文在 WebView 里，WebView 不参与嵌套滚动，所以在正文里滑动只翻页，不会把弹窗拖下来；
 * 拖动条和标题那一截才负责下拉关闭。
 */
@Composable
fun ZyxfPreviewSheet(
    file: ZyxfApi.Entry?,
    onDismiss: () -> Unit,
    onDownload: (ZyxfApi.Entry) -> Unit,
) {
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ZyxfApi.Entry?>(null) }
    if (file != null) last = file
    val shown = last
    if (file != null) BackHandler { onDismiss() }
    val sheetHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
    top.yukonga.miuix.kmp.overlay.OverlayBottomSheet(
        show = file != null,
        title = shown?.name,
        endAction = {
            shown?.let { f ->
                IconButton(onClick = { onDownload(f) }) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        },
        // 默认左右 24dp 内边距，预览是整页文档，贴边显示得更多
        insideMargin = androidx.compose.ui.unit.DpSize(0.dp, 0.dp),
        onDismissRequest = onDismiss,
    ) {
        Box(Modifier.fillMaxWidth().height(sheetHeight)) {
            if (shown != null) {
                PreviewContent(
                    fileId = shown.id,
                    fileName = shown.name,
                    sizeBytes = shown.sizeBytes,
                    onBack = onDismiss,
                    onDownload = { onDownload(shown) },
                    embedded = true,
                    showHeader = false,
                )
            }
        }
    }
}

/**
 * 预览正文。窄屏下由 [ZyxfPreviewSheet] 包进底部弹窗，
 * 宽屏下直接嵌在列表旁边的右栏里（见 ZyxfBrowseScreen）。
 *
 * @param embedded 嵌在页面里（分屏右栏、底部弹窗）而不是自带窗口。
 *   此时状态栏已经由宿主顶栏让过，再让一次就是白空一条；
 *   返回键交给宿主（底部弹窗自己关，分屏右栏不算一层），这里不再单独拦。
 */
@Composable
internal fun PreviewContent(
    fileId: Int,
    fileName: String,
    sizeBytes: Long,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    embedded: Boolean = false,
    /** false：不画自己的「文件名 / 下载 / ×」标题栏（底部弹窗用它自己的标题和下载按钮）。 */
    showHeader: Boolean = true,
) {
    var token by remember(fileId) { mutableStateOf<ZyxfApi.Weboffice?>(null) }
    var ready by remember(fileId) { mutableStateOf(false) }
    var error by remember(fileId) { mutableStateOf<String?>(null) }

    LaunchedEffect(fileId) {
        val got = withContext(Dispatchers.IO) { ZyxfApi.webofficeToken(fileId) }
        if (got == null) error = "这个文件暂时无法在线预览，可以下载后用本机应用打开。"
        else token = got
    }

    if (!embedded) BackHandler { onBack() }

    Surface(color = MiuixTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ),
        ) {
            // 标题栏：文件名一行放不下就省略，不要换行成两行大字。
            if (showHeader) Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fileName,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (showHeader) HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.12f))

            if (sizeBytes > LARGE_FILE_BYTES) {
                Text(
                    "文件较大（${sizeBytes / 1024 / 1024}MB），在手机上加载可能偏慢。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.Center,
            ) {
                val t = token
                if (error != null) {
                    Text(
                        error!!,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                } else if (t != null) {
                    WebOfficeView(
                        fileId = fileId,
                        weboffice = t,
                        onReady = { ready = true },
                        onError = { error = it },
                    )
                }
                // 盖在 WebView 上，SDK 挂载完成后撤掉。放在 Compose 这边，
                // 撤不撤由原生说了算，不会出现"内容出来了、提示还在"。
                if (error == null && !ready) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "正在加载预览…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebOfficeView(
    fileId: Int,
    weboffice: ZyxfApi.Weboffice,
    onReady: () -> Unit,
    onError: (String) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // WebOffice 会在自己的 iframe 里再开窗口，不允许的话正文是空白的。
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // 只跟 WebOffice 自己的跳转，其余一律不跟——
                        // 这是个预览器，不该变成能上任意网站的浏览器。
                        val host = request?.url?.host.orEmpty()
                        return !(host.isEmpty() ||
                            host.endsWith("aliyuncs.com") ||
                            host.endsWith("alicdn.com") ||
                            host.endsWith("aliyun.com"))
                    }
                }

                val bridge = Bridge(fileId, this, onReady, onError)
                tag = bridge
                addJavascriptInterface(bridge, BRIDGE_NAME)

                loadDataWithBaseURL(
                    "https://zyxf.top/",
                    shellHtml(weboffice),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = { view ->
            (view.tag as? Bridge)?.released = true
            view.releaseSafely(listOf(BRIDGE_NAME))
        },
    )
}

private const val BRIDGE_NAME = "ZyxfBridge"

/**
 * 壳页与原生之间的通道。
 *
 * `@JavascriptInterface` 的方法跑在 WebView 的 JS 线程上，回调 Compose 状态必须
 * post 回主线程，否则是跨线程改状态。
 */
private class Bridge(
    private val fileId: Int,
    private val webView: WebView,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    /** WebView 已释放。JS 线程上晚到的回调不再碰已离开组合的 Compose 状态，也不再联网续期。 */
    @Volatile
    var released = false

    @JavascriptInterface
    fun ready() {
        if (released) return
        webView.post { if (!released) onReady() }
    }

    @JavascriptInterface
    fun failed(message: String) {
        if (released) return
        webView.post { if (!released) onError(message.ifBlank { "预览加载失败" }) }
    }

    /**
     * 续期。SDK 在 access token 快过期时回调，我们原生去打资料站的 refresh 接口——
     * 凭证不经过壳页的 JS 生成，壳页只是渲染容器。
     */
    @JavascriptInterface
    fun refresh(accessToken: String, refreshToken: String): String {
        if (released) return "{}"
        val next = runBlocking(Dispatchers.IO) {
            ZyxfApi.webofficeRefresh(fileId, accessToken, refreshToken)
        } ?: return "{}"
        return JSONObject().apply {
            put("token", next.token)
            put("refresh_token", next.refreshToken)
        }.toString()
    }
}

/**
 * 壳页。照资料站 `OfficeViewer.jsx` 的用法调同一套官方 SDK：
 * `aliyun.config({ url, mount })` → `setToken({ token })`，并挂 refreshToken 回调。
 *
 * 页面里**没有任何自己的 UI**——加载中、失败都回调给原生去画，
 * 这样不会出现 HTML 占位块和真正内容打架。
 */
private fun shellHtml(wb: ZyxfApi.Weboffice): String {
    val url = JSONObject.quote(wb.url)
    val token = JSONObject.quote(wb.token)
    val refresh = JSONObject.quote(wb.refreshToken)
    return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  html, body { margin:0; padding:0; height:100%; background:transparent; overflow:hidden; }
  #root { width:100%; height:100%; }
</style>
</head>
<body>
<div id="root"></div>
<script src="https://g.alicdn.com/IMM/office-js/1.1.19/aliyun-web-office-sdk.min.js"></script>
<script>
(function () {
  function fail(msg) { try { window.ZyxfBridge.failed(String(msg)); } catch (e) {} }
  if (!window.aliyun || !window.aliyun.config) { fail('预览组件加载失败，请检查网络'); return; }
  try {
    var ins = window.aliyun.config({
      url: $url,
      mount: document.getElementById('root'),
      commonOptions: {
        // 关掉 WebOffice 自带的头部栏。它上面是文件名、下载、账号、菜单，
        // 和我们自己那条标题栏重复，而且它在 SDK 初始化过程中会先出现再重排，
        // 看起来就是"顶栏闪一下"。官方配置项，不需要用 CSS 去盖别人的 DOM。
        // 只关 header 不关 topArea：工具栏里的翻页和缩放是真有用的，
        // isShowTopArea:false 会把它一起带走。
        isShowHeader: false,
      },
    });
    ins.setToken({ token: $token });
    var refreshToken = $refresh;
    if (ins.on) {
      ins.on('error', function () { fail('预览出错，可下载后查看'); });
      ins.on('ready', function () { try { window.ZyxfBridge.ready(); } catch (e) {} });
    }
    // 有的版本不派发 ready：兜一个超时，避免转圈转到天荒地老。
    setTimeout(function () { try { window.ZyxfBridge.ready(); } catch (e) {} }, 2500);
    if (ins.refreshToken) {
      ins.refreshToken(function () {
        try {
          var raw = window.ZyxfBridge.refresh($token, refreshToken);
          var next = JSON.parse(raw || '{}');
          if (next.refresh_token) refreshToken = next.refresh_token;
          return next.token ? { token: next.token } : undefined;
        } catch (e) { return undefined; }
      });
    }
  } catch (e) {
    fail('预览初始化失败：' + e);
  }
})();
</script>
</body>
</html>
    """.trimIndent()
}
