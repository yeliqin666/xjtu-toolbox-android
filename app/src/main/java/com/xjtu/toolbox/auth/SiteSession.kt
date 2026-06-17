package com.xjtu.toolbox.auth

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 业务站点的会话基类。一个实例对应一个业务子系统（jwxt / jwapp / library / …），
 * 由 [SessionManager] 根据当前 access mode 动态绑定 [SessionBackend]。
 *
 * 职责范围：
 * 1. 发起 CAS 登录、提取本站局部 token，存于 [localToken]。
 * 2. 业务请求经 [executeWithReAuth] 入口：遇认证失效响应，自动 invalidate + 重认证 + 重放，上层透明。
 *
 * 子类抽象点：
 * - [runLogin]—建立 CAS session + 提取局部 token 的完整过程，并将 token 写入 [localToken]。
 * - [validateLogin]—轻量探活接口验证当前会话有效性。
 * - [decorateRequest]（可选）—为业务请求注入站点特有 header（Authorization / Synjones-Auth 等）。
 */
abstract class SiteSession(
    val siteKey: String,
    val siteName: String,
    val supportsWebVpn: Boolean = true,
) {
    /** 由 SessionManager 在创建时注入并随网络切换更新。 */
    @Volatile var backend: SessionBackend? = null
        internal set

    /** 由 SessionManager 注入，用于报告凭据失效、弹 MFA 等跨站点动作。 */
    @Volatile internal var manager: SessionManager? = null

    /** 同站点串行保护：同一时刻仅一个 ensureLogin 流程进行。 */
    private val loginLock = Mutex()

    /** 本站点局部 token / sessionid 仓库。 */
    val localToken: MutableMap<String, String> = ConcurrentHashMap()

    @Volatile var hasLogin: Boolean = false
        protected set

    /** 当前 active backend 的 OkHttpClient。 */
    val client: OkHttpClient
        get() = backend?.client
            ?: error("SiteSession[$siteKey]: backend not bound")

    val currentAccessMode: AccessMode
        get() = backend?.accessMode ?: AccessMode.NORMAL

    /**
     * 在当前 backend 上完成一次完整登录流程：走 CAS 状态机 + 提取本站点局部 token 并存入 [localToken]。
     * 子类一般会实例化 [XJTULogin]（复用 [SessionBackend.cookieJar]），遇 MFA 状态
     * 调用 [SessionManager.askMfaCode] 等待 UI 输入。
     */
    @Throws(IOException::class)
    protected abstract suspend fun runLogin(username: String, password: String)

    /**
     * 用轻量接口探测当前会话有效性。
     * 抛 IOException 表示网络错（保留现状）；返回 false 表示明确失效，由 [executeWithReAuth] 重认证。
     */
    @Throws(IOException::class)
    protected open suspend fun validateLogin(): Boolean = true

    /** 为业务请求注入本站 header（默认不注入）。 */
    open fun decorateRequest(builder: Request.Builder): Request.Builder = builder

    /** 判断响应是否表示认证失效（401 / CAS 登录页 / Safety Verify）。子类可根据业务返回格式重写。 */
    open fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (response.code == 401 || response.code == 403) return true
        if (bodyPreview != null) return XJTULogin.isAuthFailureResponse(bodyPreview)
        return false
    }

    /**
     * 确保本站点处于可用认证态：已登录且 validate 通过则直接复用，否则走 [runLogin]。
     * 密码全局失效状态下立即抛 [PasswordInvalidatedException]，避免重复错密请求被服务端封号。
     */
    @Throws(IOException::class)
    suspend fun ensureLogin(username: String, password: String, force: Boolean = false) {
        manager?.checkPasswordValid()
        loginLock.withLock {
            if (!force && hasLogin) {
                try {
                    if (validateLogin()) return
                } catch (e: IOException) {
                    Log.w(TAG, "[$siteKey] validate IOException, keep valid: ${e.message}")
                    return
                }
            }
            invalidateLogin()
            try {
                runLogin(username, password)
                hasLogin = true
                Log.d(TAG, "[$siteKey] login ok (mode=${currentAccessMode.key})")
            } catch (e: PasswordInvalidatedException) {
                manager?.reportPasswordInvalidated(siteKey, siteName)
                throw e
            }
        }
    }

    /** 标记本站点会话失效（清 hasLogin + 局部 token，不动共享 cookies）。 */
    fun invalidateLogin() {
        hasLogin = false
        localToken.clear()
    }

    /**
     * 业务请求统一入口。命中认证失效响应时自动 invalidate + 重认证 + 重放一次。
     * 重放仍失败抛 [AuthExpiredException]。调用方传入的 request 无需预注 header—— [decorateRequest] 负责补齐。
     */
    @Throws(IOException::class, AuthExpiredException::class)
    suspend fun executeWithReAuth(request: Request, retried: Boolean = false): Response {
        val finalRequest = decorateRequest(request.newBuilder()).build()
        val response = client.newCall(finalRequest).execute()

        val contentType = response.header("Content-Type", "")?.lowercase().orEmpty()
        val mayBeAuthFail = "html" in contentType || "text" in contentType || response.code in 400..499
        val bodyPreview = if (mayBeAuthFail) {
            try { response.peekBody(8192).string() } catch (_: Exception) { null }
        } else null

        if (!isAuthFailureResponse(response, bodyPreview)) return response

        response.close()
        if (retried) throw AuthExpiredException(siteName, "$siteName 登录态已失效")
        Log.w(TAG, "[$siteKey] auth failure, invalidate and re-login")
        invalidateLogin()
        val mgr = manager ?: throw AuthExpiredException(siteName)
        val creds = mgr.credentials ?: throw AuthExpiredException(siteName, "未配置凭据")
        ensureLogin(creds.first, creds.second, force = true)
        return executeWithReAuth(request, retried = true)
    }

    companion object {
        private const val TAG = "SiteSession"
    }
}

/**
 * 本账号凭据明确无效时抛出。
 * SessionManager 接收此异常后设置全局失效状态，阻断其余站点同一账号的后续登录尝试。
 */
class PasswordInvalidatedException(
    val siteName: String = "",
    message: String = "账号或密码无效",
) : IOException(message)
