package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.PersistentCookieJar
import kotlinx.coroutines.sync.Mutex
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import java.util.concurrent.TimeUnit

/**
 * 一种 [AccessMode] 对应一个 SessionBackend，提供该访问方式下所有业务站点共享的底层请求资源。
 *
 * 关键不变量：
 * - cookies 物理隔离——`cookies_normal` / `cookies_webvpn` 各自一份存储，从M不混淆。
 * - 同 backend 内所有 SiteSession 共享 cookies：一次 CAS 登录建立的 TGC 全局生效，
 *   后续走 CAS 的子系统均 SSO 直通，不会重复触发 MFA。
 * - [loginLock] 串行化 backend 自身的登录动作（如 WebVPN 网关认证）。
 */
class SessionBackend(
    val accessMode: AccessMode,
    val cookieJar: PersistentCookieJar,
    connectionPool: ConnectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS),
    private val webVpnInterceptor: okhttp3.Interceptor? = null,
) {
    /**
     * OkHttpClient—持有 cookies + 连接池 + WEBVPN 时的 URL 改写拦截器。
     * WEBVPN backend 的拦截器自动将 jwxt/jwapp/… 等原域名 URL 改写为 webvpn 加密形式，业务层无感知。
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .apply { if (webVpnInterceptor != null) addInterceptor(webVpnInterceptor) }
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** backend 自身动作的串行保护锁。 */
    val loginLock = Mutex()

    /**
     * backend 自身的认证状态（特指 WebVPN 网关）。
     * NORMAL backend 永为 true（直连无需网关认证）。
     */
    @Volatile var webvpnSelfLoggedIn: Boolean = accessMode == AccessMode.NORMAL

    /** 清空 cookies + 重置自身认证态，限于登出、密码变更等场景；不用于网络切换。 */
    fun clearAuth() {
        cookieJar.clear()
        webvpnSelfLoggedIn = accessMode == AccessMode.NORMAL
    }
}
