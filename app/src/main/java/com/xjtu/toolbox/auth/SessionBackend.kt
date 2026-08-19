package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.PersistentCookieJar
import kotlinx.coroutines.sync.Mutex
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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
    // 连接池放宽：一次登录要在 login.xjtu.edu.cn / 业务域之间来回十几跳，
    // 30 秒 keep-alive 撑不过用户在页面上的停顿，回来又要重新 TLS 握手（校园网上常 200-600ms/次）。
    connectionPool: ConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES),
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
        // 所有业务几乎都打在同一个 host 上，默认 maxRequestsPerHost=5 会把页面内的并发请求排队
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        })
        // 连不上就早点失败并让上层重试，比干等 30 秒体感好得多；读超时保持 30 秒（成绩单生成等接口确实慢）
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** backend 自身动作的串行保护锁。 */
    val loginLock = Mutex()

    /**
     * backend 自身的认证状态（特指 WebVPN 网关）。
     * NORMAL backend 永为 true（直连无需网关认证）。
     *
     * 不能当成永生旗标：进程挂很久后 wengine ticket 会过期，必须靠
     * [webvpnValidatedAt] + 探活再决定要不要重登。
     */
    @Volatile var webvpnSelfLoggedIn: Boolean = accessMode == AccessMode.NORMAL

    /** 上次确认网关仍有效的 elapsedRealtime；0 表示从未确认。 */
    @Volatile var webvpnValidatedAt: Long = 0L

    fun markWebVpnReady() {
        webvpnSelfLoggedIn = true
        webvpnValidatedAt = android.os.SystemClock.elapsedRealtime()
    }

    fun markWebVpnStale() {
        if (accessMode == AccessMode.NORMAL) {
            webvpnSelfLoggedIn = true
            webvpnValidatedAt = 0L
            return
        }
        webvpnSelfLoggedIn = false
        webvpnValidatedAt = 0L
    }

    /** 清空 cookies + 重置自身认证态，限于登出、密码变更等场景；不用于网络切换。 */
    fun clearAuth() {
        cookieJar.clear()
        markWebVpnStale()
        if (accessMode == AccessMode.NORMAL) webvpnSelfLoggedIn = true
    }
}
