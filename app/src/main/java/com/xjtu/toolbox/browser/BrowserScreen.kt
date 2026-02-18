package com.xjtu.toolbox.browser

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.auth.XJTULogin
import java.net.URI

private const val TAG = "BrowserScreen"

/**
 * 判断 URL 是否是 XJTU 通知详情页（包括 WebVPN 代理后的 URL）
 * 匹配: dean.xjtu.edu.cn/info/xxx, gs.xjtu.edu.cn/info/xxx, phy.xjtu.edu.cn/info/xxx, se.xjtu.edu.cn/info/xxx
 * 以及 webvpn.xjtu.edu.cn/.../info/xxx
 */
private fun isXjtuNotificationDetail(url: String): Boolean {
    val lower = url.lowercase()
    // 直连 URL
    if (lower.matches(Regex("""https?://(dean|gs|phy|se)\.xjtu\.edu\.cn/info/\d+/\d+\.htm.*"""))) return true
    // WebVPN 代理 URL（URL 中包含 /info/ 且在 webvpn.xjtu.edu.cn 下）
    if (lower.contains("webvpn.xjtu.edu.cn") && lower.contains("/info/")) return true
    return false
}

/**
 * 在页面开始加载时立即注入的 JS — 隐藏 body 避免闪烁
 * 配合 READER_MODE_JS 在 onPageFinished 中恢复显示
 */
private const val HIDE_BODY_JS = """
(function(){
  var s = document.createElement('style');
  s.id = '__reader_hide';
  s.textContent = 'html,body{visibility:hidden!important;overflow:hidden!important;}';
  (document.head || document.documentElement).appendChild(s);
})();
"""

/**
 * Reader Mode JavaScript — 提取 XJTU 通知页面的正文区域，隐藏导航/侧边栏/页脚
 * 兼容教务处 / 研究生院 / 物理学院等所有使用 VSB CMS 的站点
 * 包含：标题 + 日期元信息 + 正文 + 附件下载区
 */
private const val READER_MODE_JS = """
(function(){
  // 1. 尝试提取正文
  var content = document.getElementById('vsb_content')
              || document.querySelector('.v_news_content')
              || document.querySelector('.art-body')
              || document.querySelector('.list_rnr')
              || document.querySelector('.wp_articlecontent')
              || document.querySelector('.entry-content')
              || document.querySelector('.news_content');
  if(!content) {
    var h = document.getElementById('__reader_hide');
    if(h) h.remove();
    document.body.style.visibility = 'visible';
    document.body.style.overflow = '';
    return;
  }

  // 2. 尝试提取标题
  var titleEl = document.querySelector('.art-title h4')
             || document.querySelector('.art-title h1')
             || document.querySelector('.art-title')
             || document.querySelector('.art-head h1')
             || document.querySelector('.list_rdh h1')
             || document.querySelector('.arti_title')
             || document.querySelector('.wp_articlecontent h1')
             || document.querySelector('h1')
             || document.querySelector('title');
  var title = '';
  if(titleEl) {
    title = titleEl.tagName === 'TITLE' ? titleEl.textContent : (titleEl.querySelector('h4,h1,h2') || titleEl).textContent;
  }
  title = title.replace(/^\s+|\s+$/g, '');

  // 3. 提取作者/日期信息
  var metaEl = document.querySelector('.art-metas')
            || document.querySelector('.arti_metas')
            || document.querySelector('.art-title p.arti_update')
            || document.querySelector('.art-title p')
            || document.querySelector('.art-date')
            || document.querySelector('.list_rdh p');
  var meta = metaEl ? metaEl.textContent.replace(/^\s+|\s+$/g, '') : '';

  // 4. 提取附件 — 多策略
  var attachItems = [];
  var seen = {};

  // 4a: 专用附件容器
  var attachEls = document.querySelectorAll('.v_news_attach, .art-attach, .attach, .fujian, [class*=attach], [class*=fujian], .fileList, .file-list');
  for(var i=0; i<attachEls.length; i++) {
    var links = attachEls[i].querySelectorAll('a[href]');
    for(var j=0; j<links.length; j++) {
      var h2 = links[j].href || '';
      var t = (links[j].textContent || '').replace(/^\s+|\s+$/g, '');
      if(h2 && t && !seen[h2]) { seen[h2]=1; attachItems.push({url:h2, name:t}); }
    }
  }

  // 4b: 正文中指向文档的链接
  var docExts = /\.(pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z|tar|gz|txt|csv|png|jpg|jpeg)(\?.*)?$/i;
  var contentLinks = content.querySelectorAll('a[href]');
  for(var k=0; k<contentLinks.length; k++) {
    var lh = contentLinks[k].href || '';
    var lt = (contentLinks[k].textContent || '').replace(/^\s+|\s+$/g, '');
    if(docExts.test(lh) && !seen[lh]) { seen[lh]=1; attachItems.push({url:lh, name:lt||lh.split('/').pop()}); }
  }

  // 4c: 全页面兜底搜索文档链接
  if(attachItems.length === 0) {
    var allAs = document.querySelectorAll('a[href]');
    for(var m=0; m<allAs.length; m++) {
      var ah = allAs[m].href || '';
      var at = (allAs[m].textContent || '').replace(/^\s+|\s+$/g, '');
      if(docExts.test(ah) && !seen[ah]) { seen[ah]=1; attachItems.push({url:ah, name:at||ah.split('/').pop()}); }
    }
  }

  // 构建附件 HTML
  var attachHtml = '';
  if(attachItems.length > 0) {
    attachHtml = '<div class="reader-attach"><div class="reader-attach-title">\u0001\u2193 附件下载 (' + attachItems.length + ')</div>';
    for(var n=0; n<attachItems.length; n++) {
      var ext = (attachItems[n].url.match(/\.(\w{2,5})(\?.*)?$/)||[])[1]||'file';
      attachHtml += '<a class="reader-attach-item" href="' + attachItems[n].url + '" download>'
        + '<span class="reader-file-type">' + ext.toUpperCase() + '</span>'
        + '<span class="reader-file-name">' + attachItems[n].name + '</span></a>';
    }
    attachHtml += '</div>';
  }

  // 5. 替换 body 为干净阅读视图
  document.body.innerHTML = '<div id="reader-root">'
    + (title ? '<h1 class="reader-title">' + title + '</h1>' : '')
    + (meta  ? '<div class="reader-meta">' + meta + '</div>' : '')
    + '<div class="reader-body">' + content.innerHTML + '</div>'
    + attachHtml
    + '</div>';

  // 6. 注入干净样式
  var s = document.createElement('style');
  s.textContent = [
    'html,body{margin:0;padding:0;background:#FAFAFA;font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;}',
    'body{visibility:visible!important;overflow:auto!important;padding:20px 16px 40px;}',
    '#reader-root{max-width:720px;margin:0 auto;color:#1a1a1a;line-height:1.85;}',
    '.reader-title{font-size:21px;font-weight:700;line-height:1.4;margin:0 0 8px;color:#1a1a1a;}',
    '.reader-meta{color:#666;font-size:13px;margin-bottom:20px;padding-bottom:12px;border-bottom:1px solid #e0e0e0;}',
    '.reader-body{font-size:16px;}',
    '.reader-body img{max-width:100%!important;height:auto!important;border-radius:6px;margin:8px 0;}',
    '.reader-body table{width:100%!important;border-collapse:collapse;margin:12px 0;font-size:14px;}',
    '.reader-body td,.reader-body th{border:1px solid #ddd;padding:8px 10px;}',
    '.reader-body th{background:#f5f5f5;font-weight:600;}',
    '.reader-body p{margin:0.7em 0;}',
    '.reader-body a{color:#005BAA;text-decoration:none;border-bottom:1px solid rgba(0,91,170,0.3);}',
    '.reader-body a:hover{border-bottom-color:#005BAA;}',
    '.reader-attach{margin-top:24px;padding:16px;background:#f0f7ff;border-radius:12px;border:1px solid #d0e4f7;}',
    '.reader-attach-title{font-size:15px;font-weight:600;color:#005BAA;margin-bottom:12px;}',
    '.reader-attach-item{display:flex;align-items:center;padding:10px 12px;margin-bottom:6px;background:#fff;border-radius:8px;text-decoration:none!important;border:none!important;color:#333;box-shadow:0 1px 3px rgba(0,0,0,0.08);}',
    '.reader-attach-item:hover{background:#e8f2ff;}',
    '.reader-file-type{display:inline-block;min-width:36px;padding:2px 6px;text-align:center;font-size:11px;font-weight:700;color:#fff;background:#005BAA;border-radius:4px;margin-right:10px;}',
    '.reader-file-name{font-size:14px;flex:1;word-break:break-all;}',
    '@media(prefers-color-scheme:dark){',
    '  html,body{background:#121212;}',
    '  #reader-root{color:#e0e0e0;}',
    '  .reader-title{color:#e0e0e0;}',
    '  .reader-meta{color:#999;border-bottom-color:#333;}',
    '  .reader-body a{color:#64B5F6;border-bottom-color:rgba(100,181,246,0.3);}',
    '  .reader-body th{background:#222;}',
    '  .reader-body td,.reader-body th{border-color:#444;}',
    '  .reader-attach{background:#1a2a3a;border-color:#2a4a6a;}',
    '  .reader-attach-item{background:#222;color:#e0e0e0;box-shadow:0 1px 3px rgba(0,0,0,0.3);}',
    '}'
  ].join('');
  document.head.appendChild(s);

  // 7. 移除隐藏样式
  var h = document.getElementById('__reader_hide');
  if(h) h.remove();
})();
"""

/**
 * 将 OkHttp CookieJar 中的 cookies 同步到 Android WebView CookieManager
 * 兼容 PersistentCookieJar 和 java.net.CookieManager
 */
private fun syncCookiesToWebView(login: XJTULogin?) {
    if (login == null) return
    val webCookieManager = android.webkit.CookieManager.getInstance()
    webCookieManager.setAcceptCookie(true)

    try {
        val jar = login.client.cookieJar
        if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
            // 使用 PersistentCookieJar：向常见域名查询 cookies
            val domains = listOf(
                "login.xjtu.edu.cn", "cas.xjtu.edu.cn", "org.xjtu.edu.cn",
                "jwxt.xjtu.edu.cn", "ywtb.xjtu.edu.cn", "bkkq.xjtu.edu.cn",
                "card.xjtu.edu.cn", "rg.lib.xjtu.edu.cn", "jwapp.xjtu.edu.cn",
                "webvpn.xjtu.edu.cn"
            )
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
                        if (cookie.httpOnly) append("; HttpOnly")
                    }
                    webCookieManager.setCookie("https://$domain/", cookieStr)
                    count++
                }
            }
            webCookieManager.flush()
            Log.d(TAG, "Cookie sync (PersistentCookieJar) complete, total: $count")
        } else {
            // 兼容旧版 java.net.CookieManager
            val store = login.cookieManager.cookieStore
            val cookies = store.cookies
            for (cookie in cookies) {
                val uri = store.urIs.firstOrNull { store.get(it).contains(cookie) }
                val domain = cookie.domain ?: uri?.host ?: continue
                val cookieStr = buildString {
                    append("${cookie.name}=${cookie.value}")
                    append("; Domain=$domain")
                    append("; Path=${cookie.path ?: "/"}")
                    if (cookie.secure) append("; Secure")
                }
                val url = "https://$domain/"
                webCookieManager.setCookie(url, cookieStr)
            }
            webCookieManager.flush()
            Log.d(TAG, "Cookie sync (CookieManager) complete, total: ${cookies.size}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Cookie sync failed", e)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String = "",
    login: XJTULogin? = null,
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
    var isReaderPage by remember { mutableStateOf(false) } // 通知详情页 → Hide WebView until reader mode applied
    var readerReady by remember { mutableStateOf(false) } // reader mode JS applied

    // 同步 cookies（仅一次）
    LaunchedEffect(login) {
        syncCookiesToWebView(login)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            pageTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
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
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        trackColor = Color.Transparent
                    )
                }
            }
        },
        bottomBar = {
            // URL 输入栏
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = editingUrl,
                        onValueChange = { editingUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val url = normalizeUrl(editingUrl)
                                webViewRef?.loadUrl(url)
                            }
                        ),
                        placeholder = { Text("输入网址", style = MaterialTheme.typography.bodySmall) }
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
                                currentUrl = it
                                editingUrl = it
                            }
                            // 对 XJTU 通知详情页：隐藏 WebView + 注入 CSS 避免原始页面闪烁
                            if (url != null && isXjtuNotificationDetail(url)) {
                                isReaderPage = true
                                readerReady = false
                                view?.evaluateJavascript(HIDE_BODY_JS, null)
                            } else {
                                isReaderPage = false
                                readerReady = true
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
                            // 对 XJTU 通知详情页注入 Reader Mode，完成后显示
                            if (url != null && isXjtuNotificationDetail(url)) {
                                view?.evaluateJavascript(READER_MODE_JS) {
                                    readerReady = true
                                }
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
                        loadUrl(normalizeUrl(initialUrl))
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(
                    if (isReaderPage && !readerReady)
                        Modifier.alpha(0f)  // 隐藏直到 reader mode 生效
                    else Modifier
                )
        )
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
    return "https://www.bing.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
}
