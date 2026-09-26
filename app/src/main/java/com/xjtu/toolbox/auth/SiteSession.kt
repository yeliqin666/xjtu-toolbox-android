package com.xjtu.toolbox.auth

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /**
     * 该站点的域名/端口在校外是否物理不可达，必须经 WebVPN 代理才能连通。
     *
     * - `true`（默认）：域名或端口结构本身不对公网开放（如图书馆座位系统走非标准端口
     *   `rg.lib.xjtu.edu.cn:8086`），校外直连必然失败，[SessionManager.backendFor] 会
     *   跟随全局 [AccessMode] 检测结果切换 NORMAL/WEBVPN。
     * - `false`：该站点被硬编码永远绑定 [AccessMode.NORMAL]（直连原域名），不会走
     *   WebVPN 代理。**注意**：这不代表"校外一定可用"——若其域名当前仍仅限校内网络
     *   直连（历史上护网期间的常态），直连模式在校外依然会失败，需要连接校园官方
     *   VPN（操作系统层面，非本 App 的 WebVPN）或回到校园网。护网结束后部分域名
     *   （如 jwxt、lms）已放开公网直连，`false` 的站点在这类场景下才恰好校外可用，
     *   但这取决于学校当前的网络策略，并非本字段保证的行为。
     */
    val mustUseWebVpn: Boolean = true,
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

    /**
     * 登录代数：每次 runLogin 成功后自增。
     * [executeWithReAuth] 在发请求前记录代数，命中认证失效时若发现代数已前进
     * （其他并发请求已完成重登录），直接复用新会话重放，避免并发 401 触发
     * 「N 个请求 → N 次完整 CAS 登录」的踩踏（每次登录都要过 CasGate 限频，叠加即卡死）。
     */
    @Volatile private var loginEpoch: Long = 0L

    /**
     * 上次「确认会话有效」的时刻（登录成功 / validate 通过）。
     * [VALIDATE_TTL_MS] 内的 ensureLogin 直接复用，不再重复探活。
     *
     * 为什么安全：探活只是**乐观预检**，不是安全边界——真正的兜底是 [executeWithReAuth]，
     * 它按响应判定认证失效并自动重登+重放。TTL 内会话若已失效，代价是一次重放，
     * 而不是失败。反之，无缓存时每次 ensureSite 都要多一个网络往返
     *（AgentTool 里 24 处调用、页面每次刷新都会命中），这是"处处变慢"的主因。
     */
    @Volatile private var lastValidatedAt: Long = 0L

    /**
     * 当前这次 [runLogin] 是否为「静默」流程（后台预热 / 保活）。
     * 为 true 时遇到需要用户参与的环节（MFA、图形验证码）必须**直接放弃**：
     * 不弹窗、不下发短信验证码——后台任务无权打扰用户，更不该替用户消耗短信配额。
     *
     * 只在 [loginLock] 保护的 runLogin 期间读写，故单站点内不存在竞态。
     */
    @Volatile protected var silentLogin: Boolean = false
        private set

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
    suspend fun ensureLogin(
        username: String,
        password: String,
        force: Boolean = false,
        staleEpoch: Long = -1L,
        userInitiated: Boolean = false,
        silent: Boolean = false,
    ) {
        // 快路径：会话新鲜且非强制时，连 manager 的各项检查与 WebVPN 网关确认都不必做。
        if (!force && hasLogin && isFresh()) return

        val mgr = manager
        mgr?.checkPasswordValid()
        // 失败冷却只约束后台/自动路径。用户主动点功能时被 60 秒冷却挡住，
        // 表现就是"点了没反应"，而防刷已由 CasGate 的失败退避+全局串行覆盖。
        if (!userInitiated) mgr?.checkLoginCooldown(siteKey, siteName)
        if (currentAccessMode == AccessMode.WEBVPN) {
            mgr?.ensureWebVpnLogin()
        }
        loginLock.withLock {
            // 等锁期间别人可能刚登完，再判一次新鲜度，避免排队者逐个重复探活。
            if (!force && hasLogin && isFresh()) return
            // 防踩踏：force 调用方若传入其观察到失效时的代数，而等锁期间其他协程
            // 已完成一轮新登录（代数前进），则直接复用，不再重复走完整 CAS。
            if (force && staleEpoch >= 0 && hasLogin && loginEpoch > staleEpoch) {
                Log.d(TAG, "[$siteKey] re-login skipped: epoch advanced ($staleEpoch -> $loginEpoch)")
                return
            }
            if (!force && hasLogin) {
                try {
                    if (withContext(Dispatchers.IO) { validateLogin() }) {
                        lastValidatedAt = SystemClock.elapsedRealtime()
                        return
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "[$siteKey] validate IOException, keep valid: ${e.message}")
                    manager?.recordDiagnostic("WARN", siteKey, "会话探活网络异常，暂时复用现有状态：${e.message ?: e.javaClass.simpleName}")
                    return
                }
            }
            invalidateLogin()
            try {
                silentLogin = silent
                withContext(Dispatchers.IO) {
                    runLogin(username, password)
                }
                hasLogin = true
                loginEpoch++
                lastValidatedAt = SystemClock.elapsedRealtime()
                manager?.clearLoginFailure(siteKey)
                Log.d(TAG, "[$siteKey] login ok (mode=${currentAccessMode.key})")
                manager?.recordDiagnostic("INFO", siteKey, "登录成功（${currentAccessMode.key}）")
            } catch (e: PasswordInvalidatedException) {
                manager?.reportPasswordInvalidated(siteKey, siteName)
                throw e
            } catch (e: IOException) {
                manager?.reportLoginFailure(siteKey)
                manager?.recordDiagnostic("ERROR", siteKey, "登录失败：${e.message ?: e.javaClass.simpleName}")
                throw e
            } finally {
                silentLogin = false
            }
        }
    }

    /** 会话是否处于「近期确认有效」窗口内。 */
    private fun isFresh(): Boolean {
        val age = SystemClock.elapsedRealtime() - lastValidatedAt
        return age in 0 until VALIDATE_TTL_MS
    }

    /** 标记本站点会话失效（清 hasLogin + 局部 token，不动共享 cookies）。 */
    open fun invalidateLogin() {
        hasLogin = false
        lastValidatedAt = 0L
        localToken.clear()
    }

    /**
     * 业务请求统一入口。命中认证失效响应时自动 invalidate + 重认证 + 重放一次。
     * 重放仍失败抛 [AuthExpiredException]。调用方传入的 request 无需预注 header—— [decorateRequest] 负责补齐。
     */
    @Throws(IOException::class, AuthExpiredException::class)
    suspend fun executeWithReAuth(request: Request, retried: Boolean = false): Response {
        val epochBefore = loginEpoch
        val response = withContext(Dispatchers.IO) {
            val finalRequest = decorateRequest(request.newBuilder()).build()
            client.newCall(finalRequest).execute()
        }

        // 只对文本类响应做预览。二进制流（PDF/图片/附件下载）不可能是 CAS 登录页，
        // 却要为此多拷贝+解码 8KB —— 下载路径上白白多一次缓冲。
        val bodyPreview = if (isTextualResponse(response)) {
            withContext(Dispatchers.IO) {
                try { response.peekBody(8192).string() } catch (_: Exception) { null }
            }
        } else null

        if (!isAuthFailureResponse(response, bodyPreview)) return response

        // 判成"认证失效"时必须留下判据：到底是 401/403，还是响应体被识别成了 CAS 登录页。
        // 只打一句 "auth failure" 的话，遇到误判（业务接口返回 403 但会话其实是好的）
        // 根本无从分辨——教务学期列表接口就是这么被卡住的。
        val failedUrl = com.xjtu.toolbox.webvpn.WebVpnUtil.getOriginalUrl(response.request.url.toString())
            ?: response.request.url.toString()
        Log.w(
            TAG,
            "[$siteKey] auth failure: code=${response.code} url=$failedUrl " +
                "preview=${bodyPreview?.take(160)?.replace("\n", " ")}"
        )
        withContext(Dispatchers.IO) { response.close() }
        if (retried) throw AuthExpiredException(siteName, "$siteName 登录态已失效")
        Log.w(TAG, "[$siteKey] auth failure, invalidate and re-login")
        manager?.recordDiagnostic("WARN", siteKey, "业务请求认证失效，准备重认证并重放请求")
        val mgr = manager ?: throw AuthExpiredException(siteName)
        val creds = mgr.credentials ?: throw AuthExpiredException(siteName, "未配置凭据")
        // 不在锁外 invalidate（会误伤并发协程刚建立的新会话），
        // 由 ensureLogin 依据代数决定是复用还是真正重登录。
        ensureLogin(creds.first, creds.second, force = true, staleEpoch = epochBefore)
        return executeWithReAuth(request, retried = true)
    }

    /** Content-Type 是否属于「可能是登录页/JSON 错误」的文本类型。缺省无 CT 时按文本处理（保守）。 */
    private fun isTextualResponse(response: Response): Boolean {
        val ct = response.header("Content-Type")?.lowercase() ?: return true
        return "json" in ct || "html" in ct || "text" in ct || "xml" in ct || "javascript" in ct
    }

    companion object {
        private const val TAG = "SiteSession"

        /** 会话新鲜度窗口。窗口内跳过探活往返；失效由 [executeWithReAuth] 兜底自愈。 */
        private const val VALIDATE_TTL_MS = 120_000L
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

class LoginCooldownException(
    val siteName: String,
    val retryAfterSeconds: Long,
) : IOException("${siteName}登录刚刚失败，请 ${retryAfterSeconds} 秒后再试")
