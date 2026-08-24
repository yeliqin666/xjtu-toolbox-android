package com.xjtu.toolbox.browser

import android.graphics.Color
import android.net.Uri
import android.webkit.WebView

/**
 * 内置 WebView 的夜间模式：注入本地 Dark Reader（4.9.128），跟随 App 主题热切换。
 *
 * 不用运行时拉 jsDelivr：校园网 / WebVPN / 无网都不可靠，也多一次外联。
 * 不用 [DarkReader.auto]：它跟系统 `prefers-color-scheme`，跟不上设置里「始终浅色 / 始终深色」。
 */
object WebViewNightMode {

    const val DARKREADER_VERSION = "4.9.128"

    private const val ASSET = "darkreader.min.js"

    @Volatile
    private var libraryJs: String? = null

    private const val ENABLE_JS = """
        (function(){
          try {
            if (!window.DarkReader) return;
            DarkReader.setFetchMethod(window.fetch);
            DarkReader.enable({brightness:100,contrast:100,sepia:0,mode:1});
          } catch (e) {}
        })();
    """

    private const val DISABLE_JS = """
        (function(){
          try {
            if (window.DarkReader) DarkReader.disable();
          } catch (e) {}
        })();
    """

    private const val HAS_JS =
        "(function(){return !!(window.DarkReader && window.DarkReader.enable);})()"

    fun apply(view: WebView, dark: Boolean) {
        val want = dark && !shouldSkip(view.url)
        view.setBackgroundColor(if (want) Color.parseColor("#181A1B") else Color.WHITE)
        val toggle = if (want) ENABLE_JS else DISABLE_JS
        view.evaluateJavascript(HAS_JS) { loaded ->
            if (loaded == "true") {
                view.evaluateJavascript(toggle, null)
            } else if (want) {
                view.evaluateJavascript(library(view)) {
                    view.evaluateJavascript(ENABLE_JS, null)
                }
            }
        }
    }

    private fun library(view: WebView): String {
        libraryJs?.let { return it }
        val text = view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
        libraryJs = text
        return text
    }

    /** 登录 / 验证码页反色会毁掉二维码和识别图，保持原站浅色。 */
    fun shouldSkip(url: String?): Boolean {
        val raw = url.orEmpty()
        if (raw.isBlank() || raw == "about:blank") return true
        val host = runCatching { Uri.parse(raw).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == "login.xjtu.edu.cn" || host == "cas.xjtu.edu.cn") return true
        val lower = raw.lowercase()
        return "captcha" in lower || "geetest" in lower
    }
}
