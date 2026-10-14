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
 * | Web（`:web`） | **上面这一条用不了**：它要 `javax.crypto`（wasm 上没有）⇒ 这一端走服务端那个纯函数
 * 端点 `POST /api/webvpn/convert`（契约 §5.7），也就是下面那条**异步**缝 [WebVpnAsyncCodec] |
 *
 *
 * 与 `:core` 的 `WebVpnUrl`（那个只做**判据**、三端共用）是两件事：这里做的是**改写**。
 */
interface WebVpnCodec {

    /** 普通网址 → WebVPN 代理网址。已经是 WebVPN 网址时原样返回（与 `WebVpnUtil.getVpnUrl` 同口径）。 */
    fun toVpnUrl(url: String): String

    /** WebVPN 代理网址 → 原始网址。解析不出来返回 null（与 `WebVpnUtil.getOriginalUrl` 同口径）。 */
    fun fromVpnUrl(vpnUrl: String): String?
}

/**
 * 同上那条缝的**异步**形态：转换本体不在本端（serve 形态下它在服务端，一次 HTTP 往返）。
 *
 * ## 为什么是**另一条**缝，而不是把 [WebVpnCodec] 的两个方法改成 `suspend`
 *
 * [WebVpnCodec] 还有第二个消费方：`:core` 的
 * [com.xjtu.toolbox.browser.shareableUrl]（「对外交出去的地址」那条规则，把网关地址换回原网址）。
 * 那是一条**同步的纯字符串规则**，`:app` 的浏览器顶栏与桌面的外链中转页都在直接用它
 * （`BrowserScreen.kt` / `DesktopBrowser.kt` / `DesktopBrowserJvmTest`）—— 把缝改成 `suspend`
 * 会一路改到那两端的调用点，而这一轮的改动范围里没有它们。
 *
 * 所以这里加**一条**新缝，而不是把老那条掰弯：[WebVpnCodec] 继续给「本端有实现」的两端
 * （同步、纯函数），[WebVpnAsyncCodec] 给「本端没有实现、转换要打一发 HTTP」的 `:web`。
 * 屏（`WebVpnConverterScreen`）两个形态都收：传同步那份时由 [asAsyncCodec] 直接包一层，
 * 于是 Android / 桌面的调用点**一个字都没改**。
 */
interface WebVpnAsyncCodec {

    /** 普通网址 → WebVPN 代理网址。已经是 WebVPN 网址时原样返回（与 `WebVpnUtil.getVpnUrl` 同口径）。 */
    suspend fun toVpnUrl(url: String): String

    /** WebVPN 代理网址 → 原始网址。解析不出来返回 null（与 `WebVpnUtil.getOriginalUrl` 同口径）。 */
    suspend fun fromVpnUrl(vpnUrl: String): String?
}

/**
 * 同步那份实现 → 异步缝：一个 `suspend` 函数体里直接调同步方法（`WebVpnUtil` 是纯字符串运算，
 * 一微秒都不阻塞）。这是 Android / 桌面注入的老实现**原样**接上新缝的那一层，不改变任何行为。
 */
fun WebVpnCodec.asAsyncCodec(): WebVpnAsyncCodec = SyncWebVpnCodec(this)

private class SyncWebVpnCodec(private val codec: WebVpnCodec) : WebVpnAsyncCodec {
    override suspend fun toVpnUrl(url: String): String = codec.toVpnUrl(url)
    override suspend fun fromVpnUrl(vpnUrl: String): String? = codec.fromVpnUrl(vpnUrl)
}
