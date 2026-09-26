package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "JwappLogin"

/**
 * 移动教务系统 (jwapp) 专用登录
 * 登录后自动提取 Authorization token 并注入到 session headers
 *
 * ### Token 生命周期
 * - token 从 CAS 重定向 URL 中提取，存于内存
 * - 通过 [isTokenValid] 检查 token 是否存在
 * - 通过 [reAuthenticate] 利用 CAS SSO TGC cookie 重新获取 token
 * - 通过 [executeWithReAuth] 自动检测 401 并重试
 */
class JwappLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWAPP_URL, session, visitorId, cachedRsaKey) {

    var authToken: String? = null
        private set

    /** Token 获取时间戳（毫秒），用于估算过期 */
    private var tokenObtainedAt: Long = 0L

    override fun postLogin(response: Response) {
        // 从最终重定向 URL 中提取 token
        val finalUrl = response.request.url.toString()
        authToken = finalUrl.substringAfter("token=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("登录失败：无法获取教务 Token")
        tokenObtainedAt = System.currentTimeMillis()
        Log.d(TAG, "postLogin: token obtained, len=${authToken?.length}")
        // 诊断：jwapp 域 cookies 名单（不暴露值），定位 401 是否是缺 session cookie
        try {
            val jar = client.cookieJar
            if (jar is com.xjtu.toolbox.network.PersistentCookieJar) {
                val direct = jar.getCookiesForDomain("jwapp.xjtu.edu.cn") +
                    jar.getCookiesForDomain(".jwapp.xjtu.edu.cn")
                val webvpn = jar.getCookiesForDomain("webvpn.xjtu.edu.cn") +
                    jar.getCookiesForDomain(".webvpn.xjtu.edu.cn")
                Log.d(TAG, "postLogin: jwapp-cookies=${direct.map { it.name }}, webvpn-cookies=${webvpn.map { it.name }}")
            }
        } catch (_: Exception) {}
    }

    /**
     * 检查 token 是否可能有效
     * - token 非空
     * - 获取时间在 TOKEN_TTL_MS 内（默认 1 小时）
     */
    fun isTokenValid(): Boolean {
        val token = authToken ?: return false
        if (token.isEmpty()) return false
        if (tokenObtainedAt > 0 && System.currentTimeMillis() - tokenObtainedAt > TOKEN_TTL_MS) {
            Log.d(TAG, "isTokenValid: token expired (age=${(System.currentTimeMillis() - tokenObtainedAt) / 1000}s)")
            return false
        }
        return true
    }

    /**
     * 验证移动教务登录态是否仍然有效。
     * 先检查本地 token TTL，再通过 API 轻量调用验证。
     */
    override fun validateLogin(): Boolean {
        if (!isTokenValid()) return false
        return try {
            val request = Request.Builder()
                .url("https://jwapp.xjtu.edu.cn/api/student/info")
                .header("Authorization", authToken ?: "")
                .get().build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code == 200
        } catch (_: Exception) { false }
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            if (validateLogin()) return KeepAliveStatus.VALID
            if (reAuthenticate()) KeepAliveStatus.REAUTH_OK
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) {
            KeepAliveStatus.NETWORK_ERROR
        } catch (_: Exception) {
            KeepAliveStatus.ERROR
        }
    }

    private val reAuthLock = Any()

    /**
     * [D1] 重新认证：先尝试 SSO，失败后 fallback 到 casAuthenticate（TGC 过期时用保存的密码）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // [清 cookie 仅限 jwapp 业务域]
            //   - 清 jwapp.xjtu.edu.cn cookie（`sk` 等 jwapp 自己的 session）让 jwapp 服务端重建 session
            //   - **绝对不能** 清 login.xjtu.edu.cn cookie（TGC）—— TGC 是 CAS SSO 核心，清掉后所有 SSO 失败
            //     会触发 MFA detect 循环弹窗（已踩坑）
            (client.cookieJar as? com.xjtu.toolbox.network.PersistentCookieJar)?.let {
                it.clearForDomain("jwapp.xjtu.edu.cn")
            }

            // 第一步：尝试 SSO（CAS TGC 仍有效时直接成功）
            Log.d(TAG, "reAuthenticate: attempting SSO re-login (cleared jwapp cookies only)")
            val request = Request.Builder().url(JWAPP_URL).get().build()
            // [资源] 必须 .use 关闭 response 防 OkHttp connection leak（之前 logcat 已有 leak warning）。
            val token = client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                // 即使不读 body 也要 string() 触发 body close（OkHttp 自动）
                runCatching { response.body.string() }
                finalUrl.substringAfter("token=", "")
                    .substringBefore("&")
                    .takeIf { it.isNotEmpty() }
            }
            if (token != null) {
                authToken = token
                tokenObtainedAt = System.currentTimeMillis()
                Log.d(TAG, "reAuthenticate: SSO success, new token obtained")
                return true
            }

            // 第二步：SSO 失败（TGC 过期），fallback 到 casAuthenticate 用保存的密码
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate fallback")
            val casResult = casAuthenticate(JWAPP_URL)
            if (casResult != null) {
                val casToken = casResult.second.substringAfter("token=", "")
                    .substringBefore("&")
                    .takeIf { it.isNotEmpty() }
                if (casToken != null) {
                    authToken = casToken
                    tokenObtainedAt = System.currentTimeMillis()
                    Log.d(TAG, "reAuthenticate: casAuthenticate success, new token obtained")
                    return true
                }
            }
            Log.w(TAG, "reAuthenticate: all methods failed")
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        return false
    }

    companion object {
        const val JWAPP_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234"

        /** Token 预估 TTL：1 小时（jwapp token 通常较短命） */
        private const val TOKEN_TTL_MS = 60 * 60 * 1000L
    }
}
