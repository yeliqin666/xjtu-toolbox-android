package com.xjtu.toolbox.util

import android.view.ViewGroup
import android.webkit.WebView

/**
 * 释放一个不再使用的 WebView。给 Compose `AndroidView(onRelease = …)` 用。
 *
 * AndroidView 离开组合时只把 View 从树上摘掉，不会调 [WebView.destroy]；WebView 持有
 * Chromium 渲染进程句柄、JS 上下文等原生资源，不销毁的话每进出一次页面就泄漏一份。
 *
 * 顺序照官方建议：先停加载、断开回调和 JS 接口，从父布局移除，最后 destroy。
 * 每一步都兜住异常——释放失败不能反过来让退出页面崩溃。
 */
fun WebView.releaseSafely(jsInterfaceNames: Collection<String> = emptyList()) {
    runCatching { stopLoading() }
    runCatching { setDownloadListener(null) }
    runCatching { webChromeClient = null }
    jsInterfaceNames.forEach { name -> runCatching { removeJavascriptInterface(name) } }
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { removeAllViews() }
    runCatching { destroy() }
}
