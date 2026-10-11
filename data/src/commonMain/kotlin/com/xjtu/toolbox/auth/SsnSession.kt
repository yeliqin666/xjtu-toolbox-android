package com.xjtu.toolbox.auth

import com.xjtu.toolbox.dormpower.DormPowerParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 宿舍电费（`ssn.xjtu.edu.cn/cems`）的站点会话。
 *
 * 与 [SsnLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 17 条真数据路由：
 * 桌面要自己登 `ssn`、自己取缴费页入口，再由 `:data` 的 [com.xjtu.toolbox.dormpower.DormPowerApi]
 * 查宿舍与电量）。类名与包路径一字未改 ⇒ `:app` 的 `AppLoginState` / `HeadlessSessions` 里那两处
 * `register(SsnSession())` 一行不用改。
 *
 * ## 登录形态（如实交代）
 *
 * **它有自己的独立登录链**，不借校园卡 / 教务的会话：`SsnLogin` 走
 * `org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759`（开放平台自己的 OAuth2 应用）
 * → 统一认证 CAS → 带 `code` 回 `/cems/index/mobile/pay` → 站点点下会话 cookie，页面 HTML 里
 * 内嵌 `cid`（见 [SsnLogin.parseCid]）。所以本类在 `:desktop` 既进 `SESSION_SITE_KEYS`
 * （登录页那一步顺手预热）、也进那条路由的 `DesktopSiteGate`（进门时确保会话）。
 *
 * `mustUseWebVpn = true`：ssn 只对校内网络开放，校外直连回 403「请先拨通学校 VPN」，得走 WebVPN 网关。
 *
 * 三处覆写与搬迁前逐字一致：
 *  - `onLoginSuccess`：把缴费页里的 `cid` 写进 `localToken["cid"]` —— `DormPowerApi` 的每个请求都要它；
 *  - `isAuthFailureResponse`：接口回 `401001` 也算凭证过期（与 `DormPowerParsers.data` 两处认领）；
 *  - `validateLogin`：拿存下的 `cid` 查一次宿舍列表判活。**不能重开缴费页来判断** —— 缴费页要凭
 *    OAuth 回调里一次性的 code 进，不带 code 打开一律当成没登录。
 *
 * 搬迁时被替换的写法只有一处：原来用的 `Sites.kt` 里那个私有的 `withIo { }`（它留在那边给其余站点用）
 * 换成本文件里的 `withContext(Dispatchers.IO)` —— 与 `DzpzSession` / `JwxtSession` 同一条口径。
 */
class SsnSession : CasSiteSession(SITE_KEY, "宿舍电费", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SsnLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? SsnLogin)?.cid?.let { localToken["cid"] = it }
    }

    /** 接口返回 401001 表示凭证过期。 */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) || bodyPreview?.contains("\"code\":401001") == true

    /**
     * 给浏览器用的缴费页入口：缴费站没有可搬运的会话 cookie，每次进页面都要凭 OAuth 回调里一次性的 code 换会话。
     * 这里用 App 存的账号再走一遍 OAuth，拦下最后那一跳（带 code 的缴费页地址）不去请求，
     * 交给 WebView 自己打开，会话就建在 WebView 里。校外时拿到的是 WebVPN 网关形式的地址。
     */
    suspend fun freshPayUrl(): String {
        val (username, password) = manager?.credentials ?: throw IOException("还没有登录账号，请先在「我的」页登录")
        if (currentAccessMode == AccessMode.WEBVPN) manager?.ensureWebVpnLogin()
        val backend = checkNotNull(backend) { "[$siteKey] backend not bound" }
        return backend.loginGate.withLock(foreground = true) {
            withContext(Dispatchers.IO) { capturePayCallback(backend.client, username, password) }
        }
    }

    private fun capturePayCallback(client: OkHttpClient, username: String, password: String): String {
        var callback: String? = null
        // 得用网络拦截器：重定向的每一跳只有它看得到（应用拦截器只见第一个请求）。
        // 它必须放行一次，所以带 code 的那一跳改成不带参数的同一页，code 原样留给 WebView 去用。
        val capturing = client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val isCallback = request.url.encodedPath.endsWith("/cems/index/mobile/pay") && request.url.queryParameter("code") != null
            if (!isCallback) return@addNetworkInterceptor chain.proceed(request)
            callback = request.url.toString().replaceFirst("http://", "https://")
            chain.proceed(request.newBuilder().url(request.url.newBuilder().query(null).build()).build())
        }.build()
        val login = object : SsnLogin(capturing, manager?.fpVisitorId, manager?.cachedRsaKey) {
            override fun postLogin(response: Response) {}
        }
        val result = login.login(username, password)
        return callback ?: throw IOException(result.message.ifBlank { "没能取得缴费页入口，请稍后重试" })
    }

    /**
     * 拿存下的 cid 查一次宿舍列表：有效回 code 0，过期回 401001。不能重开缴费页来判断——
     * 缴费页要凭 OAuth 回调里一次性的 code 进（见 [freshPayUrl]），不带 code 打开一律当成没登录。
     */
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val cid = localToken["cid"] ?: return@withContext false
        val request = Request.Builder().url("${SsnLogin.BASE_URL}/mobile/addr/list?cid=$cid")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", SsnLogin.PAY_PAGE_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .get().build()
        client.newCall(request).execute().use { resp ->
            resp.isSuccessful && runCatching {
                DormPowerParsers.data(DormPowerParsers.decode(resp.body.bytes()))
            }.isSuccess
        }
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.SSN_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "ssn"
    }
}
