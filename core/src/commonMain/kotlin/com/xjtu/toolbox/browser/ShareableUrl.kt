package com.xjtu.toolbox.browser

import com.xjtu.toolbox.webvpn.WebVpnCodec

/**
 * 对外（复制、分享、交给**外部浏览器**）用的地址：WebVPN 代理地址换回原始地址。
 *
 * 这条规则本轮按五步法从 `:app` 的 `browser/BrowserScreen.kt` 搬进来（那边三处调用改成同一个
 * 名字、多传一格 [codec]，**画法与文案一行未改**）。它只做字符串改写、不认识 Android
 * —— 这是第 1 步「切出纯的那半」；没有第 2 步的屏、也没有取数端口：这条缝只有这一个函数。
 *
 * ## 桌面端要用同一条口径
 *
 * 桌面**没有内嵌浏览器**（这一端的 `AppRoute.Browser` 等价物是外链中转页
 * `desktop/.../DesktopBrowser.kt`），交给系统浏览器的地址与 `:app` 顶栏那颗
 * 「在外部浏览器打开」走的是同一句话：
 *
 * > `webvpn.xjtu.edu.cn/https/7772…` 这种链路对方没有网关会话打不开，也看不出是哪个网站；
 * > 原始地址在校外打不开，至少看得懂。
 *
 * @param codec 判定 + 改写的那一半：`:data` 的 `WebVpnUtil`（Android 与桌面注入的是**同一份**
 *   实现，见 [WebVpnCodec] 的 KDoc）。
 *
 * 原版那句 `WebVpnUtil.isWebVpnUrl(url)` 是多余的：`getOriginalUrl` 自己第一行就是
 * `if (!isWebVpnUrl(vpnUrl)) return null`（`:data` 的 `WebVpnUtil.kt`）—— 判据与改写都归
 * [WebVpnCodec]，这里只认它给的 null。
 */
fun shareableUrl(url: String, codec: WebVpnCodec): String =
    codec.fromVpnUrl(url)?.takeIf { it.isNotBlank() } ?: url
