package com.xjtu.toolbox.webvpn

/**
 * WebVPN 网址互转的**平台缝**：把两个方向的转换本体从 `:core` 的屏里摘出去。
 *
 * ## 为什么要缝
 *
 * 转换本身是 `:data` 的 [com.xjtu.toolbox.webvpn.WebVpnUtil]（`:app` 搬迁前就在那儿的同一份实现，
 * 与 XJTUToolBox Python 版逐字同算法）：把域名用 AES-128-CFB（key/iv 都是 `wrdvpnisthebest!`）
 * 加密成一段十六进制、拼成 `https://webvpn.xjtu.edu.cn/<proto>[-port]/<iv><host>/<rest>`。
 * 那段加密要用 `javax.crypto.Cipher` —— 它不存在于 `:core` 的 commonMain（那里要编到 wasmJs），
 * 所以**转换本体不能搬进 `:core`**，只能按「能力」切一条缝。
 *
 * ## 谁实现 / 谁注入
 *
 * | 端 | 实现 |
 * |---|---|
 * | Android（`:app`） | `:data` 的 `WebVpnUtil`（同一个对象还挂着 WebVPN backend 的 okhttp 拦截器） |
 * | 桌面（`:desktop`） | 同上（`:data` 的 jvm 变体就在这一端的类路径上） |
 *
 * 与 `:core` 的 `WebVpnUrl`（那个只做**判据**、三端共用）是两件事：这里做的是**改写**。
 */
interface WebVpnCodec {

    /** 普通网址 → WebVPN 代理网址。已经是 WebVPN 网址时原样返回（与 `WebVpnUtil.getVpnUrl` 同口径）。 */
    fun toVpnUrl(url: String): String

    /** WebVPN 代理网址 → 原始网址。解析不出来返回 null（与 `WebVpnUtil.getOriginalUrl` 同口径）。 */
    fun fromVpnUrl(vpnUrl: String): String?
}
