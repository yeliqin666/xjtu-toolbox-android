package com.xjtu.toolbox.browser

import com.xjtu.toolbox.webvpn.WebVpnCodec

/** 对外（复制、分享、交给外部浏览器）用的地址：WebVPN 代理地址换回原始地址。 */
fun shareableUrl(url: String, codec: WebVpnCodec): String =
    codec.fromVpnUrl(url)?.takeIf { it.isNotBlank() } ?: url
