package com.xjtu.toolbox.auth

import com.xjtu.toolbox.dormpower.DormPowerParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 宿舍电费（`ssn.xjtu.edu.cn/cems`）的站点会话。 */
class SsnSession : CasSiteSession(SITE_KEY, "宿舍电费", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SsnLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? SsnLogin)?.cid?.let { localToken["cid"] = it }
    }

    /** 接口返回 401001 表示凭证过期。 */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) || bodyPreview?.contains("\"code\":401001") == true

    /** 给浏览器用的缴费页入口：缴费站没有可搬运的会话 cookie，每次进页面都要凭 OAuth 回调里一次性的 code 换会话。 */
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

    /** 拿存下的 cid 查一次宿舍列表：有效回 code 0，过期回 401001。 */
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
