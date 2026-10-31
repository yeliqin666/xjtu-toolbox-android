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

/**
 * 渲染进程崩了或被系统回收内存时调用，供 `WebViewClient.onRenderProcessGone` 直接返回。
 *
 * 不接这个回调（默认返回 false）系统会把整个 App 一起杀掉；卓易通这类内存紧的安卓容器里，
 * WebOffice 这种重页面就是这样闪退的。死掉的 WebView 不能复用，照官方建议释放掉再交给 [onGone] 换界面。
 */
fun WebView.recoverFromRendererGone(onGone: () -> Unit): Boolean {
    releaseSafely()
    onGone()
    return true
}
